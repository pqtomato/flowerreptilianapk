import 'dart:async';

import 'package:material_ui/material_ui.dart';

import '../core/common/region_names.dart';
import '../core/models.dart';
import '../services/app_services.dart';
import 'tender_detail_page.dart';
import 'widgets/format.dart';
import 'widgets/states.dart';
import 'widgets/tender_card.dart';

enum _ListStatus {
  open('未截止'),
  newOnly('新公告'),
  tracked('收藏'),
  all('全部');

  const _ListStatus(this.label);

  final String label;
}

/// 公告列表：搜索、状态筛选、站点/区县筛选、无限滚动。
class TenderListPage extends StatefulWidget {
  const TenderListPage({super.key, this.onGoCrawl});

  /// 空库时「去采集」的回调（由首页切到采集 Tab）。
  final VoidCallback? onGoCrawl;

  @override
  State<TenderListPage> createState() => TenderListPageState();
}

class TenderListPageState extends State<TenderListPage> {
  static const int _pageSize = 20;

  final TextEditingController _searchController = TextEditingController();
  final ScrollController _scrollController = ScrollController();

  Timer? _searchDebounce;

  _ListStatus _status = _ListStatus.open;
  bool _onlyBiddable = true;
  bool _sortByDeadline = true;
  String? _siteCode;
  String? _region;
  String _keyword = '';

  List<TenderRecord> _items = [];
  int _total = 0;
  int _hiddenNonBiddable = 0;

  bool _loading = true;
  bool _loadingMore = false;
  String? _error;
  int _requestSeq = 0;

  @override
  void initState() {
    super.initState();
    _scrollController.addListener(_onScroll);
    _reload();
  }

  @override
  void dispose() {
    _searchDebounce?.cancel();
    _searchController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  /// 外部刷新入口（切换 Tab、采集完成、设置变更后调用）。
  Future<void> refresh() => _reload();

  TenderQuery _buildQuery() {
    return TenderQuery()
      ..keyword = _keyword.isEmpty ? null : _keyword
      ..siteCode = _siteCode
      ..region = _region
      ..onlyOpen = _status == _ListStatus.open
      ..onlyNew = _status == _ListStatus.newOnly
      ..trackedOnly = _status == _ListStatus.tracked
      ..onlyBiddable = _onlyBiddable
      ..sortBy =
          _sortByDeadline ? TenderQuery.sortDeadline : TenderQuery.sortPublish;
  }

  void _onScroll() {
    if (!_scrollController.hasClients) return;
    if (_scrollController.position.extentAfter < 360) {
      _loadMore();
    }
  }

  Future<void> _reload() async {
    final seq = ++_requestSeq;
    if (mounted) {
      setState(() {
        _loading = true;
        _loadingMore = false;
        _error = null;
      });
    }
    try {
      final repo = AppServices.instance.repository;
      final query = _buildQuery();
      final total = await repo.countQuery(query);
      final allBiddableTotal = _onlyBiddable
          ? await repo.countQuery(query.copy()..onlyBiddable = false)
          : total;
      final rows = await repo.query(query, _pageSize, 0);
      if (!mounted || seq != _requestSeq) return;
      setState(() {
        _items = rows;
        _total = total;
        final hidden = allBiddableTotal - total;
        _hiddenNonBiddable = hidden > 0 ? hidden : 0;
        _loading = false;
        _error = null;
      });
    } catch (e) {
      if (!mounted || seq != _requestSeq) return;
      setState(() {
        _loading = false;
        _error = e.toString();
      });
    }
  }

  Future<void> _loadMore() async {
    if (_loading || _loadingMore || _items.length >= _total) return;
    final seq = _requestSeq;
    setState(() => _loadingMore = true);
    try {
      final rows = await AppServices.instance.repository
          .query(_buildQuery(), _pageSize, _items.length);
      // 加载过程中筛选条件变了：直接丢弃这批旧结果
      if (!mounted || seq != _requestSeq) return;
      final seen = _items.map((item) => item.id).toSet();
      setState(() {
        for (final row in rows) {
          if (seen.add(row.id)) _items.add(row);
        }
        _loadingMore = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _loadingMore = false);
      _snack('加载更多失败：$e');
    }
  }

  void _setStatus(_ListStatus status) {
    if (_status == status) return;
    setState(() => _status = status);
    _reload();
  }

  void _onSearchChanged(String value) {
    setState(() {}); // 更新清除按钮
    _searchDebounce?.cancel();
    _searchDebounce = Timer(const Duration(milliseconds: 400), () {
      if (!mounted) return;
      final next = value.trim();
      if (next == _keyword) return;
      setState(() => _keyword = next);
      _reload();
    });
  }

  void _clearSearch() {
    _searchDebounce?.cancel();
    _searchController.clear();
    setState(() => _keyword = '');
    _reload();
  }

  Future<void> _toggleTrack(TenderRecord record) async {
    final next = !record.tracked;
    try {
      await AppServices.instance.repository.setTracked(record.id, next);
      if (!mounted) return;
      setState(() {
        record.tracked = next;
        if (_status == _ListStatus.tracked && !next) {
          _items.removeWhere((item) => item.id == record.id);
          _total = _total > 0 ? _total - 1 : 0;
        }
      });
      _snack(next ? '已收藏' : '已取消收藏');
    } catch (e) {
      if (!mounted) return;
      _snack('操作失败：$e');
    }
  }

  void _onRecordChanged(TenderRecord record) {
    if (!mounted) return;
    setState(() {
      final index = _items.indexWhere((item) => item.id == record.id);
      if (index >= 0) _items[index] = record;
    });
  }

  Future<void> _openDetail(TenderRecord record) async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => TenderDetailPage(
          record: record,
          onChanged: _onRecordChanged,
        ),
      ),
    );
  }

  Future<void> _openFilterSheet() async {
    String? site = _siteCode;
    String? region = _region;
    final applied = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) {
        return StatefulBuilder(
          builder: (context, setSheetState) {
            return SafeArea(
              child: SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      children: [
                        Text('筛选', style: Theme.of(context).textTheme.titleMedium),
                        const Spacer(),
                        TextButton(
                          onPressed: () => setSheetState(() {
                            site = null;
                            region = null;
                          }),
                          child: const Text('重置'),
                        ),
                      ],
                    ),
                    const GroupLabel('站点'),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        _chip(
                          '全部站点',
                          selected: site == null,
                          onTap: () => setSheetState(() => site = null),
                        ),
                        for (final entry in kSiteShortNames.entries)
                          if (entry.key != 'all')
                            _chip(
                              entry.value,
                              selected: site == entry.key,
                              onTap: () => setSheetState(() => site = entry.key),
                            ),
                      ],
                    ),
                    const GroupLabel('区县'),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        _chip(
                          '全部区县',
                          selected: region == null,
                          onTap: () => setSheetState(() => region = null),
                        ),
                        for (final name in _allRegions())
                          _chip(
                            name,
                            selected: region == name,
                            onTap: () => setSheetState(() => region = name),
                          ),
                      ],
                    ),
                    const SizedBox(height: 20),
                    Row(
                      children: [
                        Expanded(
                          child: OutlinedButton(
                            onPressed: () => Navigator.of(sheetContext).pop(false),
                            child: const Text('取消'),
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: FilledButton(
                            onPressed: () => Navigator.of(sheetContext).pop(true),
                            child: const Text('应用'),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
    if (applied != true || !mounted) return;
    setState(() {
      _siteCode = site;
      _region = region;
    });
    _reload();
  }

  List<String> _allRegions() {
    final regions = RegionNames.knownRegions().toSet().toList();
    regions.remove('市辖区');
    return regions;
  }

  Widget _chip(String label, {required bool selected, required VoidCallback onTap}) {
    return ChoiceChip(
      label: Text(label),
      selected: selected,
      onSelected: (_) => onTap(),
      showCheckmark: false,
      visualDensity: VisualDensity.compact,
      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
    );
  }

  void _snack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  String get _filterSummary {
    final parts = <String>[];
    if (_siteCode != null) parts.add(siteShortName(_siteCode!));
    if (_region != null) parts.add(_region!);
    return parts.join(' · ');
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        _buildHeader(context),
        if (_onlyBiddable && _hiddenNonBiddable > 0) _buildHiddenBanner(context),
        Expanded(child: _buildBody(context)),
      ],
    );
  }

  Widget _buildHeader(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
      decoration: BoxDecoration(
        color: theme.scaffoldBackgroundColor,
        border: Border(
          bottom: BorderSide(
            color: scheme.outlineVariant.withValues(alpha: 0.5),
          ),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            controller: _searchController,
            textInputAction: TextInputAction.search,
            onChanged: _onSearchChanged,
            onSubmitted: (value) {
              _searchDebounce?.cancel();
              final next = value.trim();
              if (next == _keyword) return;
              setState(() => _keyword = next);
              _reload();
            },
            decoration: InputDecoration(
              hintText: '搜索标题 / 关键词 / 区县',
              prefixIcon: const Icon(Icons.search, size: 20),
              suffixIcon: _searchController.text.isEmpty
                  ? null
                  : IconButton(
                      tooltip: '清除',
                      icon: const Icon(Icons.close, size: 18),
                      onPressed: _clearSearch,
                    ),
              isDense: true,
            ),
          ),
          const SizedBox(height: 8),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              children: [
                for (final status in _ListStatus.values) ...[
                  _chip(
                    status.label,
                    selected: _status == status,
                    onTap: () => _setStatus(status),
                  ),
                  const SizedBox(width: 8),
                ],
                Container(
                  width: 1,
                  height: 20,
                  margin: const EdgeInsets.symmetric(horizontal: 4),
                  color: scheme.outlineVariant,
                ),
                FilterChip(
                  label: const Text('只看可投'),
                  selected: _onlyBiddable,
                  showCheckmark: false,
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  onSelected: (value) {
                    setState(() => _onlyBiddable = value);
                    _reload();
                  },
                ),
                const SizedBox(width: 8),
                FilterChip(
                  label: const Text('按截止排序'),
                  selected: _sortByDeadline,
                  showCheckmark: false,
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  onSelected: (value) {
                    setState(() => _sortByDeadline = value);
                    _reload();
                  },
                ),
                const SizedBox(width: 8),
                ActionChip(
                  avatar: const Icon(Icons.tune, size: 16),
                  label: Text(
                    _siteCode == null && _region == null ? '筛选' : '筛选 · 已设',
                  ),
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  onPressed: _openFilterSheet,
                ),
              ],
            ),
          ),
          const SizedBox(height: 6),
          Row(
            children: [
              Text(
                '共 $_total 条',
                style: theme.textTheme.labelMedium?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
              ),
              if (_filterSummary.isNotEmpty) ...[
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    '· $_filterSummary',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.labelSmall?.copyWith(
                      color: scheme.primary,
                    ),
                  ),
                ),
              ] else
                const Spacer(),
              if (_filterSummary.isNotEmpty)
                TextButton(
                  onPressed: () {
                    setState(() {
                      _siteCode = null;
                      _region = null;
                    });
                    _reload();
                  },
                  style: TextButton.styleFrom(
                    visualDensity: VisualDensity.compact,
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                  ),
                  child: const Text('清除筛选'),
                ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildHiddenBanner(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      color: scheme.secondaryContainer.withValues(alpha: 0.45),
      padding: const EdgeInsets.fromLTRB(14, 4, 8, 4),
      child: Row(
        children: [
          Icon(Icons.visibility_off_outlined, size: 16, color: scheme.onSecondaryContainer),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              '已按「只看可投」隐藏 $_hiddenNonBiddable 条结果/合同公告',
              style: TextStyle(fontSize: 12, color: scheme.onSecondaryContainer),
            ),
          ),
          TextButton(
            onPressed: () {
              setState(() => _onlyBiddable = false);
              _reload();
            },
            style: TextButton.styleFrom(visualDensity: VisualDensity.compact),
            child: const Text('显示'),
          ),
        ],
      ),
    );
  }

  Widget _buildBody(BuildContext context) {
    if (_loading && _items.isEmpty) {
      return const LoadingView(text: '正在读取公告…');
    }
    if (_error != null && _items.isEmpty) {
      return RefreshIndicator(
        onRefresh: _reload,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          children: [
            const SizedBox(height: 100),
            ErrorView(message: _error!, onRetry: _reload),
          ],
        ),
      );
    }
    if (_items.isEmpty) {
      return RefreshIndicator(
        onRefresh: _reload,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          children: [
            const SizedBox(height: 48),
            EmptyView(
              icon: Icons.eco_outlined,
              title: '没有符合条件的公告',
              message: '试试切换筛选条件，或到「采集」页抓取最新数据。',
              actionLabel: widget.onGoCrawl == null ? null : '去采集',
              onAction: widget.onGoCrawl,
            ),
          ],
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: _reload,
      child: ListView.separated(
        controller: _scrollController,
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(12, 10, 12, 24),
        itemCount: _items.length + 1,
        separatorBuilder: (_, _) => const SizedBox(height: 10),
        itemBuilder: (context, index) {
          if (index == _items.length) return _buildFooter(context);
          final record = _items[index];
          return TenderCard(
            record: record,
            onTap: () => _openDetail(record),
            onToggleTrack: () => _toggleTrack(record),
          );
        },
      ),
    );
  }

  Widget _buildFooter(BuildContext context) {
    final theme = Theme.of(context);
    if (_loadingMore) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 18),
        child: Center(
          child: SizedBox(
            width: 20,
            height: 20,
            child: CircularProgressIndicator(strokeWidth: 2.2),
          ),
        ),
      );
    }
    if (_items.length >= _total && _total > 0) {
      return Padding(
        padding: const EdgeInsets.symmetric(vertical: 18),
        child: Center(
          child: Text(
            '已经到底了 · 共 $_total 条',
            style: theme.textTheme.labelSmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ),
      );
    }
    return const SizedBox(height: 48);
  }
}
