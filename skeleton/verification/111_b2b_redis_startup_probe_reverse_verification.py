#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次十三 · 反向验证（Reversal Validation）—— 证明【B-2b prod 幂等后端启动期连通性自检】有牙齿。

## 本脚本要证明什么

批次十三在盘点"距离商用还差什么"时，用**实测**抓出 B-2 的一处**门禁牙齿不全**：

B-2（批次十二）的立论是"配置错误应在**启动阶段**暴露，而不是运行期静默降级"。
但当时实现的两道防线只覆盖了：

  - prod + memory  ⇒ 拒绝启动（`reject_memory_backend_in_production`）
  - prod 段默认值必须是 redis（`IdempotencyProdBackendConfigGateTest`）

**没有覆盖另一半**：prod + redis，但 Redis 主机写错 / 未启动 / 网络不通。
Lettuce 是**惰性建连**（本仓 `dy-web/pom.xml` 注释逐字写着"afterPropertiesSet 阶段不建连接，
故障只发生在真正使用的那一刻"），故这种情况下：

    应用照常启动成功 → 直到第一个带 Idempotency-Key 的写请求 → 全线 5xx

即"配置错误被推迟到了业务已经在跑的时刻"。批次十三补上
`IdempotencyConfiguration.verify_redis_is_reachable_at_startup_if_production(...)`：
**prod 时代价是一次启动期 HASKEY 探活；Redis 不可达 ⇒ 拒绝启动**。

## 注入表

| 组 | 文件 | 注入内容 | 期望变红 |
|---|---|---|---|
| C1 | `dy-web/.../IdempotencyConfiguration.java` | 探活守卫 `if (!prodActive) { return; }` → `if (true) { return; }`（恒 return ⇒ 永不探活） | `production_with_unreachable_redis_refuses_to_start` |
| C2 | `dy-web/.../IdempotencyConfiguration.java` | 把探活体 `redis.hasKey(PROBE_KEY);` 的异常吞掉：`} catch (RuntimeException e) {` → `} catch (RuntimeException e) { if (true) { log.warn("swallowed", e); return; }`（静默放行） | `production_with_unreachable_redis_refuses_to_start` |

🛑 **C1 与 C2 守的是同一条纪律的两个不同失效模式**：
  - C1 = "自检根本没跑"（守卫条件被掏空）；
  - C2 = "自检跑了但异常被吞"（fail-closed 退化成 fail-open）。
只测其中之一会让另一条静默退化 —— 故两组都要注入。

## 纪律（与 100~110 同口径）

- **锚点预检**（存在 + 唯一）· **元层判别力自证**（基线零出现）· **逐字节还原** · **还原后复绿**。
- 🛑 **注入必须保持源文件语法合法**：C1 只改守卫条件（仍是合法 Java，语义被掏空）；
  C2 在 catch 体首部插入一条 `if (true) { ...; return; }`，仍是合法 Java。
- 🛑 **must_see 锚点必须 ASCII**（方法名）—— Windows GBK 下中文断言消息会乱码。
- 🛑 **锚点优先用单行**（技能 8.7：多行锚点在跨工具转义链路下不可靠）。
- 🛑 本脚本只改 `dy-web` 源 ⇒ **只跑 dy-web**，不需 install。
- 🛑 Windows 下用 `mvn.cmd` 绝对路径 + list 参数 + `shell=False`；**不加 `-q`**。

用法：
    python.exe verification/111_b2b_redis_startup_probe_reverse_verification.py
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

MAVEN_CMD = r"C:\opt\apache-maven-3.9.9\bin\mvn.cmd"
JAVA_HOME_WIN = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
PG_BIN_WIN = r"C:\Program Files\PostgreSQL\17\bin"

# --- 门禁 / 用例名（ASCII，供输出判定） ---
B2B_UNREACHABLE = "production_with_unreachable_redis_refuses_to_start"
B2B_REACHABLE = "production_with_reachable_redis_starts_normally"
B2B_NOPROFILE = "non_production_does_not_probe_redis_at_startup"

WEB_GATE = "IdempotencyBackendFailureTest"
WEB_METHODS = [B2B_UNREACHABLE, B2B_REACHABLE, B2B_NOPROFILE]

# --- 文件（相对 skeleton 根） ---
F_IDEM_CFG = "dy-web/src/main/java/com/diaoyuanyun/dy/web/idempotent/IdempotencyConfiguration.java"

INJECTIONS = [
    (
        # C1：把守卫条件掏空（恒 return ⇒ 永不探活）。
        # 🛑 单行锚点（技能 8.7）—— 多行锚点在跨工具转义链路下不可靠。
        "C1", F_IDEM_CFG,
        "        if (!prodActive) {",
        "        if (true) {",
        B2B_UNREACHABLE,
    ),
    (
        # C2：探活跑了，但异常被吞（fail-closed 退化成 fail-open）。
        # 锚点是 catch 行本身，替换为 catch 行 + 一条恒真的 return，语义被掏空但语法合法。
        "C2", F_IDEM_CFG,
        "        } catch (RuntimeException e) {",
        "        } catch (RuntimeException e) { if (true) { log.warn(\"swallowed\", e); return; }",
        B2B_UNREACHABLE,
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
    return env


def run_maven(args: list[str]) -> tuple[int, str]:
    proc = subprocess.run([MAVEN_CMD, "-o"] + args, cwd=str(SKEL), env=build_env(),
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, shell=False)
    return proc.returncode, proc.stdout.decode("utf-8", errors="replace")


def run_web_gates() -> tuple[int, str]:
    return run_maven([
        "-pl", "dy-web", "test",
        "-Dtest=" + WEB_GATE,
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-Dsurefire.failIfNoTests=false",
    ])


def hits(out: str, methods: list[str]) -> set[str]:
    return {m for m in methods if m in out}


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
    log("批次十三 · B-2b prod + redis 启动期探活纪律 · 反向验证")
    log("=" * 74)

    for rel in {F_IDEM_CFG}:
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

    log("\n[基线] 注入前跑 dy-web 门禁（期望全绿）...")
    cw, outw = run_web_gates()
    if cw != 0:
        log(f"[基线失败] 注入前门禁未通过（web exit={cw}）—— 后续结论无意义")
        log(outw[-3000:])
        return 2

    pre = hits(outw, WEB_METHODS)
    if pre:
        log(f"[元层自证失败] 基线（全绿）输出里已出现待查方法名 {sorted(pre)} "
            f"—— must_see 锚点恒真，本脚本无判别力")
        return 2
    log("[基线 OK] 注入前全绿，且待查方法名在基线中【零出现】（锚点有判别力）")

    results = []
    for tag, rel, anchor, repl, expect in INJECTIONS:
        log(f"\n[{tag}] 注入 {rel}：{anchor[:56].replace(chr(10), ' / ')}...")
        p = SKEL / rel
        text = _SNAPSHOTS[rel].decode("utf-8")
        assert text.count(anchor) == 1
        p.write_text(text.replace(anchor, repl), encoding="utf-8", newline="")

        code2, out2 = run_web_gates()
        got = hits(out2, WEB_METHODS)
        caught = (code2 != 0) and (expect in got)
        log(f"[{tag}] 门禁 exit={code2} · 期望红 `{expect}` 被抓={caught} · 红集={sorted(got)}")
        results.append((tag, expect, caught, code2, sorted(got)))

        p.write_bytes(_SNAPSHOTS[rel])
        assert p.read_bytes() == _SNAPSHOTS[rel], f"{tag} 还原后字节不一致"

    log("\n[复绿] 全部还原后重跑 dy-web 门禁（期望全绿）...")
    cw3, outw3 = run_web_gates()
    green = (cw3 == 0)
    log(f"[复绿] web exit={cw3} · 复绿={green}")

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