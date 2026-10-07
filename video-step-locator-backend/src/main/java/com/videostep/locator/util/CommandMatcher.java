package com.videostep.locator.util;

import com.videostep.locator.dto.response.AsrResult;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class CommandMatcher {

    /**
     * 阿里云 ASR（ITN）输出常带句号等标点（如「播放。」），且数字可能保留中文形式
     * （如「两倍速。」ITN 不转「两」）。因此：
     * - 匹配前去除空白 + 常见标点，保证锚定命令（播放/开始/继续）能命中；
     * - 数字类命令（第N步/快进N秒/后退N秒/N倍速）支持中文数字与阿拉伯数字两种写法。
     *
     * 顺序原则：带参数/具体的规则在前（循环/快进/后退/倍速），宽泛词在后
     * （下一步/上一步），避免被更宽泛的正则抢先。
     */
    private static final List<CommandPattern> PATTERNS = List.of(
        // 循环类（带参数优先）
        new CommandPattern("循环第\\s*(?<num>\\d+|[零一二两三四五六七八九十百千万]+)\\s*(?:步|段|个|次|遍)", Action.LOOP_STEP),
        new CommandPattern("(?:第\\s*(?<num>\\d+|[零一二两三四五六七八九十百千万]+)\\s*步)\\s*(?:循环|重复)", Action.LOOP_STEP),
        new CommandPattern("循环这(?:一)?(?:步|段|个|次|遍)", Action.LOOP_CURRENT),
        new CommandPattern("重复这(?:一)?(?:步|段|个|遍|次)", Action.LOOP_CURRENT),
        new CommandPattern("(?:当前|这一段|这段|这里)(?:步骤)?(?:循环|重复)", Action.LOOP_CURRENT),
        new CommandPattern("停止循环|取消循环|退出循环|别再循环|不循环了|关闭循环", Action.STOP_LOOP),
        // 重新开始
        new CommandPattern("从头|重新开始|重来|重头再来|从头再来|再来一遍|重新播放|重头开始|再来一次", Action.RESTART),
        // 秒数类（带参数）
        new CommandPattern("快进\\s*(?<sec>\\d+|[零一二两三四五六七八九十百千万]+)\\s*秒", Action.FORWARD),
        new CommandPattern("前进\\s*(?<sec>\\d+|[零一二两三四五六七八九十百千万]+)\\s*秒", Action.FORWARD),
        new CommandPattern("(?:往后|跳过|快跳|往后跳)\\s*(?<sec>\\d+|[零一二两三四五六七八九十百千万]+)\\s*秒", Action.FORWARD),
        new CommandPattern("(?:后退|倒退|回退|往前|往回)\\s*(?<sec>\\d+|[零一二两三四五六七八九十百千万]+)\\s*秒", Action.BACKWARD),
        // 倍速
        new CommandPattern("(?<speed>\\d+(?:\\.\\d+)?|[零一二两三四五六七八九十百千万]+)\\s*倍(?:速)?(?:播放|快进)?", Action.SPEED),
        // 步进类（宽泛词在后）
        new CommandPattern("(?:下一|下个|下一条|下一个|下)(?:步|个|段|条|步骤)?", Action.NEXT_STEP),
        new CommandPattern("(?:下一步骤|下一个步骤|下个步骤|下步骤|接着下一步|到下一步|往后(?:走)?一步)", Action.NEXT_STEP),
        new CommandPattern("(?:上一|上个|上一条|上一个|上)(?:步|个|段|条|步骤)?", Action.PREV_STEP),
        new CommandPattern("(?:上一步骤|上一个步骤|上个步骤|上步骤|回到上一步|回上一步|前一步|往前(?:走)?一步)", Action.PREV_STEP),
        // 播放/暂停（锚定或整句）
        new CommandPattern("^播放$|^开始$|^继续(?:播放|放)?$|^(?:接着放|接着播放|恢复播放|开始播放|继续播放|继续放)$", Action.PLAY),
        new CommandPattern("暂停|停一下|停止(?:播放)?|先暂停|暂停一下|停住|先停一下|别播了|先别播|不要再播", Action.PAUSE)
    );

    /** 运行时学习回填的同义规则（LLM 命中后写入，线程安全，防重复） */
    private final java.util.concurrent.CopyOnWriteArrayList<CommandPattern> learned = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 把 LLM 识别出的说法回填到规则表（Phase ③ 学习）。
     * 需要数字参数的动作（快进/后退/倍速/循环第N步）在缺少参数时不回填，
     * 避免学到"无参的快进"这类残缺规则。
     */
    public void learn(String text, String action, Map<String, Object> params) {
        if (text == null || text.isBlank() || action == null) return;
        String actionName = action.toUpperCase();
        Action act = null;
        for (Action a : Action.values()) {
            if (a.name().equals(actionName)) {
                act = a;
                break;
            }
        }
        if (act == null) return;
        // 需要数字参数的动作，参数缺失则不回填
        if ((act == Action.FORWARD || act == Action.BACKWARD)
                && (params == null || !params.containsKey("seconds"))) return;
        if (act == Action.SPEED && (params == null || !params.containsKey("speed"))) return;
        if (act == Action.LOOP_STEP && (params == null || !params.containsKey("stepIndex"))) return;

        String normalized = text.replaceAll("[\\s。，、！？；：,.!?;:'\"“”‘’「」·…\\-]+", "");
        if (normalized.isEmpty()) return;
        // 已覆盖则跳过（避免重复学习）
        for (CommandPattern cp : learned) {
            if (cp.getAction() == act && cp.getPattern().matcher(normalized).find()) return;
        }
        learned.add(new CommandPattern(Pattern.quote(normalized), act));
        log.info("CommandMatcher learned backfill: [{}] -> {}", normalized, actionName);
    }

    public AsrResult match(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        // 去除空白与常见中英文标点（阿里云 ITN 每条结果基本都带句号等标点）
        String normalized = text.replaceAll("[\\s。，、！？；：,.!?;:'\"“”‘’「」·…\\-]+", "");

        for (CommandPattern cp : PATTERNS) {
            Matcher matcher = cp.getPattern().matcher(normalized);
            if (matcher.find()) {
                return buildResult(text, cp, matcher);
            }
        }
        // 运行时学习回填的规则（等价的整句短语）
        for (CommandPattern cp : learned) {
            Matcher matcher = cp.getPattern().matcher(normalized);
            if (matcher.find()) {
                return AsrResult.builder()
                        .text(text)
                        .action(cp.getAction().name())
                        .params(new HashMap<>())
                        .confidence(0.8f)
                        .build();
            }
        }
        return null;
    }

    /** 构建匹配结果（从命名组提取 num/sec/speed 参数） */
    private AsrResult buildResult(String text, CommandPattern cp, Matcher matcher) {
        Map<String, Object> params = new HashMap<>();
        try {
            if (matcher.group("num") != null) {
                params.put("stepIndex", parseCnInt(matcher.group("num")));
            }
        } catch (IllegalArgumentException ignored) {
            // 该模式没有 num 命名组
        }
        try {
            if (matcher.group("sec") != null) {
                params.put("seconds", parseCnInt(matcher.group("sec")));
            }
        } catch (IllegalArgumentException ignored) {
            // 该模式没有 sec 命名组
        }
        try {
            if (matcher.group("speed") != null) {
                params.put("speed", parseCnFloat(matcher.group("speed")));
            }
        } catch (IllegalArgumentException ignored) {
            // 该模式没有 speed 命名组
        }

        return AsrResult.builder()
                .text(text)
                .action(cp.getAction().name())
                .params(params)
                .confidence(0.9f)
                .build();
    }

    /* ============ 中文数字支持 ============ */

    /** 阿拉伯数字或中文数字 → int（如 "10"→10，"十"→10，"两"→2，"一百二十"→120） */
    private static int parseCnInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return cnToArabic(s);
        }
    }

    /** 阿拉伯数字或中文数字 → float（倍速用，如 "1.5"→1.5，"两"→2.0） */
    private static float parseCnFloat(String s) {
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return cnToArabic(s);
        }
    }

    /** 中文数字串 → 阿拉伯数字。支持 零~九、两、十/百/千/万/亿，如 "十"→10、"二十"→20、"一百二十"→120 */
    private static int cnToArabic(String s) {
        if (s == null || s.isEmpty()) return 0;
        long total = 0, section = 0, num = 0;
        for (char c : s.toCharArray()) {
            int d = digitValue(c);
            int u = unitValue(c);
            if (d >= 0) {
                num = d;
            } else if (u > 0) {
                if (num == 0) num = 1; // “十/百/千”前无数按 1 计
                if (u == 10000 || u == 100000000) { // 万/亿 为节进位
                    section += num;
                    total += section * u;
                    section = 0;
                } else {
                    section += num * u;
                }
                num = 0;
            }
        }
        total += section + num;
        return (int) total;
    }

    private static int digitValue(char c) {
        switch (c) {
            case '零': return 0;
            case '一': case '壹': return 1;
            case '二': case '两': case '贰': return 2;
            case '三': case '叁': return 3;
            case '四': case '肆': return 4;
            case '五': case '伍': return 5;
            case '六': case '陆': return 6;
            case '七': case '柒': return 7;
            case '八': case '捌': return 8;
            case '九': case '玖': return 9;
            default: return -1;
        }
    }

    private static int unitValue(char c) {
        switch (c) {
            case '十': return 10;
            case '百': return 100;
            case '千': return 1000;
            case '万': return 10000;
            case '亿': return 100000000;
            default: return 0;
        }
    }

    @Getter
    public static class CommandPattern {
        private final Pattern pattern;
        private final Action action;

        public CommandPattern(String regex, Action action) {
            this.pattern = Pattern.compile(regex);
            this.action = action;
        }
    }

    public enum Action {
        NEXT_STEP, PREV_STEP, LOOP_STEP, LOOP_CURRENT, STOP_LOOP,
        PLAY, PAUSE, RESTART, FORWARD, BACKWARD, SPEED
    }
}
