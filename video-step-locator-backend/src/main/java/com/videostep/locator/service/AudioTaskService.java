package com.videostep.locator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.AudioTask;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.entity.Video;
import com.videostep.locator.entity.VideoStep;
import com.videostep.locator.exception.PipelineException;
import com.videostep.locator.repository.AudioTaskRepository;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.repository.VideoStepRepository;
import com.videostep.locator.service.PrecisionScoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 音频直传识别：手机上传音频 → 后端跑 Python 流水线（转录 + LLM 菜谱/步骤）→ 入库。
 */
@Service
@Slf4j
public class AudioTaskService {

    private final AudioTaskRepository taskRepository;
    private final RecipeMetadataRepository recipeMetadataRepository;
    private final VideoStepRepository stepRepository;
    private final VideoService videoService;
    private final ThreadPoolTaskExecutor executor;
    private final PrecisionScoreService precisionScoreService;
    private final RecipeProvenanceService provenanceService;

    public AudioTaskService(AudioTaskRepository taskRepository,
                            RecipeMetadataRepository recipeMetadataRepository,
                            VideoStepRepository stepRepository,
                            VideoService videoService,
                            @Qualifier("downloadExecutor") ThreadPoolTaskExecutor executor,
                            PrecisionScoreService precisionScoreService,
                            RecipeProvenanceService provenanceService) {
        this.taskRepository = taskRepository;
        this.recipeMetadataRepository = recipeMetadataRepository;
        this.stepRepository = stepRepository;
        this.videoService = videoService;
        this.executor = executor;
        this.precisionScoreService = precisionScoreService;
        this.provenanceService = provenanceService;
    }

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

    @Value("${video.storage.base-path}")
    private String basePath;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public AudioTask createFromUpload(Long userId, MultipartFile file) throws IOException {
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "audio.wav";
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "wav";
        Path dir = Path.of(basePath, "audio_upload");
        Files.createDirectories(dir);
        Path audioPath = dir.resolve(System.currentTimeMillis() + "_u" + userId + "." + ext);
        file.transferTo(audioPath);

        AudioTask task = AudioTask.builder()
                .userId(userId)
                .sourceType("audio_upload")
                .audioPath(audioPath.toString())
                .status(AudioTask.STATUS_QUEUED)
                .progress(0)
                .build();
        taskRepository.insert(task);
        execute(task);
        return task;
    }

    public List<AudioTask> list(Long userId) {
        return taskRepository.findByUserId(userId);
    }

    public AudioTask get(Long userId, Long id) {
        AudioTask t = taskRepository.selectById(id);
        if (t == null) throw new IllegalArgumentException("音频任务不存在");
        if (!t.getUserId().equals(userId)) throw new SecurityException("无权访问");
        return t;
    }

    private void execute(AudioTask task) {
        executor.execute(() -> {
            Long id = task.getId();
            task.setStatus(AudioTask.STATUS_RUNNING);
            taskRepository.updateById(task);

            try {
                String script = pipelineDir + "/scripts/pipeline.py";
                List<String> command = List.of(
                        pythonPath, script,
                        "--audio-only", task.getAudioPath(),
                        "--output", basePath,
                        "--llm-api-key", llmApiKey,
                        "--llm-base-url", llmBaseUrl,
                        "--llm-model", llmModel,
                        "--device", "cpu",
                        "--backend", "whisper",
                        "--extract-recipe");

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                log.info("Audio recipe task {}: {}", id, command);
                Process process = pb.start();

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), Charset.forName("GBK")))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[AudioTask-{}] {}", id, line);
                    }
                }
                int exit = process.waitFor();
                if (exit != 0) throw new PipelineException("音频识别失败 (exit=" + exit + ")");

                // 解析菜谱结果 JSON
                String stem = Path.of(task.getAudioPath()).getFileName().toString();
                int dot = stem.lastIndexOf('.');
                if (dot > 0) stem = stem.substring(0, dot);
                File recipeFile = new File(basePath + "/recipe/" + stem + "_recipe.json");
                if (!recipeFile.exists()) throw new PipelineException("菜谱结果文件不存在: " + recipeFile);
                Map<String, Object> data = objectMapper.readValue(recipeFile, Map.class);

                // 创建最小视频记录承载菜谱/步骤（无视频文件，仅展示用）
                String title = (String) data.getOrDefault("title", "菜谱");
                Video video = videoService.createVideo(task.getUserId(), null, title, "", 0L, "", "");

                // 保存菜谱元数据（统一模型 v0.2：音频直识别 → 归 VIDEO_LINK，无外部 URL，ref_id=video_id 溯源）
                RecipeMetadata rm = RecipeMetadata.builder()
                        .videoId(video.getId())
                        .title(title)
                        .summary(asStr(data.get("summary")))
                        .mainIngredients(joinList(data.get("main_ingredients")))
                        .auxiliaryIngredients(joinList(data.get("auxiliary_ingredients")))
                        .tasteTags(joinList(data.get("taste_tags")))
                        .allergens(joinList(data.get("allergens")))
                        .spiceLevel(intOf(data.get("spice_level")))
                        .cuisine(asStr(data.get("cuisine")))
                        .estimatedTime(intOf(data.get("estimated_time")))
                        .calories(intOf(data.get("calories")))
                        .rawLlmJson(objectMapper.writeValueAsString(data))
                        .build();
                rm.setSource(RecipeMetadata.SOURCE_VIDEO_LINK);
                rm.setRefId(video.getId());
                rm.setSourceUrl(null);
                rm.setExtractorVersion("pipeline-v2.1");
                rm.setExtractorEngine("whisper");
                rm.setExtractedAt(LocalDateTime.now());
                rm.setRawDigest(asStr(data.get("summary")));
                rm.setStepsJson(writeJson(data.get("steps")));
                List<Map<String, Object>> ingredients = parseIngredients(data);
                List<Map<String, Object>> scoredSteps = parseSteps(data.get("steps"));
                boolean nutritionOk = rm.getCalories() != null;
                boolean provenanceOk = false; // 音频无外部 URL，溯源不完整
                BigDecimal score = precisionScoreService.compute(ingredients, scoredSteps, nutritionOk, provenanceOk);
                rm.setPrecisionScore(score);
                rm.setRecipeType(precisionScoreService.deriveType(score));
                rm.setIngredientsJson(writeJson(ingredients));
                rm.setNutritionJson(rm.getCalories() != null ? "{\"calories\":" + rm.getCalories() + "}" : null);
                recipeMetadataRepository.insert(rm);
                provenanceService.record(rm);

                // 保存步骤（如有）
                Object stepsObj = data.get("steps");
                if (stepsObj instanceof List<?> steps) {
                    int idx = 1;
                    for (Object o : steps) {
                        if (!(o instanceof Map<?, ?> sm)) continue;
                        VideoStep step = VideoStep.builder()
                                .videoId(video.getId())
                                .stepIndex(idx++)
                                .summary(asStr(sm.get("summary")))
                                .originalText(asStr(sm.get("original_text")))
                                .startTime(num(sm.get("start_time")))
                                .endTime(num(sm.get("end_time")))
                                .confidence("medium")
                                .build();
                        stepRepository.insert(step);
                    }
                }

                task.setStatus(AudioTask.STATUS_COMPLETED);
                task.setProgress(100);
                task.setVideoId(video.getId());
                task.setCompletedAt(LocalDateTime.now());
                taskRepository.updateById(task);
                log.info("Audio task {} completed -> video {}", id, video.getId());

            } catch (Exception e) {
                log.error("Audio task {} failed: {}", id, e.getMessage(), e);
                task.setStatus(AudioTask.STATUS_FAILED);
                String msg = e.getMessage();
                task.setErrorMessage(msg != null && msg.length() > 2000 ? msg.substring(0, 2000) : msg);
                taskRepository.updateById(task);
            }
        });
    }

    private String asStr(Object o) { return o == null ? null : o.toString(); }

    private Integer intOf(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private BigDecimal num(Object o) {
        return o instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null;
    }

    private String joinList(Object o) {
        if (o == null) return null;
        if (o instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object x : list) {
                if (x == null) continue;
                if (sb.length() > 0) sb.append(',');
                sb.append(x);
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return o.toString();
    }

    private List<Map<String, Object>> parseIngredients(Map<String, Object> data) {
        List<Map<String, Object>> result = new ArrayList<>();
        Object ing = data.get("ingredients");
        if (ing instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Map<String, Object> norm = new LinkedHashMap<>();
                for (String k : new String[]{"name", "amount", "unit", "tolerance", "is_main", "note"}) {
                    if (m.containsKey(k)) norm.put(k, m.get(k));
                }
                result.add(norm);
            }
        }
        if (result.isEmpty() && data.get("main_ingredients") instanceof List<?> mains) {
            for (Object o : mains) {
                Map<String, Object> norm = new LinkedHashMap<>();
                norm.put("name", o);
                norm.put("is_main", 1);
                result.add(norm);
            }
        }
        return result;
    }

    private List<Map<String, Object>> parseSteps(Object stepsObj) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (stepsObj instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Map<String, Object> norm = new LinkedHashMap<>();
                for (String k : new String[]{"index", "summary", "detail", "timer_min", "timerMin", "temperature", "mode"}) {
                    if (m.containsKey(k)) norm.put(k, m.get(k));
                }
                result.add(norm);
            }
        }
        return result;
    }

    private String writeJson(Object o) {
        if (o == null) return null;
        try {
            String s = objectMapper.writeValueAsString(o);
            return "null".equals(s) ? null : s;
        } catch (Exception e) {
            return null;
        }
    }
}
