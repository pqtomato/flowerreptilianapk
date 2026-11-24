import 'common/date_utils.dart';
import 'common/notice_type.dart';

/// 一次站内检索用的词。
class SearchTerm {
  static const String scopeTitle = 'TITLE';
  static const String scopeContent = 'CONTENT';

  final String keyword;
  final String fields;
  final String scope;

  const SearchTerm(this.keyword, this.fields, this.scope);

  factory SearchTerm.title(String keyword) => SearchTerm(keyword, 'title', scopeTitle);

  factory SearchTerm.content(String keyword) =>
      SearchTerm(keyword, 'title;content', scopeContent);

  bool get isContentScope => scope == scopeContent;
}

/// 采集到的一条候选公告（尚未经过关键词过滤与详情补全）。
class CrawlItem {
  final String siteCode;
  final String siteName;
  String title = '';
  String url = '';
  String categoryCode = '';
  String category = '';
  String region = '';
  DateTime? publishDate;
  String summary = '';
  String sourceId = '';
  String sourceKeyword = '';

  CrawlItem(this.siteCode, this.siteName);
}

/// 采集上下文：检索词、时间窗口与日志回调。
class CrawlContext {
  final List<SearchTerm> searchTerms;
  final int? lookbackDaysOverride;
  final void Function(String message)? logger;

  CrawlContext(this.searchTerms, {this.lookbackDaysOverride, this.logger});

  int resolveLookbackDays(int siteDefault) => lookbackDaysOverride ?? siteDefault;

  void log(String message) => logger?.call(message);
}

/// 详情页解析结果。
class DetailResult {
  final String title;
  final DateTime? publishDate;
  final String contentText;
  final String contentHtml;
  final List<String> attachments;

  const DetailResult({
    this.title = '',
    this.publishDate,
    this.contentText = '',
    this.contentHtml = '',
    this.attachments = const [],
  });

  static const DetailResult empty = DetailResult();

  bool get isEmpty =>
      contentText.trim().isEmpty && title.trim().isEmpty && attachments.isEmpty;
}

/// 本次采集新入库的一条公告 —— 只带通知展示需要的字段。
class NewTender {
  static const int maxTitle = 28;
  static const int _expiredBase = 1000000;

  final String title;
  final DateTime? deadlineDate;

  const NewTender(this.title, this.deadlineDate);

  int get daysLeft => deadlineDate == null
      ? -1000000000
      : deadlineDate!.difference(DateUtils.nowCn()).inDays;

  bool get expired => deadlineDate != null && deadlineDate!.isBefore(DateUtils.nowCn());

  int get _urgencyRank {
    if (deadlineDate == null) return 0x7fffffff;
    final now = DateUtils.nowCn();
    if (deadlineDate!.isBefore(now)) {
      return _expiredBase + now.difference(deadlineDate!).inMinutes;
    }
    return deadlineDate!.difference(now).inDays;
  }

  /// 未截止的按剩余天数升序在前，已截止的排最后，没有截止日期的垫底。
  static List<NewTender> mostUrgent(List<NewTender> items, int limit) {
    final sorted = items.where((item) => true).toList()
      ..sort((a, b) => a._urgencyRank.compareTo(b._urgencyRank));
    if (limit > 0 && sorted.length > limit) {
      return sorted.sublist(0, limit);
    }
    return sorted;
  }

  String describe() => describeFields(title, deadlineDate);

  static String describeFields(String title, DateTime? deadline) {
    final shortened = shorten(title);
    final left = leftLabel(deadline);
    return left.isEmpty ? shortened : '$left · $shortened';
  }

  static String leftLabel(DateTime? deadline) {
    if (deadline == null) return '';
    final now = DateUtils.nowCn();
    if (deadline.isBefore(now)) return '已截止';
    final days = deadline.difference(now).inDays;
    return days == 0 ? '今天截止' : '剩 $days 天';
  }

  static String shorten(String title) {
    final trimmed = title.trim();
    return trimmed.length <= maxTitle ? trimmed : '${trimmed.substring(0, maxTitle)}…';
  }
}

/// 单个站点的采集结果统计。
class CrawlResult {
  final String siteCode;
  final String siteName;
  int candidateCount = 0;
  int excludedCount = 0;
  int matchedCount = 0;
  int savedCount = 0;
  int duplicateCount = 0;
  int failedCount = 0;
  int requestCount = 0;
  int durationMs = 0;
  String? errorMessage;
  final List<String> messages = [];
  final List<NewTender> newItems = [];
  int newItemCount = 0;
  static const int _maxNewItems = 50;

  CrawlResult(this.siteCode, this.siteName);

  void addNewItem(NewTender item) {
    newItemCount++;
    if (newItems.length < _maxNewItems) newItems.add(item);
  }

  void addMessage(String message) {
    if (messages.length < 200) messages.add(message);
  }

  void addCandidate(int count) => candidateCount += count;
  void incrementExcluded() => excludedCount++;
  void incrementMatched() => matchedCount++;
  void incrementSaved() => savedCount++;
  void incrementDuplicate() => duplicateCount++;
  void incrementFailed() => failedCount++;
}

/// 一次采集（可能包含多个站点）的结果汇总。
class CrawlSummary {
  String status = '';
  String message = '';
  String triggerType = '';
  DateTime? finishedAt;
  int candidateCount = 0;
  int excludedCount = 0;
  int matchedCount = 0;
  int savedCount = 0;
  int duplicateCount = 0;
  int failedCount = 0;
  int verifiedCount = 0;
  int requestCount = 0;
  int durationMs = 0;
  final List<SiteSummary> sites = [];
  final List<NewTender> newItems = [];
  int newItemCount = 0;

  CrawlSummary();

  factory CrawlSummary.of(String status, String message) {
    final summary = CrawlSummary();
    summary.status = status;
    summary.message = message;
    return summary;
  }

  void addSite(CrawlResult result) {
    final site = SiteSummary()
      ..siteCode = result.siteCode
      ..siteName = result.siteName
      ..candidateCount = result.candidateCount
      ..excludedCount = result.excludedCount
      ..matchedCount = result.matchedCount
      ..savedCount = result.savedCount
      ..duplicateCount = result.duplicateCount
      ..failedCount = result.failedCount
      ..requestCount = result.requestCount
      ..durationMs = result.durationMs
      ..errorMessage = result.errorMessage
      ..messages.addAll(result.messages);
    sites.add(site);

    candidateCount += result.candidateCount;
    excludedCount += result.excludedCount;
    matchedCount += result.matchedCount;
    savedCount += result.savedCount;
    duplicateCount += result.duplicateCount;
    failedCount += result.failedCount;
    requestCount += result.requestCount;
    durationMs += result.durationMs;
    newItems.addAll(result.newItems);
    newItemCount += result.newItemCount;
  }

  Map<String, dynamic> toJson() => {
        'status': status,
        'message': message,
        'triggerType': triggerType,
        'finishedAt': DateUtils.iso(finishedAt),
        'candidateCount': candidateCount,
        'excludedCount': excludedCount,
        'matchedCount': matchedCount,
        'savedCount': savedCount,
        'duplicateCount': duplicateCount,
        'failedCount': failedCount,
        'verifiedCount': verifiedCount,
        'requestCount': requestCount,
        'durationMs': durationMs,
      };

  factory CrawlSummary.fromJson(Map<String, dynamic> json) {
    final summary = CrawlSummary();
    summary.status = json['status'] as String? ?? '';
    summary.message = json['message'] as String? ?? '';
    summary.triggerType = json['triggerType'] as String? ?? '';
    summary.finishedAt = DateUtils.parseIso(json['finishedAt'] as String?);
    summary.candidateCount = json['candidateCount'] as int? ?? 0;
    summary.excludedCount = json['excludedCount'] as int? ?? 0;
    summary.matchedCount = json['matchedCount'] as int? ?? 0;
    summary.savedCount = json['savedCount'] as int? ?? 0;
    summary.duplicateCount = json['duplicateCount'] as int? ?? 0;
    summary.failedCount = json['failedCount'] as int? ?? 0;
    summary.verifiedCount = json['verifiedCount'] as int? ?? 0;
    summary.requestCount = json['requestCount'] as int? ?? 0;
    summary.durationMs = json['durationMs'] as int? ?? 0;
    return summary;
  }
}

/// 单站点结果。
class SiteSummary {
  String siteCode = '';
  String siteName = '';
  int candidateCount = 0;
  int excludedCount = 0;
  int matchedCount = 0;
  int savedCount = 0;
  int duplicateCount = 0;
  int failedCount = 0;
  int requestCount = 0;
  int durationMs = 0;
  String? errorMessage;
  final List<String> messages = [];
}

/// 一次采集任务的执行记录。
class CrawlLogRecord {
  int id = 0;
  String siteCode = '';
  String triggerType = '';
  String status = '';
  DateTime? startTime;
  DateTime? endTime;
  int durationMs = 0;
  int candidateCount = 0;
  int excludedCount = 0;
  int matchedCount = 0;
  int savedCount = 0;
  int duplicateCount = 0;
  int failedCount = 0;
  int requestCount = 0;
  int verifiedCount = 0;
  String detail = '';
  String errorMessage = '';
}

/// 一条花卉 / 园林绿化招标公告（与旧版数据库字段一一对应）。
class TenderRecord {
  int id = 0;
  String siteCode = '';
  String siteName = '';
  String title = '';
  String url = '';
  String urlHash = '';
  String accessUrl = '';
  String category = '';
  String categoryCode = '';
  String region = '';
  DateTime? publishDate;
  DateTime? deadlineDate;
  bool deadlineEstimated = false;
  String deadlineSource = '';
  String matchedKeywords = '';
  String matchedScope = '';
  String sourceKeyword = '';
  String summary = '';
  String content = '';
  String contentHtml = '';
  String attachments = '';
  String alsoFoundOn = '';
  String noticeType = '';
  int? budgetYuan;
  String budgetCapText = '';
  String projectCode = '';
  bool isNew = false;
  bool tracked = false;
  bool removed = false;
  DateTime? verifiedAt;
  String linkStatus = '';
  DateTime? crawlTime;
  DateTime? createTime;

  /// 距离截止还有几天（负数=已截止）；没有截止日期返回 very small。
  int get daysLeft {
    if (deadlineDate == null) return -1000000000;
    return deadlineDate!.difference(DateUtils.nowCn()).inDays;
  }

  bool get expired => deadlineDate != null && deadlineDate!.isBefore(DateUtils.nowCn());

  bool get hasContent => content.trim().isNotEmpty;

  bool get hasAttachment =>
      attachments.trim().isNotEmpty && attachments.trim() != '[]';

  bool get biddable => NoticeType.isBiddable(noticeType);

  bool get hasBudget => budgetYuan != null && budgetYuan! > 0;
}

/// 公告查询条件。
class TenderQuery {
  static const String sortPublish = 'publish';
  static const String sortDeadline = 'deadline';
  static const String sortBudget = 'budget';

  String? siteCode;
  String? keyword;
  String? region;
  DateTime? fromTime;
  DateTime? toTime;
  bool onlyOpen = false;
  bool onlyNew = false;
  bool trackedOnly = false;
  bool onlyBiddable = false;
  bool includeRemoved = false;
  String sortBy = sortPublish;

  bool get sortByDeadline => sortBy == TenderQuery.sortDeadline;
  bool get sortByBudget => sortBy == TenderQuery.sortBudget;

  TenderQuery copy() => TenderQuery()
    ..siteCode = siteCode
    ..keyword = keyword
    ..region = region
    ..fromTime = fromTime
    ..toTime = toTime
    ..onlyOpen = onlyOpen
    ..onlyNew = onlyNew
    ..trackedOnly = trackedOnly
    ..onlyBiddable = onlyBiddable
    ..includeRemoved = includeRemoved
    ..sortBy = sortBy;
}
