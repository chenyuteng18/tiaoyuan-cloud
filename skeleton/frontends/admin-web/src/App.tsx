/**
 * 端 A · 应用外壳（登录门 + 多页导航）
 * ============================================================================
 *
 * 结构与端 B 同构（同一套纪律），但**边界口径不同**，这里逐条写明：
 *
 * 🛑 端 A 的导航过滤在本端【恒真】—— 这一点必须写在代码里而不是省略
 * ---------------------------------------------------------------------------
 * 端 B 的 `visibleNav` 用 `canCall(requires, role)` 做**准入过滤**：不同角色
 * 看到不同的导航项，因为端 B 的 `grantedRoles` 是**分角色**的。
 * 端 A 的 39 个端点**全部** `grantedRoles = ['admin']`（实测 39/39），
 * 而本端只有一个 token-role（`admin`）⇒ 过滤条件在本端**恒为真**。
 *
 * 三种可能的做法，只有第三种是对的：
 *   ① 索性不写 `requires`、不做过滤 —— 等于放弃了"导航项声明它依赖哪个端点"这一信息。
 *      后果：端点被契约删掉时，导航项会**静默**留下，点进去才报错。
 *   ② 写一份端 A 专属的角色矩阵 —— 造第二份权威，且是**恒真**的假矩阵。
 *   ③ **照写 `requires`，但明确说明它在本端恒真、其价值不在过滤而在"依赖可查"** ← 本文件
 *
 * ⇒ 本层保留 `requires` 的真正用途是**依赖可查**：每个导航项都声明它依赖哪个
 *    operationId，而该 id 必须能在生成物里查到（`tools/a-check.mjs` ⑨ 判据守它）。
 *    这样"导航项引用了不存在的端点"不会再静默通过。
 *
 * 🛑 行级范围（本端真正的边界）**不在这里过滤**
 * ---------------------------------------------------------------------------
 * 端 A 的真实边界是服务端按子档位裁剪返回行数（本店 / 辖区 / 全量）。
 * 界面**不做**行级过滤 —— 自行过滤就是造第二个裁剪点，与服务端不一致时
 * 无从判断谁对。故本层只把 `scopeText` 往下传，让页面**显示**当前范围。
 */

import { useState } from 'react';
import { isAllowedInternalCapability } from './api/internal';
import { endpointById } from './contract/endpoints';
import {
  firstFrontierEndpointId,
  internalCapabilityId,
  roleExpansionOf,
  unsettledEndpoints,
} from './contract/scope';
import {
  currentRole,
  currentStoreScope,
  getCachedProfile,
  logout,
  type Profile,
} from './services/session';
import { buttonGhostStyle, inputStyle, labelStyle, UnsettledBar } from './ui/components';
import { COLOR, FONT, ROW_LEVEL_LABEL, SPACE } from './ui/tokens';

import AuditPage from './pages/AuditPage';
import CustomerConsolePage from './pages/CustomerConsolePage';
import DashboardPage from './pages/DashboardPage';
import DocTemplatePage from './pages/DocTemplatePage';
import LoginPage from './pages/LoginPage';
import OpsHealthPage from './pages/OpsHealthPage';
import RefundWorkbenchPage from './pages/RefundWorkbenchPage';
import SettlementPage from './pages/SettlementPage';
import StorePage from './pages/StorePage';
import UnsettledPage from './pages/UnsettledPage';

type Tab =
  | 'workbench' | 'customer' | 'store' | 'refund' | 'audit' | 'docs'
  | 'settlement' | 'ops' | 'unsettled';

interface NavItem {
  readonly key: Tab;
  readonly label: string;
  /**
   * 本导航项依赖的**出站 id**。
   * 🛑 `null` = 不依赖任何出站面（纯展示页）。
   * 🛑 形态有**三种**，`tools/a-check.mjs` ⑨ `nav-requires` 判据会校验，
   *    且**三种形态的计数之和必须等于非 null 的 `requires` 条数**：
   *      ① 生成物里存在的**契约**端点 id 字符串字面量（如 `'getCustomer'`）；
   *      ② `contract/scope.ts` 导出的**无参**推导函数调用（如 `firstFrontierEndpointId()`）；
   *      ③ `internalCapabilityId('<能力id>')` —— **契约外**能力面的依赖声明，
   *        实参必须是 `contract/internal-capabilities.ts` 清册里存在的 id（⑨ 双向核验）。
   *    计数等式是防"新增第四种形态导致静默漏检"（第 53 条教训）。
   */
  readonly requires: string | null;
}

/**
 * 导航表。
 * 🛑 端 A 的 `requires` 恒真（39/39 端点均授予 admin），但仍必须写：
 *    它让"本页依赖哪个端点"成为**可被门禁校验**的事实（见文件头 ③）。
 * 🛑 形态：5 条端点 id 字面量 + 1 条无参推导函数调用 + 2 条契约外能力 id 调用。
 *    "未完结总览"项**刻意不写 `'listDocTemplates'` 字面量**，而用推导函数 ——
 *    导航表是外壳组件，**无从渲染提示条**；若为过判据在这里写一行"标注"
 *    就是纸面合规（详见 scope.ts 的 `firstFrontierEndpointId` 注释）。
 *    同理，"结算对账 / 运维自检"两页打的是**契约外**能力面（契约里没有这些端点），
 *    故走 `internalCapabilityId('<能力id>')`，实参由 ⑨ 与清册逐条核对。
 */
const NAV: readonly NavItem[] = Object.freeze([
  { key: 'workbench', label: '工作台', requires: null },
  { key: 'customer', label: '客户台账', requires: 'getCustomer' },
  { key: 'store', label: '门店与合作', requires: 'listStores' },
  { key: 'refund', label: '退款工单', requires: 'createRefund' },
  { key: 'audit', label: '稽核', requires: 'listAuditSignals' },
  { key: 'docs', label: '文书模板', requires: 'listDocTemplates' },
  { key: 'settlement', label: '结算对账', requires: internalCapabilityId('listSettlementStatements') },
  { key: 'ops', label: '运维自检', requires: internalCapabilityId('getOpsHealth') },
  { key: 'unsettled', label: '未完结总览', requires: firstFrontierEndpointId() },
]);

/**
 * 角色标签：逐字取自生成物的 `ROLE_EXPANSION[role].display`（契约 x-roles 的转录）。
 * 🛑 不手写文案 —— 手写就是第二份权威（第 46 条同型）。
 */
function roleLabelOf(role: string): string {
  return roleExpansionOf(role)?.display ?? role;
}

export default function App() {
  const [profile, setProfile] = useState<Profile | null>(() => getCachedProfile());
  const role = currentRole();
  const [tab, setTab] = useState<Tab>('workbench');
  /**
   * 客户台账的当前客户 ID（外壳持有，便于切页后保留）。
   * 🛑 契约端 A **无「列出客户」端点**（`listCustomers` 不存在）⇒ 无法提供选择器。
   *    故这里是**手工输入**，并在页面上把这一点如实写成缺口（不是"待优化"）。
   */
  const [customerId, setCustomerId] = useState('');

  if (!profile || !role) {
    return (
      <LoginPage
        onDone={() => {
          setProfile(getCachedProfile());
          setTab('workbench');
        }}
      />
    );
  }

  const roleLabel = roleLabelOf(role);
  const scope = currentStoreScope();
  const scopeText = scope
    ? `${ROW_LEVEL_LABEL[scope.row_level]?.text ?? scope.row_level} · 可见门店 ${scope.store_ids.length} 个`
    : '未知（未取到 A2 store_scope）';

  // 🛑 见文件头：端 A 的 requires 恒真（39/39 端点均为 admin）。
  //    这里**如实保留判定**（`endpointById(...)` 必须能在生成物里查到），
  //    而不是删掉它 —— 删掉会让"导航项引用了不存在的端点"不再可查。
  // 🛑 第二类出站面（契约外能力面）**必须一并认**，否则那两个导航项会被
  //    这条过滤**静默摘掉**：页面文件在、渲染分支也在，但用户点不到 —— 属第 64 条
  //    "可到达性断在装载层"的同型。故判定写成"两类真源任一命中"。
  const visibleNav = NAV.filter(
    (n) => n.requires === null || endpointById(n.requires) !== null || isAllowedInternalCapability(n.requires)
  );
  const requiresCount = NAV.filter((n) => n.requires !== null).length;
  const requiresChecked = visibleNav.filter((n) => n.requires !== null).length;


  /**
   * 全局未完结提示（外壳层的**真实界面行为**，不是为过判据而写的空标注）。
   *
   * 🛑 为什么外壳要管这件事
   * ---------------------------------------------------------------------------
   * 未完结状态是**跨页的横切事实**：使用者进入本端的第一眼就应该知道
   * "这里有 8 个地方契约还没冻结"，而不是必须点到某个页面才发现。
   * 散落各页的提示条解决的是"进去之后不被误导"，本条解决的是"进来之前就知情"。
   *
   * 🛑 为什么这里用 `unsettledEndpoints()` 而不是写 `'listDocTemplates'`
   * ---------------------------------------------------------------------------
   * 本文件是外壳，它的职责是"汇总"，不是"引用某一个具体端点"。
   * 用枚举函数得到的是**本端当前全部未完结端点**（数量随契约自动变），
   * 写死一个 id 就等于把外壳绑死在某个端点上（契约一变即静默失真）。
   */
  const unsettled = unsettledEndpoints();
  const frontierCount = unsettled.filter((x) => x.items.some((i) => i.kind === 'frontier')).length;
  const pendingCount = unsettled.filter((x) => x.items.some((i) => i.kind === 'ruling-pending')).length;

  return (
    <div style={{ background: COLOR.bg, minHeight: '100vh' }}>
      <nav
        style={{
          borderBottom: `1px solid ${COLOR.border}`,
          background: COLOR.surface,
          padding: `${SPACE.sm}px ${SPACE.lg}px`,
          display: 'flex',
          gap: SPACE.md,
          alignItems: 'center',
          flexWrap: 'wrap',
        }}
      >
        <span style={{ fontSize: FONT.md, color: COLOR.brand, fontWeight: 600 }}>调元云 · 管理后台</span>
        {visibleNav.map((n) => (
          <button
            key={n.key}
            onClick={() => setTab(n.key)}
            style={{
              background: 'none',
              border: 'none',
              padding: `${SPACE.xs}px ${SPACE.sm}px`,
              cursor: 'pointer',
              fontSize: FONT.md,
              color: tab === n.key ? COLOR.brand : COLOR.textMuted,
              borderBottom: tab === n.key ? `2px solid ${COLOR.brand}` : '2px solid transparent',
            }}
          >
            {n.label}
          </button>
        ))}
        <span style={{ marginLeft: 'auto', ...labelStyle }}>{roleLabel}</span>
        <button
          style={buttonGhostStyle}
          onClick={() => {
            logout();
            setProfile(null);
            setCustomerId('');
          }}
        >
          退出
        </button>
      </nav>

      {unsettled.length > 0 ? (
        <div style={{ maxWidth: 960, margin: '0 auto', padding: `${SPACE.md}px ${SPACE.lg}px 0` }}>
          {/* 🛑 两条分别渲染（frontier / ruling-pending 语义不同，不得合成一句） */}
          <UnsettledBar
            items={[
              ...(frontierCount
                ? [{
                    kind: 'frontier',
                    note: `本端 ${frontierCount} 个端点（I 域文书模板整域）`
                      + `—— 契约内容尚未写完，界面各入口均会显示提示条。`,
                  }]
                : []),
              ...(pendingCount
                ? [{
                    kind: 'ruling-pending',
                    note: `本端 ${pendingCount} 个端点（G4 退款审批）`
                      + `—— 功能可能已生效，但取值依据是推断，裁定后取值可能变。`,
                  }]
                : []),
            ]}
          />
          <p style={{ ...labelStyle, margin: 0 }}>
            本端 39 个端点中有 {unsettled.length} 个带未完结声明 —— 明细见导航「未完结总览」。
          </p>
        </div>
      ) : null}

      {tab === 'workbench' ? <DashboardPage roleLabel={roleLabel} profile={profile} scopeText={scopeText} /> : null}
      {tab === 'customer' ? (
        <div>
          <div style={{ maxWidth: 960, margin: '0 auto', padding: `${SPACE.lg}px ${SPACE.lg}px 0` }}>
            <label style={labelStyle} htmlFor="app-customer-id">
              客户 ID（手工输入 —— 契约无「列出客户」端点，见下方说明）
            </label>
            <input
              id="app-customer-id"
              style={{ ...inputStyle, marginTop: SPACE.xs }}
              value={customerId}
              onChange={(e) => setCustomerId(e.target.value)}
              placeholder="customer_id"
            />
          </div>
          <CustomerConsolePage roleLabel={roleLabel} scopeText={scopeText} customerId={customerId.trim()} />
        </div>
      ) : null}
      {tab === 'store' ? <StorePage roleLabel={roleLabel} scopeText={scopeText} /> : null}
      {tab === 'refund' ? <RefundWorkbenchPage roleLabel={roleLabel} /> : null}
      {tab === 'audit' ? <AuditPage roleLabel={roleLabel} scopeText={scopeText} /> : null}
      {tab === 'docs' ? <DocTemplatePage roleLabel={roleLabel} /> : null}
      {tab === 'settlement' ? <SettlementPage roleLabel={roleLabel} /> : null}
      {tab === 'ops' ? <OpsHealthPage roleLabel={roleLabel} /> : null}
      {tab === 'unsettled' ? <UnsettledPage roleLabel={roleLabel} /> : null}

      <footer
        style={{
          maxWidth: 960,
          margin: '0 auto',
          padding: `${SPACE.xl}px ${SPACE.lg}px`,
          ...labelStyle,
        }}
      >
        <div>
          导航依赖已校验：本端 {NAV.length} 项导航中，{requiresCount} 项声明了出站依赖
          （契约端点 id / 契约层推导函数 / 契约外能力面 id），
          全部可在**对应真源**里查到（本次渲染实际校验 {requiresChecked} 项）。
        </div>
        <div style={{ marginTop: SPACE.xs }}>
          🛑 端 A 的导航过滤**恒真**（39/39 端点均授予 admin），`requires` 在本端的用途不是过滤
          而是"依赖可查"；本端真正的边界是**服务端行级范围**，界面不自行裁剪。
        </div>
        <div style={{ marginTop: SPACE.xs }}>
          🛑 契约外能力面（结算对账 / 运维自检）另有真源：后端
          <code>EndpointCoverageLedgerTest.INTERNAL_ENDPOINTS</code> 台账 ——
          前端清册与它的一致性由 <code>tools/a-check.mjs</code> ⑫ 判据逐条核对。
        </div>
      </footer>
    </div>
  );
}