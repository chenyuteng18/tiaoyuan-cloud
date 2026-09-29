#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
C-5 · 可观测性「指标禁租户标签」的反向验证 —— 「注入错误必须被抓住」。

针对 C-5 落地后 A7 硬门禁（{@code TenantTagGuardTest}，8 例）的两条真实防线：
  M1  禁令集合被掏空：把 TenantTagPolicy.FORBIDDEN_TAG_KEYS 清成空集
      ⇒ 所有断言会【平凡通过】—— 一个"常绿"的门禁比没有门禁更危险。
         这正是 TenantTagGuardTest.forbidden_key_set_is_not_empty 要防的形态。
  M2  公共标签装配被污染：给 ObservabilityConfiguration.dyCommonTags 加上 tenant_id
      ⇒ 公共标签应用到【每一个】Meter，是本项目最可能"顺手加一个租户维度"的位置。
         一旦加上，全平台指标的基数会随租户数爆炸，且 /actuator/prometheus
         会泄漏出全平台租户清单。

锚点 ASCII / 唯一 / 逐字节还原 —— 同 93/95/96/97/98/99/101_*.py 纪律。

🛑 与 101_*.py 的关系：101 是 C-1（审计链端点）的注入；本文件是 C-5 的补课。
    C-5 的实现（TenantTagPolicy / ObservabilityConfiguration / 4 个测试类 28 例）
    早已落地，但**从未有过"改产品代码、看门禁是否变红"的脚本化证据** ——
    TenantTagGuardTest 自身只在测试内构造注入（in-process），那证明的是
    "检测函数有区分力"，不证明"产品侧的配置项被改动时构建会失败"。
    本文件补上后者，两者合起来才是完整证据。

🛑 编码须知（同 99/101_*.py）：must_see 锚点一律用 ASCII 全限定方法名。
"""

from __future__ import annotations

import atexit
import os
import re
import signal
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SKELETON = os.path.dirname(HERE)

_SNAPSHOT: dict[str, bytes] = {}


def _restore_all() -> None:
    for path, raw in list(_SNAPSHOT.items()):
        try:
            current = read_bytes(path)
        except OSError:
            continue
        if current != raw:
            try:
                restore_bytes(path, raw)
                sys.stderr.write(f"\n[兜底还原] {path}\n")
            except OSError as e:
                sys.stderr.write(f"\n[兜底还原失败] {path}: {e}\n")


def _on_signal(signum, _frame):
    _restore_all()
    sys.stderr.write(f"\n🛑 收到信号 {signum}，已兜底还原后退出\n")
    sys.exit(3)


atexit.register(_restore_all)
for _sig in (signal.SIGTERM, signal.SIGINT):
    try:
        signal.signal(_sig, _on_signal)
    except (ValueError, OSError):
        pass

MAVEN_CMD = r"C:\opt\apache-maven-3.9.9\bin\mvn.cmd"
JAVA_HOME_WIN = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
PG_BIN_WIN = r"C:\Program Files\PostgreSQL\17\bin"
RUN_BASE = ["-o", "-q", "-DskipTests", "install"]

GATE = "TenantTagGuardTest"
TARGET_MODULE = "dy-web"

POLICY = "dy-web/src/main/java/com/diaoyuanyun/dy/web/observability/TenantTagPolicy.java"
CONFIG = "dy-web/src/main/java/com/diaoyuanyun/dy/web/observability/ObservabilityConfiguration.java"

INJECTIONS = [
    {
        "id": "M1",
        "gap": "禁令集合被掏空：FORBIDDEN_TAG_KEYS 清成空集 ⇒ 所有断言平凡通过（常绿门禁）",
        "target": POLICY,
        "old": '    public static final Set<String> FORBIDDEN_TAG_KEYS = Set.of("tenant_id", "tenantid", "tenant");',
        "new": '    public static final Set<String> FORBIDDEN_TAG_KEYS = Set.of();',
        "test": GATE,
        "must_see": "TenantTagGuardTest.forbidden_key_set_is_not_empty",
        "expected": "必须变红：禁令集合为空 ⇒ isForbidden 恒 false ⇒ 所有检查都判合规。"
                    "一个『常绿』的门禁比没有门禁更危险，因为它给出虚假保证 —— "
                    "本用例的存在就是为了让「清空禁令」这件事在构建期就响。",
    },
    {
        "id": "M2",
        "gap": "公共标签装配被污染：dyCommonTags 加上 tenant_id（最可能被顺手加的位置）",
        "target": CONFIG,
        "old": '        return registry -> registry.config().commonTags("application", applicationName, "instance_id", instanceId);',
        "new": '        return registry -> registry.config().commonTags("application", applicationName,\n'
               '                "instance_id", instanceId, "tenant_id",\n'
               '                System.getProperty("dy.probe.tenant", "t-0001"));',
        "test": GATE,
        "must_see": "TenantTagGuardTest.common_tag_customizer_does_not_inject_tenant_dimension",
        "expected": "必须变红：公共标签会应用到【每一个】Meter，故这条注入会让全平台指标带上租户维度。"
                    "基数会随租户数爆炸（监控后端过载），且 /actuator/prometheus 会泄漏全平台租户清单。"
                    "这正是 ADR-09 §7.2 明令禁止的那条。",
    },
]


def read_bytes(path: str) -> bytes:
    with open(path, "rb") as f:
        return f.read()


def read_text(path: str) -> str:
    return read_bytes(path).decode("utf-8").replace("\r\n", "\n")


def write_text(path: str, content: str) -> None:
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(content)


def restore_bytes(path: str, raw: bytes) -> None:
    with open(path, "wb") as f:
        f.write(raw)


def mvn(args: list[str], extra_env: dict | None = None):
    env = dict(os.environ)
    parts = [os.path.dirname(MAVEN_CMD), PG_BIN_WIN]
    if os.path.isdir(JAVA_HOME_WIN):
        parts.append(os.path.join(JAVA_HOME_WIN, "bin"))
        env["JAVA_HOME"] = JAVA_HOME_WIN
    env["PATH"] = os.pathsep.join(parts + [env.get("PATH", "")])
    if extra_env:
        env.update(extra_env)
    proc = subprocess.run([MAVEN_CMD] + args, cwd=SKELETON, env=env,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    return proc.returncode, proc.stdout.decode("utf-8", errors="replace")


def build() -> tuple[int, str]:
    return mvn(RUN_BASE)


def run_test(test_class: str):
    args = ["-o", "-pl", TARGET_MODULE, "test",
            "-Dtest=" + test_class, "-DfailIfNoTests=false"]
    return mvn(args)


def extract_failure_block(output: str) -> str:
    lines = output.splitlines()
    out = []
    in_block = False
    for ln in lines:
        if re.match(r"^\[ERROR\] (Failures|Errors):", ln):
            in_block = True
        if in_block:
            out.append(ln)
        if in_block and ln.startswith("[INFO]"):
            break
    if not out:
        out = [ln for ln in lines if "AssertionFailedError" in ln or "🛑" in ln]
    return "\n".join(out)


def main() -> int:
    report = []
    ok = True

    for inj in INJECTIONS:
        path = os.path.join(SKELETON, inj["target"])
        if path not in _SNAPSHOT:
            _SNAPSHOT[path] = read_bytes(path)
        hits = read_text(path).count(inj["old"])
        if hits != 1:
            print(f"🛑 {inj['id']} 锚点命中 {hits} 次（要求恰 1 次）: {inj['old'][:140]}")
            ok = False
    if not ok:
        print("🛑 锚点预检未通过，终止（未改动任何文件）")
        return 2

    print("== 基线 ==", flush=True)
    bc, bo = build()
    if bc != 0:
        print(bo[-3000:]); print("🛑 基线 install 失败"); return 2
    rc, ro = run_test(GATE)
    print(f"  baseline {GATE}: {'PASS' if rc == 0 else 'FAIL'}", flush=True)
    if rc != 0:
        print(extract_failure_block(ro)[-2500:])
        print("🛑 基线不绿。⚠️ 先确认工作区没有被上一次中断的运行留下注入残留。")
        return 2

    touched: dict[str, bytes] = dict(_SNAPSHOT)
    for inj in INJECTIONS:
        path = os.path.join(SKELETON, inj["target"])
        raw = _SNAPSHOT[path]
        original = raw.decode("utf-8").replace("\r\n", "\n")

        print(f"\n== {inj['id']} · {inj['gap']} ==", flush=True)
        hits = original.count(inj["old"])
        if hits == 0:
            print("🛑 锚点未命中: " + inj["old"][:160])
            ok = False
            report.append((inj, "ANCHOR-MISS", "", "锚点未命中"))
            continue
        if hits > 1:
            print(f"🛑 锚点不唯一（{hits} 次）")
            ok = False
            report.append((inj, "ANCHOR-AMBIGUOUS", "", f"{hits} 次"))
            continue

        write_text(path, original.replace(inj["old"], inj["new"], 1))
        try:
            bc, bo = build()
            if bc != 0:
                print("  注入后编译失败（也算被抓住）", flush=True)
                report.append((inj, "COMPILE-FAIL", extract_failure_block(bo), "编译失败"))
                ok = False
                continue
            rc, ro = run_test(inj["test"])
            caught = (rc != 0) and (inj["must_see"] in ro)
            print(f"  {inj['id']} 注入后 {inj['test']}: "
                  f"{'RED(被抓)' if rc != 0 else 'GREEN(未被抓!)'}", flush=True)
            if not caught:
                ok = False
                print("  🛑 未被抓住: " + inj["must_see"])
                print(extract_failure_block(ro)[-2000:])
                report.append((inj, "NOT-CAUGHT", extract_failure_block(ro), inj["expected"]))
            else:
                report.append((inj, "CAUGHT", extract_failure_block(ro), inj["expected"]))
        finally:
            restore_bytes(path, raw)
            print("  已还原: " + inj["target"], flush=True)

    print("\n== 逐字节还原校验 ==", flush=True)
    for path, raw in touched.items():
        now = read_bytes(path)
        rel = os.path.relpath(path, SKELETON).replace(os.sep, "/")
        if now == raw:
            print(f"  ✔ 逐字节一致: {rel}  ({len(raw)} 字节)", flush=True)
        else:
            ok = False
            print(f"  🛑 还原后不一致: {rel}", flush=True)

    print("\n== 还原后基线 ==", flush=True)
    bc, bo = build()
    if bc != 0:
        print(bo[-3000:]); ok = False
    else:
        rc, ro = run_test(GATE)
        print(f"  final {GATE}: {'PASS' if rc == 0 else 'FAIL'}", flush=True)
        if rc != 0:
            ok = False

    out_path = os.path.join(HERE, "102_c5_observability_reverse_verification.md")
    with open(out_path, "w", encoding="utf-8", newline="") as f:
        f.write("# C-5 · 可观测性「指标禁租户标签」反向验证证据\n\n")
        f.write("三列证据：注入内容 | 预期失败点 | 实际失败断言原文\n\n")
        f.write("> 🛑 编码须知同 94/98/99/101_*.md：乱码为证据原貌，判定以英文方法名/码为准。\n\n")
        f.write("> 说明：本文件补的是**产品代码侧**的注入。`TenantTagGuardTest` 自身只在测试内\n")
        f.write("> 构造注入（证明检测函数有区分力），它**不**证明「产品侧的禁令集合/公共标签装配\n")
        f.write("> 被改动时构建会失败」。两者合起来才是完整证据。\n\n")
        for inj, verdict, proof, expected in report:
            f.write(f"## {inj['id']} · {inj['gap']}\n\n")
            f.write(f"- 目标文件：`{inj['target']}`\n")
            f.write(f"- 注入：`{inj['old'][:200]}` -> `{inj['new'][:200]}`\n")
            f.write(f"- 捕捉者：`{inj['test']}`\n")
            f.write(f"- 预期失败点：{expected}\n")
            f.write(f"- 判定：**{verdict}**\n")
            f.write("- 实际失败断言原文：\n\n```\n")
            f.write((proof or "(empty)")[:4000])
            f.write("\n```\n\n")
        f.write("\n被触碰源文件（逐字节还原校验）：\n\n")
        for path in touched:
            rel = os.path.relpath(path, SKELETON).replace(os.sep, "/")
            f.write(f"- `{rel}`\n")
        f.write(f"\n总体：{'全部被抓 且 已还原全绿' if ok else '存在未通过项（见上）'}\n")

    print("\n证据已落盘: " + out_path, flush=True)
    print("\n总体: " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())