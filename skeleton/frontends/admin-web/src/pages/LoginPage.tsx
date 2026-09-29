/**
 * 端 A · 页面：登录（A1 + A2）
 * ============================================================================
 *
 * 🛑 为什么登录后必须**再调一次 A2**
 * ---------------------------------------------------------------------------
 * A1 返回 token + role + client_end 等**签发时**的声明；而**可见性档位/行级范围
 * 的唯一权威下发点是 A2**（契约逐字）。若登录后直接用 A1 的返回值填界面，
 * 就会出现"界面按签发时的档位渲染、服务端按当前解算的档位裁剪"这类矛盾。
 * 故：A1 只取 token，随后立刻 A2 取档位。
 *
 * 🛑 `client_end` 写死 `'web'`
 * ---------------------------------------------------------------------------
 * 契约 `x-roles.admin.end = web`。界面不提供选择器 —— 摆出一个不可能的选项
 * 只会让人误以为管理端可以登录 app 会话。
 */

import { useState } from 'react';
import { describe } from '../services/errors';
import { fetchProfile, login, saveProfile, saveToken } from '../services/session';
import { consoleIdentity } from '../services/session';
import { buttonStyle, Card, ErrorBar, inputStyle, labelStyle, Page } from '../ui/components';
import { COLOR, FONT, SPACE } from '../ui/tokens';

export default function LoginPage({ onDone }: { onDone: () => void }) {
  const [account, setAccount] = useState('');
  const [credential, setCredential] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<{ text: string; traceId: string } | null>(null);

  const id = consoleIdentity();

  async function submit() {
    if (!account.trim() || !credential) {
      setErr({ text: '请输入账号与凭据。', traceId: '' });
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      const res = await login(account.trim(), credential);
      if (!res?.token) {
        setErr({ text: '登录失败：服务端未返回 token。', traceId: '' });
        return;
      }
      saveToken(res.token);
      // 🛑 立刻 A2 取档位（唯一权威下发点）。A1 的角色只是签发时声明。
      const profile = await fetchProfile();
      if (!profile) {
        setErr({ text: '登录成功但未能读取 A2 档位（/auth/me 未返回 data）。请重试。', traceId: '' });
        return;
      }
      saveProfile(profile);
      onDone();
    } catch (e) {
      const d = describe(e);
      setErr({ text: d.text, traceId: d.traceId });
    } finally {
      setBusy(false);
    }
  }

  return (
    <Page title="调元云 · 管理后台" roleLabel="未登录">
      {err ? <ErrorBar text={err.text} traceId={err.traceId} /> : null}
      <Card
        title="登录（A1 → A2）"
        hint="登录成功后立刻调 A2 /auth/me 取可见性档位与行级范围 —— 档位的唯一权威下发点是 A2，不是 token 载荷。"
      >
        <div style={{ display: 'grid', gap: SPACE.sm }}>
          <div>
            <div style={labelStyle}>账号（manager / area / hq 之一）</div>
            <input
              style={inputStyle}
              value={account}
              onChange={(e) => setAccount(e.target.value)}
              placeholder="账号"
              autoComplete="username"
            />
          </div>
          <div>
            <div style={labelStyle}>凭据</div>
            <input
              style={inputStyle}
              type="password"
              value={credential}
              onChange={(e) => setCredential(e.target.value)}
              placeholder="凭据"
              autoComplete="current-password"
            />
          </div>
          <div>
            <button style={buttonStyle} disabled={busy} onClick={submit}>
              {busy ? '登录中…' : '登录'}
            </button>
          </div>
        </div>
      </Card>
      <Card title="契约侧提示">
        <p style={{ ...labelStyle, margin: 0, fontSize: FONT.xs, color: COLOR.textMuted }}>
          本端 token-role：{id.roles.join(' / ')}（单一角色）·
          子档位：{id.adminTokens.join(' / ')}（来自契约 x-roles，非手写）。
          行级范围（本店 / 辖区 / 全量）由 A2 下发，界面不推断。
        </p>
      </Card>
    </Page>
  );
}