package com.wanhong.crawler.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 一次采集任务的执行记录。
 */
@Entity
@Table(name = "crawl_log")
public class CrawlLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 站点编码，全量采集时为 ALL */
    @Column(name = "site_code", length = 32)
    private String siteCode;

    /** MANUAL / SCHEDULED */
    @Column(name = "trigger_type", length = 32)
    private String triggerType;

    /** RUNNING / SUCCESS / FAILED */
    @Column(name = "status", length = 32)
    private String status;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "duration_ms")
    private Long durationMs;

    /** 候选公告数 */
    @Column(name = "candidate_count")
    private Integer candidateCount = 0;

    /** 命中关键词数 */
    @Column(name = "matched_count")
    private Integer matchedCount = 0;

    /** 新入库数 */
    @Column(name = "saved_count")
    private Integer savedCount = 0;

    /** 已存在跳过数 */
    @Column(name = "duplicate_count")
    private Integer duplicateCount = 0;

    /** 失败数 */
    @Column(name = "failed_count")
    private Integer failedCount = 0;

    /** HTTP 请求次数 */
    @Column(name = "request_count")
    private Integer requestCount = 0;

    @Lob
    @Column(name = "detail")
    private String detail;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSiteCode() {
        return siteCode;
    }

    public void setSiteCode(String siteCode) {
        this.siteCode = siteCode;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Integer getCandidateCount() {
        return candidateCount;
    }

    public void setCandidateCount(Integer candidateCount) {
        this.candidateCount = candidateCount;
    }

    public Integer getMatchedCount() {
        return matchedCount;
    }

    public void setMatchedCount(Integer matchedCount) {
        this.matchedCount = matchedCount;
    }

    public Integer getSavedCount() {
        return savedCount;
    }

    public void setSavedCount(Integer savedCount) {
        this.savedCount = savedCount;
    }

    public Integer getDuplicateCount() {
        return duplicateCount;
    }

    public void setDuplicateCount(Integer duplicateCount) {
        this.duplicateCount = duplicateCount;
    }

    public Integer getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(Integer failedCount) {
        this.failedCount = failedCount;
    }

    public Integer getRequestCount() {
        return requestCount;
    }

    public void setRequestCount(Integer requestCount) {
        this.requestCount = requestCount;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
