/**
 * 客户与档案域（契约域 B）—— <b>领域层</b>（S2-10）。
 *
 * <h2>一、本包的设计输入冻结清单（不可变 · 逐字来自上游，不是自拟）</h2>
 * 本包全部类型只依据下列五份上游件构造。任何取值/映射/守卫<b>必须</b>能在其中找到出处；
 * 找不到的<b>不得发明</b>（本仓库纪律：不代拍、不发明枚举）。
 * <pre>
 *  ① 契约 contract/openapi-v1.0.0.yaml 域 B 六行（B1~B6）
 *  ② data-dict §2.25 customer_state_transition —— 14 态 +【14→5 显式映射表】+【G1/G2 守卫表】
 *  ③ data-dict §2.6 customer / §2.7 screening_record / §2.8 consent / §2.23 intake_profile
 *  ④ V5 迁移：screening_record(161) / consent(192) / intake_profile(770) 三段 DDL
 *  ⑤ PRD §7.2 客户服务主状态机（14 态：11 活跃 + 3 终态）+ v1.6 准入顺序修正
 * </pre>
 *
 * <h2>二、域 B 六行端点（契约逐字摘要）</h2>
 * <pre>
 *  B1 POST /screening-records            [therapist, meridian, admin]  200+400
 *     命中禁忌(result=不通过) → 客户状态置 REJECTED；后续 B2/B3 入口
 *     403 GATE_MISSING（missing_items=["screening_result"]）。记录【不可删除】。
 *  B2 POST /customers                    [therapist, meridian, admin]  200+403
 *     需 screening_result=通过。phone 为租户内唯一（跨店识别键）。
 *  B3 POST /customers/{id}/consents      [therapist, meridian, admin]  200+403
 *     需 PROFILED（门禁 G1：未建档不得签知情同意书）。
 *  B4 GET  /customers/{id}               [client, therapist, meridian, admin]  200+403+404
 *     🔴 客户 token 请求含派生字段的 query（如 ?include=verdict）
 *        → 403 VISIBILITY_DENIED，data.denied_fields 回显被拒字段名。
 *  B5 GET  /customers/{id}/intake-profile   [client, therapist, meridian, admin]  200
 *  B6 PATCH /customers/{id}/intake-profile  [therapist, meridian, admin]  200
 *     补充 + 修订（append-only 留痕，不可覆盖）。
 * </pre>
 *
 * <h2>三、🛑 本域最容易被写错的三处（各有独立落点，不得合并）</h2>
 * <ol>
 *   <li><b>B4 对客户<b>开放</b>，但字段按矩阵裁剪</b> —— 它与 A3 的形态<b>镜像相反</b>：
 *       A3 是"客户不可调"（点名拒绝），B4 是"客户可调、但只见基础字段组"。
 *       把 A3 的 {@code requireCallable} 照搬过来会让客户看不到自己的档案 ——
 *       而那正是本端点存在的意义之一。故本域<b>没有</b>"拒绝客户端角色"的守卫，
 *       却<b>有</b>"按字段组裁剪"的守卫（见 {@link com.diaoyuanyun.dy.app.customer.domain.CustomerFieldVisibility}）。</li>
 *   <li><b>14 态 ≠ 5 值</b> —— 14 态是唯一权威，5 值是<b>派生聚合</b>。
 *       映射在 data-dict §2.25 ② 已逐项冻结（含 {@code CONSENTED} 的一对多聚合），
 *       <b>不得反向由 5 值推断 14 态</b>，也<b>不得自行补一条"看起来合理"的映射</b>。</li>
 *   <li><b>门禁的 {@code missing_items} 有两级，且上游顺序不可颠倒</b> ——
 *       被筛查拒绝（REJECTED）报 {@code ["screening_result"]}（契约 B1 逐字，
 *       且明确它同时覆盖 B2 与 B3 入口）；未建档报 {@code ["PROFILED"]}（data-dict G1）。
 *       两者是<b>上游/下游</b>关系：先答"筛查没过"，再答"还没建档"。
 *       颠倒会让排查者去补建档，而真实成因是他根本不该被建档。</li>
 * </ol>
 *
 * <h2>四、🛑 已登记的库层缺口（本包<b>不</b>代拍，只在服务层 fail-closed）</h2>
 * <ol>
 *   <li><b>{@code customer} 表缺 {@code phone} / {@code gender} / {@code age} /
 *       {@code owner_store_id} / {@code serving_store_id} 五列</b> ——
 *       V1 的 {@code customer} 只有
 *       {@code id, tenant_id, name, status, owner_id, created_at, updated_at, deleted_at}；
 *       而契约 B2 把 {@code phone}/{@code gender}/{@code age} 列为<b>必填</b>、
 *       B4 要下发 {@code owner_store_id}/{@code serving_store_id}，
 *       data-dict §2.6 与 PRD L1310 也都定义了这五列。
 *       这属【契约/字典 ↔ 库结构】的结构性缺口，须走增项迁移（V7）——
 *       本包<b>不写迁移</b>、不发明替代列名，只在服务层把"缺列"表现为明确失败。</li>
 *   <li><b>{@code customer.status} 的库层默认值与权威 5 值不一致</b> ——
 *       V1 写 {@code DEFAULT 'pending'}（骨架占位），而权威集是
 *       {@code CREATED/PROFILED/CONSENTED/REJECTED/ARCHIVED}。
 *       已在 {@code _work/contract-t6-api-freeze-2026-09-19.md} 登记为待改项；
 *       故写入路径<b>必须显式给出 status</b>，不得依赖库层默认值。</li>
 *   <li><b>B3 契约无 {@code requestBody}</b>，而 {@code consent} 表有四列 NOT NULL
 *       （{@code auth_scope_json} / {@code band_willingness} / {@code signed_at} /
 *       {@code evidence_hash}）。与 F1 同类缺口，已登记；服务层按表要求取参并校验。</li>
 * </ol>
 *
 * <h2>五、本包的层纪律</h2>
 * 本包只放<b>值对象 / 枚举 / 带断言的 record / 纯解算器</b>：
 * <ul>
 *   <li>不依赖 {@code ..service..}（{@code ArchitectureBoundaryTest} 的 R4 会红）；</li>
 *   <li>不依赖 web / controller（R3）；</li>
 *   <li>不自己访问数据库（仓储在 {@code ..repository..}）；</li>
 *   <li>允许依赖 {@code dy-common}（异常/错误码）与 {@code dy-security}（端角色枚举）。</li>
 * </ul>
 */
package com.diaoyuanyun.dy.app.customer.domain;