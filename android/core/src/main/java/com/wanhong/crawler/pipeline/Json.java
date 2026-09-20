package com.wanhong.crawler.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 统一的 JSON 映射器工厂。
 *
 * <p><b>为什么必须有这个类</b>：安卓上直接 {@code new ObjectMapper()} 是不带
 * JavaTimeModule 的，序列化 {@code java.time.LocalDateTime} 会直接抛
 * <pre>
 * Java 8 date/time type `java.time.LocalDateTime` not supported by default:
 * add Module "com.fasterxml.jackson.datatype:jackson-datatype-jsr310"
 * </pre>
 * 旧版跑在 Spring Boot 上时，Spring 会自动注册这个模块，所以一直没暴露；
 * 移植到安卓后接口全挂在这里。现在所有 JSON 输出统一走这里，并关闭时间戳写法，
 * 日期序列化成 ISO-8601 字符串（{@code 2026-09-20T16:09:11}），页面直接可用。
 */
public final class Json {

    private static final ObjectMapper MAPPER = create();

    private Json() {
    }

    /** 共享实例（ObjectMapper 线程安全，配置后不要修改） */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static ObjectMapper create() {
        ObjectMapper mapper = new ObjectMapper();
        // 支持 java.time.*
        mapper.registerModule(new JavaTimeModule());
        // 日期写成 "2026-09-20T16:09:11" 而不是 [2026,9,20,16,9,11]
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 未知字段不报错，便于以后加字段
        mapper.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}
