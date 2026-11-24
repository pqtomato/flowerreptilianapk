import 'dart:async';

import 'package:flutter/widgets.dart';
import 'package:workmanager/workmanager.dart';

import '../core/common/date_utils.dart';
import 'app_services.dart';
import 'notifier.dart';

/// 后台任务调度（WorkManager）。
///
/// 用 WorkManager 而不是 AlarmManager / 自建 Timer：
/// - 系统在设备重启后自动恢复，不用写开机广播；
/// - 能声明「需要网络」「电量不低」等约束；
/// - 配合 `ForegroundServiceConfig` 可以跑几分钟的长任务。
///
/// 代价是**时间不精确**：Doze / 省电模式会推迟任务，国产 ROM 若不允许自启动
/// 还会直接不跑。所以应用内始终提供「立即采集」。
class CrawlScheduler {
  CrawlScheduler._();

  // 传给 Dart 侧处理器的任务名
  static const String dailyTask = 'dailyCrawl';
  static const String manualTask = 'manualCrawl';
  static const String reminderTask = 'deadlineReminder';

  // WorkManager 的唯一任务名
  static const String _uniqueDaily = 'flower-tender-daily';
  static const String _uniqueManual = 'flower-tender-manual';
  static const String _uniqueReminder = 'flower-tender-reminder';

  /// 采集时间之后多久检查截止提醒（先采集，再提醒，看到的是当天最新数据）。
  static const Duration reminderOffset = Duration(minutes: 30);

  static bool _initialized = false;

  /// 注册后台回调。必须在 `runApp` 之前调用一次（后台 isolate 不经过 main）。
  static Future<void> initialize() async {
    if (_initialized) return;
    await Workmanager().initialize(backgroundCallbackDispatcher);
    _initialized = true;
  }

  /// 按当前设置重排「每日采集 + 截止提醒」。
  ///
  /// 先取消再注册：`ExistingPeriodicWorkPolicy.update` 会保留原来的运行时刻，
  /// 用户把 8 点改成 9 点后不生效。
  static Future<void> scheduleAll() async {
    await initialize();
    final store = AppServices.instance.store;

    if (store.scheduleEnabled) {
      await Workmanager().cancelByUniqueName(_uniqueDaily);
      await Workmanager().registerPeriodicTask(
        _uniqueDaily,
        dailyTask,
        frequency: const Duration(days: 1),
        initialDelay: delayToNextRun(store.scheduleHour),
        constraints: Constraints(
          networkType: NetworkType.connected,
          requiresBatteryNotLow: true,
        ),
        existingWorkPolicy: ExistingPeriodicWorkPolicy.keep,
        foregroundServiceConfig: _foregroundConfig('正在采集招标公告…'),
      );
    } else {
      await Workmanager().cancelByUniqueName(_uniqueDaily);
    }

    if (store.reminderEnabled) {
      await Workmanager().cancelByUniqueName(_uniqueReminder);
      await Workmanager().registerPeriodicTask(
        _uniqueReminder,
        reminderTask,
        frequency: const Duration(days: 1),
        initialDelay:
            delayToNextRun(store.scheduleHour) + reminderOffset,
        existingWorkPolicy: ExistingPeriodicWorkPolicy.keep,
      );
    } else {
      await Workmanager().cancelByUniqueName(_uniqueReminder);
    }
  }

  /// 启动时确保后台任务已在系统里登记（不打断已排队的工作）。
  ///
  /// 用 `update` 而不是 `keep`：
  /// - `keep` 什么都不改。安卓原生版用的是同名任务（`flower-tender-daily` 等）
  ///   但 Worker 类是 Java 的 `CrawlWorker`，从安卓版升级上来时 WorkManager 会
  ///   「保留」那条旧记录，到点反射加载不存在的类，采集永远不会跑（实测）。
  /// - `update` 会按新配置重写记录（包括 Worker 类），同时保留原有的运行时刻，
  ///   不会像「先取消再登记」那样把「今早 8 点因没网在等待」的任务抹掉。
  static Future<void> ensureScheduled() async {
    await initialize();
    final store = AppServices.instance.store;
    if (store.scheduleEnabled) {
      await Workmanager().registerPeriodicTask(
        _uniqueDaily,
        dailyTask,
        frequency: const Duration(days: 1),
        initialDelay: delayToNextRun(store.scheduleHour),
        constraints: Constraints(
          networkType: NetworkType.connected,
          requiresBatteryNotLow: true,
        ),
        existingWorkPolicy: ExistingPeriodicWorkPolicy.update,
        foregroundServiceConfig: _foregroundConfig('正在采集招标公告…'),
      );
    }
    if (store.reminderEnabled) {
      await Workmanager().registerPeriodicTask(
        _uniqueReminder,
        reminderTask,
        frequency: const Duration(days: 1),
        initialDelay: delayToNextRun(store.scheduleHour) + reminderOffset,
        existingWorkPolicy: ExistingPeriodicWorkPolicy.update,
      );
    }
  }

  /// 把一次采集交给 WorkManager（App 被切到后台也能跑完）。
  ///
  /// 页面上的「立即采集」优先用 [AppServices.runCrawl] 直接跑，能实时显示进度；
  /// 这个入口留给需要「退到后台也继续」的场景。
  static Future<void> runNow({
    String siteCode = 'all',
    int? maxKeywords,
    int? lookbackDays,
    String triggerType = 'MANUAL',
  }) async {
    await initialize();
    await Workmanager().registerOneOffTask(
      _uniqueManual,
      manualTask,
      inputData: {
        'site': siteCode,
        'maxKeywords': maxKeywords ?? 0,
        'lookbackDays': lookbackDays ?? -1,
        'triggerType': triggerType,
      },
      constraints: Constraints(networkType: NetworkType.connected),
      existingWorkPolicy: ExistingWorkPolicy.replace,
      foregroundServiceConfig: _foregroundConfig('正在采集招标公告…'),
    );
  }

  /// 计算距离下一次整点运行的时长（保持「每天 8 点」这种直觉）。
  ///
  /// WorkManager 的 initialDelay 至少 1 分钟。
  static Duration delayToNextRun(int hour) {
    final now = DateUtils.nowCn();
    var target = DateTime(now.year, now.month, now.day, hour.clamp(0, 23));
    if (!target.isAfter(now)) {
      target = target.add(const Duration(days: 1));
    }
    var minutes = target.difference(now).inMinutes;
    if (minutes < 1) minutes = 1;
    return Duration(minutes: minutes);
  }

  static ForegroundServiceConfig _foregroundConfig(String text) =>
      ForegroundServiceConfig(
        notificationTitle: '花卉招标监控',
        notificationText: text,
        notificationChannelId: AppNotifier.channelCrawlId,
        notificationChannelName: AppNotifier.channelCrawlName,
        notificationId: 1001,
        foregroundServiceType: ForegroundServiceType.dataSync,
      );
}

/// WorkManager 的后台入口（独立 isolate）。
///
/// 这里不能依赖 UI isolate 里的任何内存状态：重新初始化一套 AppServices 即可，
/// SQLite / SharedPreferences / 资源词表都能在后台 isolate 正常读取。
@pragma('vm:entry-point')
void backgroundCallbackDispatcher() {
  Workmanager().executeTask((task, inputData) async {
    WidgetsFlutterBinding.ensureInitialized();
    try {
      final services = AppServices.instance;
      await services.init();
      await AppNotifier.instance.init();

      switch (task) {
        case CrawlScheduler.dailyTask:
        case CrawlScheduler.manualTask:
          final site = (inputData?['site'] as String?) ?? 'all';
          final maxKeywords = (inputData?['maxKeywords'] as int?) ?? 0;
          final lookbackDays = (inputData?['lookbackDays'] as int?) ?? -1;
          final trigger = (inputData?['triggerType'] as String?) ??
              (task == CrawlScheduler.dailyTask ? 'SCHEDULED' : 'MANUAL');
          final summary = await services.runCrawl(
            siteCode: site,
            maxKeywords: maxKeywords > 0 ? maxKeywords : null,
            lookbackDays: lookbackDays >= 0 ? lookbackDays : null,
            triggerType: trigger,
          );
          return summary.status != 'FAILED';

        case CrawlScheduler.reminderTask:
          await services.checkDeadlines();
          return true;
      }
      debugPrint('CrawlScheduler: 未知后台任务 $task');
      return true;
    } catch (error) {
      debugPrint('CrawlScheduler: 后台任务失败 $error');
      // 通知本身也可能因为权限失败，这里再兜一层
      try {
        await AppNotifier.instance.init();
        await AppNotifier.instance.notifyCrawlError('$error');
      } catch (_) {}
      return false;
    }
  });
}
