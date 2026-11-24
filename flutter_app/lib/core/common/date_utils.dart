/// 政府站群五花八门的日期格式解析（与 Java 内核保持同一口径）。
///
/// 时间一律按「上海墙钟时间」处理：抓到的日期没有时区信息，
/// 存储和比较也统一用同一套 naive 字符串，避免设备在 UTC 时区时偏 8 小时。
class DateUtils {
  DateUtils._();

  /// 中文日期串。时与分之间的分隔符要收全：`15点00分` 是常见写法。
  static final RegExp _cnDate = RegExp(
    r'(\d{4})\s*[-/年.]\s*(\d{1,2})\s*[-/月.]\s*(\d{1,2})[日号]?'
    r'(?:\s*[ T]?\s*(\d{1,2})\s*[:：时点]\s*(\d{1,2})?'
    r'(?:\s*[:：分]\s*(\d{1,2}))?)?',
  );

  static final RegExp _plain = RegExp(
    r'^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})'
    r'(?:[ T](\d{1,2}):(\d{1,2})(?::(\d{1,2}))?)?$',
  );

  /// 解析日期时间，失败返回 null。
  ///
  /// 必须先试「带时间」，再试「仅日期」：否则 `2026-09-17 16:31` 会被日期格式
  /// 解析成当天 00:00（Java 版的 LocalDate.from 也踩过同样的坑）。
  static DateTime? parse(String? raw) {
    if (raw == null) return null;
    final text = raw.trim();
    if (text.isEmpty) return null;

    // ISO 以及 Dart 原生认识的格式（含毫秒、带时区偏移）。
    // 注意：Dart 的 DateTime.parse 会把 2026-13-40 这种非法日期**自动进位**成
    // 2027-02-09，绝不会报错 —— 所以先用正则校验开头的年月日，再交给它。
    if (!text.endsWith('Z') &&
        _hasValidLeadingDate(text) &&
        DateTime.tryParse(text) != null) {
      return DateTime.parse(text);
    }

    final plain = _plain.firstMatch(text);
    if (plain != null) {
      final value = _build(
        int.parse(plain.group(1)!),
        int.parse(plain.group(2)!),
        int.parse(plain.group(3)!),
        plain.group(4) == null ? 0 : int.parse(plain.group(4)!),
        plain.group(5) == null ? 0 : int.parse(plain.group(5)!),
        plain.group(6) == null ? 0 : int.parse(plain.group(6)!),
      );
      if (value != null) return value;
    }

    if (text.length == 14 && RegExp(r'^\d{14}$').hasMatch(text)) {
      return _build(
        int.parse(text.substring(0, 4)),
        int.parse(text.substring(4, 6)),
        int.parse(text.substring(6, 8)),
        int.parse(text.substring(8, 10)),
        int.parse(text.substring(10, 12)),
        int.parse(text.substring(12, 14)),
      );
    }
    return parseFromText(text);
  }

  /// 文本开头是不是真实的年月日（拦截 DateTime.parse 的自动进位）。
  static bool _hasValidLeadingDate(String text) {
    final match = _leadingDate.firstMatch(text);
    if (match == null) return true; // 不是以日期开头，交给 DateTime.tryParse 判断
    final year = int.parse(match.group(1)!);
    final month = int.parse(match.group(2)!);
    final day = int.parse(match.group(3)!);
    return _build(year, month, day, 0, 0, 0) != null;
  }

  static final RegExp _leadingDate =
      RegExp(r'^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})');

  /// 从任意文本中抽取第一个日期时间（用于「发布日期：2026-09-16 16:31」这类串）。
  static DateTime? parseFromText(String? text) {
    if (text == null || text.trim().isEmpty) return null;
    for (final m in _cnDate.allMatches(text)) {
      final value = _build(
        int.parse(m.group(1)!),
        int.parse(m.group(2)!),
        int.parse(m.group(3)!),
        m.group(4) == null ? 0 : int.parse(m.group(4)!),
        m.group(5) == null ? 0 : int.parse(m.group(5)!),
        m.group(6) == null ? 0 : int.parse(m.group(6)!),
      );
      if (value != null) return value;
    }
    return null;
  }

  static DateTime? _build(int year, int month, int day, int hour, int minute, int second) {
    if (month < 1 || month > 12 || day < 1 || day > 31) return null;
    if (hour > 23 || minute > 59 || second > 59) return null;
    final value = DateTime(year, month, day, hour, minute, second);
    if (value.year != year || value.month != month || value.day != day) return null;
    return value;
  }

  /// 当前上海墙钟时间（naive，无时区），全项目统一用它做「现在」。
  static DateTime nowCn() {
    final shanghai = DateTime.now().toUtc().add(const Duration(hours: 8));
    return DateTime(shanghai.year, shanghai.month, shanghai.day,
        shanghai.hour, shanghai.minute, shanghai.second);
  }

  /// 统一存储格式：固定宽度，保证 SQLite 里按字典序比较等价于按时间比较。
  static String iso(DateTime? value) {
    if (value == null) return '';
    String two(int v) => v.toString().padLeft(2, '0');
    return '${value.year}-${two(value.month)}-${two(value.day)}'
        'T${two(value.hour)}:${two(value.minute)}:${two(value.second)}';
  }

  static DateTime? parseIso(String? value) {
    if (value == null || value.isEmpty) return null;
    return DateTime.tryParse(value);
  }

  static String format(DateTime? value) {
    if (value == null) return '未知';
    String two(int v) => v.toString().padLeft(2, '0');
    return '${value.year}-${two(value.month)}-${two(value.day)} '
        '${two(value.hour)}:${two(value.minute)}';
  }
}
