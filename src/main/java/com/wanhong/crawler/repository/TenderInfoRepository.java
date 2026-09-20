package com.wanhong.crawler.repository;

import com.wanhong.crawler.entity.TenderInfo;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TenderInfoRepository extends JpaRepository<TenderInfo, Long>, JpaSpecificationExecutor<TenderInfo> {

    Optional<TenderInfo> findByUrlHash(String urlHash);

    boolean existsByUrlHash(String urlHash);

    long countBySiteCode(String siteCode);

    List<TenderInfo> findAllByUrlHashIn(List<String> urlHashes);

    Optional<TenderInfo> findFirstByOrderByPublishDateDesc();

    /**
     * 取待校验的公告：未标记下架，且从未校验过或已超过跳过窗口。
     * 先校验"从没校验过的"，其次是校验时间最早的。
     */
    @Query("""
            select t from TenderInfo t
            where (t.removed is null or t.removed = false)
              and (t.verifiedAt is null or t.verifiedAt < :before)
            order by t.verifiedAt asc, t.id asc
            """)
    List<TenderInfo> findForVerification(@Param("before") LocalDateTime before, Pageable pageable);

    @Query("""
            select count(t) from TenderInfo t
            where (t.removed is null or t.removed = false)
              and (t.verifiedAt is null or t.verifiedAt < :before)
            """)
    long countForVerification(@Param("before") LocalDateTime before);

    /**
     * 只取命中词列，用于统计每个关键词命中了多少条公告。
     * 比"每个词跑一次 count 查询"划算得多。
     */
    @Query("select t.matchedKeywords from TenderInfo t where t.matchedKeywords is not null")
    List<String[]> findAllMatchedKeywords();
}
