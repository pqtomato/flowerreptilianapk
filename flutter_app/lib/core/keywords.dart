/// 用户维护的关键词（词表文件之外的增删项）。
enum KeywordScope { title, content, exclude }

enum KeywordAction { add, disable }

class CrawlerKeyword {
  int id = 0;
  String keyword = '';
  String display = '';
  KeywordScope scope = KeywordScope.title;
  KeywordAction action = KeywordAction.add;
  String note = '';

  CrawlerKeyword({
    this.id = 0,
    this.keyword = '',
    this.display = '',
    this.scope = KeywordScope.title,
    this.action = KeywordAction.add,
    this.note = '',
  });
}

/// 自定义关键词的存储端口。
abstract class KeywordOverrideSource {
  Future<List<CrawlerKeyword>> loadOverrides();

  Future<void> insert(CrawlerKeyword keyword);

  Future<void> delete(int id);

  Future<void> enable(int id);

  Future<List<CrawlerKeyword>> all();
}

class EmptyKeywordSource implements KeywordOverrideSource {
  const EmptyKeywordSource();

  @override
  Future<List<CrawlerKeyword>> loadOverrides() async => const [];

  @override
  Future<void> insert(CrawlerKeyword keyword) async {}

  @override
  Future<void> delete(int id) async {}

  @override
  Future<void> enable(int id) async {}

  @override
  Future<List<CrawlerKeyword>> all() async => const [];
}
