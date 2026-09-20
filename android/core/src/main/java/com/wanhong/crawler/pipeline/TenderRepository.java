package com.wanhong.crawler.pipeline;

import java.util.List;

/**
 * 公告存储端口。
 *
 * <p>把持久化抽象出来，内核就不依赖任何数据库实现：安卓上用 SQLite，
 * 单元测试里用内存实现（这样可以完全离线地跑通整条采集链路）。
 */
public interface TenderRepository {

    TenderRecord findById(long id);

    TenderRecord findByUrlHash(String urlHash);

    /** 新增，返回带 id 的记录 */
    TenderRecord insert(TenderRecord record);

    void update(TenderRecord record);

    long count();

    long countBySite(String siteCode);

    long countQuery(TenderQuery query);

    List<TenderRecord> query(TenderQuery query, int limit, int offset);

    /** 待校验（未下架 && 从未校验或超过跳过窗口）的记录 */
    List<TenderRecord> findForVerification(java.time.LocalDateTime before, int limit);

    long countForVerification(java.time.LocalDateTime before);

    /** 只取命中词列，用于统计每个关键词命中多少条 */
    List<String> allMatchedKeywords();

    /** 全部标记已读，返回更新条数 */
    int markAllRead();

    boolean setTracked(long id, boolean tracked);

    void saveLog(CrawlLogRecord log);

    void updateLog(CrawlLogRecord log);

    List<CrawlLogRecord> recentLogs(int limit);

    CrawlLogRecord findLog(long id);
}
