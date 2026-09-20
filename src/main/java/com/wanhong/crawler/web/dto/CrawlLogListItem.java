package com.wanhong.crawler.web.dto;

import com.wanhong.crawler.entity.CrawlLog;

import java.time.LocalDateTime;

/**
 * 采集日志列表视图（不含可能很大的 detail 字段，避免列表接口传输过大）。
 * 需要完整明细时用 {@code GET /api/crawl/logs/{id}}。
 */
public record CrawlLogListItem(Long id,
                               String siteCode,
                               String triggerType,
                               String status,
                               LocalDateTime startTime,
                               LocalDateTime endTime,
                               Long durationMs,
                               Integer candidateCount,
                               Integer matchedCount,
                               Integer savedCount,
                               Integer duplicateCount,
                               Integer failedCount,
                               Integer requestCount,
                               String errorMessage) {

    public static CrawlLogListItem from(CrawlLog entity) {
        return new CrawlLogListItem(
                entity.getId(),
                entity.getSiteCode(),
                entity.getTriggerType(),
                entity.getStatus(),
                entity.getStartTime(),
                entity.getEndTime(),
                entity.getDurationMs(),
                entity.getCandidateCount(),
                entity.getMatchedCount(),
                entity.getSavedCount(),
                entity.getDuplicateCount(),
                entity.getFailedCount(),
                entity.getRequestCount(),
                entity.getErrorMessage());
    }
}
