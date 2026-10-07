package com.videostep.locator.service.creation.videogen;

/**
 * AI 视频生成网关抽象（仿 DeviceGateway / VoiceRecognitionProvider 双引擎范式）。
 * wan | seedance | mock。
 */
public interface AIVideoGenGateway {

    /** 供应商标识：wan | seedance | mock */
    String provider();

    /** 健康检查（降级依据） */
    boolean testConnection();

    /** 异步提交视频生成任务 */
    VideoGenTaskRef submit(String prompt, int durationSec);

    /**
     * 异步提交视频生成任务（带展示标签，供 Mock 等引擎叠加步骤文字；默认忽略 label）。
     */
    default VideoGenTaskRef submit(String prompt, int durationSec, String label) {
        return submit(prompt, durationSec);
    }

    /** 轮询任务结果 */
    VideoGenResult poll(String taskRef);

    /** 把生成结果（URL/本地路径）拉到本地目标路径，返回本地绝对路径 */
    String download(String resultUrl, String localPath);
}
