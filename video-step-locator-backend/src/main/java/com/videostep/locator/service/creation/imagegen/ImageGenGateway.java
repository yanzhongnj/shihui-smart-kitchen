package com.videostep.locator.service.creation.imagegen;

/**
 * AI 文生图网关抽象（仿 AIVideoGenGateway，酱汁效果图 P1）。
 * wan_image | mock。
 */
public interface ImageGenGateway {

    /** 供应商标识：wan_image | mock */
    String provider();

    /** 健康检查（降级依据；WanImage=校验 key，Mock=恒 true） */
    boolean testConnection();

    /** 是否产生真实计费（mock 免费） */
    boolean isBillable();

    /** 异步提交文生图任务（prompt 已含菜名+描述+风格） */
    ImageGenTaskRef submit(String prompt, String style);

    /** 轮询任务结果 */
    ImageGenResult poll(String taskRef);

    /** 把生成结果（URL/本地路径）拉到本地目标路径，返回本地绝对路径 */
    String download(String resultUrl, String localPath);
}
