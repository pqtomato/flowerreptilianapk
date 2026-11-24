import 'package:material_ui/material_ui.dart';

import 'services/app_services.dart';
import 'services/crawl_scheduler.dart';
import 'services/notifier.dart';
import 'ui/home_page.dart';
import 'ui/theme.dart';

/// 应用根组件。
///
/// 启动流程做成一个「门」而不是在 main 里 await：
/// 初始化（建库、读词表、注册后台任务）可能要几百毫秒，
/// 先给出启动页，失败时还能在界面上重试，而不是白屏退出。
class FlowerTenderApp extends StatelessWidget {
  const FlowerTenderApp({super.key, this.services});

  /// 测试可注入；默认用应用单例。
  final AppServices? services;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '花卉招标监控',
      debugShowCheckedModeBanner: false,
      theme: buildFlowerTenderTheme(brightness: Brightness.light),
      darkTheme: buildFlowerTenderTheme(brightness: Brightness.dark),
      themeMode: ThemeMode.system,
      locale: const Locale('zh', 'CN'),
      // material_ui 自带的委托（含 Cupertino / Widgets）
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      supportedLocales: const [Locale('zh', 'CN'), Locale('en', 'US')],
      home: _StartupGate(services: services ?? AppServices.instance),
    );
  }
}

class _StartupGate extends StatefulWidget {
  const _StartupGate({required this.services});

  final AppServices services;

  @override
  State<_StartupGate> createState() => _StartupGateState();
}

class _StartupGateState extends State<_StartupGate> {
  bool _ready = false;
  Object? _error;

  @override
  void initState() {
    super.initState();
    _start();
  }

  Future<void> _start() async {
    setState(() {
      _ready = false;
      _error = null;
    });
    try {
      final services = widget.services;
      // 采集内核 / 数据库是硬依赖，失败就停在启动页让用户重试；
      // 通知与后台调度属于增强能力，失败只记日志，不影响打开 App。
      await services.init();
      try {
        await CrawlScheduler.initialize();
        await AppNotifier.instance.init();
        await CrawlScheduler.ensureScheduled();
      } catch (error) {
        debugPrint('启动：后台任务/通知初始化失败（不影响手动使用）：$error');
      }
      if (!mounted) return;
      setState(() => _ready = true);
      // 首帧出来之后再要通知权限，避免启动时卡在系统弹窗
      WidgetsBinding.instance.addPostFrameCallback((_) {
        AppNotifier.instance.requestPermissions();
      });
    } catch (error) {
      if (!mounted) return;
      setState(() => _error = error);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_ready) return const HomePage();
    final theme = Theme.of(context);
    return Scaffold(
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.park_rounded,
                  size: 64, color: theme.colorScheme.primary),
              const SizedBox(height: 16),
              Text('花卉招标监控',
                  style: theme.textTheme.headlineSmall?.copyWith(
                    fontWeight: FontWeight.w600,
                  )),
              const SizedBox(height: 24),
              if (_error == null) ...[
                const CircularProgressIndicator(),
                const SizedBox(height: 16),
                Text('正在初始化…', style: theme.textTheme.bodyMedium),
              ] else ...[
                Text('初始化失败', style: theme.textTheme.titleMedium),
                const SizedBox(height: 8),
                SelectableText(
                  '$_error',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.error,
                  ),
                ),
                const SizedBox(height: 24),
                FilledButton.icon(
                  onPressed: _start,
                  icon: const Icon(Icons.refresh_rounded),
                  label: const Text('重试'),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
