/**
 * 端 A 首页（骨架）
 * ---------------------------------------------------------------------------
 * 与端 B 同构：只回答"契约有没有正确地接到这一端"，三个数字全部来自生成物。
 *
 * 🛑 刻意不做业务页面：G-B 的阶段顺序要求先骨架 + SDK，业务页面填充排在
 * G-A（agreement / case_archive 通路）收口之后。
 */

import { CONTRACT_VERSION, ENDPOINT_IDS, END_TOKEN_ROLES } from './contract/endpoints';

export default function App() {
  return (
    <main style={{ fontFamily: 'system-ui, sans-serif', padding: 24, maxWidth: 720 }}>
      <h1 style={{ fontSize: 20, marginBottom: 16 }}>调元云 · 管理后台</h1>
      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <tbody>
          <Row label="契约版本" value={CONTRACT_VERSION} />
          <Row label="本端角色" value={END_TOKEN_ROLES.join(' / ')} />
          <Row label="可用端点数" value={String(ENDPOINT_IDS.length)} />
        </tbody>
      </table>
      <p style={{ marginTop: 16, color: '#666', fontSize: 13 }}>
        端点清单由 <code>frontends/tools/gen-endpoints.py</code> 从冻结契约机械转录，
        请勿手改；契约变更后重跑生成器。
      </p>
    </main>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <tr>
      <td style={{ borderBottom: '1px solid #eee', padding: '8px 0', color: '#666', width: 140 }}>{label}</td>
      <td style={{ borderBottom: '1px solid #eee', padding: '8px 0' }}>{value}</td>
    </tr>
  );
}
