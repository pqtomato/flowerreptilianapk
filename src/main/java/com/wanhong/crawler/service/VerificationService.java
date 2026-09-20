package com.wanhong.crawler.service;

import com.wanhong.crawler.config.CrawlerProperties;
import com.wanhong.crawler.entity.TenderInfo;
import com.wanhong.crawler.http.FetchResponse;
import com.wanhong.crawler.http.HttpFetcher;
import com.wanhong.crawler.repository.TenderInfoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 已下架公告校验。
 *
 * <p>沿用上一版 Python 系统的两条经验：
 * <ol>
 *   <li><b>保守判定</b>：只有明确 404/410 或页面明确写出
 *       "信息不存在 / 已删除 / 已撤销 / 已撤回" 才判为下架；网络异常、
 *       超时、被拦截一律认为公告仍然存在，宁可留着也不误删。</li>
 *   <li><b>跳过窗口</b>：同一条公告 {@code skip-hours} 小时内不重复校验，
 *       单次最多校验 {@code limit} 条，避免把整轮采集拖慢。</li>
 * </ol>
 *
 * <p>另外：收藏（tracked）的公告即使校验发现下架也保留记录，只是标记状态。
 */
@Service
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);

    /** 页面里出现这些字样说明公告已被删除 */
    private static final Pattern REMOVED_HINT = Pattern.compile(
            "信息不存在|公告不存在|该公告已(删除|撤销|撤回)|已下架|内容已删除|页面不存在");

    private final TenderInfoRepository tenderRepository;
    private final HttpFetcher httpFetcher;
    private final CrawlerProperties properties;

    public VerificationService(TenderInfoRepository tenderRepository,
                               HttpFetcher httpFetcher,
                               CrawlerProperties properties) {
        this.tenderRepository = tenderRepository;
        this.httpFetcher = httpFetcher;
        this.properties = properties;
    }

    /**
     * 校验一批公告链接。
     *
     * @param limitOverride 覆盖配置里的单次上限；null 用配置值
     * @return 本次实际校验的条数
     */
    @Transactional
    public int verify(Integer limitOverride) {
        int limit = limitOverride != null && limitOverride > 0
                ? limitOverride : properties.getVerify().getLimit();
        int skipHours = properties.getVerify().getSkipHours();
        LocalDateTime before = LocalDateTime.now().minusHours(Math.max(0, skipHours));

        List<TenderInfo> candidates = tenderRepository.findForVerification(
                before, PageRequest.of(0, Math.max(1, limit)));
        if (candidates.isEmpty()) {
            return 0;
        }

        int removedCount = 0;
        int checked = 0;
        for (TenderInfo item : candidates) {
            // linkStatus=FALLBACK 表示该公告本来就没有落地页（转载类记录），
            // 主地址 404 是已知情况，不能据此判定为下架
            if ("FALLBACK".equals(item.getLinkStatus())) {
                item.setVerifiedAt(LocalDateTime.now());
                tenderRepository.save(item);
                continue;
            }
            checked++;
            String url = item.getAccessUrl() != null && !item.getAccessUrl().isBlank()
                    ? item.getAccessUrl() : item.getUrl();
            if (url == null || url.isBlank()) {
                continue;
            }
            Boolean removed = checkRemoved(url);
            item.setVerifiedAt(LocalDateTime.now());
            if (removed != null) {
                item.setRemoved(removed);
                item.setLinkStatus(removed ? "REMOVED" : "OK");
                if (removed) {
                    removedCount++;
                }
            }
            tenderRepository.save(item);
        }
        log.info("已下架校验完成：检查 {} 条，发现已下架 {} 条", checked, removedCount);
        return checked;
    }

    /**
     * @return TRUE 已下架 / FALSE 仍然存在 / null 无法判断（保守：不改状态）
     */
    private Boolean checkRemoved(String url) {
        try {
            FetchResponse response = httpFetcher.get(url, null);
            if (response.status() == 404 || response.status() == 410) {
                return Boolean.TRUE;
            }
            if (!response.isSuccess()) {
                // 5xx / 403 等无法判断，保守认为仍然存在
                return null;
            }
            String text = response.asString();
            if (text.length() > 4000) {
                text = text.substring(0, 4000);
            }
            return REMOVED_HINT.matcher(text).find() ? Boolean.TRUE : Boolean.FALSE;
        } catch (Exception e) {
            log.debug("校验 {} 失败（视为仍然存在）：{}", url, e.getMessage());
            return null;
        }
    }

    /** 当前待校验数量（供接口展示） */
    public long pendingCount() {
        LocalDateTime before = LocalDateTime.now().minusHours(Math.max(0, properties.getVerify().getSkipHours()));
        return tenderRepository.countForVerification(before);
    }
}
