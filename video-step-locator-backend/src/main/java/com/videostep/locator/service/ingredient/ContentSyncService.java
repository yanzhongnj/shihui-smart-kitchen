package com.videostep.locator.service.ingredient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videostep.locator.entity.RecipeIngredient;
import com.videostep.locator.entity.RecipeMetadata;
import com.videostep.locator.repository.RecipeIngredientRepository;
import com.videostep.locator.repository.RecipeMetadataRepository;
import com.videostep.locator.service.PrecisionScoreService;
import com.videostep.locator.service.RecipeProvenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 开源内容源同步（P1，设计文档 §5）。
 * 当前内容源：HowToCook 开源菜谱库（Anduin2017/HowToCook，MIT，369 道中文菜谱 markdown）。
 * 流程：git clone/pull → 遍历 dishes/ 下 .md → 规则解析（标题/食材/步骤/卡路里/时长）→
 * 结构化落库 recipes（source=ARTICLE_LINK + source_url 溯源 + sourceType=open_source 公开可见）
 * → 写 recipe_ingredients + recipe_sources 流水 → source_url 唯一去重（增量）。
 * 解析器多级容错：无食材/无步骤文件跳过；解析失败不影响整体（失败保留上次索引）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContentSyncService {

    private final RecipeMetadataRepository recipeRepository;
    private final RecipeIngredientRepository ingredientRepository;
    private final RecipeProvenanceService provenanceService;
    private final PrecisionScoreService precisionScoreService;
    private final ObjectMapper objectMapper;

    @Value("${content-sync.howtocook.repo:https://github.com/Anduin2017/HowToCook.git}")
    private String repoUrl;

    @Value("${content-sync.howtocook.local-dir:data/howtocook}")
    private String localDir;

    /** 源文件 URL 前缀（溯源） */
    private static final String RAW_PREFIX = "https://github.com/Anduin2017/HowToCook/blob/master/";
    private static final String EXTRACTOR_VERSION = "howtocook-md-v1";

    /** 步骤区标题（命中即进入步骤收集） */
    private static final List<String> STEP_HEADERS = List.of("操作", "步骤", "做法", "烹饪步骤", "制作步骤");
    /** 食材区标题（命中即进入食材收集） */
    private static final List<String> INGREDIENT_HEADERS = List.of("计算", "必备原料和工具", "食材", "材料", "原料");

    private static final Pattern CALORIE = Pattern.compile("预估卡路里[:：]\\s*(\\d+)\\s*大卡");
    private static final Pattern DURATION_MIN = Pattern.compile("大约\\s*(\\d+)\\s*分钟");
    private static final Pattern DURATION_HOUR = Pattern.compile("大约\\s*(\\d+)\\s*小时");

    // ---- 同步状态（内存态；重启清空，可手动重跑） ----
    private volatile boolean running = false;
    private volatile String lastRunAt = null;
    private volatile int imported = 0;
    private volatile int skipped = 0;
    private volatile int failed = 0;
    private volatile String lastResult = null;
    private volatile String lastError = null;

    /** 同步状态（设计 §7 GET /api/content-sync/status）。imported 为 DB 持久化总数（重启后仍正确）；其余为最近一次运行（内存态）。 */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", "howtocook");
        m.put("running", running);
        m.put("lastRunAt", lastRunAt);
        m.put("imported", dbImportedCount());
        m.put("lastRunImported", imported);
        m.put("skipped", skipped);
        m.put("failed", failed);
        m.put("lastResult", lastResult);
        m.put("lastError", lastError);
        return m;
    }

    /** DB 中已导入的 HowToCook 菜谱数（持久化，跨重启准确） */
    private long dbImportedCount() {
        return recipeRepository.selectList(null).stream()
                .filter(r -> EXTRACTOR_VERSION.equals(r.getExtractorVersion())).count();
    }

    /** 内容源列表（设计 §7 GET /api/content-sync/sources） */
    public List<Map<String, Object>> sources() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("source", "howtocook");
        s.put("name", "HowToCook 开源菜谱库");
        s.put("repo", repoUrl);
        s.put("license", "MIT");
        s.put("extractorVersion", EXTRACTOR_VERSION);
        s.put("imported", dbImportedCount());
        out.add(s);
        return out;
    }

    /** 每周日 04:00 自动同步（配置 content-sync.howtocook.schedule-cron；失败保留上次索引） */
    @Scheduled(cron = "${content-sync.howtocook.schedule-cron:0 0 4 * * 0}")
    public void scheduledSync() {
        try {
            runHowToCook();
        } catch (Exception e) {
            log.warn("定时内容同步失败: {}", e.getMessage());
        }
    }

    /** 手动触发 HowToCook 同步（幂等：source_url 去重，已导入跳过） */
    public synchronized Map<String, Object> runHowToCook() {
        if (running) throw new IllegalStateException("内容同步正在进行中，请稍后再试");
        running = true;
        imported = skipped = failed = 0;
        lastError = null;
        lastResult = null;
        List<String> sampleErrors = new ArrayList<>();
        try {
            Path root = ensureRepo();
            List<Path> mds = findMdFiles(root);
            for (Path p : mds) {
                try {
                    importOne(p, root);
                } catch (Exception e) {
                    failed++;
                    if (sampleErrors.size() < 5) {
                        sampleErrors.add(rel(p, root) + ": " + e.getMessage());
                    }
                }
            }
            lastResult = "同步完成：新增 " + imported + "，跳过(已存在/非菜谱) " + skipped
                    + "，失败 " + failed + (sampleErrors.isEmpty() ? ""
                    : "；失败示例：" + String.join(" | ", sampleErrors));
            log.info("HowToCook 同步完成：新增 {} 跳过 {} 失败 {}，共 {} 个 md", imported, skipped, failed, mds.size());
        } catch (Exception e) {
            lastError = e.getMessage();
            log.warn("HowToCook 同步失败: {}", e.getMessage());
        } finally {
            running = false;
            lastRunAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        return status();
    }

    // ---------------- 仓库与文件收集 ----------------

    /** 确保本地仓库存在（git 网络抖动时回退使用本地已有仓库，设计 §12"同步失败保留上次索引"） */
    private Path ensureRepo() throws Exception {
        Path dir = Paths.get(localDir).toAbsolutePath();
        if (Files.isDirectory(dir.resolve(".git"))) {
            try {
                runCommand("git", "-C", dir.toString(), "pull", "--depth", "1");
            } catch (Exception e) {
                log.warn("git pull 失败，使用本地已有仓库继续: {}", e.getMessage());
            }
        } else {
            try {
                Files.createDirectories(dir.getParent());
                runCommand("git", "clone", "--depth", "1", repoUrl, dir.toString());
            } catch (Exception e) {
                if (Files.isDirectory(dir.resolve("dishes"))) {
                    log.warn("git clone 失败但本地已有仓库，使用本地: {}", e.getMessage());
                } else {
                    throw new IllegalStateException("无法获取 HowToCook 仓库: " + e.getMessage());
                }
            }
        }
        return dir;
    }

    private List<Path> findMdFiles(Path root) throws Exception {
        Path dishes = root.resolve("dishes");
        if (!Files.isDirectory(dishes)) dishes = root;
        try (Stream<Path> s = Files.walk(dishes)) {
            return s.filter(p -> p.toString().endsWith(".md"))
                    .filter(this::isRecipeFile)
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    /** 跳过 README/模板/非菜谱文件 */
    private boolean isRecipeFile(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        return !(name.startsWith("readme") || name.startsWith("template") || name.contains("_index"));
    }

    private String rel(Path p, Path root) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    // ---------------- 单文件导入 ----------------

    private void importOne(Path p, Path root) throws Exception {
        String relPath = rel(p, root);
        String sourceUrl = RAW_PREFIX + relPath;
        // 去重：source_url 唯一（增量更新，幂等）
        if (recipeRepository.findBySourceUrl(sourceUrl) != null) {
            skipped++;
            return;
        }
        String content = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        ParsedRecipe pr = parse(content);
        if (pr.ingredients.isEmpty() || pr.steps.isEmpty()) {
            skipped++;
            log.debug("跳过非菜谱或无食材/步骤: {}", relPath);
            return;
        }
        // 评分：HowToCook 多为定性描述 → 默认 HOME；provenanceOk=true（有 source_url 溯源）
        BigDecimal score = precisionScoreService.compute(pr.ingredients, pr.steps, pr.calories != null, true);
        RecipeMetadata rm = RecipeMetadata.builder()
                .title(pr.title)
                .mainIngredients(joinNames(pr.ingredients))
                .source(RecipeMetadata.SOURCE_ARTICLE_LINK)
                .sourceUrl(sourceUrl)
                .refId((long) Math.abs(sourceUrl.hashCode()))
                .extractorVersion(EXTRACTOR_VERSION)
                .extractorEngine("howtocook-md")
                .extractedAt(LocalDateTime.now())
                .sourceType("open_source")
                .recipeType(precisionScoreService.deriveType(score))
                .precisionScore(score)
                .calories(pr.calories)
                .estimatedTime(pr.estimatedTime)
                .ingredientsJson(writeJson(pr.ingredients))
                .stepsJson(writeJson(pr.steps))
                .rawDigest(pr.rawDigest)
                .confidence(BigDecimal.valueOf(0.7))
                .build();
        recipeRepository.insert(rm);
        saveIngredients(rm.getId(), pr.ingredients);
        provenanceService.record(rm);
        imported++;
        log.info("导入菜谱: {} ({} kcal, {}min) <- {}", rm.getTitle(), pr.calories, pr.estimatedTime, relPath);
    }

    private String joinNames(List<Map<String, Object>> ingredients) {
        return ingredients.stream()
                .map(i -> String.valueOf(i.getOrDefault("name", "")))
                .filter(s -> !s.isBlank())
                .distinct()
                .limit(8)
                .collect(Collectors.joining("、"));
    }

    private void saveIngredients(Long recipeId, List<Map<String, Object>> ingredients) {
        int seq = 1;
        for (Map<String, Object> ig : ingredients) {
            RecipeIngredient row = RecipeIngredient.builder()
                    .recipeId(recipeId)
                    .seq(seq++)
                    .name(str(ig.get("name")))
                    .amount(numOf(ig.get("amount")))
                    .unit(str(ig.get("unit")))
                    .isMain(1)
                    .note(str(ig.get("note")))
                    .build();
            try {
                ingredientRepository.insert(row);
            } catch (Exception e) {
                log.debug("ingredient insert skip {}: {}", row.getName(), e.getMessage());
            }
        }
    }

    // ---------------- 规则解析（多级容错） ----------------

    static class ParsedRecipe {
        String title;
        Integer calories;
        Integer estimatedTime;
        String rawDigest;
        List<Map<String, Object>> ingredients = new ArrayList<>();
        List<Map<String, Object>> steps = new ArrayList<>();
    }

    ParsedRecipe parse(String content) {
        ParsedRecipe pr = new ParsedRecipe();
        pr.rawDigest = content.length() > 200 ? content.substring(0, 200) : content;
        Matcher cm = CALORIE.matcher(content);
        if (cm.find()) pr.calories = Integer.valueOf(cm.group(1));
        Matcher dm = DURATION_MIN.matcher(content);
        if (dm.find()) pr.estimatedTime = Integer.valueOf(dm.group(1));
        else {
            Matcher hm = DURATION_HOUR.matcher(content);
            if (hm.find()) pr.estimatedTime = Integer.valueOf(hm.group(1)) * 60;
        }

        String section = "";   // 当前二级标题区
        boolean inSteps = false;
        for (String line : content.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isBlank()) continue;
            if (t.startsWith("# ") && pr.title == null) {
                pr.title = cleanTitle(t.substring(2));
                continue;
            }
            if (t.startsWith("## ")) {
                section = t.substring(3).trim();
                inSteps = isStepSection(section);
                continue;
            }
            if (t.startsWith("### ") || t.startsWith("#### ")) continue;
            boolean bullet = t.startsWith("* ") || t.startsWith("- ") || t.startsWith("+ ");
            String body = bullet ? t.substring(2).trim() : t;
            if (inSteps && (bullet || looksLikeStep(body))) {
                // 步骤：编号或 bullet 行
                if (!body.isEmpty() && !body.startsWith("如果您遵循")) {
                    pr.steps.add(stepOf(body));
                }
            } else if (section.equals("计算") || isIngredientSection(section)) {
                if (bullet) {
                    pr.ingredients.add(ingredientOf(body));
                }
            }
        }
        if (pr.title == null) pr.title = "未命名菜谱";
        return pr;
    }

    private String cleanTitle(String raw) {
        String t = raw.trim();
        for (String suf : new String[]{"的做法", "做法"}) {
            if (t.endsWith(suf)) { t = t.substring(0, t.length() - suf.length()).trim(); break; }
        }
        return t.isBlank() ? raw.trim() : t;
    }

    private boolean isStepSection(String section) {
        for (String h : STEP_HEADERS) {
            if (section.contains(h)) return true;
        }
        return false;
    }

    private boolean isIngredientSection(String section) {
        for (String h : INGREDIENT_HEADERS) {
            if (section.contains(h)) return true;
        }
        return false;
    }

    private boolean looksLikeStep(String t) {
        // 编号步骤：1. / 1、 / ①
        return t.matches("^\\d+[.、)].*");
    }

    /** 步骤行 → {summary, detail} */
    private Map<String, Object> stepOf(String body) {
        String t = body.replaceAll("^\\d+[.、)]\\s*", "").trim();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("summary", t.length() > 24 ? t.substring(0, 24) + "…" : t);
        m.put("detail", t);
        return m;
    }

    private static final Pattern AMOUNT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([a-zA-Z]+|[\\u4e00-\\u9fa5]{1,3})");

    /** 食材行 → {name, amount, unit, note}。多级容错：无数字 → 定性（amount=null）。 */
    Map<String, Object> ingredientOf(String body) {
        String t = body.trim().replaceAll("[。；;、]$", "");
        Map<String, Object> m = new LinkedHashMap<>();
        Matcher mt = AMOUNT.matcher(t);
        if (mt.find()) {
            String name = t.substring(0, mt.start()).trim()
                    .replaceAll("^[,，、:\\- ]+|[,，、:\\- ]+$", "").trim();
            if (name.length() <= 25 && !name.isEmpty()) {
                m.put("name", name);
                m.put("amount", numOf(mt.group(1)));
                m.put("unit", normalizeUnit(mt.group(2)));
                String note = t.substring(mt.end()).trim();
                m.put("note", note.isEmpty() ? null : note);
                return m;
            }
        }
        // 定性食材（无数量 / 描述过长）
        m.put("name", t.length() > 25 ? t.substring(0, 25) + "…" : t);
        m.put("amount", null);
        m.put("unit", null);
        m.put("note", null);
        return m;
    }

    private String normalizeUnit(String u) {
        if (u == null) return null;
        return switch (u) {
            case "克", "g", "G" -> "g";
            case "千克", "kg", "KG" -> "kg";
            case "毫升", "ml", "mL", "ML" -> "ml";
            default -> u;
        };
    }

    // ---------------- 工具 ----------------

    private void runCommand(String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("git 命令超时: " + cmd[0]);
        }
        if (p.exitValue() != 0) {
            String msg = out.length() > 300 ? out.substring(0, 300) : out.toString();
            throw new IllegalStateException("git 命令失败(" + cmd[0] + "): " + msg.trim());
        }
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

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    private BigDecimal numOf(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        String s = String.valueOf(o).trim();
        try { return new BigDecimal(s); } catch (Exception e) { return null; }
    }
}
