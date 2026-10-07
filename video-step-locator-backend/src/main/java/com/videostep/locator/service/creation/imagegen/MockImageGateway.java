package com.videostep.locator.service.creation.imagegen;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Mock 文生图网关：用 AWT 生成占位效果图（渐变底色 + 标注），P0/P1 开发/演示/免费体验用（不计费）。
 */
@Component
@Slf4j
public class MockImageGateway implements ImageGenGateway {

    @Value("${creation.effect-image.mock-delay-ms:600}")
    private long mockDelayMs;

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public boolean testConnection() {
        return true;
    }

    @Override
    public boolean isBillable() {
        return false;
    }

    @Override
    public ImageGenTaskRef submit(String prompt, String style) {
        // 模拟生成耗时（让前端进度可见）
        try {
            if (mockDelayMs > 0) Thread.sleep(mockDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"), "ai_imagegen");
        try {
            Files.createDirectories(tmpDir);
        } catch (Exception e) {
            throw new RuntimeException("创建临时目录失败: " + e.getMessage());
        }
        Path out = tmpDir.resolve("mock_" + UUID.randomUUID().toString().substring(0, 8) + ".png");
        try {
            drawMock(out, style);
            log.info("[MockImageGateway] 生成占位效果图 style='{}' -> {}", style, out);
            return new ImageGenTaskRef(provider(), out.toString());
        } catch (Exception e) {
            throw new RuntimeException("Mock 效果图生成失败: " + e.getMessage());
        }
    }

    @Override
    public ImageGenResult poll(String taskRef) {
        Path p = Path.of(taskRef);
        return new ImageGenResult(Files.exists(p) ? "completed" : "failed", taskRef);
    }

    @Override
    public String download(String resultUrl, String localPath) {
        Path src = Path.of(resultUrl);
        Path dst = Path.of(localPath);
        try {
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return dst.toString();
        } catch (Exception e) {
            throw new RuntimeException("Mock 效果图下载失败: " + e.getMessage());
        }
    }

    /** AWT 画占位图（渐变底色 + 标注，ASCII 避免字体兼容问题） */
    private void drawMock(Path out, String style) throws Exception {
        int w = 800, h = 800;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(255, 224, 130),
                w, h, new Color(244, 154, 110)));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 48));
        g.drawString("AI Effect Image", w / 2 - 200, h / 2 - 20);
        g.setFont(new Font("SansSerif", Font.PLAIN, 26));
        String st = style == null || style.isBlank() ? "realistic" : style;
        g.drawString("style: " + st, w / 2 - 100, h / 2 + 40);
        g.dispose();
        ImageIO.write(img, "png", out.toFile());
    }
}
