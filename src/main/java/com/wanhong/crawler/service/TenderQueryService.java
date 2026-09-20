package com.wanhong.crawler.service;

import com.wanhong.crawler.common.RegionNames;
import com.wanhong.crawler.entity.CrawlLog;
import com.wanhong.crawler.entity.TenderInfo;
import com.wanhong.crawler.repository.CrawlLogRepository;
import com.wanhong.crawler.repository.TenderInfoRepository;
import com.wanhong.crawler.repository.TenderSpecifications;
import com.wanhong.crawler.web.dto.CrawlLogListItem;
import com.wanhong.crawler.web.dto.PageResult;
import com.wanhong.crawler.web.dto.SiteStatus;
import com.wanhong.crawler.web.dto.TenderListItem;
import com.wanhong.crawler.web.dto.TenderQueryParams;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 公告查询与轻量写操作（已读 / 收藏）。
 */
@Service
public class TenderQueryService {

    private static final int MAX_PAGE_SIZE = 200;

    private final TenderInfoRepository tenderRepository;
    private final CrawlLogRepository crawlLogRepository;
    private final CrawlService crawlService;

    public TenderQueryService(TenderInfoRepository tenderRepository,
                              CrawlLogRepository crawlLogRepository,
                              CrawlService crawlService) {
        this.tenderRepository = tenderRepository;
        this.crawlLogRepository = crawlLogRepository;
        this.crawlService = crawlService;
    }

    public PageResult<TenderListItem> query(TenderQueryParams params, int page, int size) {
        int pageIndex = Math.max(0, page);
        int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Page<TenderInfo> result = tenderRepository.findAll(
                TenderSpecifications.build(params), pageable(params, pageIndex, pageSize));
        List<TenderListItem> items = result.getContent().stream().map(TenderListItem::from).toList();
        return PageResult.of(items, pageIndex, pageSize, result.getTotalElements());
    }

    public List<TenderInfo> listForExport(TenderQueryParams params, int limit) {
        int size = Math.min(Math.max(1, limit), 10000);
        return tenderRepository.findAll(
                TenderSpecifications.build(params),
                pageable(params, 0, size)).getContent();
    }

    private Pageable pageable(TenderQueryParams params, int pageIndex, int pageSize) {
        if (params.sortByDeadline()) {
            // 截止时间升序：最紧急的排最前（手机端默认视角）
            return PageRequest.of(pageIndex, pageSize,
                    Sort.by(Sort.Direction.ASC, "deadlineDate").and(Sort.by(Sort.Direction.DESC, "publishDate")));
        }
        return PageRequest.of(pageIndex, pageSize,
                Sort.by(Sort.Direction.DESC, "publishDate").and(Sort.by(Sort.Direction.DESC, "id")));
    }

    public Optional<TenderInfo> findById(Long id) {
        return tenderRepository.findById(id);
    }

    /** 新公告数量（手机端角标） */
    public long countNew() {
        return tenderRepository.count(TenderSpecifications.build(new TenderQueryParams(
                null, null, null, null, null, false, true, false, false, TenderQueryParams.SORT_PUBLISH)));
    }

    /** 未截止数量 */
    public long countOpen() {
        return tenderRepository.count(TenderSpecifications.build(new TenderQueryParams(
                null, null, null, null, null, true, false, false, false, TenderQueryParams.SORT_PUBLISH)));
    }

    public long countTracked() {
        return tenderRepository.count(TenderSpecifications.build(new TenderQueryParams(
                null, null, null, null, null, false, false, true, false, TenderQueryParams.SORT_PUBLISH)));
    }

    /** 全部标记为已读（手机端"清角标"） */
    @Transactional
    public int markAllRead() {
        List<TenderInfo> items = tenderRepository.findAll(TenderSpecifications.build(new TenderQueryParams(
                null, null, null, null, null, false, true, false, false, TenderQueryParams.SORT_PUBLISH)));
        items.forEach(item -> {
            item.setIsNew(Boolean.FALSE);
            item.setUpdateTime(LocalDateTime.now());
        });
        tenderRepository.saveAll(items);
        return items.size();
    }

    /** 收藏 / 取消收藏 */
    @Transactional
    public Optional<TenderInfo> setTracked(Long id, boolean tracked) {
        return tenderRepository.findById(id).map(item -> {
            item.setTracked(tracked);
            item.setUpdateTime(LocalDateTime.now());
            return tenderRepository.save(item);
        });
    }

    public Map<String, Long> countBySite() {
        Map<String, Long> counts = new LinkedHashMap<>();
        crawlService.availableSites().forEach(site -> {
            String code = (String) site.get("siteCode");
            counts.put(code, tenderRepository.countBySiteCode(code));
        });
        return counts;
    }

    /** 区县分布，供手机端筛选下拉 */
    public Map<String, Long> countByRegion() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String region : RegionNames.knownRegions()) {
            long count = tenderRepository.count(TenderSpecifications.build(new TenderQueryParams(
                    null, null, region, null, null, false, false, false, false, TenderQueryParams.SORT_PUBLISH)));
            if (count > 0) {
                counts.put(region, count);
            }
        }
        return counts;
    }

    public List<CrawlLogListItem> recentLogs(int limit) {
        return crawlLogRepository
                .findAllByOrderByStartTimeDesc(PageRequest.of(0, Math.min(Math.max(1, limit), 200)))
                .stream()
                .map(CrawlLogListItem::from)
                .toList();
    }

    public Optional<CrawlLog> findLogById(Long id) {
        return crawlLogRepository.findById(id);
    }

    public List<SiteStatus> siteStatuses() {
        List<SiteStatus> statuses = new ArrayList<>();
        for (Map<String, Object> site : crawlService.availableSites()) {
            String code = (String) site.get("siteCode");
            String name = (String) site.get("siteName");
            boolean enabled = Boolean.TRUE.equals(site.get("enabled"));
            long total = tenderRepository.countBySiteCode(code);
            LocalDateTime lastPublish = tenderRepository.findAll(
                            TenderSpecifications.build(new TenderQueryParams(code, null, null, null, null,
                                    false, false, false, false, TenderQueryParams.SORT_PUBLISH)),
                            PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "publishDate")))
                    .getContent().stream().findFirst().map(TenderInfo::getPublishDate).orElse(null);
            CrawlLog lastLog = crawlLogRepository.findAllByOrderByStartTimeDesc(PageRequest.of(0, 20)).stream()
                    .filter(logEntry -> code.equals(logEntry.getSiteCode()))
                    .findFirst().orElse(null);
            statuses.add(new SiteStatus(code, name, enabled, total, lastPublish,
                    lastLog == null ? null : lastLog.getStartTime(),
                    lastLog == null ? null : lastLog.getStatus(),
                    lastLog == null ? null : lastLog.getSavedCount()));
        }
        return statuses;
    }

    /** 把 LocalDate 转成查询用的时间边界 */
    public static LocalDateTime startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    public static LocalDateTime endOfDay(LocalDate date) {
        return date == null ? null : date.atTime(LocalTime.MAX);
    }
}
