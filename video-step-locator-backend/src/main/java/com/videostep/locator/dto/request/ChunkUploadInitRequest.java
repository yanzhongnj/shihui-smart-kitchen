package com.videostep.locator.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ChunkUploadInitRequest {
    @NotBlank(message = "文件名不能为空")
    private String filename;

    @NotNull(message = "文件大小不能为空")
    private Long fileSize;

    /**
     * 全文件 MD5（可选，兼容两条上传链路）：
     * - Web 端：计算并提交 MD5 → merge 时严格校验，防损坏/秒传。
     * - APP 端：传空字符串 → merge 时跳过校验、回填计算值入库。
     */
    private String md5;

    @NotNull(message = "总分片数不能为空")
    private Integer totalChunks;
}
