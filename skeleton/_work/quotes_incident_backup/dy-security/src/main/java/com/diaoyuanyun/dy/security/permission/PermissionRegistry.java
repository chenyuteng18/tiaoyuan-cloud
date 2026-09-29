package com.diaoyuanyun.dy.security.permission;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 角色→权限码 映射 (骨架最小实现, 权威档位以 {@code /auth/me} 下发为准, ADR-07)。
 *
 * <p>SUPER_ADMIN 拥有全部权限; 其余角色按骨架预置映射。真实权限矩阵应来自配置/策略服务。
 *
 * <h2>🛑 契约角色码必须登记 —— 否则契约的 {@code x-callable-roles} 全部拿不到权限</h2>
 * 骨架早期只登记了<b>大写</b>码（{@code SUPER_ADMIN} / {@code REGION_ADMIN} /
 * {@code STORE_STAFF} / {@code TENANT_ADMIN}），而契约 {@code x-roles} 冻结的是<b>小写</b>码
 * （{@code client} / {@code therapist} / {@code meridian} / {@code manager} / {@code area} /
 * {@code hq}）。两者不相等 ⇒ 用真签名 JWT 以契约角色码发请求时，
 * {@link #hasPermission} 一律返回 false ⇒ 契约写明 {@code x-callable-roles: [therapist,
 * meridian, admin]} 的端点对 therapist/meridian/admin <b>全部 403</b>。
 * 这个缺口是被 {@code DerivedVisibilityE2ETest} 的真请求抓出来的：
 * 经络师调 E4 拿到 2001，而契约要求他拿到 200。
 *
 * <h2>本次登记是"消除缺口"，不是"拍定矩阵"</h2>
 * 本类仍是<b>骨架占位</b>：完整的档位矩阵应由 {@code /auth/me} 下发（ADR-07）。
 * 故这里只做两件可辩护的事：
 * <ol>
 *   <li>把契约的六个角色码<b>登记进来</b>（否则整个权限层对契约角色码是失效的）；</li>
 *   <li>值取"让契约明确写出的调用关系成立"的<b>最小集合</b> —— 读权限给全部 staff 角色；
 *       写权限<b>按语义分码</b>（见下方域 B 一节）：档案写入（{@code customer:archive}）给
 *       契约点名的全部角色（含 therapist/meridian）；而 {@code customer:write}
 *       仍然只给管理层级与遗留 staff 码，<b>不给</b> therapist/meridian
 *       （它承载的是题库导入这类内容资产的写动作，不属一线职责）。</li>
 * </ol>
 * 🛑 骨架大写码与契约小写码的<b>双重编号</b>不在此处消除（登记为待裁定项 T-11/T-12）；
 * 本类对两者并存登记，使"角色码分叉"不再表现为运行时 403。
 *
 * <h2>🛑 {@code store_customer_service} 刻意<b>不</b>登记</h2>
 * 契约 {@code x-roles} 明确其 {@code token-role} 为 {@code null}、{@code end} 为 {@code null}
 * （"无端，任何字段组均不成立"）—— 它<b>不是一个 token 角色</b>。
 * 更关键的是：登记它等于替 P0-19（门店客服能否看见退款文案）<b>预先拍板</b>，
 * 而那是必须由用户裁定的项。故此处留白：未登记角色在 {@link #hasPermission} 下
 * 一律 false（fail-closed），P0-19 裁定后再按裁定值登记。
 *
 * <h2>🔴 退款域（{@code refund:*}）—— 被真请求 E2E 抓出的第二例同类缺口</h2>
 * 本节的登记不是"补一张拍脑袋的矩阵"，而是<b>逐条对齐契约域 G 五端点的
 * {@code x-callable-roles}</b>（{@code openapi-v1.0.0.yaml} L983~L1103）：
 * <pre>
 *   G1 POST /refunds                      x-callable-roles: [meridian, admin]
 *   G2 GET  /refunds/{id}                 x-callable-roles: [meridian, admin]
 *   G3 POST /refunds/{id}/retentions      x-callable-roles: [meridian, admin]
 *   G4 POST /refunds/{id}/approvals       x-callable-roles: [admin]
 *   G5 POST /refunds/{id}/receipts        x-callable-roles: [meridian, admin]
 * （{@code admin} 按 {@code VisibilityRole.ADMIN} 展开 = {manager, area, hq}）
 * </pre>
 * 控制器 {@code RefundWorkOrderController} 同步贴了 6 处
 * {@code @RequirePermission("refund:read"/"write"/"approve")}。
 * 在补本节之前，本类<b>没有任何一条 refund:* 登记</b> ⇒ {@link #hasPermission} 对
 * <b>全部角色恒 false</b> ⇒ 真请求下 meridian/manager/area/hq 调 G1~G5 <b>一律 403</b>，
 * 而契约逐字声明这些端点对他们可调。这正是上文那条"骨架早期只登记大写码"缺口的
 * <b>同型复发</b>：这一次不是角色码大小写分叉，而是<b>权限码本身从未登记</b>。
 *
 * <p>它为何又一次躲过了单测：{@code RefundWorkOrderEndpointTest} 直调服务层与控制器对象，
 * <b>不经过</b> {@link PermissionInterceptor} —— 于是 55 条断言全绿、构建 BUILD SUCCESS，
 * 而线上退款域整体不可用。抓住它的唯一方式仍是"用真 JWT 发真 HTTP"
 * （{@code RefundDomainGEndpointsE2ETest}）。
 *
 * <h3>🛑 {@code area} 必须持有 {@code refund:approve} —— 否则"可见≠可审批"是空话</h3>
 * 契约 G4 的 {@code x-callable-roles: [admin]} 含 {@code area}（区域督导），
 * 同时附 {@code x-ruling-pending}：审批白名单未由上游逐项明示。
 * PRD §2.2 逐字写明督导「✓（<b>可见</b>、<b>不审批</b>）」。
 * 这两件事的共同可执行形态是：<b>督导必须<b>过得了</b>权限层，
 * 然后被<b>审批白名单闸</b>拒掉</b>
 * （{@code RetentionPolicy#assertApproverIsNotDeputy} 的角色分支，
 * 消息含「不属退款审批人集合」）。
 * <ul>
 *   <li>若 {@code area} <b>不</b>持有 {@code refund:approve}：他在权限层就被拒
 *       （消息「权限不足: refund:approve」），白名单闸<b>从未被执行</b>，
 *       {@code x-ruling-pending} 记录的那条规则在实现里形同不存在；</li>
 *   <li>若 {@code area} 持有它却<b>没有</b>白名单闸兜底：督导会真的批掉钱 ——
 *       与 §2.2 直接冲突。</li>
 * </ul>
 * 故本登记"给 area 发 {@code refund:approve}"<b>不是</b>授权他审批（他仍被白名单闸拒），
 * 而是让那条更细的规则<b>有机会被执行</b>。职责边界是分层的：
 * {@code @RequirePermission} 管"岗位允不允许做这件事"，白名单闸管"这个人能不能批这一单"。
 * 把两层压成一层，就会失去"督导能进、但不能批"这一档。
 *
 * <h3>🛑 {@code client} / {@code therapist} 刻意<b>不</b>给任何 {@code refund:*}</h3>
 * 契约域 G 首行逐字：「本域全部接口：<b>客户端与调理师端一律 403 VISIBILITY_DENIED</b>」。
 * 两条防线必须<b>同时</b>成立（纵深防御）：端点级
 * （{@code @StaffOnly} / 派生字段拦截器，按"是否客户端"判）与
 * 功能权限级（客户/调理师不持有 {@code refund:*}）。只留一条的话，
 * 任一条被改坏（例如某天有人的 {@code role} 值配错），退款域就对客户洞开了。
 * 这也是为何 meridian 拿到 {@code refund:read/write} 但 <b>不</b>给
 * {@code refund:approve} —— 经络师是代录人集合成员（G1 可写），
 * 但「代录者不可审批」（P0-19 / R4b ④ 动机阀门）意味着他连"进审批端点再被白名单拒"
 *
 * <h2>🔴 判定域（{@code verdict:*}）—— 第三次同型复发的<b>预防性</b>登记</h2>
 * 同样是"逐条对齐 {@code x-callable-roles}"，依据是契约域 F 的前两行：
 * <pre>
 *   F1 POST /cycle-assessments/{id}/verdicts   x-callable-roles: [meridian, admin]
 *   F2 GET  /customers/{id}/verdicts           x-callable-roles: [meridian, admin]
 * （admin 展开 = {manager, area, hq}）
 * </pre>
 * 控制器 {@code VerdictController} 贴了 4 处
 * {@code @RequirePermission("verdict:read"/"write")}。
 *
 * <p>🛑 为什么要<b>新开一个域</b>，而不是复用 {@code refund:read/write}：
 * 判定域与退款域是<b>两个功能域</b>（契约把 F 与 G 分成两节）。
 * 复用会让"判定结论怎么落库"这件事被一个"退款"权限码授权 ——
 * 而判定链覆盖的是<b>每一个客户</b>（含从没提过退款的），
 * 退款域只覆盖提出诉求的那一小部分。两者的授权范围差了数量级，
 * 用同一个码等于把判定权顺带发给了"所有能处理退款的人"，
 * 且未来想单独收紧判定权时会发现<b>两者无法分离</b>。
 *
 * <p>🔴 但必须同时记住：{@code verdict:*} 是<b>岗位功能权限</b>这一层，
 * 它<b>不</b>表达"客户不可见"—— 客户的拒绝由另外两层完成：
 * F2 的 {@code @StaffOnly}（契约 {@code x-client-explicitly-denied: true}）与
 * {@code VerdictService.requireCallable}（客户与调理师<b>显式点名</b>拒绝）。
 * 三层语义不同，不可互相替代（与退款域那一节的论证同源）。
 *
 * <p>⚠️ 前两次缺口（角色码大小写分叉、refund:* 从未登记）都是被<b>真请求 E2E</b> 抓出来的，
 * 且都躲过了单测 —— 因为端点单测直调控制器对象、不经过 {@code PermissionInterceptor}。
 * 本节的登记是第三次的<b>预防</b>而非补课：在写控制器之前先落码，
 * 并由 {@code PermissionCodeRegistrationGateTest} 的"必然存在码"清单钉住它。
 * 这正是那个门禁存在的意义 —— 把"想起来要登记"变成"构建会告诉你"。
 * 这一档都不该有。
 *
 * <h2>🔴 组织域（{@code store:read}）—— 第四次的预防性登记（S2-9 / 契约域 A）</h2>
 * 依据是契约域 A 三端点的 {@code x-callable-roles}：
 * <pre>
 *   A1 POST /auth/login   x-callable-roles: [client, therapist, meridian, admin]  ⇒ Non-goal（不实现）
 *   A2 GET  /auth/me      x-callable-roles: [client, therapist, meridian, admin]
 *   A3 GET  /stores       x-callable-roles: [therapist, meridian, admin]
 * （admin 展开 = {manager, area, hq}）
 * </pre>
 *
 * <h3>🛑 A2 <b>刻意不登记任何码</b> —— 这不是遗漏，是"给客户开口"的唯一正确形态</h3>
 * A2 的 {@code x-callable-roles} <b>含 {@code client}</b>，而本注册表<b>刻意不登记 client</b>
 * （见上方"client 刻意不登记"一节）。两条事实叠加的结论是：
 * <b>A2 一旦贴上 {@code @RequirePermission}，客户查自己的可见性档位就会 403。</b>
 * 而 A2 恰恰是「可见性档位<b>唯一权威下发点</b>」—— 客户的三端 UI 靠它决定渲染分支，
 * 把它对客户关掉等于让客户端拿不到自己该渲染什么。
 *
 * <p>故 A2 的形态是：<b>不贴任何功能权限码</b>，"可调用角色"由服务层
 * {@code VisibilityRole.tryOf(role)} 做 fail-closed 校验（未登记角色一律拒绝）。
 * 这是"三层防线各守一件事"的镜像用法 —— 在这里，<b>缺第三层（功能权限）是刻意的</b>，
 * 因为契约要求该端点对全体端角色开放。
 *
 * <p>🛑 不得为了让"A2 也有权限码"而给 {@code client} 登记一个码：
 * 那会让 {@code PermissionCodeRegistrationGateTest} 的第 ③ 条契约防回退断言失去
 * "客户不持有任何域权限码"这条基线，而那条基线正是"给客户发码"这件事必须显式经过评审的原因。
 *
 * <h3>A3 登记 {@code store:read}（仅 staff 侧）</h3>
 * A3 的 {@code x-callable-roles} <b>不含 client</b>，故它就是"该给 staff 发码、不给客户发码"
 * 的标准形态 —— 与退款域 / 判定域同款。客户对 A3 的 403 由<b>两条</b>防线共同承担：
 * {@code store:read}（客户不持有）与服务层的 {@code requireCallable}（显式点名拒绝）。
 *
 * <p>⚠️ 注意 A3 的契约是 {@code x-client-forbidden: false} + {@code x-callable-roles} 不含 client。
 * 这两个键<b>语义不同</b>（前者="客户端小程序是否生成调用面"，后者="服务端可调用角色"），
 * 故 A3 <b>不贴 {@code @StaffOnly}</b> —— 后者对应的是 {@code x-client-explicitly-denied: true}
 * （契约里只有 E4 / 域 F 的 F2 / 域 G 五端点标了它）。
 *
 * <h2>🔴 域 B（{@code customer:archive}）—— 第五次同型复发的补登记（S2-10）</h2>
 * 依据是契约域 B 四行的 {@code x-callable-roles}：
 * <pre>
 *   B1 POST /screening-records            x-callable-roles: [therapist, meridian, admin]
 *   B2 POST /customers                    x-callable-roles: [therapist, meridian, admin]
 *   B3 POST /customers/{id}/consents      x-callable-roles: [therapist, meridian, admin]
 *   B6 PATCH /customers/{id}/intake-profile  x-callable-roles: [therapist, meridian, admin]
 * （admin 按 {@code VisibilityRole.ADMIN} 展开 = {manager, area, hq}）
 * </pre>
 *
 * <h3>🛑 缺口形态：码<b>有主</b>，但持有的不是契约点名的角色</h3>
 * 这四个端点在落码时贴的是 {@code customer:write}。本类里 {@code customer:write} 的持有者是
 * <b>管理层级（manager/area/hq）与遗留大写码（REGION_ADMIN/STORE_STAFF）</b> ——
 * 契约域 B 点名的 {@code therapist} / {@code meridian} <b>一个都不持有</b>。
 * 于是：真请求下 therapist / meridian 调 B1/B2/B3/B6 <b>一律 403「权限不足: customer:write」</b>。
 *
 * <p>🔴 它为何又一次躲过了全部既有门禁 —— 这一次的原因比前四次更值得记住：
 * <b>{@code customer:write} 是「有主」的</b>（manager/area/hq 持有），
 * 故 {@code PermissionCodeRegistrationGateTest} 的第二条（"每个码必须有主"）<b>全程是绿的</b>。
 * 那个门禁在类注释里已明说自己是<b>码级</b>而非<b>角色级</b> ——
 * 本次缺口正好落在它<b>宣称不覆盖</b>的那一格上。
 * <b>这不是门禁失效，而是"码级门禁看不见角色级缺失"的一次实证。</b>
 *
 * <h3>🛑 为什么另立 {@code customer:archive}，而不是给 therapist/meridian 补 {@code customer:write}</h3>
 * 因为 {@code customer:write} 当时<b>已经被三类互不相干的语义共用</b>：
 * <ol>
 *   <li>契约域 B 的档案写入（B1/B2/B3/B6）—— 一线角色<b>该</b>有；</li>
 *   <li>E5 的 {@code POST /band/available-dates}（小程序端设备探测）—— 契约要 <b>client</b>，见下节；</li>
 *   <li>题库的导入与组卷（{@code ScaleItemBankController} 的 {@code /scale/items/import}
 *       与 {@code /scale/paper/compose}）—— <b>仅总部</b>该有。</li>
 * </ol>
 * 其中第 3 类的路径<b>不在契约内</b>（契约 C1 只有 {@code GET /scale-item-banks} 这一条只读行），
 * 它是骨架期的内部演示入口。若为了修第 1 类而给 therapist/meridian 补 {@code customer:write}，
 * <b>会顺带把题库的导入与组卷权限发给一线调理师/经络师</b> ——
 * 而这正是本类上方"写权限……<b>不给</b> therapist/meridian（题库导入等写动作不属其职责）"
 * 那句纪律明文反对的事。两处纪律互相冲突，说明真正的毛病是<b>码的粒度</b>，
 * 不是"谁持有"。
 *
 * <p>故本次修的是<b>粒度</b>：新立 {@code customer:archive} 承载第 1 类语义
 * （域 B 的"档案写入推进"），{@code customer:write} 保持原样继续承载第 3 类。
 * 命名沿用既有的<br>{@code 域:动作} 两段式（与 {@code refund:approve} 同风格），
 * 不发明第三段。
 *
 * <p>🛑 取值是<b>纯加法</b>：凡原本持有 {@code customer:write} 的角色，
 * 都发给 {@code customer:archive} —— 这样<b>没有任何角色的既有能力被缩小</b>
 * （遗留大写码 REGION_ADMIN/STORE_STAFF 也保留其调域 B 的能力），
 * 而契约点名的一线角色被<b>新增</b>进来。T-11/T-12（大小写码分叉）不在此处裁决。
 *
 * <h2>🔴 E5 {@code /band/available-dates} —— 摘码，不是补码（本类的第六条形态）</h2>
 * 契约 L829-862 逐字：{@code x-callable-roles: [client]}，{@code x-client-forbidden: false}，
 * responses = {@code 200 / 400 / 422} —— <b>没有 403</b>。
 * 而该端点当时贴的是 {@code customer:write}；{@code client} 在本类里<b>刻意不登记任何码</b>
 * （见上方"A2 刻意不登记任何码"一节，且 {@code PermissionCodeRegistrationGateTest} 第⑤②条
 * 把"client 无码"钉成基线）⇒ <b>合法的客户端探测请求恒 403</b>。
 *
 * <p>🛑 正确形态是<b>把这个注解摘掉</b>，与 A2 完全同款：当契约要求某端点对
 * {@code client} 开放、而 client 刻意无码时，<b>缺第三层（功能权限）是刻意的</b>，
 * 不是遗漏。E5 不下发任何可见性受限字段（它只做契约校验与纯计算、不落库），
 * 故对其它角色开放不产生数据面泄露；而契约也没有为其它角色声明 403 ——
 * 依"不自行发明守卫"的同源纪律，此处<b>不</b>新增角色拒绝。
 *
 * <p>⚠️ E5 的 403 目前确实会出现在 {@code DerivedVisibilityE2ETest} 里，
 * 但那是<b>派生字段拦截器</b>（排在权限拦截器<b>之前</b>）对请求体里的 {@code as_value} 键产生的 2001 ——
 * 与权限层无关。两条路径归同一个码，掩盖了这个缺口：
 * 派生用例绿着，而合法的客户端请求是 403。
 *
 * <h2>🔴 评估域（{@code assessment:write}）—— S3-1 / 契约域 C2 的登记</h2>
 * C2 {@code POST /customers/{id}/assessments/baseline} 的
 * {@code x-callable-roles: [therapist, meridian, admin]} 不含 client。
 * 故新立 {@code assessment:write}（= 基线评估提交），发给三个 staff 端角色。
 * 🛑 <b>不</b>复用 {@code customer:write} / {@code customer:archive}：前者是内容资产写入，
 * 后者是档案写入 —— 把一个"评估字段"塞进它们，会重蹈 {@code customer:write}
 * 一码多义的覆辙（第 29 条正是这么抓出来的）。分码是代价最低的防复发。
 *
 * <h2>🔴 履约域（{@code fulfillment:write}）—— S3-2 / 契约域 D1 的登记</h2>
 * D1 {@code POST /customers/{id}/visits}（服务核销，四道闸门）的
 * {@code x-callable-roles: [therapist, meridian, admin]} 不含 client。
 * 新立 {@code fulfillment:write}（= 履约写：服务核销 / 每日填报写入）。
 * 🛑 <b>不</b>复用 {@code customer:archive}（它是"档案写入推进"，语义是入组链的
 * screening/建档/同意书；而"核销一次服务"是服务履约链，两者数据与门禁完全不同）——
 * 分码，防"一码多义"的第七次复发。
 *
 * <h2>🔴 文书模板域（{@code doc:write}）—— S3-4 / 契约域 I 的登记</h2>
 * 域 I 全 8 端点的 {@code x-callable-roles: [admin]}（I1~I7）—— 文书模板是
 * <b>总部维护的内容资产</b>（同 {@code scale_item_bank}）。新立 {@code doc:write}。
 * 🛑 <b>不</b>复用 {@code customer:write}（它已经被"题库导入组卷"占用语义，
 * 第 29 条正是它一码多义被真请求抓出）—— 分码，防第八次复发。
 * 🛑 <b>仅</b> admin 三端持有（manager/area/hq），therapist/meridian 不持（文书模板
 * 是总部治理面，一线角色无写权）。
 *
 * <h2>🔴 审计链自检域（{@code audit:read}）—— C-1 的登记</h2>
 * {@code GET /audit/log-chain}（审计日志哈希链校验，内部自描述端点族）。
 * <b>仅 hq 持有</b> —— 理由不是"审计数据敏感"这种泛泛之谈，而是一条结构性事实：
 * {@code audit_log} 是<b>全局单链</b>（跨租户串联，刻意豁免 RLS / T-09），
 * 因此它<b>不属于任何单一租户</b>，而契约 F3 的 {@code x-row-scope}
 * 「门店负责人仅本店、区域督导仅辖区」预设了"对象属于某租户"。
 * 把 {@code area} / {@code manager} 放进来会造出"本店 scope 的身份读全局对象"的语义裂缝。
 * 🛑 <b>不</b>复用 {@code refund:read} / {@code verdict:read} 等业务域读码：
 * 那些描述的是"读某个业务域的数据"，而本码描述的是"读审计与稽核<b>事实</b>"，
 * 混用会让"谁该读审计"这个判断附着在业务域上 —— 防一码多义的第九次复发。
 * 🛑 若产品需区域督导自检，须先裁定"辖区自检的对象是什么"（三门走法，见
 * {@code AuditChainController} 类注释），前两种走法会产出<b>必然为假</b>的
 * "本辖区链有效"结论 —— 故在裁定之前<b>不开</b> area/manager。
 */
@Component
public class PermissionRegistry {

    private final Map<String, Set<String>> rolePermissions = new HashMap<>();

    public PermissionRegistry() {
        // --- 骨架遗留大写码（T-11/T-12：与契约小写码并存，不去重不归一）---
        rolePermissions.put("SUPER_ADMIN", Set.of("*"));
        rolePermissions.put("REGION_ADMIN", Set.of("customer:read", "customer:write", "customer:archive",
                "report:region"));
        rolePermissions.put("STORE_STAFF", Set.of("customer:read", "customer:write", "customer:archive"));
        rolePermissions.put("GUEST", Set.of());

        // --- 契约 x-roles 冻结的小写角色码（必须登记，否则 x-callable-roles 失效）---
        // 读：staff 全角色（契约矩阵里 ①②③④ 对 therapist/meridian/admin 均为可见档）。
        // 写：仅管理层级 —— 判定链与题库的写动作不属调理师/经络师职责。
        // 🛑 client 刻意不登记：客户对 ④ 派生结果无档位，且 E4 的第二层防线
        //    （@RequirePermission("customer:read")）正是靠"客户不持有该权限"成立。
        //
        // 🔴 refund:* 逐条对齐契约域 G 五端点的 x-callable-roles（见下方专节）。
        //
        // 🔴 store:read（S2-9）逐条对齐契约域 A3 的 x-callable-roles: [therapist, meridian, admin]。
        //    给 staff 全角色发读码：门店列表是组织台账的只读面，
        //    三个 staff 端角色（调理师 / 经络师 / 三个管理码）在契约里都可调。
        //    🛑 客户不持该码 —— A3 的 x-callable-roles 不含 client。
        //
        // 🔴 customer:archive（S2-10）逐条对齐契约域 B 四行 B1/B2/B3/B6 的
        //    x-callable-roles: [therapist, meridian, admin]。取值见类注释"纯加法"一节。
        //
        // 🔴 assessment:write（S3-1）逐条对齐契约域 C2 的 x-callable-roles: [therapist, meridian, admin]。
        //    三端 staff 角色各持一个评估写码。🛑 不并入 customer:write / customer:archive：
        //    见类注释"评估域"一节（防重蹈第 29 条"一码多义"覆辙）。
        //
        // 🔴 fulfillment:write（S3-2）逐条对齐契约域 D1 的 x-callable-roles: [therapist, meridian, admin]。
        //    三端 staff 角色各持一个履约写码（服务核销 / 每日填报）。见类注释"履约域"一节。
        //
        // 🔴 doc:write（S3-4）逐条对齐契约域 I 的 x-callable-roles: [admin]（仅管理层三端）。
        //    文书模板是总部维护的内容资产，一线角色不持。见类注释"文书模板域"一节。
        rolePermissions.put("manager", Set.of("customer:read", "customer:write", "customer:archive",
                "store:read",
                "refund:read", "refund:write", "refund:approve",
                "verdict:read", "verdict:write",
                "assessment:write", "fulfillment:write",
                "doc:write"));
        rolePermissions.put("area", Set.of("customer:read", "customer:write", "customer:archive",
                "report:region", "store:read",
                "refund:read", "refund:write", "refund:approve",
                "verdict:read", "verdict:write",
                "assessment:write", "fulfillment:write",
                "doc:write"));
        rolePermissions.put("hq", Set.of("customer:read", "customer:write", "customer:archive",
                "report:region", "store:read",
                "refund:read", "refund:write", "refund:approve",
                "verdict:read", "verdict:write",
                "assessment:write", "fulfillment:write",
                "doc:write",
                "audit:read"));
        rolePermissions.put("therapist", Set.of("customer:read", "customer:archive", "store:read",
                "assessment:write", "fulfillment:write"));
        rolePermissions.put("meridian", Set.of("customer:read", "customer:archive", "store:read",
                "refund:read", "refund:write",
                "verdict:read", "verdict:write",
                "assessment:write", "fulfillment:write"));
    }

    public boolean hasPermission(String role, String permission) {
        if (role == null) {
            return false;
        }
        Set<String> perms = rolePermissions.getOrDefault(role, Set.of());
        return perms.contains("*") || perms.contains(permission);
    }
}
