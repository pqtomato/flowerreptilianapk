import '../models.dart';

/// 站点采集器：只负责「发现公告」，不负责关键词过滤与落库。
abstract class SiteCrawler {
  String get siteCode;

  String get siteName;

  bool get enabled;

  Future<List<CrawlItem>> crawl(CrawlContext context);

  /// 详情页打不开时的兜底链接。
  ///
  /// 有些站点（交易服务网）的索引里存在「已转载但没有落地页」的记录，拼出来的详情地址
  /// 必然 404。此时返回站点自己的规范跳转链接，至少不会给用户死链。
  String? fallbackDetailUrl(CrawlItem item) => null;
}

/// 采集过程中的可预期错误（列表首页抓不到、检索接口被 WAF 拦截等）。
///
/// 与普通异常区分开：引擎会把它当作「整站失败」写进日志，而不是静默返回 0 条。
class CrawlException implements Exception {
  final String message;

  const CrawlException(this.message);

  @override
  String toString() => message;
}
