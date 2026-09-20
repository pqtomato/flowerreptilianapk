package com.wanhong.tender.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.wanhong.crawler.common.NoticeType;
import com.wanhong.crawler.config.CrawlerSettings;
import com.wanhong.crawler.crawler.BudgetExtractor;
import com.wanhong.crawler.crawler.DeadlineExtractor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * SQLite 建表。
 *
 * <p>时间统一存 ISO 字符串（LocalDateTime.toString()），既能被 DateTimeUtils 直接解析，
 * 也能按字典序排序（SQLite 里做时间范围查询就不必转换）。
 */
public class TenderDbHelper extends SQLiteOpenHelper {

    public static final String DB_NAME = "flower_tender.db";
    /**
     * v2：新增 notice_type（公告类型），用于默认过滤掉"成交 / 合同"这类非可投公告
     * v3：订正数据 —— 清掉非可投公告上已经存进去的"估算截止日"
     * v4：新增 budget_yuan / budget_cap_text / project_code（预算金额、最高限价原文、项目编号）
     * v5：订正数据 —— 重新解析金额相关字段（v4 的「最高限价」会吞掉后面紧邻的字段）
     * v6：订正数据 —— 重新解析截止时间（修正前「15点00分」的时分会被丢掉）
     */
    public static final int DB_VERSION = 6;

    public static final String TABLE_TENDER = "tender";
    public static final String TABLE_KEYWORD = "crawler_keyword";
    public static final String TABLE_LOG = "crawl_log";

    public TenderDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_TENDER + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "site_code TEXT,"
                + "site_name TEXT,"
                + "title TEXT NOT NULL,"
                + "url TEXT NOT NULL,"
                + "url_hash TEXT NOT NULL UNIQUE,"
                + "access_url TEXT,"
                + "category TEXT,"
                + "category_code TEXT,"
                + "region TEXT,"
                + "publish_date TEXT,"
                + "deadline_date TEXT,"
                + "deadline_estimated INTEGER NOT NULL DEFAULT 0,"
                + "deadline_source TEXT,"
                + "matched_keywords TEXT,"
                + "matched_scope TEXT,"
                + "source_keyword TEXT,"
                + "summary TEXT,"
                + "content TEXT,"
                + "content_html TEXT,"
                + "attachments TEXT,"
                + "also_found_on TEXT,"
                + "notice_type TEXT,"
                + "budget_yuan INTEGER,"
                + "budget_cap_text TEXT,"
                + "project_code TEXT,"
                + "is_new INTEGER NOT NULL DEFAULT 1,"
                + "tracked INTEGER NOT NULL DEFAULT 0,"
                + "removed INTEGER NOT NULL DEFAULT 0,"
                + "verified_at TEXT,"
                + "link_status TEXT,"
                + "crawl_time TEXT,"
                + "create_time TEXT)");

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tender_site ON " + TABLE_TENDER + "(site_code)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tender_publish ON " + TABLE_TENDER + "(publish_date)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tender_deadline ON " + TABLE_TENDER + "(deadline_date)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tender_region ON " + TABLE_TENDER + "(region)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tender_new ON " + TABLE_TENDER + "(is_new)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tender_notice_type ON " + TABLE_TENDER + "(notice_type)");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_KEYWORD + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "keyword TEXT NOT NULL,"
                + "display TEXT,"
                + "scope TEXT NOT NULL,"
                + "action TEXT NOT NULL,"
                + "note TEXT,"
                + "create_time TEXT,"
                + "UNIQUE(keyword, scope))");

        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_LOG + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "site_code TEXT,"
                + "trigger_type TEXT,"
                + "status TEXT,"
                + "start_time TEXT,"
                + "end_time TEXT,"
                + "duration_ms INTEGER DEFAULT 0,"
                + "candidate_count INTEGER DEFAULT 0,"
                + "excluded_count INTEGER DEFAULT 0,"
                + "matched_count INTEGER DEFAULT 0,"
                + "saved_count INTEGER DEFAULT 0,"
                + "duplicate_count INTEGER DEFAULT 0,"
                + "failed_count INTEGER DEFAULT 0,"
                + "request_count INTEGER DEFAULT 0,"
                + "verified_count INTEGER DEFAULT 0,"
                + "detail TEXT,"
                + "error_message TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_log_start ON " + TABLE_LOG + "(start_time)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_TENDER + " ADD COLUMN notice_type TEXT");
            backfillNoticeType(db);
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_tender_notice_type ON "
                    + TABLE_TENDER + "(notice_type)");
        }
        if (oldVersion < 3) {
            clearEstimatedDeadlineForNonBiddable(db);
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE " + TABLE_TENDER + " ADD COLUMN budget_yuan INTEGER");
            db.execSQL("ALTER TABLE " + TABLE_TENDER + " ADD COLUMN budget_cap_text TEXT");
            db.execSQL("ALTER TABLE " + TABLE_TENDER + " ADD COLUMN project_code TEXT");
            backfillBudget(db);
        }
        if (oldVersion < 5) {
            // v4 的「最高限价」取值会吞掉紧邻的下一个字段
            //（实测存成了 "574099.03元采购需求"）。清空后按新正则重解析。
            // 爬虫那条自愈路径只在字段为空时才跑，所以必须在这里主动订正。
            db.execSQL("UPDATE " + TABLE_TENDER
                    + " SET budget_yuan=NULL, budget_cap_text=NULL, project_code=NULL");
            backfillBudget(db);
        }
        if (oldVersion < 6) {
            reparseParsedDeadlines(db);
        }
    }

    /** {@code DeadlineExtractor} 解析成功时写进 deadline_source 的值。 */
    private static final String DEADLINE_SOURCE_PARSED = "正文解析";

    /**
     * v5 → v6 数据订正：重新解析"正文解析"出来的截止时间。
     *
     * <p>修正前 {@code 15点00分} 这类写法里的时分会被整段丢掉、只剩 00:00 ——
     * 于是截止当天上午就被判成"已截止"，而实际还能投到下午三点。
     *
     * <p>只重解析 source 为「正文解析」的：估算出来的日期不受这次修正影响。
     * <b>保守更新</b>：只有重新解析成功才覆盖，解析不出来就保留原值，
     * 免得"订正"反而把某些记录的截止时间弄丢。
     */
    private void reparseParsedDeadlines(SQLiteDatabase db) {
        DeadlineExtractor extractor = new DeadlineExtractor(new CrawlerSettings());
        Map<Long, LocalDateTime> reparsed = new HashMap<>();
        Cursor cursor = db.rawQuery("SELECT id, content, summary, publish_date FROM " + TABLE_TENDER
                + " WHERE deadline_source='" + DEADLINE_SOURCE_PARSED + "'", null);
        try {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String content = cursor.getString(1);
                String summary = cursor.getString(2);
                LocalDateTime publishDate = parseIso(cursor.getString(3));
                String text = (content != null && !content.trim().isEmpty()) ? content : summary;
                // allowEstimate=false：原文里本来就有日期，重解析不该把它变成"估算值"
                DeadlineExtractor.Deadline deadline = extractor.resolve(text, publishDate, false);
                if (deadline.date() != null) {
                    reparsed.put(id, deadline.date());
                }
            }
        } finally {
            cursor.close();
        }
        for (Map.Entry<Long, LocalDateTime> entry : reparsed.entrySet()) {
            ContentValues values = new ContentValues();
            values.put("deadline_date", entry.getValue().toString());
            db.update(TABLE_TENDER, values, "id=?", new String[]{String.valueOf(entry.getKey())});
        }
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

    /**
     * v3 → v4 迁移：从已经入库的正文里补出「预算金额 / 最高限价 / 项目编号」。
     *
     * <p>不回填的话老数据全是 NULL，列表上既看不到金额、按预算排序也排不出来 ——
     * 这次改动对已有数据等于没生效。正文都在库里，重跑一遍正则即可，不用重新采集。
     */
    private void backfillBudget(SQLiteDatabase db) {
        BudgetExtractor extractor = new BudgetExtractor();
        Map<Long, BudgetExtractor.Budget> parsed = new HashMap<>();
        Cursor cursor = db.rawQuery(
                "SELECT id, content, summary FROM " + TABLE_TENDER, null);
        try {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String content = cursor.getString(1);
                String summary = cursor.getString(2);
                // 与爬虫同一口径：优先正文，没有正文才退回摘要
                String text = (content != null && !content.trim().isEmpty()) ? content : summary;
                BudgetExtractor.Budget budget = extractor.extract(text);
                if (budget.amountYuan() != null || budget.projectCode() != null || budget.capText() != null) {
                    parsed.put(id, budget);
                }
            }
        } finally {
            cursor.close();
        }
        for (Map.Entry<Long, BudgetExtractor.Budget> entry : parsed.entrySet()) {
            BudgetExtractor.Budget budget = entry.getValue();
            ContentValues values = new ContentValues();
            if (budget.amountYuan() != null) {
                values.put("budget_yuan", budget.amountYuan());
            }
            if (budget.capText() != null) {
                values.put("budget_cap_text", budget.capText());
            }
            if (budget.projectCode() != null) {
                values.put("project_code", budget.projectCode());
            }
            if (values.size() > 0) {
                db.update(TABLE_TENDER, values, "id=?", new String[]{String.valueOf(entry.getKey())});
            }
        }
    }

    /**
     * v2 → v3 数据订正：把非可投公告（成交 / 中标 / 合同 / 终止）上"估算出来的截止日"清掉。
     *
     * <p>这些公告本来就没有报名截止时间，之前被按「发布日 + N 天」估出了一个未来的日期，
     * 于是"已定标"的项目顶着「还剩 48 天」混进未截止列表，甚至排在按截止排序的最前面。
     * 光加类型过滤还不够 —— 老数据里这些假日期已经落库了，而爬虫只有在
     * {@code deadlineDate == null} 时才会重算，永远轮不到它们。
     *
     * <p>只清"估算"的：正文里真解析出日期的（estimated=0）保留。
     */
    private void clearEstimatedDeadlineForNonBiddable(SQLiteDatabase db) {
        db.execSQL("UPDATE " + TABLE_TENDER
                + " SET deadline_date=NULL, deadline_estimated=0, deadline_source=''"
                + " WHERE deadline_estimated=1"
                + " AND notice_type IS NOT NULL AND notice_type<>'" + NoticeType.BID + "'");
    }

    /**
     * v1 → v2 迁移：给已经入库的公告补上类型。
     *
     * <p>不补的话老数据 noticeType 全是 NULL，会被当成"可投"照旧显示 ——
     * 等于这次过滤对老用户完全没生效。
     */
    private void backfillNoticeType(SQLiteDatabase db) {
        Map<Long, String> types = new HashMap<>();
        Cursor cursor = db.rawQuery(
                "SELECT id, category, title FROM " + TABLE_TENDER, null);
        try {
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String category = cursor.getString(1);
                String title = cursor.getString(2);
                types.put(id, NoticeType.classify(category, title));
            }
        } finally {
            cursor.close();
        }
        for (Map.Entry<Long, String> entry : types.entrySet()) {
            ContentValues values = new ContentValues();
            values.put("notice_type", entry.getValue());
            db.update(TABLE_TENDER, values, "id=?", new String[]{String.valueOf(entry.getKey())});
        }
    }
}
