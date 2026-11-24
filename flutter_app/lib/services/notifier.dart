import 'dart:io';

import 'package:flutter_local_notifications/flutter_local_notifications.dart';

import '../core/models.dart';
import '../core/pipeline/deadline_reminder.dart';

/// 本地通知：采集结果 + 截止提醒。
///
/// 与 Java 版一致，两条通知用**独立渠道**：
/// - `crawl_task`：采集进度（前台服务常驻通知，低优先级，可静音），由 WorkManager 创建；
/// - `deadline_reminder`：采集结果与截止提醒（默认优先级，必须留下）。
///
/// 之所以把「发现新公告」也放进 `deadline_reminder`：采集进度属于过程噪声，
/// 用户很可能想静音；但「发现了新机会」一旦被一起静掉，这个通知就白发了。
class AppNotifier {
  AppNotifier._();

  static final AppNotifier instance = AppNotifier._();

  static const String channelReminderId = 'deadline_reminder';
  static const String channelReminderName = '截止提醒';
  static const String channelReminderDesc = '即将截止的公告，以及采集到的新公告';

  /// 采集前台服务通知的渠道（由 WorkManager 按这个名字创建）。
  static const String channelCrawlId = 'crawl_task';
  static const String channelCrawlName = '采集任务';

  static const int _resultId = 1002;
  static const int _reminderId = 1003;

  final FlutterLocalNotificationsPlugin _plugin =
      FlutterLocalNotificationsPlugin();

  bool _initialized = false;

  /// 初始化插件并建渠道。
  ///
  /// [requestPermission] 只在主 isolate（有 Activity 的时候）传 true；
  /// 后台 isolate 里请求权限会被系统直接忽略，还可能抛异常。
  Future<void> init({bool requestPermission = false}) async {
    if (_initialized) return;
    _initialized = true;

    const settings = InitializationSettings(
      android: AndroidInitializationSettings('@mipmap/ic_launcher'),
      iOS: DarwinInitializationSettings(
        requestAlertPermission: false,
        requestSoundPermission: false,
        requestBadgePermission: false,
      ),
    );
    await _plugin.initialize(settings: settings);

    if (Platform.isAndroid) {
      final android = _plugin.resolvePlatformSpecificImplementation<
          AndroidFlutterLocalNotificationsPlugin>();
      await android?.createNotificationChannel(const AndroidNotificationChannel(
        channelReminderId,
        channelReminderName,
        description: channelReminderDesc,
        importance: Importance.defaultImportance,
      ));
      if (requestPermission) {
        await android?.requestNotificationsPermission();
      }
    } else if (Platform.isIOS) {
      if (requestPermission) {
        await _plugin
            .resolvePlatformSpecificImplementation<
                IOSFlutterLocalNotificationsPlugin>()
            ?.requestPermissions(alert: true, badge: true, sound: true);
      }
    }
  }

  /// 在界面上请求通知权限（Android 13+ / iOS）。
  ///
  /// 单独一个方法：初始化时不能同步等权限弹窗，否则首帧永远出不来。
  Future<void> requestPermissions() async {
    try {
      if (Platform.isAndroid) {
        await _plugin
            .resolvePlatformSpecificImplementation<
                AndroidFlutterLocalNotificationsPlugin>()
            ?.requestNotificationsPermission();
      } else if (Platform.isIOS) {
        await _plugin
            .resolvePlatformSpecificImplementation<
                IOSFlutterLocalNotificationsPlugin>()
            ?.requestPermissions(alert: true, badge: true, sound: true);
      }
    } catch (_) {
      // 用户拒绝 / 系统不支持都不影响其它功能
    }
  }

  /// 采集结束后的结果通知。
  ///
  /// 与 Java 版文案一致：优先列「还没截止」的新公告（标题 + 剩余天数），
  /// 已截止的不占折叠后的第一行；没有可投的也直说，别让用户白点进来。
  Future<void> notifyCrawlResult(CrawlSummary summary) async {
    final items = summary.newItems
        .where((item) => item.deadlineDate == null || !item.expired)
        .toList();
    final total = summary.newItemCount > items.length
        ? summary.newItemCount
        : items.length;
    final listed = NewTender.mostUrgent(items, DeadlineReminder.maxLines);

    late final String title;
    late final String text;
    if (listed.isNotEmpty) {
      title = '发现 $total 条可投的新公告';
      text = _buildLines(listed, total);
    } else if (total > 0) {
      title = '采集完成';
      text = '新增 $total 条新公告，但都已过报名截止时间';
    } else if (summary.savedCount > 0) {
      title = '采集完成';
      text = '新增 ${summary.savedCount} 条，均为结果/合同类公告，没有可投的';
    } else {
      title = '采集完成';
      text = summary.duplicateCount > 0 ? '没有新公告（已是最新）' : '本次没有匹配到新公告';
    }
    await _post(
      id: _resultId,
      title: title,
      text: text,
      payload: listed.isNotEmpty ? 'new' : null,
    );
  }

  /// 采集失败的通知（后台任务用；前台按钮采集由页面自己提示）。
  Future<void> notifyCrawlError(String message) => _post(
        id: _resultId,
        title: '采集失败',
        text: message.trim().isEmpty ? '未知错误' : message,
      );

  /// 截止提醒。调用方应先用 [DeadlineReminder.pick] 过滤；空列表不发。
  ///
  /// 用固定 ID，所以每天只会在通知栏留最新的一条，不会越堆越多。
  Future<void> notifyDue(List<TenderRecord> due) async {
    if (due.isEmpty) return;
    await _post(
      id: _reminderId,
      title: DeadlineReminder.buildTitle(due),
      text: DeadlineReminder.buildMessage(due),
      payload: 'due',
    );
  }

  Future<void> _post({
    required int id,
    required String title,
    required String text,
    String? payload,
  }) async {
    final firstLine = text.split('\n').first;
    final details = NotificationDetails(
      android: AndroidNotificationDetails(
        channelReminderId,
        channelReminderName,
        channelDescription: channelReminderDesc,
        importance: Importance.defaultImportance,
        priority: Priority.defaultPriority,
        styleInformation: BigTextStyleInformation(text),
      ),
      iOS: const DarwinNotificationDetails(),
    );
    await _plugin.show(
      id: id,
      title: title,
      body: firstLine,
      notificationDetails: details,
      payload: payload,
    );
  }

  /// 把已排好序的条目拼成通知正文；超出部分用「…等 N 条」收尾（N 用总数）。
  static String _buildLines(List<NewTender> items, int total) {
    final buffer = StringBuffer();
    final shown = items.length < DeadlineReminder.maxLines
        ? items.length
        : DeadlineReminder.maxLines;
    for (var i = 0; i < shown; i++) {
      if (i > 0) buffer.write('\n');
      buffer.write(items[i].describe());
    }
    if (total > shown) {
      buffer.write('\n…等 $total 条');
    }
    return buffer.toString();
  }
}
