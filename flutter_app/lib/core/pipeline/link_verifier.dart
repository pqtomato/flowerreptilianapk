import '../common/date_utils.dart';
import '../net/http_fetcher.dart';
import '../repository.dart';
import '../settings.dart';

/// 采集进度回调（只上报一句给界面展示的文案）。
typedef ProgressListener = void Function(String message);

/// 已下架公告校验。
///
/// 两条经验：
/// 1. **保守判定**：只有明确 404/410 或页面明确写出“信息不存在 / 已删除 / 已撤销”
///    才判为下架；网络异常、超时、被拦截一律认为公告仍然存在，宁可留着也不误删；
/// 2. **跳过窗口**：同一公告 skipHours 小时内不重复校验，单次最多校验 limit 条。
///
/// 另外 `linkStatus=FALLBACK` 的记录直接跳过 —— 这类是交易网索引里的县级转载公告，
/// 本来就没有落地页（详情地址必然 404），按“404 即下架”会误判。
class LinkVerifier {
  /// 页面里出现这些字样说明公告已被删除。
  static final RegExp _removedHint = RegExp(
    '信息不存在|公告不存在|该公告已(删除|撤销|撤回)|已下架|内容已删除|页面不存在',
  );

  final TenderRepository repository;
  final HttpFetcher httpFetcher;
  final CrawlerSettings settings;
  final void Function(String message)? onWarn;

  LinkVerifier(this.repository, this.httpFetcher, this.settings, {this.onWarn});

  /// 校验一批公告链接。
  ///
  /// [limit] 覆盖配置里的单次上限；为空用配置值。
  /// 返回本次实际校验的条数（FALLBACK 跳过的不计）。
  Future<int> verify({
    int? limit,
    ProgressListener? onProgress,
    ProgressListener? listener,
  }) async {
    final progress = onProgress ?? listener;
    final effectiveLimit =
        limit != null && limit > 0 ? limit : settings.verify.limit;
    final before = DateUtils.nowCn().subtract(Duration(
      hours: settings.verify.skipHours < 0 ? 0 : settings.verify.skipHours,
    ));

    final candidates =
        await repository.findForVerification(before, effectiveLimit < 1 ? 1 : effectiveLimit);
    if (candidates.isEmpty) return 0;

    var checked = 0;
    for (final item in candidates) {
      if (item.linkStatus == 'FALLBACK') {
        // 本来就没有落地页，探了必然 404；只刷新验证时间，避免下一轮再捞到它
        item.verifiedAt = DateUtils.nowCn();
        await repository.update(item);
        continue;
      }
      checked++;
      final url = item.accessUrl.trim().isNotEmpty ? item.accessUrl : item.url;
      if (url.trim().isEmpty) continue;

      final removed = await _checkRemoved(url);
      item.verifiedAt = DateUtils.nowCn();
      if (removed != null) {
        item.removed = removed;
        item.linkStatus = removed ? 'REMOVED' : 'OK';
      }
      await repository.update(item);
      if (progress != null && checked % 20 == 0) {
        progress('已校验 $checked 条链接');
      }
    }
    return checked;
  }

  /// 返回 true 已下架 / false 仍存在 / null 无法判断。
  Future<bool?> _checkRemoved(String url) async {
    try {
      final response = await httpFetcher.get(url);
      if (response.status == 404 || response.status == 410) {
        return true;
      }
      if (!response.isSuccess) {
        return null;
      }
      final text = response.text;
      final sample = text.length > 4000 ? text.substring(0, 4000) : text;
      return _removedHint.hasMatch(sample);
    } catch (e) {
      onWarn?.call('校验链接失败（按仍存在处理）：$url（$e）');
      return null;
    }
  }

  Future<int> pendingCount() {
    final before = DateUtils.nowCn().subtract(Duration(
      hours: settings.verify.skipHours < 0 ? 0 : settings.verify.skipHours,
    ));
    return repository.countForVerification(before);
  }
}
