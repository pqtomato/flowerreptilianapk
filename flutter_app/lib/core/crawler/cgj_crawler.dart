import 'package:html/dom.dart';
import 'package:html/parser.dart' as html_parser;

import '../common/date_utils.dart';
import '../common/region_names.dart';
import '../models.dart';
import '../net/http_fetcher.dart';
import '../settings.dart';
import 'site_crawler.dart';

/// 安庆市城市管理局「通知公告」采集器（https://cgj.anqing.gov.cn/xxfb/tzgg/）。
///
/// 列表是服务端渲染的静态 HTML，直接解析 `ul.doc_list li`：
/// ```html
/// <ul class="doc_list list-14015578">
///   <li><a href="..." title="标题"><span>标题</span></a>
///       <span class="right date">2026-09-17</span></li>
/// </ul>
/// ```
/// 翻页方式由站点脚本给出：`/content/column/14015578?pageIndex=N`，页码从 1 开始。
class CgjCrawler extends SiteCrawler {
  CgjCrawler(this.settings, this.httpFetcher, {this.onWarn});

  static const String code = 'cgj';
  static const String _categoryName = '通知公告';

  final CrawlerSettings settings;
  final HttpFetcher httpFetcher;
  final void Function(String message)? onWarn;

  @override
  String get siteCode => code;

  @override
  String get siteName => settings.sites.cgj.name;

  @override
  bool get enabled => settings.sites.cgj.enabled;

  @override
  Future<List<CrawlItem>> crawl(CrawlContext context) async {
    final config = settings.sites.cgj;
    final baseUrl = config.baseUrl;
    final listUrl = baseUrl + config.listUrl;

    final items = <CrawlItem>[];
    final seenUrls = <String>{};

    for (var page = 1; page <= config.maxPages; page++) {
      final pageUrl = page == 1
          ? listUrl
          : '$baseUrl${config.columnUrl}?pageIndex=$page';

      String body;
      try {
        body = await httpFetcher.getText(pageUrl, referer: listUrl);
      } catch (e) {
        onWarn?.call('[$siteName] 列表页第 $page 页抓取失败：$e');
        context.log('列表页第 $page 页抓取失败：$e');
        if (page == 1 && items.isEmpty) {
          // 首页就拿不到数据 → 整站失败（快速失败，避免静默返回 0 条）
          throw CrawlException('列表首页抓取失败：$e');
        }
        break;
      }

      final pageItems = _parseListPage(body, pageUrl, config.name, seenUrls);
      items.addAll(pageItems);
      if (pageItems.isEmpty) break;
    }
    return items;
  }

  List<CrawlItem> _parseListPage(
    String body,
    String pageUrl,
    String siteName,
    Set<String> seenUrls,
  ) {
    final items = <CrawlItem>[];
    if (body.trim().isEmpty) return items;

    Document doc;
    try {
      doc = html_parser.parse(body);
    } catch (e) {
      onWarn?.call('[$siteName] 列表页解析失败：$pageUrl（$e）');
      return items;
    }
    var rows = doc.querySelectorAll('ul.doc_list li');
    if (rows.isEmpty) {
      rows = doc.querySelectorAll('div.ls-page-container li');
    }

    for (final row in rows) {
      final link = row.querySelector('a[href]');
      if (link == null) continue;

      final attrTitle = link.attributes['title'];
      var title = attrTitle != null && attrTitle.trim().isNotEmpty
          ? attrTitle
          : link.text;
      title = title.replaceAll(' ', ' ').trim();
      if (title.isEmpty) continue;

      final url = _absoluteUrl(link.attributes['href'] ?? '', pageUrl);
      if (url.isEmpty || url.startsWith('javascript')) continue;
      if (!seenUrls.add(url)) continue;

      var dateElement = row.querySelector('span.right.date');
      dateElement ??= row.querySelector('span.date');

      final item = CrawlItem(code, siteName)
        ..title = title
        ..url = url
        ..category = _categoryName
        ..categoryCode = ''
        ..publishDate = dateElement == null
            ? DateUtils.parseFromText(row.text)
            : DateUtils.parse(dateElement.text.trim())
        // 该站没有地区字段，只能从标题推断
        ..region = RegionNames.guessFromTitle(title);
      items.add(item);
    }
    return items;
  }

  static String _absoluteUrl(String href, String baseUrl) {
    final raw = href.trim();
    if (raw.isEmpty) return '';
    final own = Uri.tryParse(raw);
    if (own != null && own.hasScheme) return raw;
    final base = Uri.tryParse(baseUrl);
    if (base == null) return raw;
    try {
      return base.resolve(raw).toString();
    } catch (_) {
      return raw;
    }
  }
}
