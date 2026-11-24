import 'package:html/dom.dart';
import 'package:html/parser.dart' as html_parser;

import '../common/date_utils.dart';
import '../models.dart';
import '../settings.dart';

/// 三站通用的详情页解析器。
///
/// 三个站点都是政务 / epoint 站群，正文容器命名各不相同，因此采用
/// 「候选选择器列表 + 取文本最长者」的策略，并对老页面做「文本密度最大块」兜底。
class DetailParser {
  DetailParser(this.settings, {this.onWarn});

  final CrawlerSettings settings;
  final void Function(String message)? onWarn;

  static const List<String> _titleSelectors = <String>[
    'h1.ls-article-title',
    'div.sub-detail-title',
    'div.notice-tit',
    'h1.article-title',
    'div.article-title',
    'h1',
    'h2.title',
  ];

  static const List<String> _contentSelectors = <String>[
    'div.notice-content',
    'div.ls-article-info',
    'div.sub-detail-content',
    'div.ewb-article',
    'div.article-content',
    'div.bid-box',
    'div.public-style',
    '#content',
    'div.content',
    'article',
  ];

  static const List<String> _dateSelectors = <String>[
    'div.ls-article-menu',
    'div.sub-detail-info',
    'div.article-source',
    'div.notice-info',
    'div.info',
    'div.source',
  ];

  static const List<String> _attachmentSelectors = <String>[
    'a.enclosure-name',
    'div.enclosure-bd a',
    'div.sub-file-title a',
    'div.sub-detail-file a',
  ];

  static final RegExp _fileLink = RegExp(
    r'\.(pdf|docx?|xlsx?|zip|rar|7z|txt)(\?|$)',
    caseSensitive: false,
  );

  static const int _minAcceptableContent = 200;
  static const int _maxAttachments = 30;

  /// 解析详情页。
  ///
  /// [html] 是已按页面 charset 解码好的正文（由 HttpFetcher 负责），
  /// [baseUrl] 用于补全相对链接。
  DetailResult parse(String html, String baseUrl) {
    if (html.trim().isEmpty) return DetailResult.empty;

    Document doc;
    try {
      doc = html_parser.parse(html);
    } catch (e) {
      onWarn?.call('详情页解析失败: $baseUrl ($e)');
      return DetailResult.empty;
    }
    for (final element in doc.querySelectorAll('script, style, noscript')) {
      element.remove();
    }

    final title = _extractTitle(doc);
    final publishDate = _extractDate(doc);
    final contentElement = _extractContentElement(doc);
    final baseUri = _baseUriOf(doc, baseUrl);

    String contentText = '';
    String contentHtml = '';
    if (contentElement != null) {
      contentText = _truncate(_normalizeText(contentElement.text));
      contentHtml = _truncate(contentElement.innerHtml);
    }
    final attachments = _extractAttachments(doc, contentElement, baseUri);
    return DetailResult(
      title: title,
      publishDate: publishDate,
      contentText: contentText,
      contentHtml: contentHtml,
      attachments: attachments,
    );
  }

  String _extractTitle(Document doc) {
    for (final selector in _titleSelectors) {
      final element = doc.querySelector(selector);
      if (element == null) continue;
      final attrTitle = element.attributes['title'];
      final candidate = _normalizeText(
        attrTitle != null && attrTitle.trim().isNotEmpty
            ? attrTitle
            : element.text,
      );
      if (candidate.length >= 4) {
        return candidate;
      }
    }
    return _stripSiteSuffix(
      _normalizeText(doc.querySelector('title')?.text ?? ''),
    );
  }

  /// 去掉「标题_站点名」「标题 - 站点名」这类后缀。
  String _stripSiteSuffix(String title) {
    if (title.trim().isEmpty) return title;
    const separators = <String>['_', ' - ', '—', '|'];
    for (final separator in separators) {
      final index = title.lastIndexOf(separator);
      if (index > 4 && title.length - index < 30) {
        return title.substring(0, index).trim();
      }
    }
    return title;
  }

  DateTime? _extractDate(Document doc) {
    final infodate = doc.querySelector('input#infodate');
    if (infodate != null) {
      final value = DateUtils.parse(infodate.attributes['value']);
      if (value != null) return value;
    }
    final published = doc.querySelector('input#publishdate');
    if (published != null) {
      final value = DateUtils.parse(published.attributes['value']);
      if (value != null) return value;
    }
    for (final selector in _dateSelectors) {
      final element = doc.querySelector(selector);
      if (element == null) continue;
      final value = DateUtils.parseFromText(element.text);
      if (value != null) return value;
    }
    final body = doc.querySelector('body');
    if (body != null) {
      var head = body.text;
      if (head.length > 1500) head = head.substring(0, 1500);
      return DateUtils.parseFromText(head);
    }
    return null;
  }

  /// 正文容器：候选选择器中文本最长者；都太短时用「文本密度最大」的块兜底。
  Element? _extractContentElement(Document doc) {
    Element? best;
    var bestLength = 0;
    for (final selector in _contentSelectors) {
      for (final element in doc.querySelectorAll(selector)) {
        final length = element.text.length;
        if (length > bestLength) {
          bestLength = length;
          best = element;
        }
      }
      if (bestLength >= _minAcceptableContent) return best;
    }
    if (bestLength >= _minAcceptableContent) return best;

    Element? densest;
    var densestLength = 0;
    for (final element in doc.querySelectorAll('div, article, section, td')) {
      final ownLength = _ownTextLength(element);
      if (ownLength > densestLength) {
        densestLength = ownLength;
        densest = element;
      }
    }
    if (densest != null && densestLength > bestLength) return densest;
    return best;
  }

  int _ownTextLength(Element element) {
    var length = 0;
    for (final node in element.nodes) {
      if (node is Text) length += node.data.length;
    }
    return length;
  }

  /// 相对链接补全用的基准地址（优先页面里的 `<base href>`）。
  Uri? _baseUriOf(Document doc, String baseUrl) {
    final base = Uri.tryParse(baseUrl);
    final tag = doc.querySelector('base[href]');
    final href = tag?.attributes['href'];
    if (href == null || href.trim().isEmpty) return base;
    final own = Uri.tryParse(href);
    if (own == null) return base;
    if (own.hasScheme) return own;
    return base?.resolveUri(own) ?? own;
  }

  List<String> _extractAttachments(
    Document doc,
    Element? contentElement,
    Uri? baseUri,
  ) {
    final result = <String>{};
    for (final selector in _attachmentSelectors) {
      for (final link in doc.querySelectorAll(selector)) {
        _addAttachment(result, link, baseUri);
      }
    }
    if (contentElement != null) {
      for (final link in contentElement.querySelectorAll('a[href]')) {
        final href = link.attributes['href'] ?? '';
        if (_fileLink.hasMatch(href) || _fileLink.hasMatch(link.text)) {
          _addAttachment(result, link, baseUri);
        }
      }
    }
    if (result.length < _maxAttachments) {
      for (final link in doc.querySelectorAll('a[href]')) {
        if (result.length >= _maxAttachments) break;
        if (_fileLink.hasMatch(link.attributes['href'] ?? '')) {
          _addAttachment(result, link, baseUri);
        }
      }
    }
    return result.toList();
  }

  void _addAttachment(Set<String> collector, Element link, Uri? baseUri) {
    if (collector.length >= _maxAttachments) return;
    final raw = link.attributes['href'] ?? '';
    final absolute = _absoluteUrl(raw, baseUri);
    if (absolute.isEmpty || absolute.startsWith('javascript')) return;
    var name = _normalizeText(link.text);
    if (name.isEmpty) {
      name = absolute.substring(absolute.lastIndexOf('/') + 1);
    }
    collector.add('$name :: $absolute');
  }

  static String _absoluteUrl(String href, Uri? baseUri) {
    final raw = href.trim();
    if (raw.isEmpty || raw.startsWith('javascript')) return '';
    final own = Uri.tryParse(raw);
    if (own != null && own.hasScheme) return raw;
    if (baseUri == null) return raw;
    try {
      return baseUri.resolve(raw).toString();
    } catch (_) {
      return raw;
    }
  }

  String _normalizeText(String text) {
    return text
        .replaceAll(' ', ' ')
        .replaceAll(RegExp(r'[\t\x0B\f\r]+'), ' ')
        .replaceAll(RegExp(r' {2,}'), ' ')
        .replaceAll(RegExp(r'(\s*\n\s*)+'), '\n')
        .trim();
  }

  String _truncate(String text) {
    final max = settings.detail.maxContentLength;
    if (max > 0 && text.length > max) {
      return text.substring(0, max);
    }
    return text;
  }
}
