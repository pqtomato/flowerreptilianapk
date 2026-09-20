package com.wanhong.crawler.repository;

import com.wanhong.crawler.entity.CrawlerKeyword;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrawlerKeywordRepository extends JpaRepository<CrawlerKeyword, Long> {

    List<CrawlerKeyword> findAllByOrderByScopeAscCreateTimeAsc();

    Optional<CrawlerKeyword> findByKeywordAndScope(String keyword, CrawlerKeyword.Scope scope);

    List<CrawlerKeyword> findByScope(CrawlerKeyword.Scope scope);
}
