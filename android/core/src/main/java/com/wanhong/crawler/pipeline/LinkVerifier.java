package com.wanhong.crawler.pipeline;

import com.wanhong.crawler.config.CrawlerSettings;
import com.wanhong.crawler.http.FetchResponse;
import com.wanhong.crawler.http.HttpFetcher;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 已下架公告校验。
 *
 * <p>沿用两条经验：
 * <ol>
 *   <li><b>保守判定</b>：只有明确 404/410 或页面明确写出"信息不存在 / 已删除 / 已撤销"
 *       才判为下架；网络异常、超时、被拦截一律认为公告仍然存在，宁可留着也不误删；</li>
 *   <li><b>跳过窗口</b>：同一公告 skipHours 小时内不重复校验，单次最多校验 limit 条。</li>
 * </ol>
 *
 * <p>另外：{@code linkStatus=FALLBACK} 的记录直接跳过 —— 这类是交易网索引里的县级转载公告，
 * 本来就没有落地页（详情地址必然 404），按"404 即下架"会把真实存在的公告误判为已下架。
 */
public class LinkVerifier {

    /** 页面里出现这些字样说明公告已被删除 */
    private static final Pattern REMOVED_HINT = Pattern.compile(
            "信息不存在|公告不存在|该公告已(删除|撤销|撤回)|已下架|内容已删除|页面不存在");

    public interface Logger {
        void warn(String message);
    }

    private final TenderRepository repository;    private final HttpFetcher httpFetcher;
    private final CrawlerSettings settings;

    public LinkVerifier(TenderRepository repository, HttpFetcher httpFetcher, CrawlerSettings settings) {
        this.repository = repository;
        this.httpFetcher = httpFetcher;
        this.settings = settings;
    }

    /**
     * 校验一批公告链接。
     *
     * @param limitOverride 覆盖配置里的单次上限；null 用配置值
     * @return 本次实际校验的条数
     */
    public int verify(Integer limitOverride, ProgressListener listener) {
        int limit = limitOverride != null && limitOverride > 0 ? limitOverride : settings.verify.limit;
        LocalDateTime before = LocalDateTime.now().minusHours(Math.max(0, settings.verify.skipHours));

        List<TenderRecord> candidates = repository.findForVerification(before, Math.max(1, limit));
        if (candidates.isEmpty()) {
            return 0;
        }

        int checked = 0;
        int removedCount = 0;
        for (TenderRecord item : candidates) {
            if ("FALLBACK".equals(item.linkStatus)) {
                item.verifiedAt = LocalDateTime.now();
                repository.update(item);
                continue;
            }
            checked++;
            String url = item.accessUrl != null && !item.accessUrl.trim().isEmpty()
                    ? item.accessUrl : item.url;
            if (url == null || url.trim().isEmpty()) {
                continue;
            }
            Boolean removed = checkRemoved(url);
            item.verifiedAt = LocalDateTime.now();
            if (removed != null) {
                item.removed = removed;
                item.linkStatus = removed ? "REMOVED" : "OK";
                if (removed) {
                    removedCount++;
                }
            }
            repository.update(item);
            if (listener != null && checked % 20 == 0) {
                listener.onProgress("已校验 " + checked + " 条链接");
            }
        }
        return checked;
    }

    /** @return TRUE 已下架 / FALSE 仍存在 / null 无法判断 */
    private Boolean checkRemoved(String url) {
        try {
            FetchResponse response = httpFetcher.get(url, null);
            if (response.status() == 404 || response.status() == 410) {
                return Boolean.TRUE;
            }
            if (!response.isSuccess()) {
                return null;
            }
            String text = response.asString();
            if (text.length() > 4000) {
                text = text.substring(0, 4000);
            }
            return REMOVED_HINT.matcher(text).find() ? Boolean.TRUE : Boolean.FALSE;
        } catch (Exception e) {
            return null;
        }
    }

    public long pendingCount() {
        LocalDateTime before = LocalDateTime.now().minusHours(Math.max(0, settings.verify.skipHours));
        return repository.countForVerification(before);
    }
}
