package com.videostep.locator.config;

import org.springframework.context.annotation.Configuration;

/**
 * AI 配置
 *
 * 默认使用 DeepSeek API（通过 video.processing.llm-* 配置）
 * 环境变量：LLM_API_KEY, LLM_BASE_URL, LLM_MODEL
 */
@Configuration
public class AiConfig {
}
