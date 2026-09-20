package com.wanhong.crawler.support;

import com.wanhong.crawler.pipeline.CrawlLogRecord;
import com.wanhong.crawler.pipeline.TenderQuery;
import com.wanhong.crawler.pipeline.TenderRecord;
import com.wanhong.crawler.pipeline.TenderRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版公告存储，供单元测试使用。
 *
 * <p>有了它，整条采集链路（发现 → 过滤 → 详情补全 → 去重入库）可以完全离线跑通，
 * 不需要网络也不需要数据库。
 */
public class InMemoryTenderRepository implements TenderRepository {

    private final Map<Long, TenderRecord> store = new HashMap<>();
    private final Map<Long, CrawlLogRecord> logs = new HashMap<>();
    private long sequence = 1;

    @Override
    public TenderRecord findById(long id) {
        return store.get(id);
    }

    @Override
    public TenderRecord findByUrlHash(String urlHash) {
        for (TenderRecord record : store.values()) {
            if (record.urlHash != null && record.urlHash.equals(urlHash)) {
                return record;
            }
        }
        return null;
    }

    @Override
    public TenderRecord insert(TenderRecord record) {
        record.id = sequence++;
        store.put(record.id, record);
        return record;
    }

    @Override
    public void update(TenderRecord record) {
        store.put(record.id, record);
    }

    @Override
    public long count() {
        return store.size();
    }

    @Override
    public long countBySite(String siteCode) {
        long count = 0;
        for (TenderRecord record : store.values()) {
            if (siteCode != null && siteCode.equals(record.siteCode)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public long countQuery(TenderQuery query) {
        return filter(query).size();
    }

    @Override
    public List<TenderRecord> query(TenderQuery query, int limit, int offset) {
        List<TenderRecord> matched = filter(query);
        if (query != null && query.sortByDeadline()) {
            Collections.sort(matched, new Comparator<TenderRecord>() {
                public int compare(TenderRecord a, TenderRecord b) {
                    LocalDateTime left = a.deadlineDate;
                    LocalDateTime right = b.deadlineDate;
                    if (left == null && right == null) {
                        return 0;
                    }
                    if (left == null) {
                        return 1;
                    }
                    if (right == null) {
                        return -1;
                    }
                    return left.compareTo(right);
                }
            });
        } else {
            Collections.sort(matched, new Comparator<TenderRecord>() {
                public int compare(TenderRecord a, TenderRecord b) {
                    if (a.publishDate == null && b.publishDate == null) {
                        return Long.compare(b.id, a.id);
                    }
                    if (a.publishDate == null) {
                        return 1;
                    }
                    if (b.publishDate == null) {
                        return -1;
                    }
                    int cmp = b.publishDate.compareTo(a.publishDate);
                    return cmp != 0 ? cmp : Long.compare(b.id, a.id);
                }
            });
        }
        int from = Math.max(0, offset);
        if (from >= matched.size()) {
            return new ArrayList<>();
        }
        int to = Math.min(matched.size(), from + Math.max(0, limit));
        return new ArrayList<>(matched.subList(from, to));
    }

    private List<TenderRecord> filter(TenderQuery query) {
        List<TenderRecord> matched = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (TenderRecord record : store.values()) {
            if (query == null) {
                matched.add(record);
                continue;
            }
            if (!query.includeRemoved && record.removed) {
                continue;
            }
            if (query.siteCode != null && !query.siteCode.isEmpty()
                    && !query.siteCode.equals(record.siteCode)) {
                continue;
            }
            if (query.region != null && !query.region.isEmpty()
                    && !query.region.equals(record.region)) {
                continue;
            }
            if (query.keyword != null && !query.keyword.isEmpty()) {
                String needle = query.keyword.toLowerCase();
                boolean hit = contains(record.title, needle)
                        || contains(record.matchedKeywords, needle)
                        || contains(record.region, needle);
                if (!hit) {
                    continue;
                }
            }
            if (query.fromTime != null && (record.publishDate == null
                    || record.publishDate.isBefore(query.fromTime))) {
                continue;
            }
            if (query.toTime != null && (record.publishDate == null
                    || record.publishDate.isAfter(query.toTime))) {
                continue;
            }
            if (query.onlyOpen && (record.deadlineDate == null || record.deadlineDate.isBefore(now))) {
                continue;
            }
            if (query.onlyNew && !record.isNew) {
                continue;
            }
            if (query.trackedOnly && !record.tracked) {
                continue;
            }
            matched.add(record);
        }
        return matched;
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }

    @Override
    public List<TenderRecord> findForVerification(LocalDateTime before, int limit) {
        List<TenderRecord> list = new ArrayList<>();
        for (TenderRecord record : store.values()) {
            if (record.removed) {
                continue;
            }
            if (record.verifiedAt == null || record.verifiedAt.isBefore(before)) {
                list.add(record);
                if (list.size() >= limit) {
                    break;
                }
            }
        }
        return list;
    }

    @Override
    public long countForVerification(LocalDateTime before) {
        return findForVerification(before, Integer.MAX_VALUE).size();
    }

    @Override
    public List<String> allMatchedKeywords() {
        List<String> list = new ArrayList<>();
        for (TenderRecord record : store.values()) {
            if (record.matchedKeywords != null) {
                list.add(record.matchedKeywords);
            }
        }
        return list;
    }

    @Override
    public int markAllRead() {
        int count = 0;
        for (TenderRecord record : store.values()) {
            if (record.isNew) {
                record.isNew = false;
                count++;
            }
        }
        return count;
    }

    @Override
    public boolean setTracked(long id, boolean tracked) {
        TenderRecord record = store.get(id);
        if (record == null) {
            return false;
        }
        record.tracked = tracked;
        return true;
    }

    @Override
    public void saveLog(CrawlLogRecord log) {
        log.id = sequence++;
        logs.put(log.id, log);
    }

    @Override
    public void updateLog(CrawlLogRecord log) {
        logs.put(log.id, log);
    }

    @Override
    public List<CrawlLogRecord> recentLogs(int limit) {
        List<CrawlLogRecord> list = new ArrayList<>(logs.values());
        Collections.sort(list, new Comparator<CrawlLogRecord>() {
            public int compare(CrawlLogRecord a, CrawlLogRecord b) {
                return Long.compare(b.id, a.id);
            }
        });
        return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }

    @Override
    public CrawlLogRecord findLog(long id) {
        return logs.get(id);
    }

    public List<TenderRecord> all() {
        return new ArrayList<>(store.values());
    }
}
