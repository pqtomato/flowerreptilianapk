package com.wanhong.crawler.crawler.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个站点的采集结果统计。
 */
public class CrawlResult {

    private String siteCode;
    private String siteName;
    /** 浏览到的候选公告数 */
    private int candidateCount;
    /** 命中关键词数 */
    private int matchedCount;
    /** 新入库数 */
    private int savedCount;
    /** 重复跳过数 */
    private int duplicateCount;
    /** 失败数 */
    private int failedCount;
    /** 被排除词过滤掉的数量 */
    private int excludedCount;
    /** 该站点新增的 HTTP 请求数 */
    private long requestCount;
    private long durationMs;
    private String errorMessage;
    private final List<String> messages = new ArrayList<>();

    public CrawlResult(String siteCode, String siteName) {
        this.siteCode = siteCode;
        this.siteName = siteName;
    }

    public void addMessage(String message) {
        if (messages.size() < 500) {
            messages.add(message);
        }
    }

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

    public void addCandidate(int count) {
        this.candidateCount += count;
    }

    public int getMatchedCount() {
        return matchedCount;
    }

    public void setMatchedCount(int matchedCount) {
        this.matchedCount = matchedCount;
    }

    public void incrementMatched() {
        this.matchedCount++;
    }

    public int getSavedCount() {
        return savedCount;
    }

    public void setSavedCount(int savedCount) {
        this.savedCount = savedCount;
    }

    public void incrementSaved() {
        this.savedCount++;
    }

    public int getDuplicateCount() {
        return duplicateCount;
    }

    public void setDuplicateCount(int duplicateCount) {
        this.duplicateCount = duplicateCount;
    }

    public void incrementDuplicate() {
        this.duplicateCount++;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(int failedCount) {
        this.failedCount = failedCount;
    }

    public void incrementFailed() {
        this.failedCount++;
    }

    public int getExcludedCount() {
        return excludedCount;
    }

    public void setExcludedCount(int excludedCount) {
        this.excludedCount = excludedCount;
    }

    public void incrementExcluded() {
        this.excludedCount++;
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
