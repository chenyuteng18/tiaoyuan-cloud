package com.diaoyuanyun.dy.app.scale.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 分龄量表题库的取值域（8 年龄组 / 7 维度 / 5 级量程）。
 *
 * <h2>权威来源（逐条可追溯）</h2>
 * <ul>
 *   <li><b>8 年龄组</b>：PRD 附录 C.1.3 —
 *       「{@code baseline_assessment.age_group_locked}
 *       enum{男16-32,男33-40,男41-48,男49以上,女14-28,女29-35,女36-42,女43-49以上}」。
 *       本枚举<b>逐字</b>采用同一字面（不含空格、连字符为半角 {@code -}），
 *       使"基线锁定的题组"与"题库里的题组"在同一把尺上对齐。</li>
 *   <li><b>7 维度</b>：PRD 附录 C.1.3 —「维度枚举（7 项）：
 *       {@code 体能精力 / 面部气色肤质 / 肩颈腰背筋骨 / 睡眠质量 / 记忆专注 / 代谢体态消化 / 情绪抗压与抵抗力}」。</li>
 *   <li><b>量程 0–4 五级</b>：PRD 附录 C.1.5 —「量程统一 <b>0–4 五级</b>
 *       （维度满分 0–16 / 总分 0–112）」；每题 0–4，每维度 4 题 = 0–16，7 维度 = 0–112。</li>
 *   <li><b>224 题</b>：PRD §8 需求池「分龄量表题库 <b>224 题</b>（8 组 × 7 维 × 4 题）」。</li>
 * </ul>
 *
 * <h2>为什么这些取值必须与 DB 的 CHECK 逐字一致</h2>
 * {@code V4__scale_item_bank.sql} 的 {@code age_group} / {@code dimension} CHECK
 * 用的是<b>同一批中文字面</b>。Java 侧若改用英文 token（如 {@code MALE_16_32}）作为落库值，
 * 就会出现"服务层认为合法、库层拒绝"或反过来的错位 —— 表现为 23514 而不是一条清晰的契约错误。
 * 故枚举的 {@link AgeGroup#label()} / {@link Dimension#label()}（= 落库值）
 * <b>就是</b>库里的值，唯一真相源是 PRD 附录 C.1.3。
 *
 * <h2>记忆专注：能出题、能算维度分，但不参与效果判定</h2>
 * 「记忆专注」在 7 维之内（题库必须能出它的题、能算它的维度分 —— 否则基线档案无处留档），
 * 但 PRD P0-13 / Y4 已确认它<b>不参与效果判定</b>（{@code cycle_assessment.module_scores} 不含该项）。
 * 🛑 该约束<b>不在本枚举</b>表达，而在判定引擎的服务层守卫上落地 ——
 * 在枚举里删掉它会让"基线档案留存"这件正当的事无法落库（见 V4 迁移的同名注释）。
 *
 * <h2>为何本类不含任何"分值"</h2>
 * 单题上下限与总分上限属<b>可配置口径</b>（config {@code #35} 的 {@code cfg:scale.range_rule}），
 * 由 {@link ScaleScoringProfile} 承载。本类只保留<b>结构性事实</b>
 * （每维题数 / 组数 / 维数），它们在 PRD 里是结构定义而非可调参数。
 * 本类的常量被 {@code ScaleEngineContractTest} 的静态扫描守卫：它把本类出现的数字字面量
 * 与"从 config {@code #35} 推导出的数字集合"比对 —— {@code 4 / 7 / 8} 之所以合法，
 * 是因为它们恰好等于配置口径里的"每维题数 / 维度数 / 年龄组数"，
 * 而不是因为它们在某个允许清单里。多一个口径外的数字即失败。
 */
public final class ScaleDomain {

    private ScaleDomain() {
    }

    /** 8 年龄组 —— 与 PRD 附录 C.1.3 `age_group_locked` 逐字同字面。 */
    public enum AgeGroup {
        MALE_16_32("男16-32"),
        MALE_33_40("男33-40"),
        MALE_41_48("男41-48"),
        MALE_49_PLUS("男49以上"),
        FEMALE_14_28("女14-28"),
        FEMALE_29_35("女29-35"),
        FEMALE_36_42("女36-42"),
        FEMALE_43_49_PLUS("女43-49以上");

        private final String label;

        AgeGroup(String label) {
            this.label = label;
        }

        /** 落库/契约字面（= DB CHECK 里的值）。 */
        public String label() {
            return label;
        }

        public static List<String> allLabels() {
            return Arrays.stream(values()).map(AgeGroup::label).toList();
        }

        /** 按落库字面解析；未知一律 fail-closed（不回落到某个默认年龄组 —— 回落会让锁定的题组漂移）。 */
        public static AgeGroup parse(String label) {
            if (label == null) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "age_group 必填（8 组之一）");
            }
            Optional<AgeGroup> hit = Arrays.stream(values())
                    .filter(g -> g.label.equals(label.trim()))
                    .findFirst();
            return hit.orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,
                    "age_group 不在 8 组枚举内: " + label + "（合法值: " + allLabels() + "）"));
        }
    }

    /** 7 维度 —— 与 PRD 附录 C.1.3「维度枚举（7 项）」逐字同字面。 */
    public enum Dimension {
        PHYSICAL_ENERGY("体能精力"),
        FACE_COMPLEXION("面部气色肤质"),
        NECK_BACK("肩颈腰背筋骨"),
        SLEEP_QUALITY("睡眠质量"),
        MEMORY_FOCUS("记忆专注"),
        METABOLISM("代谢体态消化"),
        MOOD_IMMUNITY("情绪抗压与抵抗力");

        private final String label;

        Dimension(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static List<String> allLabels() {
            return Arrays.stream(values()).map(Dimension::label).toList();
        }

        public static Dimension parse(String label) {
            if (label == null) {
                throw new BizException(ErrorCode.VALIDATION_FAILED, "dimension 必填（7 维之一）");
            }
            Optional<Dimension> hit = Arrays.stream(values())
                    .filter(d -> d.label.equals(label.trim()))
                    .findFirst();
            return hit.orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,
                    "dimension 不在 7 维枚举内: " + label + "（合法值: " + allLabels() + "）"));
        }
    }

    // ------------------------------------------------------------------
    // 结构常量（结构性事实，不是可配的业务阈值）
    // ------------------------------------------------------------------

    /** 每维度题数 —— PRD 附录 C.1.3 / C.1.5：7 维 × 4 题。 */
    public static final int ITEMS_PER_DIMENSION = 4;

    /** 年龄组数 —— PRD 附录 C.1.3 的 8 组枚举。 */
    public static final int AGE_GROUP_COUNT = 8;

    /** 维度数 —— PRD 附录 C.1.3 的 7 维枚举。 */
    public static final int DIMENSION_COUNT = 7;

    /** 题库容量 —— PRD §8「224 题（8 组 × 7 维 × 4 题）」，由上面三项推导而非另写 224。 */
    public static final int TOTAL_ITEM_CAPACITY = AGE_GROUP_COUNT * DIMENSION_COUNT * ITEMS_PER_DIMENSION;
}