package com.wanhong.crawler.pipeline;

import java.time.LocalDateTime;

/** 一次采集任务的执行记录。 */
public class CrawlLogRecord {

    public long id;
    public String siteCode;
    /** MANUAL / SCHEDULED */
    public String triggerType;
    /** RUNNING / SUCCESS / FAILED */
    public String status;
    public LocalDateTime startTime;
    public LocalDateTime endTime;
    public long durationMs;
    public int candidateCount;
    public int excludedCount;
    public int matchedCount;
    public int savedCount;
    public int duplicateCount;
    public int failedCount;
    public int requestCount;
    public int verifiedCount;
    public String detail;
    public String errorMessage;
}
