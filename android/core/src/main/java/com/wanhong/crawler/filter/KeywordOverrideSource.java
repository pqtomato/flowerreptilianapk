package com.wanhong.crawler.filter;

import java.util.List;

/** 用户自定义关键词的来源（安卓上由 SQLite 实现）。 */
public interface KeywordOverrideSource {

    List<CrawlerKeyword> loadOverrides();

    KeywordOverrideSource EMPTY = new KeywordOverrideSource() {
        public List<CrawlerKeyword> loadOverrides() {
            return java.util.Collections.emptyList();
        }
    };
}
