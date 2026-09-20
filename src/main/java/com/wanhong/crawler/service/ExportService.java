package com.wanhong.crawler.service;

import com.wanhong.crawler.entity.TenderInfo;
import com.wanhong.crawler.web.dto.TenderQueryParams;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 结果导出。
 */
@Service
public class ExportService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TenderQueryService tenderQueryService;

    public ExportService(TenderQueryService tenderQueryService) {
        this.tenderQueryService = tenderQueryService;
    }

    /**
     * 导出 CSV。带 UTF-8 BOM，Excel 直接双击不会乱码。
     *
     * <p>列里带上「剩余天数」，导出到手机上也能直接看出来还能不能投。
     */
    public byte[] exportCsv(TenderQueryParams params, int limit) {
        List<TenderInfo> list = tenderQueryService.listForExport(params, limit);
        StringBuilder sb = new StringBuilder(1024 + list.size() * 320);
        sb.append('\uFEFF');
        sb.append("标题,站点,分类,区县,发布日期,截止日期,剩余天数,截止日期是否估算,命中关键词,命中位置,详情链接\n");
        for (TenderInfo tender : list) {
            sb.append(csv(tender.getTitle())).append(',')
                    .append(csv(tender.getSiteName())).append(',')
                    .append(csv(tender.getCategory())).append(',')
                    .append(csv(tender.getRegion())).append(',')
                    .append(csv(format(tender.getPublishDate()))).append(',')
                    .append(csv(format(tender.getDeadlineDate()))).append(',')
                    .append(csv(daysLeftText(tender.getDeadlineDate()))).append(',')
                    .append(csv(Boolean.TRUE.equals(tender.getDeadlineEstimated()) ? "是" : "否")).append(',')
                    .append(csv(tender.getMatchedKeywords())).append(',')
                    .append(csv(tender.getMatchedScope())).append(',')
                    .append(csv(tender.getUrl())).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String format(LocalDateTime time) {
        return time == null ? "" : time.format(DATE_TIME);
    }

    private String daysLeftText(LocalDateTime deadline) {
        if (deadline == null) {
            return "";
        }
        long days = ChronoUnit.DAYS.between(LocalDateTime.now(), deadline);
        if (days < 0) {
            return "已截止";
        }
        return days + " 天";
    }

    /**
     * CSV 字段转义，并防御 CSV 注入（Excel 会把 = + - @ 开头的内容当公式执行）。
     */
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
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return "\"" + text + "\"";
        }
        return text;
    }
}
