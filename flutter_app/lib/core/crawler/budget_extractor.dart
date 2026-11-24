/// 从公告正文里抠「预算金额 / 项目编号 / 最高限价」。
///
/// 为什么只把「预算金额」当钱：真实正文里 `最高限价` 有三种写法 ——
/// `9.5万元`（总额）、`3.4元/平方米/年`（**单价**）、`审定金额的95%`（比例）。
/// 后两种当总额排序会得出荒谬结果，所以限价只留文本给人看，不参与排序。
///
/// 写法样本全部来自实测抓到的正文（见 Java 版 `BudgetExtractorTest`）：
/// - `预算金额：306万元` / `预算金额 3060000元`（有冒号/无冒号、万元/元）
/// - `预算金额：574099.03元`（带小数）
/// - `预算金额：/`（**空值**，不能当成 0）
/// - `项目编号：H0FSCG26D03G0397 FS34081620260014号`（两个编号，取第一个）
/// - `项目编号 H3GC26A07G041`（无冒号）
/// - `投标人交易项目编号：H1QT26Z100124交易项目名称：…`（中文紧贴，必须截断）
class BudgetExtractor {
  /// 预算金额：只认这几个明确说法，不用泛化的"预算"以免误命中"预算单位"之类
  static final RegExp _amount = RegExp(
    r'(?:采购预算|项目预算|预算金额|预算总价)[：:\s]*'
    r'([0-9][0-9,]*(?:\.[0-9]+)?)\s*(万元|万|元)',
  );

  /// 单价后缀的单位白名单，形如 `/平方米/年`。
  ///
  /// 为什么必须白名单：币种后面直接跟着下一个字段时没有任何分隔符
  /// （`3.4元/平方米/年采购需求`），靠"长度不超过 N 个字"分不出
  /// `/年` 和 `/年采购需求` —— 只有枚举出真实单位才切得干净。
  static final RegExp _capAmount = RegExp(
    r'最高限价[：: \t]*([0-9][0-9,]*(?:\.[0-9]+)?\s*(?:万元|万|元)'
    r'(?:/(?:平方米|㎡|m2|公里|千米|公顷|亩|株|棵|个|台|套|项|人|天|年|月|日|次|米))*)',
  );

  /// 最高限价（文字形态）：单价说明、比例、甚至一段解释。
  ///
  /// 两个细节都是踩出来的：
  /// - 分隔符**不允许换行**：真实公告里 `6、最高限价：\n7、评审方法：…`
  ///   很常见，一旦让 `\s` 吃掉换行，就会把下一行的序号 "7" 当限价；
  /// - 取值集合要**排除冒号**：否则分隔符匹配 0 个字符回溯时，
  ///   冒号本身会被当成限价文本抓进来。
  static final RegExp _capText = RegExp(r'最高限价[：: \t]*([^\s，。；;、：:]{1,40})');

  /// 项目编号：值基本是 ASCII 字母数字加连字符，遇到中文自然停下
  static final RegExp _projectCode = RegExp(
    r'(?:采购项目编号|交易项目编号|招标编号|采购编号|项目编号)[：: \t]*'
    r'([A-Za-z0-9][A-Za-z0-9\-]{2,})',
  );

  /// 解析结果；三项都可能为 null。
  Budget extract(String? content) {
    if (content == null || content.trim().isEmpty) return Budget.none;
    return Budget(
      _parseAmount(content),
      _parseCap(content),
      _parseProjectCode(content),
    );
  }

  int? _parseAmount(String content) {
    final match = _amount.firstMatch(content);
    if (match == null) return null;
    return toYuan(match.group(1) ?? '', match.group(2) ?? '');
  }

  String? _parseCap(String content) {
    // 先试"数字 + 单位"：这种情况后面常常直接跟着下一个字段，不能贪心
    final amount = _capAmount.firstMatch(content);
    if (amount != null) {
      return amount.group(1)?.trim();
    }
    final text = _capText.firstMatch(content);
    if (text == null) return null;
    final captured = (text.group(1) ?? '').trim();
    // 全是数字说明抓到的是下一行的序号之类，不是限价
    return RegExp(r'^[0-9.,]+$').hasMatch(captured) ? null : captured;
  }

  String? _parseProjectCode(String content) {
    final match = _projectCode.firstMatch(content);
    return match?.group(1);
  }

  /// 「306万元」→ 3060000；「574099.03元」→ 574099（四舍五入到元）。
  static int? toYuan(String number, String unit) {
    final digits = number.replaceAll(',', '').trim();
    if (digits.isEmpty) return null;
    final value = double.tryParse(digits);
    if (value == null) return null;
    var yuan = value;
    if (unit == '万元' || unit == '万') {
      yuan *= 10000;
    }
    if (yuan <= 0) {
      // 「预算金额：/」这类空值会走不到这里（正则要求数字），
      // 但 0 元也没有排序意义，一律当作"没有金额"
      return null;
    }
    return yuan.round();
  }

  /// 给界面用的短标签：「306 万」/「9500 元」。没有金额返回空串。
  static String label(int? yuan) {
    if (yuan == null || yuan <= 0) return '';
    if (yuan >= 10000) {
      final wan = yuan / 10000;
      if (wan == wan.floorToDouble()) {
        return '${wan.toInt()} 万';
      }
      return '${wan.toStringAsFixed(1)} 万';
    }
    return '$yuan 元';
  }
}

/// 解析结果；三项都可能为 null。
class Budget {
  final int? amountYuan;
  final String? capText;
  final String? projectCode;

  const Budget(this.amountYuan, this.capText, this.projectCode);

  static const Budget none = Budget(null, null, null);
}
