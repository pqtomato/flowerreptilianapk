import 'package:material_ui/material_ui.dart';

import '../core/models.dart';
import '../services/app_services.dart';
import 'widgets/badges.dart';
import 'widgets/format.dart';
import 'widgets/states.dart';

/// 采集页：选站点/时间窗口/检索词数量，一键跑，完成后看分站结果。
class CrawlPage extends StatefulWidget {
  const CrawlPage({super.key, this.onCrawlFinished});

  /// 采集完成（或失败）后通知首页刷新公告列表。
  final VoidCallback? onCrawlFinished;

  @override
  State<CrawlPage> createState() => CrawlPageState();
}

class CrawlPageState extends State<CrawlPage> {
  String _site = 'all';
  int _lookback = 30;
  int? _maxKeywords = 20;

  bool _loading = true;
  bool _running = false;
  String _progress = '';
  String? _error;

  CrawlSummary? _summary;
  Map<String, int> _siteTotals = {};
  int _total = 0;
  int _openCount = 0;
  int _newCount = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// 外部刷新入口。
  Future<void> refresh() => _load();

  Future<void> _load() async {
    try {
      final services = AppServices.instance;
      final repository = services.repository;
      final total = await repository.count();
      final open = await repository.countQuery(TenderQuery()..onlyOpen = true);
      final fresh = await repository.countQuery(TenderQuery()..onlyNew = true);

      final siteTotals = <String, int>{};
      for (final code in kSiteShortNames.keys) {
        if (code == 'all') continue;
        siteTotals[code] = await repository.countBySite(code);
      }

      if (!mounted) return;
      setState(() {
        _summary = services.loadLastSummary();
        _siteTotals = siteTotals;
        _total = total;
        _openCount = open;
        _newCount = fresh;
        _loading = false;
        _error = null;
        if (services.engine.isRunning && !_running) {
          _running = true;
          _progress = '后台采集进行中…';
        }
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = e.toString();
      });
    }
  }

  Future<void> _startCrawl() async {
    if (_running) return;
    setState(() {
      _running = true;
      _progress = '正在启动采集…';
    });
    try {
      final summary = await AppServices.instance.runCrawl(
        siteCode: _site,
        maxKeywords: _maxKeywords,
        lookbackDays: _lookback,
        triggerType: 'MANUAL',
        onProgress: (message) {
          if (mounted) setState(() => _progress = message);
        },
      );
      if (!mounted) return;
      await _load();
      if (!mounted) return;
      setState(() {
        _running = false;
        _progress = '';
      });
      widget.onCrawlFinished?.call();
      if (summary.status == 'FAILED') {
        _snack('采集失败：${summary.message.isEmpty ? '未知错误' : summary.message}');
      } else {
        _snack('采集完成：新增 ${summary.savedCount} 条');
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _running = false;
        _progress = '';
      });
      _snack('采集失败：$e');
    }
  }

  void _snack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const LoadingView(text: '正在读取采集状态…');
    }
    if (_error != null) {
      return ErrorView(message: _error!, onRetry: _load);
    }

    return RefreshIndicator(
      onRefresh: _load,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 28),
        children: [
          _buildStatsCard(context),
          const SizedBox(height: 12),
          _buildRunCard(context),
          const SizedBox(height: 12),
          _buildSummaryCard(context),
          const SizedBox(height: 12),
          _buildSitesCard(context),
        ],
      ),
    );
  }

  Widget _buildStatsCard(BuildContext context) {
    return SectionCard(
      title: '本地数据',
      icon: Icons.storage_outlined,
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceAround,
        children: [
          StatTile(value: '$_total', label: '已入库'),
          StatTile(value: '$_openCount', label: '未截止'),
          StatTile(value: '$_newCount', label: '新公告'),
        ],
      ),
    );
  }

  Widget _buildRunCard(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return SectionCard(
      title: '开始采集',
      icon: Icons.cloud_download_outlined,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _sectionLabel(context, '站点'),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final entry in kSiteShortNames.entries)
                _chip(
                  entry.value,
                  selected: _site == entry.key,
                  onTap: _running ? null : () => setState(() => _site = entry.key),
                ),
            ],
          ),
          const SizedBox(height: 14),
          _sectionLabel(context, '时间窗口'),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _chip('最近 30 天', selected: _lookback == 30,
                  onTap: _running ? null : () => setState(() => _lookback = 30)),
              _chip('最近 90 天', selected: _lookback == 90,
                  onTap: _running ? null : () => setState(() => _lookback = 90)),
              _chip('最近 365 天', selected: _lookback == 365,
                  onTap: _running ? null : () => setState(() => _lookback = 365)),
              _chip('不限', selected: _lookback == 0,
                  onTap: _running ? null : () => setState(() => _lookback = 0)),
            ],
          ),
          const SizedBox(height: 14),
          _sectionLabel(context, '检索词数量'),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              _chip('20 个', selected: _maxKeywords == 20,
                  onTap: _running ? null : () => setState(() => _maxKeywords = 20)),
              _chip('50 个', selected: _maxKeywords == 50,
                  onTap: _running ? null : () => setState(() => _maxKeywords = 50)),
              _chip('全部', selected: _maxKeywords == null,
                  onTap: _running ? null : () => setState(() => _maxKeywords = null)),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            '检索词按「窄 → 宽」均匀取样，不是只取前 N 个。'
            '时间窗口默认 30 天；首次建库或补历史时选「不限」。',
            style: theme.textTheme.bodySmall?.copyWith(
              color: scheme.onSurfaceVariant,
              height: 1.5,
            ),
          ),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: _running ? null : _startCrawl,
            icon: _running
                ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.play_arrow_rounded, size: 20),
            label: Text(_running ? '采集中…' : '开始采集'),
            style: FilledButton.styleFrom(
              minimumSize: const Size.fromHeight(48),
            ),
          ),
          if (_running) ...[
            const SizedBox(height: 12),
            LinearProgressIndicator(minHeight: 3, borderRadius: BorderRadius.circular(2)),
            const SizedBox(height: 8),
            Text(
              _progress.isEmpty ? '正在采集…' : _progress,
              style: theme.textTheme.bodySmall?.copyWith(
                color: scheme.primary,
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildSummaryCard(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final summary = _summary;
    if (summary == null) {
      return SectionCard(
        title: '上次采集',
        icon: Icons.history,
        child: Text(
          '尚未执行采集。点上面的「开始采集」抓取数据。',
          style: theme.textTheme.bodySmall?.copyWith(
            color: scheme.onSurfaceVariant,
          ),
        ),
      );
    }

    final failed = summary.status.toUpperCase().contains('FAIL');
    return SectionCard(
      title: '上次采集',
      icon: Icons.history,
      trailing: BadgePill(
        crawlStatusLabel(summary.status),
        tone: failed ? BadgeTone.danger : BadgeTone.ok,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (summary.message.trim().isNotEmpty)
            Text(
              summary.message.trim(),
              style: theme.textTheme.bodySmall?.copyWith(
                color: scheme.onSurfaceVariant,
                height: 1.5,
              ),
            ),
          if (summary.finishedAt != null) ...[
            const SizedBox(height: 6),
            Text(
              '结束于 ${formatDateTime(summary.finishedAt)}'
              '${summary.durationMs > 0 ? ' · 耗时 ${(summary.durationMs / 1000).toStringAsFixed(1)} 秒' : ''}',
              style: theme.textTheme.bodySmall?.copyWith(
                color: scheme.onSurfaceVariant,
              ),
            ),
          ],
          const SizedBox(height: 14),
          Wrap(
            spacing: 18,
            runSpacing: 12,
            children: [
              _countItem(context, '候选', summary.candidateCount),
              _countItem(context, '命中', summary.matchedCount),
              _countItem(context, '新增', summary.savedCount, highlight: true),
              _countItem(context, '重复', summary.duplicateCount),
              _countItem(context, '失败', summary.failedCount,
                  danger: summary.failedCount > 0),
              _countItem(context, '请求', summary.requestCount),
              if (summary.verifiedCount > 0)
                _countItem(context, '已校验', summary.verifiedCount),
            ],
          ),
          if (summary.sites.isNotEmpty) ...[
            const SizedBox(height: 14),
            const Divider(),
            const SizedBox(height: 10),
            for (final site in summary.sites) _siteSummaryRow(context, site),
          ],
          if (summary.newItems.isNotEmpty) ...[
            const SizedBox(height: 14),
            const Divider(),
            const SizedBox(height: 10),
            Text(
              '本次新增（${summary.newItemCount}）',
              style: theme.textTheme.labelMedium?.copyWith(
                color: scheme.onSurfaceVariant,
                fontWeight: FontWeight.w600,
              ),
            ),
            const SizedBox(height: 6),
            for (final item in summary.newItems.take(8))
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    BadgePill(
                      NewTender.leftLabel(item.deadlineDate).isEmpty
                          ? '期限未知'
                          : NewTender.leftLabel(item.deadlineDate),
                      tone: DeadlineBadge.toneOf(item.deadlineDate),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        item.title,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.bodySmall?.copyWith(height: 1.4),
                      ),
                    ),
                  ],
                ),
              ),
          ],
        ],
      ),
    );
  }

  Widget _siteSummaryRow(BuildContext context, SiteSummary site) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final hasError = (site.errorMessage ?? '').trim().isNotEmpty;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  site.siteName.isEmpty ? siteShortName(site.siteCode) : site.siteName,
                  style: theme.textTheme.bodyMedium?.copyWith(
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
              Text(
                '新增 ${site.savedCount} · 命中 ${site.matchedCount} · 重复 ${site.duplicateCount}',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
          if (hasError)
            Padding(
              padding: const EdgeInsets.only(top: 3),
              child: Text(
                site.errorMessage!,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: scheme.error,
                  height: 1.4,
                ),
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildSitesCard(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return SectionCard(
      title: '各站点已入库',
      icon: Icons.dns_outlined,
      child: Column(
        children: [
          for (final entry in kSiteShortNames.entries)
            if (entry.key != 'all')
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 5),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        entry.value,
                        style: theme.textTheme.bodyMedium,
                      ),
                    ),
                    Text(
                      '${_siteTotals[entry.key] ?? 0} 条',
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: scheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
              ),
        ],
      ),
    );
  }

  Widget _countItem(BuildContext context, String label, int value,
      {bool highlight = false, bool danger = false}) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final color = danger
        ? scheme.error
        : (highlight ? scheme.primary : scheme.onSurface);
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          '$value',
          style: theme.textTheme.titleMedium?.copyWith(
            fontWeight: FontWeight.w700,
            color: color,
          ),
        ),
        Text(
          label,
          style: theme.textTheme.labelSmall?.copyWith(
            color: scheme.onSurfaceVariant,
          ),
        ),
      ],
    );
  }

  Widget _sectionLabel(BuildContext context, String text) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Text(
        text,
        style: Theme.of(context).textTheme.labelMedium?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
              fontWeight: FontWeight.w600,
            ),
      ),
    );
  }

  Widget _chip(String label, {required bool selected, VoidCallback? onTap}) {
    return ChoiceChip(
      label: Text(label),
      selected: selected,
      onSelected: onTap == null ? null : (_) => onTap(),
      showCheckmark: false,
      visualDensity: VisualDensity.compact,
      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
    );
  }
}
