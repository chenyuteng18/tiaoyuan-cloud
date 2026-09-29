#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次十一 · 反向验证（Reversal Validation）—— 证明【跨源一致性门禁】与【契约驱动角色矩阵】有牙齿。

## 本脚本要证明什么

批次十一收口两件事，各自新增一类"以前没有的东西"：

### A. `RefundDomainCrossSourceGateTest`（N-8 跨源一致性门禁，9 例）

它把"退款域库侧列 × 契约 schema 字段"的差异钉成**可机械清点的集合**。它守的六条：

| 测试 | 它守的 |
|---|---|
| `library_columns_are_extracted_mechanically` | ① 库侧列机械提取 + **12 列金丝雀**（防解析器把内联 CHECK 续行的 `OR` 读成列） |
| `contract_refund_create_request_lacks_the_source_fields` | ② 契约侧确实没有 `requested_at_source`（缺口真实存在） |
| `contract_receipt_data_only_declares_receipt_state` | ③ 契约侧 `RefundReceiptData` 只有 `receipt_state` |
| `every_library_column_is_either_covered_or_registered` | ④ 双向覆盖：库侧每列非被覆盖即被登记，无"第三个" |
| `registered_gaps_have_no_zombie_entries` | ⑤ 僵尸登记：登记表每条必须真的在库侧存在 |
| `generic_audit_columns_are_read_from_the_dictionary` | ⑥ 豁免自证：通用审计列读自字典、不得吞业务列（`operator_id`） |
| `g5_receipt_endpoint_has_no_request_body` | ⑦ G5 无 requestBody 的契约事实 |

### B. `RefundWritePathMatrixE2ETest`（C-3 写路径矩阵，16 例）

其中**新增**的是"角色集由契约展开、不再手抄"这一层：

| 测试 | 它守的 |
|---|---|
| `callable_token_roles_are_derived_from_the_contract` | ⑧ `x-callable-roles` × `x-roles` 展开结果 = 显式期望值 |
| `the_endless_role_expands_to_nothing` | ⑨ 无端角色（`token-role: null`）不得进入矩阵 |
| `g1/g3/g5_is_reachable_by_all_contract_callable_staff_roles` | ⑩ 展开出的每个角色都必须走过装配链 |

## 注入表

| 组 | 注入内容 | 期望变红 |
|---|---|---|
| C1 | 契约 `RefundCreateRequest` 补 `requested_at_source`（模拟上游补齐 N-8） | ② `..._lacks_the_source_fields` |
| C2 | 库侧 `refund_receipt.template_id` 改名（模拟列被改） | ⑤ `..._zombie_entries` + ④ `..._either_covered_...` |
| C3 | 字典「审计字段（全表必带）」去掉 `tenant_id` | ⑥ `..._read_from_the_dictionary` |
| C4 | 契约 G3 的 `x-callable-roles` 加 `therapist`（模拟契约角色面变更） | ⑧ `..._derived_from_the_contract` + ⑩ `..._reachable_by_all_...` |
| C5 | 契约 `x-roles.admin.token-role` 去掉 `hq`（模拟角色展开面变更） | ⑧ `..._derived_from_the_contract` |
| C6 | 契约 `x-roles.store_customer_service.token-role` 由 null 改成 `meridian` | ⑨ `..._endless_role_...` |

## 纪律（与 102~108 同口径）

- **锚点预检**（存在 + 唯一）· **元层判别力自证**（基线零出现）· **逐字节还原** · **还原后复绿**。
- 🛑 **注入必须保持源文件语法合法**：全部只改 YAML / SQL 的字面量或行，
  不改 Java 结构；C2 只改列名标识符（仍是合法 DDL）。
- 🛑 **must_see 锚点必须 ASCII**（方法名）—— Windows GBK 下中文断言消息可能乱码。
- 🛑 Windows 下用 `mvn.cmd` 绝对路径 + list 参数 + `shell=False`；**不加 `-q`**。
- 🛑 跨模块注入：C1/C4/C5/C6 改的是**仓库根 `contract/openapi-v1.0.0.yaml`**
  （测试通过 `resolveRepoRoot()` 直读**文件**，不走 classpath）⇒ **无需 install**。
  C2/C3 改的是 `skeleton/dy-app/...`（同模块）与 `_work/...`（直读文件）⇒ 同样无需 install。

用法：
    python.exe verification/109_cross_source_and_contract_driven_reverse_verification.py
"""
from __future__ import annotations

import atexit
import os
import signal
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SKEL = HERE.parent           # .../skeleton
ROOT = SKEL.parent           # .../product-strategy（仓库根）

MAVEN_CMD = r"C:\opt\apache-maven-3.9.9\bin\mvn.cmd"
JAVA_HOME_WIN = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
PG_BIN_WIN = r"C:\Program Files\PostgreSQL\17\bin"

MODULE = "dy-app"
GATE_A = "RefundDomainCrossSourceGateTest"
GATE_B = "RefundWritePathMatrixE2ETest*"

# --- A 组（跨源门禁）方法名 ---
A_COLS = "library_columns_are_extracted_mechanically"
A_LACKS = "contract_refund_create_request_lacks_the_source_fields"
A_ONLY = "contract_receipt_data_only_declares_receipt_state"
A_COVER = "every_library_column_is_either_covered_or_registered"
A_ZOMBIE = "registered_gaps_have_no_zombie_entries"
A_DICT = "generic_audit_columns_are_read_from_the_dictionary"
A_G5 = "g5_receipt_endpoint_has_no_request_body"
A_ALL = [A_COLS, A_LACKS, A_ONLY, A_COVER, A_ZOMBIE, A_DICT, A_G5]

# --- B 组（契约驱动矩阵）方法名 ---
B_DERIVE = "callable_token_roles_are_derived_from_the_contract"
B_ENDLESS = "the_endless_role_expands_to_nothing"
B_REACH = "is_reachable_by_all_contract_callable_staff_roles"
B_ALL = [B_DERIVE, B_ENDLESS, B_REACH]

# 路径均相对仓库根（ROOT）—— 只读文件锚点，不涉 classpath
F_OPENAPI = "contract/openapi-v1.0.0.yaml"
F_V6 = ("skeleton/dy-app/src/main/resources/db/migration/"
        "V6__refund_domain_alignment_and_ledgers.sql")
F_DICT = "_work/data-dict-entities-ddl-2026-09-19.md"

# G3 挽留端点在契约里的 x-callable-roles 行（唯一性预检保证）
G3_ROLES_ANCHOR = "      operationId: createRetention"

INJECTIONS = [
    (
        "C1", F_OPENAPI,
        "        requested_at: { type: string, format: date-time, description: 最早且可核实 }",
        "        requested_at_source: { type: string }\n"
        "        requested_at: { type: string, format: date-time, description: 最早且可核实 }",
        A_LACKS, False,
    ),
    (
        "C2", F_V6,
        "    template_id     VARCHAR(64),",
        "    template_id_x     VARCHAR(64),",
        A_ZOMBIE, False,
    ),
    (
        "C3", F_DICT,
        "**审计字段（全表必带）**：`tenant_id`(FK)",
        "**审计字段（全表必带）**：`tenant_zz`(FK)",
        A_DICT, False,
    ),
    (
        "C4", F_OPENAPI,
        "      x-contract-row: G3\n      x-callable-roles: [meridian, admin]",
        "      x-contract-row: G3\n      x-callable-roles: [meridian, therapist, admin]",
        B_DERIVE, False,
    ),
    (
        "C5", F_OPENAPI,
        "  admin:\n    token-role: [manager, area, hq]",
        "  admin:\n    token-role: [manager, area]",
        B_DERIVE, False,
    ),
    (
        "C6", F_OPENAPI,
        "  store_customer_service:\n    token-role: null",
        "  store_customer_service:\n    token-role: meridian",
        B_ENDLESS, False,
    ),
]


def log(msg: str) -> None:
    print(msg, flush=True)


def build_env() -> dict:
    env = dict(os.environ)
    java_home = env.get("JAVA_HOME")
    if not java_home or not Path(java_home).is_dir():
        java_home = JAVA_HOME_WIN
    env["JAVA_HOME"] = java_home
    parts = [str(Path(java_home) / "bin"), PG_BIN_WIN, env.get("PATH", "")]
    env["PATH"] = os.pathsep.join(p for p in parts if p)
    env.setdefault("DY_PG_HOST", "127.0.0.1")
    env.setdefault("DY_PG_PORT", "5432")
    env.setdefault("DY_PG_SUPER_PASSWORD", "postgres")
    return env


def run_maven(args: list[str]) -> tuple[int, str]:
    proc = subprocess.run([MAVEN_CMD, "-o"] + args, cwd=str(SKEL), env=build_env(),
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, shell=False)
    return proc.returncode, proc.stdout.decode("utf-8", errors="replace")


def run_gates() -> tuple[int, str]:
    """同时跑 A/B 两个门禁（B 用通配符以覆盖 @Nested 分组）。"""
    return run_maven([
        "-pl", MODULE, "test",
        "-Dtest=" + GATE_A + "," + GATE_B,
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-Dsurefire.failIfNoTests=false",
    ])


def hits(out: str, methods: list[str]) -> set[str]:
    return {m for m in methods if m in out}


_SNAPSHOTS: dict[str, bytes] = {}
_RESTORED = False


def snapshot(rel: str) -> bytes:
    data = (ROOT / rel).read_bytes()
    _SNAPSHOTS[rel] = data
    return data


def restore_all() -> None:
    global _RESTORED
    if _RESTORED:
        return
    log("\n[还原] 正在逐字节还原被注入的文件 ...")
    for rel, data in _SNAPSHOTS.items():
        p = ROOT / rel
        p.write_bytes(data)
        now = p.read_bytes()
        log(f"  {'OK ' if now == data else 'BAD'} {rel}  bytes={len(now)}/{len(data)}")
    _RESTORED = True


def _sig(signum, frame):  # noqa: ARG001
    restore_all()
    sys.exit(1)


atexit.register(restore_all)
signal.signal(signal.SIGINT, _sig)
signal.signal(signal.SIGTERM, _sig)


def main() -> int:
    log("=" * 74)
    log("批次十一 · 跨源一致性门禁 + 契约驱动角色矩阵 · 反向验证")
    log("=" * 74)

    for rel in {F_OPENAPI, F_V6, F_DICT}:
        snapshot(rel)
        log(f"[快照] {rel} bytes={len(_SNAPSHOTS[rel])}")

    texts = {rel: _SNAPSHOTS[rel].decode("utf-8") for rel in _SNAPSHOTS}
    problems = []
    for tag, rel, anchor, _repl, _expect, _inst in INJECTIONS:
        n = texts[rel].count(anchor)
        log(f"[预检 {'OK ' if n == 1 else 'BAD'}] {tag} 锚点出现 {n} 次")
        if n != 1:
            problems.append(f"{tag}: 锚点出现 {n} 次（要求恰 1 次）")
    if problems:
        log("\n[预检失败] " + " | ".join(problems))
        return 2

    log("\n[基线] 注入前跑两个门禁（期望全绿）...")
    code, out = run_gates()
    if code != 0:
        log("[基线失败] 注入前门禁未通过 —— 后续结论无意义")
        log(out[-3000:])
        return 2
    allm = A_ALL + B_ALL
    if hits(out, allm):
        log(f"[元层自证失败] 基线（全绿）输出里已出现待查方法名 {sorted(hits(out, allm))} "
            f"—— must_see 锚点恒真，本脚本无判别力")
        return 2
    log("[基线 OK] 注入前全绿，且待查方法名在基线中【零出现】（锚点有判别力）")

    results = []
    for tag, rel, anchor, repl, expect, need_install in INJECTIONS:
        log(f"\n[{tag}] 注入 {rel}：{anchor[:56].replace(chr(10), ' / ')}...")
        p = ROOT / rel
        text = _SNAPSHOTS[rel].decode("utf-8")
        assert text.count(anchor) == 1
        p.write_text(text.replace(anchor, repl), encoding="utf-8", newline="")

        code2, out2 = run_gates()
        got = hits(out2, allm)
        caught = (code2 != 0) and (expect in got)
        log(f"[{tag}] 门禁 exit={code2} · 期望红 `{expect}` 被抓={caught} · 红集={sorted(got)}")
        results.append((tag, expect, caught, code2, sorted(got)))

        p.write_bytes(_SNAPSHOTS[rel])
        assert p.read_bytes() == _SNAPSHOTS[rel], f"{tag} 还原后字节不一致"

    log("\n[复绿] 全部还原后重跑两个门禁（期望全绿）...")
    code3, out3 = run_gates()
    green = code3 == 0
    log(f"[复绿] exit={code3} 复绿={green}")

    log("\n" + "=" * 74)
    log("汇 总")
    log("=" * 74)
    passed = sum(1 for _t, _e, c, _c2, _g in results if c)
    for tag, expect, caught, code, got in results:
        log(f"  [{'PASS' if caught else 'FAIL'}] {tag}: 期望 `{expect}` 变红 · exit={code} · 实际红集={got}")
    log(f"  [{'PASS' if green else 'FAIL'}] 还原后复绿")
    total = len(results) + 1
    ok = passed + (1 if green else 0)
    log(f"\n总体：{ok}/{total} 通过")
    log("=" * 74)
    return 0 if ok == total else 1


if __name__ == "__main__":
    sys.exit(main())