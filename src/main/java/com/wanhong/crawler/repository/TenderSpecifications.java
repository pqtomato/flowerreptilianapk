package com.wanhong.crawler.repository;

import com.wanhong.crawler.entity.TenderInfo;
import com.wanhong.crawler.web.dto.TenderQueryParams;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 公告查询条件构造器。
 *
 * <p>用 Criteria 而不是 JPQL 拼字符串：条件组合多（站点 / 关键词 / 区县 / 时间 /
 * 未截止 / 新公告 / 收藏 / 是否含下架），而且可空参数在 JPQL 里容易踩类型推断的坑。
 */
public final class TenderSpecifications {

    private TenderSpecifications() {
    }

    public static Specification<TenderInfo> build(TenderQueryParams params) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (hasText(params.siteCode())) {
                predicates.add(cb.equal(root.get("siteCode"), params.siteCode().trim()));
            }
            if (hasText(params.keyword())) {
                String like = "%" + params.keyword().trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("title")), like),
                        cb.like(cb.lower(root.get("matchedKeywords")), like),
                        cb.like(cb.lower(root.get("region")), like)
                ));
            }
            if (hasText(params.region())) {
                predicates.add(cb.equal(root.get("region"), params.region().trim()));
            }
            if (params.fromTime() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("publishDate"), params.fromTime()));
            }
            if (params.toTime() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("publishDate"), params.toTime()));
            }
            // 已下架公告默认不展示（校验发现的死链）
            if (!params.includeRemoved()) {
                predicates.add(cb.or(
                        cb.isNull(root.get("removed")),
                        cb.isFalse(root.get("removed"))
                ));
            }
            if (params.onlyNew()) {
                predicates.add(cb.isTrue(root.get("isNew")));
            }
            if (params.trackedOnly()) {
                predicates.add(cb.isTrue(root.get("tracked")));
            }
            if (params.onlyOpen()) {
                predicates.add(cb.isNotNull(root.get("deadlineDate")));
                predicates.add(cb.greaterThanOrEqualTo(root.get("deadlineDate"), LocalDateTime.now()));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
