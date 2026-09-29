#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次九 · 反向验证（Reversal Validation）—— 证明【排除项 × 配置真相源交叉自证】有牙齿（N-3 收口）。

## 本脚本要证明什么

`ThresholdVersionFingerprintTest` 新增断言：
> **`cfg:verdict.*` 全集 = 九段已消费 ∪ `EXCLUDED_NOTE` 显式排除，不得有第三个。**

它的由来是一个**真实漏项**：`cfg:verdict.*` 实际有 **4** 个槽位
（`#9 branch_rules` / `#30 formula_params` / `#33 mcid_threshold` / `#45 confidence_formula`），
指纹消费了 `#33`/`#45`，而 `EXCLUDED_NOTE` **只点名了 `#30`** ——
**`#9` 既不在九段里、也不在排除说明里**（两侧都不在）：
这正是"『不纳入』与『忘了纳入』在复盘时无法区分"的形态。

| 组 | 注入内容 | 期望 | 证明了哪一条 |
|---|---|---|---|
| P1 | 从 `EXCLUDED_NOTE` 删掉 `#9` 那两行 | 变红 | ③ 「全集 = 已消费 ∪ 已排除」会抓到"两侧都不在" |
| P2 | 把 seed 里 `#35` 的 key 命名空间改成 `cfg:verdict.*` | 变红 | ① 真相源侧多出槽位会被抓到（**不是只对实现侧生效**） |
| P3 | 期望集合写成 `Set.of("33","45")`（漏掉 9/30） | 变红 | ① 期望集与真相源脱节也会被抓（防"手抄第二份清单"） |

## 纪律（与 102~106 同口径，含批次八教训）

- **🛑 跨模块注入必须先 install 依赖模块**：`P2` 注入的是 **dy-config** 的
  `src/main/resources/db/config/02_slots_seed.sql`（classpath 资源），
  被测类在 **dy-app**。若只跑 `-pl dy-app`，读到的是**旧 dy-config jar 里的旧资源**
  ⇒ 注入不生效 ⇒ 会误报"未被抓住"。**故本脚本对 P2 先 `install dy-config`。**
- **锚点预检**（存在 + 唯一）· **元层判别力自证**（基线零出现）· **逐字节还原** · **还原后复绿**。
- **🛑 注入必须保持源文件语法合法**：P1 删的是字符串拼接中间的两行（前后仍有连接）·
  P2 只改 SQL 字符串字面 · P3 只改集合字面 —— 三者均不破坏语法。
- 🛑 **must_see 锚点必须 ASCII**（方法名）—— Windows GBK 下中文断言消息可能乱码。
- 🛑 Windows 下用 `mvn.cmd` 绝对路径 + list 参数 + `shell=False`；**不加 `-q`**。

用法：
    python.exe verification/107_excluded_items_cross_check_reverse_verification.py
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

MODULE = "dy-app"
GATE_CLASS = "ThresholdVersionFingerprintTest"
M_CROSS = "excluded_items_are_cross_checked_against_the_verdict_config_namespace"
ALL_METHODS = [M_CROSS]

F_TEST = ("dy-app/src/test/java/com/diaoyuanyun/dy/app/derived/"
          "ThresholdVersionFingerprintTest.java")
# 🛑 跨模块：main 类在 dy-app；seed 资源在 dy-config
F_IMPL = ("dy-app/src/main/java/com/diaoyuanyun/dy/app/derived/domain/"
          "ThresholdVersionFingerprint.java")
F_SEED = "dy-config/src/main/resources/db/config/02_slots_seed.sql"

INJECTIONS = [
    (
        "P1",
        F_IMPL,
        # 删掉 EXCLUDED_NOTE 里关于 #9 的两行（前后仍有 "+" 连接 ⇒ 语法合法）
        '                    + "config #9 的 branch_rules（其 branches 五字面与 VerdictBranch 枚举逐字一致，"\n'
        '                    + "而分支顺序/条件判断 D1~D5 写死在 VerdictService 内；"\n'
        '                    + "它是『已声明但运行时未消费』—— 将来改为配置驱动时必须纳入本指纹）；"\n',
        '',
        M_CROSS,
    ),
    (
        "P2",
        F_SEED,
        # 把 #35 的 key 命名空间从 scale 改成 verdict（真相源侧多出一个 cfg:verdict.* 槽位）
        "(35, 'cfg:scale.range_rule', 'JSON', NULL, NULL,",
        "(35, 'cfg:verdict.probe_range_rule', 'JSON', NULL, NULL,",
        M_CROSS,
    ),
    (
        "P3",
        F_TEST,
        # 期望集合与真相源脱节（漏掉 #9 / #30）
        'assertEquals(Set.of("9", "30", "33", "45"), allVerdictSlots,',
        'assertEquals(Set.of("33", "45"), allVerdictSlots,',
        M_CROSS,
    ),
]

# P2 是跨模块注入（改 dy-config 的 classpath 资源）⇒ 需要先 install dy-config
NEEDS_CONFIG_INSTALL = {"P2"}


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


def run_gate(install_config: bool = False) -> tuple[int, str]:
    """跑门禁；`install_config=True` 时先把 dy-config 装进本地仓库。

    🛑 为什么需要（批次八教训的直接应用）：`02_slots_seed.sql` 是 **dy-config** 的
    classpath 资源，却被 **dy-app** 的测试读取。若只跑 `-pl dy-app`，
    dy-app 会用**本地仓库里 dy-config jar 内的旧资源** ⇒ 对 dy-config 资源的注入**不生效**
    ⇒ 会误报"未被抓住"。判别方法：**先问"红点应该来自哪里"，再看改动文件是否参与本次构建**。
    """
    if install_config:
        code, out = run_maven(["-pl", "dy-config", "install", "-DskipTests"])
        if code != 0:
            return code, out
    return run_maven([
        "-pl", MODULE, "test",
        "-Dtest=" + GATE_CLASS,
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-Dsurefire.failIfNoTests=false",
    ])


def hits(out: str) -> set[str]:
    return {m for m in ALL_METHODS if m in out}


_SNAPSHOTS: dict[str, bytes] = {}
_RESTORED = False


def snapshot(rel: str) -> bytes:
    data = (SKEL / rel).read_bytes()
    _SNAPSHOTS[rel] = data
    return data


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
    _RESTORED = True


def _sig(signum, frame):  # noqa: ARG001
    restore_all()
    sys.exit(1)


atexit.register(restore_all)
signal.signal(signal.SIGINT, _sig)
signal.signal(signal.SIGTERM, _sig)


def main() -> int:
    log("=" * 74)
    log("批次九 · 排除项 × 配置真相源交叉自证 · 反向验证（N-3）")
    log("=" * 74)

    for rel in {F_TEST, F_IMPL, F_SEED}:
        snapshot(rel)
        log(f"[快照] {rel} bytes={len(_SNAPSHOTS[rel])}")

    texts = {rel: _SNAPSHOTS[rel].decode("utf-8") for rel in _SNAPSHOTS}
    problems = []
    for tag, rel, anchor, _repl, _expect in INJECTIONS:
        n = texts[rel].count(anchor)
        log(f"[预检 {'OK ' if n == 1 else 'BAD'}] {tag} 锚点出现 {n} 次")
        if n != 1:
            problems.append(f"{tag}: 锚点出现 {n} 次（要求恰 1 次）")
    if problems:
        log("\n[预检失败] " + " | ".join(problems))
        return 2

    log("\n[基线] 注入前跑门禁（期望全绿）...")
    code, out = run_gate()
    if code != 0:
        log("[基线失败] 注入前门禁未通过 —— 后续结论无意义")
        log(out[-3000:])
        return 2
    if hits(out):
        log(f"[元层自证失败] 基线（全绿）输出里已出现待查方法名 {sorted(hits(out))} "
            f"—— must_see 锚点恒真，本脚本无判别力")
        return 2
    log("[基线 OK] 注入前全绿，且待查方法名在基线中【零出现】（锚点有判别力）")

    results = []
    for tag, rel, anchor, repl, expect in INJECTIONS:
        log(f"\n[{tag}] 注入（{'跨模块 ⇒ 先 install dy-config' if tag in NEEDS_CONFIG_INSTALL else '同模块'}）："
            f"{anchor[:56].replace(chr(10), ' ')}...")
        p = SKEL / rel
        text = _SNAPSHOTS[rel].decode("utf-8")
        assert text.count(anchor) == 1
        p.write_text(text.replace(anchor, repl), encoding="utf-8", newline="")

        code2, out2 = run_gate(install_config=(tag in NEEDS_CONFIG_INSTALL))
        got = hits(out2)
        caught = (code2 != 0) and (expect in got)
        log(f"[{tag}] 门禁 exit={code2} · 期望红 `{expect}` 被抓={caught} · 红集={sorted(got)}")
        results.append((tag, expect, caught, code2))

        p.write_bytes(_SNAPSHOTS[rel])
        assert p.read_bytes() == _SNAPSHOTS[rel], f"{tag} 还原后字节不一致"

    log("\n[复绿] 全部还原后重跑门禁（期望全绿）...")
    # 还原后 dy-config 资源已回原值，install 一次确保库内与磁盘一致
    code3, out3 = run_gate(install_config=True)
    green = code3 == 0
    log(f"[复绿] exit={code3} 复绿={green}")

    log("\n" + "=" * 74)
    log("汇 总")
    log("=" * 74)
    passed = sum(1 for _t, _e, c, _c2 in results if c)
    for tag, expect, caught, code in results:
        log(f"  [{'PASS' if caught else 'FAIL'}] {tag}: 期望 `{expect}` 变红 · exit={code}")
    log(f"  [{'PASS' if green else 'FAIL'}] 还原后复绿")
    total = len(results) + 1
    ok = passed + (1 if green else 0)
    log(f"\n总体：{ok}/{total} 通过")
    log("=" * 74)
    return 0 if ok == total else 1


if __name__ == "__main__":
    sys.exit(main())