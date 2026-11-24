import 'package:sqflite/sqflite.dart';

import '../core/common/date_utils.dart';
import '../core/keywords.dart';
import 'database.dart';

/// 用户自定义关键词的 SQLite 实现（同时作为内核的 [KeywordOverrideSource]）。
///
/// 内置词表来自 assets，**不写库**；这里只记两类覆盖：
/// - [KeywordAction.add]：用户新增的词；
/// - [KeywordAction.disable]：用户停用的内置词（内置词不物理删除）。
class SqliteKeywordStore implements KeywordOverrideSource {
  SqliteKeywordStore(this._database);

  final TenderDatabase _database;

  Future<Database> get _db => _database.database;

  @override
  Future<List<CrawlerKeyword>> loadOverrides() => all();

  @override
  Future<List<CrawlerKeyword>> all() async {
    final rows = await (await _db).query(
      TenderDatabase.tableKeyword,
      orderBy: 'scope ASC, id ASC',
    );
    return rows.map(_read).toList();
  }

  @override
  Future<void> insert(CrawlerKeyword keyword) async {
    final values = <String, Object?>{
      'keyword': keyword.keyword,
      'display': keyword.display.isEmpty ? keyword.keyword : keyword.display,
      'scope': _scopeName(keyword.scope),
      'action': _actionName(keyword.action),
      'note': keyword.note,
      'create_time': DateUtils.iso(DateUtils.nowCn()),
    };
    // UNIQUE(keyword, scope)：同一个词重复添加时覆盖旧记录，id 会变
    final id = await (await _db).insert(
      TenderDatabase.tableKeyword,
      values,
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
    keyword.id = id;
    if (keyword.display.isEmpty) {
      keyword.display = keyword.keyword;
    }
  }

  /// 与安卓版 `KeywordStore.add` 同名，便于服务层照搬。
  Future<void> add(CrawlerKeyword keyword) => insert(keyword);

  @override
  Future<void> delete(int id) async {
    await (await _db).delete(
      TenderDatabase.tableKeyword,
      where: 'id=?',
      whereArgs: [id],
    );
  }

  /// 与安卓版 `KeywordStore.remove` 同名。
  Future<void> remove(int id) => delete(id);

  /// 撤销停用：删掉 DISABLE 覆盖，内置词就重新回到词表里。
  ///
  /// ADD 记录调用它不会有副作用（自定义词要删请用 [delete]）。
  @override
  Future<void> enable(int id) async {
    final existing = await _findById(id);
    if (existing != null && existing.action == KeywordAction.disable) {
      await delete(id);
    }
  }

  /// 按「规范化关键词 + 作用域」查一条覆盖记录。
  Future<CrawlerKeyword?> findByKeywordAndScope(
    String keyword,
    KeywordScope scope,
  ) async {
    final rows = await (await _db).query(
      TenderDatabase.tableKeyword,
      where: 'keyword=? AND scope=?',
      whereArgs: [keyword, _scopeName(scope)],
      limit: 1,
    );
    return rows.isEmpty ? null : _read(rows.first);
  }

  Future<CrawlerKeyword?> _findById(int id) async {
    final rows = await (await _db).query(
      TenderDatabase.tableKeyword,
      where: 'id=?',
      whereArgs: [id],
      limit: 1,
    );
    return rows.isEmpty ? null : _read(rows.first);
  }

  CrawlerKeyword _read(Map<String, Object?> m) => CrawlerKeyword(
    id: _int(m['id']),
    keyword: _str(m['keyword']),
    display: _str(m['display']),
    scope: _parseScope(_str(m['scope'])),
    action: _parseAction(_str(m['action'])),
    note: _str(m['note']),
  );

  static String _scopeName(KeywordScope scope) {
    switch (scope) {
      case KeywordScope.title:
        return 'TITLE';
      case KeywordScope.content:
        return 'CONTENT';
      case KeywordScope.exclude:
        return 'EXCLUDE';
    }
  }

  static String _actionName(KeywordAction action) =>
      action == KeywordAction.disable ? 'DISABLE' : 'ADD';

  static KeywordScope _parseScope(String value) {
    switch (value.toUpperCase()) {
      case 'CONTENT':
        return KeywordScope.content;
      case 'EXCLUDE':
        return KeywordScope.exclude;
      default:
        return KeywordScope.title;
    }
  }

  static KeywordAction _parseAction(String value) =>
      value.toUpperCase() == 'DISABLE'
      ? KeywordAction.disable
      : KeywordAction.add;

  static int _int(Object? value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return 0;
  }

  static String _str(Object? value) => value is String ? value : '';
}
