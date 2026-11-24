import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';
import 'package:share_plus/share_plus.dart';
import 'package:url_launcher/url_launcher.dart';

import '../core/models.dart';
import '../services/app_services.dart';
import 'widgets/badges.dart';
import 'widgets/format.dart';
import 'widgets/states.dart';

/// 公告详情：把「还能不能投、投什么、去哪投」一次说清。
class TenderDetailPage extends StatefulWidget {
  const TenderDetailPage({
    super.key,
    required this.record,
    this.onChanged,
  });

  final TenderRecord record;

  /// 收藏状态等变化时通知列表刷新这一行。
  final ValueChanged<TenderRecord>? onChanged;

  @override
  State<TenderDetailPage> createState() => _TenderDetailPageState();
}

class _TenderDetailPageState extends State<TenderDetailPage> {
  TenderRecord get _record => widget.record;

  String get _link => displayUrl(_record.url, _record.accessUrl);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final attachments = parseAttachments(_record.attachments);
    final content = _record.content.trim().isNotEmpty
        ? _record.content
        : (_record.summary.trim().isNotEmpty ? _record.summary : '');
    final statusBanner = _linkStatusBanner(context);

    return Scaffold(
      appBar: AppBar(
        title: Text(siteShortName(_record.siteCode).isEmpty
            ? '公告详情'
            : siteShortName(_record.siteCode)),
        actions: [
          IconButton(
            tooltip: _record.tracked ? '取消收藏' : '收藏',
            onPressed: _toggleTrack,
            icon: Icon(
              _record.tracked ? Icons.star_rounded : Icons.star_border_rounded,
              color: _record.tracked ? const Color(0xFFF2A600) : null,
            ),
          ),
          const SizedBox(width: 4),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              _record.title,
              style: theme.textTheme.titleLarge?.copyWith(
                fontWeight: FontWeight.w700,
                height: 1.42,
              ),
            ),
            const SizedBox(height: 12),
            Wrap(spacing: 6, runSpacing: 6, children: tenderBadges(_record)),
            if (statusBanner != null) ...[
              const SizedBox(height: 14),
              statusBanner,
            ],
            const SizedBox(height: 16),
            _buildInfoCard(context),
            if (attachments.isNotEmpty) ...[
              const SizedBox(height: 12),
              SectionCard(
                title: '附件（${attachments.length}）',
                icon: Icons.attach_file,
                child: Column(
                  children: [
                    for (final attachment in attachments)
                      ListTile(
                        dense: true,
                        contentPadding: EdgeInsets.zero,
                        leading: const Icon(Icons.description_outlined, size: 20),
                        title: Text(
                          attachment.name,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.bodyMedium,
                        ),
                        trailing: const Icon(Icons.open_in_new, size: 16),
                        onTap: () => _openUrl(attachment.url, what: '附件'),
                      ),
                  ],
                ),
              ),
            ],
            const SizedBox(height: 12),
            SectionCard(
              title: '公告正文',
              icon: Icons.article_outlined,
              child: content.isEmpty
                  ? Text(
                      '没有抓到正文。可以点下方「打开原文」查看站点上的完整内容。',
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: scheme.onSurfaceVariant,
                        height: 1.6,
                      ),
                    )
                  : SelectableText(
                      content,
                      style: theme.textTheme.bodyMedium?.copyWith(
                        fontSize: 14.5,
                        height: 1.72,
                        color: scheme.onSurface,
                      ),
                    ),
            ),
          ],
        ),
      ),
      bottomNavigationBar: _buildBottomBar(context),
    );
  }

  Widget _buildInfoCard(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final siteLine = [
      siteShortName(_record.siteCode),
      if (_record.region.isNotEmpty) _record.region,
      if (_record.category.isNotEmpty) _record.category,
    ].where((part) => part.isNotEmpty).join(' · ');
    final scope = _scopeLabel(_record.matchedScope);

    Widget row(String label, String value, {Widget? trailing, Color? valueColor}) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 5),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: 72,
              child: Text(
                label,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
              ),
            ),
            Expanded(
              child: Text(
                value,
                style: theme.textTheme.bodyMedium?.copyWith(
                  height: 1.45,
                  color: valueColor ?? scheme.onSurface,
                ),
              ),
            ),
            ?trailing,
          ],
        ),
      );
    }

    return SectionCard(
      title: '关键信息',
      icon: Icons.fact_check_outlined,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (siteLine.isNotEmpty) row('来源', siteLine),
          row('发布', formatDateTime(_record.publishDate)),
          row(
            '截止',
            _record.deadlineDate == null
                ? '未写明（不做估算）'
                : '${formatDateTime(_record.deadlineDate)}'
                    '${_record.deadlineSource.isEmpty ? '' : ' · ${_record.deadlineSource}'}',
            trailing: DeadlineBadge(deadline: _record.deadlineDate),
          ),
          if (_record.hasBudget) row('预算金额', budgetLabel(_record.budgetYuan)),
          if (_record.budgetCapText.trim().isNotEmpty)
            row('最高限价', _record.budgetCapText.trim()),
          if (_record.projectCode.trim().isNotEmpty)
            row('项目编号', _record.projectCode.trim()),
          row(
            '命中词',
            _record.matchedKeywords.trim().isEmpty
                ? '—'
                : _record.matchedKeywords.trim() + (scope.isEmpty ? '' : '（$scope）'),
          ),
          if (_record.verifiedAt != null)
            row('链接校验', formatDateTime(_record.verifiedAt)),
        ],
      ),
    );
  }

  Widget? _linkStatusBanner(BuildContext context) {
    switch (_record.linkStatus) {
      case 'FALLBACK':
        return const _NoticeBanner(
          icon: Icons.alt_route,
          tone: BadgeTone.info,
          text: '县级转载公告：本站没有落地页，打开的是交易网的检索页。',
        );
      case 'REMOVED':
        return const _NoticeBanner(
          icon: Icons.link_off,
          tone: BadgeTone.danger,
          text: '原站已下架这条公告，打开可能显示页面不存在。',
        );
      case 'UNKNOWN':
        return const _NoticeBanner(
          icon: Icons.help_outline,
          tone: BadgeTone.neutral,
          text: '尚未校验原链接是否有效，重要信息请以浏览器打开的原文为准。',
        );
      default:
        return null;
    }
  }

  Widget _buildBottomBar(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return SafeArea(
      top: false,
      child: Container(
        padding: const EdgeInsets.fromLTRB(16, 8, 12, 10),
        decoration: BoxDecoration(
          color: Theme.of(context).colorScheme.surface,
          border: Border(
            top: BorderSide(color: scheme.outlineVariant.withValues(alpha: 0.6)),
          ),
        ),
        child: Row(
          children: [
            Expanded(
              child: FilledButton.icon(
                onPressed: () => _openUrl(_link, what: '原文'),
                icon: const Icon(Icons.open_in_new, size: 18),
                label: Text(_record.accessUrl.trim().isNotEmpty ? '打开跳转页' : '打开原文'),
              ),
            ),
            IconButton(
              tooltip: '复制标题和链接',
              onPressed: _copy,
              icon: const Icon(Icons.copy_all_outlined),
            ),
            IconButton(
              tooltip: '分享',
              onPressed: _share,
              icon: const Icon(Icons.share_outlined),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _toggleTrack() async {
    final next = !_record.tracked;
    try {
      await AppServices.instance.repository.setTracked(_record.id, next);
      if (!mounted) return;
      setState(() => _record.tracked = next);
      widget.onChanged?.call(_record);
      _snack(next ? '已收藏' : '已取消收藏');
    } catch (e) {
      if (!mounted) return;
      _snack('操作失败：$e');
    }
  }

  Future<void> _openUrl(String raw, {required String what}) async {
    final url = raw.trim();
    if (url.isEmpty) {
      _snack('这条公告没有可打开的$what链接');
      return;
    }
    final uri = Uri.tryParse(url);
    if (uri == null || !uri.hasScheme) {
      _snack('链接无效：$url');
      return;
    }
    try {
      final ok = await launchUrl(uri, mode: LaunchMode.externalApplication);
      if (!ok) _snack('没有可以打开该链接的应用');
    } catch (e) {
      _snack('打开失败：$e');
    }
  }

  Future<void> _copy() async {
    final buffer = StringBuffer(_record.title.trim());
    if (_link.trim().isNotEmpty) {
      buffer
        ..writeln()
        ..write(_link.trim());
    }
    if (_record.projectCode.trim().isNotEmpty) {
      buffer
        ..writeln()
        ..write('项目编号：${_record.projectCode.trim()}');
    }
    if (_record.hasBudget) {
      buffer
        ..writeln()
        ..write('预算金额：${budgetLabel(_record.budgetYuan)}');
    }
    if (_record.deadlineDate != null) {
      buffer
        ..writeln()
        ..write('截止：${formatDateTime(_record.deadlineDate)}');
    }
    await Clipboard.setData(ClipboardData(text: buffer.toString()));
    if (!mounted) return;
    _snack('已复制标题和链接');
  }

  Future<void> _share() async {
    final parts = <String>[
      _record.title.trim(),
      if (_link.trim().isNotEmpty) _link.trim(),
      if (_record.deadlineDate != null) '截止：${formatDateTime(_record.deadlineDate)}',
      if (_record.hasBudget) '预算金额：${budgetLabel(_record.budgetYuan)}',
    ];
    try {
      await SharePlus.instance.share(
        ShareParams(subject: _record.title.trim(), text: parts.join('\n')),
      );
    } catch (e) {
      if (!mounted) return;
      _snack('分享失败：$e');
    }
  }

  void _snack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }
}

class _NoticeBanner extends StatelessWidget {
  const _NoticeBanner({
    required this.icon,
    required this.text,
    required this.tone,
  });

  final IconData icon;
  final String text;
  final BadgeTone tone;

  @override
  Widget build(BuildContext context) {
    final color = BadgePill.foreground(context, tone);
    final dark = Theme.of(context).brightness == Brightness.dark;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: color.withValues(alpha: dark ? 0.16 : 0.09),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 17, color: color),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              text,
              style: TextStyle(fontSize: 12.5, height: 1.45, color: color),
            ),
          ),
        ],
      ),
    );
  }
}

String _scopeLabel(String scope) {
  switch (scope) {
    case 'TITLE':
      return '标题';
    case 'CONTENT':
      return '正文';
    case 'TITLE,CONTENT':
      return '标题+正文';
    default:
      return '';
  }
}
