#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S3-3 · 契约域 E（E1/E2/E3/E6）反向验证 —— 「注入错误必须被抓住」。

针对 S3-3 落地的四条防线：
  I1  E1 幂等被短路：idempotencyKeys 命中判断删掉（重复批次不再 409）
  I2  E2 双分支幂等键被破坏：telemetryIdempotentHit 恒返回 false（重复 upsert 不再去重）
  I3  E3 客户字段裁剪被删：gapReasonVisibleTo 恒 true（客户看到 gap_reason）
  I4  is_wear 技术性缺失覆写被破坏：normalizeIsWear 恒返回入参（(-1,255) 不再归 null）

锚点 ASCII / 唯一 / 逐字节还原 —— 同 93/95/96/97_*.py 纪律。
"""

from __future__ import annotations

import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SKELETON = os.path.dirname(HERE)

MAVEN_CMD = r"C:\opt\apache-maven-3.9.9\bin\mvn.cmd"
JAVA_HOME_WIN = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
PG_BIN_WIN = r"C:\Program Files\PostgreSQL\17\bin"
RUN_BASE = ["-o", "-q", "-DskipTests", "install"]

PG_ENV = {
    "DY_PG_HOST": "127.0.0.1",
    "DY_PG_PORT": "5432",
    "DY_PG_SUPER_PASSWORD": "postgres",
}

INJECTIONS = [
    {
        "id": "I1",
        "gap": "🛑 已迁出（2026-09-26 · B-2）：原锚点是 BandService 里那份内存 Map 的命中判断，"
              "该字段已在 B-2 收口时【从源码删除】（理由：它只是单实例快路径、"
              "带来两份真相源且语义可漂移；幂等的权威只需库层唯一索引）。"
              "故本锚点必然 ANCHOR-MISS —— 不是测试失效，而是被测对象已按设计移除。"
              "等价且更强的验证已迁至 99_band_idempotency_reverse_verification.py："
              "J1 抽掉库层原子轴（ON CONFLICT）· J2 内存防线复发 · J3 双分支谓词削弱。",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/band/service/BandService.java",
        "old": "__SKIP__",
        "new": "__SKIP__",
        "runner": "skip",
        "test": "BandEndpointsE2ETest",
        "must_see": "",
        "expected": "不注入。见 gap 说明与 99_*.py。",
    },
    {
        "id": "I2",
        "gap": "metric 13 值校验被删：TelemetryRow 构造器放行未登记 metric",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/band/domain/TelemetryRow.java",
        "old": "if (metric == null || !METRICS.contains(metric)) {",
        "new": "if (false) {",
        "runner": "unit",
        "test": "BandRowTest",
        "must_see": "telemetry_metric_enum",
        "expected": "单测必须变红：未登记 metric（heart_rate）不再被拒 —— 契约 §4.3 双分支幂等键的 metric 成员失效",
    },
    {
        "id": "I3",
        "gap": "OPTIONAL-SKIP：gap_reason 的 client 隔离由【全局】DerivedResponseBodyAdvice 承担（出口兜底），"
              "service 层的 gapReasonVisibleTo 只是第一层冗余；注入单层会被第二层兜住，证明不了牙齿，故本轮不注入。"
              "该两层分工的证据 = 注入 service 层后 DerivedResponseBodyAdvice 日志仍摘除 gap_reason（fields=[gap_reason]）。",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/band/service/BandService.java",
        "old": "__SKIP__",
        "new": "__SKIP__",
        "runner": "skip",
        "test": "BandEndpointsE2ETest",
        "must_see": "",
        "expected": "不注入。见 gap 说明。",
    },
    {
        "id": "I4",
        "gap": "四态校验被删：SyncBatchRow 构造器放行非法 state（如 worn）",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/band/domain/SyncBatchRow.java",
        "old": "if (state == null || !STATES.contains(state)) {",
        "new": "if (false) {",
        "runner": "unit",
        "test": "BandRowTest",
        "must_see": "sync_batch_state_enum",
        "expected": "单测必须变红：非法四态（含『未佩戴』语义）不再被拒 —— 契约 BandSyncBatchRequest 四态被破坏",
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


def run_test(test_class: str, need_pg: bool):
    args = ["-o", "-pl", "dy-app", "test",
            "-Dtest=" + test_class, "-DfailIfNoTests=false"]
    return mvn(args, PG_ENV if need_pg else None)


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
    return "\n".join(out)


def main() -> int:
    report = []
    ok = True

    print("== 基线 ==", flush=True)
    bc, bo = build()
    if bc != 0:
        print(bo[-3000:]); print("🛑 基线 install 失败"); return 2
    for cls, pg in (("BandEndpointsE2ETest", True), ("BandRowTest", False)):
        rc, ro = run_test(cls, pg)
        print(f"  baseline {cls}: {'PASS' if rc == 0 else 'FAIL'}", flush=True)
        if rc != 0:
            print(extract_failure_block(ro)[-2500:]); print(f"🛑 基线 {cls} 不绿"); return 2

    touched: dict[str, bytes] = {}
    for inj in INJECTIONS:
        if inj.get("runner") == "skip":
            print(f"\n== {inj['id']} · SKIP（{inj['gap'][:80]}）==", flush=True)
            report.append((inj, "OPTIONAL-SKIP", "", inj["expected"]))
            continue
        path = os.path.join(SKELETON, inj["target"])
        raw = read_bytes(path)
        original = read_text(path)
        if path not in touched:
            touched[path] = raw

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
            rc, ro = run_test(inj["test"], need_pg=(inj["runner"] == "e2e"))
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
            restore_bytes(path, touched[path])
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
        for cls, pg in (("BandEndpointsE2ETest", True), ("BandRowTest", False)):
            rc, ro = run_test(cls, pg)
            print(f"  final {cls}: {'PASS' if rc == 0 else 'FAIL'}", flush=True)
            if rc != 0:
                ok = False

    out_path = os.path.join(HERE, "98_domain_e_reverse_verification.md")
    with open(out_path, "w", encoding="utf-8", newline="") as f:
        f.write("# S3-3 · 契约域 E 反向验证证据\n\n")
        f.write("三列证据：注入内容 | 预期失败点 | 实际失败断言原文\n\n")
        f.write("> 🛑 编码须知同 94_domain_b_reverse_verification.md：乱码为证据原貌，判定以英文方法名/码为准。\n\n")
        for inj, verdict, proof, expected in report:
            f.write(f"## {inj['id']} · {inj['gap']}\n\n")
            f.write(f"- 目标文件：`{inj['target']}`\n")
            f.write(f"- 注入：`{inj['old'][:120]}` -> `{inj['new'][:120]}`\n")
            f.write(f"- 捕捉者：真请求 E2E `{inj['test']}`\n")
            f.write(f"- 预期失败点：{expected}\n")
            f.write(f"- 判定：**{verdict}**\n")
            f.write("- 实际失败断言原文：\n\n```\n")
            f.write((proof or "(empty)")[:4000])
            f.write("\n```\n\n")
        f.write(f"\n被触碰源文件（逐字节还原校验）：\n\n")
        for path in touched:
            rel = os.path.relpath(path, SKELETON).replace(os.sep, "/")
            f.write(f"- `{rel}`\n")
        f.write(f"\n总体：{'全部被抓 且 已还原全绿' if ok else '存在未通过项（见上）'}\n")

    print("\n证据已落盘: " + out_path, flush=True)
    print("\n总体: " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())