package com.wanhong.tender.data;

import com.wanhong.crawler.pipeline.CrawlLogRecord;
import com.wanhong.crawler.pipeline.TenderQuery;
import com.wanhong.crawler.pipeline.TenderRecord;
import com.wanhong.crawler.pipeline.TenderRepository;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** {@link TenderRepository} 的 SQLite 实现。 */
public class SqliteTenderRepository implements TenderRepository {

    private final TenderDbHelper helper;

    public SqliteTenderRepository(Context context) {
        this.helper = new TenderDbHelper(context);
    }

    // ---------------------------------------------------------------- 增删改查

    @Override
    public TenderRecord findById(long id) {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor cursor = db.query(TenderDbHelper.TABLE_TENDER, null, "id=?",
                new String[]{String.valueOf(id)}, null, null, null);
        try {
            return cursor.moveToFirst() ? read(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    @Override
    public TenderRecord findByUrlHash(String urlHash) {
        if (urlHash == null) {
            return null;
        }
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor cursor = db.query(TenderDbHelper.TABLE_TENDER, null, "url_hash=?",
                new String[]{urlHash}, null, null, null);
        try {
            return cursor.moveToFirst() ? read(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    @Override
    public TenderRecord insert(TenderRecord record) {
        SQLiteDatabase db = helper.getWritableDatabase();
        long id = db.insertWithOnConflict(TenderDbHelper.TABLE_TENDER, null, write(record),
                SQLiteDatabase.CONFLICT_IGNORE);
        record.id = id;
        return record;
    }

    @Override
    public void update(TenderRecord record) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.update(TenderDbHelper.TABLE_TENDER, write(record), "id=?",
                new String[]{String.valueOf(record.id)});
    }

    @Override
    public long count() {
        return scalar("SELECT COUNT(*) FROM " + TenderDbHelper.TABLE_TENDER);
    }

    @Override
    public long countBySite(String siteCode) {
        return scalar("SELECT COUNT(*) FROM " + TenderDbHelper.TABLE_TENDER + " WHERE site_code=?",
                new String[]{siteCode});
    }

    @Override
    public long countQuery(TenderQuery query) {
        Where where = buildWhere(query);
        return scalar("SELECT COUNT(*) FROM " + TenderDbHelper.TABLE_TENDER + where.sql, where.args);
    }

    @Override
    public List<TenderRecord> query(TenderQuery query, int limit, int offset) {
        Where where = buildWhere(query);
        List<String> args = new ArrayList<>();
        if (where.args != null) {
            for (String arg : where.args) {
                args.add(arg);
            }
        }
        String order;
        if (query != null && query.sortByBudget()) {
            // 预算从高到低：没有金额的排最后。
            // 用 (budget_yuan IS NULL) 当第一排序键而不是 NULLS LAST ——
            // 后者要 SQLite 3.30+，不同 ROM 带的版本不一致。
            order = " ORDER BY (budget_yuan IS NULL), budget_yuan DESC, publish_date DESC";
        } else if (query != null && query.sortByDeadline()) {
            // 未截止的按截止升序在前（最紧急的先看到），已截止的整档垫后；
            // 同一个"已截止不该打头"的口径，与通知里的排序保持一致。
            // 时间用 ? 占位而不是 SQLite 的 datetime('now')：库里存的是 ISO 串（带 T，
            // 还有秒的小数位），和 datetime 的格式不能直接比字典序。
            String now = iso(LocalDateTime.now());
            order = " ORDER BY (deadline_date IS NULL), (deadline_date < ?) ASC,"
                    + " CASE WHEN deadline_date >= ? THEN deadline_date END ASC,"
                    + " deadline_date DESC, publish_date DESC";
            args.add(now);
            args.add(now);
        } else {
            order = " ORDER BY publish_date DESC, id DESC";
        }
        String sql = "SELECT * FROM " + TenderDbHelper.TABLE_TENDER + where.sql + order
                + " LIMIT " + Math.max(0, limit) + " OFFSET " + Math.max(0, offset);
        return queryList(sql, args.toArray(new String[0]));
    }

    @Override
    public List<TenderRecord> findForVerification(LocalDateTime before, int limit) {
        String sql = "SELECT * FROM " + TenderDbHelper.TABLE_TENDER
                + " WHERE removed=0 AND (verified_at IS NULL OR verified_at < ?)"
                + " ORDER BY (verified_at IS NOT NULL), verified_at ASC, id ASC LIMIT " + Math.max(1, limit);
        return queryList(sql, new String[]{iso(before)});
    }

    @Override
    public long countForVerification(LocalDateTime before) {
        return scalar("SELECT COUNT(*) FROM " + TenderDbHelper.TABLE_TENDER
                + " WHERE removed=0 AND (verified_at IS NULL OR verified_at < ?)",
                new String[]{iso(before)});
    }

    @Override
    public List<String> allMatchedKeywords() {
        List<String> list = new ArrayList<>();
        Cursor cursor = helper.getReadableDatabase().rawQuery(
                "SELECT matched_keywords FROM " + TenderDbHelper.TABLE_TENDER
                        + " WHERE matched_keywords IS NOT NULL AND matched_keywords != ''", null);
        try {
            while (cursor.moveToNext()) {
                list.add(cursor.getString(0));
            }
        } finally {
            cursor.close();
        }
        return list;
    }

    @Override
    public int markAllRead() {
        ContentValues values = new ContentValues();
        values.put("is_new", 0);
        return helper.getWritableDatabase().update(TenderDbHelper.TABLE_TENDER, values, "is_new=1", null);
    }

    @Override
    public boolean setTracked(long id, boolean tracked) {
        ContentValues values = new ContentValues();
        values.put("tracked", tracked ? 1 : 0);
        int rows = helper.getWritableDatabase().update(TenderDbHelper.TABLE_TENDER, values, "id=?",
                new String[]{String.valueOf(id)});
        return rows > 0;
    }

    // ---------------------------------------------------------------- 采集日志

    @Override
    public void saveLog(CrawlLogRecord log) {
        long id = helper.getWritableDatabase().insert(TenderDbHelper.TABLE_LOG, null, writeLog(log));
        log.id = id;
    }

    @Override
    public void updateLog(CrawlLogRecord log) {
        helper.getWritableDatabase().update(TenderDbHelper.TABLE_LOG, writeLog(log), "id=?",
                new String[]{String.valueOf(log.id)});
    }

    @Override
    public List<CrawlLogRecord> recentLogs(int limit) {
        List<CrawlLogRecord> list = new ArrayList<>();
        Cursor cursor = helper.getReadableDatabase().query(TenderDbHelper.TABLE_LOG, null, null, null,
                null, null, "id DESC", String.valueOf(Math.max(1, limit)));
        try {
            while (cursor.moveToNext()) {
                list.add(readLog(cursor));
            }
        } finally {
            cursor.close();
        }
        return list;
    }

    @Override
    public CrawlLogRecord findLog(long id) {
        Cursor cursor = helper.getReadableDatabase().query(TenderDbHelper.TABLE_LOG, null, "id=?",
                new String[]{String.valueOf(id)}, null, null, null);
        try {
            return cursor.moveToFirst() ? readLog(cursor) : null;
        } finally {
            cursor.close();
        }
    }

    // ---------------------------------------------------------------- 内部工具

    private static class Where {
        String sql = "";
        String[] args = new String[0];
    }

    /** 构造查询条件。所有值都走参数绑定，避免拼接 SQL。 */
    private Where buildWhere(TenderQuery query) {
        StringBuilder sb = new StringBuilder();
        List<String> args = new ArrayList<>();
        if (query == null) {
            return new Where();
        }
        sb.append(" WHERE 1=1");
        if (!query.includeRemoved) {
            sb.append(" AND removed=0");
        }
        if (notBlank(query.siteCode)) {
            sb.append(" AND site_code=?");
            args.add(query.siteCode);
        }
        if (notBlank(query.region)) {
            sb.append(" AND region=?");
            args.add(query.region);
        }
        if (notBlank(query.keyword)) {
            sb.append(" AND (title LIKE ? OR matched_keywords LIKE ? OR region LIKE ?)");
            String like = "%" + query.keyword + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (query.fromTime != null) {
            sb.append(" AND publish_date >= ?");
            args.add(iso(query.fromTime));
        }
        if (query.toTime != null) {
            sb.append(" AND publish_date <= ?");
            args.add(iso(query.toTime));
        }
        if (query.onlyOpen) {
            sb.append(" AND deadline_date IS NOT NULL AND deadline_date >= ?");
            args.add(iso(LocalDateTime.now()));
        }
        if (query.onlyNew) {
            sb.append(" AND is_new=1");
        }
        if (query.trackedOnly) {
            sb.append(" AND tracked=1");
        }
        if (query.onlyBiddable) {
            // 空值按"可投"处理：老数据迁移前或类型认不出来时，宁可多显示也不漏机会
            sb.append(" AND (notice_type IS NULL OR notice_type='' OR notice_type='bid')");
        }
        Where where = new Where();
        where.sql = sb.toString();
        where.args = args.toArray(new String[0]);
        return where;
    }

    private List<TenderRecord> queryList(String sql, String[] args) {
        List<TenderRecord> list = new ArrayList<>();
        Cursor cursor = helper.getReadableDatabase().rawQuery(sql, args);
        try {
            while (cursor.moveToNext()) {
                list.add(read(cursor));
            }
        } finally {
            cursor.close();
        }
        return list;
    }

    private long scalar(String sql, String... args) {
        Cursor cursor = helper.getReadableDatabase().rawQuery(sql, args);
        try {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        } finally {
            cursor.close();
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String iso(LocalDateTime time) {
        return time == null ? null : time.toString();
    }

    private static LocalDateTime parseIso(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private ContentValues write(TenderRecord r) {
        ContentValues v = new ContentValues();
        v.put("site_code", r.siteCode);
        v.put("site_name", r.siteName);
        v.put("title", r.title);
        v.put("url", r.url);
        v.put("url_hash", r.urlHash);
        v.put("access_url", r.accessUrl);
        v.put("category", r.category);
        v.put("category_code", r.categoryCode);
        v.put("region", r.region);
        v.put("publish_date", iso(r.publishDate));
        v.put("deadline_date", iso(r.deadlineDate));
        v.put("deadline_estimated", r.deadlineEstimated ? 1 : 0);
        v.put("deadline_source", r.deadlineSource);
        v.put("matched_keywords", r.matchedKeywords);
        v.put("matched_scope", r.matchedScope);
        v.put("source_keyword", r.sourceKeyword);
        v.put("summary", r.summary);
        v.put("content", r.content);
        v.put("content_html", r.contentHtml);
        v.put("attachments", r.attachments);
        v.put("also_found_on", r.alsoFoundOn);
        // 类型兜底：万一调用方没设，落库前补算一次，避免出现 NULL 被当成"可投"的漏网数据
        v.put("notice_type", r.noticeType != null && !r.noticeType.isEmpty()
                ? r.noticeType
                : com.wanhong.crawler.common.NoticeType.classify(r.category, r.title));
        v.put("budget_yuan", r.budgetYuan);
        v.put("budget_cap_text", r.budgetCapText);
        v.put("project_code", r.projectCode);
        v.put("is_new", r.isNew ? 1 : 0);
        v.put("tracked", r.tracked ? 1 : 0);
        v.put("removed", r.removed ? 1 : 0);
        v.put("verified_at", iso(r.verifiedAt));
        v.put("link_status", r.linkStatus);
        v.put("crawl_time", iso(r.crawlTime));
        v.put("create_time", iso(r.createTime));
        return v;
    }

    private TenderRecord read(Cursor c) {
        TenderRecord r = new TenderRecord();
        r.id = c.getLong(c.getColumnIndexOrThrow("id"));
        r.siteCode = c.getString(c.getColumnIndexOrThrow("site_code"));
        r.siteName = c.getString(c.getColumnIndexOrThrow("site_name"));
        r.title = c.getString(c.getColumnIndexOrThrow("title"));
        r.url = c.getString(c.getColumnIndexOrThrow("url"));
        r.urlHash = c.getString(c.getColumnIndexOrThrow("url_hash"));
        r.accessUrl = c.getString(c.getColumnIndexOrThrow("access_url"));
        r.category = c.getString(c.getColumnIndexOrThrow("category"));
        r.categoryCode = c.getString(c.getColumnIndexOrThrow("category_code"));
        r.region = c.getString(c.getColumnIndexOrThrow("region"));
        r.publishDate = parseIso(c.getString(c.getColumnIndexOrThrow("publish_date")));
        r.deadlineDate = parseIso(c.getString(c.getColumnIndexOrThrow("deadline_date")));
        r.deadlineEstimated = c.getInt(c.getColumnIndexOrThrow("deadline_estimated")) == 1;
        r.deadlineSource = c.getString(c.getColumnIndexOrThrow("deadline_source"));
        r.matchedKeywords = c.getString(c.getColumnIndexOrThrow("matched_keywords"));
        r.matchedScope = c.getString(c.getColumnIndexOrThrow("matched_scope"));
        r.sourceKeyword = c.getString(c.getColumnIndexOrThrow("source_keyword"));
        r.summary = c.getString(c.getColumnIndexOrThrow("summary"));
        r.content = c.getString(c.getColumnIndexOrThrow("content"));
        r.contentHtml = c.getString(c.getColumnIndexOrThrow("content_html"));
        r.attachments = c.getString(c.getColumnIndexOrThrow("attachments"));
        r.alsoFoundOn = c.getString(c.getColumnIndexOrThrow("also_found_on"));
        r.noticeType = c.getString(c.getColumnIndexOrThrow("notice_type"));
        int budgetIndex = c.getColumnIndex("budget_yuan");
        r.budgetYuan = (budgetIndex < 0 || c.isNull(budgetIndex)) ? null : c.getLong(budgetIndex);
        r.budgetCapText = c.getString(c.getColumnIndexOrThrow("budget_cap_text"));
        r.projectCode = c.getString(c.getColumnIndexOrThrow("project_code"));
        r.isNew = c.getInt(c.getColumnIndexOrThrow("is_new")) == 1;
        r.tracked = c.getInt(c.getColumnIndexOrThrow("tracked")) == 1;
        r.removed = c.getInt(c.getColumnIndexOrThrow("removed")) == 1;
        r.verifiedAt = parseIso(c.getString(c.getColumnIndexOrThrow("verified_at")));
        r.linkStatus = c.getString(c.getColumnIndexOrThrow("link_status"));
        r.crawlTime = parseIso(c.getString(c.getColumnIndexOrThrow("crawl_time")));
        r.createTime = parseIso(c.getString(c.getColumnIndexOrThrow("create_time")));
        return r;
    }

    private ContentValues writeLog(CrawlLogRecord log) {
        ContentValues v = new ContentValues();
        v.put("site_code", log.siteCode);
        v.put("trigger_type", log.triggerType);
        v.put("status", log.status);
        v.put("start_time", iso(log.startTime));
        v.put("end_time", iso(log.endTime));
        v.put("duration_ms", log.durationMs);
        v.put("candidate_count", log.candidateCount);
        v.put("excluded_count", log.excludedCount);
        v.put("matched_count", log.matchedCount);
        v.put("saved_count", log.savedCount);
        v.put("duplicate_count", log.duplicateCount);
        v.put("failed_count", log.failedCount);
        v.put("request_count", log.requestCount);
        v.put("verified_count", log.verifiedCount);
        v.put("detail", log.detail);
        v.put("error_message", log.errorMessage);
        return v;
    }

    private CrawlLogRecord readLog(Cursor c) {
        CrawlLogRecord log = new CrawlLogRecord();
        log.id = c.getLong(c.getColumnIndexOrThrow("id"));
        log.siteCode = c.getString(c.getColumnIndexOrThrow("site_code"));
        log.triggerType = c.getString(c.getColumnIndexOrThrow("trigger_type"));
        log.status = c.getString(c.getColumnIndexOrThrow("status"));
        log.startTime = parseIso(c.getString(c.getColumnIndexOrThrow("start_time")));
        log.endTime = parseIso(c.getString(c.getColumnIndexOrThrow("end_time")));
        log.durationMs = c.getLong(c.getColumnIndexOrThrow("duration_ms"));
        log.candidateCount = c.getInt(c.getColumnIndexOrThrow("candidate_count"));
        log.excludedCount = c.getInt(c.getColumnIndexOrThrow("excluded_count"));
        log.matchedCount = c.getInt(c.getColumnIndexOrThrow("matched_count"));
        log.savedCount = c.getInt(c.getColumnIndexOrThrow("saved_count"));
        log.duplicateCount = c.getInt(c.getColumnIndexOrThrow("duplicate_count"));
        log.failedCount = c.getInt(c.getColumnIndexOrThrow("failed_count"));
        log.requestCount = c.getInt(c.getColumnIndexOrThrow("request_count"));
        log.verifiedCount = c.getInt(c.getColumnIndexOrThrow("verified_count"));
        log.detail = c.getString(c.getColumnIndexOrThrow("detail"));
        log.errorMessage = c.getString(c.getColumnIndexOrThrow("error_message"));
        return log;
    }
}
