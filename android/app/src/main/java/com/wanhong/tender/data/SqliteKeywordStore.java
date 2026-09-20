package com.wanhong.tender.data;

import com.wanhong.crawler.filter.CrawlerKeyword;
import com.wanhong.crawler.filter.KeywordOverrideSource;
import com.wanhong.crawler.pipeline.KeywordStore;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 用户自定义关键词的 SQLite 实现（同时作为内核的 {@link KeywordOverrideSource}）。 */
public class SqliteKeywordStore implements KeywordStore, KeywordOverrideSource {

    private final TenderDbHelper helper;

    public SqliteKeywordStore(Context context) {
        this.helper = new TenderDbHelper(context);
    }

    @Override
    public List<CrawlerKeyword> loadOverrides() {
        return list();
    }

    @Override
    public List<CrawlerKeyword> list() {
        List<CrawlerKeyword> list = new ArrayList<>();
        Cursor cursor = helper.getReadableDatabase().query(TenderDbHelper.TABLE_KEYWORD, null,
                null, null, null, null, "scope ASC, id ASC");
        try {
            while (cursor.moveToNext()) {
                CrawlerKeyword keyword = new CrawlerKeyword();
                keyword.setId(cursor.getLong(cursor.getColumnIndexOrThrow("id")));
                keyword.setKeyword(cursor.getString(cursor.getColumnIndexOrThrow("keyword")));
                keyword.setDisplay(cursor.getString(cursor.getColumnIndexOrThrow("display")));
                keyword.setScope(parseScope(cursor.getString(cursor.getColumnIndexOrThrow("scope"))));
                keyword.setAction(parseAction(cursor.getString(cursor.getColumnIndexOrThrow("action"))));
                keyword.setNote(cursor.getString(cursor.getColumnIndexOrThrow("note")));
                list.add(keyword);
            }
        } finally {
            cursor.close();
        }
        return list;
    }

    @Override
    public void add(CrawlerKeyword keyword) {
        ContentValues values = new ContentValues();
        values.put("keyword", keyword.getKeyword());
        values.put("display", keyword.getDisplay());
        values.put("scope", keyword.getScope().name());
        values.put("action", keyword.getAction().name());
        values.put("note", keyword.getNote());
        values.put("create_time", LocalDateTime.now().toString());
        long id = helper.getWritableDatabase().insertWithOnConflict(TenderDbHelper.TABLE_KEYWORD,
                null, values, SQLiteDatabase.CONFLICT_REPLACE);
        keyword.setId(id);
    }

    @Override
    public void remove(long id) {
        helper.getWritableDatabase().delete(TenderDbHelper.TABLE_KEYWORD, "id=?",
                new String[]{String.valueOf(id)});
    }

    @Override
    public CrawlerKeyword findByKeywordAndScope(String keyword, CrawlerKeyword.Scope scope) {
        Cursor cursor = helper.getReadableDatabase().query(TenderDbHelper.TABLE_KEYWORD, null,
                "keyword=? AND scope=?", new String[]{keyword, scope.name()}, null, null, null);
        try {
            if (!cursor.moveToFirst()) {
                return null;
            }
            CrawlerKeyword result = new CrawlerKeyword();
            result.setId(cursor.getLong(cursor.getColumnIndexOrThrow("id")));
            result.setKeyword(cursor.getString(cursor.getColumnIndexOrThrow("keyword")));
            result.setDisplay(cursor.getString(cursor.getColumnIndexOrThrow("display")));
            result.setScope(parseScope(cursor.getString(cursor.getColumnIndexOrThrow("scope"))));
            result.setAction(parseAction(cursor.getString(cursor.getColumnIndexOrThrow("action"))));
            result.setNote(cursor.getString(cursor.getColumnIndexOrThrow("note")));
            return result;
        } finally {
            cursor.close();
        }
    }

    private static CrawlerKeyword.Scope parseScope(String value) {
        try {
            return CrawlerKeyword.Scope.valueOf(value);
        } catch (Exception e) {
            return CrawlerKeyword.Scope.TITLE;
        }
    }

    private static CrawlerKeyword.Action parseAction(String value) {
        try {
            return CrawlerKeyword.Action.valueOf(value);
        } catch (Exception e) {
            return CrawlerKeyword.Action.ADD;
        }
    }
}
