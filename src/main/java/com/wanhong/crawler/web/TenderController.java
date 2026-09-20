package com.wanhong.crawler.web;

import com.wanhong.crawler.common.RegionNames;
import com.wanhong.crawler.entity.TenderInfo;
import com.wanhong.crawler.service.ExportService;
import com.wanhong.crawler.service.TenderQueryService;
import com.wanhong.crawler.web.dto.PageResult;
import com.wanhong.crawler.web.dto.SiteStatus;
import com.wanhong.crawler.web.dto.TenderListItem;
import com.wanhong.crawler.web.dto.TenderQueryParams;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公告查询接口。
 */
@RestController
@RequestMapping("/api/tenders")
public class TenderController {

    private final TenderQueryService tenderQueryService;
    private final ExportService exportService;

    public TenderController(TenderQueryService tenderQueryService, ExportService exportService) {
        this.tenderQueryService = tenderQueryService;
        this.exportService = exportService;
    }

    @GetMapping
    public PageResult<TenderListItem> list(
            @RequestParam(required = false) String site,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean onlyOpen,
            @RequestParam(defaultValue = "false") boolean onlyNew,
            @RequestParam(defaultValue = "false") boolean tracked,
            @RequestParam(defaultValue = "false") boolean includeRemoved,
            @RequestParam(defaultValue = "publish") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return tenderQueryService.query(params(site, keyword, region, from, to,
                onlyOpen, onlyNew, tracked, includeRemoved, sort), page, size);
    }

    @GetMapping("/{id}")
    public TenderInfo detail(@PathVariable Long id) {
        return tenderQueryService.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "公告不存在：" + id));
    }

    /** 收藏 / 取消收藏（手机端星标） */
    @PostMapping("/{id}/track")
    public TenderInfo track(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean tracked) {
        return tenderQueryService.setTracked(id, tracked)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "公告不存在：" + id));
    }

    /** 全部标记已读，清掉手机端的"新"角标 */
    @PostMapping("/mark-read")
    public Map<String, Object> markRead() {
        int count = tenderQueryService.markAllRead();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("markedRead", count);
        result.put("newCount", tenderQueryService.countNew());
        return result;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        Map<String, Long> counts = tenderQueryService.countBySite();
        stats.put("countBySite", counts);
        stats.put("total", counts.values().stream().mapToLong(Long::longValue).sum());
        stats.put("newCount", tenderQueryService.countNew());
        stats.put("openCount", tenderQueryService.countOpen());
        stats.put("trackedCount", tenderQueryService.countTracked());
        stats.put("countByRegion", tenderQueryService.countByRegion());
        stats.put("knownRegions", RegionNames.knownRegions());
        List<SiteStatus> sites = tenderQueryService.siteStatuses();
        stats.put("sites", sites);
        return stats;
    }

    @GetMapping("/export.csv")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String site,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean onlyOpen,
            @RequestParam(defaultValue = "false") boolean tracked,
            @RequestParam(defaultValue = "5000") int limit) {
        byte[] csv = exportService.exportCsv(params(site, keyword, region, from, to,
                onlyOpen, false, tracked, false, TenderQueryParams.SORT_PUBLISH), limit);
        String filename = URLEncoder.encode("花卉招标数据.csv", StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv);
    }

    private TenderQueryParams params(String site, String keyword, String region, LocalDate from, LocalDate to,
                                     boolean onlyOpen, boolean onlyNew, boolean tracked,
                                     boolean includeRemoved, String sort) {
        return new TenderQueryParams(
                blankToNull(site),
                blankToNull(keyword),
                blankToNull(region),
                TenderQueryService.startOfDay(from),
                TenderQueryService.endOfDay(to),
                onlyOpen, onlyNew, tracked, includeRemoved, sort);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
