package com.wanhong.crawler.scheduler;

import com.wanhong.crawler.config.CrawlerProperties;
import com.wanhong.crawler.service.CrawlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时增量采集。
 */
@Component
public class CrawlScheduler {

    private static final Logger log = LoggerFactory.getLogger(CrawlScheduler.class);

    private final CrawlService crawlService;
    private final CrawlerProperties properties;

    public CrawlScheduler(CrawlService crawlService, CrawlerProperties properties) {
        this.crawlService = crawlService;
        this.properties = properties;
    }

    @Scheduled(cron = "${crawler.schedule.cron:0 30 2 * * ?}",
            zone = "${crawler.schedule.zone:Asia/Shanghai}")
    public void scheduledCrawl() {
        if (!properties.isEnabled()) {
            return;
        }
        if (!properties.getSchedule().isEnabled()) {
            log.debug("定时采集已关闭（crawler.schedule.enabled=false）");
            return;
        }
        if (crawlService.status().running()) {
            log.warn("已有采集任务在执行，本次定时任务跳过");
            return;
        }
        log.info("定时采集开始");
        try {
            crawlService.crawl("all", null, "SCHEDULED");
        } catch (Exception e) {
            log.error("定时采集失败", e);
        }
    }
}
