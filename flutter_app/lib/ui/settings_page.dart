import 'package:material_ui/material_ui.dart';

// material_ui 也导出了 Flutter 的 DateUtils，这里用别名避免冲突。
import '../core/common/date_utils.dart' as cn;
import '../services/app_services.dart';
import 'widgets/states.dart';

/// 应用设置：定时采集、截止提醒、抓取策略与数据管理。
class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  bool _loading = true;
  bool _saving = false;
  String? _error;

  bool _scheduleEnabled = true;
  int _scheduleHour = 8;
  int _lookbackDays = 30;
  bool _fetchDetail = true;
  bool _verifyLink = true;
  bool _reminderEnabled = true;
  int _reminderDays = 3;
  int _retentionDays = 180;

  int _tenderCount = 0;
  int _purgeable = 0;
  bool _purgeArmed = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      await _readSettings();
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = null;
      });
      await _reloadStorage();
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = e.toString();
      });
    }
  }

  Future<void> _readSettings() async {
    final services = AppServices.instance;
    _scheduleEnabled = services.isScheduleEnabled();
    _scheduleHour = services.getScheduleHour();
    _lookbackDays = services.getLookbackDays();
    _fetchDetail = services.isFetchDetail();
    _verifyLink = services.isVerifyLink();
    _reminderEnabled = services.isReminderEnabled();
    _reminderDays = services.getReminderDays();
    _retentionDays = services.getRetentionDays();
  }

  Future<void> _reloadStorage() async {
    final repository = AppServices.instance.repository;
    final count = await repository.count();
    final cutoff = _purgeCutoff();
    final purgeable = await repository.countPurgeable(cutoff, keepTracked: true);
    if (!mounted) return;
    setState(() {
      _tenderCount = count;
      _purgeable = purgeable;
      _purgeArmed = false;
    });
  }

  DateTime _purgeCutoff() =>
      cn.DateUtils.nowCn().subtract(Duration(days: _retentionDays));

  Future<void> _save(Future<void> Function() action, String message) async {
    if (_saving) return;
    setState(() => _saving = true);
    try {
      await action();
      await AppServices.instance.reschedule();
      if (!mounted) return;
      _snack(message);
    } catch (e) {
      if (!mounted) return;
      _snack('保存失败：$e');
      await _readSettings();
      if (mounted) setState(() {});
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<void> _setScheduleEnabled(bool value) async {
    setState(() {
      _scheduleEnabled = value;
      _purgeArmed = false;
    });
    await _save(
      () => AppServices.instance.setScheduleEnabled(value),
      value ? '定时采集已开启' : '定时采集已关闭',
    );
  }

  Future<void> _setScheduleHour(int hour) async {
    setState(() => _scheduleHour = hour);
    await _save(
      () => AppServices.instance.setScheduleHour(hour),
      '每天 ${hour.toString().padLeft(2, '0')}:00 自动采集',
    );
  }

  Future<void> _setLookbackDays(int days) async {
    setState(() => _lookbackDays = days);
    await _save(
      () => AppServices.instance.setLookbackDays(days),
      days == 0 ? '采集时间窗口：不限' : '采集时间窗口：最近 $days 天',
    );
  }

  Future<void> _setFetchDetail(bool value) async {
    setState(() => _fetchDetail = value);
    await _save(
      () => AppServices.instance.setFetchDetail(value),
      value ? '已开启详情抓取' : '已关闭详情抓取（只保留列表信息）',
    );
  }

  Future<void> _setVerifyLink(bool value) async {
    setState(() => _verifyLink = value);
    await _save(
      () => AppServices.instance.setVerifyLink(value),
      value ? '已开启下架校验' : '已关闭下架校验',
    );
  }

  Future<void> _setReminderEnabled(bool value) async {
    setState(() => _reminderEnabled = value);
    await _save(
      () => AppServices.instance.setReminderEnabled(value),
      value ? '截止提醒已开启' : '截止提醒已关闭',
    );
  }

  Future<void> _setReminderDays(int days) async {
    setState(() => _reminderDays = days);
    await _save(
      () => AppServices.instance.setReminderDays(days),
      '提前 $days 天提醒',
    );
  }

  Future<void> _setRetentionDays(int days) async {
    setState(() {
      _retentionDays = days;
      _purgeArmed = false;
    });
    await _save(
      () => AppServices.instance.setRetentionDays(days),
      '数据保留 $days 天',
    );
    await _reloadStorage();
  }

  Future<void> _markAllRead() async {
    try {
      final marked = await AppServices.instance.repository.markAllRead();
      if (!mounted) return;
      _snack(marked > 0 ? '已标记 $marked 条为已读' : '没有未读公告');
    } catch (e) {
      if (!mounted) return;
      _snack('操作失败：$e');
    }
  }

  Future<void> _purge() async {
    if (_purgeable <= 0) return;
    if (!_purgeArmed) {
      setState(() => _purgeArmed = true);
      _snack('再点一次确认清理（收藏的公告会保留）');
      return;
    }
    setState(() => _purgeArmed = false);
    try {
      final deleted = await AppServices.instance.repository
          .purgeExpired(_purgeCutoff(), keepTracked: true);
      await _reloadStorage();
      if (!mounted) return;
      _snack('已清理 $deleted 条');
    } catch (e) {
      if (!mounted) return;
      _snack('清理失败：$e');
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
    return Scaffold(
      appBar: AppBar(title: const Text('设置')),
      body: _loading
          ? const LoadingView(text: '正在读取设置…')
          : (_error != null
              ? ErrorView(message: _error!, onRetry: _load)
              : _buildBody(context)),
    );
  }

  Widget _buildBody(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final services = AppServices.instance;
    final rules = services.keywordRules;
    final insecureHosts = services.settings.request.insecureTlsHosts;
    final purgeText = _purgeArmed
        ? '确认清理 $_purgeable 条？'
        : (_purgeable > 0 ? '清理已截止的老公告' : '暂无需要清理的数据');

    return ListView(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 28),
      children: [
        SectionCard(
          title: '采集与提醒',
          icon: Icons.schedule,
          padding: EdgeInsets.zero,
          headerPadding: const EdgeInsets.fromLTRB(16, 14, 16, 0),
          child: Column(
            children: [
              SwitchListTile(
                title: const Text('定时采集'),
                subtitle: Text(
                  _scheduleEnabled
                      ? '每天 ${_scheduleHour.toString().padLeft(2, '0')}:00 自动跑一次'
                      : '已关闭，可在「采集」页手动执行',
                ),
                value: _scheduleEnabled,
                onChanged: (value) => _setScheduleEnabled(value),
              ),
              if (_scheduleEnabled)
                ListTile(
                  leading: const Icon(Icons.access_time),
                  title: const Text('采集时间'),
                  trailing: _hourDropdown(),
                ),
              const Divider(indent: 16, endIndent: 16),
              SwitchListTile(
                title: const Text('截止提醒'),
                subtitle: Text(
                  _reminderEnabled
                      ? '在截止前 $_reminderDays 天发通知'
                      : '已关闭',
                ),
                value: _reminderEnabled,
                onChanged: (value) => _setReminderEnabled(value),
              ),
              if (_reminderEnabled)
                ListTile(
                  leading: const Icon(Icons.notifications_active_outlined),
                  title: const Text('提前天数'),
                  trailing: _intDropdown(
                    value: _reminderDays,
                    options: const [1, 2, 3, 5, 7, 15],
                    labelOf: (value) => '$value 天',
                    onChanged: _setReminderDays,
                  ),
                ),
              const Divider(indent: 16, endIndent: 16),
              SwitchListTile(
                title: const Text('抓取详情'),
                subtitle: const Text('进详情页补全正文、截止日期、预算和附件'),
                value: _fetchDetail,
                onChanged: (value) => _setFetchDetail(value),
              ),
              SwitchListTile(
                title: const Text('下架校验'),
                subtitle: const Text('定期检查公告是否已从原站删除'),
                value: _verifyLink,
                onChanged: (value) => _setVerifyLink(value),
              ),
              ListTile(
                leading: const Icon(Icons.date_range_outlined),
                title: const Text('采集时间窗口'),
                subtitle: const Text('只在窗口内发布/更新的公告才会进库'),
                trailing: _intDropdown(
                  value: _lookbackDays,
                  options: const [30, 90, 365, 0],
                  labelOf: (value) => value == 0 ? '不限' : '$value 天',
                  onChanged: _setLookbackDays,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        SectionCard(
          title: '数据管理',
          icon: Icons.storage_outlined,
          padding: EdgeInsets.zero,
          headerPadding: const EdgeInsets.fromLTRB(16, 14, 16, 0),
          child: Column(
            children: [
              ListTile(
                leading: const Icon(Icons.dataset_outlined),
                title: const Text('已入库公告'),
                trailing: Text(
                  '$_tenderCount 条',
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: scheme.onSurfaceVariant,
                  ),
                ),
              ),
              ListTile(
                leading: const Icon(Icons.auto_delete_outlined),
                title: const Text('数据保留天数'),
                subtitle: Text('截止超过 $_retentionDays 天的公告可以清理'),
                trailing: _intDropdown(
                  value: _retentionDays,
                  options: const [30, 90, 180, 365, 730],
                  labelOf: (value) => '$value 天',
                  onChanged: _setRetentionDays,
                ),
              ),
              ListTile(
                leading: Icon(
                  Icons.cleaning_services_outlined,
                  color: _purgeArmed ? scheme.error : null,
                ),
                title: Text(
                  purgeText,
                  style: TextStyle(color: _purgeArmed ? scheme.error : null),
                ),
                subtitle: Text(
                  _purgeable > 0
                      ? '收藏的公告不会被清理'
                      : '没有已截止超过 $_retentionDays 天的公告',
                ),
                trailing: TextButton(
                  onPressed: _purgeable > 0 ? _purge : null,
                  child: Text(_purgeArmed ? '确认' : '清理'),
                ),
              ),
              const Divider(indent: 16, endIndent: 16),
              ListTile(
                leading: const Icon(Icons.done_all),
                title: const Text('全部标记为已读'),
                subtitle: const Text('清除列表里的「新」标记'),
                onTap: _markAllRead,
              ),
            ],
          ),
        ),
        const SizedBox(height: 12),
        SectionCard(
          title: '关于',
          icon: Icons.info_outline,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _infoRow(context, '版本', '${services.appVersion}（build ${services.appVersionCode}）'),
              _infoRow(context, '词表', '标题 ${rules.titleKeywords.length} · '
                  '正文 ${rules.contentKeywords.length} · '
                  '排除 ${rules.excludeKeywords.length}'),
              _infoRow(context, '数据库', '应用私有目录 · SQLite（本地存储）'),
              if (insecureHosts.isNotEmpty) ...[
                const SizedBox(height: 10),
                Container(
                  padding: const EdgeInsets.all(12),
                  decoration: BoxDecoration(
                    color: scheme.surfaceContainerHighest.withValues(alpha: 0.45),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Text(
                    '放宽证书校验的域名：${insecureHosts.join('、')}\n'
                    '这些域名不校验 HTTPS 证书。中标信息本身不是机密，'
                    '但链路上被篡改的内容可能被当成真的，重要公告请以浏览器打开的原文为准。',
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: scheme.onSurfaceVariant,
                      height: 1.55,
                    ),
                  ),
                ),
              ],
              const SizedBox(height: 10),
              Text(
                '提示：后台采集受系统省电策略影响，可能在设定时间之后才执行。'
                '如果长时间没有新数据，可到系统设置里允许本应用后台运行。',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: scheme.onSurfaceVariant,
                  height: 1.55,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _infoRow(BuildContext context, String label, String value) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 64,
            child: Text(
              label,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
          Expanded(
            child: Text(
              value,
              style: theme.textTheme.bodyMedium?.copyWith(height: 1.45),
            ),
          ),
        ],
      ),
    );
  }

  Widget _hourDropdown() {
    return _intDropdown(
      value: _scheduleHour,
      options: List<int>.generate(24, (index) => index),
      labelOf: (value) => '${value.toString().padLeft(2, '0')}:00',
      onChanged: _setScheduleHour,
    );
  }

  Widget _intDropdown({
    required int value,
    required List<int> options,
    required String Function(int) labelOf,
    required Future<void> Function(int) onChanged,
  }) {
    // 值可能来自别的入口（例如月数不在选项里），补一个选项避免 Dropdown 报错。
    final values = [...options];
    if (!values.contains(value)) {
      values.add(value);
      values.sort();
    }
    return DropdownButtonHideUnderline(
      child: DropdownButton<int>(
        value: value,
        isDense: true,
        borderRadius: BorderRadius.circular(12),
        items: [
          for (final option in values)
            DropdownMenuItem(value: option, child: Text(labelOf(option))),
        ],
        onChanged: _saving
            ? null
            : (next) {
                if (next != null) onChanged(next);
              },
      ),
    );
  }
}
