import 'package:material_ui/material_ui.dart';

import '../core/models.dart';
import '../services/app_services.dart';
import 'widgets/badges.dart';
import 'widgets/format.dart';
import 'widgets/states.dart';

/// 采集日志：最近 100 条，失败原因和分站消息都能展开看。
class LogsPage extends StatefulWidget {
  const LogsPage({super.key});

  @override
  State<LogsPage> createState() => LogsPageState();
}

class LogsPageState extends State<LogsPage> {
  static const int _limit = 100;

  List<CrawlLogRecord> _logs = const [];
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// 外部刷新入口。
  Future<void> refresh() => _load();

  Future<void> _load() async {
    if (mounted) setState(() => _error = null);
    try {
      final logs = await AppServices.instance.repository.recentLogs(_limit);
      if (!mounted) return;
      setState(() {
        _logs = logs;
        _loading = false;
        _error = null;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = e.toString();
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const LoadingView(text: '正在读取采集日志…');
    }
    if (_error != null) {
      return ErrorView(message: _error!, onRetry: _load);
    }
    return RefreshIndicator(
      onRefresh: _load,
      child: _logs.isEmpty
          ? ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              children: const [
                SizedBox(height: 48),
                EmptyView(
                  icon: Icons.receipt_long_outlined,
                  title: '暂无采集日志',
                  message: '每次手动或定时采集都会在这里留下一条记录。',
                ),
              ],
            )
          : ListView.separated(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: const EdgeInsets.fromLTRB(12, 10, 12, 28),
              itemCount: _logs.length + 1,
              separatorBuilder: (_, _) => const SizedBox(height: 8),
              itemBuilder: (context, index) {
                if (index == 0) return _buildHeader(context);
                return _LogCard(log: _logs[index - 1]);
              },
            ),
    );
  }

  Widget _buildHeader(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 0, 4, 4),
      child: Text(
        '最近 ${_logs.length} 条（最多保留 $_limit 条）',
        style: theme.textTheme.labelSmall?.copyWith(
          color: theme.colorScheme.onSurfaceVariant,
        ),
      ),
    );
  }
}

class _LogCard extends StatelessWidget {
  const _LogCard({required this.log});

  final CrawlLogRecord log;

  bool get _failed => log.status.toUpperCase().contains('FAIL');
  String get _site => log.siteCode.isEmpty ? '全部站点' : siteShortName(log.siteCode);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final hasDetail = log.detail.trim().isNotEmpty;
    final hasError = log.errorMessage.trim().isNotEmpty;

    final header = Row(
      children: [
        Icon(
          _failed ? Icons.error_outline : Icons.check_circle_outline,
          size: 18,
          color: _failed ? scheme.error : scheme.primary,
        ),
        const SizedBox(width: 8),
        Text(
          _site,
          style: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w600),
        ),
        const SizedBox(width: 8),
        BadgePill(
          crawlStatusLabel(log.status),
          tone: _failed ? BadgeTone.danger : BadgeTone.ok,
        ),
        const Spacer(),
        Text(
          log.durationMs > 0 ? '${(log.durationMs / 1000).toStringAsFixed(1)}s' : '—',
          style: theme.textTheme.labelSmall?.copyWith(
            color: scheme.onSurfaceVariant,
          ),
        ),
      ],
    );

    final body = Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 4),
        Text(
          '${formatDateTime(log.startTime)} · 候选 ${log.candidateCount} · '
          '命中 ${log.matchedCount} · 新增 ${log.savedCount} · '
          '重复 ${log.duplicateCount} · 失败 ${log.failedCount}'
          '${log.requestCount > 0 ? ' · 请求 ${log.requestCount}' : ''}',
          style: theme.textTheme.bodySmall?.copyWith(
            color: scheme.onSurfaceVariant,
            height: 1.45,
          ),
        ),
        if (hasError)
          Padding(
            padding: const EdgeInsets.only(top: 5),
            child: Text(
              log.errorMessage.trim(),
              style: theme.textTheme.bodySmall?.copyWith(
                color: scheme.error,
                height: 1.45,
              ),
            ),
          ),
      ],
    );

    if (!hasDetail && !hasError) {
      return Card(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [header, body],
          ),
        ),
      );
    }

    return Card(
      child: ExpansionTile(
        shape: const Border(),
        collapsedShape: const Border(),
        tilePadding: const EdgeInsets.symmetric(horizontal: 14),
        childrenPadding: const EdgeInsets.fromLTRB(14, 0, 14, 14),
        title: header,
        subtitle: body,
        expandedCrossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (hasDetail)
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: scheme.surfaceContainerHighest.withValues(alpha: 0.45),
                borderRadius: BorderRadius.circular(10),
              ),
              child: SelectableText(
                log.detail.trim(),
                style: theme.textTheme.bodySmall?.copyWith(
                  height: 1.55,
                  fontFamily: 'monospace',
                ),
              ),
            ),
        ],
      ),
    );
  }
}
