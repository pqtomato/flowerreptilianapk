import 'package:flutter_test/flutter_test.dart';
import 'package:flower_tender/core/filter/keyword_rules.dart';
import 'package:flower_tender/core/keywords.dart';
import 'package:flower_tender/core/settings.dart';

/// 只活在内存里的自定义词存储。
class FakeOverrides implements KeywordOverrideSource {
  final List<CrawlerKeyword> items = [];

  @override
  Future<List<CrawlerKeyword>> loadOverrides() async => List.of(items);

  @override
  Future<List<CrawlerKeyword>> all() async => List.of(items);

  @override
  Future<void> insert(CrawlerKeyword keyword) async => items.add(keyword);

  @override
  Future<void> delete(int id) async =>
      items.removeWhere((keyword) => keyword.id == id);

  @override
  Future<void> enable(int id) async {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late KeywordRules rules;

  setUp(() async {
    rules = KeywordRules(CrawlerSettings(), FakeOverrides());
    await rules.reload();
  });

  test('内置词表已加载', () {
    expect(rules.titleKeywords, contains('苗木'));
    expect(rules.contentKeywords, isNotEmpty);
    expect(rules.excludeKeywords, contains('拍卖'));
    expect(rules.searchTerms, isNotEmpty);
  });

  test('排除词只判定标题', () {
    expect(rules.isExcluded('车辆维修服务采购项目'), isTrue);
    expect(rules.isExcluded('城区绿化苗木采购项目'), isFalse);
  });

  test('标题命中', () {
    final result = rules.match('城区绿化苗木采购项目', null);
    expect(result.isMatched, isTrue);
    expect(result.titleHit, isTrue);
    expect(result.keywords, contains('苗木'));
  });

  test('命中排除词时整体不命中（即使正文提到苗木）', () {
    final result = rules.match('某单位资产拍卖交易公告', '标的包含苗木一批');
    expect(result.isMatched, isFalse);
  });

  test('正文命中只看正文词表', () {
    final result = rules.match('货物采购项目', '本项目采购花卉苗木一批用于道路绿化');
    expect(result.contentHit, isTrue);
    expect(result.titleHit, isFalse);
  });

  test('自定义新增与停用生效', () async {
    final overrides = FakeOverrides();
    final custom = KeywordRules(CrawlerSettings(), overrides);
    await custom.reload();
    final before = custom.titleKeywords.length;
    expect(custom.match('鸢尾种植项目', null).isMatched, isFalse);

    await overrides.insert(CrawlerKeyword(
      keyword: '鸢尾',
      display: '鸢尾',
      scope: KeywordScope.title,
      action: KeywordAction.add,
    ));
    await overrides.insert(CrawlerKeyword(
      keyword: '苗木',
      scope: KeywordScope.title,
      action: KeywordAction.disable,
    ));
    await custom.reload();

    expect(custom.titleKeywords.length, before); // +1 新增 -1 停用
    expect(custom.match('鸢尾种植项目', null).isMatched, isTrue);
    expect(custom.match('苗木供应项目', null).isMatched, isFalse);
    expect(custom.disabledCount, 1);
  });

  test('检索词：正文独有词按 title;content 搜索', () {
    final contentTerm =
        rules.searchTerms.firstWhere((term) => term.isContentScope);
    expect(contentTerm.fields, 'title;content');
  });

  test('normalize 去空白并小写', () {
    expect(KeywordRules.normalize(' 花 卉 ABC '), '花卉abc');
  });
}
