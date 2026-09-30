/**
 * 端 A · 页面：文书模板（I1~I7 —— **整域占位待冻结**）
 * ============================================================================
 *
 * 🛑 本页是第 54 条的**主后果面**：整域 7 个端点契约逐字 `x-frontier: 占位待冻结`。
 * ---------------------------------------------------------------------------
 * 契约原话是「占位待冻结」—— 含义是**契约内容尚未写完、功能属未来**。
 * 若本页把它们当普通功能渲染，使用者会以为"模板管理已经能用了"，
 * 然后去排查为什么上传没反应。
 *
 * ⇒ 本页的三条硬要求：
 *   1. **每个入口都渲染 `UnsettledBar`** —— `a-check.mjs` 的 `unsettled-surfaced`
 *      判据要求"引用了未完结端点的源文件必须引用未完结标注"，本页是它的主战场。
 *   2. **I7 叠加第二条约束**：`downloadDocTemplate` 带 `x-super-admin-only: true`
 *      （**仅超管**）⇒ 本页同时渲染 `SuperAdminNote`。
 *   3. **返回值一律不做字段承诺** —— 契约说它是占位，替他承诺字段形态
 *      就是替契约做决定（本层 `domain.ts` 已一律 `Record<string, unknown>`）。
 *
 * 🛑 I3 的幂等键**不是随机值**
 * ---------------------------------------------------------------------------
 * 契约 `x-idempotency-key` 逐字：`(tenant_id, doc_type, file_hash)`。
 * 即"同一文件重复上传"必须由服务端按该三要素去重。
 * 故本页**要求显式输入这三个要素并拼出幂等键**，不自动生成随机键
 * （随机键会让去重永远不命中，表现为"上传成功但每次都是新记录"）。
 */

import { useState } from 'react';
import { describe } from '../services/errors';
import {
  contractNoteOf,
  createDocTemplate,
  createDocTemplateVersion,
  downloadDocTemplate,
  listDocTemplateVersions,
  listDocTemplates,
  publishDocTemplateVersion,
  uploadDocTemplate,
} from '../services/domain';
import {
  buttonGhostStyle,
  Card,
  Empty,
  ErrorBar,
  inputStyle,
  KV,
  labelStyle,
  Page,
  SuperAdminNote,
  UnsettledBar,
} from '../ui/components';
import { COLOR, FONT, SPACE } from '../ui/tokens';

export default function DocTemplatePage({ roleLabel }: { roleLabel: string }) {
  const [list, setList] = useState<readonly Record<string, unknown>[] | null>(null);
  const [versions, setVersions] = useState<readonly Record<string, unknown>[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [err, setErr] = useState<{ text: string; traceId: string } | null>(null);

  const [templateId, setTemplateId] = useState('');
  const [version, setVersion] = useState('');
  const [newBody, setNewBody] = useState('{}');
  // I3 幂等键三要素
  const [tenantId, setTenantId] = useState('');
  const [docType, setDocType] = useState('');
  const [fileHash, setFileHash] = useState('');

  // 未完结标注：I 域 7 个端点全部占位待冻结；I7 另带仅超管。
  const notes = {
    list: contractNoteOf('listDocTemplates'),
    create: contractNoteOf('createDocTemplate'),
    upload: contractNoteOf('uploadDocTemplate'),
    version: contractNoteOf('createDocTemplateVersion'),
    versions: contractNoteOf('listDocTemplateVersions'),
    publish: contractNoteOf('publishDocTemplateVersion'),
    download: contractNoteOf('downloadDocTemplate'),
  };
  const allUnsettled = notes.list?.unsettled ?? [];

  async function run(label: string, fn: () => Promise<void>) {
    setBusy(true);
    setErr(null);
    setMsg(null);
    try {
      await fn();
      setMsg(`${label} 完成`);
    } catch (e) {
      const d = describe(e);
      setErr({ text: d.text, traceId: d.traceId });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Page title="文书模板" roleLabel={roleLabel}>
      <UnsettledBar items={allUnsettled} />
      <div
        style={{
          border: `1px solid ${COLOR.frozen}`,
          background: COLOR.surfaceAlt,
          borderRadius: 4,
          padding: `${SPACE.sm}px ${SPACE.md}px`,
          marginBottom: SPACE.lg,
          fontSize: FONT.sm,
        }}
      >
        <strong style={{ color: COLOR.frozen }}>整域说明</strong>
        <span>
          {' '}—— I1~I7 **全部** 7 个端点契约逐字标 `x-frontier: 占位待冻结`。
          本页保留接线（端点确实在契约里），但**不得**把本域当作已冻结、可用的功能。
        </span>
      </div>

      {err ? <ErrorBar text={err.text} traceId={err.traceId} /> : null}
      {msg ? <div style={{ ...labelStyle, marginBottom: SPACE.md, color: COLOR.ok }}>✓ {msg}</div> : null}

      <Card title="I1 模板列表">
        <UnsettledBar items={notes.list?.unsettled ?? []} />
        <button
          style={buttonGhostStyle}
          disabled={busy}
          onClick={() => run('I1 列表', async () => {
            setList((await listDocTemplates({ page: 1, page_size: 50 })) ?? null);
          })}
        >
          I1 读取
        </button>
        {list ? (
          <div style={{ marginTop: SPACE.md }}>
            <KV k="条数" v={String(list.length)} />
            {list.length === 0 ? <Empty text="（无模板）" /> : list.map((t, i) => (
              <KV key={String(t.template_id ?? i)} k={String(t.template_id ?? `#${i}`)} v={String(t.id ?? '')} />
            ))}
          </div>
        ) : null}
      </Card>

      <Card title="I2 新建模板 / I4 新建版本 / I6 发布">
        <UnsettledBar items={notes.create?.unsettled ?? []} />
        <div style={labelStyle}>template_id（I4/I5/I6/I7 用）</div>
        <input style={inputStyle} value={templateId} onChange={(e) => setTemplateId(e.target.value)} />
        <div style={{ ...labelStyle, marginTop: SPACE.sm }}>
          version（I6 发布 / <strong>I7 下载**都**用</strong> —— 🛑 I7 的 version 是契约
          required query 参数，后端 `@RequestParam("version")` 强制，缺则 400）
        </div>
        <input style={inputStyle} value={version} onChange={(e) => setVersion(e.target.value)} />
        <div style={{ ...labelStyle, marginTop: SPACE.sm }}>
          请求体（JSON —— 契约未声明 requestBody，本页不替契约编字段名）
        </div>
        <input style={inputStyle} value={newBody} onChange={(e) => setNewBody(e.target.value)} />
        <div style={{ display: 'flex', gap: SPACE.sm, marginTop: SPACE.sm, flexWrap: 'wrap' }}>
          <button
            style={buttonGhostStyle}
            disabled={busy}
            onClick={() => run('I2 新建模板', async () => {
              await createDocTemplate(JSON.parse(newBody) as Record<string, unknown>, `dt-${Date.now()}`);
            })}
          >
            I2 新建模板
          </button>
          <button
            style={buttonGhostStyle}
            disabled={busy}
            onClick={() => run('I4 新建版本', async () => {
              await createDocTemplateVersion(templateId.trim(), JSON.parse(newBody) as Record<string, unknown>, `dv-${Date.now()}`);
            })}
          >
            I4 新建版本
          </button>
          <button
            style={buttonGhostStyle}
            disabled={busy}
            onClick={() => run('I6 发布版本', async () => {
              await publishDocTemplateVersion(templateId.trim(), version.trim(), `dp-${Date.now()}`);
            })}
          >
            I6 发布
          </button>
        </div>
      </Card>

      <Card
        title="I3 上传模板文件"
        hint="契约 x-idempotency-key 逐字 = (tenant_id, doc_type, file_hash) —— 幂等键不是随机值。"
      >
        <UnsettledBar items={notes.upload?.unsettled ?? []} />
        <div style={labelStyle}>tenant_id</div>
        <input style={inputStyle} value={tenantId} onChange={(e) => setTenantId(e.target.value)} />
        <div style={{ ...labelStyle, marginTop: SPACE.sm }}>doc_type</div>
        <input style={inputStyle} value={docType} onChange={(e) => setDocType(e.target.value)} />
        <div style={{ ...labelStyle, marginTop: SPACE.sm }}>file_hash</div>
        <input style={inputStyle} value={fileHash} onChange={(e) => setFileHash(e.target.value)} />
        <button
          style={{ ...buttonGhostStyle, marginTop: SPACE.sm }}
          disabled={busy}
          onClick={() => run('I3 上传', async () => {
            if (!tenantId.trim() || !docType.trim() || !fileHash.trim()) {
              throw new Error('I3：幂等键三要素（tenant_id / doc_type / file_hash）均须填写 —— 契约逐字如此。');
            }
            // 🛑 幂等键按契约三要素构造（**不是随机值**）——随机键会让去重永不命中。
            const key = `${tenantId.trim()}:${docType.trim()}:${fileHash.trim()}`;
            await uploadDocTemplate(JSON.parse(newBody) as Record<string, unknown>, key);
          })}
        >
          I3 上传（幂等键 = 三要素）
        </button>
        <p style={{ ...labelStyle, marginTop: SPACE.sm }}>
          🛑 本页**不自动生成随机幂等键** —— 那会让"同一文件重复上传"永远无法被服务端去重，
          表现为"上传成功但每次都是新记录"。
        </p>
      </Card>

      <Card title="I5 版本列表">
        <UnsettledBar items={notes.versions?.unsettled ?? []} />
        <button
          style={buttonGhostStyle}
          disabled={busy}
          onClick={() => run('I5 版本列表', async () => {
            setVersions((await listDocTemplateVersions(templateId.trim())) ?? null);
          })}
        >
          I5 读取
        </button>
        {versions ? (
          <div style={{ marginTop: SPACE.md }}>
            <KV k="版本数" v={String(versions.length)} />
            {versions.length === 0 ? <Empty text="（无版本）" /> : versions.map((v, i) => (
              <pre key={i} style={{ fontSize: FONT.xs, background: COLOR.surfaceAlt, padding: SPACE.sm, borderRadius: 4 }}>
                {JSON.stringify(v, null, 2)}
              </pre>
            ))}
          </div>
        ) : null}
      </Card>

      <Card title="I7 下载模板（契约：**占位待冻结** + **仅超管**）">
        <UnsettledBar items={notes.download?.unsettled ?? []} />
        <SuperAdminNote />
        {/*
          🛑 I7 的 `version` 为什么必须由使用者显式给出（本仓第 65 条）
          ----------------------------------------------------------------------
          契约 I7 逐字有 `version: in: query, required: true`，后端
          `DocFileController.download(..., @RequestParam("version") int version)`
          是**强制**的 ⇒ 缺它必然 400。
          而调用点此前只传 `params: { id: templateId }` —— 漏了 version。
          这正是"端点被调用了 ≠ 调用是对的"：`tsc` / `vite build` / 触达判据 /
          反向验证**一律绿**，因为门禁此前只问"有没有被调用"。
          ⇒ 此处沿用上方 I6 的 version 输入框（同一个"版本"事实，不另设第二个输入），
            并在未填时**禁用按钮** —— 而不是代填 0 去撞一次成功的错版本下载。
        */}
        <p style={{ ...labelStyle, marginTop: SPACE.sm }}>
          本端点复用上方 <code>version</code> 输入框（I6 发布 / I7 下载同用）。
          {version.trim() === '' ? ' 🛑 当前未填 ⇒ 下方按钮保持禁用。' : ` 当前 version = ${version.trim()}`}
        </p>
        <button
          style={buttonGhostStyle}
          disabled={busy || !templateId.trim() || version.trim() === ''}
          onClick={() => run('I7 下载', async () => {
            await downloadDocTemplate(templateId.trim(), version.trim());
          })}
        >
          I7 下载
        </button>
        <p style={{ ...labelStyle, marginTop: SPACE.sm }}>
          本端点是端 A **唯一**同时带 `x-super-admin-only` 与 `x-frontier` 的端点。
          非超管档位调用是服务端 403；界面**不应**依据本地判断放行。
        </p>
      </Card>

      <Card title="契约侧提示">
        <p style={{ ...labelStyle, margin: 0, fontSize: FONT.xs }}>
          当前角色 {roleLabel} · I 域 7 个端点全部只授予 admin（端点级无分叉）·
          未完结端点 {allUnsettled.length} 项。本页在 `a-check.mjs` 的
          `unsettled-surfaced` 判据覆盖范围之内（已引用 unsettledOf / frontier）。
        </p>
      </Card>
    </Page>
  );
}