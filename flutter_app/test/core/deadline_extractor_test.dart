import 'package:flutter_test/flutter_test.dart';
import 'package:flower_tender/core/crawler/deadline_extractor.dart';
import 'package:flower_tender/core/settings.dart';

void main() {
  final extractor = DeadlineExtractor(CrawlerSettings());
  final publish = DateTime(2026, 9, 1, 10);

  test('认「15点00分」的时分，不能只剩 00:00', () {
    final deadline = extractor.resolve(
      '投标截止时间：2026年09月24日15点00分，逾期不予受理。',
      publish,
    );
    expect(deadline.date, isNotNull);
    expect(deadline.date, DateTime(2026, 9, 24, 15, 0));
    expect(deadline.source, '正文解析');
  });

  test('区间写法取结束日', () {
    final deadline = extractor.resolve(
      '报名时间：2026年9月10日至2026年9月17日17:00。',
      publish,
    );
    expect(deadline.date, DateTime(2026, 9, 17, 17, 0));
  });

  test('「报名方式…在日期前发送」这种写法也要认', () {
    final deadline = extractor.resolve(
      '报名方式：将报名材料加盖公章在2026年9月8日17时前发送至邮箱。',
      publish,
    );
    expect(deadline.date, DateTime(2026, 9, 8, 17, 0));
  });

  test('投标截止优先于开标时间', () {
    final deadline = extractor.resolve(
      '开标时间：2026年9月21日09:30。投标截止时间：2026年9月20日09:30。',
      publish,
    );
    expect(deadline.date, DateTime(2026, 9, 20, 9, 30));
  });

  test('正文没写日期就是未知，绝不按发布日期估算', () {
    final deadline = extractor.resolve('本项目采用综合评分法，详见招标文件。', publish);
    expect(deadline.date, isNull);
    expect(deadline.source, isEmpty);
  });

  test('早于发布日期的日期视为误判', () {
    final deadline = extractor.resolve(
      '投标截止时间：2026年09月01日09:00。',
      DateTime(2026, 9, 20),
    );
    expect(deadline.date, isNull);
  });

  test('「报名已结束」不是截止时间', () {
    final deadline = extractor.resolve('[报名已结束] 城区绿化养护项目', publish);
    expect(deadline.date, isNull);
  });

  test('超过 maxDays 的离谱日期丢弃', () {
    final deadline = extractor.resolve(
      '投标截止时间：2030年09月24日15点00分',
      publish,
    );
    expect(deadline.date, isNull);
  });

  test('关闭正文解析后直接返回未知', () {
    final settings = CrawlerSettings()..deadline.parseFromContent = false;
    final disabled = DeadlineExtractor(settings);
    expect(
      disabled
          .resolve('投标截止时间：2026年09月24日15点00分', publish)
          .date,
      isNull,
    );
  });
}
