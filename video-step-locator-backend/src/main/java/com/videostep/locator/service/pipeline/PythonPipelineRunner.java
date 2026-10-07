package com.videostep.locator.service.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.exception.PipelineException;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class PythonPipelineRunner {

    @Value("${video.processing.python-path}")
    private String pythonPath;

    @Value("${video.processing.pipeline-dir}")
    private String pipelineDir;

    @Value("${video.processing.llm-api-key}")
    private String llmApiKey;

    @Value("${video.processing.llm-base-url}")
    private String llmBaseUrl;

    @Value("${video.processing.llm-model}")
    private String llmModel;

    @Value("${video.processing.device}")
    private String device;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Pattern PHASE_PATTERN =
            Pattern.compile("\\[阶段(\\d)/4]\\s*(.+)");

    /** 运行中的流水线进程（按 videoId），用于暂停/取消任务时终止 */
    private final Map<Long, Process> runningProcesses = new ConcurrentHashMap<>();

    public CompletableFuture<PipelineResult> runPipeline(
            Long videoId,
            String videoPath,
            String outputDir,
            Consumer<PipelineProgress> progressCallback) {

        return CompletableFuture.supplyAsync(() -> {
            String scriptPath = pipelineDir + "/scripts/pipeline.py";
            List<String> command = List.of(
                    pythonPath, scriptPath,
                    "--input", videoPath,
                    "--output", outputDir,
                    "--whisper-model", "tiny",
                    "--llm-api-key", llmApiKey,
                    "--llm-base-url", llmBaseUrl,
                    "--llm-model", llmModel,
                    "--device", device,
                    "--extract-recipe",
                    "--log-dir", outputDir + "/logs"
            );

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(new File(pipelineDir));
            pb.redirectErrorStream(true);

            log.info("Starting pipeline for video {}: {}", videoId, command);
            Process process = null;
            try {
                process = pb.start();
                runningProcesses.put(videoId, process);

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), "GBK"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[Pipeline-{}] {}", videoId, line);
                        PipelineProgress progress = parseProgress(line);
                        if (progress != null && progressCallback != null) {
                            progressCallback.accept(progress);
                        }
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new PipelineException(
                            "Pipeline exited with code: " + exitCode);
                }

                return parseResult(videoId, videoPath, outputDir);

            } catch (Exception e) {
                throw new PipelineException(
                        "Pipeline execution failed for video " + videoId, e);
            } finally {
                if (process != null) runningProcesses.remove(videoId);
            }
        });
    }

    /**
     * 终止指定视频的流水线进程（用于暂停/取消任务）。
     */
    public void cancel(Long videoId) {
        Process process = runningProcesses.remove(videoId);
        if (process != null) {
            process.destroy();
            process.destroyForcibly();
            log.info("Pipeline process cancelled for video {}", videoId);
        }
    }

    private PipelineProgress parseProgress(String line) {
        Matcher matcher = PHASE_PATTERN.matcher(line);
        if (matcher.find()) {
            int phase = Integer.parseInt(matcher.group(1));
            String phaseName = matcher.group(2).trim();
            int progress = switch (phase) {
                case 1 -> 25;
                case 2 -> 50;
                case 3 -> 75;
                case 4 -> 90;
                default -> 0;
            };
            return new PipelineProgress(progress, phaseName);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private PipelineResult parseResult(Long videoId, String videoPath, String outputDir) {
        try {
            // Extract filename stem from video path (e.g., "1785205725444_cooking" from ".../1785205725444_cooking.mp4")
            String videoFileName = Paths.get(videoPath).getFileName().toString();
            String stem = videoFileName.contains(".") ? videoFileName.substring(0, videoFileName.lastIndexOf('.')) : videoFileName;
            File stepsFile = new File(outputDir + "/steps/" + stem + "_steps.json");
            if (!stepsFile.exists()) {
                throw new PipelineException("Steps file not found: " + stepsFile);
            }
            Map<String, Object> data = objectMapper.readValue(stepsFile, Map.class);

            PipelineResult result = new PipelineResult();
            result.setVideo((String) data.get("video"));
            result.setDuration((Number) data.get("duration"));
            result.setSteps((List<Map<String, Object>>) data.get("steps"));
            return result;

        } catch (Exception e) {
            throw new PipelineException(
                    "Failed to parse pipeline result for video " + videoId, e);
        }
    }

    @Data
    public static class PipelineProgress {
        private final int progress;
        private final String phase;

        public PipelineProgress(int progress, String phase) {
            this.progress = progress;
            this.phase = phase;
        }
    }

    @Data
    public static class PipelineResult {
        private String video;
        private Number duration;
        private List<Map<String, Object>> steps;
    }
}
