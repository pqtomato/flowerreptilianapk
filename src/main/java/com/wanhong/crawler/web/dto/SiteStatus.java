package com.wanhong.crawler.web.dto;

import java.time.LocalDateTime;

/**
 * 站点概览：开关状态、已采集数量、最近一次采集情况。
 */
public record SiteStatus(String siteCode,
                         String siteName,
                         boolean enabled,
                         long totalCount,
                         LocalDateTime lastPublishDate,
                         LocalDateTime lastCrawlTime,
                         String lastCrawlStatus,
                         Integer lastSavedCount) {
}
