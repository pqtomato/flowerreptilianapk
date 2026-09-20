package com.wanhong.crawler.web;

import com.wanhong.crawler.entity.CrawlLog;
import com.wanhong.crawler.service.CrawlService;
import com.wanhong.crawler.service.KeywordMatcher;
import com.wanhong.crawler.service.TenderQueryService;
import com.wanhong.crawler.service.VerificationService;
import com.wanhong.crawler.web.dto.CrawlLogListItem;
import com.wanhong.crawler.web.dto.CrawlStatus;
import com.wanhong.crawler.web.dto.CrawlSummary;
import com.wanhong.crawler.web.dto.SiteStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集任务接口。
 */
@RestController
@RequestMapping("/api/crawl")
public class CrawlController {

    private final CrawlService crawlService;
    private final TenderQueryService tenderQueryService;
    private final VerificationService verificationService;

    public CrawlController(CrawlService crawlService,
                           TenderQueryService tenderQueryService,
                           VerificationService verificationService) {
        this.crawlService = crawlService;
        this.tenderQueryService = tenderQueryService;
        this.verificationService = verificationService;
    }

    /**
     * 异步触发采集，立即返回当前状态，进度通过 {@code /api/crawl/status} 查询。
     *
     * @param lookbackDays 只采集最近 N 天；不传用站点配置；0 表示不限（首次建库要历史数据）
     */
    @PostMapping("/run")
    public CrawlStatus run(@RequestParam(defaultValue = "all") String site,
                           @RequestParam(required = false) Integer maxKeywords,
                           @RequestParam(required = false) Integer lookbackDays) {
        return crawlService.triggerAsync(site, maxKeywords, lookbackDays, "MANUAL");
    }

    /**
     * 同步触发采集，直到完成才返回（适合脚本 / 定时任务调用）。
     */
    @PostMapping("/run-sync")
    public CrawlSummary runSync(@RequestParam(defaultValue = "all") String site,
                                @RequestParam(required = false) Integer maxKeywords,
                                @RequestParam(required = false) Integer lookbackDays) {
        return crawlService.crawl(site, maxKeywords, lookbackDays, "MANUAL");
    }

    /**
     * 手动触发已下架校验（采集后也会自动跑一次）。
     */
    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestParam(required = false) Integer limit) {
        int checked = verificationService.verify(limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checked", checked);
        result.put("pending", verificationService.pendingCount());
        return result;
    }

    @GetMapping("/status")
    public CrawlStatus status() {
        return crawlService.status();
    }

    @GetMapping("/logs")
    public List<CrawlLogListItem> logs(@RequestParam(defaultValue = "20") int limit) {
        return tenderQueryService.recentLogs(limit);
    }

    /**
     * 单条采集日志的完整明细（含逐条失败原因）。
     */
    @GetMapping("/logs/{id}")
    public CrawlLog logDetail(@PathVariable Long id) {
        return tenderQueryService.findLogById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "日志不存在：" + id));
    }

    @GetMapping("/sites")
    public List<SiteStatus> sites() {
        return tenderQueryService.siteStatuses();
    }
}
