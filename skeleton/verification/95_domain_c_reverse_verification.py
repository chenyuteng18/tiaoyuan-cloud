#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S3-1 · 契约域 C（C1~C3）反向验证 —— 「注入错误必须被抓住」。

针对 S3-1 落地的 C1/C2/C3 四类缺口逐一注入回归：
    注入后 -> 对应的门禁 / 真请求 E2E 必须【由绿变红】
    还原后 -> 必须【回到绿】

要抓住的缺口：
  I1  码无主：把 C2 的 assessment:write 改成一个注册表里不存在的码
  I2  migratable 四要素推导被改坏：item_group_id 为空的判断被删
  I3  维度分/总分自洽校验被删：不自洽时不再阻断
  I4  客户不下发 migratable 的裁剪被删：客户响应体出现 migratable

锚点必须 ASCII / 锚点必须唯一 / 还原按字节 —— 详见同目录 93_*.py 的既有注释
（这三条纪律是前几轮反向验证用真实误判逼出来的，本轮不重复展开）。
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
        "gap": "码无主：把 C2 的 assessment:write 改成一个注册表里不存在的码",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/assessment/controller/AssessmentController.java",
        "old": '@RequirePermission("assessment:write")\n    @PostMapping("/customers/{id}/assessments/baseline")',
        "new": '@RequirePermission("assessment:phantom")\n    @PostMapping("/customers/{id}/assessments/baseline")',
        "runner": "gate",
        "test": "PermissionCodeRegistrationGateTest",
        "must_see": "every_require_permission_code_has_at_least_one_holder",
        "expected": "码级门禁第②条必须报出 orphans 非空（assessment:phantom 被声明、却无人持有）",
    },
    {
        "id": "I2",
        "gap": "migratable 四要素推导被改坏：把【题组ID为空 → false】删成恒 true",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/assessment/service/AssessmentService.java",
        "old": "boolean migratable = itemGroupId != null && scaleVersion != null;",
        "new": "boolean migratable = true;",
        "runner": "e2e",
        "test": "AssessmentDomainCEndpointsE2ETest",
        "must_see": "missing_item_group_is_migratable_false",
        "expected": "真请求 E2E 必须变红：缺 item_group_id 时仍 migratable=true —— "
                    "四要素推导被静默破坏，effect_verdict=NULL 的防线失守",
    },
    {
        "id": "I3",
        "gap": "维度分/总分自洽校验被删：总分≠Σ维度分时不再阻断",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/assessment/service/AssessmentService.java",
        "old": "if (sum != totalScore) {",
        "new": "if (false) {",
        "runner": "e2e",
        "test": "AssessmentDomainCEndpointsE2ETest",
        "must_see": "inconsistent_scores_is_422",
        "expected": "真请求 E2E 必须变红：总分 55 ≠ Σ维度分 56 时不再 422 —— "
                    "不自洽的数据会静默落库，让同源复评的改善率分母漂移",
    },
    {
        "id": "I4",
        "gap": "客户不下发 migratable 的裁剪被删：客户响应体出现 migratable 键",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/assessment/service/AssessmentService.java",
        "old": "public static boolean migratableVisibleTo(VisibilityRole role) {\n"
              "        return role != null && role != VisibilityRole.CLIENT;\n"
              "    }",
        "new": "public static boolean migratableVisibleTo(VisibilityRole role) {\n"
              "        return true;\n"
              "    }",
        "runner": "e2e",
        "test": "AssessmentDomainCEndpointsE2ETest",
        "must_see": "client_sees_no_migratable",
        "expected": "真请求 E2E 必须变红：客户的 C3 响应体出现 migratable —— "
                    "契约 BaselineAssessmentData.migratable 的 x-visible-to 不含 client，"
                    "无权限字段必须【不下发】（契约硬约束①）",
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

    print("== 基线（注入前必须全绿）==", flush=True)
    bc, bo = build()
    if bc != 0:
        print(bo[-3000:])
        print("🛑 基线 install 失败 —— 反向验证无意义，先修构建")
        return 2
    for cls, pg in (("PermissionCodeRegistrationGateTest", False),
                    ("AssessmentDomainCEndpointsE2ETest", True)):
        rc, ro = run_test(cls, pg)
        status = "PASS" if rc == 0 else "FAIL"
        print(f"  baseline {cls}: {status}", flush=True)
        if rc != 0:
            print(extract_failure_block(ro)[-2500:])
            print(f"🛑 基线 {cls} 不是绿的 —— 反向验证无意义")
            return 2

    touched: dict[str, bytes] = {}
    for inj in INJECTIONS:
        path = os.path.join(SKELETON, inj["target"])
        raw = read_bytes(path)
        original = read_text(path)
        if path not in touched:
            touched[path] = raw

        print(f"\n== {inj['id']} · {inj['gap']} ==", flush=True)
        hits = original.count(inj["old"])
        if hits == 0:
            print("🛑 锚点未命中（注入无效，结论不可采信）：")
            print("   anchor: " + inj["old"][:160])
            ok = False
            report.append((inj, "ANCHOR-MISS", "", "锚点未命中"))
            continue
        if hits > 1:
            print(f"🛑 锚点不唯一（出现 {hits} 次）—— 注入位置不确定，结论不可采信：")
            print("   anchor: " + inj["old"][:160])
            ok = False
            report.append((inj, "ANCHOR-AMBIGUOUS", "",
                           f"锚点在源码中出现 {hits} 次"))
            continue

        injected = original.replace(inj["old"], inj["new"], 1)
        write_text(path, injected)

        try:
            bc, bo = build()
            if bc != 0:
                print("  注入后编译失败（也算被抓住，但形态是编译而非断言）", flush=True)
                report.append((inj, "COMPILE-FAIL", extract_failure_block(bo),
                               "注入导致编译失败"))
                ok = False
                continue

            rc, ro = run_test(inj["test"], need_pg=(inj["runner"] == "e2e"))
            caught = (rc != 0) and (inj["must_see"] in ro)
            print(f"  {inj['id']} 注入后 {inj['test']}: "
                  f"{'RED(被抓)' if rc != 0 else 'GREEN(未被抓!)'}", flush=True)

            if not caught:
                ok = False
                print("  🛑 未被抓住 —— 该守护是纸做的：")
                print("  期望出现的用例: " + inj["must_see"])
                print(extract_failure_block(ro)[-2000:])
                report.append((inj, "NOT-CAUGHT", extract_failure_block(ro),
                               inj["expected"]))
            else:
                report.append((inj, "CAUGHT", extract_failure_block(ro),
                               inj["expected"]))
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
            print(f"  🛑 还原后与原始字节不一致: {rel}", flush=True)

    print("\n== 还原后基线（必须回到全绿）==", flush=True)
    bc, bo = build()
    if bc != 0:
        print(bo[-3000:]); ok = False
    else:
        for cls, pg in (("PermissionCodeRegistrationGateTest", False),
                        ("AssessmentDomainCEndpointsE2ETest", True)):
            rc, ro = run_test(cls, pg)
            print(f"  final {cls}: {'PASS' if rc == 0 else 'FAIL'}", flush=True)
            if rc != 0:
                ok = False
                print(extract_failure_block(ro)[-2500:])

    out_path = os.path.join(HERE, "95_domain_c_reverse_verification.md")
    with open(out_path, "w", encoding="utf-8", newline="") as f:
        f.write("# S3-1 · 契约域 C 反向验证证据\n\n")
        f.write("三列证据：注入内容 | 预期失败点 | 实际失败断言原文\n\n")
        f.write("> 🛑 编码须知同 94_domain_b_reverse_verification.md："
                "Maven 前缀行 GBK 与 surefire 断言消息 UTF-8 并存，"
                "『实际失败断言原文』刻意保留乱码为证据原貌，判定以英文方法名/码为准。\n\n")
        for inj, verdict, proof, expected in report:
            f.write(f"## {inj['id']} · {inj['gap']}\n\n")
            f.write(f"- 目标文件：`{inj['target']}`\n")
            f.write(f"- 注入（old -> new）：`{inj['old'][:120]}` -> `{inj['new'][:120]}`\n")
            f.write(f"- 捕捉者：{'码级门禁' if inj['runner']=='gate' else '真请求 E2E'} "
                    f"`{inj['test']}`\n")
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
    print("\n总体: " + ("PASS —— 每条注入都被抓住，且树已还原全绿" if ok
                        else "FAIL —— 存在未被抓住的注入或还原后不绿"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())