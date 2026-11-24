import 'package:flutter_test/flutter_test.dart';
import 'package:flower_tender/core/filter/keyword_rules.dart';
import 'package:flower_tender/core/keywords.dart';
import 'package:flower_tender/core/models.dart';
import 'package:flower_tender/core/net/http_fetcher.dart';
import 'package:flower_tender/core/crawler/deadline_extractor.dart';
import 'package:flower_tender/core/crawler/detail_parser.dart';
import 'package:flower_tender/core/crawler/site_crawler.dart';
import 'package:flower_tender/core/pipeline/crawl_engine.dart';
import 'package:flower_tender/core/pipeline/link_verifier.dart';
import 'package:flower_tender/core/repository.dart';
import 'package:flower_tender/core/settings.dart';

/// 固定返回一批候选的站点。
class FakeCrawler extends SiteCrawler {
  FakeCrawler(this.items);

  final List<CrawlItem> items;

  @override
  String get siteCode => 'fake';

  @override
  String get siteName => '测试站点';

  @override
  bool get enabled => true;

  @override
  Future<List<CrawlItem>> crawl(CrawlContext context) async => items;
}

/// 内存仓库：引擎只用到去重、入库、更新与日志。
class MemoryRepository implements TenderRepository {
  final List<TenderRecord> records = [];
  final List<CrawlLogRecord> logs = [];
  int _nextId = 1;

  @override
  Future<TenderRecord?> findByUrlHash(String urlHash) async {
    for (final record in records) {
      if (record.urlHash == urlHash) return record;
    }
    return null;
  }

  @override
  Future<TenderRecord> insert(TenderRecord record) async {
    record.id = _nextId++;
    records.add(record);
    return record;
  }

  @override
  Future<void> update(TenderRecord record) async {
    final index = records.indexWhere((item) => item.id == record.id);
    if (index >= 0) records[index] = record;
  }

  @override
  Future<void> saveLog(CrawlLogRecord log) async {
    log.id = _nextId++;
    logs.add(log);
  }

  @override
  Future<void> updateLog(CrawlLogRecord log) async {}

  @override
  Future<TenderRecord?> findById(int id) async => null;

  @override
  Future<int> count() async => records.length;

  @override
  Future<int> countBySite(String siteCode) async => 0;

  @override
  Future<int> countQuery(TenderQuery query) async => 0;

  @override
  Future<List<TenderRecord>> query(TenderQuery query, int limit, int offset) async =>
      const [];

  @override
  Future<List<TenderRecord>> findForVerification(DateTime before, int limit) async =>
      const [];

  @override
  Future<int> countForVerification(DateTime before) async => 0;

  @override
  Future<List<String>> allMatchedKeywords() async => const [];

  @override
  Future<int> countPurgeable(DateTime deadlineBefore, {bool keepTracked = true}) async =>
      0;

  @override
  Future<int> purgeExpired(DateTime before, {bool keepTracked = true}) async => 0;

  @override
  Future<int> markAllRead() async => 0;

  @override
  Future<bool> setTracked(int id, bool tracked) async => false;

  @override
  Future<CrawlLogRecord?> findLog(int id) async => null;

  @override
  Future<List<CrawlLogRecord>> recentLogs(int limit) async => const [];
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  CrawlItem item(String title, String url,
      {String summary = '', String category = ''}) {
    return CrawlItem('fake', '测试站点')
      ..title = title
      ..url = url
      ..summary = summary
      ..category = category;
  }

  late CrawlerSettings settings;
  late MemoryRepository repository;
  late KeywordRules rules;
  late CrawlEngine engine;

  setUp(() async {
    settings = CrawlerSettings();
    settings.detail.enabled = false; // 不联网
    settings.verify.enabled = false;
    repository = MemoryRepository();
    rules = KeywordRules(settings, const EmptyKeywordSource());
    await rules.reload();
  });

  Future<CrawlEngine> engineWith(List<CrawlItem> items) async {
    final fetcher = HttpFetcher(settings);
    return CrawlEngine(
      settings: settings,
      crawlers: [FakeCrawler(items)],
      keywordRules: rules,
      repository: repository,
      httpFetcher: fetcher,
      detailParser: DetailParser(settings),
      deadlineExtractor: DeadlineExtractor(settings),
      linkVerifier: LinkVerifier(repository, fetcher, settings),
    );
  }

  test('先排除后匹配：命中 = 新增 + 重复', () async {
    final candidates = [
      item('城区绿化苗木采购项目', 'https://example.com/1'),
      item('车辆维修服务项目', 'https://example.com/2'),
      item('办公楼窗帘采购', 'https://example.com/3'),
    ];
    engine = await engineWith(candidates);

    final first = await engine.crawl();
    expect(first.candidateCount, 3);
    expect(first.excludedCount, 1);
    expect(first.matchedCount, 1);
    expect(first.savedCount, 1);
    expect(first.duplicateCount, 0);
    expect(first.status, 'SUCCESS');

    final second = await engine.crawl();
    expect(second.savedCount, 0);
    expect(second.duplicateCount, 1);
    // 重复项也算命中：日志不能出现「命中 0 · 重复 1」
    expect(second.matchedCount, second.savedCount + second.duplicateCount);
  });

  test('正文里没写日期就是未知，不估算', () async {
    final summary = '本项目采用综合评分法，具体详见招标文件。'
        '投标截止时间：2026年12月24日15点00分。';
    final longSummary = '$summary${List.filled(40, '说明。').join()}';
    engine = await engineWith([
      item('城区绿化苗木采购项目', 'https://example.com/d1', summary: longSummary),
    ]);

    await engine.crawl();
    expect(repository.records, hasLength(1));
    final record = repository.records.first;
    expect(record.deadlineDate, DateTime(2026, 12, 24, 15, 0));
    expect(record.deadlineEstimated, isFalse);
    expect(record.linkStatus, 'UNKNOWN');
  });

  test('结果类公告不进入可投通知列表', () async {
    engine = await engineWith([
      item('城区绿化苗木采购项目中标结果公告', 'https://example.com/a1',
          category: '中标结果'),
    ]);
    final summary = await engine.crawl();
    expect(repository.records.first.biddable, isFalse);
    expect(summary.newItemCount, 0);
  });

  test('预算金额入库、限价只留文本', () async {
    final longSummary =
        '预算金额：306万元。最高限价：审定金额的95%。${List.filled(40, '说明。').join()}';
    engine = await engineWith([
      item('城区绿化苗木采购项目', 'https://example.com/b1', summary: longSummary),
    ]);
    await engine.crawl();
    final record = repository.records.first;
    expect(record.budgetYuan, 3060000);
    expect(record.budgetCapText, '审定金额的95%');
  });

  test('检索词均匀取样：首尾都要覆盖', () {
    final source = List.generate(100, (index) => index);
    final sample = CrawlEngine.evenlySample(source, 5);
    expect(sample.length, 5);
    expect(sample.first, 0);
    expect(sample.last, 99);
    expect(sample, [0, 24, 49, 74, 99]);
  });
}
