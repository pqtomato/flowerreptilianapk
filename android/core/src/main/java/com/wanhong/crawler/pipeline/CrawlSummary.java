package com.wanhong.crawler.pipeline;

import com.wanhong.crawler.crawler.model.CrawlResult;
import com.wanhong.crawler.crawler.model.NewTender;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 一次采集（可能包含多个站点）的结果汇总。 */
public class CrawlSummary {

    /** SUCCESS / PARTIAL / FAILED / BUSY / DISABLED */
    public String status;
    public String message;
    public String triggerType;
    /** 采集结束时间；界面用它显示"上次采集：xx-xx xx:xx"，好判断数据新不新 */
    public LocalDateTime finishedAt;
    public int candidateCount;
    public int excludedCount;
    public int matchedCount;
    public int savedCount;
    public int duplicateCount;
    public int failedCount;
    public int verifiedCount;
    public long requestCount;
    public long durationMs;
    public final List<SiteSummary> sites = new ArrayList<>();
    /** 本次新入库的公告（跨站点汇总），"采集完成"通知用它列出具体是哪些 */
    public final List<NewTender> newItems = new ArrayList<>();
    /** 新入库的"可投"公告总数（newItems 会封顶，这个不会） */
    public int newItemCount;

    public static CrawlSummary of(String status, String message) {
        CrawlSummary summary = new CrawlSummary();
        summary.status = status;
        summary.message = message;
        return summary;
    }

    public void addSite(CrawlResult result) {
        SiteSummary site = new SiteSummary();
        site.siteCode = result.getSiteCode();
        site.siteName = result.getSiteName();
        site.candidateCount = result.getCandidateCount();
        site.excludedCount = result.getExcludedCount();
        site.matchedCount = result.getMatchedCount();
        site.savedCount = result.getSavedCount();
        site.duplicateCount = result.getDuplicateCount();
        site.failedCount = result.getFailedCount();
        site.requestCount = result.getRequestCount();
        site.durationMs = result.getDurationMs();
        site.errorMessage = result.getErrorMessage();
        site.messages = new ArrayList<>(result.getMessages());
        sites.add(site);

        candidateCount += result.getCandidateCount();
        excludedCount += result.getExcludedCount();
        matchedCount += result.getMatchedCount();
        savedCount += result.getSavedCount();
        duplicateCount += result.getDuplicateCount();
        failedCount += result.getFailedCount();
        requestCount += result.getRequestCount();
        durationMs += result.getDurationMs();
        newItems.addAll(result.getNewItems());
        newItemCount += result.getNewItemCount();
    }

    /** 单站点结果。 */
    public static class SiteSummary {
        public String siteCode;
        public String siteName;
        public int candidateCount;
        public int excludedCount;
        public int matchedCount;
        public int savedCount;
        public int duplicateCount;
        public int failedCount;
        public long requestCount;
        public long durationMs;
        public String errorMessage;
        public List<String> messages = new ArrayList<>();
    }
}
