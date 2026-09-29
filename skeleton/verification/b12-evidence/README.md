# B12 证据目录（V2 迁移 + 真库验证）

生成方式：`bash skeleton/verification/99_b12_run.sh`
（并另有 `90_b12_injection.sh` 注入实验、`92_b12_fk_closure_probe.sh` FK 可达性探针）

## 当前有效的证据文件

| 文件 | 内容 | 判读 |
|---|---|---|
| `00-reset.out` | 重建专属库 `diaoyuanyun_rls_test_b12` + 非超级用户角色 | 前置步骤 |
| `01-apply-chain.out` | 整链 V1+V2 首次应用 | `CREATE TABLE/INDEX` 正常 |
| `02-reapply-v2.out` | **只重放 V2** | 无报错 → V2 幂等 |
| `schema-A-after-chain.txt` | 应用整链后的 schema 指纹（100 行） | 与 B、C 逐字节比对 |
| `schema-B-after-v2-reapply.txt` | 重放 V2 后的 schema 指纹 | **A == B** → V2 幂等实证 |
| `schema-C-after-v1-reapply.txt` | 重放 V1 后的 schema 指纹 | **A == C** → V1 幂等实证（2026-09-24 新增） |
| `rows-A-after-chain.txt` / `rows-B-after-v2-reapply.txt` | 行数摘要 | 两次均为 `schema_migration=1 cst=0 band=0 V2_rows=1`，未重复插行 |
| `04-v1-reapply-must-succeed.out` | **只重放 V1（基线）** | **预期成功**（EXIT=0）—— V1 已补 `DROP POLICY IF EXISTS`，幂等成立 |
| `05-seed.out` | 非超级用户灌种子 | 证明策略连通 |
| `06-assert.out` | 断言 B0–B12 全绿 | `ALL B-ENTITY ASSERTIONS PASSED` |
| `fk-closure-probe.out` | 条件式 FK 闭合命中路径实测 | 目标表存在→加 FK；重复→跳过 |
| `injection/` | 4 个注入情形（基线 + 3 注入） | 4/4 变红符合预期，见 `injection/injection.log` |
| `run.log` | 上述全部的最后一次完整跑批日志 | 总控 EXIT=0 |

## 🔴 2026-09-24 变更：V1 幂等期望翻转（S1-2 验收④）

**背景**：S1-2 验收④ 明令「迁移脚本**连跑 2 次幂等**、无报错」。
按该验收项对整链（V1→V5）连跑两轮，V1 在第二轮以
`42710 duplicate_object`（`用于表"customer"的策略"tenant_isolation"已经存在`）整体失败 ——
**V1 基线本身不幂等**，验收④ 在 V1 这一步就不成立。

**处置**：给 `V1__baseline_tenant_rls.sql` 的 `CREATE POLICY` 补前置
`DROP POLICY IF EXISTS tenant_isolation ON customer;`。
**策略定义一字未改**（`FOR ALL` + `USING`/`WITH CHECK` 双 `NULLIF` 表达式与修复前逐字符相同），
仅让重放安全。依据 = S1-2 验收④；V1 原先被视为「不可动的既有基线」，
该前提被验收④ 明令推翻。

**本目录随之翻转的期望**（不是删除反向验证，而是换射程）：

| 项 | 旧期望 | 新期望 |
|---|---|---|
| 04 步「只重放 V1」 | **必须失败**于 `duplicate_object` | **必须成功**，且 `schema-C == schema-A`（逐字节） |

- 旧证法（只覆盖 V2、V1 显式豁免）→ 新证法覆盖**整条链**，证明强度更高。
- 旧文件 `04-v1-reapply-expect-fail.out` 已移入 `superseded/`，**不得再作现行证据引用**。
- 反向验证**未被删除**：若有人往 V1 塞回非幂等语句或删掉 `DROP POLICY` 守卫，
  04 步会以「重复应用失败」报红。`90_b12_injection.sh` 仍 4/4 通过，harness 有牙齿的性质保留。

## `superseded/` —— 被废弃设计产生的陈旧证据

这些文件来自本任务**早期被推翻的实现路线**（当时脚本把整链跑两遍来证幂等，
因 V1 的裸 `CREATE POLICY` 必然报错而放弃，改为「整链跑一次 + 只重放 V2」）。

- `01-apply-pass1.out` / `02-apply-pass2.out` / `schema-after-pass1.txt`
- `case-A-no-to_state-check/`（早期删 CHECK 的注入式，会产生非法 SQL，已改为「放宽取值集」）
- `case-B-no-rls-policy/`（早期删策略的注入式，会让 seed 全拒，已改为「fail-open 谓词」）

⚠️ **不要拿这些文件当现行证据引用**：`schema-after-pass1.txt` 与当前
`schema-A-after-chain.txt` 内容相同但含义已不同（前者属于两步跑法）。
保留它们只为追溯设计演进，不参与任何断言。
