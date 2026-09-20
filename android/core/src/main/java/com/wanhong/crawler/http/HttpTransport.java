package com.wanhong.crawler.http;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 原始 HTTP 传输端口。
 *
 * <p>把"真正发请求"这一步抽象出来，是实现「同一套爬虫内核同时跑在电脑和安卓上」的关键：
 * 内核负责 UA 轮换、限速、重试、cookie 预热、证书降级等策略，
 * 具体用哪个 HTTP 库（JDK HttpClient / OkHttp）由宿主决定。
 *
 * <p>实现方约定：
 * <ul>
 *   <li>负责 <b>自动解压</b> gzip/deflate，返回明文字节；</li>
 *   <li>负责 <b>cookie 的保存与回传</b>（首次访问的 WAF cookie 很重要）；</li>
 *   <li>负责处理证书异常域名（部分政务站点证书过期 / 证书链不完整）；</li>
 *   <li><b>不要</b>额外添加 Origin / Referer 头 —— 安庆阳光采购平台的 WAF 会因为
 *       这两个头判定「跨站请求伪造」，内核只会按站点配置传 Referer。</li>
 * </ul>
 */
public interface HttpTransport {

    /**
     * 发送一次请求。
     *
     * @param method  GET / POST
     * @param url     完整地址
     * @param headers 需要附加的头（内核已按站点配置决定是否包含 Referer）
     * @param body    POST 的请求体，GET 时为 null
     */
    FetchResponse send(String method, String url, Map<String, String> headers, byte[] body) throws IOException;

    /** 清掉某个域名的 cookie（403 时内核会调用它并重新预热）。 */
    void clearCookies(String host);

    /** 供日志排查：当前实现名。 */
    default String name() {
        return getClass().getSimpleName();
    }

    /** 传输层不支持的能力（如无法按域名逐个放宽证书）时返回的说明。 */
    default List<String> warnings() {
        return java.util.Collections.emptyList();
    }
}
