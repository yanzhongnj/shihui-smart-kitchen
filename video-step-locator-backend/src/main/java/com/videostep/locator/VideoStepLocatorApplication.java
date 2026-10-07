package com.videostep.locator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 视频步骤定位后端系统 - 主启动类
 */
@SpringBootApplication
@EnableScheduling
public class VideoStepLocatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoStepLocatorApplication.class, args);
    }
}
