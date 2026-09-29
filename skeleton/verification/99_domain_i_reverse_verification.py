#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S3-4 · 契约域 I（I1/I2/I4/I5/I6/I8）反向验证 —— 「注入错误必须被抓住」。

针对 S3-4 落地的四条防线：
  I1  I8 规则③ phone 脱敏被短路：maskPhone 恒返回原值（完整手机号进入渲染结果）
  I2  I8 规则② 白名单越界被删：未在白名单的占位符被静默放过（不 2004）
  I3  I8 规则① fail-closed 被删：未声明 placeholder_schema 却携占位符不再拒
  I4  版本不可覆盖被破坏：version < 1 校验删除（领域构造器放行）

锚点 ASCII / 唯一 / 逐字节还原 —— 同 93/95/96/97/98_*.py 纪律。
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
        "gap": "I8 规则③ phone 脱敏被短路：maskPhone 恒返回原值",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/doctpl/service/DocTemplateService.java",
        "old": "return phone.substring(0, 3) + \"****\" + phone.substring(phone.length() - 4);",
        "new": "return phone;",
        "runner": "e2e",
        "test": "DocTemplateE2ETest",
        "must_see": "render_with_mask",
        "expected": "E2E 必须变红：渲染结果出现完整手机号（13812345678）—— "
                    "契约 I8 规则③「原名不得出现在文书内」被破坏",
    },
    {
        "id": "I2",
        "gap": "I8 规则②白名单越界被删：未在白名单的占位符被静默放过",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/doctpl/service/DocTemplateService.java",
        "old": "if (!whitelist.contains(key)) {",
        "new": "if (false && !whitelist.contains(key)) {",
        "runner": "e2e",
        "test": "DocTemplateE2ETest",
        "must_see": "render_out_of_whitelist",
        "expected": "E2E 必须变红：未声明占位符（not_declared）不再 2004 —— "
                    "契约 I8 规则②「不静默留空」被破坏",
    },
    {
        "id": "I3",
        "gap": "OPTIONAL-SKIP：规则①（整体 fail-closed）与规则②（逐 key 拒绝）对『空白名单 + 携占位符』"
              "是纵深双层 —— 破单层规则①会被规则②的 !whitelist.contains(key) 兜住，"
              "prove 不了牙齿（与 S3-3 的 gap_reason 双层防线同款）。故不注入。",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/doctpl/service/DocTemplateService.java",
        "old": "__SKIP__",
        "new": "__SKIP__",
        "runner": "skip",
        "test": "DocTemplateE2ETest",
        "must_see": "",
        "expected": "不注入。见 gap 说明。",
    },
    {
        "id": "I4",
        "gap": "版本不可覆盖被破坏：version < 1 校验删除",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/doctpl/domain/DocTemplateRow.java",
        "old": "if (version < 1) {",
        "new": "if (false && version < 1) {",
        "runner": "unit",
        "test": "DocTemplateRowTest",
        "must_see": "version_lt_1_fails",
        "expected": "单测必须变红：version=0 不再被拒 —— 契约 I4「版本递增不可覆盖」的领域闸被破坏",
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
    for cls, pg in (("DocTemplateE2ETest", True), ("DocTemplateRowTest", False)):
        rc, ro = run_test(cls, pg)
        print(f"  baseline {cls}: {'PASS' if rc == 0 else 'FAIL'}", flush=True)
        if rc != 0:
            print(extract_failure_block(ro)[-2500:]); print(f"🛑 基线 {cls} 不绿"); return 2

    touched: dict[str, bytes] = {}
    for inj in INJECTIONS:
        if inj.get("runner") == "skip":
            print(f"\n== {inj['id']} · SKIP ==", flush=True)
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
        for cls, pg in (("DocTemplateE2ETest", True), ("DocTemplateRowTest", False)):
            rc, ro = run_test(cls, pg)
            print(f"  final {cls}: {'PASS' if rc == 0 else 'FAIL'}", flush=True)
            if rc != 0:
                ok = False

    out_path = os.path.join(HERE, "99_domain_i_reverse_verification.md")
    with open(out_path, "w", encoding="utf-8", newline="") as f:
        f.write("# S3-4 · 契约域 I 反向验证证据\n\n")
        f.write("三列证据：注入内容 | 预期失败点 | 实际失败断言原文\n\n")
        f.write("> 🛑 编码须知同 94_domain_b_reverse_verification.md：乱码为证据原貌，判定以英文方法名/码为准。\n\n")
        for inj, verdict, proof, expected in report:
            f.write(f"## {inj['id']} · {inj['gap']}\n\n")
            f.write(f"- 目标文件：`{inj['target']}`\n")
            f.write(f"- 注入：`{inj['old'][:120]}` -> `{inj['new'][:120]}`\n")
            f.write(f"- 捕捉者：{'真请求 E2E' if inj['runner'] == 'e2e' else '领域单测'} `{inj['test']}`\n")
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