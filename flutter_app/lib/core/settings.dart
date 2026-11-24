/// 采集配置（字段与 Java 内核一一对应）。
class CrawlerSettings {
  bool enabled = true;
  String timezone = 'Asia/Shanghai';

  List<String> userAgents = [
    'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36',
    'Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36',
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36',
  ];

  final RequestConfig request = RequestConfig();
  final FilterConfig filter = FilterConfig();
  final DetailConfig detail = DetailConfig();
  final DeadlineConfig deadline = DeadlineConfig();
  final VerifyConfig verify = VerifyConfig();
  final SitesConfig sites = SitesConfig();
}

class RequestConfig {
  int connectTimeoutSeconds = 15;
  int timeoutSeconds = 25;
  int retryTimes = 3;
  int retryBackoffMillis = 1200;
  int minIntervalMillis = 700;
  int maxDetailPerRun = 300;
  bool warmUp = true;
  List<String> insecureTlsHosts = ['www.wyxmzx.com'];
  bool certFallbackToInsecure = true;
}

class FilterConfig {
  String titleKeywordsFile = 'assets/keywords/flower-keywords.txt';
  String contentKeywordsFile = 'assets/keywords/flower-core-keywords.txt';
  String excludeKeywordsFile = 'assets/keywords/exclude-keywords.txt';
  bool matchTitle = true;
  bool matchContent = true;
  List<String> extraTitleKeywords = [];
  List<String> extraContentKeywords = [];
  List<String> excludeKeywords = [];
}

class DetailConfig {
  bool enabled = true;
  int maxContentLength = 30000;
}

class DeadlineConfig {
  bool parseFromContent = true;
  int maxDays = 400;
}

class VerifyConfig {
  bool enabled = true;
  int limit = 120;
  int skipHours = 12;
}

class SitesConfig {
  final AqggzySite aqggzy = AqggzySite();
  final CgjSite cgj = CgjSite();
  final WyxmzxSite wyxmzx = WyxmzxSite();
  final Zh0556Site zh0556 = Zh0556Site();
}

class AqggzySite {
  bool enabled = true;
  String name = '安庆市公共资源交易服务网';
  String baseUrl = 'https://aqggzy.anqing.gov.cn';
  String searchPath = '/inteligentsearch/rest/esinteligentsearch/getFullTextDataNew';
  String referer = 'https://aqggzy.anqing.gov.cn/jyxx/003001/construction.html';
  int pageSize = 30;
  int maxPagesPerKeyword = 2;
  int contentLength = 2000;
  int lookbackDays = 30;
  List<String> excludedCategoryPrefixes = ['002'];
}

class CgjSite {
  bool enabled = true;
  String name = '安庆市城市管理局-通知公告';
  String baseUrl = 'https://cgj.anqing.gov.cn';
  String listUrl = '/xxfb/tzgg/';
  String columnUrl = '/content/column/14015578';
  int maxPages = 2;
}

class WyxmzxSite {
  bool enabled = true;
  String name = '安庆市阳光采购交易服务平台';
  // 2026-11-24 实测：站点的 https vhost 只剩首页，检索接口与详情页都返回 404；
  // 同一路径换成 http 全部正常。不要再改回 https。
  String baseUrl = 'http://www.wyxmzx.com';
  String searchPath = '/inteligentsearch/rest/esinteligentsearch/getFullTextDataNew';
  String referer = '';
  // 站点的检索接口现在会忽略 wd / cnum / time，只按 webdate 倒序返回全部公告，
  // 所以采集按「翻页列表」处理：cnum 传空串（080 会直接 0 条），翻页到时间窗外为止。
  String cnum = '';
  int pageSize = 30;
  int maxPages = 40;
  int contentLength = 3000;
  int lookbackDays = 30;
  List<String> excludedCategoryPrefixes = [];
}

class Zh0556Site {
  bool enabled = true;
  String name = '安庆交控集团集采平台';
  String baseUrl = 'https://jc.zh0556.com';
  String listPath = '/list/search';
  List<int> categoryIds = [1, 2];
  List<int> typeIds = [1, 2, 3, 4];
  int maxPagesPerCategory = 3;
  int lookbackDays = 30;
}
