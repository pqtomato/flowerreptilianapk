import 'package:material_ui/material_ui.dart';

import '../services/app_services.dart';
import 'crawl_page.dart';
import 'keyword_page.dart';
import 'logs_page.dart';
import 'settings_page.dart';
import 'tender_list_page.dart';

/// 应用主壳：底部四个 Tab（公告 / 关键词 / 采集 / 日志），右上角设置。
class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  static const List<String> _titles = ['招标公告', '关键词', '采集', '采集日志'];

  int _index = 0;

  final GlobalKey<TenderListPageState> _listKey = GlobalKey<TenderListPageState>();
  final GlobalKey<KeywordPageState> _keywordKey = GlobalKey<KeywordPageState>();
  final GlobalKey<CrawlPageState> _crawlKey = GlobalKey<CrawlPageState>();
  final GlobalKey<LogsPageState> _logsKey = GlobalKey<LogsPageState>();

  void _selectTab(int index) {
    if (index == _index) {
      _refreshCurrent();
      return;
    }
    setState(() => _index = index);
    // 切过去之后再刷，避免在构建期间同步触发 setState
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _refreshCurrent();
    });
  }

  void _refreshCurrent() {
    switch (_index) {
      case 0:
        _listKey.currentState?.refresh();
        break;
      case 1:
        _keywordKey.currentState?.refresh();
        break;
      case 2:
        _crawlKey.currentState?.refresh();
        break;
      case 3:
        _logsKey.currentState?.refresh();
        break;
    }
  }

  void _onCrawlFinished() {
    _listKey.currentState?.refresh();
    _logsKey.currentState?.refresh();
  }

  Future<void> _openSettings() async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(builder: (_) => const SettingsPage()),
    );
    if (!mounted) return;
    _refreshCurrent();
  }

  Future<void> _markAllRead() async {
    try {
      final marked = await AppServices.instance.repository.markAllRead();
      if (!mounted) return;
      _snack(marked > 0 ? '已标记 $marked 条为已读' : '没有未读公告');
      _listKey.currentState?.refresh();
    } catch (e) {
      if (!mounted) return;
      _snack('操作失败：$e');
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
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(_titles[_index]),
            if (_index == 0)
              Text(
                '安庆 · 花卉 / 园林绿化',
                style: theme.textTheme.labelSmall?.copyWith(
                  fontSize: 11,
                  color: theme.colorScheme.onSurfaceVariant,
                  fontWeight: FontWeight.w400,
                ),
              ),
          ],
        ),
        actions: [
          if (_index == 0)
            IconButton(
              tooltip: '全部标记为已读',
              onPressed: _markAllRead,
              icon: const Icon(Icons.done_all),
            ),
          IconButton(
            tooltip: '刷新',
            onPressed: _refreshCurrent,
            icon: const Icon(Icons.refresh),
          ),
          IconButton(
            tooltip: '设置',
            onPressed: _openSettings,
            icon: const Icon(Icons.settings_outlined),
          ),
          const SizedBox(width: 4),
        ],
      ),
      body: IndexedStack(
        index: _index,
        children: [
          TenderListPage(
            key: _listKey,
            onGoCrawl: () => _selectTab(2),
          ),
          KeywordPage(
            key: _keywordKey,
            onCrawlRequested: () => _selectTab(2),
          ),
          CrawlPage(
            key: _crawlKey,
            onCrawlFinished: _onCrawlFinished,
          ),
          LogsPage(key: _logsKey),
        ],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: _selectTab,
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.assignment_outlined),
            selectedIcon: Icon(Icons.assignment),
            label: '公告',
          ),
          NavigationDestination(
            icon: Icon(Icons.key_outlined),
            selectedIcon: Icon(Icons.key),
            label: '关键词',
          ),
          NavigationDestination(
            icon: Icon(Icons.cloud_download_outlined),
            selectedIcon: Icon(Icons.cloud_download),
            label: '采集',
          ),
          NavigationDestination(
            icon: Icon(Icons.receipt_long_outlined),
            selectedIcon: Icon(Icons.receipt_long),
            label: '日志',
          ),
        ],
      ),
    );
  }
}
