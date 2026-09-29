#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次十二 · 反向验证（Reversal Validation）—— 证明【B-2 生产幂等纪律】与【B-3 过渡态门禁】有牙齿。

## 本脚本要证明什么

批次十二收口两条"此前只写在注释 / 部署文档里、没有任何测试守护"的纪律。
它们的共同特征是：**失效时完全静默** —— 系统照常启动、请求照常 200、监控照常全绿。

### A. B-2 生产环境禁内存幂等后端（两道防线）

| 测试 | 它守的 |
|---|---|
| `IdempotencyBackendFailureTest.production_profile_refuses_to_start_with_the_in_memory_backend` | ① 启动自检：prod + memory ⇒ 抛（含键名与正确取值） |
| `IdempotencyBackendFailureTest.non_production_profiles_keep_defaulting_to_memory` | ② dev/test 不被误伤（"骨架无需外部资源即可跑"这条保证不得被破坏） |
| `IdempotencyBackendFailureTest.production_profile_check_survives_whitespace_and_case_variants` | ③ `"Prod"` / `" prod "` 变体不得静默绕过 |
| `IdempotencyProdBackendConfigGateTest.prod_profile_declares_a_redis_default_for_the_idempotency_backend` | ④ **交付物本身**：application.yml prod 段的默认值必须是 redis |

### B. B-3 配置来源过渡态（构建期门禁，无 DB）

| 测试 | 它守的 |
|---|---|
| `ConfigSourceTransitionGateTest.transition_sources_are_exactly_the_registered_set` | ⑤ 过渡来源**全集机械核对**（从源树派生 vs 登记集，精确相等） |
| `ConfigSourceTransitionGateTest.no_config_port_has_more_than_one_implementation` | ⑥ 五个域端口各自恰有一个过渡实现（防"新增 DB 版却没删 seed 版"） |
| `ConfigSourceTransitionGateTest.every_transition_source_declares_itself_as_a_transition` | ⑦ `describeSource()` 返回值里必须有「过渡」 |
| `ConfigSourceTransitionGateTest.seed_resource_constant_points_to_the_classpath_seed_file` | ⑧ `SEED_RESOURCE` 常量必须精确指向 classpath 声明文件 |

## 注入表

| 组 | 文件 | 注入内容 | 期望变红 |
|---|---|---|---|
| C1 | `dy-app/.../application.yml` | prod 段 `backend: ${DY_IDEMPOTENCY_BACKEND:redis}` → `backend: memory` | ④ |
| C2 | `dy-web/.../IdempotencyConfiguration.java` | 自检守卫 `if (!prodActive \|\| !MEMORY.equals(mode))` → `if (true)`（恒 return ⇒ 永不抛） | ① |
| C3 | `dy-app/.../ConfigSeedScaleProfileSource.java` | `describeSource()` 去掉「过渡」字样 | ⑦ |
| C4 | `dy-app/.../ConfigSeedRefundProfileSource.java` | `SEED_RESOURCE` 常量改名（模拟来源被悄悄换掉） | ⑧ |

## 纪律（与 102~109 同口径）

- **锚点预检**（存在 + 唯一）· **元层判别力自证**（基线零出现）· **逐字节还原** · **还原后复绿**。
- 🛑 **注入必须保持源文件语法合法**：C2 只改守卫条件（`if (...)` → `if (true)`），
  仍是合法 Java 且语义被掏空；C3 只改一个字符串字面量；C4 只改常量字面量；
  C1 只改 YAML 标量值。
- 🛑 **must_see 锚点必须 ASCII**（方法名）—— Windows GBK 下中文断言消息会乱码。
- 🛑 **跨模块注入要跑对模块**：C2 改的是 `dy-web` 源 ⇒ 跑 dy-web；
  C1/C3/C4 改的是 `dy-app` 源 / 资源 ⇒ 跑 dy-app。
  两处都**不需要 install**（同模块内 `test` 即可，且门禁直读文件 / classpath 资源）。
- 🛑 Windows 下用 `mvn.cmd` 绝对路径 + list 参数 + `shell=False`；**不加 `-q`**。

用法：
    python.exe verification/110_b2_b3_reverse_verification.py
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
B2_SELFCHECK = "production_profile_refuses_to_start_with_the_in_memory_backend"
B2_NOTIFY = "non_production_profiles_keep_defaulting_to_memory"
B2_VARIANTS = "production_profile_check_survives_whitespace_and_case_variants"
B2_CONFIG = "prod_profile_declares_a_redis_default_for_the_idempotency_backend"

B3_FULLSET = "transition_sources_are_exactly_the_registered_set"
B3_SINGLE = "no_config_port_has_more_than_one_implementation"
B3_TRANSITION = "every_transition_source_declares_itself_as_a_transition"
B3_SEEDRES = "seed_resource_constant_points_to_the_classpath_seed_file"

APP_GATES = "ConfigSourceTransitionGateTest,IdempotencyProdBackendConfigGateTest"
APP_METHODS = [B2_CONFIG, B3_FULLSET, B3_SINGLE, B3_TRANSITION, B3_SEEDRES]

WEB_GATE = "IdempotencyBackendFailureTest"
WEB_METHODS = [B2_SELFCHECK, B2_NOTIFY, B2_VARIANTS]

# --- 文件（相对 skeleton 根） ---
F_YML = "dy-app/src/main/resources/application.yml"
F_IDEM_CFG = "dy-web/src/main/java/com/diaoyuanyun/dy/web/idempotent/IdempotencyConfiguration.java"
F_SCALE = "dy-app/src/main/java/com/diaoyuanyun/dy/app/scale/service/ConfigSeedScaleProfileSource.java"
F_REFUND = "dy-app/src/main/java/com/diaoyuanyun/dy/app/refund/service/ConfigSeedRefundProfileSource.java"

INJECTIONS = [
    (
        "C1", F_YML, "app",
        "    backend: ${DY_IDEMPOTENCY_BACKEND:redis}",
        "    backend: memory",
        B2_CONFIG,
    ),
    (
        "C2", F_IDEM_CFG, "web",
        "        if (!prodActive || !MEMORY.equals(mode)) {",
        "        if (true) {",
        B2_SELFCHECK,
    ),
    (
        "C3", F_SCALE, "app",
        '                + "（过渡来源；配置真相源落库后应替换为 DB 读取实现）";',
        '                + "（配置真相源落库后应替换为 DB 读取实现）";',
        B3_TRANSITION,
    ),
    (
        "C4", F_REFUND, "app",
        'private static final String SEED_RESOURCE = "db/config/02_slots_seed.sql";',
        'private static final String SEED_RESOURCE = "db/config/02_slots_seed_renamed.sql";',
        B3_SEEDRES,
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


def run_app_gates() -> tuple[int, str]:
    return run_maven([
        "-pl", "dy-app", "test",
        "-Dtest=" + APP_GATES,
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-Dsurefire.failIfNoTests=false",
    ])


def run_web_gates() -> tuple[int, str]:
    return run_maven([
        "-pl", "dy-web", "test",
        "-Dtest=" + WEB_GATE,
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-Dsurefire.failIfNoTests=false",
    ])


def run_gates(scope: str) -> tuple[int, str]:
    return run_app_gates() if scope == "app" else run_web_gates()


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
    log("批次十二 · B-2 生产幂等纪律 + B-3 过渡态门禁 · 反向验证")
    log("=" * 74)

    for rel in {F_YML, F_IDEM_CFG, F_SCALE, F_REFUND}:
        snapshot(rel)
        log(f"[快照] {rel} bytes={len(_SNAPSHOTS[rel])}")

    texts = {rel: _SNAPSHOTS[rel].decode("utf-8") for rel in _SNAPSHOTS}
    problems = []
    for tag, rel, _scope, anchor, _repl, _expect in INJECTIONS:
        n = texts[rel].count(anchor)
        log(f"[预检 {'OK ' if n == 1 else 'BAD'}] {tag} 锚点出现 {n} 次")
        if n != 1:
            problems.append(f"{tag}: 锚点出现 {n} 次（要求恰 1 次）")
    if problems:
        log("\n[预检失败] " + " | ".join(problems))
        return 2

    log("\n[基线] 注入前跑两个模块的门禁（期望全绿）...")
    ca, outa = run_app_gates()
    cw, outw = run_web_gates()
    if ca != 0 or cw != 0:
        log(f"[基线失败] 注入前门禁未通过（app exit={ca}, web exit={cw}）—— 后续结论无意义")
        log((outa + outw)[-3000:])
        return 2

    allm = APP_METHODS + WEB_METHODS
    pre = hits(outa, allm) | hits(outw, allm)
    if pre:
        log(f"[元层自证失败] 基线（全绿）输出里已出现待查方法名 {sorted(pre)} "
            f"—— must_see 锚点恒真，本脚本无判别力")
        return 2
    log("[基线 OK] 注入前全绿，且待查方法名在基线中【零出现】（锚点有判别力）")

    results = []
    for tag, rel, scope, anchor, repl, expect in INJECTIONS:
        log(f"\n[{tag}] 注入 {rel}（模块 {scope}）：{anchor[:56].replace(chr(10), ' / ')}...")
        p = SKEL / rel
        text = _SNAPSHOTS[rel].decode("utf-8")
        assert text.count(anchor) == 1
        p.write_text(text.replace(anchor, repl), encoding="utf-8", newline="")

        code2, out2 = run_gates(scope)
        got = hits(out2, allm)
        caught = (code2 != 0) and (expect in got)
        log(f"[{tag}] 门禁 exit={code2} · 期望红 `{expect}` 被抓={caught} · 红集={sorted(got)}")
        results.append((tag, expect, caught, code2, sorted(got)))

        p.write_bytes(_SNAPSHOTS[rel])
        assert p.read_bytes() == _SNAPSHOTS[rel], f"{tag} 还原后字节不一致"

    log("\n[复绿] 全部还原后重跑两个模块的门禁（期望全绿）...")
    ca3, outa3 = run_app_gates()
    cw3, outw3 = run_web_gates()
    green = (ca3 == 0) and (cw3 == 0)
    log(f"[复绿] app exit={ca3} · web exit={cw3} · 复绿={green}")

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