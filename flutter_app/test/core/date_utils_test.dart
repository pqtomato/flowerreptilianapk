import 'package:flutter_test/flutter_test.dart';
import 'package:flower_tender/core/common/date_utils.dart';

void main() {
  group('DateUtils.parse', () {
    test('解析中文日期与「15点00分」', () {
      final value = DateUtils.parse('2026年09月24日15点00分');
      expect(value, isNotNull);
      expect(value!.year, 2026);
      expect(value.month, 9);
      expect(value.day, 24);
      expect(value.hour, 15);
      expect(value.minute, 0);
    });

    test('解析带冒号的时分（不能把 09:30 截成 09:3）', () {
      final value = DateUtils.parse('2026-09-20 09:30');
      expect(value!.hour, 9);
      expect(value.minute, 30);
    });

    test('只写小时也认', () {
      final value = DateUtils.parse('2026年9月20日15时');
      expect(value!.hour, 15);
      expect(value.minute, 0);
    });

    test('非法月日返回 null', () {
      expect(DateUtils.parse('2026-13-40'), isNull);
      expect(DateUtils.parse('2026-02-30'), isNull);
    });

    test('从长文本里抽第一个日期', () {
      final value = DateUtils.parseFromText('发布日期：2026-09-16 16:31 来源：某某');
      expect(value!.month, 9);
      expect(value.day, 16);
      expect(value.hour, 16);
      expect(value.minute, 31);
    });
  });

  group('DateUtils.iso', () {
    test('固定宽度，可按字典序比较', () {
      expect(DateUtils.iso(DateTime(2026, 9, 5, 8, 3, 7)),
          '2026-09-05T08:03:07');
      expect(
        DateUtils.iso(DateTime(2026, 9, 5, 8, 3, 7))
            .compareTo(DateUtils.iso(DateTime(2026, 9, 5, 10))),
        lessThan(0),
      );
    });

    test('往返解析保持一致', () {
      final original = DateTime(2026, 12, 31, 23, 59, 59);
      expect(DateUtils.parseIso(DateUtils.iso(original)), original);
    });
  });

  test('nowCn 是 UTC+8 墙钟时间', () {
    final cn = DateUtils.nowCn();
    // nowCn 返回的是 naive 墙钟时间（不带时区标记），这里也构造成 naive 再比
    final utc8 = DateTime.now().toUtc().add(const Duration(hours: 8));
    final naive = DateTime(
        utc8.year, utc8.month, utc8.day, utc8.hour, utc8.minute, utc8.second);
    expect(cn.difference(naive).inSeconds.abs(), lessThan(2));
  });
}
