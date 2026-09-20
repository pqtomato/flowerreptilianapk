package com.wanhong.crawler.pipeline;

import com.wanhong.crawler.filter.CrawlerKeyword;

import java.util.List;

/** 用户自定义关键词的存储端口（增删查）。 */
public interface KeywordStore {

    List<CrawlerKeyword> list();

    void add(CrawlerKeyword keyword);

    void remove(long id);

    CrawlerKeyword findByKeywordAndScope(String keyword, CrawlerKeyword.Scope scope);
}
