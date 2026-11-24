import 'package:material_ui/material_ui.dart';

import '../core/filter/keyword_rules.dart';
import '../core/keywords.dart';
import '../core/models.dart';
import '../services/app_services.dart';
import 'widgets/states.dart';

/// 关键词维护：三套词表 + 用户增删 + 重扫已入库公告。
class KeywordPage extends StatefulWidget {
  const KeywordPage({super.key, this.onCrawlRequested});

  /// 「用当前词表采集」——由首页切到采集 Tab。
  final VoidCallback? onCrawlRequested;

  @override
  State<KeywordPage> createState() => KeywordPageState();
}

class KeywordPageState extends State<KeywordPage> {
  final TextEditingController _inputController = TextEditingController();
  final TextEditingController _filterController = TextEditingController();

  KeywordScope _addScope = KeywordScope.title;
  List<String> _titles = const [];
  List<String> _contents = const [];
  List<String> _excludes = const [];
  Set<String> _customTitle = {};
  Set<String> _customContent = {};
  Set<String> _customExclude = {};
  List<CrawlerKeyword> _disabled = const [];
  Map<String, int> _hits = {};

  String _filter = '';
  bool _loading = true;
  bool _busy = false;
  String _busyText = '';
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _inputController.dispose();
    _filterController.dispose();
    super.dispose();
  }

  /// 外部刷新入口。
  Future<void> refresh() => _load();

  List<String> _effectiveFor(KeywordScope scope) {
    switch (scope) {
      case KeywordScope.title:
        return _titles;
      case KeywordScope.content:
        return _contents;
      case KeywordScope.exclude:
        return _excludes;
    }
  }

  Future<void> _load() async {
    if (mounted) {
      setState(() {
        _loading = _titles.isEmpty && _contents.isEmpty && _excludes.isEmpty;
        _error = null;
      });
    }
    try {
      final rules = AppServices.instance.keywordRules;
      final overrides = await rules.overrideSource.loadOverrides();
      final matched = await AppServices.instance.repository.allMatchedKeywords();

      final hits = <String, int>{};
      for (final word in rules.titleKeywords) {
        hits[word] = 0;
      }
      for (final word in rules.contentKeywords) {
        hits.putIfAbsent(word, () => 0);
      }
      for (final row in matched) {
        for (final part in row.split(',')) {
          final word = part.trim();
          if (word.isNotEmpty && hits.containsKey(word)) {
            hits[word] = hits[word]! + 1;
          }
        }
      }

      final customTitle = <String>{};
      final customContent = <String>{};
      final customExclude = <String>{};
      final disabled = <CrawlerKeyword>[];
      for (final keyword in overrides) {
        if (keyword.action == KeywordAction.disable) {
          disabled.add(keyword);
          continue;
        }
        switch (keyword.scope) {
          case KeywordScope.title:
            customTitle.add(keyword.keyword);
            break;
          case KeywordScope.content:
            customContent.add(keyword.keyword);
            break;
          case KeywordScope.exclude:
            customExclude.add(keyword.keyword);
            break;
        }
      }

      if (!mounted) return;
      setState(() {
        _titles = List.of(rules.titleKeywords);
        _contents = List.of(rules.contentKeywords);
        _excludes = List.of(rules.excludeKeywords);
        _customTitle = customTitle;
        _customContent = customContent;
        _customExclude = customExclude;
        _disabled = disabled;
        _hits = hits;
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

  Future<void> _addKeyword() async {
    if (_busy) return;
    final display = _inputController.text.trim();
    if (display.isEmpty) {
      _snack('请先输入关键词');
      return;
    }
    if (display.length > 32) {
      _snack('关键词过长（最多 32 个字）');
      return;
    }
    final normalized = KeywordRules.normalize(display);
    if (normalized.isEmpty) {
      _snack('关键词不能只包含空白字符');
      return;
    }

    final scope = _addScope;
    final rules = AppServices.instance.keywordRules;
    try {
      final overrides = await rules.overrideSource.loadOverrides();
      CrawlerKeyword? disableRecord;
      var alreadyAdded = false;
      for (final keyword in overrides) {
        if (keyword.keyword != normalized || keyword.scope != scope) continue;
        if (keyword.action == KeywordAction.add) alreadyAdded = true;
        if (keyword.action == KeywordAction.disable) disableRecord = keyword;
      }
      if (alreadyAdded || _effectiveFor(scope).contains(normalized)) {
        _snack('该关键词已在词表中：$display');
        return;
      }

      setState(() {
        _busy = true;
        _busyText = '正在添加「$display」…';
      });
      if (disableRecord != null) {
        // 之前停用过的内置词：撤销停用即等于重新启用
        await rules.overrideSource.delete(disableRecord.id);
      } else {
        await rules.overrideSource.insert(CrawlerKeyword(
          keyword: normalized,
          display: display,
          scope: scope,
          action: KeywordAction.add,
        ));
      }
      await rules.reload();
      _inputController.clear();
      if (mounted) setState(() => _busyText = '已添加，正在重扫已入库公告…');
      final rescan = await _rescan();
      await _load();
      final hits = rescan.perKeyword[normalized] ?? 0;
      _snack(hits > 0
          ? '已添加「$display」，历史公告命中 $hits 条'
          : '已添加「$display」，下次采集生效');
    } catch (e) {
      _snack('添加失败：$e');
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          _busyText = '';
        });
      }
    }
  }

  Future<void> _removeKeyword(String word, KeywordScope scope) async {
    if (_busy) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('移除关键词'),
        content: Text('确定要移除「$word」吗？\n\n内置词会停用（不删文件），自定义词会直接删除。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('移除'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() {
      _busy = true;
      _busyText = '正在移除「$word」…';
    });
    try {
      final rules = AppServices.instance.keywordRules;
      final overrides = await rules.overrideSource.loadOverrides();
      CrawlerKeyword? existing;
      for (final keyword in overrides) {
        if (keyword.keyword == word && keyword.scope == scope) {
          existing = keyword;
          break;
        }
      }
      if (existing != null && existing.action == KeywordAction.add) {
        await rules.overrideSource.delete(existing.id);
        _snack('已删除自定义词：$word');
      } else {
        if (existing == null) {
          await rules.overrideSource.insert(CrawlerKeyword(
            keyword: word,
            display: word,
            scope: scope,
            action: KeywordAction.disable,
          ));
        }
        _snack('已停用内置词：$word');
      }
      await rules.reload();
      await _load();
    } catch (e) {
      _snack('移除失败：$e');
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          _busyText = '';
        });
      }
    }
  }

  Future<void> _enableKeyword(CrawlerKeyword keyword) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _busyText = '正在启用「${keyword.display}」…';
    });
    try {
      final rules = AppServices.instance.keywordRules;
      await rules.overrideSource.delete(keyword.id);
      await rules.reload();
      await _load();
      _snack('已重新启用：${keyword.display}');
    } catch (e) {
      _snack('启用失败：$e');
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          _busyText = '';
        });
      }
    }
  }

  Future<void> _reloadTables() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _busyText = '正在重新加载内置词表…';
    });
    try {
      await AppServices.instance.keywordRules.reload();
      await _load();
      _snack('词表已重新加载');
    } catch (e) {
      _snack('重载失败：$e');
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          _busyText = '';
        });
      }
    }
  }

  Future<void> _rescanOnly() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _busyText = '正在重扫已入库公告…';
    });
    try {
      final result = await _rescan();
      await _load();
      _snack('重扫完成：处理 ${result.processed} 条，更新 ${result.changed} 条，'
          '新命中 ${result.newlyMatched} 条');
    } catch (e) {
      _snack('重扫失败：$e');
    } finally {
      if (mounted) {
        setState(() {
          _busy = false;
          _busyText = '';
        });
      }
    }
  }

  /// 用当前词表重扫已入库公告，回填命中关键词（与 Java `KeywordService.rescan` 同口径）。
  Future<_RescanResult> _rescan() async {
    final repository = AppServices.instance.repository;
    final rules = AppServices.instance.keywordRules;
    final total = await repository.count();
    const batchSize = 200;

    var processed = 0;
    var changed = 0;
    var newlyMatched = 0;
    final perKeyword = <String, int>{};

    for (var offset = 0; offset < total; offset += batchSize) {
      final rows = await repository.query(TenderQuery(), batchSize, offset);
      if (rows.isEmpty) break;
      for (final item in rows) {
        processed++;
        final titleMatch = rules.match(item.title, null);
        final contentMatch = rules.match(null, item.content);
        final hits = <String>{
          ...titleMatch.keywords,
          ...contentMatch.keywords,
        };
        if (hits.isEmpty) continue;

        final joinedRaw = hits.join(',');
        final joined =
            joinedRaw.length > 500 ? joinedRaw.substring(0, 500) : joinedRaw;
        if (joined == item.matchedKeywords) continue;

        if (item.matchedKeywords.trim().isEmpty) newlyMatched++;
        item.matchedKeywords = joined;
        item.matchedScope = titleMatch.isMatched && contentMatch.isMatched
            ? 'TITLE,CONTENT'
            : titleMatch.isMatched
                ? 'TITLE'
                : (contentMatch.isMatched ? 'CONTENT' : 'TITLE');
        await repository.update(item);
        changed++;
        for (final hit in hits) {
          perKeyword[hit] = (perKeyword[hit] ?? 0) + 1;
        }
      }
      if (mounted && _busy && total > 0) {
        setState(() => _busyText = '重扫中… $processed / $total');
      }
    }

    return _RescanResult(
      processed: processed,
      changed: changed,
      newlyMatched: newlyMatched,
      perKeyword: perKeyword,
    );
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
      return const LoadingView(text: '正在读取词表…');
    }
    if (_error != null) {
      return ErrorView(message: _error!, onRetry: _load);
    }

    return Stack(
      children: [
        RefreshIndicator(
          onRefresh: _load,
          child: ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            padding: const EdgeInsets.fromLTRB(12, 10, 12, 28),
            children: [
              _buildSummaryCard(context),
              const SizedBox(height: 12),
              _buildAddCard(context),
              const SizedBox(height: 12),
              _buildFilterField(context),
              const SizedBox(height: 8),
              _group(
                context,
                label: '标题词',
                icon: Icons.title,
                tip: '作为站内检索词（搜标题），并判定标题命中。',
                words: _titles,
                scope: KeywordScope.title,
                custom: _customTitle,
              ),
              const SizedBox(height: 12),
              _group(
                context,
                label: '正文词',
                icon: Icons.notes,
                tip: '以「标题+正文」检索，能捞到标题没写但正文提到的公告。',
                words: _contents,
                scope: KeywordScope.content,
                custom: _customContent,
              ),
              const SizedBox(height: 12),
              _group(
                context,
                label: '排除词',
                icon: Icons.block,
                tip: '只判定标题，标题命中即丢弃（车辆维修、厕所管护、资产拍卖这类噪声）。',
                words: _excludes,
                scope: KeywordScope.exclude,
                custom: _customExclude,
              ),
              if (_disabled.isNotEmpty) ...[
                const SizedBox(height: 12),
                _buildDisabledCard(context),
              ],
              const SizedBox(height: 16),
              _buildActions(context),
            ],
          ),
        ),
        if (_busy) _buildBusyBar(context),
      ],
    );
  }

  Widget _buildSummaryCard(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final customCount =
        _customTitle.length + _customContent.length + _customExclude.length;
    return SectionCard(
      title: '词表总览',
      icon: Icons.key_outlined,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceAround,
            children: [
              StatTile(value: '${_titles.length}', label: '标题词'),
              StatTile(value: '${_contents.length}', label: '正文词'),
              StatTile(value: '${_excludes.length}', label: '排除词'),
              StatTile(value: '$customCount', label: '自定义'),
            ],
          ),
          const SizedBox(height: 12),
          Text(
            '排除词只判定标题：正文里顺带提到不算噪声。'
            '命中 = 标题命中 + 正文命中（取并集）。',
            style: theme.textTheme.bodySmall?.copyWith(
              color: scheme.onSurfaceVariant,
              height: 1.5,
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildAddCard(BuildContext context) {
    final theme = Theme.of(context);
    return SectionCard(
      title: '添加关键词',
      icon: Icons.add_circle_outline,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextField(
            controller: _inputController,
            textInputAction: TextInputAction.done,
            onSubmitted: (_) => _addKeyword(),
            decoration: const InputDecoration(
              hintText: '例如：绿篱、水生植物、花境',
              isDense: true,
            ),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              Expanded(
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12),
                  decoration: BoxDecoration(
                    color: theme.colorScheme.surfaceContainerHighest
                        .withValues(alpha: 0.5),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: DropdownButtonHideUnderline(
                    child: DropdownButton<KeywordScope>(
                      value: _addScope,
                      isExpanded: true,
                      borderRadius: BorderRadius.circular(12),
                      items: const [
                        DropdownMenuItem(
                          value: KeywordScope.title,
                          child: Text('标题词 — 检索并判定标题'),
                        ),
                        DropdownMenuItem(
                          value: KeywordScope.content,
                          child: Text('正文词 — 检索标题+正文'),
                        ),
                        DropdownMenuItem(
                          value: KeywordScope.exclude,
                          child: Text('排除词 — 标题命中即丢弃'),
                        ),
                      ],
                      onChanged: _busy
                          ? null
                          : (value) {
                              if (value == null) return;
                              setState(() => _addScope = value);
                            },
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 10),
              FilledButton.icon(
                onPressed: _busy ? null : _addKeyword,
                icon: const Icon(Icons.add, size: 18),
                label: const Text('添加'),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildFilterField(BuildContext context) {
    return TextField(
      controller: _filterController,
      onChanged: (value) => setState(() => _filter = value.trim()),
      decoration: InputDecoration(
        hintText: '在词表里查找',
        prefixIcon: const Icon(Icons.search, size: 20),
        isDense: true,
        suffixIcon: _filter.isEmpty
            ? null
            : IconButton(
                tooltip: '清除',
                icon: const Icon(Icons.close, size: 18),
                onPressed: () {
                  _filterController.clear();
                  setState(() => _filter = '');
                },
              ),
      ),
    );
  }

  Widget _group(
    BuildContext context, {
    required String label,
    required IconData icon,
    required String tip,
    required List<String> words,
    required KeywordScope scope,
    required Set<String> custom,
  }) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final filtered = _filter.isEmpty
        ? words
        : words.where((word) => word.contains(_filter)).toList();
    return SectionCard(
      title: '$label（${words.length}）',
      icon: icon,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            tip,
            style: theme.textTheme.bodySmall?.copyWith(
              color: scheme.onSurfaceVariant,
              height: 1.5,
            ),
          ),
          const SizedBox(height: 10),
          if (filtered.isEmpty)
            Text(
              _filter.isEmpty ? '（空）' : '没有匹配「$_filter」的词',
              style: theme.textTheme.bodySmall?.copyWith(
                color: scheme.onSurfaceVariant,
              ),
            )
          else
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                for (final word in filtered)
                  _keywordChip(context, word, scope, custom.contains(word)),
              ],
            ),
        ],
      ),
    );
  }

  Widget _keywordChip(
    BuildContext context,
    String word,
    KeywordScope scope,
    bool custom,
  ) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final hits = scope == KeywordScope.exclude ? null : (_hits[word] ?? 0);
    return InputChip(
      label: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(word, style: const TextStyle(fontSize: 12.5)),
          if (hits != null) ...[
            const SizedBox(width: 5),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 1),
              decoration: BoxDecoration(
                color: (hits == 0 ? scheme.surfaceContainerHighest : scheme.primaryContainer)
                    .withValues(alpha: 0.8),
                borderRadius: BorderRadius.circular(5),
              ),
              child: Text(
                '$hits',
                style: TextStyle(
                  fontSize: 10,
                  height: 1.3,
                  color: hits == 0 ? scheme.onSurfaceVariant : scheme.onPrimaryContainer,
                ),
              ),
            ),
          ],
        ],
      ),
      onDeleted: _busy ? null : () => _removeKeyword(word, scope),
      deleteIcon: const Icon(Icons.close, size: 15),
      deleteButtonTooltipMessage: '移除',
      backgroundColor:
          custom ? scheme.primaryContainer.withValues(alpha: 0.35) : null,
      visualDensity: VisualDensity.compact,
      materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
    );
  }

  Widget _buildDisabledCard(BuildContext context) {
    final theme = Theme.of(context);
    return SectionCard(
      title: '已停用的内置词（${_disabled.length}）',
      icon: Icons.visibility_off_outlined,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            '这些词还在文件里，但当前不参与匹配。点一下可以重新启用。',
            style: theme.textTheme.bodySmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
              height: 1.5,
            ),
          ),
          const SizedBox(height: 10),
          Wrap(
            spacing: 6,
            runSpacing: 6,
            children: [
              for (final keyword in _disabled)
                ActionChip(
                  avatar: const Icon(Icons.undo, size: 15),
                  label: Text(keyword.display),
                  onPressed: _busy ? null : () => _enableKeyword(keyword),
                  visualDensity: VisualDensity.compact,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildActions(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Row(
          children: [
            Expanded(
              child: OutlinedButton.icon(
                onPressed: _busy ? null : _reloadTables,
                icon: const Icon(Icons.refresh, size: 18),
                label: const Text('重新加载词表'),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: OutlinedButton.icon(
                onPressed: _busy ? null : _rescanOnly,
                icon: const Icon(Icons.manage_search, size: 18),
                label: const Text('重扫已入库'),
              ),
            ),
          ],
        ),
        if (widget.onCrawlRequested != null) ...[
          const SizedBox(height: 10),
          FilledButton.icon(
            onPressed: _busy ? null : widget.onCrawlRequested,
            icon: const Icon(Icons.cloud_download_outlined, size: 18),
            label: const Text('用当前词表去采集'),
          ),
        ],
      ],
    );
  }

  Widget _buildBusyBar(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Positioned(
      left: 12,
      right: 12,
      bottom: 12,
      child: Material(
        elevation: 4,
        borderRadius: BorderRadius.circular(12),
        color: scheme.inverseSurface,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 10, 14, 12),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                _busyText,
                style: TextStyle(fontSize: 12.5, color: scheme.onInverseSurface),
              ),
              const SizedBox(height: 8),
              const LinearProgressIndicator(minHeight: 3),
            ],
          ),
        ),
      ),
    );
  }
}

class _RescanResult {
  const _RescanResult({
    required this.processed,
    required this.changed,
    required this.newlyMatched,
    required this.perKeyword,
  });

  final int processed;
  final int changed;
  final int newlyMatched;
  final Map<String, int> perKeyword;
}
