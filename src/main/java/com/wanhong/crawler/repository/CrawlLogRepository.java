package com.wanhong.crawler.repository;

import com.wanhong.crawler.entity.CrawlLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CrawlLogRepository extends JpaRepository<CrawlLog, Long> {

    List<CrawlLog> findAllByOrderByStartTimeDesc(Pageable pageable);
}
