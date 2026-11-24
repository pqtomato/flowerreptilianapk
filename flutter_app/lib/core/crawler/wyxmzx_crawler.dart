import '../common/category_names.dart';
import '../common/date_utils.dart';
import '../common/region_names.dart';
import '../models.dart';
import '../settings.dart';
import 'epoint.dart';
import 'site_crawler.dart';

/// 安庆市阳光采购交易服务平台（http://www.wyxmzx.com/jyxx/）采集器。
///
/// 列表页原本走 epoint 站内检索接口（`cnum=080`）；2026-11-24 起该接口会忽略
/// 关键词，只按 `webdate` 倒序返回全部公告，所以采集改成翻页列表，
/// 关键词匹配交给 CrawlEngine。接口在 `cl` 长度内直接返回正文，
/// 大部分记录不需要回源详情页。
///
/// 必须注意的坑（前两个已在 HttpFetcher 里处理）：
/// - 带 `Origin` 头 → 503「疑似跨站请求伪造」；
/// - 带 `Referer` 头 → 400。所以该站 referer 配置必须为空；
/// - 2026-11-24 实测：站点的 https vhost 只剩 nginx 默认页，检索接口与详情页都 404，
///   只有 http 正常；
/// - 同一日期起，检索接口会**忽略 `wd`**（任意关键词都返回同一批记录）、
///   `cnum=080` 直接返回空结构、带 `time` 过滤返回 0 条；
///   只有 `fields` 为空串时才有结果。现在该接口实际是「按发布日期倒序的公告列表」，
///   所以这里按列表翻页采集，时间窗在本地过滤，关键词交给引擎去匹配。
class WyxmzxCrawler extends SiteCrawler {
  WyxmzxCrawler(this.settings, this.searchClient);

  static const String code = 'wyxmzx';

  final CrawlerSettings settings;
  final EpointSearchClient searchClient;

  @override
  String get siteCode => code;

  @override
  String get siteName => settings.sites.wyxmzx.name;

  @override
  bool get enabled => settings.sites.wyxmzx.enabled;

  @override
  Future<List<CrawlItem>> crawl(CrawlContext context) async {
    final config = settings.sites.wyxmzx;
    final endpoint = config.baseUrl + config.searchPath;
    final lookbackDays = context.resolveLookbackDays(config.lookbackDays);
    // 接口的 time 过滤会让结果变成 0 条，时间窗在本地过
    final since = lookbackDays > 0
        ? DateUtils.nowCn().subtract(Duration(days: lookbackDays))
        : null;
    final items = <CrawlItem>[];
    final seenUrls = <String>{};

    for (var page = 0; page < config.maxPages; page++) {
      final result = await searchClient.search(
        EpointQuery(
          endpoint: endpoint,
          referer: config.referer,
          // 接口忽略关键词，按列表翻页；关键词匹配交给 CrawlEngine
          keyword: '',
          // 该站接口只有 fields 为空串时才返回结果（填 title 会 0 条）
          fields: '',
          // 该站 cnum=080 已失效，空串才返回全部
          cnum: config.cnum,
          pageIndex: page,
          pageSize: config.pageSize,
          contentLength: config.contentLength,
          // 不传时间窗：带上 time 过滤后该站接口返回空结果
          lookbackDays: 0,
          // 该站接口要求 unionCondition 为 null 而非空数组
          unionConditionNull: true,
        ),
      );

      if (page == 0 && items.isEmpty && !result.responseOk) {
        throw const CrawlException('检索接口首次请求即失败（疑似被 WAF 拦截），已跳过本站点');
      }
      if (!result.responseOk || result.isEmpty) {
        break;
      }

      // 整页都早于时间窗就收工（记录按 webdate 倒序）
      var allBeforeWindow = since != null;
      for (final record in result.records) {
        final published = record.publishDate;
        if (published == null || since == null || !published.isBefore(since)) {
          allBeforeWindow = false;
        }
        if (_isExcluded(config.excludedCategoryPrefixes, record.categoryCode)) {
          continue;
        }
        // 本地时间窗（接口不支持 time 过滤）
        if (since != null && published != null && published.isBefore(since)) {
          continue;
        }
        final url = _buildDetailUrl(config.baseUrl, record);
        if (url == null || !seenUrls.add(url)) {
          continue;
        }

        final item = CrawlItem(code, config.name)
          ..title = record.title
          ..url = url
          ..categoryCode = record.categoryCode
          ..category = CategoryNames.resolve(record.categoryCode)
          ..publishDate = record.publishDate
          ..summary = record.content
          ..sourceId = record.infoId.isEmpty ? record.linkUrl : record.infoId;
        final region = RegionNames.byCityCode(record.cityCode);
        item.region = region.isEmpty
            ? RegionNames.guessFromTitle(record.title)
            : region;
        items.add(item);
      }

      if (allBeforeWindow) break;
      if (result.records.length < config.pageSize) break;
      if ((page + 1) * config.pageSize >= result.totalCount) break;
    }
    return items;
  }

  String? _buildDetailUrl(String baseUrl, EpointRecord record) {
    final link = record.linkUrl;
    if (link.trim().isEmpty) return null;
    if (link.startsWith('http://') || link.startsWith('https://')) return link;
    if (link.startsWith('/')) return baseUrl + link;
    return '$baseUrl/$link';
  }

  bool _isExcluded(List<String> prefixes, String categoryCode) {
    if (categoryCode.isEmpty) return false;
    for (final prefix in prefixes) {
      if (prefix.trim().isNotEmpty && categoryCode.startsWith(prefix.trim())) {
        return true;
      }
    }
    return false;
  }
}
