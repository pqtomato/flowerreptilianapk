import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

/// SQLite 建库。
///
/// 表结构与安卓版 v7 完全一致，但这是 Flutter 版的第一个版本，
/// 所以 [version] 从 1 开始（安卓库直接升到 v7 的历史迁移不搬过来）。
/// 时间统一存固定宽度的 ISO 串（[DateUtils.iso]），
/// 既能直接解析，也能在 SQLite 里按字典序比较 —— 不必用 datetime() 转换。
class TenderDatabase {
  TenderDatabase({String? path}) : _customPath = path;

  /// 应用内共享的单例（数据库文件在应用私有目录）。
  static final TenderDatabase instance = TenderDatabase();

  static const String dbName = 'flower_tender.db';
  static const int version = 1;

  static const String tableTender = 'tender';
  static const String tableKeyword = 'crawler_keyword';
  static const String tableLog = 'crawl_log';

  final String? _customPath;
  Database? _db;

  Future<Database> get database async => _db ??= await _open();

  Future<Database> _open() async {
    final path = _customPath ?? p.join(await getDatabasesPath(), dbName);
    return openDatabase(
      path,
      version: version,
      onCreate: (db, _) async => _create(db),
      onUpgrade: _upgrade,
      // UI isolate 与 WorkManager 后台 isolate 会同时打开同一个库：
      // WAL 允许一写多读，busy_timeout 让并发写等待而不是立刻抛 SQLITE_BUSY。
      onConfigure: (db) async {
        await db.execute('PRAGMA journal_mode = WAL');
        await db.execute('PRAGMA busy_timeout = 5000');
      },
      // 安卓版把同一个 applicationId 的库升到过 v7。Flutter 版从 1 起算，
      // 覆盖安装时旧库会「版本更高」。两边表结构一致，沿用旧库即可，不能删数据。
      onDowngrade: (db, _, _) async => _create(db),
    );
  }

  Future<void> close() async {
    final db = _db;
    _db = null;
    await db?.close();
  }

  /// 当前数据库文件路径（设置页展示用）。
  Future<String> get path async =>
      _customPath ?? p.join(await getDatabasesPath(), dbName);

  Future<void> _upgrade(Database db, int oldVersion, int newVersion) async {
    // 首个版本没有历史库；后续加字段时在这里逐版本迁移。
    // 约定（与安卓版一致）：改结构的同时用已入库数据回填，不要求用户重新采集。
  }

  Future<void> _create(Database db) async {
    await db.execute(
      'CREATE TABLE IF NOT EXISTS $tableTender ('
      'id INTEGER PRIMARY KEY AUTOINCREMENT,'
      'site_code TEXT,'
      'site_name TEXT,'
      'title TEXT NOT NULL,'
      'url TEXT NOT NULL,'
      'url_hash TEXT NOT NULL UNIQUE,'
      'access_url TEXT,'
      'category TEXT,'
      'category_code TEXT,'
      'region TEXT,'
      'publish_date TEXT,'
      'deadline_date TEXT,'
      'deadline_estimated INTEGER NOT NULL DEFAULT 0,'
      'deadline_source TEXT,'
      'matched_keywords TEXT,'
      'matched_scope TEXT,'
      'source_keyword TEXT,'
      'summary TEXT,'
      'content TEXT,'
      'content_html TEXT,'
      'attachments TEXT,'
      'also_found_on TEXT,'
      'notice_type TEXT,'
      'budget_yuan INTEGER,'
      'budget_cap_text TEXT,'
      'project_code TEXT,'
      'is_new INTEGER NOT NULL DEFAULT 1,'
      'tracked INTEGER NOT NULL DEFAULT 0,'
      'removed INTEGER NOT NULL DEFAULT 0,'
      'verified_at TEXT,'
      'link_status TEXT,'
      'crawl_time TEXT,'
      'create_time TEXT)',
    );

    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_tender_site ON $tableTender(site_code)',
    );
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_tender_publish ON $tableTender(publish_date)',
    );
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_tender_deadline ON $tableTender(deadline_date)',
    );
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_tender_region ON $tableTender(region)',
    );
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_tender_new ON $tableTender(is_new)',
    );
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_tender_notice_type ON $tableTender(notice_type)',
    );

    await db.execute(
      'CREATE TABLE IF NOT EXISTS $tableKeyword ('
      'id INTEGER PRIMARY KEY AUTOINCREMENT,'
      'keyword TEXT NOT NULL,'
      'display TEXT,'
      'scope TEXT NOT NULL,'
      'action TEXT NOT NULL,'
      'note TEXT,'
      'create_time TEXT,'
      'UNIQUE(keyword, scope))',
    );

    await db.execute(
      'CREATE TABLE IF NOT EXISTS $tableLog ('
      'id INTEGER PRIMARY KEY AUTOINCREMENT,'
      'site_code TEXT,'
      'trigger_type TEXT,'
      'status TEXT,'
      'start_time TEXT,'
      'end_time TEXT,'
      'duration_ms INTEGER DEFAULT 0,'
      'candidate_count INTEGER DEFAULT 0,'
      'excluded_count INTEGER DEFAULT 0,'
      'matched_count INTEGER DEFAULT 0,'
      'saved_count INTEGER DEFAULT 0,'
      'duplicate_count INTEGER DEFAULT 0,'
      'failed_count INTEGER DEFAULT 0,'
      'request_count INTEGER DEFAULT 0,'
      'verified_count INTEGER DEFAULT 0,'
      'detail TEXT,'
      'error_message TEXT)',
    );
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_log_start ON $tableLog(start_time)',
    );
  }
}

/// 兼容用别名：服务层按「应用数据库」的名字引用也可以。
typedef AppDatabase = TenderDatabase;
