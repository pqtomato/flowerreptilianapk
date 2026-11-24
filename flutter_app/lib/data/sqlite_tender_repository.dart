import 'package:sqflite/sqflite.dart';

import '../core/common/date_utils.dart';
import '../core/common/notice_type.dart';
import '../core/models.dart';
import '../core/repository.dart';
import 'database.dart';

/// [TenderRepository] 的 SQLite 实现（查询口径与安卓版逐条对齐）。
class SqliteTenderRepository implements TenderRepository {
  SqliteTenderRepository(this._database);

  final TenderDatabase _database;

  Future<Database> get _db => _database.database;

  // ---------------------------------------------------------------- 增删改查

  @override
  Future<TenderRecord?> findById(int id) async {
    final rows = await (await _db).query(
      TenderDatabase.tableTender,
      where: 'id=?',
      whereArgs: [id],
      limit: 1,
    );
    return rows.isEmpty ? null : _read(rows.first);
  }

  @override
  Future<TenderRecord?> findByUrlHash(String urlHash) async {
    if (urlHash.isEmpty) return null;
    final rows = await (await _db).query(
      TenderDatabase.tableTender,
      where: 'url_hash=?',
      whereArgs: [urlHash],
      limit: 1,
    );
    return rows.isEmpty ? null : _read(rows.first);
  }

  @override
  Future<TenderRecord> insert(TenderRecord record) async {
    final id = await (await _db).insert(
      TenderDatabase.tableTender,
      _write(record),
      conflictAlgorithm: ConflictAlgorithm.ignore,
    );
    record.id = id;
    return record;
  }

  @override
  Future<void> update(TenderRecord record) async {
    await (await _db).update(
      TenderDatabase.tableTender,
      _write(record),
      where: 'id=?',
      whereArgs: [record.id],
    );
  }

  @override
  Future<int> count() async {
    return _scalarInt('SELECT COUNT(*) FROM ${TenderDatabase.tableTender}');
  }

  @override
  Future<int> countBySite(String siteCode) async {
    return _scalarInt(
      'SELECT COUNT(*) FROM ${TenderDatabase.tableTender} WHERE site_code=?',
      [siteCode],
    );
  }

  @override
  Future<int> countQuery(TenderQuery query) async {
    final where = _buildWhere(query);
    return _scalarInt(
      'SELECT COUNT(*) FROM ${TenderDatabase.tableTender}${where.sql}',
      where.args,
    );
  }

  @override
  Future<List<TenderRecord>> query(
    TenderQuery query,
    int limit,
    int offset,
  ) async {
    final where = _buildWhere(query);
    final args = <Object?>[...where.args];

    String order;
    if (query.sortByBudget) {
      // 预算从高到低：没有金额的排最后。
      // 用 (budget_yuan IS NULL) 当第一排序键而不是 NULLS LAST ——
      // 后者要 SQLite 3.30+，不同 ROM 带的版本不一致。
      order = ' ORDER BY (budget_yuan IS NULL), budget_yuan DESC, publish_date DESC';
    } else if (query.sortByDeadline) {
      // 未截止的按截止升序在前（最紧急的先看到），已截止的整档垫后。
      // 时间用占位符而不是 datetime('now')：库里存的是 ISO 串，格式不同不能直接比。
      final now = DateUtils.iso(DateUtils.nowCn());
      order =
          ' ORDER BY (deadline_date IS NULL), (deadline_date < ?) ASC,'
          ' CASE WHEN deadline_date >= ? THEN deadline_date END ASC,'
          ' deadline_date DESC, publish_date DESC';
      args.add(now);
      args.add(now);
    } else {
      order = ' ORDER BY publish_date DESC, id DESC';
    }

    final sql =
        'SELECT * FROM ${TenderDatabase.tableTender}${where.sql}$order'
        ' LIMIT ${limit < 0 ? 0 : limit} OFFSET ${offset < 0 ? 0 : offset}';
    final rows = await (await _db).rawQuery(sql, args);
    return rows.map(_read).toList();
  }

  @override
  Future<List<TenderRecord>> findForVerification(
    DateTime before,
    int limit,
  ) async {
    final sql =
        'SELECT * FROM ${TenderDatabase.tableTender}'
        ' WHERE removed=0 AND (verified_at IS NULL OR verified_at < ?)'
        ' ORDER BY (verified_at IS NOT NULL), verified_at ASC, id ASC'
        ' LIMIT ${limit < 1 ? 1 : limit}';
    final rows = await (await _db).rawQuery(sql, [_iso(before)]);
    return rows.map(_read).toList();
  }

  @override
  Future<int> countForVerification(DateTime before) async {
    return _scalarInt(
      'SELECT COUNT(*) FROM ${TenderDatabase.tableTender}'
      ' WHERE removed=0 AND (verified_at IS NULL OR verified_at < ?)',
      [_iso(before)],
    );
  }

  @override
  Future<List<String>> allMatchedKeywords() async {
    final rows = await (await _db).query(
      TenderDatabase.tableTender,
      columns: ['matched_keywords'],
      where: "matched_keywords IS NOT NULL AND matched_keywords != ''",
    );
    return rows.map((row) => row['matched_keywords'] as String? ?? '').toList();
  }

  @override
  Future<int> markAllRead() async {
    return (await _db).update(TenderDatabase.tableTender, {
      'is_new': 0,
    }, where: 'is_new=1');
  }

  @override
  Future<bool> setTracked(int id, bool tracked) async {
    final rows = await (await _db).update(
      TenderDatabase.tableTender,
      {'tracked': tracked ? 1 : 0},
      where: 'id=?',
      whereArgs: [id],
    );
    return rows > 0;
  }

  @override
  Future<int> countPurgeable(
    DateTime deadlineBefore, {
    bool keepTracked = true,
  }) async {
    final where =
        'deadline_date IS NOT NULL AND deadline_date < ?'
        '${keepTracked ? ' AND tracked=0' : ''}';
    return _scalarInt(
      'SELECT COUNT(*) FROM ${TenderDatabase.tableTender} WHERE $where',
      [_iso(deadlineBefore)],
    );
  }

  @override
  Future<int> purgeExpired(
    DateTime deadlineBefore, {
    bool keepTracked = true,
  }) async {
    final where =
        'deadline_date IS NOT NULL AND deadline_date < ?'
        '${keepTracked ? ' AND tracked=0' : ''}';
    final db = await _db;
    final deleted = await db.delete(
      TenderDatabase.tableTender,
      where: where,
      whereArgs: [_iso(deadlineBefore)],
    );
    if (deleted > 0) {
      // 删完不 VACUUM 的话文件大小不会变（SQLite 只是把页标成空闲），
      // 而用户点「清理」最直观的期待就是「占用变小」。
      try {
        await db.execute('VACUUM');
      } catch (_) {
        // VACUUM 失败不影响删除结果，忽略
      }
    }
    return deleted;
  }

  // ---------------------------------------------------------------- 采集日志

  @override
  Future<void> saveLog(CrawlLogRecord log) async {
    final id = await (await _db).insert(
      TenderDatabase.tableLog,
      _writeLog(log),
    );
    log.id = id;
  }

  @override
  Future<void> updateLog(CrawlLogRecord log) async {
    await (await _db).update(
      TenderDatabase.tableLog,
      _writeLog(log),
      where: 'id=?',
      whereArgs: [log.id],
    );
  }

  @override
  Future<List<CrawlLogRecord>> recentLogs(int limit) async {
    final rows = await (await _db).query(
      TenderDatabase.tableLog,
      orderBy: 'id DESC',
      limit: limit < 1 ? 1 : limit,
    );
    return rows.map(_readLog).toList();
  }

  @override
  Future<CrawlLogRecord?> findLog(int id) async {
    final rows = await (await _db).query(
      TenderDatabase.tableLog,
      where: 'id=?',
      whereArgs: [id],
      limit: 1,
    );
    return rows.isEmpty ? null : _readLog(rows.first);
  }

  // ---------------------------------------------------------------- 内部工具

  Future<int> _scalarInt(String sql, [List<Object?>? args]) async {
    final rows = await (await _db).rawQuery(sql, args);
    if (rows.isEmpty) return 0;
    final value = rows.first.values.first;
    return value is int ? value : int.tryParse('$value') ?? 0;
  }

  /// 构造查询条件。所有值都走参数绑定，避免拼接 SQL。
  _Where _buildWhere(TenderQuery query) {
    final sb = StringBuffer();
    final args = <Object?>[];
    sb.write(' WHERE 1=1');
    if (!query.includeRemoved) {
      sb.write(' AND removed=0');
    }
    if (_notBlank(query.siteCode)) {
      sb.write(' AND site_code=?');
      args.add(query.siteCode);
    }
    if (_notBlank(query.region)) {
      sb.write(' AND region=?');
      args.add(query.region);
    }
    if (_notBlank(query.keyword)) {
      sb.write(
        ' AND (title LIKE ? OR matched_keywords LIKE ? OR region LIKE ?)',
      );
      final like = '%${query.keyword}%';
      args.add(like);
      args.add(like);
      args.add(like);
    }
    if (query.fromTime != null) {
      sb.write(' AND publish_date >= ?');
      args.add(_iso(query.fromTime));
    }
    if (query.toTime != null) {
      sb.write(' AND publish_date <= ?');
      args.add(_iso(query.toTime));
    }
    if (query.onlyOpen) {
      sb.write(' AND deadline_date IS NOT NULL AND deadline_date >= ?');
      args.add(DateUtils.iso(DateUtils.nowCn()));
    }
    if (query.onlyNew) {
      sb.write(' AND is_new=1');
    }
    if (query.trackedOnly) {
      sb.write(' AND tracked=1');
    }
    if (query.onlyBiddable) {
      // 空值按「可投」处理：老数据迁移前或类型认不出来时，宁可多显示也不漏机会
      sb.write(
        " AND (notice_type IS NULL OR notice_type='' OR notice_type='bid')",
      );
    }
    return _Where(sb.toString(), args);
  }

  static bool _notBlank(String? value) =>
      value != null && value.trim().isNotEmpty;

  /// 固定宽度 ISO 串；null 必须落成 SQL NULL（`deadline_date IS NOT NULL` 依赖它）。
  static String? _iso(DateTime? value) =>
      value == null ? null : DateUtils.iso(value);

  Map<String, Object?> _write(TenderRecord r) => {
    'site_code': r.siteCode,
    'site_name': r.siteName,
    'title': r.title,
    'url': r.url,
    'url_hash': r.urlHash,
    'access_url': r.accessUrl,
    'category': r.category,
    'category_code': r.categoryCode,
    'region': r.region,
    'publish_date': _iso(r.publishDate),
    'deadline_date': _iso(r.deadlineDate),
    'deadline_estimated': r.deadlineEstimated ? 1 : 0,
    'deadline_source': r.deadlineSource,
    'matched_keywords': r.matchedKeywords,
    'matched_scope': r.matchedScope,
    'source_keyword': r.sourceKeyword,
    'summary': r.summary,
    'content': r.content,
    'content_html': r.contentHtml,
    'attachments': r.attachments,
    'also_found_on': r.alsoFoundOn,
    // 类型兜底：万一调用方没设，落库前补算一次，
    // 避免出现 NULL 被当成「可投」的漏网数据
    'notice_type': r.noticeType.isNotEmpty
        ? r.noticeType
        : NoticeType.classify(r.category, r.title),
    'budget_yuan': r.budgetYuan,
    'budget_cap_text': r.budgetCapText,
    'project_code': r.projectCode,
    'is_new': r.isNew ? 1 : 0,
    'tracked': r.tracked ? 1 : 0,
    'removed': r.removed ? 1 : 0,
    'verified_at': _iso(r.verifiedAt),
    'link_status': r.linkStatus,
    'crawl_time': _iso(r.crawlTime),
    'create_time': _iso(r.createTime),
  };

  TenderRecord _read(Map<String, Object?> m) {
    final r = TenderRecord()
      ..id = _int(m['id'])
      ..siteCode = _str(m['site_code'])
      ..siteName = _str(m['site_name'])
      ..title = _str(m['title'])
      ..url = _str(m['url'])
      ..urlHash = _str(m['url_hash'])
      ..accessUrl = _str(m['access_url'])
      ..category = _str(m['category'])
      ..categoryCode = _str(m['category_code'])
      ..region = _str(m['region'])
      ..publishDate = _time(m['publish_date'])
      ..deadlineDate = _time(m['deadline_date'])
      ..deadlineEstimated = _int(m['deadline_estimated']) == 1
      ..deadlineSource = _str(m['deadline_source'])
      ..matchedKeywords = _str(m['matched_keywords'])
      ..matchedScope = _str(m['matched_scope'])
      ..sourceKeyword = _str(m['source_keyword'])
      ..summary = _str(m['summary'])
      ..content = _str(m['content'])
      ..contentHtml = _str(m['content_html'])
      ..attachments = _str(m['attachments'])
      ..alsoFoundOn = _str(m['also_found_on'])
      ..noticeType = _str(m['notice_type'])
      ..budgetYuan = m['budget_yuan'] == null ? null : _int(m['budget_yuan'])
      ..budgetCapText = _str(m['budget_cap_text'])
      ..projectCode = _str(m['project_code'])
      ..isNew = _int(m['is_new']) == 1
      ..tracked = _int(m['tracked']) == 1
      ..removed = _int(m['removed']) == 1
      ..verifiedAt = _time(m['verified_at'])
      ..linkStatus = _str(m['link_status'])
      ..crawlTime = _time(m['crawl_time'])
      ..createTime = _time(m['create_time']);
    return r;
  }

  Map<String, Object?> _writeLog(CrawlLogRecord log) => {
    'site_code': log.siteCode,
    'trigger_type': log.triggerType,
    'status': log.status,
    'start_time': _iso(log.startTime),
    'end_time': _iso(log.endTime),
    'duration_ms': log.durationMs,
    'candidate_count': log.candidateCount,
    'excluded_count': log.excludedCount,
    'matched_count': log.matchedCount,
    'saved_count': log.savedCount,
    'duplicate_count': log.duplicateCount,
    'failed_count': log.failedCount,
    'request_count': log.requestCount,
    'verified_count': log.verifiedCount,
    'detail': log.detail,
    'error_message': log.errorMessage,
  };

  CrawlLogRecord _readLog(Map<String, Object?> m) {
    return CrawlLogRecord()
      ..id = _int(m['id'])
      ..siteCode = _str(m['site_code'])
      ..triggerType = _str(m['trigger_type'])
      ..status = _str(m['status'])
      ..startTime = _time(m['start_time'])
      ..endTime = _time(m['end_time'])
      ..durationMs = _int(m['duration_ms'])
      ..candidateCount = _int(m['candidate_count'])
      ..excludedCount = _int(m['excluded_count'])
      ..matchedCount = _int(m['matched_count'])
      ..savedCount = _int(m['saved_count'])
      ..duplicateCount = _int(m['duplicate_count'])
      ..failedCount = _int(m['failed_count'])
      ..requestCount = _int(m['request_count'])
      ..verifiedCount = _int(m['verified_count'])
      ..detail = _str(m['detail'])
      ..errorMessage = _str(m['error_message']);
  }

  static int _int(Object? value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    if (value is String) return int.tryParse(value) ?? 0;
    return 0;
  }

  static String _str(Object? value) => value is String ? value : '';

  static DateTime? _time(Object? value) =>
      value is String ? DateUtils.parseIso(value) : null;
}

class _Where {
  const _Where(this.sql, this.args);

  final String sql;
  final List<Object?> args;
}
