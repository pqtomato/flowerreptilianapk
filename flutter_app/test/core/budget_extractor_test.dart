import 'package:flutter_test/flutter_test.dart';
import 'package:flower_tender/core/crawler/budget_extractor.dart';

void main() {
  final extractor = BudgetExtractor();

  test('预算金额：306万元', () {
    final budget = extractor.extract('预算金额：306万元，资金来源：财政。');
    expect(budget.amountYuan, 3060000);
  });

  test('预算金额带小数与千分位', () {
    expect(extractor.extract('预算金额：574099.03元').amountYuan, 574099);
    expect(extractor.extract('预算金额：1,200,000元').amountYuan, 1200000);
  });

  test('「预算金额：/」是无金额，不能当 0', () {
    expect(extractor.extract('预算金额：/').amountYuan, isNull);
  });

  test('最高限价是单价时只留文本、不参与金额', () {
    final budget = extractor.extract('最高限价：3.4元/平方米/年，服务期三年。');
    expect(budget.amountYuan, isNull);
    expect(budget.capText, '3.4元/平方米/年');
  });

  test('最高限价是比例时只留文本', () {
    final budget = extractor.extract('最高限价：审定金额的95%');
    expect(budget.amountYuan, isNull);
    expect(budget.capText, '审定金额的95%');
  });

  test('最高限价后面直接跟下一个字段时不能贪心', () {
    final budget = extractor.extract('最高限价：9.5万元采购需求：详见附件');
    expect(budget.capText, '9.5万元');
  });

  test('最高限价冒号后换行时不能把下一行序号当限价', () {
    final budget = extractor.extract('6、最高限价：\n7、评审方法：综合评分法');
    expect(budget.capText, isNull);
  });

  test('项目编号取第一个并遇到中文截断', () {
    expect(
      extractor
          .extract('项目编号：H0FSCG26D03G0397 FS34081620260014号')
          .projectCode,
      'H0FSCG26D03G0397',
    );
    expect(
      extractor
          .extract('投标人交易项目编号：H1QT26Z100124交易项目名称：苗木采购')
          .projectCode,
      'H1QT26Z100124',
    );
    expect(extractor.extract('项目编号 H3GC26A07G041').projectCode,
        'H3GC26A07G041');
  });

  test('空正文不崩', () {
    expect(extractor.extract(null).amountYuan, isNull);
    expect(extractor.extract('   ').amountYuan, isNull);
  });

  test('金额标签', () {
    expect(BudgetExtractor.label(3060000), '306 万');
    expect(BudgetExtractor.label(1234500), '123.5 万');
    expect(BudgetExtractor.label(9500), '9500 元');
    expect(BudgetExtractor.label(null), '');
  });
}
