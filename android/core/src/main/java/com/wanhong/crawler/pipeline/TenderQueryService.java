package com.wanhong.crawler.pipeline;

import com.wanhong.crawler.common.RegionNames;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 公告查询 / 统计 / 导出。 */
public class TenderQueryService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TenderRepository repository;

    public TenderQueryService(TenderRepository repository) {
        this.repository = repository;
    }

    public PageResult<TenderRecord> query(TenderQuery query, int page, int size) {
        int pageIndex = Math.max(0, page);
        int pageSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        long total = repository.countQuery(query);
        List<TenderRecord> content = repository.query(query, pageSize, pageIndex * pageSize);
        return new PageResult<>(content, pageIndex, pageSize, total);
    }

    public TenderRecord findById(long id) {
        return repository.findById(id);
    }

    public boolean setTracked(long id, boolean tracked) {
        return repository.setTracked(id, tracked);
    }

    public int markAllRead() {
        return repository.markAllRead();
    }

    public long countNew() {
        TenderQuery query = new TenderQuery();
        query.onlyNew = true;
        return repository.countQuery(query);
    }

    public long countOpen() {
        TenderQuery query = new TenderQuery();
        query.onlyOpen = true;
        return repository.countQuery(query);
    }

    public long countTracked() {
        TenderQuery query = new TenderQuery();
        query.trackedOnly = true;
        return repository.countQuery(query);
    }

    /** 站点统计（含最近采集信息由调用方补充） */
    public Map<String, Long> countBySite(List<String> siteCodes) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String code : siteCodes) {
            counts.put(code, repository.countBySite(code));
        }
        return counts;
    }

    /** 区县分布，供前端筛选下拉。 */
    public Map<String, Long> countByRegion() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String region : RegionNames.knownRegions()) {
            TenderQuery query = new TenderQuery();
            query.region = region;
            long count = repository.countQuery(query);
            if (count > 0) {
                counts.put(region, count);
            }
        }
        return counts;
    }

    /**
     * 导出 CSV。带 UTF-8 BOM，Excel 直接打开不乱码；
     * 并防御 CSV 注入（Excel 会把 = + - @ 开头的内容当公式执行）。
     */
    public String exportCsv(TenderQuery query, int limit) {
        int size = Math.min(Math.max(1, limit), 10000);
        List<TenderRecord> list = repository.query(query, size, 0);
        StringBuilder sb = new StringBuilder(1024 + list.size() * 320);
        sb.append('\uFEFF');
        sb.append("标题,站点,分类,区县,发布日期,截止日期,剩余天数,截止日期是否估算,命中关键词,命中位置,详情链接\n");
        for (TenderRecord tender : list) {
            sb.append(csv(tender.title)).append(',')
                    .append(csv(tender.siteName)).append(',')
                    .append(csv(tender.category)).append(',')
                    .append(csv(tender.region)).append(',')
                    .append(csv(format(tender.publishDate))).append(',')
                    .append(csv(format(tender.deadlineDate))).append(',')
                    .append(csv(daysLeftText(tender.deadlineDate))).append(',')
                    .append(csv(tender.deadlineEstimated ? "是" : "否")).append(',')
                    .append(csv(tender.matchedKeywords)).append(',')
                    .append(csv(tender.matchedScope)).append(',')
                    .append(csv(tender.url)).append('\n');
        }
        return sb.toString();
    }

    public static LocalDateTime startOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    public static LocalDateTime endOfDay(LocalDate date) {
        return date == null ? null : date.atTime(LocalTime.MAX);
    }

    private String format(LocalDateTime time) {
        return time == null ? "" : time.format(DATE_TIME);
    }

    private String daysLeftText(LocalDateTime deadline) {
        if (deadline == null) {
            return "";
        }
        long days = ChronoUnit.DAYS.between(LocalDateTime.now(), deadline);
        return days < 0 ? "已截止" : days + " 天";
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        String text = value;
        if (!text.isEmpty()) {
            char first = text.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@') {
                text = "'" + text;
            }
        }
        text = text.replace("\"", "\"\"");
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return "\"" + text + "\"";
        }
        return text;
    }

    /** 供 UI 使用：把记录转成前端需要的字段（含剩余天数） */
    public static Map<String, Object> toListItem(TenderRecord record) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", record.id);
        item.put("siteCode", record.siteCode);
        item.put("siteName", record.siteName);
        item.put("title", record.title);
        item.put("url", record.url);
        item.put("accessUrl", record.accessUrl);
        item.put("category", record.category);
        item.put("categoryCode", record.categoryCode);
        item.put("region", record.region);
        item.put("publishDate", record.publishDate);
        item.put("deadlineDate", record.deadlineDate);
        item.put("deadlineEstimated", record.deadlineEstimated);
        item.put("daysLeft", record.deadlineDate == null ? null : record.daysLeft());
        item.put("expired", record.expired());
        item.put("isNew", record.isNew);
        item.put("tracked", record.tracked);
        item.put("removed", record.removed);
        item.put("linkStatus", record.linkStatus);
        item.put("matchedKeywords", record.matchedKeywords);
        item.put("matchedScope", record.matchedScope);
        item.put("alsoFoundOn", record.alsoFoundOn);
        item.put("crawlTime", record.crawlTime);
        item.put("hasContent", record.hasContent());
        item.put("hasAttachment", record.hasAttachment());
        return item;
    }

    /** 供 UI 使用：详情视图（含正文与附件） */
    public static Map<String, Object> toDetail(TenderRecord record) {
        Map<String, Object> item = toListItem(record);
        item.put("summary", record.summary);
        item.put("content", record.content);
        item.put("attachments", record.attachments);
        item.put("deadlineSource", record.deadlineSource);
        item.put("sourceKeyword", record.sourceKeyword);
        item.put("verifiedAt", record.verifiedAt);
        return item;
    }

    public static List<Map<String, Object>> toListItems(List<TenderRecord> records) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (TenderRecord record : records) {
            list.add(toListItem(record));
        }
        return list;
    }
}
