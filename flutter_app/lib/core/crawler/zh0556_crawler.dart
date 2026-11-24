import 'package:html/dom.dart';
import 'package:html/parser.dart' as html_parser;

import '../common/date_utils.dart';
import '../common/region_names.dart';
import '../models.dart';
import '../net/http_fetcher.dart';
import '../settings.dart';
import 'site_crawler.dart';

/// 安庆交控集团集采平台采集器（https://jc.zh0556.com/）。
///
/// 与另外三个站点不同：这里没有检索接口，也没有关键词参数，只能按
/// 「类目 × 项目类型」翻静态列表页发现公告。列表项结构：
/// ```html
/// <li>
///   <span class="status">[报名已结束]</span>
///   <a href="/zbshow/886">标题</a>
///   <span class="time">[2026-10-29]</span>
/// </li>
/// ```
///
/// 几个必须注意的点：
/// - 列表页里的导航也是 `<li>`，所以只能限定在 `div.projectlist` 里选；
/// - `[报名已结束]` 是站点自己算的，不写进截止日期，避免把"状态"当"日期"；
/// - 详情页 `<title>` 永远是站点名，公告标题只能取列表 `<a>` 的文本；
/// - 面包屑会把比选公告写成「中标公示」，文档类型由 CrawlEngine 按标题判定。
class Zh0556Crawler extends SiteCrawler {
  Zh0556Crawler(this.settings, this.httpFetcher, {this.onWarn});

  static const String code = 'zh0556';

  /// 类目 1 / 2 的中文名
  static const String _categoryBid = '招标采购';
  static const String _categoryNonBid = '非招采购';

  /// 从 /zbshow/886、/cjshow/123 这类地址里取数字 ID
  static final RegExp _sourceId = RegExp(r'/(?:[a-z]+)?show/(\d+)');

  final CrawlerSettings settings;
  final HttpFetcher httpFetcher;
  final void Function(String message)? onWarn;

  @override
  String get siteCode => code;

  @override
  String get siteName => settings.sites.zh0556.name;

  @override
  bool get enabled => settings.sites.zh0556.enabled;

  @override
  Future<List<CrawlItem>> crawl(CrawlContext context) async {
    final config = settings.sites.zh0556;
    final homeUrl = '${config.baseUrl}/';

    final lookback = context.resolveLookbackDays(config.lookbackDays);
    final cutoff = lookback > 0
        ? DateUtils.nowCn().subtract(Duration(days: lookback))
        : null;

    final items = <CrawlItem>[];
    final seenUrls = <String>{};
    var firstRequest = true;

    for (final categoryId in config.categoryIds) {
      for (final typeId in config.typeIds) {
        for (var page = 1; page <= config.maxPagesPerCategory; page++) {
          final pageUrl = _buildListUrl(config, categoryId, typeId, page);
          // 注意：HttpFetcher 对 4xx/5xx 不抛异常，只返回响应，必须自己判状态；
          // 否则站点挂掉时会静默返回 0 条，看起来像"今天没有新公告"。
          String? body;
          String? failure;
          try {
            final response = await httpFetcher.get(pageUrl, referer: homeUrl);
            if (response.isSuccess) {
              body = response.text;
            } else {
              failure = 'HTTP ${response.status}';
            }
          } catch (e) {
            failure = e.toString();
          }
          if (failure != null) {
            if (firstRequest) {
              // 第一条列表请求就失败 → 整站失败，避免静默返回 0 条
              throw CrawlException('列表首页抓取失败：$failure');
            }
            onWarn?.call('[$siteName] 列表页抓取失败：$pageUrl（$failure）');
            context.log(
              '列表页抓取失败（类目 $categoryId / 类型 $typeId '
              '第 $page 页）：$failure',
            );
            break;
          }
          firstRequest = false;

          final pageItems = _parseListPage(
            body ?? '',
            pageUrl,
            categoryId,
            typeId,
            cutoff,
            seenUrls,
          );
          items.addAll(pageItems);
          if (pageItems.isEmpty) {
            // 空页（或整页都在时间窗之外）→ 该组合不再往后翻
            break;
          }
        }
      }
    }
    return items;
  }

  String _buildListUrl(
    Zh0556Site config,
    int categoryId,
    int typeId,
    int page,
  ) {
    final sb = StringBuffer(config.baseUrl)
      ..write(config.listPath)
      ..write('?category_id=')
      ..write(categoryId)
      ..write('&type_id=')
      ..write(typeId)
      ..write('&path=zb');
    if (page > 1) {
      sb.write('&page=$page');
    }
    return sb.toString();
  }

  List<CrawlItem> _parseListPage(
    String body,
    String pageUrl,
    int categoryId,
    int typeId,
    DateTime? cutoff,
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

    // 导航栏也是 <li>，必须限定在项目列表容器里
    var rows = doc.querySelectorAll('div.projectlist ul li');
    if (rows.isEmpty) {
      rows = doc.querySelectorAll('div.projectlist li');
    }

    for (final row in rows) {
      final link = row.querySelector('a[href]');
      if (link == null) continue;
      final title = _normalizeText(link.text);
      if (title.isEmpty) continue;

      final url = _absoluteUrl(link.attributes['href'] ?? '', pageUrl);
      if (url.isEmpty || url.startsWith('javascript')) continue;
      if (!seenUrls.add(url)) continue;

      final timeElement = row.querySelector('span.time');
      final publishDate = timeElement == null
          ? null
          : DateUtils.parse(_stripBrackets(_normalizeText(timeElement.text)));

      if (cutoff != null &&
          publishDate != null &&
          publishDate.isBefore(cutoff)) {
        continue;
      }

      final item = CrawlItem(code, siteName)
        ..title = title
        ..url = url
        ..category = categoryId == 2 ? _categoryNonBid : _categoryBid
        ..categoryCode = '$categoryId-$typeId'
        ..publishDate = publishDate
        ..sourceId = _extractSourceId(url) ?? ''
        // 该站没有地区字段，只能从标题推断
        ..region = RegionNames.guessFromTitle(title);
      items.add(item);
    }
    return items;
  }

  /// 日期在列表里带方括号：[2026-10-29]
  static String _stripBrackets(String text) {
    return text
        .replaceAll('[', '')
        .replaceAll(']', '')
        .replaceAll('【', '')
        .replaceAll('】', '')
        .trim();
  }

  static String _normalizeText(String text) {
    return text.replaceAll(' ', ' ').replaceAll(RegExp(r'\s+'), ' ').trim();
  }

  static String? _extractSourceId(String url) {
    final match = _sourceId.firstMatch(url);
    return match?.group(1);
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
