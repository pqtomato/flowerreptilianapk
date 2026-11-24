import 'dart:convert';

import 'package:material_ui/material_ui.dart';

// 注意：material_ui 也导出了 Flutter 自带的 DateUtils，这里必须起别名，
// 否则同文件里两个 DateUtils 会冲突。
import '../../core/common/date_utils.dart' as cn;

/// 站点简称：卡片和日志里只放得下短名字。
const Map<String, String> kSiteShortNames = {
  'all': '全部站点',
  'aqggzy': '公共资源交易网',
  'cgj': '城管局',
  'wyxmzx': '阳光采购平台',
  'zh0556': '交控集采平台',
};

String siteShortName(String code) {
  if (code.isEmpty) return '';
  return kSiteShortNames[code] ?? code;
}

String _two(int value) => value.toString().padLeft(2, '0');

/// `2026-09-17`
String formatDay(DateTime? value) {
  if (value == null) return '未知';
  return '${value.year}-${_two(value.month)}-${_two(value.day)}';
}

/// `09-17 15:00`（列表、日志里的紧凑写法）
String formatShortTime(DateTime? value) {
  if (value == null) return '未知';
  return '${_two(value.month)}-${_two(value.day)} ${_two(value.hour)}:${_two(value.minute)}';
}

/// `2026-09-17 15:00`
String formatDateTime(DateTime? value) => cn.DateUtils.format(value);

/// 按「现在」计算剩余天数（今天截止 = 0，已截止为负）。
int deadlineDaysLeft(DateTime? deadline) {
  if (deadline == null) return -1000000000;
  return deadline.difference(cn.DateUtils.nowCn()).inDays;
}

bool isDeadlineExpired(DateTime? deadline) {
  if (deadline == null) return false;
  return deadline.isBefore(cn.DateUtils.nowCn());
}

/// 预算金额短标签，与 Java `BudgetExtractor.label` 同一口径：
/// 「306 万」/「1.5 万」/「9500 元」。没有金额返回空串。
String budgetLabel(int? yuan) {
  if (yuan == null || yuan <= 0) return '';
  if (yuan >= 10000) {
    final wan = yuan / 10000;
    if (wan == wan.floorToDouble()) return '${wan.toStringAsFixed(0)} 万';
    return '${wan.toStringAsFixed(1)} 万';
  }
  return '$yuan 元';
}

/// 点击/分享时优先用 accessUrl（县级转载公告的跳转页）。
String displayUrl(String url, String accessUrl) {
  final fallback = accessUrl.trim();
  return fallback.isNotEmpty ? fallback : url.trim();
}

/// 附件在库里是 JSON 数组，元素形如 `名称 :: https://…`。
class AttachmentRef {
  const AttachmentRef(this.name, this.url);

  final String name;
  final String url;
}

List<AttachmentRef> parseAttachments(String raw) {
  final text = raw.trim();
  if (text.isEmpty) return const [];

  List<String> items;
  try {
    final decoded = jsonDecode(text);
    if (decoded is List) {
      items = decoded.map((e) => e.toString()).toList();
    } else if (decoded is String) {
      items = [decoded];
    } else {
      items = const [];
    }
  } catch (_) {
    // 老数据可能是一行一个链接，退化成按行拆
    items = text.split('\n');
  }

  final list = <AttachmentRef>[];
  for (final item in items) {
    final line = item.trim();
    if (line.isEmpty) continue;
    final separator = line.indexOf(' :: ');
    if (separator > 0) {
      list.add(AttachmentRef(
        line.substring(0, separator).trim(),
        line.substring(separator + 4).trim(),
      ));
    } else {
      list.add(AttachmentRef(line, line.startsWith('http') ? line : ''));
    }
  }
  return list;
}

/// 把标题里的命中词挑出来高亮。
///
/// 先收集所有命中区间再合并，避免「月季」与「月季花」重叠时切出残缺片段。
List<TextSpan> highlightTitle(
  String title,
  String matchedKeywords, {
  required TextStyle highlight,
}) {
  if (title.isEmpty || matchedKeywords.isEmpty) {
    return [TextSpan(text: title)];
  }
  final words = matchedKeywords
      .split(',')
      .map((word) => word.trim())
      .where((word) => word.length > 1)
      .take(12)
      .toList();
  if (words.isEmpty) return [TextSpan(text: title)];

  final ranges = <List<int>>[];
  for (final word in words) {
    var start = 0;
    while (start < title.length) {
      final index = title.indexOf(word, start);
      if (index < 0) break;
      ranges.add([index, index + word.length]);
      start = index + word.length;
    }
  }
  if (ranges.isEmpty) return [TextSpan(text: title)];

  ranges.sort((a, b) => a[0].compareTo(b[0]));
  final merged = <List<int>>[];
  for (final range in ranges) {
    if (merged.isNotEmpty && range[0] <= merged.last[1]) {
      if (range[1] > merged.last[1]) merged.last[1] = range[1];
    } else {
      merged.add([range[0], range[1]]);
    }
  }

  final spans = <TextSpan>[];
  var cursor = 0;
  for (final range in merged) {
    if (range[0] > cursor) {
      spans.add(TextSpan(text: title.substring(cursor, range[0])));
    }
    spans.add(TextSpan(
      text: title.substring(range[0], range[1]),
      style: highlight,
    ));
    cursor = range[1];
  }
  if (cursor < title.length) {
    spans.add(TextSpan(text: title.substring(cursor)));
  }
  return spans;
}

/// 命中词列表（去空、截断）。
List<String> matchedKeywordList(String raw, {int limit = 6}) {
  return raw
      .split(',')
      .map((word) => word.trim())
      .where((word) => word.isNotEmpty)
      .take(limit)
      .toList();
}

/// 采集状态 → 中文。无法识别时原样显示。
String crawlStatusLabel(String status) {
  switch (status.toUpperCase()) {
    case 'SUCCESS':
    case 'OK':
    case 'DONE':
      return '成功';
    case 'FAILED':
    case 'FAIL':
      return '失败';
    case 'RUNNING':
      return '进行中';
    case 'SKIPPED':
      return '已跳过';
    case 'PARTIAL':
      return '部分成功';
    default:
      return status.isEmpty ? '—' : status;
  }
}
