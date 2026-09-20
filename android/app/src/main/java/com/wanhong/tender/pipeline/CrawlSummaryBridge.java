package com.wanhong.tender.pipeline;

import com.wanhong.crawler.pipeline.CrawlSummary;
import com.wanhong.crawler.pipeline.ProgressListener;
import com.wanhong.tender.core.CrawlerHolder;

/**
 * 让 Worker / 前台服务能拿到"可序列化"的采集结果。
 *
 * <p>内核的 CrawlSummary 只在进程内传递，这里把它拍平成简单字段，便于写数据库或通知。
 */
public final class CrawlSummaryBridge {

    private CrawlSummaryBridge() {
    }

    /** 简化的采集结果，供通知与页面展示 */
    public static class Summary {
        public String status = "UNKNOWN";
        public String message = "";
        public int candidateCount;
        public int excludedCount;
        public int matchedCount;
        public int savedCount;
        public int duplicateCount;
        public int failedCount;
        public int verifiedCount;
        public long requestCount;
        public long durationMs;
        /** 本次新入库的"可投"公告（列表可能封顶），"采集完成"通知用它列具体条目 */
        public java.util.List<com.wanhong.crawler.crawler.model.NewTender> newItems =
                new java.util.ArrayList<>();
        /** 新入库的"可投"公告总数（不封顶） */
        public int newItemCount;

        public static Summary from(CrawlSummary source) {
            Summary summary = new Summary();
            if (source == null) {
                return summary;
            }
            summary.status = source.status;
            summary.message = source.message;
            summary.candidateCount = source.candidateCount;
            summary.excludedCount = source.excludedCount;
            summary.matchedCount = source.matchedCount;
            summary.savedCount = source.savedCount;
            summary.duplicateCount = source.duplicateCount;
            summary.failedCount = source.failedCount;
            summary.verifiedCount = source.verifiedCount;
            summary.requestCount = source.requestCount;
            summary.durationMs = source.durationMs;
            summary.newItems = new java.util.ArrayList<>(source.newItems);
            summary.newItemCount = source.newItemCount;
            return summary;
        }
    }

    /** 同步执行一次采集（Worker 里调用） */
    public static Summary run(CrawlerHolder holder, String site, Integer maxKeywords,
                              Integer lookbackDays, String triggerType,
                              ProgressListener listener) {
        CrawlSummary result = holder.engine.crawl(site, maxKeywords, lookbackDays, triggerType, listener);
        // 顺手落一份快照：进程重启后「采集」页还能显示上次结果，而不是退回"尚未执行采集"。
        // 所有采集（手动 / 定时）都走这里，是唯一的收口点。
        holder.saveLastSummary(result);
        return Summary.from(result);
    }
}
