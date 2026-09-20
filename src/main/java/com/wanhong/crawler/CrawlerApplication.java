package com.wanhong.crawler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * 安庆地区花卉 / 园林绿化招标信息采集系统启动类。
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class CrawlerApplication {

    /**
     * 统一时区。
     *
     * <p>参考上一版系统的踩坑记录：容器默认 UTC 会导致「今天/是否过期」判断偏移 8 小时。
     * 截止日期与过期判断都依赖 {@code LocalDateTime.now()}，所以必须在启动最开始就把
     * 默认时区固定为 Asia/Shanghai（可用 {@code -Duser.timezone=} 覆盖）。
     */
    public static void main(String[] args) {
        if (System.getProperty("user.timezone") == null || System.getProperty("user.timezone").isBlank()) {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        }
        SpringApplication.run(CrawlerApplication.class, args);
    }
}
