package com.videostep.locator.service.xunwei;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.videostep.locator.service.aiconfig.AiConfigRefreshable;
import com.videostep.locator.service.aiconfig.AiProviderConfigSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「寻味」拍照识食材（视觉 LLM，OpenAI 兼容 chat/completions + image_url）。
 *
 * 视觉模型端点可配置（`xunwei.vision.*`）：
 * - 默认 DashScope Qwen-VL（base-url=https://dashscope.aliyuncs.com/compatible-mode/v1, model=qwen-vl-plus），
 *   需设置 DASHSCOPE_API_KEY；
 * - 也可指向自建 DeepSeek-VL2 等 OpenAI 兼容端点（XUNWEI_VISION_BASE_URL / XUNWEI_VISION_MODEL /
 *   XUNWEI_VISION_API_KEY）。注意：DeepSeek 官方 API（api.deepseek.com）不支持图片输入，无法直接用于视觉。
 * - 未配置 key 或调用失败时抛 {@link XunweiVisionException}（controller 转友好提示，不阻塞寻味其它功能）。
 */
@Service
@Slf4j
public class XunweiVisionService implements AiConfigRefreshable {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    @Value("${xunwei.vision.api-key:}")
    private volatile String apiKey;

    @Value("${xunwei.vision.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private volatile String baseUrl;

    @Value("${xunwei.vision.model:qwen-vl-plus}")
    private volatile String model;

    @Value("${xunwei.vision.timeout-ms:30000}")
    private volatile long timeoutMs;

    /** 调试用：为 true 时不调视觉模型，返回固定示例食材（便于无 key 时联调 UI/闭环流程） */
    @Value("${xunwei.vision.mock:false}")
    private volatile boolean mock;

    public XunweiVisionService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String providerKey() {
        return "vision.qwen-vl";
    }

    /** 连通性测试：校验 key 可访问性（完整识别需图片输入，测试代价高，仅校验鉴权配置） */
    @Override
    public boolean testConnection() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 配置中心热更新：下次调用即用新值（含 mock 开关） */
    @Override
    public void refreshConfig(AiProviderConfigSnapshot cfg) {
        if (cfg == null) return;
        this.apiKey = cfg.getApiKey();
        this.baseUrl = cfg.getEndpoint();
        this.model = cfg.getModel();
        this.timeoutMs = cfg.getExtraLong("timeout_ms", this.timeoutMs);
        this.mock = cfg.isMockEnabled();
        log.info("XunweiVisionService 配置已热更新: model={}, mock={}, hasKey={}",
                model, mock, apiKey != null && !apiKey.isBlank());
    }

    /** 拍照识别 → 食材列表。未配置/调用失败抛 {@link XunweiVisionException}。 */
    public List<String> recognizeIngredients(byte[] imageBytes, String filename) {
        if (mock) {
            log.info("Xunwei vision MOCK mode: return sample ingredients");
            return List.of("西红柿", "鸡蛋", "面粉");
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new XunweiVisionException("图片内容为空，请重新拍照或选择照片");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new XunweiVisionException(
                    "视觉识别未配置：请在后端环境设置 ALIYUN_ASR_APP_KEY（或 DASHSCOPE_API_KEY / XUNWEI_VISION_API_KEY），"
                    + "或设置 XUNWEI_VISION_BASE_URL/MODEL 指向自建 DeepSeek-VL2 等视觉端点");
        }
        try {
            String mime = mimeOf(filename);
            String dataUrl = "data:" + mime + ";base64,"
                    + Base64.getEncoder().encodeToString(imageBytes);

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            ArrayNode arr = body.putArray("messages");
            ObjectNode msg = arr.addObject();
            msg.put("role", "user");
            ArrayNode content = msg.putArray("content");
            content.addObject().put("type", "text").put("text", VISION_PROMPT);
            content.addObject().put("type", "image_url")
                    .putObject("image_url").put("url", dataUrl);
            body.put("temperature", 0.2);
            body.put("stream", false);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("Xunwei vision HTTP {} body={}", response.statusCode(),
                        response.body().length() > 300 ? response.body().substring(0, 300) : response.body());
                throw new XunweiVisionException(
                        "视觉识别调用失败 (HTTP " + response.statusCode() + ")：请检查视觉模型 key/端点配置");
            }
            JsonNode root = objectMapper.readTree(response.body());
            String contentText = root.path("choices").path(0).path("message").path("content").asText("");
            List<String> ingredients = parseIngredients(contentText);
            if (ingredients.isEmpty()) {
                log.warn("Xunwei vision returned no ingredients: {}", contentText);
            }
            return ingredients;
        } catch (XunweiVisionException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Xunwei vision error", e);
            throw new XunweiVisionException("视觉识别失败：" + e.getMessage());
        }
    }

    /**
     * 拍照识别菜品（多菜，列表式）：识别图片中的所有菜品，返回 [{dishName, confidence}]（按置信度降序）。
     * P0 不做 bbox 定位（预留字段位，bbox 结构后续再加）；MOCK 返回示例多菜。
     * 未配置/调用失败抛 {@link XunweiVisionException}。
     */
    public List<Map<String, Object>> recognizeDishes(byte[] imageBytes, String filename) {
        if (mock) {
            log.info("Xunwei vision MOCK mode: return sample dishes");
            List<Map<String, Object>> mock = new ArrayList<>();
            mock.add(Map.of("dishName", "番茄炒蛋", "confidence", 0.92));
            mock.add(Map.of("dishName", "清蒸鲈鱼", "confidence", 0.78));
            return mock;
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new XunweiVisionException("图片内容为空，请重新拍照或选择照片");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new XunweiVisionException(
                    "视觉识别未配置：请在后端环境设置 ALIYUN_ASR_APP_KEY（或 DASHSCOPE_API_KEY / XUNWEI_VISION_API_KEY），"
                    + "或设置 XUNWEI_VISION_BASE_URL/MODEL 指向自建 DeepSeek-VL2 等视觉端点");
        }
        try {
            String mime = mimeOf(filename);
            String dataUrl = "data:" + mime + ";base64,"
                    + Base64.getEncoder().encodeToString(imageBytes);

            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            ArrayNode arr = body.putArray("messages");
            ObjectNode msg = arr.addObject();
            msg.put("role", "user");
            ArrayNode content = msg.putArray("content");
            content.addObject().put("type", "text").put("text", DISH_PROMPT);
            content.addObject().put("type", "image_url")
                    .putObject("image_url").put("url", dataUrl);
            body.put("temperature", 0.2);
            body.put("stream", false);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("Xunwei vision dish HTTP {} body={}", response.statusCode(),
                        response.body().length() > 300 ? response.body().substring(0, 300) : response.body());
                throw new XunweiVisionException(
                        "视觉识别调用失败 (HTTP " + response.statusCode() + ")：请检查视觉模型 key/端点配置");
            }
            JsonNode root = objectMapper.readTree(response.body());
            String contentText = root.path("choices").path(0).path("message").path("content").asText("");
            List<Map<String, Object>> dishes = parseDishes(contentText);
            if (dishes.isEmpty()) {
                log.warn("Xunwei vision returned no dishes: {}", contentText);
            }
            return dishes;
        } catch (XunweiVisionException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Xunwei vision dish error", e);
            throw new XunweiVisionException("视觉识别失败：" + e.getMessage());
        }
    }

    static final String DISH_PROMPT =
            "你是菜品识别助手。请仔细识别图片中出现的所有菜品（整道菜，如 鱼香肉丝、番茄炒蛋、清蒸鲈鱼），"
            + "输出 JSON 数组，不要输出任何其它文字。格式：[{\"dishName\":\"菜名\",\"confidence\":0.9},...]。"
            + "confidence 为 0-1 的识别置信度，按可见度从高到低排序；若图片不是菜品或无法识别，输出 []。";

    /** 从 LLM 回复中提取菜品 JSON 数组（容忍代码块/前后文字） */
    private List<Map<String, Object>> parseDishes(String text) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String t = text.trim();
        int start = t.indexOf('[');
        int end = t.lastIndexOf(']');
        if (start < 0 || end <= start) return out;
        try {
            JsonNode arr = objectMapper.readTree(t.substring(start, end + 1));
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    String name = n.path("dishName").asText("").trim();
                    if (name.isEmpty()) continue;
                    double conf = n.path("confidence").asDouble(0.5);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("dishName", name);
                    m.put("confidence", Math.max(0, Math.min(1.0, conf)));
                    out.add(m);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static final String VISION_PROMPT =
            "你是食材识别助手。请仔细识别图片中出现的所有食材（食物原料、蔬菜、肉类、水果、调味料等），"
            + "只输出 JSON 数组，不要输出任何其它文字。格式：[\"食材1\",\"食材2\",\"食材3\",...]。"
            + "若图片不是食材或无法识别，输出 []。";

    /** 从 LLM 回复中提取 JSON 数组（容忍代码块/前后文字）。 */
    private List<String> parseIngredients(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String t = text.trim();
        int start = t.indexOf('[');
        int end = t.lastIndexOf(']');
        if (start < 0 || end <= start) return out;
        try {
            JsonNode arr = objectMapper.readTree(t.substring(start, end + 1));
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    String v = n.asText("").trim();
                    if (!v.isEmpty()) out.add(v);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private String mimeOf(String filename) {
        if (filename == null) return "image/jpeg";
        String n = filename.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }

    /**
     * 成品拍照纠偏：双图（用户实拍成品图 + 目标成品参照图）对比 → 结构化纠偏报告。
     * 第 1 张为用户实拍（待评价），第 2 张为目标成品参照（标准样）——实拍放首图，避免视觉模型把首图误当目标参照。
     * 返回 {@code {summary, dimensions:[{name,conclusion,severity,advice}]}}。
     * 未配置/调用失败抛 {@link XunweiVisionException}。
     */
    public Map<String, Object> correctDish(byte[] userImage, String userFilename,
                                           byte[] refImage, String refFilename, String dishName) {
        if (mock) {
            log.info("Xunwei vision MOCK mode: return sample correction report");
            return mockCorrectionReport();
        }
        if (userImage == null || userImage.length == 0) {
            throw new XunweiVisionException("图片内容为空，请重新拍照或选择照片");
        }
        if (refImage == null || refImage.length == 0) {
            throw new XunweiVisionException("目标成品参照图为空，无法对比");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new XunweiVisionException(
                    "视觉识别未配置：请在后端环境设置 ALIYUN_ASR_APP_KEY（或 DASHSCOPE_API_KEY / XUNWEI_VISION_API_KEY），"
                    + "或设置 XUNWEI_VISION_BASE_URL/MODEL 指向自建 DeepSeek-VL2 等视觉端点");
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            ArrayNode arr = body.putArray("messages");
            ObjectNode msg = arr.addObject();
            msg.put("role", "user");
            ArrayNode content = msg.putArray("content");
            String prompt = (dishName == null || dishName.isBlank())
                    ? CORRECT_PROMPT.replace("{dishName}", "这道菜")
                    : CORRECT_PROMPT.replace("{dishName}", dishName);
            content.addObject().put("type", "text").put("text", prompt);
            // 第 1 张：你的成品实拍图（待评价）——放首图，顺应模型「首图=评价对象」的倾向
            content.addObject().put("type", "image_url").putObject("image_url")
                    .put("url", "data:" + mimeOf(userFilename) + ";base64,"
                            + Base64.getEncoder().encodeToString(userImage));
            // 第 2 张：目标成品参照图（标准样）
            content.addObject().put("type", "image_url").putObject("image_url")
                    .put("url", "data:" + mimeOf(refFilename) + ";base64,"
                            + Base64.getEncoder().encodeToString(refImage));
            log.debug("correctDish slot1(user)={}({}B) slot2(ref)={}({}B)",
                    userFilename, userImage.length, refFilename, refImage.length);
            body.put("temperature", 0.2);
            body.put("stream", false);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("Xunwei vision correct HTTP {} body={}", response.statusCode(),
                        response.body().length() > 300 ? response.body().substring(0, 300) : response.body());
                throw new XunweiVisionException(
                        "视觉对比调用失败 (HTTP " + response.statusCode() + ")：请检查视觉模型 key/端点配置");
            }
            JsonNode root = objectMapper.readTree(response.body());
            String contentText = root.path("choices").path(0).path("message").path("content").asText("");
            Map<String, Object> report = parseCorrectReport(contentText);
            if (((List<?>) report.get("dimensions")).isEmpty()) {
                log.warn("Xunwei vision correct returned no dimensions: {}", contentText);
            }
            return report;
        } catch (XunweiVisionException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Xunwei vision correct error", e);
            throw new XunweiVisionException("成品对比失败：" + e.getMessage());
        }
    }

    static final String CORRECT_PROMPT =
            "你是专业的成品菜对比评测助手。我会给你两张图片，角色如下：\n"
            + "- 第 1 张 =【你的成品实拍图】（待评价的那张）\n"
            + "- 第 2 张 =【目标成品参照图】（标准样 / 目标效果，通常摆盘更完整、更像标准成品）\n"
            + "菜名：{dishName}。\n"
            + "请以第 2 张（目标成品参照）为基准，评价第 1 张（你的成品）与它的差距。\n"
            + "【角色自检】若你根据内容判断两张图的角色与上述不符（例如第 1 张看起来是摆盘标准的成品图、第 2 张才是随手拍的成品），请以内容为准：更像标准成品图的那张视为目标参照，另一张视为你的成品——并在 summary 开头注明（如 \"按内容识别，第 X 张为目标参照\"）。\n"
            + "请只输出一个合法 JSON 对象，不要输出任何其它文字。格式：\n"
            + "{\"summary\":\"一句话总评（先说做得好的地方，再说可改进点，正面措辞）\","
            + "\"dimensions\":[{\"name\":\"色泽\",\"conclusion\":\"偏深\",\"severity\":\"medium\",\"advice\":\"可操作的改进建议\"},...]}\n"
            + "dimensions 固定且顺序固定为这 5 项：色泽、火候、收汁、成型摆盘、总体完成度。\n"
            + "- name：固定维度名；conclusion：与目标参照相比的结论（如\"基本一致\"\"偏深\"\"略浅\"\"偏过\"\"欠火候\"\"收太干\"\"汤汁偏多\"\"85%\"等）\n"
            + "- severity：none|light|medium|heavy（none=无差异；总体完成度此项 severity 填\"none\"）\n"
            + "- advice：可操作的改进建议（量化：时间/调料用量/步骤顺序）；无差异则填\"保持现有做法即可\"\n"
            + "判断约束：颜色深浅要相对目标参照表述，不要裸判绝对颜色；火候、收汁是过程属性，只能依据照片可见线索（光泽、质地、边缘焦化、汤汁观感）推断，措辞用\"推断/可能\"；"
            + "若实拍图不是菜品或无法与参照图对比，summary 说明并建议重拍，dimensions 返回空数组。";

    /** 从 LLM 回复中提取纠偏报告 JSON 对象（容忍代码块/前后文字）。 */
    private Map<String, Object> parseCorrectReport(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", "");
        out.put("dimensions", new ArrayList<>());
        if (text == null || text.isBlank()) return out;
        String t = text.trim();
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start < 0 || end <= start) return out;
        try {
            JsonNode obj = objectMapper.readTree(t.substring(start, end + 1));
            if (!obj.isObject()) return out;
            out.put("summary", obj.path("summary").asText(""));
            List<Map<String, Object>> dims = new ArrayList<>();
            JsonNode arr = obj.path("dimensions");
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", n.path("name").asText(""));
                    m.put("conclusion", n.path("conclusion").asText(""));
                    m.put("severity", n.path("severity").asText("none"));
                    m.put("advice", n.path("advice").asText(""));
                    if (!((String) m.get("name")).isBlank()) dims.add(m);
                }
            }
            out.put("dimensions", dims);
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 联调用：不调模型返回示例纠偏报告。 */
    private Map<String, Object> mockCorrectionReport() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", "整体完成度不错，色泽比参考图略深一点，酱汁建议再留一点汤汁，其余基本一致。");
        List<Map<String, Object>> dims = new ArrayList<>();
        dims.add(Map.of("name", "色泽", "conclusion", "偏深", "severity", "medium",
                "advice", "下次收火可提前 30 秒，或减少生抽用量"));
        dims.add(Map.of("name", "火候", "conclusion", "略过（推断）", "severity", "light",
                "advice", "边缘焦化较明显，可适当调低火候"));
        dims.add(Map.of("name", "收汁", "conclusion", "收太干（推断）", "severity", "heavy",
                "advice", "留一点汤汁再关火，利用余温收汁"));
        dims.add(Map.of("name", "成型摆盘", "conclusion", "基本一致", "severity", "none",
                "advice", "保持现有做法即可"));
        dims.add(Map.of("name", "总体完成度", "conclusion", "85%", "severity", "none",
                "advice", "继续保持，注意火候与收汁"));
        out.put("dimensions", dims);
        return out;
    }
}
