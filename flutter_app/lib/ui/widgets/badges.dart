import 'package:material_ui/material_ui.dart';

// material_ui 也导出了 Flutter 的 DateUtils，这里用别名避免冲突。
import '../../core/common/date_utils.dart' as cn;
import '../../core/models.dart';
import 'format.dart';

/// 徽标的语义色。深浅色下取不同的前景色，背景一律用低透明度填充。
enum BadgeTone { neutral, ok, warn, danger, info, money }

class BadgePill extends StatelessWidget {
  const BadgePill(this.text, {super.key, this.tone = BadgeTone.neutral, this.icon});

  final String text;
  final BadgeTone tone;
  final IconData? icon;

  static Color foreground(BuildContext context, BadgeTone tone) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    switch (tone) {
      case BadgeTone.danger:
        return dark ? const Color(0xFFFFB4AB) : const Color(0xFFB3261E);
      case BadgeTone.warn:
        return dark ? const Color(0xFFFFCC80) : const Color(0xFFB45309);
      case BadgeTone.ok:
        return dark ? const Color(0xFF9CD67F) : const Color(0xFF2E7D32);
      case BadgeTone.info:
        return dark ? const Color(0xFF90CAF9) : const Color(0xFF1565C0);
      case BadgeTone.money:
        return dark ? const Color(0xFFFFD180) : const Color(0xFF8A5A00);
      case BadgeTone.neutral:
        return Theme.of(context).colorScheme.onSurfaceVariant;
    }
  }

  @override
  Widget build(BuildContext context) {
    final color = foreground(context, tone);
    final dark = Theme.of(context).brightness == Brightness.dark;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withValues(alpha: dark ? 0.18 : 0.10),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 13, color: color),
            const SizedBox(width: 3),
          ],
          Text(
            text,
            style: TextStyle(
              fontSize: 11.5,
              height: 1.2,
              fontWeight: FontWeight.w600,
              color: color,
            ),
          ),
        ],
      ),
    );
  }
}

/// 截止日期徽标 —— 手机上最关键的一条信息。
class DeadlineBadge extends StatelessWidget {
  const DeadlineBadge({super.key, required this.deadline});

  final DateTime? deadline;

  static String textOf(DateTime? deadline) {
    if (deadline == null) return '截止日期未知';
    if (deadline.isBefore(cn.DateUtils.nowCn())) return '已截止';
    final days = deadline.difference(cn.DateUtils.nowCn()).inDays;
    return days == 0 ? '今天截止' : '剩 $days 天';
  }

  static BadgeTone toneOf(DateTime? deadline) {
    if (deadline == null) return BadgeTone.neutral;
    if (deadline.isBefore(cn.DateUtils.nowCn())) return BadgeTone.neutral;
    final days = deadline.difference(cn.DateUtils.nowCn()).inDays;
    if (days <= 3) return BadgeTone.danger;
    if (days <= 7) return BadgeTone.warn;
    return BadgeTone.ok;
  }

  @override
  Widget build(BuildContext context) {
    return BadgePill(
      textOf(deadline),
      tone: toneOf(deadline),
      icon: Icons.schedule,
    );
  }
}

/// 一条公告的徽标集合（列表卡片与详情页共用）。
List<Widget> tenderBadges(TenderRecord record) {
  final badges = <Widget>[
    DeadlineBadge(deadline: record.deadlineDate),
  ];
  if (record.hasBudget) {
    badges.add(BadgePill(
      budgetLabel(record.budgetYuan),
      tone: BadgeTone.money,
      icon: Icons.payments_outlined,
    ));
  }
  if (record.isNew) {
    badges.add(const BadgePill('新', tone: BadgeTone.info));
  }
  if (!record.biddable) {
    final label = _noticeTypeLabel(record.noticeType);
    if (label.isNotEmpty) badges.add(BadgePill(label, tone: BadgeTone.neutral));
  }
  if (record.accessUrl.trim().isNotEmpty) {
    badges.add(const BadgePill('跳转链接', tone: BadgeTone.neutral));
  }
  if (!record.hasContent) {
    badges.add(const BadgePill('无正文', tone: BadgeTone.neutral));
  }
  if (record.hasAttachment) {
    badges.add(const BadgePill('含附件', tone: BadgeTone.ok, icon: Icons.attach_file));
  }
  if (record.removed) {
    badges.add(const BadgePill('已下架', tone: BadgeTone.danger));
  }
  return badges;
}

/// 本地简化实现，避免 UI 层再 import 一次 NoticeType。
String _noticeTypeLabel(String noticeType) {
  switch (noticeType) {
    case 'award':
      return '结果公告';
    case 'contract':
      return '合同公告';
    case 'aborted':
      return '已终止';
    default:
      return '';
  }
}
