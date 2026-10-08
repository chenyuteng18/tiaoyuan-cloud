# 调元云 · 生产部署手册（单机商用起步形态）

> 目标读者：负责把本系统部署到一台 Linux 服务器的运维/研发。
> 形态：Docker Compose 单机（Nginx TLS → app → PG/Redis 内网）。
> 规模假设：单实例起步；多副本扩容要点见文末"扩容"。

---

## 0. 前置清单

| 项 | 要求 |
| --- | --- |
| 服务器 | Linux x86_64，≥2C4G，Docker Engine ≥ 24 + compose 插件 |
| 域名 | 一个解析到服务器公网 IP 的域名（HTTPS 证书用） |
| 端口 | 80 / 443 对公网开放；**5432 / 6379 / 8080 一律不对公网开放** |
| 证书 | Let's Encrypt（certbot）或商业证书，产出 `fullchain.pem` / `privkey.pem` |
| 仓库 | `git clone` 本仓库（**必须已是私有仓库**） |

## 1. 首次部署（十分钟路径）

```bash
# ① 代码
git clone <repo> /opt/diaoyuanyun && cd /opt/diaoyuanyun

# ② 环境变量
cd deploy
cp .env.production.example .env && chmod 600 .env
vi .env          # 逐项填写（全部 CHANGE_ME 必须替换；DY_JWT_SECRET 用 openssl rand -base64 48）

# ③ 证书
mkdir -p certs && cp /path/fullchain.pem /path/privkey.pem certs/
#   Let's Encrypt 签发（HTTP-01）示例：
#   certbot certonly --standalone -d your.domain && 拷贝或软链到 certs/

# ④ 起服务
docker compose -f docker-compose.prod.yml --env-file .env up -d --build

# ⑤ 验收（全过才算部署完成）
docker compose -f docker-compose.prod.yml ps                    # 四容器 healthy
curl -fsS https://your.domain/actuator/health                   # {"status":"UP"...}
curl -fsS -X POST https://your.domain/api/v1/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"account":"...","credential":"...","client_end":"web"}'   # 200 + token
curl -fsS https://your.domain/api/v1/audit/log-chain -H "Authorization: Bearer <上一步的token>"
```

**⑤ 是验收门，不是建议**：登录闭环（A1→A2）与审计链自检（/audit/log-chain）是两
条"挂了也不报错"的静默链路，只有真调一次才算数。

## 2. 初始化数据

- Flyway 在应用启动时自动跑迁移（V1→V23，含 RLS、配置真相源、凭证表）。
- 首个租户与首个账号的开通走内部开通流程（不要在库上手插生产数据；
  如需紧急开通，用 `auth_credential` 表的哈希格式串必须由 `PasswordHasher` 产出）。

## 3. 日常运维

### 备份（必须挂上）
```bash
crontab -e
# 30 2 * * * /opt/diaoyuanyun/deploy/backup.sh >> /var/log/dy-backup.log 2>&1
```
- 每日 02:30 逻辑备份到 `deploy/backups/`，保留 14 份，头字节自检（非 PGDMP 即报警）。
- **恢复演练**：上线首月内至少做一次 `restore.sh` 全流程演练（含应用健康复查），
  没演练过的备份等于没有备份。

### 日志
- 应用：`docker compose logs app --since 1h`（json-file，50m×10 轮转）。
- 拒绝留痕：401/403 均经 `TenantRejectionListener` 落审计链，可在 `/audit/log-chain` 复核。

### 升级发布
```bash
cd /opt/diaoyuanyun && git pull
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build app
```
- 迁移只进不退：任何"回滚版本"都不得回滚 Flyway（V23 头注明的回滚风险清单先读）。

## 4. 安全基线（部署完成当天下班前逐条核对）

1. **仓库转私有**（GitHub Settings → Danger Zone）——public 状态的商用代码必须当天处理。
2. `.env` 权限 600；`deploy/backups/` 与 `deploy/certs/` 不进 git。
3. 服务器防火墙只放 80/443 + SSH（SSH 建议改密钥登录）。
4. CI 仓库设置里把 4 个 workflow 设为 **required check**（工作流本身拦不住合并）。
5. JWT 密钥 / 主密钥的轮换预案：换 `DY_JWT_SECRET` = 全员重新登录（可接受的停机面）；
   `DY_MASTER_KEY` 轮换前必须先读 dy-crypto 的 KEK 文档，**不得直接换**。

## 5. 扩容要点（单机 → 多副本）

- 幂等后端已是 redis（prod 下 memory 会被 `IdempotencyConfiguration` 启动自检拒绝）——
  多副本前提天然满足。
- `auth_credential` 登录读路径是单行定点读，无状态，可直接加副本。
- PostgreSQL 先垂直升配，再考虑主从；审计链是全局单链，**禁止**分库。
- Nginx `upstream` 加 server 行（见 nginx.conf 注释），会话无粘性要求（JWT 无状态）。

## 6. 监控与告警手册（商用开发第二批 · E2）

机器可读入口：`GET /api/v1/ops/health`（总部服务账号，Bearer token）。
一次调用返回四项自检：`db`（连通 + 已登记迁移数）/ `redis`（PING；未装配如实报
`not_configured`）/ `audit_chain`（审计哈希链完整性，含 `checked` 条数）/
`overall`（UP / DEGRADED）。**健康端点自身不缓存**——缓存会让"刚断的链"延迟暴露。

### 6.1 巡检与阈值

| 检查项 | 命令/来源 | 告警阈值 | 级别 | 告警动作 |
| --- | --- | --- | --- | --- |
| 服务进程 | `curl -sf http://127.0.0.1:8080/actuator/health` 或容器 healthcheck | 连续 3 次（≈1 分钟）非 200 | P1 | 重启容器；5 分钟内两次自动重启仍失败 → 值班人介入 |
| 聚合自检 | `GET /api/v1/ops/health` → `overall` | `DEGRADED` 或非 200，连续 2 次（间隔 ≥60s） | P1 | 按 6.2 分项定位 |
| 审计链 | `overall` 报告里的 `audit_chain.status` | `BROKEN` | **P0** | 立即冻结对应时段业务操作，按 §4 安全基线排查篡改；**禁止**任何人"重算回写"（见 audit_log 的纪律） |
| 迁移一致性 | `db.registered_migrations` 与发布说明的迁移数比对 | 数值偏小 | P1 | 发布半途失败，重新执行部署第 1 节第 5 步 |
| 备份产物 | `ls -l /var/backups/diaoyuanyun/` 最新文件时间 | 距今 > 26h | P2 | 手工执行 `backup.sh` 并排查 cron |
| 登录限流 | Nginx access log 中 `/auth/login` 的 429 数 | 1 小时 > 500 次 | P2 | 疑似撞库，按 §4 处理（换密钥前先看 §3 的吊销流程） |
| 磁盘 | `df -h /var/lib/postgresql` | 使用率 > 80% | P2 | 扩容或归档旧备份 |

### 6.2 `DEGRADED` 分项定位（按报告字段）

1. `db.status=DOWN` —— 数据库不可达/凭证失效：`docker compose -f docker-compose.prod.yml ps`
   看容器状态 → `docker compose logs postgres --tail 50`；**先看日志再重启**（重启会掩盖 OOM 证据）。
2. `redis.status=DOWN` —— 吊销黑名单退化为 fail-open（登出延迟生效，不是全线故障）：
   `docker compose logs redis --tail 30`；修复前登出/强制下线依赖 12h 的 token 过期兜底。
3. `audit_chain.status=BROKEN` —— **P0**：`broken_at` 是第一条断链记录的 ID、`reason`
   是断链种类（HASH_MISMATCH=记录被改写 / PREV_HASH_MISMATCH=中间有删除 /
   GENESIS_MISMATCH=链头被动）。保留现场，导出 `docker compose logs dy-app` 与
   该时段访问日志，再谈恢复。
4. `redis.status=not_configured` —— 不是故障：吊销黑名单未装配（可选项），
   按 §1 第 3 步的 `DY_AUTH_REVOCATION` 配置项补配即可。

### 6.3 巡检脚本骨架（cron 每 5 分钟）

```bash
#!/usr/bin/env bash
set -euo pipefail
# TOKEN：用总部服务账号走 /auth/login 获取（过期前重登），不要把 token 写进 cron 文件
REPORT=$(curl -sf -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/v1/ops/health)
echo "$REPORT" | grep -q '"overall":"UP"' || {
  echo "[P1] ops/health not UP: $REPORT" | tee -a /var/log/dy-alert.log
  # 接告警通道（邮件/IM webhook），P0 时同时电话值班人
}
```

> 🛑 告警纪律：`audit_chain=BROKEN` 永远是 P0 —— 证据链完整性是合规审计的底线，
> 任何"先修链再说"的操作都可能把一次篡改洗成一次故障。先取证，后恢复。
