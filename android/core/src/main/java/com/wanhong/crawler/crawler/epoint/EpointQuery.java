package com.wanhong.crawler.crawler.epoint;

/** 一次站内检索的请求参数。 */
public class EpointQuery {

    private final String endpoint;
    private final String referer;
    private final String keyword;
    private final String fields;
    private final String cnum;
    private final int pageIndex;
    private final int pageSize;
    private final int contentLength;
    private final int lookbackDays;
    private final boolean unionConditionNull;

    public EpointQuery(String endpoint, String referer, String keyword, String fields, String cnum,
                       int pageIndex, int pageSize, int contentLength, int lookbackDays,
                       boolean unionConditionNull) {
        this.endpoint = endpoint;
        this.referer = referer;
        this.keyword = keyword;
        this.fields = fields;
        this.cnum = cnum;
        this.pageIndex = pageIndex;
        this.pageSize = pageSize;
        this.contentLength = contentLength;
        this.lookbackDays = lookbackDays;
        this.unionConditionNull = unionConditionNull;
    }

    public String endpoint() {
        return endpoint;
    }

    public String referer() {
        return referer;
    }

    public String keyword() {
        return keyword;
    }

    public String fields() {
        return fields;
    }

    public String cnum() {
        return cnum;
    }

    public int pageIndex() {
        return pageIndex;
    }

    public int pageSize() {
        return pageSize;
    }

    public int contentLength() {
        return contentLength;
    }

    public int lookbackDays() {
        return lookbackDays;
    }

    public boolean unionConditionNull() {
        return unionConditionNull;
    }
}
