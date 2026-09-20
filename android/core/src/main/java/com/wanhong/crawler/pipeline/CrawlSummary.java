package com.wanhong.crawler.pipeline;

import com.wanhong.crawler.crawler.model.CrawlResult;

import java.util.ArrayList;
import java.util.List;

/** 一次采集（可能包含多个站点）的结果汇总。 */
public class CrawlSummary {

    /** SUCCESS / PARTIAL / FAILED / BUSY / DISABLED */
    public String status;
    public String message;
    public String triggerType;
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
