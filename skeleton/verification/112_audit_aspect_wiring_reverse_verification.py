#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次十三 · 反向验证（Reversal Validation）—— 证明【AuditFillAspect 接线状态门禁】有牙齿。

## 本脚本要证明什么

批次十三实测抓出：`AuditFillAspect`（README 列为 ADR-09 落地物）是一对**从未接线的死代码** ——
切点 `..save(..)` / `..persist(..)` 全库零匹配、`AuditableEntity` 零子类。
新建的 `AuditFillAspectWiringGateTest`（4 例）把"接线状态"从人写的结论变成**构建期可断言的事实**。

但"断言空集 == 空集"型门禁**天然有退化成恒绿的风险**（目录搬家 / 正则失效 / 注释未剥）。
故必须证明它**真的会红**。

## 注入表

| 组 | 注入 | 期望变红 | 守的失效模式 |
|---|---|---|---|
| C1 | 在 `dy-app` 某 Ledger 里加一个 `save(...)` **方法定义** | `the_pointcut_matches_no_method_definition_in_production_sources` | **真接线**（有人给切面接上了） |
| C2 | 让某实体类 `extends AuditableEntity` | `auditable_entity_has_no_subclass_in_production_sources` | **载体到位** |
| C3 | 摘掉切面文件（模拟"把切面删了"） | `the_aspect_and_its_pointcut_annotation_still_exist` | **删掉切面但门禁还绿** |

🛑 **C1/C2 的语义要点**：它们注入的是"**合法且正确**的接线动作"，门禁要求变红**不是**因为接线错了，
而是因为"接线是显式动作、必须连同步文档登记一起做"。这正是"差异真在"型门禁的牙齿：
**修好即红、须显式移除登记**。

## 纪律（与 100~111 同口径）

- 锚点预检（存在 + 唯一）· 元层判别力自证 · 逐字节还原 · 还原后复绿
- 🛑 **锚点优先用单行**（技能 8.7）
- 🛑 注入必须保持源文件语法合法
- 🛑 must_see 锚点一律 ASCII（方法名）
- 🛑 只改 dy-app / dy-audit ⇒ 只跑 dy-audit（被测模块）
- 🛑 Windows：`mvn.cmd` 绝对路径 + list 参数 + `shell=False`

用法：
    python.exe verification/112_audit_aspect_wiring_reverse_verification.py
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

GATE = "AuditFillAspectWiringGateTest"
M_POINTCUT = "the_pointcut_matches_no_method_definition_in_production_sources"
M_SUBCLASS = "auditable_entity_has_no_subclass_in_production_sources"
M_ASPECT = "the_aspect_and_its_pointcut_annotation_still_exist"
METHODS = [M_POINTCUT, M_SUBCLASS, M_ASPECT]

F_LEDGER = "dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/repository/ConsentLedger.java"
F_STATE = "dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/domain/CustomerState.java"
F_ASPECT = "dy-audit/src/main/java/com/diaoyuanyun/dy/audit/aspect/AuditFillAspect.java"

# C1：在某 Ledger 类里插入一个 save(...) 方法定义（模拟"真接线"）。
ANCHOR_C1 = "public class ConsentLedger {"
REPL_C1 = ("public class ConsentLedger {\n\n"
           "    /** 反向验证注入：模拟'给 AuditFillAspect 接线'（新增 save(..) 方法定义）。 */\n"
           "    public void save(Object entity) {\n"
           "        throw new UnsupportedOperationException(\"reverse-verification probe\");\n"
           "    }\n")

# C2：新建一个"载体"探针源文件（模拟 AuditableEntity 有了子类）。该文件由脚本创建 / 删除。
F_PROBE = "dy-app/src/main/java/com/diaoyuanyun/dy/app/ReverseProbeAuditable.java"
PROBE_BODY = """package com.diaoyuanyun.dy.app;

import com.diaoyuanyun.dy.common.audit.AuditableEntity;

/** 反向验证注入：模拟 AuditableEntity 有了子类（载体到位）。本文件由脚本创建/删除。 */
public class ReverseProbeAuditable extends AuditableEntity {
}
"""

# C3：摘掉切面的 @Aspect 注解（改为普通注释，保持语法合法且可编译）。
# 🛑 单行锚点（技能 8.7）：@Aspect 在文件里恰出现 1 次。
# 🛑 初版曾把类名改成 AuditFillAspect_RENAMED ⇒ public 类名与文件名不符 ⇒ 编译失败 ⇒
#    红的是编译器而非断言（红集为空 = 假阳性）。改为摘注解后红的是断言本身。
ANCHOR_C3 = "@Aspect"
REPL_C3 = "// @Aspect 反向验证注入：已摘掉"

INJECTIONS = [
    ("C1", F_LEDGER, ANCHOR_C1, REPL_C1, M_POINTCUT),
    ("C2", None, None, None, M_SUBCLASS),      # 特殊：新建文件
    ("C3", F_ASPECT, ANCHOR_C3, REPL_C3, M_ASPECT),
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
    return env


def run_maven(args: list[str]) -> tuple[int, str]:
    proc = subprocess.run([MAVEN_CMD, "-o"] + args, cwd=str(SKEL), env=build_env(),
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, shell=False)
    return proc.returncode, proc.stdout.decode("utf-8", errors="replace")


def run_gate() -> tuple[int, str]:
    return run_maven([
        "-pl", "dy-audit", "test",
        "-Dtest=" + GATE,
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-Dsurefire.failIfNoTests=false",
    ])


def hits(out: str, methods: list[str]) -> set[str]:
    return {m for m in methods if m in out}


_SNAPSHOTS: dict[str, bytes] = {}
_RESTORED = False
_CREATED: list[str] = []      # 由本脚本新建的文件（还原时删除）


def restore_all() -> None:
    global _RESTORED
    if _RESTORED:
        return
    log("\n[还原] 正在逐字节还原被注入的文件 ...")
    for rel, data in _SNAPSHOTS.items():
        p = SKEL / rel
        p.write_bytes(data)
        now = p.read_bytes()
        log(f"  {'OK ' if now == data else 'BAD'} {rel}  bytes={len(now)}/{len(data)}")
    for rel in _CREATED:
        p = SKEL / rel
        if p.exists():
            p.unlink()
            log(f"  {'OK ' if not p.exists() else 'BAD'} {rel}  (新建文件已删除)")
    _RESTORED = True


def _sig(signum, frame):  # noqa: ARG001
    restore_all()
    sys.exit(1)


atexit.register(restore_all)
signal.signal(signal.SIGINT, _sig)
signal.signal(signal.SIGTERM, _sig)


def main() -> int:
    log("=" * 74)
    log("批次十三 · AuditFillAspect 接线状态门禁 · 反向验证")
    log("=" * 74)

    for rel in {F_LEDGER, F_ASPECT}:
        (SKEL / rel).read_bytes()
        _SNAPSHOTS[rel] = (SKEL / rel).read_bytes()
        log(f"[快照] {rel} bytes={len(_SNAPSHOTS[rel])}")

    texts = {rel: data.decode("utf-8") for rel, data in _SNAPSHOTS.items()}
    problems = []
    for tag, rel, anchor, _repl, _expect in INJECTIONS:
        if rel is None:          # 新建文件型注入：锚点预检 = 目标文件当前不得存在
            exists = (SKEL / F_PROBE).exists()
            log(f"[预检 {'OK ' if not exists else 'BAD'}] {tag} 探针文件当前{'已存在(异常)' if exists else '不存在(正常)'}")
            if exists:
                problems.append(f"{tag}: 探针文件 {F_PROBE} 已存在，还原逻辑会误删它")
            continue
        n = texts[rel].count(anchor)
        log(f"[预检 {'OK ' if n == 1 else 'BAD'}] {tag} 锚点出现 {n} 次")
        if n != 1:
            problems.append(f"{tag}: 锚点出现 {n} 次（要求恰 1 次）")
    if problems:
        log("\n[预检失败] " + " | ".join(problems))
        return 2

    log("\n[基线] 注入前跑门禁（期望全绿）...")
    c0, out0 = run_gate()
    if c0 != 0:
        log(f"[基线失败] 注入前门禁未通过（exit={c0}）—— 后续结论无意义")
        log(out0[-3000:])
        return 2

    pre = hits(out0, METHODS)
    if pre:
        log(f"[元层自证失败] 基线（全绿）输出里已出现待查方法名 {sorted(pre)} —— 锚点恒真，无判别力")
        return 2
    log("[基线 OK] 注入前全绿，且待查方法名在基线中【零出现】（锚点有判别力）")

    results = []
    for tag, rel, anchor, repl, expect in INJECTIONS:
        if rel is None:
            # 新建文件型注入（C2）
            log(f"\n[{tag}] 新建探针文件 {F_PROBE}")
            probe = SKEL / F_PROBE
            probe.write_text(PROBE_BODY, encoding="utf-8", newline="")
            _CREATED.append(F_PROBE)
        else:
            log(f"\n[{tag}] 注入 {rel}：{anchor[:60].replace(chr(10), ' / ')}...")
            p = SKEL / rel
            text = _SNAPSHOTS[rel].decode("utf-8")
            assert text.count(anchor) == 1
            p.write_text(text.replace(anchor, repl), encoding="utf-8", newline="")

        code2, out2 = run_gate()
        got = hits(out2, METHODS)
        caught = (code2 != 0) and (expect in got)
        log(f"[{tag}] 门禁 exit={code2} · 期望红 `{expect}` 被抓={caught} · 红集={sorted(got)}")
        results.append((tag, expect, caught, code2, sorted(got)))

        if rel is not None:
            p.write_bytes(_SNAPSHOTS[rel])
            assert p.read_bytes() == _SNAPSHOTS[rel], f"{tag} 还原后字节不一致"
        else:
            probe = SKEL / F_PROBE
            probe.unlink()
            _CREATED.remove(F_PROBE)
            assert not probe.exists(), f"{tag} 探针文件未删除"

    log("\n[复绿] 全部还原后重跑门禁（期望全绿）...")
    c3, _ = run_gate()
    green = (c3 == 0)
    log(f"[复绿] exit={c3} · 复绿={green}")

    log("\n" + "=" * 74)
    log("汇 总")
    log("=" * 74)
    for tag, expect, caught, code, got in results:
        log(f"  [{'PASS' if caught else 'FAIL'}] {tag}: 期望 `{expect}` 变红 · exit={code} · 实际红集={got}")
    log(f"  [{'PASS' if green else 'FAIL'}] 还原后复绿")

    passed = sum(1 for _t, _e, c, _c2, _g in results if c) + (1 if green else 0)
    total = len(results) + 1
    log(f"\n结论: {passed}/{total} {'PASS' if passed == total else 'FAIL'}")
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())