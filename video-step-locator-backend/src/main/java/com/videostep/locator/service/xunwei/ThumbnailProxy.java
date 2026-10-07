package com.videostep.locator.service.xunwei;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 目标成品参照图代理：抓取外部封面 URL（yt-dlp 封面多为远程图）为本地字节，供双图纠偏使用。
 * 超时/失败/过大 → 抛 {@link XunweiVisionException}（controller 转可读提示，可降级为用户手动上传参照图）。
 */
@Service
@Slf4j
public class ThumbnailProxy {

    @Value("${xunwei.vision.thumb-timeout-ms:10000}")
    private long timeoutMs;

    @Value("${xunwei.vision.thumb-max-bytes:5242880}")
    private long maxBytes; // 5MB

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public byte[] fetch(String url) {
        if (url == null || url.isBlank()) {
            throw new XunweiVisionException("参照图地址为空");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                throw new XunweiVisionException("参照图下载失败 (HTTP " + resp.statusCode() + ")");
            }
            byte[] body = resp.body();
            if (body == null || body.length == 0) {
                throw new XunweiVisionException("参照图内容为空");
            }
            if (body.length > maxBytes) {
                throw new XunweiVisionException("参照图过大，无法用于对比");
            }
            return body;
        } catch (XunweiVisionException e) {
            throw e;
        } catch (Exception e) {
            log.warn("ThumbnailProxy fetch error url={}: {}", url, e.getMessage());
            throw new XunweiVisionException("参照图获取失败：" + e.getMessage());
        }
    }
}
