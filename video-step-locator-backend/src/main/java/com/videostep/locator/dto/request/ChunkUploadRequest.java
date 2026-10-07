package com.videostep.locator.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
public class ChunkUploadRequest {
    @NotBlank(message = "uploadId不能为空")
    private String uploadId;

    @NotNull(message = "分片索引不能为空")
    private Integer chunkIndex;

    @NotNull(message = "分片文件不能为空")
    private MultipartFile file;
}
