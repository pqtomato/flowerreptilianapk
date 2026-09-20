package com.wanhong.crawler.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import javax.net.ssl.SSLParameters;
import java.util.List;

/**
 * 在容器启动前处理 HTTPS 主机名校验开关。
 *
 * <p>背景：安庆阳光采购交易服务平台（www.wyxmzx.com）对外提供的证书
 * 既不在有效期内，SAN 里也没有该域名，JDK 的 {@code java.net.http.HttpClient}
 * 会直接抛出 {@code SSLHandshakeException}。
 *
 * <p>JDK 的 HttpClient 把主机名校验做成 <b>JVM 级</b> 的静态开关
 * （{@code jdk.internal.httpclient.disableHostnameVerification}），
 * 只能在任何 HttpClient 实例创建之前设置 —— 也就是必须在 Spring 容器
 * 创建 Bean 之前完成，因此这里用 {@link EnvironmentPostProcessor}。
 *
 * <p>是否生效完全由配置决定：只有显式配置了 {@code crawler.request.insecure-tls=true}
 * 或 {@code crawler.request.insecure-tls-hosts} 时才会关闭主机名校验。
 */
public class HostnameVerificationInitializer implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(HostnameVerificationInitializer.class);

    static final String DISABLE_HOSTNAME_VERIFICATION = "jdk.internal.httpclient.disableHostnameVerification";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean global = Boolean.TRUE.equals(
                environment.getProperty("crawler.request.insecure-tls", Boolean.class, Boolean.FALSE));
        List<String> hosts = readHosts(environment);

        if (!global && hosts.isEmpty()) {
            return;
        }

        System.setProperty(DISABLE_HOSTNAME_VERIFICATION, "true");
        log.warn("目标站点证书异常，已关闭 JDK HttpClient 的 HTTPS 主机名校验（{}={}）",
                DISABLE_HOSTNAME_VERIFICATION, "true");
        if (global) {
            log.warn("crawler.request.insecure-tls=true：所有站点的证书链校验都被跳过，请仅在必要时使用");
        } else {
            log.warn("仅以下域名的证书链校验会被跳过：{}；这些站点证书恢复正常后请清空 insecure-tls-hosts", hosts);
        }
    }

    private List<String> readHosts(ConfigurableEnvironment environment) {
        try {
            return Binder.get(environment)
                    .bind("crawler.request.insecure-tls-hosts", Bindable.listOf(String.class))
                    .orElse(List.of());
        } catch (Exception e) {
            log.debug("读取 crawler.request.insecure-tls-hosts 失败", e);
            return List.of();
        }
    }

    /**
     * 供 {@code HttpFetcher} 复用：不设置 endpointIdentificationAlgorithm 的
     * SSLParameters（配合上面的 JVM 开关使用）。
     */
    public static SSLParameters relaxedSslParameters() {
        SSLParameters parameters = new SSLParameters();
        parameters.setEndpointIdentificationAlgorithm(null);
        return parameters;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
