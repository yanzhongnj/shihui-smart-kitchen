package com.videostep.locator.util;

import java.net.InetAddress;
import java.net.URI;

/**
 * 简单 SSRF 防护：仅允许 http/https，且拒绝内网/环回/链路本地地址。
 */
public class SsrfUtil {

    private SsrfUtil() {}

    public static void assertPublicUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("仅允许 http/https 链接");
            }
            String host = uri.getHost();
            if (host == null) throw new IllegalArgumentException("无效的 URL");
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                    || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
                throw new IllegalArgumentException("不允许访问内网地址");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("URL 校验失败");
        }
    }
}
