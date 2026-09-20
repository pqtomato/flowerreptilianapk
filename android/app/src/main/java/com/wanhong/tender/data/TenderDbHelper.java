package com.wanhong.tender.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * SQLite 建表。
 *
 * <p>时间统一存 ISO 字符串（LocalDateTime.toString()），既能被 DateTimeUtils 直接解析，
 * 也能按字典序排序（SQLite 里做时间范围查询就不必转换）。
 */
public class TenderDbHelper extends SQLiteOpenHelper {

    public static final String DB_NAME = "flower_tender.db";
    public static final int DB_VERSION = 1;

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
        // 目前只有 v1；后续加字段时在这里按版本号补 ALTER TABLE，避免用户数据丢失
    }
}
