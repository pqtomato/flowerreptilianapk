import '../common/date_utils.dart';
import '../settings.dart';

/// 从公告正文里解析「报名 / 投标截止时间」「开标时间」。
///
/// 绝大多数站点不提供截止日期字段，只能从正文里抠。抠不到就是**未知**，
/// 不再按「发布日期 + N 天」估算 —— 估出来的日期和真实报名截止毫无关系，
/// 反而会让用户以为「还剩 45 天」，而公告其实早就截止了（实测踩过）。
///
/// 解析优先级：投标/响应截止 > 开标时间 > 获取文件截止。
/// 同一次匹配取「窗口内最后一个日期」，以正确处理「报名时间：9月10日至9月17日」这种区间写法。
class DeadlineExtractor {
  DeadlineExtractor(this.settings);

  final CrawlerSettings settings;

  /// 标记词 → 其后窗口内找日期
  static final List<RegExp> _markers = <RegExp>[
    RegExp(
      r'(?:投标|响应|报价|竞标|申请|递交|提交|上传|接收)'
      r'[^。；;\n]{0,14}?(?:截止|结束)(?:时间|日期|时限)?',
    ),
    // 「报名方式：将…盖公章在<日期>前发送…」——日期在标记之后，
    // 且这往往才是真正"还能不能报名"的截止点（交控集采平台全是这种写法）
    RegExp(r'(?:报名|参选)[^。；;\n]{0,8}?(?:截止|结束|方式)'),
    RegExp(r'(?:开标|磋商|谈判|评审|竞价|比选)(?:时间|日期|起始时间)'),
    RegExp(r'(?:获取|领取|下载|购买)[^。；;\n]{0,14}?(?:截止|结束)(?:时间|日期)?'),
    RegExp(
      r'(?:报名|获取|领取|下载|公示)[^。；;\n]{0,12}?(?:时间|期限|日期)'
      r'[^。；;\n]{0,20}?至',
    ),
  ];

  /// 窗口边界：遇到这些符号就不再往右看，避免读到下一句话里的日期
  static const List<String> _boundaries = <String>['。', '；', ';', '\n', '，'];

  static const int _window = 46;

  /// 正文里的日期时间。
  ///
  /// 时与分之间的分隔符要把「点」也收进来 —— 政府公告里
  /// `2026年09月24日15点00分` 是常见写法（实测原文）。不收的话时分被整段丢掉，
  /// 只剩 00:00，截止当天上午就会被判成"已截止"，而实际还能投到下午三点。
  static final RegExp _dateInText = RegExp(
    r'\d{4}\s*[-/年.]\s*\d{1,2}\s*[-/月.]\s*\d{1,2}'
    // 分钟可省：「15点」和「15点00分」都要认。
    // 注意必须是 (?:\d{1,2})? 而不是 \d{1,2}? —— 后者是"懒惰匹配"，
    // 会优先只吃 1 位数字，把 09:30 截成 09:3 再解析成 09:03（踩过）
    r'(?:[日号]?\s*[ T]?\s*\d{1,2}\s*[:：时点]\s*(?:\d{1,2})?'
    r'(?:\s*[:：分]\s*\d{1,2})?)?',
  );

  /// 解析截止时间。正文里没有明确日期时返回 [Deadline.none]，
  /// **不做任何估算**。
  Deadline resolve(String? content, DateTime? publishDate) {
    final maxDays = settings.deadline.maxDays;

    if (settings.deadline.parseFromContent &&
        content != null &&
        content.trim().isNotEmpty) {
      for (final marker in _markers) {
        final parsed = _parseAfter(marker, content, publishDate, maxDays);
        if (parsed != null) {
          return Deadline(parsed, '正文解析');
        }
      }
    }
    return Deadline.none;
  }

  DateTime? _parseAfter(
    RegExp marker,
    String content,
    DateTime? publishDate,
    int maxDays,
  ) {
    for (final match in marker.allMatches(content)) {
      final from = match.end;
      final to = from + _window < content.length
          ? from + _window
          : content.length;
      final window = _limitWindow(content.substring(from, to));

      final dates = _allDates(window);
      for (var i = dates.length - 1; i >= 0; i--) {
        final candidate = dates[i];
        if (_isSane(candidate, publishDate, maxDays)) {
          return candidate;
        }
      }
    }
    return null;
  }

  /// 把取值窗口限制在当前分句内。
  ///
  /// 必须做这个限制：像「投标截止时间：2026-09-20 09:30。开标时间：2026-09-21 09:30。」
  /// 这样的句子，如果窗口跨到下一句，取"窗口内最后一个日期"就会选中开标时间。
  String _limitWindow(String window) {
    var boundary = window.length;
    for (final terminator in _boundaries) {
      final index = window.indexOf(terminator);
      if (index >= 0 && index < boundary) boundary = index;
    }
    for (final marker in _markers) {
      final match = marker.firstMatch(window);
      if (match != null && match.start > 0 && match.start < boundary) {
        boundary = match.start;
      }
    }
    return window.substring(0, boundary < 0 ? 0 : boundary);
  }

  List<DateTime> _allDates(String text) {
    final dates = <DateTime>[];
    for (final match in _dateInText.allMatches(text)) {
      final parsed = DateUtils.parse(match.group(0));
      if (parsed != null) dates.add(parsed);
    }
    return dates;
  }

  /// 合理性校验：不能早于发布日期，也不能离谱地远（超过 maxDays 视为误判）。
  bool _isSane(DateTime candidate, DateTime? publishDate, int maxDays) {
    if (publishDate == null) return true;
    if (candidate.isBefore(publishDate)) return false;
    if (maxDays <= 0) return true;
    // 用日历加法而不是 Duration：设备处于夏令时区时，加 24 小时的倍数会偏移 1 小时
    final limit = DateTime(
      publishDate.year,
      publishDate.month,
      publishDate.day + maxDays,
      publishDate.hour,
      publishDate.minute,
      publishDate.second,
    );
    return !candidate.isAfter(limit);
  }
}

/// 解析结果。
class Deadline {
  final DateTime? date;
  final String source;

  const Deadline(this.date, this.source);

  static const Deadline none = Deadline(null, '');
}
