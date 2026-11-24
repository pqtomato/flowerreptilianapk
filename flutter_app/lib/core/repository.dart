import 'models.dart';

/// 公告存储端口。内核不依赖 SQLite，测试里可以用内存实现。
abstract class TenderRepository {
  Future<TenderRecord?> findById(int id);

  Future<TenderRecord?> findByUrlHash(String urlHash);

  Future<TenderRecord> insert(TenderRecord record);

  Future<void> update(TenderRecord record);

  Future<int> count();

  Future<int> countBySite(String siteCode);

  Future<int> countQuery(TenderQuery query);

  Future<List<TenderRecord>> query(TenderQuery query, int limit, int offset);

  Future<List<TenderRecord>> findForVerification(DateTime before, int limit);

  Future<int> countForVerification(DateTime before);

  Future<List<String>> allMatchedKeywords();

  Future<int> countPurgeable(DateTime deadlineBefore, {bool keepTracked = true});

  Future<int> purgeExpired(DateTime deadlineBefore, {bool keepTracked = true});

  Future<int> markAllRead();

  Future<bool> setTracked(int id, bool tracked);

  Future<void> saveLog(CrawlLogRecord log);

  Future<void> updateLog(CrawlLogRecord log);

  Future<List<CrawlLogRecord>> recentLogs(int limit);

  Future<CrawlLogRecord?> findLog(int id);
}
