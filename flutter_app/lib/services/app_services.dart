import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:package_info_plus/package_info_plus.dart';

import '../core/common/date_utils.dart';
import '../core/crawler/aqggzy_crawler.dart';
import '../core/crawler/cgj_crawler.dart';
import '../core/crawler/deadline_extractor.dart';
import '../core/crawler/detail_parser.dart';
import '../core/crawler/epoint.dart';
import '../core/crawler/site_crawler.dart';
import '../core/crawler/wyxmzx_crawler.dart';
import '../core/crawler/zh0556_crawler.dart';
import '../core/filter/keyword_rules.dart';
import '../core/models.dart';
import '../core/net/http_fetcher.dart';
import '../core/pipeline/crawl_engine.dart';
import '../core/pipeline/deadline_reminder.dart';
import '../core/pipeline/link_verifier.dart';
import '../core/settings.dart';
import '../data/database.dart';
import '../data/settings_store.dart';
import '../data/sqlite_keyword_store.dart';
import '../data/sqlite_tender_repository.dart';
import 'crawl_scheduler.dart';
import 'notifier.dart';

/// 应用内单例：把内核各部件和 Flutter 侧实现（dart:io / sqflite / 通知）接起来。
///
/// 对应安卓版的 `CrawlerHolder`。UI isolate 和 WorkManager 的后台 isolate
/// 各有一份实例，互不共享内存，但读写同一个 SQLite 库。
class AppServices {
  AppServices._();

  static final AppServices instance = AppServices._();

  final CrawlerSettings settings = CrawlerSettings();
  final AppNotifier notifier = AppNotifier.instance;

  late final SettingsStore store;
  late final SqliteTenderRepository repository;
  late final SqliteKeywordStore keywordStore;
  late final HttpFetcher fetcher;
  late final KeywordRules keywordRules;
  late final DetailParser detailParser;
  late final DeadlineExtractor deadlineExtractor;
  late final LinkVerifier linkVerifier;
  late final List<SiteCrawler> crawlers;
  late final CrawlEngine engine;

  String appVersion = '';
  String appVersionCode = '';

  bool _initialized = false;
  Future<void>? _initFuture;

  bool get isReady => _initialized;

  /// 初始化（幂等）。失败后允许重试，不会永久卡在失败状态。
  Future<void> init() {
    return _initFuture ??= _doInit().catchError((Object error, StackTrace stack) {
      _initFuture = null;
      Error.throwWithStackTrace(error, stack);
    });
  }

  Future<void> _doInit() async {
    final loadedStore = SettingsStore();
    await loadedStore.init();
    store = loadedStore;
    // 用户设置覆盖到内核配置（对应安卓 CrawlerHolder.applyPrefs）
    loadedStore.applyTo(settings);

    final db = TenderDatabase.instance;
    // 先打开一次，建库/迁移失败时在启动页就暴露出来，而不是等第一次查询
    await db.database;
    repository = SqliteTenderRepository(db);
    keywordStore = SqliteKeywordStore(db);

    fetcher = HttpFetcher(
      settings,
      onWarn: (message) => _warn(message),
      onInfo: (message) => _info(message),
      onDebug: (message) => _debug(message),
    );

    keywordRules = KeywordRules(
      settings,
      keywordStore,
      onInfo: (message) => _info(message),
      onWarn: (message) => _warn(message),
    );
    await keywordRules.reload();

    final searchClient = EpointSearchClient(
      fetcher,
      onWarn: (message) => _warn(message),
      onError: (message, error) => _warn('$message${error == null ? '' : '：$error'}'),
    );
    detailParser = DetailParser(settings, onWarn: (message) => _warn(message));
    deadlineExtractor = DeadlineExtractor(settings);
    linkVerifier = LinkVerifier(repository, fetcher, settings);

    crawlers = <SiteCrawler>[
      AqggzyCrawler(settings, searchClient),
      CgjCrawler(settings, fetcher, onWarn: (message) => _warn(message)),
      WyxmzxCrawler(settings, searchClient),
      Zh0556Crawler(settings, fetcher, onWarn: (message) => _warn(message)),
    ];

    engine = CrawlEngine(
      settings: settings,
      crawlers: crawlers,
      keywordRules: keywordRules,
      repository: repository,
      httpFetcher: fetcher,
      detailParser: detailParser,
      deadlineExtractor: deadlineExtractor,
      linkVerifier: linkVerifier,
      onLog: (message) => _info(message),
      onWarn: (message, error) => _warn('$message：$error'),
    );

    await _loadPackageInfo();
    _initialized = true;
  }

  Future<void> _loadPackageInfo() async {
    try {
      final info = await PackageInfo.fromPlatform();
      appVersion = info.version;
      appVersionCode = info.buildNumber;
    } catch (_) {
      // 后台 isolate / 单元测试里可能拿不到，界面会显示空版本号，不影响采集
    }
  }

  // ------------------------------------------------------------------ 设置

  Future<void> reloadKeywords() => keywordRules.reload();

  int getLookbackDays() => store.lookbackDays;

  Future<void> setLookbackDays(int days) async {
    await store.setLookbackDays(days);
    // 与 Java 版一致：改时间窗口同时作用于三个按天检索的站点
    settings.sites.aqggzy.lookbackDays = days;
    settings.sites.wyxmzx.lookbackDays = days;
    settings.sites.zh0556.lookbackDays = days;
  }

  bool isScheduleEnabled() => store.scheduleEnabled;

  Future<void> setScheduleEnabled(bool enabled) async {
    await store.setScheduleEnabled(enabled);
    await reschedule();
  }

  int getScheduleHour() => store.scheduleHour;

  Future<void> setScheduleHour(int hour) async {
    await store.setScheduleHour(hour);
    await reschedule();
  }

  bool isFetchDetail() => store.fetchDetail;

  Future<void> setFetchDetail(bool enabled) async {
    await store.setFetchDetail(enabled);
    settings.detail.enabled = enabled;
  }

  bool isVerifyLink() => store.verifyLink;

  Future<void> setVerifyLink(bool enabled) async {
    await store.setVerifyLink(enabled);
    settings.verify.enabled = enabled;
  }

  bool isReminderEnabled() => store.reminderEnabled;

  Future<void> setReminderEnabled(bool enabled) async {
    await store.setReminderEnabled(enabled);
    await reschedule();
  }

  int getReminderDays() => store.reminderDays;

  Future<void> setReminderDays(int days) async {
    await store.setReminderDays(days);
    await reschedule();
  }

  int getRetentionDays() => store.retentionDays;

  Future<void> setRetentionDays(int days) => store.setRetentionDays(days);

  /// 设置变化后重排后台任务。
  Future<void> reschedule() async {
    try {
      await CrawlScheduler.scheduleAll();
    } catch (error) {
      // 没有通知权限 / 系统限制时不要影响设置保存
      debugPrint('AppServices: 重排后台任务失败 $error');
    }
  }

  // -------------------------------------------------------------- 采集与提醒

  /// 立即采集一次，落盘快照并发通知。
  Future<CrawlSummary> runCrawl({
    String siteCode = 'all',
    int? maxKeywords,
    int? lookbackDays,
    String triggerType = 'MANUAL',
    void Function(String message)? onProgress,
  }) async {
    CrawlSummary summary;
    try {
      summary = await engine.crawl(
        siteCode: siteCode,
        maxKeywords: maxKeywords,
        lookbackDays: lookbackDays,
        triggerType: triggerType,
        onProgress: onProgress,
      );
    } catch (error) {
      // 库损坏 / 词表加载失败等意外：转成失败汇总，让页面和通知都有话可说
      summary = CrawlSummary.of('FAILED', '$error');
    }
    await _afterCrawl(summary);
    return summary;
  }

  /// 采集完成后的公共收尾：存快照 + 发通知。
  Future<void> _afterCrawl(CrawlSummary summary) async {
    try {
      await store.saveLastSummary(summary);
    } catch (error) {
      debugPrint('AppServices: 保存采集快照失败 $error');
    }
    try {
      if (summary.status == 'FAILED') {
        await notifier.notifyCrawlError(
          summary.message.isEmpty ? '采集失败' : summary.message,
        );
      } else {
        await notifier.notifyCrawlResult(summary);
      }
    } catch (error) {
      // 通知失败不影响采集结果
      debugPrint('AppServices: 发送采集通知失败 $error');
    }
  }

  /// 每日截止提醒检查：只提醒「未截止 + 可投 + 剩余天数 ≤ 阈值」的公告。
  ///
  /// 没有到期公告时**不发通知** —— 每天准点报「没有内容」只会让人关掉通知权限。
  Future<int> checkDeadlines() async {
    if (!store.reminderEnabled) return 0;
    final query = TenderQuery()
      ..onlyOpen = true
      ..onlyBiddable = true
      ..sortBy = TenderQuery.sortDeadline;
    // 正常库里也就几十条未截止的，这个上限足够
    final records = await repository.query(query, 200, 0);
    final due = DeadlineReminder.pick(records, store.reminderDays);
    if (due.isNotEmpty) {
      await notifier.notifyDue(due);
    }
    return due.length;
  }

  // -------------------------------------------------------------- 数据维护

  /// 可清理的条数（已截止超过保留天数的，收藏的保留）。
  Future<int> countPurgeable() {
    final before = DateUtils.nowCn().subtract(Duration(days: store.retentionDays));
    return repository.countPurgeable(before);
  }

  /// 用户主动清理。公告删了就得重新采集，所以绝不自动执行。
  Future<int> purgeExpired() {
    final before = DateUtils.nowCn().subtract(Duration(days: store.retentionDays));
    return repository.purgeExpired(before);
  }

  CrawlSummary? loadLastSummary() => store.lastSummary;

  Future<void> saveLastSummary(CrawlSummary summary) => store.saveLastSummary(summary);

  // ------------------------------------------------------------------ 其它

  /// 站点代码 → 名称（「全部站点」也在里面，方便 UI 直接做筛选）。
  Map<String, String> get siteNames => <String, String>{
        'all': '全部站点',
        for (final crawler in crawlers) crawler.siteCode: crawler.siteName,
      };

  List<String> get siteCodes => crawlers.map((c) => c.siteCode).toList();

  void _info(String message) => debugPrint('[采集] $message');

  void _warn(String message) => debugPrint('[采集][警告] $message');

  void _debug(String message) => debugPrint('[采集][调试] $message');
}
