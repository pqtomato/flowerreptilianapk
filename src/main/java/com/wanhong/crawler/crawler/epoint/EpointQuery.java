package com.wanhong.crawler.crawler.epoint;

/**
 * 一次站内检索的请求参数。
 *
 * @param lookbackDays 只检索最近 N 天内发布的公告；0 表示不限
 * @param unionConditionNull 阳光采购平台的 WAF 要求该字段为 null 而非空数组
 */
public record EpointQuery(String endpoint,
                          String referer,
                          String keyword,
                          String fields,
                          String cnum,
                          int pageIndex,
                          int pageSize,
                          int contentLength,
                          int lookbackDays,
                          boolean unionConditionNull) {
}
