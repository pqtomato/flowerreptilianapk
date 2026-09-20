package com.wanhong.crawler.crawler.model;

import java.util.ArrayList;
import java.util.List;

/** 单个站点的采集结果统计。 */
public class CrawlResult {

    private final String siteCode;
    private final String siteName;
    private int candidateCount;
    private int excludedCount;
    private int matchedCount;
    private int savedCount;
    private int duplicateCount;
    private int failedCount;
    private long requestCount;
    private long durationMs;
    private String errorMessage;
    private final List<String> messages = new ArrayList<>();

    public CrawlResult(String siteCode, String siteName) {
        this.siteCode = siteCode;
        this.siteName = siteName;
    }

    public void addMessage(String message) {
        if (messages.size() < 200 && message != null) {
            messages.add(message);
        }
    }

    public String getSiteCode() {
        return siteCode;
    }

    public String getSiteName() {
        return siteName;
    }

    public int getCandidateCount() {
        return candidateCount;
    }

    public void addCandidate(int count) {
        this.candidateCount += count;
    }

    public int getExcludedCount() {
        return excludedCount;
    }

    public void incrementExcluded() {
        this.excludedCount++;
    }

    public int getMatchedCount() {
        return matchedCount;
    }

    public void incrementMatched() {
        this.matchedCount++;
    }

    public int getSavedCount() {
        return savedCount;
    }

    public void incrementSaved() {
        this.savedCount++;
    }

    public int getDuplicateCount() {
        return duplicateCount;
    }

    public void incrementDuplicate() {
        this.duplicateCount++;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void incrementFailed() {
        this.failedCount++;
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
}
