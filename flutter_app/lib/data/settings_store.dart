import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../core/models.dart';
import '../core/settings.dart';

/// 用户设置与「上次采集结果」快照（键名与安卓版 SharedPreferences 一致）。
///
/// 用法：`final store = SettingsStore(); await store.init();`
/// 之后 getter 全部同步（值在 init 时读进内存，setter 写完立即更新内存），
/// 设置页点一下就能看到勾选状态，不用等落盘。
class SettingsStore {
  SettingsStore();

  /// 测试用：直接注入已载入的 SharedPreferences。
  SettingsStore.withPrefs(SharedPreferences prefs) {
    _applyPrefs(prefs);
  }

  SharedPreferences? _prefs;
  bool _initialized = false;

  int _lookbackDays = 30;
  bool _scheduleEnabled = true;
  int _scheduleHour = 8;
  bool _fetchDetail = true;
  bool _verifyLink = true;
  bool _reminderEnabled = true;
  int _reminderDays = 3;
  int _retentionDays = 180;
  int _maxDetailPerRun = 300;
  String _insecureHosts = '';
  CrawlSummary? _lastSummary;

  /// 安卓版用的是具名文件；Flutter 的 shared_preferences 固定用插件默认文件，
  /// 仅作展示/文档用途保留这个名字。
  static const String prefsName = 'flower_tender';

  static const List<String> defaultInsecureHosts = ['www.wyxmzx.com'];

  /// 载入 SharedPreferences 并缓存全部设置。可重复调用。
  Future<void> init() async {
    final prefs = await SharedPreferences.getInstance();
    _applyPrefs(prefs);
  }

  /// [init] 的别名，语义更直白。
  static Future<SettingsStore> load() async {
    final store = SettingsStore();
    await store.init();
    return store;
  }

  void _applyPrefs(SharedPreferences prefs) {
    _prefs = prefs;
    _lookbackDays = prefs.getInt('lookbackDays') ?? 30;
    _scheduleEnabled = prefs.getBool('scheduleEnabled') ?? true;
    _scheduleHour = (prefs.getInt('scheduleHour') ?? 8).clamp(0, 23);
    _fetchDetail = prefs.getBool('fetchDetail') ?? true;
    _verifyLink = prefs.getBool('verifyLink') ?? true;
    _reminderEnabled = prefs.getBool('reminderEnabled') ?? true;
    _reminderDays = (prefs.getInt('reminderDays') ?? 3).clamp(1, 30);
    _retentionDays = (prefs.getInt('retentionDays') ?? 180).clamp(1, 3650);
    _maxDetailPerRun = prefs.getInt('maxDetailPerRun') ?? 300;
    _insecureHosts = prefs.getString('insecureHosts') ?? '';
    _lastSummary = _readLastSummary(prefs);
    _initialized = true;
  }

  Future<SharedPreferences> _requirePrefs() async {
    if (!_initialized) await init();
    return _prefs!;
  }

  // ---------------------------------------------------------------- 基本设置

  int get lookbackDays => _lookbackDays;

  Future<void> setLookbackDays(int v) async {
    final prefs = await _requirePrefs();
    await prefs.setInt('lookbackDays', v);
    _lookbackDays = v;
  }

  bool get fetchDetail => _fetchDetail;

  Future<void> setFetchDetail(bool v) async {
    final prefs = await _requirePrefs();
    await prefs.setBool('fetchDetail', v);
    _fetchDetail = v;
  }

  bool get verifyLink => _verifyLink;

  Future<void> setVerifyLink(bool v) async {
    final prefs = await _requirePrefs();
    await prefs.setBool('verifyLink', v);
    _verifyLink = v;
  }

  int get maxDetailPerRun => _maxDetailPerRun;

  Future<void> setMaxDetailPerRun(int v) async {
    final value = v < 1 ? 1 : v;
    final prefs = await _requirePrefs();
    await prefs.setInt('maxDetailPerRun', value);
    _maxDetailPerRun = value;
  }

  // ---------------------------------------------------------------- 计划 / 提醒

  bool get scheduleEnabled => _scheduleEnabled;

  Future<void> setScheduleEnabled(bool v) async {
    final prefs = await _requirePrefs();
    await prefs.setBool('scheduleEnabled', v);
    _scheduleEnabled = v;
  }

  /// 每天几点跑（0-23）。
  int get scheduleHour => _scheduleHour;

  Future<void> setScheduleHour(int v) async {
    final value = v.clamp(0, 23);
    final prefs = await _requirePrefs();
    await prefs.setInt('scheduleHour', value);
    _scheduleHour = value;
  }

  bool get reminderEnabled => _reminderEnabled;

  Future<void> setReminderEnabled(bool v) async {
    final prefs = await _requirePrefs();
    await prefs.setBool('reminderEnabled', v);
    _reminderEnabled = v;
  }

  /// 提前几天开始提醒（1 / 3 / 7，输入框允许 1-30）。
  int get reminderDays => _reminderDays;

  Future<void> setReminderDays(int v) async {
    final value = v.clamp(1, 30);
    final prefs = await _requirePrefs();
    await prefs.setInt('reminderDays', value);
    _reminderDays = value;
  }

  /// 已截止公告保留多少天后可以清理（默认 180 天，只在用户点「清理」时生效）。
  int get retentionDays => _retentionDays;

  Future<void> setRetentionDays(int v) async {
    final value = v.clamp(1, 3650);
    final prefs = await _requirePrefs();
    await prefs.setInt('retentionDays', value);
    _retentionDays = value;
  }

  // ---------------------------------------------------------------- 证书放宽

  /// 用户额外添加的域名（逗号分隔，不含内置的 wyxmzx）。
  String get insecureHosts => _insecureHosts;

  Future<void> setInsecureHosts(String v) async {
    final value = v.trim();
    final prefs = await _requirePrefs();
    await prefs.setString('insecureHosts', value);
    _insecureHosts = value;
  }

  /// 实际生效的放宽名单：内置 + 用户添加，去重保序。
  List<String> get effectiveInsecureHosts {
    final hosts = <String>[...defaultInsecureHosts];
    for (final host in _insecureHosts.split(',')) {
      final trimmed = host.trim();
      if (trimmed.isNotEmpty && !hosts.contains(trimmed)) {
        hosts.add(trimmed);
      }
    }
    return hosts;
  }

  /// 把设置应用到内核配置对象（对应安卓 `CrawlerHolder.applyPrefs()`）。
  void applyTo(CrawlerSettings settings) {
    settings.sites.aqggzy.lookbackDays = _lookbackDays;
    settings.sites.wyxmzx.lookbackDays = _lookbackDays;
    settings.sites.zh0556.lookbackDays = _lookbackDays;
    settings.detail.enabled = _fetchDetail;
    settings.verify.enabled = _verifyLink;
    settings.request.maxDetailPerRun = _maxDetailPerRun;
    settings.request.insecureTlsHosts = effectiveInsecureHosts;
  }

  // ---------------------------------------------------------------- 上次采集快照

  /// 读回上次采集快照；从没采集过或快照损坏时返回 null。
  ///
  /// 汇总只活在进程内存里，应用被系统回收后「采集」页会退回「尚未执行采集」，
  /// 而「日志」页明明有历史 —— 所以必须单独落一份 JSON 快照。
  CrawlSummary? get lastSummary => _lastSummary;

  Future<void> saveLastSummary(CrawlSummary summary) async {
    final prefs = await _requirePrefs();
    final json = jsonEncode(summary.toJson());
    await prefs.setString('lastSummaryJson', json);
    _lastSummary = summary;
  }

  Future<void> clearLastSummary() async {
    final prefs = await _requirePrefs();
    await prefs.remove('lastSummaryJson');
    _lastSummary = null;
  }

  static CrawlSummary? _readLastSummary(SharedPreferences prefs) {
    final raw = prefs.getString('lastSummaryJson');
    if (raw == null || raw.isEmpty) return null;
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map<String, dynamic>) return null;
      return CrawlSummary.fromJson(decoded);
    } catch (_) {
      return null;
    }
  }
}
