import 'dart:convert';

import '../common/date_utils.dart';
import '../common/hashing.dart';
import '../common/notice_type.dart';
import '../common/region_names.dart';
import '../common/text_utils.dart';
import '../crawler/budget_extractor.dart';
import '../crawler/deadline_extractor.dart';
import '../crawler/detail_parser.dart';
import '../crawler/site_crawler.dart';
import '../filter/keyword_rules.dart';
import '../models.dart';
import '../net/http_fetcher.dart';
import '../repository.dart';
import '../settings.dart';
import 'link_verifier.dart';

/// 采集编排：发现 → 关键词过滤 → 详情补全 → 去重入库 → 记日志 → 已下架校验。
///
/// 与 Java 内核保持同一口径：
/// - URL 的 SHA-256 作为去重键；
/// - **先判标题排除词**再匹配（否则“标题含排除词、正文命中关键词”的公告会漏进来）；
/// - 详情页打不开时用检索接口自带正文，并把链接换成站点兜底地址，不给死链；
/// - 入库即记录“已验证”（详情页刚探过），让下架校验的负载与新增量而不是库存量成正比。
class CrawlEngine {
  static const int _maxSummaryLength = 2000;
  static const int _maxKeywordsLength = 500;

  final CrawlerSettings settings;
  final List<SiteCrawler> crawlers;
  final KeywordRules keywordRules;
  final TenderRepository repository;
  final HttpFetcher httpFetcher;
  final DetailParser detailParser;
  final DeadlineExtractor deadlineExtractor;
  final BudgetExtractor budgetExtractor;
  final LinkVerifier linkVerifier;

  /// 运行态日志：写系统日志 / 控制台，与“采集日志”表不是一回事。
  /// 构造时 [onInfo] 与 [onLog] 等价，保留两个名字是为了兼容不同调用方。
  final void Function(String message)? onInfo;
  final void Function(String message, Object error)? onWarn;

  bool _running = false;
  String? _runningSite;
  DateTime? _runningSince;
  CrawlSummary? _lastSummary;

  CrawlEngine({
    required this.settings,
    required this.crawlers,
    required this.keywordRules,
    required this.repository,
    required this.httpFetcher,
    required this.detailParser,
    required this.deadlineExtractor,
    required this.linkVerifier,
    BudgetExtractor? budgetExtractor,
    void Function(String message)? onInfo,
    void Function(String message)? onLog,
    this.onWarn,
  })  : onInfo = onInfo ?? onLog,
        budgetExtractor = budgetExtractor ?? BudgetExtractor();

  bool get isRunning => _running;

  String? get runningSite => _runningSite;

  /// 本次采集的开始时间（页面显示“开始于 …”）。
  DateTime? get runningSince => _runningSince;

  CrawlSummary? get lastSummary => _lastSummary;

  Future<CrawlSummary> crawl({
    String? siteCode,
    int? maxKeywords,
    int? lookbackDays,
    String triggerType = 'manual',
    ProgressListener? onProgress,
    ProgressListener? listener,
  }) async {
    final progress = onProgress ?? listener;
    if (!settings.enabled) {
      return CrawlSummary.of('DISABLED', '采集总开关已关闭');
    }
    if (_running) {
      return CrawlSummary.of('BUSY', '已有采集任务正在执行：${_runningSite ?? ''}');
    }
    _running = true;
    _runningSite = (siteCode == null || siteCode.trim().isEmpty) ? 'all' : siteCode;
    _runningSince = DateUtils.nowCn();
    try {
      final summary =
          await _doCrawl(siteCode, maxKeywords, lookbackDays, triggerType, progress);
      _lastSummary = summary;
      return summary;
    } finally {
      _running = false;
      _runningSite = null;
      _runningSince = null;
    }
  }

  Future<CrawlSummary> _doCrawl(
    String? siteCode,
    int? maxKeywords,
    int? lookbackDays,
    String triggerType,
    ProgressListener? listener,
  ) async {
    final startedAt = DateTime.now().millisecondsSinceEpoch;

    final List<SiteCrawler> targets;
    try {
      targets = _resolveCrawlers(siteCode);
    } catch (e) {
      // 未知站点 / 全部禁用：返回失败汇总而不是把异常抛给 UI
      return CrawlSummary.of('FAILED', '$e');
    }
    var terms = keywordRules.searchTerms;
    if (maxKeywords != null && maxKeywords > 0 && terms.length > maxKeywords) {
      terms = evenlySample(terms, maxKeywords);
    }
    if (terms.isEmpty) {
      return CrawlSummary.of('FAILED', '关键词为空，请在「关键词」页添加');
    }

    httpFetcher.resetRequestCount();
    final detailBudget = _DetailBudget(settings.request.maxDetailPerRun < 0
        ? 0
        : settings.request.maxDetailPerRun);

    final summary = CrawlSummary()..triggerType = triggerType;
    var okSites = 0;
    for (final crawler in targets) {
      final result = await _crawlOne(
          crawler, terms, lookbackDays, detailBudget, triggerType, listener);
      if (result.errorMessage == null) okSites++;
      summary.addSite(result);
    }

    // 采集完成后顺带抽查已入库公告是否已下架
    if (settings.verify.enabled && okSites > 0) {
      try {
        summary.verifiedCount = await linkVerifier.verify(listener: listener);
      } catch (e) {
        onWarn?.call('已下架校验失败（不影响采集结果）', e);
      }
    }

    summary.durationMs = DateTime.now().millisecondsSinceEpoch - startedAt;
    summary.finishedAt = DateUtils.nowCn();
    if (okSites == 0) {
      summary.status = 'FAILED';
      summary.message = '全部站点采集失败';
    } else if (okSites < targets.length) {
      summary.status = 'PARTIAL';
      summary.message =
          '$okSites/${targets.length} 个站点采集成功，新增 ${summary.savedCount} 条';
    } else {
      summary.status = 'SUCCESS';
      summary.message = '采集完成，新增 ${summary.savedCount} 条';
    }
    if (summary.verifiedCount > 0) {
      summary.message = '${summary.message}；已下架校验 ${summary.verifiedCount} 条';
    }
    onInfo?.call('采集结束：${summary.status}，候选 ${summary.candidateCount}'
        '，排除 ${summary.excludedCount}，命中 ${summary.matchedCount}'
        '，新增 ${summary.savedCount}，耗时 ${summary.durationMs}ms');
    return summary;
  }

  /// 在整张词表上**均匀取样** count 个词（首尾都要覆盖）。
  ///
  /// 词表是“窄 → 宽”排列的，直接取前 N 个等于只挑最窄的词：
  /// 实测「90 天窗口 + 前 5 个窄词」候选 0，用户会以为应用坏了。
  /// 均匀取样缩小的是覆盖密度而不是召回面。
  static List<T> evenlySample<T>(List<T> source, int count) {
    final picked = <T>[];
    if (source.isEmpty || count <= 0) return picked;
    if (source.length <= count) {
      picked.addAll(source);
      return picked;
    }
    if (count == 1) {
      picked.add(source.first);
      return picked;
    }
    // 把 [0, size-1] 均分成 count 个点，首尾都取到
    for (var i = 0; i < count; i++) {
      final index = (i * (source.length - 1)) ~/ (count - 1);
      picked.add(source[index]);
    }
    return picked;
  }

  List<SiteCrawler> _resolveCrawlers(String? siteCode) {
    if (siteCode == null ||
        siteCode.trim().isEmpty ||
        siteCode.trim().toLowerCase() == 'all') {
      final enabled = crawlers.where((crawler) => crawler.enabled).toList();
      if (enabled.isEmpty) {
        throw StateError('没有任何启用的站点');
      }
      return enabled;
    }
    final code = siteCode.trim().toLowerCase();
    for (final crawler in crawlers) {
      if (crawler.siteCode == code) {
        if (!crawler.enabled) {
          throw StateError('站点已禁用：$code');
        }
        return [crawler];
      }
    }
    throw ArgumentError('未知站点：$siteCode');
  }

  Future<CrawlResult> _crawlOne(
    SiteCrawler crawler,
    List<SearchTerm> terms,
    int? lookbackDays,
    _DetailBudget detailBudget,
    String triggerType,
    ProgressListener? listener,
  ) async {
    final result = CrawlResult(crawler.siteCode, crawler.siteName);
    final startedAt = DateTime.now().millisecondsSinceEpoch;
    final requestsBefore = httpFetcher.requestCount;

    final log = CrawlLogRecord()
      ..siteCode = crawler.siteCode
      ..triggerType = triggerType
      ..status = 'RUNNING'
      ..startTime = DateUtils.nowCn();
    await repository.saveLog(log);

    final effectiveLookback = lookbackDays ?? _siteLookbackDays(crawler.siteCode);
    final contentTerms = terms.where((term) => term.isContentScope).length;
    onInfo?.call('[${crawler.siteName}] 开始采集，检索词 ${terms.length} 个'
        '（其中正文检索 $contentTerms 个），时间窗口 '
        '${effectiveLookback > 0 ? '最近 $effectiveLookback 天' : '不限'}');
    listener?.call('正在采集 ${crawler.siteName}');

    try {
      final context = CrawlContext(
        terms,
        lookbackDaysOverride: lookbackDays,
        logger: result.addMessage,
      );
      final items = await crawler.crawl(context);
      result.addCandidate(items.length);

      for (final item in items) {
        try {
          await _processItem(item, result, detailBudget);
        } catch (e) {
          result.incrementFailed();
          result.addMessage('处理失败：${item.title} -> $e');
          onWarn?.call('处理公告失败：${item.title}', e);
        }
      }
      log.status = 'SUCCESS';
    } catch (e) {
      result.errorMessage = e.toString();
      log.status = 'FAILED';
      log.errorMessage = _truncate(e.toString(), 2000);
      onWarn?.call('[${crawler.siteName}] 采集失败', e);
    } finally {
      result.durationMs = DateTime.now().millisecondsSinceEpoch - startedAt;
      result.requestCount = httpFetcher.requestCount - requestsBefore;
      log.endTime = DateUtils.nowCn();
      log.durationMs = result.durationMs;
      log.candidateCount = result.candidateCount;
      log.excludedCount = result.excludedCount;
      log.matchedCount = result.matchedCount;
      log.savedCount = result.savedCount;
      log.duplicateCount = result.duplicateCount;
      log.failedCount = result.failedCount;
      log.requestCount = result.requestCount;
      log.detail = _truncate(TextUtils.join(result.messages), 20000);
      await repository.updateLog(log);
      onInfo?.call('[${crawler.siteName}] 采集完成：候选 ${result.candidateCount}'
          '，排除 ${result.excludedCount}，命中 ${result.matchedCount}'
          '，新增 ${result.savedCount}，重复 ${result.duplicateCount}'
          '，耗时 ${result.durationMs}ms');
    }
    return result;
  }

  Future<void> _processItem(
    CrawlItem item,
    CrawlResult result,
    _DetailBudget detailBudget,
  ) async {
    final title = item.title;
    final rawUrl = item.url;
    if (title.trim().isEmpty || rawUrl.trim().isEmpty) {
      result.incrementFailed();
      return;
    }

    final urlHash = Hashing.urlHash(rawUrl);
    final existing = await repository.findByUrlHash(urlHash);
    if (existing != null) {
      await _handleDuplicate(existing, item, result, detailBudget);
      return;
    }

    // 标题命中排除词直接丢弃。必须先判：下面“正文命中”那条路径标题参数是 null，
    // 否则「XX资产拍卖交易公告」会被正文里的“苗木”放进来
    if (keywordRules.isExcluded(title)) {
      result.incrementExcluded();
      return;
    }

    final titleMatch = keywordRules.match(title, null);
    final summaryMatch = keywordRules.match(null, item.summary);
    if (!titleMatch.isMatched && !summaryMatch.isMatched) {
      return;
    }
    result.incrementMatched();

    final now = DateUtils.nowCn();
    final record = TenderRecord()
      ..siteCode = item.siteCode
      ..siteName = item.siteName
      ..title = _truncate(title, 1000)
      ..url = _truncate(rawUrl, 1000)
      ..urlHash = urlHash
      ..category = _truncate(item.category, 200)
      ..categoryCode = _truncate(item.categoryCode, 64)
      ..region = _truncate(item.region, 64)
      ..publishDate = item.publishDate
      ..sourceKeyword = _truncate(item.sourceKeyword, 128)
      ..summary = _truncate(item.summary, _maxSummaryLength)
      ..crawlTime = now
      ..createTime = now
      ..noticeType = NoticeType.classify(item.category, title)
      ..isNew = true
      ..tracked = false
      ..removed = false;

    var detailFetched = false;
    if (settings.detail.enabled && detailBudget.left > 0 && _isFetchable(rawUrl)) {
      detailBudget.left--;
      final detail = await _fetchDetail(rawUrl);
      if (detail != null) {
        _applyDetail(record, detail);
        detailFetched = !detail.isEmpty;
      }
    }
    if (detailFetched) {
      record.linkStatus = 'OK';
    } else {
      _useSummaryAsContent(record);
      final fallback = _fallbackUrlFor(item);
      if (fallback != null && fallback.trim().isNotEmpty) {
        record.accessUrl = _truncate(fallback, 1000);
        record.linkStatus = 'FALLBACK';
      } else {
        record.linkStatus = 'UNKNOWN';
      }
    }
    // 入库时已探过一次链接，直接记为“已验证”：让下架校验的负载与新增量成正比
    record.verifiedAt = now;

    _applyDeadline(record);
    record.region = _resolveRegion(record);
    _applyBudget(record);

    final hits = <String>{...titleMatch.keywords, ...summaryMatch.keywords};
    final finalTitle = keywordRules.match(record.title, null);
    final contentForMatch = record.hasContent ? record.content : record.summary;
    final finalContent = keywordRules.match(null, contentForMatch);
    hits.addAll(finalTitle.keywords);
    hits.addAll(finalContent.keywords);

    record.matchedKeywords = _truncate(TextUtils.join(hits), _maxKeywordsLength);
    record.matchedScope = _resolveScope(finalTitle.isMatched, finalContent.isMatched);

    await repository.insert(record);
    result.incrementSaved();
    // 只把“可投的”收进通知列表：新入库的成交/合同类公告不是机会。
    if (record.biddable) {
      result.addNewItem(NewTender(record.title, record.deadlineDate));
    }
  }

  /// 已存在的公告：补跨站来源、补正文、补截止日期与区县。
  Future<void> _handleDuplicate(
    TenderRecord existing,
    CrawlItem item,
    CrawlResult result,
    _DetailBudget detailBudget,
  ) async {
    // 重复项也算“命中”：它在 findByUrlHash 分支就被拦下了，没走到 incrementMatched()。
    // 不补这一句，“命中”就会把重复项漏掉，日志出现「命中 0 · 重复 7」这种矛盾数字。
    result.incrementMatched();

    var changed = false;

    // 自愈：老数据可能带着过期 / 缺失的类型标记，或挂着“结果类公告估出来的假截止日”。
    final expectedType = NoticeType.classify(existing.category, existing.title);
    if (expectedType != existing.noticeType) {
      existing.noticeType = expectedType;
      changed = true;
    }
    // 估算功能已停用；这里兜底清掉老库里残留的估算值，清空后会按正文重新解析一次。
    if (existing.deadlineEstimated) {
      existing.deadlineDate = null;
      existing.deadlineEstimated = false;
      existing.deadlineSource = '';
      changed = true;
    }

    if (item.siteCode != existing.siteCode) {
      final alsoFoundOn = <String>{
        ...existing.alsoFoundOn.split(',').where((value) => value.trim().isNotEmpty),
      };
      if (alsoFoundOn.add(item.siteCode)) {
        existing.alsoFoundOn = _truncate(TextUtils.join(alsoFoundOn), 255);
        changed = true;
      }
    }

    if (!existing.hasContent &&
        settings.detail.enabled &&
        detailBudget.left > 0 &&
        _isFetchable(existing.url)) {
      detailBudget.left--;
      final detail = await _fetchDetail(existing.url);
      if (detail != null && !detail.isEmpty) {
        _applyDetail(existing, detail);
        changed = true;
      }
    }
    if (!existing.hasContent) {
      _useSummaryAsContent(existing);
      if (existing.accessUrl.trim().isEmpty) {
        final fallback = _fallbackUrlFor(item);
        if (fallback != null && fallback.trim().isNotEmpty) {
          existing.accessUrl = _truncate(fallback, 1000);
          existing.linkStatus = 'FALLBACK';
          changed = true;
        }
      }
    }
    if (existing.deadlineDate == null) {
      _applyDeadline(existing);
      changed = true;
    }
    // 正文补齐后顺带补金额 / 项目编号（老数据，或首次采集时没抓到正文的）
    if (_applyBudget(existing)) {
      changed = true;
    }
    final region = _resolveRegion(existing);
    if (region.isNotEmpty && region != existing.region) {
      existing.region = region;
      changed = true;
    }
    if (changed) {
      await repository.update(existing);
    }
    result.incrementDuplicate();
  }

  void _useSummaryAsContent(TenderRecord record) {
    if (record.hasContent) return;
    if (record.summary.length >= 60) {
      record.content = record.summary;
    }
  }

  void _applyDeadline(TenderRecord record) {
    final text = record.hasContent ? record.content : record.summary;
    // 只认正文里写明的日期；解析不到就是「未知」，不再按发布日期估算。
    final deadline = deadlineExtractor.resolve(text, record.publishDate);
    record.deadlineDate = deadline.date;
    // 字段保留是为了兼容旧库结构，估算功能停用后恒为 false
    record.deadlineEstimated = false;
    record.deadlineSource = _truncate(deadline.source, 64);
  }

  /// 从正文里抠「预算金额 / 项目编号 / 最高限价」。
  ///
  /// 已经有金额和编号就不再重跑正则 —— 正文可能上万字，重复采集时没必要每次解析。
  bool _applyBudget(TenderRecord record) {
    if (record.hasBudget && record.projectCode.isNotEmpty) {
      return false;
    }
    final text = record.hasContent ? record.content : record.summary;
    if (text.trim().isEmpty) return false;

    final budget = budgetExtractor.extract(text);
    var changed = false;
    if (budget.amountYuan != null && !record.hasBudget) {
      record.budgetYuan = budget.amountYuan;
      changed = true;
    }
    if (budget.capText != null && budget.capText != record.budgetCapText) {
      record.budgetCapText = _truncate(budget.capText, 200);
      changed = true;
    }
    if (budget.projectCode != null && record.projectCode.isEmpty) {
      record.projectCode = _truncate(budget.projectCode, 64);
      changed = true;
    }
    return changed;
  }

  String _resolveRegion(TenderRecord record) {
    if (record.region.trim().isNotEmpty) return record.region;
    return _truncate(RegionNames.guessFromTitle(record.title), 64);
  }

  void _applyDetail(TenderRecord record, DetailResult detail) {
    if (detail.contentText.trim().isNotEmpty) {
      record.content = detail.contentText;
      record.contentHtml = detail.contentHtml;
    }
    if (detail.publishDate != null && record.publishDate == null) {
      record.publishDate = detail.publishDate;
    }
    if (detail.attachments.isNotEmpty) {
      try {
        record.attachments = jsonEncode(detail.attachments);
      } catch (e) {
        onWarn?.call('附件列表序列化失败', e);
      }
    }
  }

  Future<DetailResult?> _fetchDetail(String url) async {
    try {
      final response = await httpFetcher.get(url, referer: _refererOf(url));
      if (!response.isSuccess) return null;
      return detailParser.parse(response.text, response.finalUrl);
    } catch (_) {
      return null;
    }
  }

  String? _fallbackUrlFor(CrawlItem item) {
    for (final crawler in crawlers) {
      if (crawler.siteCode == item.siteCode) {
        return crawler.fallbackDetailUrl(item);
      }
    }
    return null;
  }

  bool _isFetchable(String url) =>
      url.startsWith('http://') || url.startsWith('https://');

  String? _refererOf(String url) {
    final uri = Uri.tryParse(url);
    if (uri == null || uri.scheme.isEmpty || uri.host.isEmpty) return null;
    return '${uri.scheme}://${uri.host}/';
  }

  int _siteLookbackDays(String siteCode) {
    switch (siteCode) {
      case 'aqggzy':
        return settings.sites.aqggzy.lookbackDays;
      case 'wyxmzx':
        return settings.sites.wyxmzx.lookbackDays;
      case 'zh0556':
        return settings.sites.zh0556.lookbackDays;
      default:
        return 0;
    }
  }

  String _resolveScope(bool titleHit, bool contentHit) {
    if (titleHit && contentHit) return 'TITLE,CONTENT';
    if (titleHit) return 'TITLE';
    return contentHit ? 'CONTENT' : 'TITLE';
  }

  static String _truncate(String? value, int max) {
    if (value == null) return '';
    return value.length <= max ? value : value.substring(0, max);
  }
}

/// 单次采集的详情页预算（去重路径也会消耗，防止多站点重复抓同一批页面）。
class _DetailBudget {
  int left;

  _DetailBudget(this.left);
}
