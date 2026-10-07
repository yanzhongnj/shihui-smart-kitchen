package com.videostep.locator.config;

import com.videostep.locator.websocket.DownloadProgressHandler;
import com.videostep.locator.websocket.DeviceProgressHandler;
import com.videostep.locator.websocket.TaskProgressHandler;
import com.videostep.locator.websocket.TranscribeAudioHandler;
import com.videostep.locator.websocket.VideoGenProgressHandler;
import com.videostep.locator.websocket.VoiceAudioHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TaskProgressHandler taskProgressHandler;
    private final VoiceAudioHandler voiceAudioHandler;
    private final TranscribeAudioHandler transcribeAudioHandler;
    private final DownloadProgressHandler downloadProgressHandler;
    private final DeviceProgressHandler deviceProgressHandler;
    private final VideoGenProgressHandler videoGenProgressHandler;

    public WebSocketConfig(TaskProgressHandler taskProgressHandler,
                           VoiceAudioHandler voiceAudioHandler,
                           TranscribeAudioHandler transcribeAudioHandler,
                           DownloadProgressHandler downloadProgressHandler,
                           DeviceProgressHandler deviceProgressHandler,
                           VideoGenProgressHandler videoGenProgressHandler) {
        this.taskProgressHandler = taskProgressHandler;
        this.voiceAudioHandler = voiceAudioHandler;
        this.transcribeAudioHandler = transcribeAudioHandler;
        this.downloadProgressHandler = downloadProgressHandler;
        this.deviceProgressHandler = deviceProgressHandler;
        this.videoGenProgressHandler = videoGenProgressHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(taskProgressHandler, "/ws/tasks/{taskId}")
                .setAllowedOrigins("*");

        registry.addHandler(voiceAudioHandler, "/ws/voice/{sessionId}")
                .setAllowedOrigins("*");

        registry.addHandler(transcribeAudioHandler, "/ws/transcribe/{sessionId}")
                .setAllowedOrigins("*");

        registry.addHandler(downloadProgressHandler, "/ws/download/{taskId}")
                .setAllowedOrigins("*");

        registry.addHandler(deviceProgressHandler, "/ws/device/{deviceId}")
                .setAllowedOrigins("*");

        registry.addHandler(videoGenProgressHandler, "/ws/videogen/{creationId}")
                .setAllowedOrigins("*");
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(65536);
        container.setMaxBinaryMessageBufferSize(262144);
        container.setMaxSessionIdleTimeout(600000L);
        return container;
    }
}
