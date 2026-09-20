package com.wanhong.crawler.web.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 采集任务实时状态。
 */
public record CrawlStatus(boolean running,
                          String site,
                          LocalDateTime since,
                          long requestCount,
                          CrawlSummary lastSummary,
                          List<Map<String, Object>> sites) {
}
