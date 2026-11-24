import 'package:flutter_test/flutter_test.dart';
import 'package:flower_tender/core/common/date_utils.dart';
import 'package:flower_tender/core/models.dart';
import 'package:flower_tender/core/pipeline/deadline_reminder.dart';

TenderRecord record(
  String title, {
  DateTime? deadline,
  String noticeType = 'bid',
}) {
  return TenderRecord()
    ..title = title
    ..deadlineDate = deadline
    ..noticeType = noticeType;
}

void main() {
  final today = DateUtils.nowCn();
  final noonToday = DateTime(today.year, today.month, today.day, 23, 59);

  test('只提醒阈值内、未截止、可投的公告，并按紧急程度升序', () {
    final due = DeadlineReminder.pick([
      record('三天后', deadline: noonToday.add(const Duration(days: 3))),
      record('明天', deadline: noonToday.add(const Duration(days: 1))),
      record('已截止', deadline: noonToday.subtract(const Duration(days: 1))),
      record('中标结果', deadline: noonToday.add(const Duration(days: 2)),
          noticeType: 'award'),
      record('没有截止日期'),
      record('太远', deadline: noonToday.add(const Duration(days: 30))),
    ], 7);

    expect(due.map((r) => r.title), ['明天', '三天后']);
  });

  test('阈值 ≤ 0 视为不提醒', () {
    expect(
      DeadlineReminder.pick(
        [record('明天', deadline: noonToday.add(const Duration(days: 1)))],
        0,
      ),
      isEmpty,
    );
  });

  test('空候选不崩', () {
    expect(DeadlineReminder.pick([], 3), isEmpty);
  });

  test('通知标题与正文最多列 5 条，超出用「…等 N 条」', () {
    final due = List.generate(
      7,
      (index) => record(
        '第 ${index + 1} 条',
        deadline: noonToday.add(Duration(days: index + 1)),
      ),
    );
    expect(DeadlineReminder.buildTitle(due), '7 条公告即将截止');
    final message = DeadlineReminder.buildMessage(due);
    expect(message.split('\n').length, 6);
    expect(message, contains('…等 7 条'));
    expect(message, isNot(contains('第 6 条')));
  });
}
