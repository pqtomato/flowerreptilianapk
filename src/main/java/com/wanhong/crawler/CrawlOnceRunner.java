package com.wanhong.crawler;

import com.wanhong.crawler.service.CrawlService;
import com.wanhong.crawler.web.dto.CrawlSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 命令行一次性采集：{@code java -jar flower-tender-crawler.jar --crawl.once=true}。
 *
 * <p>常用参数：
 * <ul>
 *   <li>{@code --crawl.site=all|aqggzy|cgj|wyxmzx}</li>
 *   <li>{@code --crawl.maxKeywords=10} 只用前 10 个关键词（快速验证）</li>
 *   <li>{@code --crawl.lookbackDays=0} 不限时间（首次建库要历史数据；默认用站点配置的 30 天）</li>
 * </ul>
 * 采集结束后进程退出，退出码 0 表示成功、1 表示失败，方便接入 cron / 计划任务。
 */
@Component
public class CrawlOnceRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CrawlOnceRunner.class);

    private final CrawlService crawlService;
    private final ConfigurableApplicationContext context;

    public CrawlOnceRunner(CrawlService crawlService, ConfigurableApplicationContext context) {
        this.crawlService = crawlService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("crawl.once")) {
            return;
        }
        String site = firstValue(args, "crawl.site", "all");
        int maxKeywords = Integer.parseInt(firstValue(args, "crawl.maxKeywords", "0"));
        int lookbackDays = Integer.parseInt(firstValue(args, "crawl.lookbackDays", "-1"));

        log.info("命令行采集启动：site={}，maxKeywords={}，lookbackDays={}",
                site, maxKeywords, lookbackDays);
        CrawlSummary summary;
        try {
            summary = crawlService.crawl(site,
                    maxKeywords > 0 ? maxKeywords : null,
                    lookbackDays >= 0 ? lookbackDays : null,
                    "MANUAL");
        } catch (Exception e) {
            log.error("命令行采集失败", e);
            System.exit(SpringApplication.exit(context, () -> 1));
            return;
        }

        log.info("命令行采集结束：状态={}，候选={}，命中={}，新增={}，重复={}，失败={}，耗时={}ms",
                summary.getStatus(), summary.getCandidateCount(), summary.getMatchedCount(),
                summary.getSavedCount(), summary.getDuplicateCount(), summary.getFailedCount(),
                summary.getDurationMs());
        summary.getSites().forEach(siteSummary ->
                log.info("  - {}：候选 {}，命中 {}，新增 {}，重复 {}，失败 {}，请求 {}，耗时 {}ms{}",
                        siteSummary.getSiteName(), siteSummary.getCandidateCount(), siteSummary.getMatchedCount(),
                        siteSummary.getSavedCount(), siteSummary.getDuplicateCount(), siteSummary.getFailedCount(),
                        siteSummary.getRequestCount(), siteSummary.getDurationMs(),
                        siteSummary.getErrorMessage() == null ? "" : "，错误：" + siteSummary.getErrorMessage()));

        int exitCode = "FAILED".equals(summary.getStatus()) ? 1 : 0;
        System.exit(SpringApplication.exit(context, () -> exitCode));
    }

    private String firstValue(ApplicationArguments args, String option, String defaultValue) {
        if (!args.containsOption(option)) {
            return defaultValue;
        }
        var values = args.getOptionValues(option);
        if (values == null || values.isEmpty() || values.get(0) == null || values.get(0).isBlank()) {
            return defaultValue;
        }
        return values.get(0).trim();
    }
}
