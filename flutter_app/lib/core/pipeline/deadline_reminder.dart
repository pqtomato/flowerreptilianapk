import '../models.dart';

/// 截止日期提醒：从已入库的公告里挑出「今天该提醒的」。
///
/// 放在内核里（而不是 UI / 后台任务里）是为了能离线单测 —— 这段逻辑决定
/// 「会不会漏掉一个本来能投的项目」，而且每天自动跑、没人盯着，必须可测。
///
/// 提醒口径（与 Java 版完全一致）：
/// - 只提醒**还没截止**的；
/// - 只提醒**可投**的（成交 / 中标 / 合同类不提醒，见 [NoticeType]）；
/// - 只提醒**剩余天数 ≤ 阈值**的；
/// - 按剩余天数升序，最紧急的排最前。
class DeadlineReminder {
  DeadlineReminder._();

  /// 通知正文里最多列几条，多了看不完也刷屏。
  static const int maxLines = 5;

  /// 挑出需要提醒的公告。
  ///
  /// [withinDays] ≤ 0 视为不提醒，返回空列表。
  static List<TenderRecord> pick(List<TenderRecord> candidates, int withinDays) {
    final result = <TenderRecord>[];
    if (withinDays <= 0) return result;
    for (final record in candidates) {
      if (record.deadlineDate == null) continue;
      if (!record.biddable || record.expired) continue;
      final daysLeft = record.daysLeft;
      // daysLeft 为极小值表示没有截止日期，前面已经挡掉
      if (daysLeft < 0 || daysLeft > withinDays) continue;
      result.add(record);
    }
    result.sort((a, b) => a.daysLeft.compareTo(b.daysLeft));
    return result;
  }

  /// 通知标题，例如「3 条公告即将截止」。
  static String buildTitle(List<TenderRecord> due) => '${due.length} 条公告即将截止';

  /// 通知正文：每行「剩 N 天 · 标题」，最多 [maxLines] 条，
  /// 超出部分用「…等 N 条」收尾。行文案与「采集完成」通知共用 [NewTender.describeFields]。
  static String buildMessage(List<TenderRecord> due) {
    final buffer = StringBuffer();
    final shown = due.length < maxLines ? due.length : maxLines;
    for (var i = 0; i < shown; i++) {
      if (i > 0) buffer.write('\n');
      final record = due[i];
      buffer.write(NewTender.describeFields(record.title, record.deadlineDate));
    }
    if (due.length > shown) {
      buffer.write('\n…等 ${due.length} 条');
    }
    return buffer.toString();
  }
}
