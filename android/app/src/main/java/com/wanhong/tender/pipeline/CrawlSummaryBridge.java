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
            return summary;
        }
    }

    /** 同步执行一次采集（Worker 里调用） */
    public static Summary run(CrawlerHolder holder, String site, Integer maxKeywords,
                              Integer lookbackDays, String triggerType,
                              ProgressListener listener) {
        CrawlSummary result = holder.engine.crawl(site, maxKeywords, lookbackDays, triggerType, listener);
        return Summary.from(result);
    }
}
