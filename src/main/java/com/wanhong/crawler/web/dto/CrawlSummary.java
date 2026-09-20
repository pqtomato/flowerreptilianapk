package com.wanhong.crawler.web.dto;

import com.wanhong.crawler.crawler.model.CrawlResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次采集（可能包含多个站点）的结果汇总。
 */
public class CrawlSummary {

    /** SUCCESS / PARTIAL / FAILED / BUSY / DISABLED */
    private String status;
    private String message;
    private String triggerType;
    private int candidateCount;
    private int matchedCount;
    private int savedCount;
    private int duplicateCount;
    private int failedCount;
    private long requestCount;
    private long durationMs;
    /** 本次已下架校验条数 */
    private int verifiedCount;
    private List<SiteSummary> sites = new ArrayList<>();

    public static CrawlSummary of(String status, String message) {
        CrawlSummary summary = new CrawlSummary();
        summary.setStatus(status);
        summary.setMessage(message);
        return summary;
    }

    public void addSite(CrawlResult result) {
        SiteSummary site = new SiteSummary();
        site.setSiteCode(result.getSiteCode());
        site.setSiteName(result.getSiteName());
        site.setCandidateCount(result.getCandidateCount());
        site.setMatchedCount(result.getMatchedCount());
        site.setSavedCount(result.getSavedCount());
        site.setDuplicateCount(result.getDuplicateCount());
        site.setFailedCount(result.getFailedCount());
        site.setRequestCount(result.getRequestCount());
        site.setDurationMs(result.getDurationMs());
        site.setErrorMessage(result.getErrorMessage());
        site.setMessages(result.getMessages());
        sites.add(site);

        candidateCount += result.getCandidateCount();
        matchedCount += result.getMatchedCount();
        savedCount += result.getSavedCount();
        duplicateCount += result.getDuplicateCount();
        failedCount += result.getFailedCount();
        requestCount += result.getRequestCount();
        durationMs += result.getDurationMs();
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public int getCandidateCount() {
        return candidateCount;
    }

    public void setCandidateCount(int candidateCount) {
        this.candidateCount = candidateCount;
    }

    public int getMatchedCount() {
        return matchedCount;
    }

    public void setMatchedCount(int matchedCount) {
        this.matchedCount = matchedCount;
    }

    public int getSavedCount() {
        return savedCount;
    }

    public void setSavedCount(int savedCount) {
        this.savedCount = savedCount;
    }

    public int getDuplicateCount() {
        return duplicateCount;
    }

    public void setDuplicateCount(int duplicateCount) {
        this.duplicateCount = duplicateCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(int failedCount) {
        this.failedCount = failedCount;
    }

    public long getRequestCount() {
        return requestCount;
    }

    public void setRequestCount(long requestCount) {
        this.requestCount = requestCount;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public int getVerifiedCount() {
        return verifiedCount;
    }

    public void setVerifiedCount(int verifiedCount) {
        this.verifiedCount = verifiedCount;
    }

    public List<SiteSummary> getSites() {
        return sites;
    }

    public void setSites(List<SiteSummary> sites) {
        this.sites = sites;
    }

    /**
     * 单站点结果。
     */
    public static class SiteSummary {
        private String siteCode;
        private String siteName;
        private int candidateCount;
        private int matchedCount;
        private int savedCount;
        private int duplicateCount;
        private int failedCount;
        private long requestCount;
        private long durationMs;
        private String errorMessage;
        private List<String> messages = new ArrayList<>();

        public String getSiteCode() {
            return siteCode;
        }

        public void setSiteCode(String siteCode) {
            this.siteCode = siteCode;
        }

        public String getSiteName() {
            return siteName;
        }

        public void setSiteName(String siteName) {
            this.siteName = siteName;
        }

        public int getCandidateCount() {
            return candidateCount;
        }

        public void setCandidateCount(int candidateCount) {
            this.candidateCount = candidateCount;
        }

        public int getMatchedCount() {
            return matchedCount;
        }

        public void setMatchedCount(int matchedCount) {
            this.matchedCount = matchedCount;
        }

        public int getSavedCount() {
            return savedCount;
        }

        public void setSavedCount(int savedCount) {
            this.savedCount = savedCount;
        }

        public int getDuplicateCount() {
            return duplicateCount;
        }

        public void setDuplicateCount(int duplicateCount) {
            this.duplicateCount = duplicateCount;
        }

        public int getFailedCount() {
            return failedCount;
        }

        public void setFailedCount(int failedCount) {
            this.failedCount = failedCount;
        }

        public long getRequestCount() {
            return requestCount;
        }

        public void setRequestCount(long requestCount) {
            this.requestCount = requestCount;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public void setDurationMs(long durationMs) {
            this.durationMs = durationMs;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }

        public List<String> getMessages() {
            return messages;
        }

        public void setMessages(List<String> messages) {
            this.messages = messages;
        }
    }
}
