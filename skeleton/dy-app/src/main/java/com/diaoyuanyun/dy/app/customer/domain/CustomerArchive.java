package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.List;
import java.util.UUID;

/**
 * 建档草稿（B2 {@code POST /customers} 的入参语义）—— 契约 {@code CustomerCreateRequest} 逐字段。
 *
 * <h2>权威来源</h2>
 * <pre>
 *  契约 CustomerCreateRequest：
 *    required: [name, gender, age, phone, screening_id]
 *    name              string
 *    gender            enum [男, 女]
 *    age               integer, minimum 1, maximum 119
 *    phone             string —— description 逐字「租户内唯一（跨店识别键）」
 *    screening_id      string —— description 逐字「服务端校验其 result=通过」
 *  data-dict §2.6 customer:
 *    phone  UNIQUE(tenant_id, phone) —— 跨店识别键
 *    age    CHECK 0 < age < 120
 *    owner_store_id / serving_store_id  FK store；owner = 首诊店（锁定档案）
 * </pre>
 *
 * <h2>🛑🛑 本 record 存在的前提，是一条<b>尚未落地的库层缺口</b>（必须读）</h2>
 * V1 迁移的 {@code customer} 表<b>只有</b>：
 * <pre>
 *   id / tenant_id / name / status / owner_id / created_at / updated_at / deleted_at
 * </pre>
 * 而契约 B2 把 {@code phone} / {@code gender} / {@code age} 列为<b>必填</b>，
 * B4 要下发 {@code owner_store_id} / {@code serving_store_id}，
 * data-dict §2.6 与 PRD（"customer｜…｜owner_store_id、serving_store_id、name、phone、status"）
 * 也都定义了这五列。<b>五列在库层全部缺失，且此前未被登记。</b>
 *
 * <h3>处置（按本仓库纪律）</h3>
 * <ol>
 *   <li><b>不放宽契约</b>：契约是三方冻结件，把必填改可选属 MAJOR 变更、须走「变更业务裁定」；</li>
 *   <li><b>不发明替代列名</b>：例如把 {@code phone} 塞进 {@code name} 或另建影子表 ——
 *       那会造出一份"看起来能跑、但与上游字典不一致"的结构，且**永远不会有人发现它对不上**；</li>
 *   <li><b>落码 + 明确登记</b>：本 record 忠实承载五值时所需的全部字段，
 *       服务层的写入路径在<b>缺列</b>这一事实上以明确错误失败（而不是静默丢弃），
 *       缺口本身以 {@link #MISSING_CUSTOMER_COLUMNS} 的形式成为<b>可断言的事实</b>，
 *       并由增项迁移（建议 V7）补齐。</li>
 * </ol>
 * 🛑 这条处置的意义在于：缺一个列与缺一个业务规则不同 —— 它<b>不阻塞</b>领域层与契约对齐，
 * 但<b>必须</b>被显式看见，否则"建档"这个动作会在真请求下以 SQL 错误失败，
 * 而错误消息里只有列名，看不出这是"契约与库结构没对齐"。
 *
 * <h2>🛑 {@code phone} 是"跨店识别键"，故它的唯一性<b>必然</b>是租户级</h2>
 * data-dict §2.6 与契约 description 都逐字写「租户内唯一（跨店识别键）」。
 * 这个约束与"门店级唯一"的差别是本系统的一条核心业务前提：
 * <b>客户属于品牌而非门店</b>（PRD §1 首段：客户"属于品牌可跨店通兑"）。
 * 若 phone 做成门店级唯一，同一个人在两家店会被建成两个客户档案 ——
 * 而他的调理记录、判定链、退款工单会分列在两条互不知晓的主线上。
 *
 * <h2>🛑 未加任何"手机号格式"正则（刻意的）</h2>
 * 上游（契约 / data-dict / PRD）<b>均未给出</b> phone 的格式约束，
 * 只给"非空 + 租户唯一"。自行加一个国内手机号正则属于<b>发明口径</b>：
 * 它会拒掉固话、境外号、虚拟号段等上游从未排除的输入，
 * 且那条拒绝在文档里查无出处。故本 record 只校验非空。
 */
public record CustomerArchive(
        String name,
        Gender gender,
        Integer age,
        String phone,
        UUID screeningId) {

    /**
     * 契约与字典要求、但<b>V1 库层缺失</b>的 {@code customer} 列清单（缺口登记 · 可断言）。
     *
     * <h2>为何写成常量而不是注释</h2>
     * 注释会随文件重组消失，也与"构建是否发现该问题"无关。
     * 写成常量后，它可以被回归用例直接读取并断言"这五列确实还不在迁移文件里"，
     * 从而把这条缺口钉在<b>构建</b>上 —— 一旦增项迁移补齐了它们，那条断言会红，
     * 提示把它从本清单移除（而不是让清单悄悄过期，给后人一份错的现状描述）。
     */
    public static final List<String> MISSING_CUSTOMER_COLUMNS = List.of(
            "phone",            // 契约 B2 必填；data-dict §2.6 UNIQUE(tenant_id, phone)
            "gender",           // 契约 B2 必填；data-dict §2.6 CHECK ∈ {男,女}
            "age",              // 契约 B2 必填；data-dict §2.6 CHECK 0<age<120
            "owner_store_id",   // 契约 B4 出参；data-dict §2.6 FK store（首诊店，锁定档案）
            "serving_store_id"); // 契约 B4 出参；data-dict §2.6 FK store（当前服务店，跨店迁移）

    /** 构造期校验 —— 逐条对应契约的 required / minimum / maximum。 */
    public CustomerArchive {
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档缺 name —— 契约 CustomerCreateRequest.required 含它（必填）");
        }
        if (gender == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档缺 gender —— 契约 required 含它，且它是 AgeGroup（年龄分组）的输入维度之一");
        }
        if (age == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档缺 age —— 契约 required 含它");
        }
        if (age < 1 || age > 119) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档 age 越界: " + age + "（合法区间 1~119）—— "
                            + "契约 CustomerCreateRequest.age 的 minimum=1 / maximum=119；"
                            + "data-dict §2.6 的 CHECK 为 0 < age < 120，两者等价。"
                            + "🛑 越界报 1001 而非夹逼到边界：夹逼会让一个人被静默算进错误的年龄分组，"
                            + "而分组错误只会让分数口径偏移，不报错");
        }
        if (phone == null || phone.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档缺 phone —— 契约 B2 description 逐字『phone 为租户内唯一（跨店识别键）』；"
                            + "data-dict §2.6 UNIQUE(tenant_id, phone)。"
                            + "🛑 本处【不加】手机号格式正则：上游未给出任何格式约束，"
                            + "自加一条会拒掉固话 / 境外号 / 虚拟号段等上游从未排除的输入");
        }
        if (screeningId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档缺 screening_id —— 契约 required 含它，且其 description 逐字"
                            + "『服务端校验其 result=通过』：本字段是 B2 门禁的输入，"
                            + "由服务层据它查出筛查结论后经 CustomerGateGuard 判定");
        }
    }

    /**
     * 该建档草稿在库层的可写形态说明（供自描述与断言；不含任何取值）。
     *
     * <p>它把"五列缺失"这条事实变成可读取的运行时信息，而不是埋在类注释里。
     */
    public static java.util.Map<String, Object> describeStorageGap() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("endpoint", "B2 POST /customers");
        m.put("contract_required_fields", List.of("name", "gender", "age", "phone", "screening_id"));
        m.put("v1_customer_columns", List.of(
                "id", "tenant_id", "name", "status", "owner_id", "created_at", "updated_at", "deleted_at"));
        m.put("missing_columns", MISSING_CUSTOMER_COLUMNS);
        m.put("gap_kind", "契约/字典 ↔ 库结构 未对齐（结构性缺口）");
        m.put("disposition",
                "不放宽契约、不发明替代列名；以增项迁移（建议 V7）补齐，"
                        + "服务层写入路径在缺列这一事实上以明确错误失败");
        m.put("owner_vs_serving",
                "data-dict §2.6：owner_store_id = 归属/首诊店（锁定档案）；"
                        + "serving_store_id = 当前服务店（跨店迁移）；"
                        + "PRD L1310『档案归客户，首诊店锁定，跨店只读共享，他店补充/修订留痕不可覆盖』");
        m.put("phone_uniqueness_scope",
                "租户级 UNIQUE(tenant_id, phone)，【不是】门店级 —— "
                        + "客户属品牌、可跨店通兑（PRD §1）；门店级唯一会把同一人建成两份档案");
        return m;
    }
}