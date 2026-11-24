import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';
import 'dart:typed_data';

import 'package:charset_converter/charset_converter.dart';

import '../settings.dart';

/// 一次 HTTP 请求的结果（字节已解压，文本已按 charset 解码）。
class FetchResponse {
  final int status;
  final Uint8List bytes;
  final String text;
  final String contentType;
  final String finalUrl;

  const FetchResponse({
    required this.status,
    required this.bytes,
    required this.text,
    this.contentType = '',
    this.finalUrl = '',
  });

  bool get isSuccess => status >= 200 && status < 300;

  int get size => bytes.length;

  static final FetchResponse empty = FetchResponse(
    status: 0,
    bytes: Uint8List(0),
    text: '',
  );
}

/// 请求最终失败（所有重试耗尽）。
class HttpFetchException implements Exception {
  final String message;

  const HttpFetchException(this.message);

  @override
  String toString() => message;
}

/// 统一抓取器：UA 轮换、同域名限速、失败退避重试、WAF cookie 预热、证书错误自动降级。
///
/// 与 Java 内核的 [HttpFetcher] 保持同一套策略，只是把网络发送换成了 `dart:io`
/// 的 [HttpClient]（安卓上同样可用，不需要额外依赖）。
class HttpFetcher {
  HttpFetcher(this.settings, {this.onWarn, this.onInfo, this.onDebug}) {
    insecureHosts.addAll(settings.request.insecureTlsHosts);
    _client = HttpClient()
      ..connectionTimeout = Duration(
        seconds: settings.request.connectTimeoutSeconds,
      )
      // 仅在域名被列入 insecureHosts 时放行证书错误。先失败一次 → 加进集合 → 重试，
      // 与 Java 版「证书错误自动降级」的行为一致，默认仍然严格校验证书。
      // 同时看运行时集合与配置：设置页可能在抓取器创建后再改 insecureTlsHosts
      ..badCertificateCallback =
          (X509Certificate cert, String host, int port) =>
              insecureHosts.contains(host) ||
              settings.request.insecureTlsHosts.contains(host);
  }

  final CrawlerSettings settings;
  final void Function(String message)? onWarn;
  final void Function(String message)? onInfo;
  final void Function(String message)? onDebug;

  /// 跳过证书校验的域名集合。默认来自配置，运行时遇到证书错误会自动补进来。
  final Set<String> insecureHosts = <String>{};

  late final HttpClient _client;
  final Random _random = Random();

  /// 每个域名的串行队列，保证同域名请求按最小间隔顺序发出
  final Map<String, Future<void>> _hostTails = <String, Future<void>>{};
  final Map<String, DateTime> _hostLastRequestAt = <String, DateTime>{};

  /// 已预热过的域名（每域名一次）
  final Set<String> _warmedHosts = <String>{};

  /// 手动维护的 cookie（按域名）。WAF 下发的 cookie 必须原样带回，否则 403。
  final Map<String, Map<String, String>> _cookies =
      <String, Map<String, String>>{};

  int _userAgentCursor = 0;
  int _requestCount = 0;

  int get requestCount => _requestCount;

  void resetRequestCount() {
    _requestCount = 0;
  }

  Duration get _timeout => Duration(seconds: settings.request.timeoutSeconds);

  Future<Uint8List> getBytes(String url, {String? referer}) async =>
      (await get(url, referer: referer)).bytes;

  Future<String> getText(String url, {String? referer}) async =>
      (await get(url, referer: referer)).text;

  Future<FetchResponse> get(String url, {String? referer}) =>
      _execute('GET', url, null, referer);

  Future<FetchResponse> postJson(
    String url,
    String jsonBody, {
    String? referer,
  }) => _execute('POST', url, jsonBody, referer);

  void clearCookies(String host) {
    _cookies.remove(host);
  }

  void close() {
    _client.close(force: true);
  }

  Future<FetchResponse> _execute(
    String method,
    String url,
    String? jsonBody,
    String? referer,
  ) async {
    final maxAttempts = max(1, settings.request.retryTimes);
    final backoff = settings.request.retryBackoffMillis;
    Object? lastError;
    var certFallbackTried = false;

    for (var attempt = 1; attempt <= maxAttempts; attempt++) {
      await _throttle(url);
      if (settings.request.warmUp) {
        await _warmUpIfNeeded(url);
      }
      try {
        final headers = <String, String>{
          'User-Agent': _nextUserAgent(),
          'Accept':
              'text/html,application/xhtml+xml,application/xml;q=0.9,'
              'application/json;q=0.9,*/*;q=0.8',
          'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
          'Cache-Control': 'no-cache',
        };
        // 刻意不设置 Accept-Encoding：让 HttpClient 自己声明 gzip 并透明解压
        if (referer != null && referer.trim().isNotEmpty) {
          headers['Referer'] = referer;
        }
        Uint8List? payload;
        if (method == 'POST') {
          headers['Content-Type'] = 'application/json;charset=UTF-8';
          payload = Uint8List.fromList(utf8.encode(jsonBody ?? '{}'));
        }

        final response = await _send(method, url, headers, payload);
        final status = response.status;

        if (_isRetryable(status) && attempt < maxAttempts) {
          if (status == 403 && settings.request.warmUp) {
            await _refreshCookies(url);
          }
          final sleep = backoff * attempt + _random.nextInt(400);
          onWarn?.call(
            'HTTP $status 命中重试（第 $attempt/$maxAttempts 次），'
            '${sleep}ms 后重试：$url',
          );
          await Future<void>.delayed(Duration(milliseconds: sleep));
          continue;
        }
        if (_isRetryable(status)) {
          onWarn?.call('HTTP $status 重试耗尽：$url');
        }
        return response;
      } catch (e) {
        if (!certFallbackTried &&
            settings.request.certFallbackToInsecure &&
            _isCertificateError(e)) {
          certFallbackTried = true;
          final host = _hostOf(url);
          clearCookies(host);
          insecureHosts.add(host);
          if (!settings.request.insecureTlsHosts.contains(host)) {
            settings.request.insecureTlsHosts.add(host);
          }
          onWarn?.call(
            '$host 的 HTTPS 证书无法校验（$e），'
            '本次已自动降级为跳过证书校验；已加入 insecureTlsHosts 以便后续直连',
          );
          // 与 Java 版一致：证书降级不消耗重试次数
          attempt--;
          continue;
        }
        lastError = e;
        if (attempt < maxAttempts) {
          await Future<void>.delayed(
            Duration(milliseconds: backoff * attempt + _random.nextInt(400)),
          );
        } else {
          onWarn?.call('请求最终失败（已重试 $maxAttempts 次）：$url -> $e');
        }
      }
    }
    throw HttpFetchException(
      '请求失败: $url（${lastError == null ? '无响应' : lastError.toString()}）',
    );
  }

  bool _isRetryable(int status) =>
      status == 429 || status == 403 || status >= 500;

  /// 首次访问域名前先 GET 一次站点首页，拿 WAF 下发的 cookie。
  Future<void> _warmUpIfNeeded(String url) async {
    final host = _hostOf(url);
    if (!_warmedHosts.add(host)) return;
    await _sendRaw(_homeUrlOf(url), '预热');
  }

  /// 403 时清理 cookie 并重新预热。
  Future<void> _refreshCookies(String url) async {
    final host = _hostOf(url);
    clearCookies(host);
    onInfo?.call('$host 返回 403，已清理 cookie 并重新预热');
    await _sendRaw(_homeUrlOf(url), '重新预热');
  }

  Future<void> _sendRaw(String url, String purpose) async {
    try {
      final headers = <String, String>{
        'User-Agent': _nextUserAgent(),
        'Accept':
            'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
      };
      final response = await _send('GET', url, headers, null);
      onDebug?.call('$purpose $url -> HTTP ${response.status}');
    } catch (e) {
      onDebug?.call('$purpose $url 失败（忽略）：$e');
    }
  }

  /// 真正发请求（含手动重定向，以便捕捉每一跳的 Set-Cookie）。
  Future<FetchResponse> _send(
    String method,
    String url,
    Map<String, String> headers,
    Uint8List? payload,
  ) async {
    var currentUrl = url;
    var currentMethod = method;
    var currentBody = payload;

    for (var redirects = 0; redirects <= 5; redirects++) {
      final uri = Uri.parse(encodeUrl(currentUrl));
      final host = uri.host;
      _requestCount++;

      final request = await _client
          .openUrl(currentMethod, uri)
          .timeout(_timeout);
      headers.forEach((name, value) => request.headers.set(name, value));
      final cookie = _cookieHeader(host);
      if (cookie.isNotEmpty) {
        request.headers.set(HttpHeaders.cookieHeader, cookie);
      }
      if (currentBody != null) {
        request.contentLength = currentBody.length;
        request.add(currentBody);
      }
      final response = await request.close().timeout(_timeout);

      final setCookies = <String>[];
      response.headers.forEach((name, values) {
        if (name.toLowerCase() == HttpHeaders.setCookieHeader) {
          setCookies.addAll(values);
        }
      });
      _storeCookies(host, setCookies);

      final status = response.statusCode;
      final location = response.headers.value(HttpHeaders.locationHeader);
      if (_isRedirect(status) &&
          location != null &&
          location.trim().isNotEmpty) {
        await response.drain<void>().timeout(_timeout);
        currentUrl = uri.resolve(location).toString();
        if (status == 301 || status == 302 || status == 303) {
          if (currentMethod != 'GET' && currentMethod != 'HEAD') {
            currentMethod = 'GET';
            currentBody = null;
            headers.remove('Content-Type');
          }
        }
        continue;
      }

      final bytes = await _readAll(response);
      final contentType = response.headers.contentType?.toString() ?? '';
      final text = await _decodeBody(bytes, contentType);
      return FetchResponse(
        status: status,
        bytes: bytes,
        text: text,
        contentType: contentType,
        finalUrl: currentUrl,
      );
    }
    throw HttpFetchException('重定向次数过多: $url');
  }

  Future<Uint8List> _readAll(HttpClientResponse response) async {
    final builder = BytesBuilder(copy: false);
    await for (final chunk in response.timeout(_timeout)) {
      builder.add(chunk);
    }
    return builder.takeBytes();
  }

  static bool _isRedirect(int status) =>
      status == 301 ||
      status == 302 ||
      status == 303 ||
      status == 307 ||
      status == 308;

  void _storeCookies(String host, List<String> setCookies) {
    if (setCookies.isEmpty) return;
    final jar = _cookies.putIfAbsent(host, () => <String, String>{});
    for (final raw in setCookies) {
      final first = raw.split(';').first;
      final eq = first.indexOf('=');
      if (eq <= 0) continue;
      final name = first.substring(0, eq).trim();
      final value = first.substring(eq + 1).trim();
      if (name.isEmpty) continue;
      if (value.isEmpty) {
        jar.remove(name);
      } else {
        jar[name] = value;
      }
    }
  }

  String _cookieHeader(String host) {
    final jar = _cookies[host];
    if (jar == null || jar.isEmpty) return '';
    return jar.entries.map((entry) => '${entry.key}=${entry.value}').join('; ');
  }

  /// 解析响应文本。
  ///
  /// 先看 `Content-Type` 里的 charset；没有、或声明 UTF-8 时直接按 UTF-8 解
  /// （畸形字节用替换符兜底，避免整页解析失败）。GBK / GB2312 这类国标编码
  /// 走系统转换器（安卓上由平台提供），失败时退回 UTF-8。
  static Future<String> _decodeBody(Uint8List bytes, String contentType) async {
    if (bytes.isEmpty) return '';
    // 很多政务站只在 <meta charset> 里声明编码，响应头不带 charset。
    // Java 版把字节直接交给 Jsoup，由它按 meta 嗅探；这里补上同样的兜底。
    final charset = _charsetOf(contentType) ?? _sniffCharset(bytes);
    if (charset == null) return utf8.decode(bytes, allowMalformed: true);

    final normalized = charset.toLowerCase().replaceAll('_', '-');
    if (normalized == 'utf-8' || normalized == 'utf8') {
      return utf8.decode(bytes, allowMalformed: true);
    }
    try {
      return await CharsetConverter.decode(normalized, bytes);
    } catch (_) {
      // 桌面单测 / 平台不支持时没有转换器，退回 UTF-8（页面内容多为 UTF-8）
      return utf8.decode(bytes, allowMalformed: true);
    }
  }

  static final RegExp _charsetPattern = RegExp(
    'charset\\s*=\\s*["\']?([\\w\\-]+)',
    caseSensitive: false,
  );

  static final RegExp _metaCharsetPattern = RegExp(
    '<meta[^>]+charset\\s*=\\s*["\']?([\\w\\-]+)',
    caseSensitive: false,
  );

  static String? _charsetOf(String contentType) {
    if (contentType.isEmpty) return null;
    final match = _charsetPattern.firstMatch(contentType);
    return match?.group(1);
  }

  /// 从页面头部 2KB 里找 `<meta charset="gbk">`。
  static String? _sniffCharset(Uint8List bytes) {
    final end = bytes.length > 2048 ? 2048 : bytes.length;
    final head = latin1.decode(bytes.sublist(0, end), allowInvalid: true);
    return _metaCharsetPattern.firstMatch(head)?.group(1);
  }

  /// 同域名最小请求间隔控制。
  ///
  /// 用「每域名一条 Future 队列」而不是锁：Dart 单线程下 await 会让出执行权，
  /// 只有把后续请求挂在同一个 Future 上才能保证严格串行。
  Future<void> _throttle(String url) async {
    final host = _hostOf(url);
    final previous = _hostTails[host] ?? Future<void>.value();
    final gate = Completer<void>();
    _hostTails[host] = gate.future;
    try {
      await previous;
      final minInterval = settings.request.minIntervalMillis;
      final last = _hostLastRequestAt[host];
      if (last != null) {
        final wait =
            minInterval - DateTime.now().difference(last).inMilliseconds;
        if (wait > 0) {
          await Future<void>.delayed(
            Duration(milliseconds: wait + _random.nextInt(150)),
          );
        }
      }
      _hostLastRequestAt[host] = DateTime.now();
    } finally {
      if (!gate.isCompleted) gate.complete();
    }
  }

  String _hostOf(String url) {
    final uri = Uri.tryParse(encodeUrl(url));
    final host = uri?.host ?? '';
    return host.isEmpty ? url : host;
  }

  String _homeUrlOf(String url) {
    final uri = Uri.tryParse(encodeUrl(url));
    if (uri == null || uri.scheme.isEmpty || uri.host.isEmpty) return url;
    return '${uri.scheme}://${uri.host}/';
  }

  String _nextUserAgent() {
    final agents = settings.userAgents;
    if (agents.isEmpty) {
      return 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 '
          'Chrome/126.0.0.0 Mobile Safari/537.36';
    }
    final index = _userAgentCursor % agents.length;
    _userAgentCursor++;
    return agents[index];
  }

  /// 判断异常链里是否为证书相关错误（过期 / 主机名不匹配 / 证书链无法构建）。
  static bool _isCertificateError(Object error) {
    if (error is HandshakeException ||
        error is TlsException ||
        error is CertificateException) {
      return true;
    }
    final message = error.toString().toLowerCase();
    if (message.contains('pkix') ||
        message.contains('unable to find valid certification path') ||
        message.contains('no subject alternative') ||
        message.contains('certificate') ||
        message.contains('trust anchor') ||
        message.contains('hostname')) {
      return true;
    }
    if (error is OSError && error.message.isNotEmpty) {
      final inner = error.message.toLowerCase();
      if (inner.contains('certificate') || inner.contains('cert')) return true;
    }
    return false;
  }

  /// 把可能含空格 / 中文 / 竖线的地址转成合法 URI。
  static String encodeUrl(String url) {
    final sb = StringBuffer();
    for (final unit in url.codeUnits) {
      if (unit > 127 || unit == 0x20 || unit == 0x7c) {
        List<int> bytes;
        try {
          bytes = utf8.encode(String.fromCharCode(unit));
        } catch (_) {
          bytes = const <int>[0x3f];
        }
        for (final byte in bytes) {
          sb.write('%');
          sb.write(byte.toRadixString(16).toUpperCase().padLeft(2, '0'));
        }
      } else {
        sb.writeCharCode(unit);
      }
    }
    return sb.toString();
  }
}
