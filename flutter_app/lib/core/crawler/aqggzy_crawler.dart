import '../common/category_names.dart';
import '../common/region_names.dart';
import '../models.dart';
import '../settings.dart';
import 'epoint.dart';
import 'site_crawler.dart';

/// 安庆市公共资源交易服务网（https://aqggzy.anqing.gov.cn/）采集器。
///
/// 列表页由前端 Mustache 模板 + AJAX 渲染，直接抓 HTML 拿不到数据，因此走站内检索接口，
/// 按关键词逐词检索。详情页地址按 epoint 目录约定拼接：
/// `/jyxx/{分类前6位}/{分类全码}/{yyyyMMdd}/{infoid}.html`
class AqggzyCrawler extends SiteCrawler {
  AqggzyCrawler(this.settings, this.searchClient);

  static const String code = 'aqggzy';

  final CrawlerSettings settings;
  final EpointSearchClient searchClient;

  @override
  String get siteCode => code;

  @override
  String get siteName => settings.sites.aqggzy.name;

  @override
  bool get enabled => settings.sites.aqggzy.enabled;

  @override
  Future<List<CrawlItem>> crawl(CrawlContext context) async {
    final config = settings.sites.aqggzy;
    final endpoint = config.baseUrl + config.searchPath;
    final lookbackDays = context.resolveLookbackDays(config.lookbackDays);
    final items = <CrawlItem>[];
    final seenUrls = <String>{};

    for (final term in context.searchTerms) {
      for (var page = 0; page < config.maxPagesPerKeyword; page++) {
        final result = await searchClient.search(
          EpointQuery(
            endpoint: endpoint,
            referer: config.referer,
            keyword: term.keyword,
            // 正文词用 title;content 检索，能捞到"标题没写、正文提到"的公告
            fields: term.fields,
            cnum: '',
            pageIndex: page,
            pageSize: config.pageSize,
            contentLength: config.contentLength,
            lookbackDays: lookbackDays,
            unionConditionNull: false,
          ),
        );

        // 快速失败：首次请求就不可解析（典型是被 WAF 拦截），直接判该站点失败，
        // 避免 50 多个关键词逐个白跑重试
        if (page == 0 && items.isEmpty && !result.responseOk) {
          throw const CrawlException('检索接口首次请求即失败（疑似被 WAF 拦截或站点改版），已跳过本站点');
        }
        if (!result.responseOk || result.isEmpty) {
          break;
        }

        for (final record in result.records) {
          if (_isExcluded(
            config.excludedCategoryPrefixes,
            record.categoryCode,
          )) {
            continue;
          }
          final url = _buildDetailUrl(config.baseUrl, record);
          if (url == null || !seenUrls.add(url)) {
            continue;
          }
          items.add(_toItem(config.name, record, url, term.keyword));
        }

        if (result.records.length < config.pageSize) break;
        if ((page + 1) * config.pageSize >= result.totalCount) break;
      }
    }
    return items;
  }

  CrawlItem _toItem(
    String siteName,
    EpointRecord record,
    String url,
    String keyword,
  ) {
    final item = CrawlItem(code, siteName)
      ..title = record.title
      ..url = url
      ..categoryCode = record.categoryCode
      ..category = CategoryNames.resolve(record.categoryCode)
      ..publishDate = record.publishDate
      ..summary = record.content
      ..sourceId = record.infoId.isEmpty ? record.linkUrl : record.infoId
      ..sourceKeyword = keyword;
    final region = RegionNames.byCityCode(record.cityCode);
    item.region = region.isEmpty
        ? RegionNames.guessFromTitle(record.title)
        : region;
    return item;
  }

  /// 把检索结果里的 linkurl 还原成详情页地址。
  String? _buildDetailUrl(String baseUrl, EpointRecord record) {
    final link = record.linkUrl;
    if (link.trim().isEmpty) return null;
    if (link.startsWith('http://') || link.startsWith('https://')) return link;
    if (link.startsWith('/')) return baseUrl + link;

    final category = record.categoryCode;
    final publishDate = record.publishDate;
    if (category.length < 6 || publishDate == null) return null;
    final datePath =
        '${publishDate.year}'
        '${publishDate.month.toString().padLeft(2, '0')}'
        '${publishDate.day.toString().padLeft(2, '0')}';
    return '$baseUrl/jyxx/${category.substring(0, 6)}/$category/$datePath/$link.html';
  }

  /// 兜底链接：站点自身列表页用的跳转地址。
  ///
  /// 该站索引里混有大量转载自「安庆市公共资源交易平台」的县级公告
  /// （categorynum 形如 036001xxx / 005xxx / 004xxx），这些记录在本站没有落地页，
  /// 按目录规则拼出来的地址必然 404，所以改用站点自己的 tiaozhuan.html。
  @override
  String? fallbackDetailUrl(CrawlItem item) {
    final sourceId = item.sourceId;
    if (sourceId.trim().isEmpty) return null;
    final category = item.categoryCode;
    return '${settings.sites.aqggzy.baseUrl}'
        '/tiaozhuan.html?infoid=$sourceId&categorynum=$category';
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
