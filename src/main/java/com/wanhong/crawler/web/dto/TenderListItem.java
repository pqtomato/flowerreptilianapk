package com.wanhong.crawler.web.dto;

import com.wanhong.crawler.entity.TenderInfo;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 列表视图（不含正文，避免传输过大）。
 *
 * <p>为手机端补充了三个"一眼判断"字段：
 * {@code region}（哪里的）、{@code daysLeft}（还剩几天，负数为已截止）、
 * {@code isNew}（本轮新发现）。
 */
public record TenderListItem(Long id,
                             String siteCode,
                             String siteName,
                             String title,
                             String url,
                             String accessUrl,
                             String category,
                             String categoryCode,
                             String region,
                             LocalDateTime publishDate,
                             LocalDateTime deadlineDate,
                             Boolean deadlineEstimated,
                             Long daysLeft,
                             boolean expired,
                             Boolean isNew,
                             Boolean tracked,
                             Boolean removed,
                             String linkStatus,
                             String matchedKeywords,
                             String matchedScope,
                             String alsoFoundOn,
                             LocalDateTime crawlTime,
                             boolean hasContent,
                             boolean hasAttachment) {

    public static TenderListItem from(TenderInfo entity) {
        Long daysLeft = null;
        boolean expired = false;
        if (entity.getDeadlineDate() != null) {
            daysLeft = ChronoUnit.DAYS.between(LocalDateTime.now(), entity.getDeadlineDate());
            expired = entity.getDeadlineDate().isBefore(LocalDateTime.now());
        }
        return new TenderListItem(
                entity.getId(),
                entity.getSiteCode(),
                entity.getSiteName(),
                entity.getTitle(),
                entity.getUrl(),
                entity.getAccessUrl(),
                entity.getCategory(),
                entity.getCategoryCode(),
                entity.getRegion(),
                entity.getPublishDate(),
                entity.getDeadlineDate(),
                entity.getDeadlineEstimated(),
                daysLeft,
                expired,
                entity.getIsNew(),
                entity.getTracked(),
                entity.getRemoved(),
                entity.getLinkStatus(),
                entity.getMatchedKeywords(),
                entity.getMatchedScope(),
                entity.getAlsoFoundOn(),
                entity.getCrawlTime(),
                entity.getContent() != null && !entity.getContent().isBlank(),
                entity.getAttachments() != null && !entity.getAttachments().isBlank());
    }
}
