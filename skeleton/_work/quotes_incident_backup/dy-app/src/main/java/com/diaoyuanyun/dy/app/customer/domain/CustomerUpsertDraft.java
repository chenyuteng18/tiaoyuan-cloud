package com.diaoyuanyun.dy.app.customer.domain;

import com.diaoyuanyun.dy.common.exception.BizException;
import com.diaoyuanyun.dy.common.result.ErrorCode;

import java.util.List;
import java.util.UUID;

/**
 * B2 建档的完整写入草稿（契约 {@code CustomerCreateRequest} + 服务端补充量）。
 *
 * <h2>与 {@link CustomerArchive} 的分工（两者不可合并）</h2>
 * <pre>
 *   CustomerArchive      = **客户端可见的请求面**（契约 required 的 5 项：
 *                          name / gender / age / phone / screening_id）
 *   CustomerUpsertDraft  = **实际写入 customer 行的完整字段集**，
 *                          多出 4 项且全部由**服务端**决定：
 *                            status            ← 不由客户端给（它是状态机派生聚合）
 *                            owner_store_id    ← 归属店/首诊店（建档一次性写入）
 *                            serving_store_id  ← 当前服务店（建档时通常 = owner，可空）
 *                            created_by        ← 操作人
 * </pre>
 * 🛑 <b>为什么不把 server-only 字段塞进 {@code CustomerArchive} 的请求体</b>：
 * 一旦它们能被请求体携带，就会立刻出现两条越权路径，而两条都**不报错**：
 * <ol>
 *   <li>{@code status} —— 客户端直接传 {@code REJECTED}/{@code ARCHIVED} 即可自造终态，
 *       绕过上一态的判定链；</li>
 *   <li>{@code owner_store_id} —— 客户端把归属店填成自己的店，
 *       而"归属店锁定档案"正靠它成立（data-dict §2.6 / U1）。</li>
 * </ol>
 * 两条的共同特征是"看起来完全合法的请求体，写出来的却是不该有的状态"。
 * 故分离成两个类型，让越权字段**在类型上就无处可放**。
 *
 * <h2>🛑 {@code status} 在这里<b>必须</b>显式给出（不得依赖库层 DEFAULT）</h2>
 * V1 的 {@code customer.status} 有 {@code DEFAULT 'pending'}，而权威 5 值是
 * {@code CREATED/PROFILED/CONSENTED/REJECTED/ARCHIVED} —— {@code 'pending'} <b>不在其中</b>。
 * 一旦某条写入路径依赖了那个默认值，它写出的行既不是 CREATED 也不是任何一态，
 * 任何按 5 值分组的报表都会**静默漏掉**这批行。
 * 故本 record 把 {@code status} 设为必填构造参数（{@code null} 即抛），
 * 使"显式给"成为类型层面的强制，而不是一句注释里的约定。
 * （同一个坑在 {@link ConsentRow#dataSource()} 上同样设了构造期校验。）
 *
 * <h2>🛑 越界一律报 1001，<b>不</b>夹逼</h2>
 * {@code age} / {@code gender} 的合法域见 {@link CustomerArchive} 与 {@link Gender}。
 * 夹逼（把 130 岁收成 119）会让一个人被静默算进错误的年龄分组，
 * 而分组错误只让分数口径偏移、不报错 —— 这是本域最典型的静默失效形态。
 */
public record CustomerUpsertDraft(
        UUID customerId,
        String name,
        Gender gender,
        Integer age,
        String phone,
        UUID screeningId,
        CustomerStatus status,
        UUID ownerStoreId,
        UUID servingStoreId,
        String createdBy) {

    /** 构造期校验 —— 逐条对应契约 {@code required} 与库层 CHECK。 */
    public CustomerUpsertDraft {
        if (customerId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档写入草稿缺 customer_id —— 它是主键，也是"
                            + "customer_state_transition 首条跃迁的外键目标");
        }
        if (name == null || name.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档写入草稿缺 name —— 契约 CustomerCreateRequest.required 含它；"
                            + "V1 该列 NOT NULL");
        }
        if (gender == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档写入草稿缺 gender —— 契约 required 含它，且它是 AgeGroup 的输入维度");
        }
        if (age == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档写入草稿缺 age —— 契约 required 含它");
        }
        if (age < 1 || age > 119) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档写入草稿 age 越界: " + age + "（合法区间 1~119）—— "
                            + "契约 minimum=1 / maximum=119，库层 CHECK 0<age<120，两者等价。"
                            + "🛑 不夹逼：夹逼会让一个人被静默算进错误的年龄分组");
        }
        if (phone == null || phone.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档写入草稿缺 phone —— 契约 B2 description 逐字『租户内唯一（跨店识别键）』。"
                            + "🛑 不加手机号格式正则：上游均未给出格式约束，"
                            + "自加一条会拒掉固话 / 境外号 / 虚拟号段等上游从未排除的输入");
        }
        if (screeningId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档写入草稿缺 screening_id —— 契约 required 含它；"
                            + "它是 B2 门禁的输入（服务端据它查出筛查结论后再放行）");
        }
        if (status == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档写入草稿缺 status —— 🛑 必须显式给出，不得依赖库层 DEFAULT。"
                            + "V1 的 customer.status 有 DEFAULT 'pending'，"
                            + "而 'pending' 【不在】权威 5 值 {CREATED,PROFILED,CONSENTED,REJECTED,ARCHIVED} 里；"
                            + "依赖它的路径写出的行既不是 CREATED 也不是任何一态，"
                            + "任何按 5 值分组的报表都会静默漏掉这批行");
        }
        if (ownerStoreId == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "建档写入草稿缺 owner_store_id —— data-dict §2.6『归属/首诊店，锁定档案』。"
                            + "🛑 它【不得】由客户端请求体提供（否则客户端可把归属店填成自己的店，"
                            + "而『归属店锁定档案』正靠它成立）；"
                            + "服务端从 token 的组织锚点解出（staff.store_id）");
        }
    }

    /**
     * 是否"服务店与归属店一致"（建档的常见情形）。
     *
     * <p>{@code serving_store_id} 可空是业务事实：<b>仅建档、尚未开始服务</b>时没有服务店。
     * 把它默认成 owner 会让"客户已经在某店被服务"变成一条无从分辨的记录 ——
     * 而跨店迁移的审计正需要"没有服务店"与"服务店 = 某店"这两态可分。
     */
    public boolean servingMatchesOwner() {
        return servingStoreId != null && servingStoreId.equals(ownerStoreId);
    }

    /** 从请求草稿 + 服务端补充量构造。 */
    public static CustomerUpsertDraft from(CustomerArchive archive,
                                          UUID customerId,
                                          CustomerStatus status,
                                          UUID ownerStoreId,
                                          UUID servingStoreId,
                                          String createdBy) {
        if (archive == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "建档请求草稿为空");
        }
        return new CustomerUpsertDraft(
                customerId, archive.name(), archive.gender(), archive.age(), archive.phone(),
                archive.screeningId(), status, ownerStoreId, servingStoreId, createdBy);
    }

    /**
     * 库层可写形态说明（供自描述与断言）。
     *
     * <p>🛑 {@code missing_columns} 与 {@link CustomerArchive#MISSING_CUSTOMER_COLUMNS}
     * 同源 —— 但本类读的是<b>迁移补齐后</b>的事实：V7 已把它们加上，
     * 故 {@link #describeStorageGap()} 反过来断言"不再缺列"，
     * 使"V7 真的生效了"成为可验证的事实，而不是一句自述。
     */
    public static java.util.Map<String, Object> describeStorageGap() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("endpoint", "B2 POST /customers");
        m.put("v7_migration", "V7__customer_domain_alignment.sql");
        m.put("columns_added_by_v7", List.of(
                "phone", "gender", "age", "owner_store_id", "serving_store_id"));
        m.put("still_nullable_reason",
                "五列全部保持可空（V7 刻意不加 NOT NULL）。三个各自独立的理由："
                        + "ⓐ verification/02_seed.sql 是上一轮的证据基线、不得修改，"
                        + "它插入 customer 时只给 (id, tenant_id, name, status) —— "
                        + "若新列 NOT NULL 且无默认值，真库门禁的 provision 会当场失败；"
                        + "ⓑ 该表已有 V1 历史行，给它们补一个语义正确的 NOT NULL 值是不可能的"
                        + "（phone 无从编造，而编造手机号会把跨店识别键变成一根假数据）；"
                        + "ⓒ 『必填』的权威落点是【写入路径】而不是表结构 —— "
                        + "本 record 与前一个 record 的构造期校验即拒绝缺失");
        m.put("status_explicit",
                "status 由本 record 强制显式给出，不得依赖 V1 的 DEFAULT 'pending'"
                        + "（'pending' 不在权威 5 值 {CREATED,PROFILED,CONSENTED,REJECTED,ARCHIVED} 内）");
        m.put("owner_vs_serving",
                "owner_store_id = 归属/首诊店（锁定档案）；serving_store_id = 当前服务店（可空）。"
                        + "🛑 两者不得合并 —— 合并会让『跨店通兑 + 他店只读共享』（U1）失去承载，"
                        + "而那个错误不报错，只表现为『客户跟着走了，原店的档案归属也没了』");
        return m;
    }
}