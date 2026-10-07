package com.videostep.locator.service.xunwei;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * B 站视频搜索工具（匿名可用）。
 *
 * 原理：B 站搜索接口需 wbi 签名 + buvid3 cookie 才能绕过风控。
 * - buvid3：从 https://api.bilibili.com/x/frontend/finger/spi 匿名获取（缓存）。
 * - wbi 签名：从 nav 接口取 img_key/sub_key（失败回退内置默认值），按官方算法生成 w_rid。
 * - 可选配置 xunwei.search.bilibili-cookie：填入浏览器 Cookie（含 SESSDATA）后最稳，
 *   自用场景可显著提升成功率。
 */
@Service
@Slf4j
public class BilibiliSearchTool {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final String SPI_URL = "https://api.bilibili.com/x/frontend/finger/spi";
    private static final String NAV_URL = "https://api.bilibili.com/x/web-interface/nav";
    private static final String SEARCH_URL = "https://api.bilibili.com/x/web-interface/search/type";
    /** 内置默认 wbi 密钥（nav 获取失败时回退；官方会不定期轮换） */
    private static final String DEFAULT_IMG_KEY = "7cd084941338484aae1ad9425b84077c";
    private static final String DEFAULT_SUB_KEY = "4932caff0ff746eab6f01bf08b70ac45";
    private static final int[] MIXIN_TABLE = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43,
            5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16,
            24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59,
            6, 63, 57, 62, 11, 36, 20, 34, 44, 52};

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    @Value("${xunwei.search.bilibili-cookie:}")
    private String bilibiliCookie;

    private volatile String buvid3;
    private volatile String[] wbiKeys;

    public BilibiliSearchTool() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public List<VideoCandidate> search(String keyword, int count) throws Exception {
        int n = Math.max(1, Math.min(count, 10));
        String cookie = resolveCookie();
        String mixin = mixinKey();

        long wts = System.currentTimeMillis() / 1000;
        Map<String, String> params = new TreeMap<>();
        params.put("search_type", "video");
        params.put("keyword", keyword);
        params.put("page", "1");
        params.put("wts", String.valueOf(wts));
        StringBuilder qs = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (qs.length() > 0) qs.append('&');
            qs.append(e.getKey()).append('=').append(encode(e.getValue()));
        }
        String wRid = md5Hex(qs + mixin);
        String url = SEARCH_URL + "?" + qs + "&w_rid=" + wRid;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com/")
                .header("Cookie", cookie)
                .header("Accept", "application/json, text/plain, */*")
                .GET().build();
        HttpResponse<String> resp = httpClient.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new RuntimeException("B站搜索 HTTP " + resp.statusCode());
        }
        JsonNode root = objectMapper.readTree(resp.body());
        if (root.path("code").asInt() != 0) {
            throw new RuntimeException("B站搜索失败 code=" + root.path("code").asInt()
                    + " msg=" + root.path("message").asText());
        }

        List<VideoCandidate> out = new ArrayList<>();
        JsonNode result = root.path("data").path("result");
        if (result.isArray()) {
            for (JsonNode v : result) {
                if (!"video".equals(v.path("type").asText())) continue;
                String bvid = v.path("bvid").asText("");
                if (bvid.isBlank()) continue;
                out.add(new VideoCandidate(
                        bvid,
                        "https://www.bilibili.com/video/" + bvid,
                        stripHtml(v.path("title").asText("")),
                        v.path("author").asText(""),
                        parseDuration(v.path("duration").asText("")),
                        v.path("play").asLong(0),
                        normalizePic(v.path("pic").asText("")),
                        "bilibili"));
                if (out.size() >= n) break;
            }
        }
        log.info("[寻味] B站搜索 keyword={} results={}", keyword, out.size());
        return out;
    }

    // ---------------- 内部工具 ----------------

    private String resolveCookie() throws Exception {
        if (bilibiliCookie != null && !bilibiliCookie.isBlank()) {
            return bilibiliCookie;
        }
        if (buvid3 == null) {
            buvid3 = fetchBuvid3();
        }
        return "buvid3=" + buvid3;
    }

    private String fetchBuvid3() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(SPI_URL))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", UA)
                .GET().build();
        HttpResponse<String> resp = httpClient.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode root = objectMapper.readTree(resp.body());
        String b3 = root.path("data").path("b_3").asText("");
        String b4 = root.path("data").path("b_4").asText("");
        if (!b3.isBlank() && !b4.isBlank()) {
            return b3 + "; buvid4=" + b4;
        }
        return b3;
    }

    private String mixinKey() throws Exception {
        if (wbiKeys == null) {
            try {
                wbiKeys = fetchWbiKeys();
            } catch (Exception e) {
                log.warn("[寻味] 获取 wbi 密钥失败，使用内置默认值: {}", e.getMessage());
                wbiKeys = new String[]{DEFAULT_IMG_KEY, DEFAULT_SUB_KEY};
            }
        }
        String raw = wbiKeys[0] + wbiKeys[1];
        StringBuilder mixin = new StringBuilder();
        for (int i : MIXIN_TABLE) mixin.append(raw.charAt(i));
        return mixin.substring(0, 32);
    }

    private String[] fetchWbiKeys() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(NAV_URL))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", UA)
                .header("Cookie", "buvid3=" + (buvid3 == null ? "" : buvid3))
                .GET().build();
        HttpResponse<String> resp = httpClient.send(req,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode root = objectMapper.readTree(resp.body());
        String img = root.path("data").path("wbi_img").path("img_url").asText("");
        String sub = root.path("data").path("wbi_img").path("sub_url").asText("");
        return new String[]{keyFromUrl(img), keyFromUrl(sub)};
    }

    private String keyFromUrl(String url) {
        if (url == null || url.isBlank()) return "";
        String name = url.substring(url.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String md5Hex(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** 去掉 <em class="keyword"> 等 HTML 标签 */
    private String stripHtml(String s) {
        return s.replaceAll("<[^>]+>", "").trim();
    }

    /** "2:17" → 137 秒 */
    private double parseDuration(String d) {
        if (d == null || d.isBlank()) return 0;
        String[] parts = d.split(":");
        try {
            if (parts.length == 2) return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            if (parts.length == 3) return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    /** //i2.hdslb.com/... → https://i2.hdslb.com/... */
    private String normalizePic(String pic) {
        if (pic == null || pic.isBlank()) return "";
        return pic.startsWith("//") ? "https:" + pic : pic;
    }
}
