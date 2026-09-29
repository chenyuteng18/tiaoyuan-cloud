#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次十三 · 反向验证（Reversal Validation）—— 证明【RLS 注入机制的「文档机制 vs 实际机制」门禁】有牙齿。

## 本脚本要证明什么

批次十三第二轮实测抓出一条**未登记的真实分歧**，并把模糊数字纠正为实测值：

| 事实 | 实测值 | 说明 |
|---|---|---|
| `@RlsScoped` 的**生产使用数** | **0** | 唯一一处出现是它自己的定义文件（`@interface` 声明，不是使用） |
| README ADR-02 把 `RlsSessionAspect` 记为 **L2 机制** | — | 切点是 `@Before("@annotation(...RlsScoped)")`，匹配数为零 ⇒ **永不触发** |
| 真正承担 RLS 注入的机制 | **18 个类的 `inTenant(tenantId, body)`** | 17 个 dy-app 域仓储 + 1 个 dy-config 支撑类 |
| `SET LOCAL app.tenant_id` 出现处 | **19 处** | 18 个载体各 1 + 切面内 4（该文件另有 2 条告警消息提及） |
| `applyTenantSession` 在**生产代码**中的调用 | **0**（仅定义行） | 既未被切点触发，也未被手工调用 |

新建的 `RlsInjectionRealityGateTest`（6 例）把这条分歧从「读代码才发现」
变成「构建期事实」。但它是**混合型门禁**——既有「断言空集 == 空集」（判据①④）
又有「断言集合相等」（判据②③），**两类都天然会退化成恒绿**
（正则失效 / 剥离器用错粒度 / 路径搬家 / 集合恰好都为空）。
故必须逐组证明它**真的会红**。

## 注入表（六组，覆盖门禁的每一条判据）

| 组 | 注入 | 期望变红 | 守的失效模式 |
|---|---|---|---|
| C1 | 在 `StoreRepository`（一个真实载体）加 `@RlsScoped` 标注 | `the_rls_scoped_annotation_is_never_used_in_production_code` | **L2 被真正启用**（文档机制变成实际机制） |
| C2 | 在 `StoreRepository` 里把 `SET LOCAL app.tenant_id` 改成 `SET LOCAL app.tenant_id_probe` | `every_class_defining_in_tenant_also_sets_the_session_variable` | **空壳入口**（定义了 inTenant 却不设上下文 ⇒ RLS 静默零行） |
| C3 | 新建一个 `ProbeLedger.java`（定义 inTenant 且 SET LOCAL）但不登记 | `the_set_of_in_tenant_carriers_is_exactly_the_registered_set` | **漏登记**（新增载体不表态） |
| C4 | 把 `RlsScoped.java` 重命名为 `.java.bak`（删掉注解定义） | `the_documented_mechanism_classes_still_exist_so_deleting_them_must_be_explicit` | **死机制被静默删除**（代码删了、文档没改） |
| C5 | 在 `CustomerLedger`（另一个真实载体）加一处 `applyTenantSession` 调用 | `the_aspect_method_is_never_called_outside_its_own_definition` | **切面被手工调用**（"上下文有切面兜住"被当成事实） |
| C6 | 破坏剥离器：把字符串态分支改成不剥（用 `stripStrings && false`） | `the_comment_and_string_stripper_has_real_discriminating_power` | **判据①假绿**（"扫不到"≠"不存在"；剥离器坏了也照样 0 命中） |

🛑 **C1/C3/C5 的语义要点**：它们注入的是「**语法合法、可编译**的变更」，
门禁要求变红**不是**因为变更写错了，而是因为**状态被改变了却未同步登记/文档**。
这正是「差异真在」型门禁的牙齿：**改变即红、须显式表态**。

🛑 **C2/C6 是「能力守卫」组**：它们注入的是**真实缺陷本身**
（空壳入口 / 判别力丧失），验证门禁能抓住"坏东西"而不是只在状态漂移时报警。

## 纪律（与 100~113 同口径）

- 锚点预检（存在 + 唯一）· 元层判别力自证（基线零出现）· 逐字节还原 · 还原后复绿
- 🛑 **锚点优先用单行**（技能 8.7）—— 故 C1/C2/C5/C6 全用单行锚点
- 🛑 注入必须保持源文件**语法合法且可编译**（112 的教训：改类名 ⇒ 编译失败 ⇒ 红的是编译器）
- 🛑 判定式 `caught = (exit != 0) and (expect in got)` ——
      `exit != 0` 不足以证明牙齿（可能是编译错误），必须同时要求**期望方法名出现在输出里**
- 🛑 must_see 锚点一律 ASCII（方法名 / 注释符号 / 文件路径）
- 🛑 C3 会**新建**一个文件：还原时必须 `unlink`（快照机制对"新增文件"不适用，单独处理）
- 🛑 Windows：`mvn.cmd` 绝对路径 + list 参数 + `shell=False`

用法：
    python.exe verification/114_rls_injection_reality_reverse_verification.py
"""
from __future__ import annotations

import atexit
import os
import signal
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SKEL = HERE.parent

MAVEN_CMD = r"C:\opt\apache-maven-3.9.9\bin\mvn.cmd"
JAVA_HOME_WIN = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
PG_BIN_WIN = r"C:\Program Files\PostgreSQL\17\bin"

GATE = "RlsInjectionRealityGateTest"
M_ANNOT = "the_rls_scoped_annotation_is_never_used_in_production_code"
M_SHALL = "every_class_defining_in_tenant_also_sets_the_session_variable"
M_CARRIER = "the_set_of_in_tenant_carriers_is_exactly_the_registered_set"
M_ASPECT = "the_aspect_method_is_never_called_outside_its_own_definition"
M_STRIP = "the_comment_and_string_stripper_has_real_discriminating_power"
M_EXIST = "the_documented_mechanism_classes_still_exist_so_deleting_them_must_be_explicit"
METHODS = [M_ANNOT, M_SHALL, M_CARRIER, M_ASPECT, M_STRIP, M_EXIST]

# ---- 被测文件（相对 skeleton 根）----
F_STORE_REPO = "dy-app/src/main/java/com/diaoyuanyun/dy/app/identity/repository/StoreRepository.java"
F_CUSTOMER_LEDGER = "dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/repository/CustomerLedger.java"
F_ANNOTATION = "dy-tenancy/src/main/java/com/diaoyuanyun/dy/tenancy/rls/RlsScoped.java"
F_GATE = "dy-app/src/test/java/com/diaoyuanyun/dy/app/provisioning/RlsInjectionRealityGateTest.java"
F_PROBE_LEDGER = "dy-app/src/main/java/com/diaoyuanyun/dy/app/provisioning/ProbeLedger.java"

# ---- C1：在真实载体里加一处 @RlsScoped 标注（模拟"L2 被真正启用"）----
# 🛑 单行锚点：StoreRepository 的 inTenant 定义那一行恰 1 次；标注加在它前面是语法合法的。
ANCHOR_C1 = "    <T> T inTenant(String tenantId, Supplier<T> body) {"
REPL_C1 = ("    // 反向验证注入：模拟『有人照 README 给方法加上 @RlsScoped』（L2 被真正启用）\n"
           "    @com.diaoyuanyun.dy.tenancy.rls.RlsScoped\n"
           + ANCHOR_C1)

# ---- C2：把某个真实载体的 SET LOCAL 标记改坏（模拟"空壳入口"）----
# 🛑 单行锚点：StoreRepository 里恰 1 处 SET LOCAL app.tenant_id。
ANCHOR_C2 = 'jdbc.execute("SET LOCAL app.tenant_id = \'" + tenantId + "\'");'
REPL_C2 = 'jdbc.execute("SET LOCAL app.tenant_id_probe = \'" + tenantId + "\'");'

# ---- C3：新建一个未登记的载体（模拟"新增 inTenant 载体不表态"）----
PROBE_LEDGER_SRC = """package com.diaoyuanyun.dy.app.provisioning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.function.Supplier;

/** 反向验证注入：一个「定义了 inTenant 但未登记进 LEDGER_CARRIERS」的载体。 */
class ProbeLedger {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    ProbeLedger(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    <T> T inTenant(String tenantId, Supplier<T> body) {
        return tx.execute(status -> {
            jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'");
            return body.get();
        });
    }
}
"""

# ---- C4：删掉注解定义文件（模拟"死机制被静默删除"）----
# 无文本锚点：直接 rename 整个文件。单独处理（见 main）。

# ---- C5：在另一个真实载体里手工调用切面方法（模拟"切面被当成事实"）----
# 🛑 单行锚点：CustomerLedger 的 inTenant 定义那一行恰 1 次。
ANCHOR_C5 = "    <T> T inTenant(String tenantId, Supplier<T> body) {"
REPL_C5 = ("    // 反向验证注入：模拟『有人手工调用切面方法』（把\"切面会自动兜住\"当成事实）\n"
           "    void probeCallAspect() { new com.diaoyuanyun.dy.tenancy.rls.RlsSessionAspect(null)"
           ".applyTenantSession(); }\n"
           + ANCHOR_C5)

# ---- C6：破坏剥离器的字符串态分支（模拟"判别力丧失"）----
# 🛑 单行锚点：门禁里的字符串态进入条件恰 1 次。
ANCHOR_C6 = "} else if (c == '\"' && stripStrings) {"
REPL_C6 = "} else if (c == '\"' && stripStrings && false) {"

# 文本注入表（tag, 文件, 锚点, 替换, 期望红方法）
INJECTIONS = [
    ("C1", F_STORE_REPO, ANCHOR_C1, REPL_C1, M_ANNOT),
    ("C2", F_STORE_REPO, ANCHOR_C2, REPL_C2, M_SHALL),
    ("C5", F_CUSTOMER_LEDGER, ANCHOR_C5, REPL_C5, M_ASPECT),
    ("C6", F_GATE, ANCHOR_C6, REPL_C6, M_STRIP),
]

# C3（新建文件）与 C4（改名文件）单独处理
TAG_CREATE = ("C3", F_PROBE_LEDGER, M_CARRIER)
TAG_REMOVE = ("C4", F_ANNOTATION, M_EXIST)


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
    return env


def run_maven(args: list[str]) -> tuple[int, str]:
    proc = subprocess.run([MAVEN_CMD, "-o"] + args, cwd=str(SKEL), env=build_env(),
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, shell=False)
    return proc.returncode, proc.stdout.decode("utf-8", errors="replace")


def run_gate() -> tuple[int, str]:
    # 🛑 不带 -am：只跑 dy-app（带 -am 会连带跑上游模块的测试）。
    return run_maven([
        "-pl", "dy-app", "test",
        "-Dtest=" + GATE,
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-Dsurefire.failIfNoTests=false",
    ])


def hits(out: str, methods: list[str]) -> set[str]:
    return {m for m in methods if m in out}


_SNAPSHOTS: dict[str, bytes] = {}
_RESTORED = False


def restore_all() -> None:
    global _RESTORED
    if _RESTORED:
        return
    log("\n[还原] 正在逐字节还原被注入的文件 ...")
    # 先删掉本轮可能新建的探测文件
    probe = (SKEL / F_PROBE_LEDGER).resolve()
    if probe.exists():
        probe.unlink()
        log(f"  OK  (已删除新建的探测文件) {F_PROBE_LEDGER}")
    # 再还原可能被改名的文件
    for rel, data in _SNAPSHOTS.items():
        p = (SKEL / rel).resolve()
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
    log("=" * 78)
    log("批次十三 · RLS 注入机制「文档机制 vs 实际机制」门禁 · 反向验证（114）")
    log("=" * 78)

    # ---- 快照 ----
    for rel in {F_STORE_REPO, F_CUSTOMER_LEDGER, F_ANNOTATION, F_GATE}:
        p = (SKEL / rel).resolve()
        assert p.is_file(), f"被测文件不存在：{p}"
        _SNAPSHOTS[rel] = p.read_bytes()
        log(f"[快照] {rel} bytes={len(_SNAPSHOTS[rel])}")

    # 🛑 前置：探测文件不得已存在（否则会污染还原逻辑）
    probe = (SKEL / F_PROBE_LEDGER).resolve()
    if probe.exists():
        log(f"[前置失败] 探测文件已存在，请先删除：{probe}")
        return 2

    texts = {rel: data.decode("utf-8") for rel, data in _SNAPSHOTS.items()}

    # ---- 锚点预检：存在且恰 1 次 ----
    problems = []
    for tag, rel, anchor, _repl, _expect in INJECTIONS:
        n = texts[rel].count(anchor)
        ok = (n == 1)
        shown = anchor.replace("\n", "\\n")[:60]
        log(f"[预检 {'OK ' if ok else 'BAD'}] {tag} 锚点出现 {n} 次 :: {shown}")
        if not ok:
            problems.append(f"{tag}: 锚点出现 {n} 次（要求恰 1 次）")
    if problems:
        log("\n[预检失败] " + " | ".join(problems))
        return 2

    # ---- 基线：注入前必须全绿 ----
    log("\n[基线] 注入前跑门禁（期望 6 例全绿）...")
    c0, out0 = run_gate()
    if c0 != 0:
        log(f"[基线失败] 注入前门禁未通过（exit={c0}）—— 后续结论无意义")
        log(out0[-4000:])
        return 2
    pre = hits(out0, METHODS)
    if pre:
        log(f"[元层自证失败] 基线（全绿）输出里已出现待查方法名 {sorted(pre)} —— 锚点恒真，无判别力")
        return 2
    log("[基线 OK] 注入前全绿，且 6 个待查方法名在基线中【零出现】（锚点有判别力）")

    results = []

    # ---- 逐组文本注入 ----
    for tag, rel, anchor, repl, expect in INJECTIONS:
        log("\n" + "-" * 78)
        log(f"[{tag}] 注入 {rel}：{anchor.replace(chr(10), ' / ')[:66]} ...")
        p = (SKEL / rel).resolve()
        text = _SNAPSHOTS[rel].decode("utf-8")
        assert text.count(anchor) == 1, f"{tag} 锚点不唯一"
        p.write_text(text.replace(anchor, repl), encoding="utf-8", newline="")

        code2, out2 = run_gate()
        got = hits(out2, METHODS)
        caught = (code2 != 0) and (expect in got)
        log(f"[{tag}] 门禁 exit={code2} · 期望红 `{expect}` 被抓={caught} · 红集={sorted(got)}")
        if not caught:
            tail = [ln for ln in out2.splitlines()
                    if ("RlsInjectionReality" in ln or "ERROR" in ln
                        or "AssertionFailed" in ln or "具" in ln or "必须" in ln)]
            log("   —— 诊断片段 ——")
            for ln in tail[:14]:
                log("   " + ln[:180])
        results.append((tag, expect, caught, code2, sorted(got)))

        p.write_bytes(_SNAPSHOTS[rel])
        assert p.read_bytes() == _SNAPSHOTS[rel], f"{tag} 还原后字节不一致"

    # ---- C3：新建一个未登记的载体 ----
    log("\n" + "-" * 78)
    tag, rel, expect = TAG_CREATE
    log(f"[{tag}] 新建未登记的 inTenant 载体：{rel} ...")
    p = (SKEL / rel).resolve()
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(PROBE_LEDGER_SRC, encoding="utf-8", newline="")
    code3, out3 = run_gate()
    got3 = hits(out3, METHODS)
    caught3 = (code3 != 0) and (expect in got3)
    log(f"[{tag}] 门禁 exit={code3} · 期望红 `{expect}` 被抓={caught3} · 红集={sorted(got3)}")
    if not caught3:
        tail = [ln for ln in out3.splitlines()
                if ("RlsInjectionReality" in ln or "ERROR" in ln or "AssertionFailed" in ln)]
        log("   —— 诊断片段 ——")
        for ln in tail[:14]:
            log("   " + ln[:180])
    results.append((tag, expect, caught3, code3, sorted(got3)))
    if p.exists():
        p.unlink()
    assert not p.exists(), f"{tag} 还原失败：探测文件仍存在"

    # ---- C4：删掉注解定义文件（改名，可完整还原）----
    log("\n" + "-" * 78)
    tag, rel, expect = TAG_REMOVE
    log(f"[{tag}] 临时移走注解定义文件：{rel} ...")
    p = (SKEL / rel).resolve()
    moved = p.with_suffix(p.suffix + ".probe_removed")
    p.rename(moved)
    try:
        code4, out4 = run_gate()
        got4 = hits(out4, METHODS)
        caught4 = (code4 != 0) and (expect in got4)
        log(f"[{tag}] 门禁 exit={code4} · 期望红 `{expect}` 被抓={caught4} · 红集={sorted(got4)}")
        if not caught4:
            tail = [ln for ln in out4.splitlines()
                    if ("RlsInjectionReality" in ln or "ERROR" in ln or "AssertionFailed" in ln)]
            log("   —— 诊断片段 ——")
            for ln in tail[:14]:
                log("   " + ln[:180])
        results.append((tag, expect, caught4, code4, sorted(got4)))
    finally:
        moved.rename(p)
        assert p.read_bytes() == _SNAPSHOTS[rel], f"{tag} 还原后字节不一致"

    # ---- 复绿 ----
    log("\n[复绿] 全部还原后重跑门禁（期望全绿）...")
    c5, out5 = run_gate()
    green = (c5 == 0)
    log(f"[复绿] exit={c5} · 复绿={green}")
    if not green:
        log(out5[-2000:])

    # ---- 还原后核验：所有文件字节一致 + 探测文件不存在 ----
    log("\n[还原核验] 逐文件比对字节 ...")
    all_restored = True
    for rel, data in _SNAPSHOTS.items():
        cur = (SKEL / rel).resolve().read_bytes()
        ok = (cur == data)
        all_restored = all_restored and ok
        log(f"  {'OK ' if ok else 'BAD'} {rel}  bytes={len(cur)}/{len(data)}")
    leftover = (SKEL / F_PROBE_LEDGER).resolve()
    if leftover.exists():
        all_restored = False
        log(f"  BAD 探测文件残留：{F_PROBE_LEDGER}")
    log(f"[还原核验] {'全部逐字节一致且无残留' if all_restored else '存在不一致/残留'}")

    log("\n" + "=" * 78)
    log("汇 总")
    log("=" * 78)
    for tag, expect, caught, code, got in results:
        log(f"  [{'PASS' if caught else 'FAIL'}] {tag}: 期望 `{expect}` 变红 · exit={code} · 实际红集={got}")
    log(f"  [{'PASS' if green else 'FAIL'}] 还原后复绿")
    log(f"  [{'PASS' if all_restored else 'FAIL'}] 逐字节还原 + 无残留")

    passed = sum(1 for _t, _e, c, _c2, _g in results if c) + (1 if green else 0) + (1 if all_restored else 0)
    total = len(results) + 2
    log(f"\n结论: {passed}/{total} {'PASS' if passed == total else 'FAIL'}")
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())