import 'dart:io';

import 'package:flutter/foundation.dart';

import 'package:flutter_test/flutter_test.dart';

import 'package:flower_tender/core/crawler/epoint.dart';
import 'package:flower_tender/core/crawler/wyxmzx_crawler.dart';
import 'package:flower_tender/core/crawler/zh0556_crawler.dart';
import 'package:flower_tender/core/filter/keyword_rules.dart';
import 'package:flower_tender/core/keywords.dart';
import 'package:flower_tender/core/models.dart';
import 'package:flower_tender/core/net/http_fetcher.dart';
import 'package:flower_tender/core/settings.dart';

/// 联网打真实站点。默认跳过，避免 CI / 离线环境失败：
///
///   flutter test test/live --dart-define=LIVE=true
///
/// 选 zh0556 做冒烟：它是纯静态列表页，不依赖检索接口，也不受关键词驱动，
/// 最适合验证「网络层 + HTML 解析 + 日期解析」这一整条链路。
const bool _live = bool.fromEnvironment('LIVE');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  // flutter_test 默认把所有 HTTP 请求拦成 400；联网测试要显式关掉。
  HttpOverrides.global = null;

  test('zh0556 真实列表页能抓到公告', () async {
    final settings = CrawlerSettings();
    final fetcher = HttpFetcher(settings, onWarn: (message) => debugPrint(message));
    final crawler = Zh0556Crawler(settings, fetcher);
    final rules = KeywordRules(settings, const EmptyKeywordSource());
    await rules.reload();

    final items = await crawler.crawl(
      CrawlContext(rules.searchTerms, lookbackDaysOverride: 365),
    );

    // 把前几条打出来，人工看一眼标题/日期是否合理
    for (final item in items.take(5)) {
      debugPrint('${item.publishDate} | ${item.title} | ${item.url}');
    }
    debugPrint('共 ${items.length} 条候选（${crawler.siteCode}）');

    expect(items, isNotEmpty);
    for (final item in items) {
      expect(item.title.trim(), isNotEmpty);
      expect(item.url, startsWith('http'));
      // 标题不能是站点名（zh0556 详情页 <title> 没有公告名）
      expect(item.title, isNot('安庆交控集团集采平台'));
    }
    expect(items.where((item) => item.publishDate != null), isNotEmpty);
  }, skip: _live ? false : '联网测试，用 --dart-define=LIVE=true 打开', timeout: const Timeout(Duration(minutes: 5)));

  test('wyxmzx 列表接口能返回公告（http，不能是 https）', () async {
    final settings = CrawlerSettings();
    final fetcher = HttpFetcher(settings, onWarn: (message) => debugPrint(message));
    final search = EpointSearchClient(fetcher, onWarn: (message) => debugPrint(message));
    final crawler = WyxmzxCrawler(settings, search);

    // 该站的检索接口已忽略关键词，只按最新公告翻页，所以这里不需要给检索词
    final items = await crawler.crawl(
      CrawlContext(const [], lookbackDaysOverride: 30),
    );
    debugPrint('wyxmzx 共 ${items.length} 条候选');
    expect(items, isNotEmpty);
    for (final item in items.take(3)) {
      debugPrint('${item.publishDate} | ${item.title} | ${item.url}');
    }
    // 站内链接必须是 http：站点 https 的检索接口与详情页都返回 404。
    // 接口偶尔夹杂少量外站绝对链接（安徽招标网、gov.cn 等），不在此列。
    expect(
      items
          .where((item) => item.url.contains('wyxmzx.com'))
          .every((item) => item.url.startsWith('http://www.wyxmzx.com')),
      isTrue,
      reason: 'https 的检索接口与详情页返回 404，baseUrl 必须是 http',
    );
    final windowStart = DateTime.now().subtract(const Duration(days: 31));
    expect(
      items.every(
        (item) => item.publishDate == null || item.publishDate!.isAfter(windowStart),
      ),
      isTrue,
      reason: '接口不支持 time 过滤，时间窗应在本地过滤',
    );
  }, skip: _live ? false : '联网测试，用 --dart-define=LIVE=true 打开', timeout: const Timeout(Duration(minutes: 5)));
}
