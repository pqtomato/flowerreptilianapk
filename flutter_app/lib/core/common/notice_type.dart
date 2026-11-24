/// 公告类型判定 —— 这条公告「还能不能投」。
///
/// 判定原则：只排除「确定不是机会」的。认不出来的一律当可投，
/// 宁可多显示一条，也不要把真正的招标公告藏起来。
class NoticeType {
  NoticeType._();

  static const String bid = 'bid';
  static const String award = 'award';
  static const String contract = 'contract';
  static const String aborted = 'aborted';

  static final RegExp _contract = RegExp('合同公告|合同公示|采购合同|合同备案|合同签订|合同变更|合同履行情况');
  static final RegExp _award = RegExp('成交|中标|中选|结果公告|结果公示|中标候选人|中标通知书|评标结果|定标结果|成交结果');
  static final RegExp _aborted = RegExp('流标|废标|终止公告|中止公告|招标失败|采购失败|终止采购|废标公告|终止招标');

  /// 顺序很重要：合同 > 结果 > 终止。
  static String classify(String? category, String? title) {
    final text = '${category ?? ''} ${title ?? ''}';
    if (_contract.hasMatch(text)) return contract;
    if (_award.hasMatch(text)) return award;
    if (_aborted.hasMatch(text)) return aborted;
    return bid;
  }

  static bool isBiddable(String? type) =>
      type == null || type.isEmpty || type == bid;

  static String label(String? type) {
    switch (type) {
      case award:
        return '结果公告';
      case contract:
        return '合同公告';
      case aborted:
        return '已终止';
      default:
        return '';
    }
  }
}
