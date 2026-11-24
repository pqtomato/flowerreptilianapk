import 'package:flutter/services.dart' show rootBundle;

import '../keywords.dart';
import '../models.dart';
import '../settings.dart';

/// 关键词匹配：三套词表 + 用户自定义增删。
///
/// - 标题词表：既是站内检索词，也判定标题命中；
/// - 正文词表：只判定正文命中；
/// - 排除词表：命中标题即丢弃（车辆维修 / 厕所管护 / 资产拍卖这类噪声）。
class KeywordRules {
  final CrawlerSettings settings;
  final KeywordOverrideSource overrideSource;
  final void Function(String)? onInfo;
  final void Function(String)? onWarn;

  List<String> _titleKeywords = const [];
  List<String> _contentKeywords = const [];
  List<String> _excludeKeywords = const [];
  List<String> _customTitleKeywords = const [];
  List<String> _customContentKeywords = const [];
  int _disabledCount = 0;

  KeywordRules(this.settings, this.overrideSource, {this.onInfo, this.onWarn});

  /// 重新加载：文件里的内置词 + 存储里用户维护的增删。
  Future<void> reload() async {
    final filter = settings.filter;

    final fileTitles = <String>[...await _loadKeywords(filter.titleKeywordsFile)];
    fileTitles.addAll(filter.extraTitleKeywords);
    final fileContents = <String>[...await _loadKeywords(filter.contentKeywordsFile)];
    fileContents.addAll(filter.extraContentKeywords);
    final fileExcludes = <String>[...await _loadKeywords(filter.excludeKeywordsFile)];
    fileExcludes.addAll(filter.excludeKeywords);

    final overrides = await _loadOverrides();
    final disabled = <String>{};
    final addedTitle = <String>[];
    final addedContent = <String>[];
    final addedExclude = <String>[];
    for (final keyword in overrides) {
      if (keyword.keyword.isEmpty) continue;
      if (keyword.action == KeywordAction.disable) {
        disabled.add(keyword.keyword);
      } else if (keyword.scope == KeywordScope.title) {
        addedTitle.add(keyword.keyword);
      } else if (keyword.scope == KeywordScope.content) {
        addedContent.add(keyword.keyword);
      } else if (keyword.scope == KeywordScope.exclude) {
        addedExclude.add(keyword.keyword);
      }
    }

    // 停用的内置词剔除，再并入用户新增的词
    fileTitles.removeWhere(disabled.contains);
    fileContents.removeWhere(disabled.contains);
    fileExcludes.removeWhere(disabled.contains);
    fileTitles.addAll(addedTitle);
    fileContents.addAll(addedContent);
    fileExcludes.addAll(addedExclude);

    _titleKeywords = _normalizeKeywords(fileTitles);
    _contentKeywords = _normalizeKeywords(fileContents);
    _excludeKeywords = _normalizeKeywords(fileExcludes);
    _customTitleKeywords = _normalizeKeywords(addedTitle);
    _customContentKeywords = _normalizeKeywords(addedContent);
    _disabledCount = disabled.length;

    onInfo?.call('关键词加载完成：标题词 ${_titleKeywords.length}（自定义 ${_customTitleKeywords.length}），'
        '正文词 ${_contentKeywords.length}（自定义 ${_customContentKeywords.length}），'
        '排除词 ${_excludeKeywords.length}，停用 $_disabledCount');
  }

  Future<List<CrawlerKeyword>> _loadOverrides() async {
    try {
      return await overrideSource.loadOverrides();
    } catch (e) {
      onWarn?.call('读取自定义关键词失败（本次仅使用文件词表）：$e');
      return const [];
    }
  }

  List<String> get titleKeywords => _titleKeywords;
  List<String> get contentKeywords => _contentKeywords;
  List<String> get excludeKeywords => _excludeKeywords;
  List<String> get customTitleKeywords => _customTitleKeywords;
  List<String> get customContentKeywords => _customContentKeywords;
  int get disabledCount => _disabledCount;

  /// 站内检索用词：标题词搜 title；只在正文词表里的词搜 title;content。
  /// 已在标题词表里的词不重复搜正文，避免请求量翻倍。
  List<SearchTerm> get searchTerms {
    final terms = <SearchTerm>[];
    final seen = <String>{};
    for (final keyword in _titleKeywords) {
      if (seen.add(keyword)) terms.add(SearchTerm.title(keyword));
    }
    final titleSet = _titleKeywords.toSet();
    for (final keyword in _contentKeywords) {
      if (!titleSet.contains(keyword) && seen.add(keyword)) {
        terms.add(SearchTerm.content(keyword));
      }
    }
    return terms;
  }

  /// 标题是否命中排除词。必须能单独调用（正文命中那条路径标题参数是 null）。
  bool isExcluded(String? title) {
    final normalized = normalize(title);
    if (normalized.isEmpty) return false;
    for (final exclude in _excludeKeywords) {
      if (normalized.contains(exclude)) return true;
    }
    return false;
  }

  /// 标题 + 正文联合判定。排除词只判定标题，正文顺带提及不误伤。
  MatchResult match(String? title, String? content) {
    final normalizedTitle = normalize(title);
    final normalizedContent = normalize(content);

    for (final exclude in _excludeKeywords) {
      if (normalizedTitle.isNotEmpty && normalizedTitle.contains(exclude)) {
        return MatchResult.notMatched;
      }
    }

    final hits = <String>{};
    var titleHit = false;
    var contentHit = false;

    if (settings.filter.matchTitle && normalizedTitle.isNotEmpty) {
      for (final keyword in _titleKeywords) {
        if (normalizedTitle.contains(keyword)) {
          hits.add(keyword);
          titleHit = true;
        }
      }
    }
    if (settings.filter.matchContent && normalizedContent.isNotEmpty) {
      for (final keyword in _contentKeywords) {
        if (normalizedContent.contains(keyword)) {
          hits.add(keyword);
          contentHit = true;
        }
      }
    }
    return MatchResult(hits, titleHit, contentHit);
  }

  /// 读取词表（assets 打包资源）。空行与 # 注释忽略；行尾 ` #` 注释也裁掉。
  Future<List<String>> _loadKeywords(String location) async {
    if (location.trim().isEmpty) return const [];
    try {
      final raw = await rootBundle.loadString(location.trim());
      final keywords = <String>[];
      for (var line in raw.split('\n')) {
        var value = line.trim();
        if (value.isEmpty || value.startsWith('#')) continue;
        final commentIndex = value.indexOf(' #');
        if (commentIndex > 0) value = value.substring(0, commentIndex).trim();
        if (value.isNotEmpty) keywords.add(value);
      }
      return keywords;
    } catch (e) {
      onWarn?.call('关键词文件不存在或读取失败，已跳过：$location（$e）');
      return const [];
    }
  }

  /// 去重 + 小写 + 去空白，并按长度倒序（长词优先，命中信息更精确）。
  List<String> _normalizeKeywords(List<String> raw) {
    final unique = <String>{};
    for (final keyword in raw) {
      final normalized = normalize(keyword);
      if (normalized.isNotEmpty) unique.add(normalized);
    }
    final list = unique.toList()
      ..sort((a, b) => b.length.compareTo(a.length));
    return List.unmodifiable(list);
  }

  /// 小写化并去掉所有空白字符，规避「花 卉」这类排版干扰（含 &nbsp;）。
  static String normalize(String? text) {
    if (text == null) return '';
    return text.replaceAll(' ', ' ').replaceAll(RegExp(r'\s+'), '').toLowerCase();
  }
}

/// 一次匹配结果。
class MatchResult {
  static final MatchResult notMatched = MatchResult(const {}, false, false);

  final Set<String> keywords;
  final bool titleHit;
  final bool contentHit;

  MatchResult(this.keywords, this.titleHit, this.contentHit);

  bool get isMatched => keywords.isNotEmpty;

  String get scope {
    if (titleHit && contentHit) return 'TITLE,CONTENT';
    if (titleHit) return 'TITLE';
    return contentHit ? 'CONTENT' : '';
  }

  String get joinedKeywords => keywords.join(',');
}
