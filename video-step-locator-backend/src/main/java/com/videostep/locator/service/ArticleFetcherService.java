package com.videostep.locator.service;

import com.videostep.locator.util.SsrfUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 链接图文抓取（ARTICLE_LINK 来源，设计文档 §6.2）。
 * 仅抓取用户主动粘贴的公开链接，个人学习用途；保留 source_url 溯源。
 * SSRF 防护复用 {@link SsrfUtil}；正文提取为朴素 HTML→文本（去 script/style/标签、折叠空白）。
 */
@Service
@Slf4j
public class ArticleFetcherService {

    private static final int MAX_TEXT = 8000;
    private static final long MAX_BODY_BYTES = 2L * 1024 * 1024;

    private static final Pattern TAG_SCRIPT = Pattern.compile("(?is)<(script|style|noscript|iframe|svg)[^>]*>.*?</\\1>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t\\r\\f\\v]+");
    private static final Pattern NEWLINE = Pattern.compile("\\n{3,}");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public Map<String, Object> fetch(String url) {
        SsrfUtil.assertPublicUrl(url);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) ShihuiKitchen/1.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new IllegalArgumentException("抓取失败：HTTP " + resp.statusCode());
            }
            byte[] body = resp.body();
            if (body == null || body.length == 0) throw new IllegalArgumentException("页面内容为空");
            if (body.length > MAX_BODY_BYTES) throw new IllegalArgumentException("页面过大，拒绝抓取");

            Charset cs = detectCharset(resp.headers().firstValue("Content-Type").orElse(""), body);
            String html = new String(body, cs);
            String title = extractTitle(html);
            String text = extractText(html);
            if (text.isBlank()) throw new IllegalArgumentException("未能提取到正文内容");

            Map<String, Object> res = new LinkedHashMap<>();
            res.put("url", url);
            res.put("title", title);
            res.put("text", text);
            return res;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("网页抓取失败：" + e.getMessage());
        }
    }

    private String extractTitle(String html) {
        Matcher t = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        if (t.find()) {
            String s = stripTags(t.group(1)).trim();
            if (!s.isEmpty()) return s.length() > 100 ? s.substring(0, 100) : s;
        }
        Matcher h1 = Pattern.compile("(?is)<h1[^>]*>(.*?)</h1>").matcher(html);
        if (h1.find()) {
            String s = stripTags(h1.group(1)).trim();
            return s.length() > 100 ? s.substring(0, 100) : s;
        }
        return "";
    }

    private String extractText(String html) {
        // 优先取 <article>/<main>/正文容器，降低导航/广告噪音（设计文档 §11：正文提取器多级回退）
        String region = pickContentRegion(html);
        String s = TAG_SCRIPT.matcher(region).replaceAll(" ");
        s = s.replaceAll("(?is)<br\\s*/?>", "\n").replaceAll("(?i)</(p|div|li|h[1-6]|tr)>", "\n");
        s = TAG.matcher(s).replaceAll(" ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
        s = WHITESPACE.matcher(s).replaceAll(" ");
        s = NEWLINE.matcher(s).replaceAll("\n\n");
        // 过滤样板行（导航/登录/版权/过短行）
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (isBoilerplate(t)) continue;
            sb.append(t).append('\n');
        }
        s = sb.toString().trim();
        if (s.length() > MAX_TEXT) s = s.substring(0, MAX_TEXT);
        return s;
    }

    private String pickContentRegion(String html) {
        Matcher m = Pattern.compile("(?is)<(article|main)[^>]*>(.*?)</(article|main)>").matcher(html);
        if (m.find()) return m.group(2);
        Matcher c = Pattern.compile(
                "(?is)<(div|section)[^>]*(?:class|id)\\s*=\\s*[\"'][^\"']*(?:content|article|recipe|detail|main|body)[^\"']*[\"'][^>]*>(.*?)</\\1>")
                .matcher(html);
        if (c.find()) return c.group(2);
        return html;
    }

    private boolean isBoilerplate(String t) {
        if (t.length() < 4) return true;
        return t.matches("(?i).*(登录|注册|首页|下载|版权|版权所有|copyright|©|关注我们|分享到|上一篇|下一篇).*");
    }

    private String stripTags(String s) {
        return TAG.matcher(s).replaceAll(" ").replaceAll("[ \\t]+", " ").trim();
    }

    private Charset detectCharset(String contentType, byte[] body) {
        if (contentType != null) {
            Matcher m = Pattern.compile("charset\\s*=\\s*[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(contentType);
            if (m.find()) {
                try { return Charset.forName(m.group(1)); } catch (Exception ignored) { }
            }
        }
        int len = Math.min(body.length, 4096);
        String head = new String(body, 0, len, StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([\\w-]+)").matcher(head);
        if (m.find()) {
            try { return Charset.forName(m.group(1)); } catch (Exception ignored) { }
        }
        return StandardCharsets.UTF_8;
    }
}
