package com.wanhong.crawler.pipeline;

/** 采集/校验进度回调（安卓上用于更新前台通知与页面）。 */
public interface ProgressListener {

    void onProgress(String message);

    ProgressListener SILENT = new ProgressListener() {
        public void onProgress(String message) {
        }
    };
}
