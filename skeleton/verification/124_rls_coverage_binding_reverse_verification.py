#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""
A-4 · 反向验证（Reversal Validation）—— 证明【登记咬合判据】有牙齿。

## 本脚本要证明什么

A-4 的实测结论是：`RlsCoverageGateTest.ISOLATION_TESTS` 的 **值**（测试类 FQN）
**长期没有任何咬合检查** —— 原有第 ③ 条判据只验「登记的类可加载 且 @Test 数 >= 5」，
**不验该类真的断言了这张表**。

⇒ 后果（本轮实测认定的真实缺口）：
   把一张新表登记到任一既有 >=5 `@Test` 的类上，①②③ 三条判据**全部照常通过**，
   而该表的**行为隔离断言为零**。登记表看起来"满了"，实际是**把新表挂到了邻居名下**。

本轮新增第 ④ 条判据 `the_registered_test_class_must_actually_mention_the_table_it_covers`
把这条咬合变成构建期事实。但它是**「必须提到」型**判据 ——
这类判据天然会退化成恒绿（正则失效 / 剥离器用错粒度 / 路径搬家 / 锚点恒真），
故必须逐组证明它**真的会红**。

## 判据形态是【实测选出来的】（不是拍的）

| 候选 | 形状 | 对现有 38 条登记的误伤 |
|---|---|---|
| F1 | 双引号 Java 字面量 `"<table>"` | **1/38**（`RlsTenantIsolationTest` 用常量拼接） |
| F2 | 单引号 SQL 字面量 `'<table>'` | **28/38**（本仓 SQL 普遍是 `" FROM " + table` 拼接） |
| F3 | 裸标识词（词边界） | **0/38** ✅ 采用 |

🛑 **误伤为 0 是关键**：一个把正确实现判红的门禁会被关掉（本仓既有纪律）。
本脚本的 C4 组**专门把误伤形态钉住** —— 若有人把 F3 换成 F1/F2，C4 立刻红。

## 注入表（五组，覆盖新判据的每一种失效模式）

| 组 | 注入 | 期望变红 | 守的失效模式 |
|---|---|---|---|
| C1 | 把 `agreement` 的登记从 `RlsV5EntityIsolationTest` 改挂到一个**真实存在但不提 agreement** 的类（`RlsV11CryptoKeyIsolationTest`） | `..._must_actually_mention_the_table_it_covers` | 🎯 **原始的洞本身**（把新表挂到邻居名下） |
| C2 | 破坏词边界：`(?<![A-Za-z0-9_])` → `(?<![A-Za-z0-9_])` 去掉后一半 `(?![A-Za-z0-9_])` | `..._matcher_has_real_discriminating_power` | **子串假绿**（`refund` 满足 `refund_receipt`；本仓有 3 组真实子串关系） |
| C3 | 破坏剥离注释：行注释分支不再切换状态（`state = 1` → `state = 0`） | `..._matcher_has_real_discriminating_power` 或咬合判据 | **注释假绿**（javadoc 里逐字列着 24 张表名 ⇒ 不剥注释则判据恒真） |
| C4 | 把判据形态从 F3（词边界）换成 F1（双引号字面量） | `..._must_actually_mention_the_table_it_covers` | 🎯 **判据收紧过度**（1/38 误伤 ⇒ 正确实现被判红） |
| C5 | 把 `TEST_SOURCE_MODULES` **剔掉 `dy-app`**（登记表当前所落的模块） | `..._must_actually_mention_the_table_it_covers` | **搜索面缺失**（38 条 FQN 全部反查不到源码 ⇒ 必须以"登记了不存在的类"变红，不得静默跳过） |

🛑 **C1/C4/C5 是「判据语义」组**：注入的变更**语法合法、可编译**，
门禁变红**不是**因为变更写错了，而是因为**咬合被破坏**——
这正是「必须提到」型判据的牙齿：**改变即红、须显式表态**。

🛑 **C2/C3 是「判别力」组**：注入的是**真实缺陷本身**（假绿能力），
验证门禁能抓住"门禁自己坏掉"而不是只在状态漂移时报警。

## 🛑 第一版 C5 的教训（保留在案，别把"注入不生效"读成"判据没牙"）

本脚本 C5 初版注入的是**"收窄成只剩 `dy-app`"**，实测**未被抓住（exit=0）**。
排查结论：**不是判据没牙，是该注入【等价于原样】** ——
登记表当前 **38/38 恰好全部落在 `dy-app`**
（`V5`×24 · `V6`×3 · `V11`×3 · `V14`×2 · `V7`×1 + 直写 FQN 的 5 条），
`TEST_SOURCE_MODULES` 里其余 7 个模块对**今天**的 38 条登记**毫无作用** ⇒ 收窄前后行为逐字相同。

⇒ 已改为**剔掉 `dy-app`**（保留其余）：38 条 FQN 全部反查不到源码 ⇒ 判据④ 必红。
⇒ **通用规则（值得固化为技能条目）：注入必须先证明"它改变了被测对象的行为"** ——
   等价改写会让反向验证**报出一个假失败**，而假失败的读法恰恰是"判据有洞"，
   会把人引向给一个**本来正确的判据**加冗余断言的方向。
   本仓既有的"锚点预检 + 基线零出现"只证明**锚点有判别力**，
   **不**证明**注入有判别力** —— 后者只能靠"注入后确有一个可观测差异"来证。

⇒ 顺带更正一处**源码中的不实陈述**：该常量旧注释写着"登记表里已出现 `dy-config` 等模块的类"，
   与实测不符（当时 38/38 全在 `dy-app`）。真正理由是**防假红**（登记表允许跨模块登记），
   已在源码注释里逐字更正。

## 纪律（与 100~123 同口径）

- 锚点预检（存在 + 唯一）· 元层判别力自证（基线零出现）· 逐字节还原 · 还原后复绿
- 🛑 **锚点优先用单行**（技能 8.7）—— 故五组全用单行锚点
- 🛑 注入必须保持源文件**语法合法且可编译**（112 的教训：改类名 ⇒ 编译失败 ⇒ 红的是编译器）
- 🛑 判定式 `caught = (exit != 0) and (expect in got)` ——
      `exit != 0` 不足以证明牙齿（可能是编译错误），必须同时要求**期望方法名出现在输出里**
- 🛑 Windows：`mvn.cmd` 绝对路径 + list 参数 + `shell=False`
- 🛑 **install 只做一次**（2026-09-30 加固）：原版每组都 `install` ⇒ 7 × ~100s ⇒
      外层命令超时把进程 **SIGTERM 腰斩**，且被杀时**源文件停在注入态**（实测 C5 残留）。
      本脚本五组注入全在 dy-app 的 **test 源码**，`mvn -pl dy-app test` 自己会重编译 ⇒
      install 提到循环外一次即可。
- 🛑 **磁盘兜底快照 + 污染态自检**：快照落盘 `_work/124_snapshots/`；
      启动时若检测到源文件**已处于注入态**则拒绝覆盖快照并中止；
      可用 `--restore-only` 事后人工还原。

用法：
    python.exe verification/124_rls_coverage_binding_reverse_verification.py
    python.exe verification/124_rls_coverage_binding_reverse_verification.py --restore-only
"""
from __future__ import annotations

import atexit
import os
import re
import signal
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SKEL = HERE.parent

MAVEN_CMD = r"C:\opt\apache-maven-3.9.9\bin\mvn.cmd"
JAVA_HOME_WIN = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
PG_BIN_WIN = r"C:\Program Files\PostgreSQL\17\bin"

GATE_CLASS = "RlsCoverageGateTest"
F_GATE = "dy-app/src/test/java/com/diaoyuanyun/dy/app/rls/RlsCoverageGateTest.java"

M_BINDING = "the_registered_test_class_must_actually_mention_the_table_it_covers"
M_MATCHER = "the_registration_binding_matcher_has_real_discriminating_power"
METHODS = [M_BINDING, M_MATCHER]

# ---- C1：把 agreement 的登记改挂到一个真实存在但不提 agreement 的类 ----
# 🛑 选 RlsV11CryptoKeyIsolationTest 作为"邻居"的理由（实测，非随手挑）：
#    ① 它**真实存在**（可加载）且 @Test 数 = 9 >= 5 ⇒ 旧判据第③条对它完全不设防；
#    ② 它**不提** agreement（它讲的是密钥材料三表）⇒ 新判据必须抓住。
ANCHOR_C1 = '            Map.entry("agreement", V5),'
REPL_C1 = ('            Map.entry("agreement", '
           '"com.diaoyuanyun.dy.app.rls.RlsV11CryptoKeyIsolationTest"),')

# ---- C2：破坏词边界（去掉后半段负向断言）----
ANCHOR_C2 = 'Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(table) + "(?![A-Za-z0-9_])")'
REPL_C2 = 'Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(table) + "")'

# ---- C3：破坏行注释剥离（行注释分支不再切换状态）----
# 🛑 单行锚点：状态机里 `state = 1;` 只出现 1 次（行注释入口）。
ANCHOR_C3 = "                    state = 1;"
REPL_C3 = "                    state = 0;  // 注入：行注释不再切换状态（注释假绿的形态）"

# ---- C4：把判据形态从 F3（词边界）换成 F1（双引号字面量）----
ANCHOR_C4 = 'Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(table) + "(?![A-Za-z0-9_])")'
REPL_C4 = 'Pattern.compile("\\"" + Pattern.quote(table) + "\\"")'

# ---- C5：把搜索面收窄（剔掉登记表当前所落的模块）----
# 🛑 第一版注入写的是"只留 dy-app"，**实测未被抓住** —— 原因不是判据没牙，而是
#    该注入**等价于原样**：登记表当前 38/38 恰好全落在 dy-app，
#    `TEST_SOURCE_MODULES` 里其余 7 个模块对**今天**的 38 条登记毫无作用 ⇒ 收窄前后行为相同。
# 🛑 故改为**剔掉 dy-app**（保留其余）：38 条 FQN 全部反查不到源码 ⇒ `unloadable` 非空
#    ⇒ 判据④ 必须以"登记了不存在的类"变红。守的失效模式 = **搜索面缺失必须响亮地红，
#    不得静默跳过**（若有人把 `return null` 改成 `continue`，本组立刻失效 ⇒ 该改动会被本组钉住）。
ANCHOR_C5 = '            "dy-audit", "dy-config", "dy-crypto", "dy-app");'
REPL_C5 = '            "dy-common");'

INJECTIONS = [
    ("C1 把 agreement 改挂到不覆盖它的邻居类", F_GATE, ANCHOR_C1, REPL_C1, M_BINDING),
    ("C2 破坏词边界（子串假绿）", F_GATE, ANCHOR_C2, REPL_C2, M_MATCHER),
    ("C3 破坏行注释剥离（注释假绿）", F_GATE, ANCHOR_C3, REPL_C3, M_MATCHER),
    ("C4 判据收紧过度（F3→F1，1/38 误伤）", F_GATE, ANCHOR_C4, REPL_C4, M_BINDING),
    ("C5 搜索面剔除【登记所落的模块】（FQN 反查不到源码）", F_GATE, ANCHOR_C5, REPL_C5, M_BINDING),
]

_SNAPSHOTS: dict[str, bytes] = {}
_RESTORED = False

# 🛑 磁盘兜底快照目录（2026-09-30 加固）：
#    本脚本曾因外层命令超时被 **SIGTERM 硬杀**，而硬杀发生在子进程（mvn）正在跑时 ⇒
#    handler 未必来得及执行 ⇒ 被注入的源文件**停在注入态**（实测 C5 残留）。
#    ⇒ 注入前把快照落盘，`restore_all()` 在内存快照缺失时从磁盘恢复，
#      另外提供 `--restore-only` 便于事后人工还原。
SNAP_DIR = SKEL / "_work" / "124_snapshots"


def log(msg: str) -> None:
    print(msg, flush=True)


def restore_all() -> None:
    global _RESTORED
    if _RESTORED:
        return
    log("\n[还原] 正在逐字节还原被注入的文件 ...")
    for rel in _snapshot_rels():
        data = _SNAPSHOTS.get(rel)
        if data is None:
            disk = SNAP_DIR / Path(rel).name
            if not disk.is_file():
                log(f"  BAD {rel}  内存与磁盘均无快照，无法还原")
                continue
            data = disk.read_bytes()
            log(f"  ~~ {rel}  内存快照缺失，改用磁盘兜底快照")
        p = (SKEL / rel).resolve()
        p.write_bytes(data)
        now = p.read_bytes()
        log(f"  {'OK ' if now == data else 'BAD'} {rel}  bytes={len(now)}/{len(data)}")
    _RESTORED = True


def _snapshot_rels():
    if _SNAPSHOTS:
        return list(_SNAPSHOTS.keys())
    if SNAP_DIR.is_dir():
        return [rel for _t, rel, _a, _r, _e in INJECTIONS
                if (SNAP_DIR / Path(rel).name).is_file()]
    return []


def _looks_injected(text: str) -> bool:
    r"""判断源文件是否**仍停在某一组注入态**（清理态识别，非判据）。

    只在"清理完毕"的文件上，五组锚点才**全部恰 1 次**；任一组残留都会破坏至少一个锚点。
    """
    return not all(text.count(anchor) == 1
                   for _t, _rel, anchor, _r, _e in INJECTIONS)


def _sig(signum, frame):  # noqa: ARG001
    restore_all()
    sys.exit(1)


atexit.register(restore_all)
signal.signal(signal.SIGINT, _sig)
signal.signal(signal.SIGTERM, _sig)


def _env():
    env = dict(os.environ)
    env["MAVEN_OPTS"] = "-Dfile.encoding=UTF-8"
    env["JAVA_HOME"] = JAVA_HOME_WIN
    env["PATH"] = JAVA_HOME_WIN + r"\bin;" + PG_BIN_WIN + ";" + env.get("PATH", "")
    env["PGPASSWORD"] = "diaoyuanyun"
    return env


def install_main() -> tuple[int, str]:
    r"""🛑 安装主件**只做一次**（2026-09-30 收口：原版每组都 install ⇒ 7 × ~100s ⇒ 被 SIGTERM 腰斩，
    且 C5 组中断时源文件停在注入态）。

    本脚本五组注入**全部落在 dy-app 的 test 源码**（`dy-app/src/test/java/...`），
    而 `mvn -pl dy-app test` 自己会重编译 test 源码 ⇒ 每组的"注入生效"只依赖这一次 test。
    install 的唯一目的是让 dy-app 依赖的上游模块（dy-config / dy-crypto 等）jar 是新的，
    与"测试类被注入"无关 ⇒ 提到循环外一次即可。
    """
    p = subprocess.run(
        [MAVEN_CMD, "-o", "-q", "-pl", "dy-app", "-am", "install", "-DskipTests"],
        cwd=str(SKEL), capture_output=True, text=True, encoding="utf-8",
        errors="replace", env=_env(), shell=False, timeout=1800)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def run_gate():
    # 🛑 install 已在 install_main() 做过（见其 docstring）；此处只 test。
    p = subprocess.run(
        [MAVEN_CMD, "-o", "-pl", "dy-app", "test",
         f"-Dtest={GATE_CLASS}",
         "-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false"],
        cwd=str(SKEL), capture_output=True, text=True, encoding="utf-8",
        errors="replace", env=_env(), shell=False, timeout=1800)
    return p.returncode, (p.stdout or "") + (p.stderr or "")


def hits(out: str, methods):
    return {m for m in methods if m in out}


def main() -> int:
    log("=" * 78)
    log("A-4 · RLS 覆盖【登记咬合判据】· 反向验证（124）")
    log("=" * 78)

    rels = {rel for _t, rel, _a, _r, _e in INJECTIONS}
    SNAP_DIR.mkdir(parents=True, exist_ok=True)
    for rel in rels:
        p = (SKEL / rel).resolve()
        assert p.is_file(), f"被测文件不存在：{p}"
        data = p.read_bytes()
        # 🛑 先判"是否已停在注入态"，再落盘 —— 否则会把污染态当基线快照存起来
        if _looks_injected(data.decode("utf-8")):
            log(f"[中止] {rel} 当前已处于注入态（上次运行被硬杀未还原）。"
                f"请先 `--restore-only` 或手工还原，再重跑。")
            return 2
        _SNAPSHOTS[rel] = data
        (SNAP_DIR / Path(rel).name).write_bytes(data)
        log(f"[快照] {rel} bytes={len(data)}（内存 + 磁盘兜底）")

    texts = {rel: data.decode("utf-8") for rel, data in _SNAPSHOTS.items()}

    # ---- 锚点预检：存在且恰 1 次 ----
    problems = []
    for tag, rel, anchor, _repl, _expect in INJECTIONS:
        n = texts[rel].count(anchor)
        ok = (n == 1)
        log(f"[预检 {'OK ' if ok else 'BAD'}] {tag} 锚点出现 {n} 次 :: {anchor.strip()[:64]}")
        if not ok:
            problems.append(f"{tag}: 锚点出现 {n} 次（要求恰 1 次）")
    if problems:
        log("\n[预检失败] " + " | ".join(problems))
        return 2

    # ---- 基线：注入前必须全绿 ----
    log("\n[基线] 安装主件（一次性，之后每组只 test）...")
    ci, outi = install_main()
    if ci != 0:
        log(f"[基线失败] install 主件失败（exit={ci}）—— 后续结论无意义")
        log(outi[-4000:])
        return 2
    log("[基线] install OK")
    log("[基线] 注入前跑门禁（期望 6 例全绿）...")
    c0, out0 = run_gate()
    if c0 != 0:
        log(f"[基线失败] 注入前门禁未通过（exit={c0}）—— 后续结论无意义")
        log(out0[-4000:])
        return 2
    pre = hits(out0, METHODS)
    if pre:
        log(f"[元层自证失败] 基线（全绿）输出里已出现待查方法名 {sorted(pre)} —— 锚点恒真，无判别力")
        return 2
    log("[基线 OK] 注入前全绿，且 2 个待查方法名在基线中【零出现】（锚点有判别力）")

    results = []
    for tag, rel, anchor, repl, expect in INJECTIONS:
        log("\n" + "-" * 78)
        log(f"[{tag}] 注入 {rel} ...")
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
                    if ("RlsCoverageGate" in ln or "ERROR" in ln
                        or "AssertionFailed" in ln or "必须" in ln or "编译" in ln)]
            log("   —— 诊断片段 ——")
            for ln in tail[:14]:
                log("   " + ln[:180])
        results.append((tag, expect, caught, code2, sorted(got)))

        p.write_bytes(_SNAPSHOTS[rel])
        assert p.read_bytes() == _SNAPSHOTS[rel], f"{tag} 还原后字节不一致"

    # ---- 复绿 ----
    log("\n[复绿] 全部还原后重跑门禁（期望全绿）...")
    c5, out5 = run_gate()
    green = (c5 == 0)
    log(f"[复绿] exit={c5} · 复绿={green}")
    if not green:
        log(out5[-2000:])

    # ---- 还原后核验 ----
    log("\n[还原核验] 逐文件比对字节 ...")
    all_restored = True
    for rel, data in _SNAPSHOTS.items():
        cur = (SKEL / rel).resolve().read_bytes()
        ok = (cur == data)
        all_restored = all_restored and ok
        log(f"  {'OK ' if ok else 'BAD'} {rel}  bytes={len(cur)}/{len(data)}")

    log("\n" + "=" * 78)
    log("汇 总")
    log("=" * 78)
    for tag, expect, caught, code, got in results:
        log(f"  [{'PASS' if caught else 'FAIL'}] {tag}: 期望 `{expect}` 变红 · exit={code} · 实际红集={got}")
    log(f"  [{'PASS' if green else 'FAIL'}] 还原后复绿")
    log(f"  [{'PASS' if all_restored else 'FAIL'}] 逐字节还原")

    passed = sum(1 for _t, _e, c, _c2, _g in results if c) + (1 if green else 0) + (1 if all_restored else 0)
    total = len(results) + 2
    log(f"\n结论: {passed}/{total} {'PASS' if passed == total else 'FAIL'}")
    return 0 if passed == total else 1


if __name__ == "__main__":
    if "--restore-only" in sys.argv:
        if not SNAP_DIR.is_dir():
            log(f"[还原失败] 找不到磁盘兜底快照目录：{SNAP_DIR}")
            sys.exit(2)
        restore_all()
        for rel in _snapshot_rels():
            cur = (SKEL / rel).resolve().read_bytes()
            log(f"  [{'OK ' if not _looks_injected(cur.decode('utf-8')) else 'BAD'}] "
                f"{rel} 已回到清理态 bytes={len(cur)}")
        sys.exit(0)
    sys.exit(main())