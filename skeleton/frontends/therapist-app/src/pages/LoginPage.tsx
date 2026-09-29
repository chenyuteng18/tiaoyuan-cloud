/**
 * 端 B · 页面：登录（A1）
 * ============================================================================
 * 🛑 契约 `authLogin` 的请求体**逐字**是 `{account, credential, client_end}`
 *    （内联 schema，required 三项）。本页只提供这两项输入 + 恒定的 client_end='app'：
 *    · 不提供"选择端"的下拉 —— 端由**构建产物**决定，不是用户选项；
 *      若让用户能改 client_end，就等于允许拿 web 档位登进 APP。
 *    · 不提供注册 / 自助改密 —— 员工账号由管理端开通（端 A 的 B-7 通路）。
 */

import { useState } from 'react';
import { login } from '../services/session';
import { describe } from '../services/errors';
import { COLOR, FONT, SPACE } from '../ui/tokens';
import { buttonStyle, Card, ErrorBar, inputStyle, labelStyle, Page } from '../ui/components';
import { ROLE_LABEL } from '../contract/access';
import type { Profile } from '../services/session';

export default function LoginPage({ onDone }: { onDone: (p: Profile) => void }) {
  const [account, setAccount] = useState('');
  const [credential, setCredential] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<{ text: string; traceId: string } | null>(null);

  async function submit() {
    if (!account.trim() || !credential) {
      setErr({ text: '请填写账号与凭证。', traceId: '' });
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      const profile = await login(account.trim(), credential);
      onDone(profile);
    } catch (e) {
      const d = describe(e);
      setErr({ text: d.text, traceId: d.traceId });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Page title="调元云 · 员工登录" roleLabel={`${ROLE_LABEL.therapist} / ${ROLE_LABEL.meridian}`}>
      {err ? <ErrorBar text={err.text} traceId={err.traceId} /> : null}
      <Card hint="账号与凭证由门店 / 总部在管理端开通，本端不提供注册与自助改密。">
        <div style={{ marginBottom: SPACE.md }}>
          <div style={labelStyle}>员工号</div>
          <input
            style={inputStyle}
            value={account}
            onChange={(e) => setAccount(e.target.value)}
            placeholder="例如 S10023"
            autoComplete="username"
          />
        </div>
        <div style={{ marginBottom: SPACE.lg }}>
          <div style={labelStyle}>凭证（密码 / 短信码）</div>
          <input
            style={inputStyle}
            type="password"
            value={credential}
            onChange={(e) => setCredential(e.target.value)}
            autoComplete="current-password"
          />
        </div>
        <button style={buttonStyle} disabled={busy} onClick={submit}>
          {busy ? '登录中…' : '登录'}
        </button>
      </Card>
      <p style={{ ...labelStyle, fontSize: FONT.xs, color: COLOR.textMuted }}>
        登录后系统会立即读取 A2 身份与可见性档位 —— 档位以服务端解算为准，
        不以本地缓存为准。
      </p>
    </Page>
  );
}