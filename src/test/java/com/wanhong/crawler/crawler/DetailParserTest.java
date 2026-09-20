package com.wanhong.crawler.crawler;

import com.wanhong.crawler.config.CrawlerProperties;
import com.wanhong.crawler.crawler.model.DetailResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用三个站点真实的页面骨架验证详情页解析。
 */
class DetailParserTest {

    private DetailParser parser;

    private static final String LONG_TEXT =
            "本项目为园区绿化提升工程，采购内容包括时令花卉、草花、乔灌木及配套绿化养护服务，"
                    + "要求供应商具备苗木花卉经营资质，并按招标文件要求提供样品。"
                    + "投标人须在投标截止时间前完成投标文件的递交，逾期送达的投标文件将被拒收。"
                    + "本项目采用综合评分法评标，具体评分细则详见招标文件第三章评标办法。";

    @BeforeEach
    void setUp() {
        CrawlerProperties properties = new CrawlerProperties();
        properties.getDetail().setMaxContentLength(30000);
        parser = new DetailParser(properties);
    }

    private byte[] utf8(String html) {
        return html.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("城管局版式：h1.ls-article-title + .ls-article-info + 发布日期")
    void parsesCgjLayout() {
        String html = """
                <html><head><title>安庆市狮子山公园招标代理机构采购比价公告_安庆市城市管理局</title></head>
                <body>
                <div class="ls-article">
                  <div class="ls-article-inner">
                    <h1 class="ls-article-title">安庆市狮子山公园招标代理机构采购比价公告</h1>
                    <div class="ls-article-menu"><span class="sp">发布日期：2026-09-16 16:31</span></div>
                    <div class="ls-article-info">%s</div>
                  </div>
                </div>
                </body></html>
                """.formatted(LONG_TEXT);

        DetailResult result = parser.parse(utf8(html), "https://cgj.anqing.gov.cn/xxfb/tzgg/1.html");

        assertEquals("安庆市狮子山公园招标代理机构采购比价公告", result.title());
        assertEquals(LocalDateTime.of(2026, 9, 16, 16, 31), result.publishDate());
        assertNotNull(result.contentText());
        assertTrue(result.contentText().contains("时令花卉"));
    }

    @Test
    @DisplayName("阳光采购平台版式：.sub-detail-title(title属性) + .sub-detail-content + 附件")
    void parsesWyxmzxLayout() {
        String html = """
                <html><head><title>安庆市阳光采购交易服务平台</title></head>
                <body>
                  <div class="sub-detail">
                    <div class="sub-detail-title" title="苗木花卉交易大会特装展区设计施工项目">苗木花卉交易大会特装展区设计施工项目</div>
                    <div class="sub-detail-info">发布时间：2026-09-11 15:50</div>
                    <div class="sub-detail-content">%s</div>
                    <div class="sub-file-title"><a href="/files/比选文件.pdf">比选文件.pdf</a></div>
                  </div>
                </body></html>
                """.formatted(LONG_TEXT);

        DetailResult result = parser.parse(utf8(html),
                "https://www.wyxmzx.com/jyxx/002003/002003001/20230911/x.html");

        assertEquals("苗木花卉交易大会特装展区设计施工项目", result.title());
        assertEquals(LocalDateTime.of(2026, 9, 11, 15, 50), result.publishDate());
        assertTrue(result.contentText().contains("绿化养护"));
        assertFalse(result.attachments().isEmpty());
        assertTrue(result.attachments().get(0).contains("https://www.wyxmzx.com/files/"));
    }

    @Test
    @DisplayName("交易服务网版式：<title> + input#infodate + .notice-content + a.enclosure-name")
    void parsesAqggzyLayout() {
        String html = """
                <html><head><title>安庆市机场路护栏花箱提升项目采购公告</title></head>
                <body>
                  <div class="notice-box">
                    <div class="notice-content">%s</div>
                  </div>
                  <div class="enclosure">
                    <div class="enclosure-bd">
                      <a class="enclosure-name" href="/EpointWebBuilder/attach/download?attachGuid=1" title="公告.pdf">公告.pdf</a>
                    </div>
                  </div>
                  <input type="hidden" id="infodate" value="2026-09-08" />
                </body></html>
                """.formatted(LONG_TEXT);

        DetailResult result = parser.parse(utf8(html),
                "https://aqggzy.anqing.gov.cn/jyxx/003002/003002001/20260908/x.html");

        assertEquals("安庆市机场路护栏花箱提升项目采购公告", result.title());
        assertEquals(LocalDateTime.of(2026, 9, 8, 0, 0), result.publishDate());
        assertTrue(result.contentText().contains("草花"));
        assertFalse(result.attachments().isEmpty());
        assertTrue(result.attachments().get(0).contains("公告.pdf"));
    }

    @Test
    @DisplayName("标题去掉『_站点名』后缀")
    void stripsSiteSuffixFromTitle() {
        String html = "<html><head><title>花卉采购公告_安庆市城市管理局</title></head><body>"
                + "<div class=\"ls-article-info\">" + LONG_TEXT + "</div></body></html>";

        DetailResult result = parser.parse(utf8(html), "https://cgj.anqing.gov.cn/x.html");
        assertEquals("花卉采购公告", result.title());
    }

    @Test
    @DisplayName("空响应体不会抛异常")
    void handlesEmptyBody() {
        DetailResult result = parser.parse(new byte[0], "https://a.com/x.html");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("正文过长时按配置截断")
    void truncatesLongContent() {
        CrawlerProperties properties = new CrawlerProperties();
        properties.getDetail().setMaxContentLength(100);
        DetailParser shortParser = new DetailParser(properties);

        String html = "<html><head><title>花卉采购</title></head><body>"
                + "<div class=\"notice-content\">" + LONG_TEXT + LONG_TEXT + "</div></body></html>";

        DetailResult result = shortParser.parse(utf8(html), "https://a.com/x.html");
        assertEquals(100, result.contentText().length());
    }
}
