/// epoint 站群的分类编码 → 中文分类名。
class CategoryNames {
  CategoryNames._();

  static const String unknownCategory = '其他交易';

  static const Map<String, String> _names = {
    '003001': '工程建设',
    '003001002': '招标公告',
    '003001005': '答疑澄清',
    '003001009': '中标候选人公示',
    '003001011': '中标结果公示',
    '003001013': '合同公告',
    '003001014': '项目异常',
    '003001019': '招标文件预公示',
    '003002': '政府采购',
    '003002009': '成交公告',
    '003002010': '采购合同公告',
    '003002014': '采购意向公开',
    '003003': '国有产权',
    '003004': '土地矿权',
    '003005': '自主交易',
    '003006': '其他交易',
    '003008': '其他交易',
    '002001': '工程',
    '002002': '货物',
    '002003': '服务',
    '002004': '竞价采购',
    '002008': '标前公示',
  };

  /// 解析分类名：精确 → 6 位大类 → 3 位段 → 其他交易。
  static String resolve(String? code) {
    if (code == null || code.trim().isEmpty) return '';
    final value = code.trim();
    final exact = _names[value];
    if (exact != null) return exact;
    if (value.length >= 6) {
      final parent = _names[value.substring(0, 6)];
      if (parent != null) return parent;
    }
    if (value.length >= 3) {
      final section = _names[value.substring(0, 3)];
      if (section != null) return section;
    }
    return RegExp(r'^\d+$').hasMatch(value) ? unknownCategory : value;
  }
}
