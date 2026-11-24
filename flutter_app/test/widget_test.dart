import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import 'package:flower_tender/core/common/date_utils.dart' as cn;
import 'package:flower_tender/core/models.dart';
import 'package:flower_tender/ui/theme.dart';
import 'package:flower_tender/ui/widgets/badges.dart';
import 'package:flower_tender/ui/widgets/tender_card.dart';

void main() {
  test('截止徽标文案', () {
    expect(DeadlineBadge.textOf(null), '截止日期未知');
    final now = cn.DateUtils.nowCn();
    expect(
      DeadlineBadge.textOf(now.subtract(const Duration(days: 1))),
      '已截止',
    );
    final today = DateTime(now.year, now.month, now.day, 23, 59);
    expect(DeadlineBadge.textOf(today), '今天截止');
  });

  testWidgets('公告卡片显示标题、截止、预算与命中词', (tester) async {
    final now = cn.DateUtils.nowCn();
    final record = TenderRecord()
      ..id = 1
      ..siteCode = 'zh0556'
      ..siteName = '安庆交控集团集采平台'
      ..title = '城区绿化苗木采购项目'
      ..url = 'https://example.com/1'
      ..matchedKeywords = '苗木,绿化'
      ..budgetYuan = 3060000
      ..deadlineDate = DateTime(now.year, now.month, now.day, 23, 59)
          .add(const Duration(days: 2))
      ..publishDate = now.subtract(const Duration(days: 1))
      ..noticeType = 'bid'
      ..linkStatus = 'OK';

    await tester.pumpWidget(
      MaterialApp(
        theme: buildFlowerTenderTheme(brightness: Brightness.light),
        home: Scaffold(
          body: TenderCard(
            record: record,
            onTap: () {},
            onToggleTrack: () {},
          ),
        ),
      ),
    );

    expect(find.text(record.title, findRichText: true), findsOneWidget);
    expect(find.textContaining('剩'), findsOneWidget);
    expect(find.textContaining('306'), findsOneWidget);
    expect(find.text('苗木'), findsWidgets);
  });
}
