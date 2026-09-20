package com.wanhong.crawler.http;

/**
 * 采集过程中的致命异常（重试后仍然失败）。
 */
public class CrawlException extends RuntimeException {

    public CrawlException(String message) {
        super(message);
    }

    public CrawlException(String message, Throwable cause) {
        super(message, cause);
    }
}
