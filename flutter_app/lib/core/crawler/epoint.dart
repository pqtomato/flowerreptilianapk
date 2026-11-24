import 'dart:convert';

import 'package:html/parser.dart' as html_parser;

import '../common/date_utils.dart';
import '../common/text_utils.dart';
import '../net/http_fetcher.dart';

/// 一次站内检索的请求参数。
class EpointQuery {
  final String endpoint;
  final String referer;
  final String keyword;
  final String fields;
  final String cnum;
  final int pageIndex;
  final int pageSize;
  final int contentLength;
  final int lookbackDays;
  final bool unionConditionNull;

  const EpointQuery({
    required this.endpoint,
    required this.referer,
    required this.keyword,
    required this.fields,
    required this.cnum,
    required this.pageIndex,
    required this.pageSize,
    required this.contentLength,
    required this.lookbackDays,
    required this.unionConditionNull,
  });
}

/// 站内检索结果。
///
/// [responseOk] 用来区分「真的没有数据」和「被 WAF 拦截 / 返回了 HTML」，
/// 避免整轮采集静默跑空（这是本项目开发中真实踩过的坑）。
class EpointPage {
  final int totalCount;
  final List<EpointRecord> records;
  final bool responseOk;

  const EpointPage(this.totalCount, this.records, this.responseOk);

  static const EpointPage failed = EpointPage(0, <EpointRecord>[], false);

  static const EpointPage emptyResult = EpointPage(0, <EpointRecord>[], true);

  bool get isEmpty => records.isEmpty;
}

/// 一条检索记录。
class EpointRecord {
  final String title;
  final String categoryCode;
  final DateTime? publishDate;
  final String linkUrl;
  final String infoId;
  final String content;
  final String cityCode;

  const EpointRecord(
    this.title,
    this.categoryCode,
    this.publishDate,
    this.linkUrl,
    this.infoId,
    this.content,
    this.cityCode,
  );
}

/// epoint 站群站内检索接口客户端。
///
/// 安庆市公共资源交易服务网与安庆市阳光采购交易服务平台同属 epoint 站群，
/// 都用 `/inteligentsearch/rest/esinteligentsearch/getFullTextDataNew`。
///
/// 注意：`wd`（关键词）不能为空，否则 totalcount 恒为 0，所以采集是关键词驱动的。
/// 另需注意阳光采购平台的 WAF 会拦截携带 Origin 头的请求（HttpFetcher 从不发送该头）。
class EpointSearchClient {
  EpointSearchClient(this.httpFetcher, {this.onWarn, this.onError});

  final HttpFetcher httpFetcher;
  final void Function(String message)? onWarn;
  final void Function(String message, String? error)? onError;

  Future<EpointPage> search(EpointQuery query) async {
    final payload = <String, Object?>{
      'token': '',
      'pn': query.pageIndex * query.pageSize,
      'rn': query.pageSize,
      'sdt': '',
      'edt': '',
      'wd': query.keyword,
      'inc_wd': '',
      'exc_wd': '',
      'fields': query.fields,
      'cnum': query.cnum,
      'sort': '{"webdate":"0"}',
      'ssort': 'title',
      'cl': query.contentLength,
      'terminal': '',
      'condition': <Object>[],
      'time': _buildTimeFilter(query.lookbackDays),
      'highlights': '',
      'statistics': null,
      'unionCondition': query.unionConditionNull ? null : <Object>[],
      'accuracy': '',
      'noParticiple': '1',
      'searchRange': null,
    };

    String json;
    try {
      json = jsonEncode(payload);
    } catch (e) {
      onError?.call('检索参数序列化失败', e.toString());
      return EpointPage.failed;
    }

    final response = await httpFetcher.postJson(
      query.endpoint,
      json,
      referer: query.referer,
    );
    if (!response.isSuccess) {
      onWarn?.call(
        '检索接口返回 HTTP ${response.status}：${query.endpoint}'
        '（关键词=${query.keyword}）',
      );
      return EpointPage.failed;
    }
    return _parse(response, query);
  }

  EpointPage _parse(FetchResponse response, EpointQuery query) {
    Object? root;
    try {
      root = jsonDecode(response.text);
    } catch (_) {
      var preview = response.text;
      if (preview.length > 160) preview = preview.substring(0, 160);
      onWarn?.call(
        '检索接口返回非 JSON（关键词=${query.keyword}）：'
        '${preview.replaceAll(RegExp(r'\s+'), ' ')}',
      );
      return EpointPage.failed;
    }

    if (root is! Map<String, dynamic>) {
      onWarn?.call('检索接口返回结构异常（关键词=${query.keyword}）');
      return EpointPage.failed;
    }
    final result = root['result'];
    if (result is! Map<String, dynamic>) {
      final status = root['status'];
      if (status is Map<String, dynamic>) {
        onWarn?.call('检索接口返回异常状态：${_text(status, 'text')}');
      }
      return EpointPage.failed;
    }

    final totalCount = _asLong(result['totalcount'], 0);

    final records = <EpointRecord>[];
    final seen = <String>{};
    final array = result['records'];
    if (array is List) {
      for (final node in array) {
        if (node is! Map<String, dynamic>) continue;
        final linkUrl = _text(node, 'linkurl');
        final infoId = _text(node, 'infoid');
        final dedupeKey = infoId.isNotEmpty ? infoId : linkUrl;
        if (dedupeKey.isEmpty || !seen.add(dedupeKey)) continue;
        records.add(
          EpointRecord(
            _cleanTitle(_text(node, 'title')),
            _text(node, 'categorynum'),
            DateUtils.parse(_text(node, 'webdate')),
            linkUrl,
            infoId,
            _cleanContent(_text(node, 'content')),
            _text(node, 'citycode'),
          ),
        );
      }
    }
    return EpointPage(totalCount, records, true);
  }

  /// 时间范围过滤：不加窗口时关键词检索会返回大量早已过期的老公告
  /// （实测 wyxmzx 搜「绿化」会带出 2024 年的记录），既浪费详情页配额又污染
  /// 「还能不能投」的判断。
  static List<Map<String, Object>> _buildTimeFilter(int lookbackDays) {
    if (lookbackDays <= 0) return <Map<String, Object>>[];
    final start = DateUtils.nowCn().subtract(Duration(days: lookbackDays));
    return <Map<String, Object>>[
      <String, Object>{
        'fieldName': 'webdate',
        'startTime': _formatDateTime(start),
        'endTime': '2999-12-31 23:59:59',
      },
    ];
  }

  static String _formatDateTime(DateTime value) {
    String two(int v) => v.toString().padLeft(2, '0');
    return '${value.year}-${two(value.month)}-${two(value.day)} '
        '${two(value.hour)}:${two(value.minute)}:${two(value.second)}';
  }

  static String _text(Map<String, dynamic> node, String field) {
    final value = node[field];
    if (value == null) return '';
    return value.toString();
  }

  static int _asLong(Object? node, int defaultValue) {
    if (node == null) return defaultValue;
    if (node is num) return node.toInt();
    final parsed = int.tryParse(node.toString().trim());
    return parsed ?? defaultValue;
  }

  /// 检索结果里的标题可能带高亮 HTML 标签，清掉。
  static String _cleanTitle(String raw) {
    if (raw.trim().isEmpty) return '';
    if (!raw.contains('<')) return raw.trim();
    return (html_parser.parseFragment(raw).text ?? '').trim();
  }

  /// 正文片段里带 &nbsp; 之类的实体，统一还原并压缩空白。
  static String _cleanContent(String raw) {
    if (raw.trim().isEmpty) return '';
    // 片段里还可能有 <em> 高亮标签，一并还原成纯文本
    final text = html_parser.parseFragment(raw).text ?? '';
    return TextUtils.normalize(text);
  }
}
