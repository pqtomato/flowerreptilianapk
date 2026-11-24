/// 文本清洗小工具（与 Java 内核口径一致）。
class TextUtils {
  TextUtils._();

  /// 还原 &nbsp;、压缩空白与空行。
  static String normalize(String? text) {
    if (text == null) return '';
    return text
        .replaceAll(' ', ' ')
        .replaceAll(RegExp(r'[\t\x0B\f\r]+'), ' ')
        .replaceAll(RegExp(r' {2,}'), ' ')
        .replaceAll(RegExp(r'(\s*\n\s*)+'), '\n')
        .trim();
  }

  /// 压成单行（标题、摘要用）。
  static String oneLine(String? text) {
    if (text == null) return '';
    return text
        .replaceAll(' ', ' ')
        .replaceAll(RegExp(r'\s+'), ' ')
        .trim();
  }

  static String truncate(String? value, int max) {
    if (value == null) return '';
    return value.length <= max ? value : value.substring(0, max);
  }

  static String join(Iterable<String> values) => values.join(',');
}
