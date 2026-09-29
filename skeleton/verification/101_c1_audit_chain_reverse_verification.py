#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
C-1 · 审计链校验端点（GET /audit/log-chain）的反向验证 —— 「注入错误必须被抓住」。

针对 C-1 落地后新增的四道防线，逐条注入缺陷并断言门禁变红：

  K1  计数防线被掏空：控制器把 checked 恒写 0
      ⇒ 「穿透保真」断言必须变红 —— 一个把表读成 0 行却报 valid=true 的校验器
        是灾难性假通过；checked 这个计数器存在的唯一目的就是让那种情况现形。
  K2  权限防线被撤：摘掉 @RequirePermission("audit:read")
      ⇒ 「hq only」断言必须变红 —— area / manager / therapist / meridian 会随即可调，
        于是"用一个本店 scope 的身份去读全局单链"的语义裂缝被开出来。
  K3  能力边界被削：从 capability_envelope 里删掉「链尾截断」那一条
      ⇒ 「四条齐全」断言必须变红 —— 尾部截断是本轮真请求实测新挖出的盲区
        （擦掉最近的操作痕迹恰是攻击者的首选动作），它不得静默消失。
  K4  权限登记防线被绕：给 area 补上 audit:read（改动 dy-security 的登记表）
      ⇒ 「hq only」断言必须变红 —— 证明登记表本身是承重的，而非摆设：
        即便控制器一行不改，只要登记表多一个持有者，端点就多一个可调角色。

锚点 ASCII / 唯一 / 逐字节还原 —— 同 93/95/96/97/98/99_*.py 纪律。

🛑 与 99_*.py 的关系：99 是 B-2（手环幂等）的注入，与 C-1 无关；本文件是 C-1 的补课。
    C-1 的控制器与 12 例 E2E 早已落地，但**从未有过脚本化反向验证证据** ——
    本文件把它补上，使"这四道防线有牙齿"从判断变成可复现的事实。

🛑 编码须知（一次真实假阴性的教训，见 99_*.py 文件头）：
    must_see 锚点一律用 **ASCII 全限定方法名**。Maven/surefire 在 Windows 上
    输出 GBK 字节流，主流程按 UTF-8 解码后中文全乱码 ⇒ 任何依赖中文的判定恒为 False。
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

# 🛑 兜底还原登记表：{绝对路径: 原始字节}（同 99_*.py 的事故教训）
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

PG_ENV = {
    "DY_PG_HOST": "127.0.0.1",
    "DY_PG_PORT": "5432",
    "DY_PG_SUPER_PASSWORD": "postgres",
}

GATE = "AuditChainEndpointE2ETest"
CTRL = "dy-app/src/main/java/com/diaoyuanyun/dy/app/audit/controller/AuditChainController.java"
REG = "dy-security/src/main/java/com/diaoyuanyun/dy/security/permission/PermissionRegistry.java"

INJECTIONS = [
    {
        "id": "K1",
        "gap": "计数防线被掏空：控制器把 checked 恒写 0（= 把表读成 0 行却报 valid 的假通过形态）",
        "target": CTRL,
        # 逐字锚点：verifyLogChain 里唯一那一行（注解与自描述里的 "checked" 不含此串）
        "old": '        data.put("checked", v.checked());',
        "new": '        data.put("checked", 0);',
        "test": GATE + "$Callability",
        "must_see": "AuditChainEndpointE2ETest$Callability"
                    ".hq_reaches_the_endpoint_with_exact_passthrough_fidelity",
        "expected": "穿透保真断言必须变红：控制器把 checked 写死为 0，与 verifyChain() 的权威值不符。"
                    "checked 是『记录条数』而非『通过条数』，它的唯一用途就是让"
                    "『链有效』与『链是空的』无法被混淆。",
    },
    {
        "id": "K2",
        "gap": "权限防线被撤：摘掉 @RequirePermission(\"audit:read\")",
        "target": CTRL,
        # 🛑 锚点必须唯一：@RequirePermission("audit:read") 在本文件出现【2 次】
        #    （verifyLogChain 与 describeContract 各一），故带上紧随其后的路由注解以钉死目标。
        "old": '    @RequirePermission("audit:read")\n'
               '    @GetMapping("/audit/log-chain")',
        "new": '    @GetMapping("/audit/log-chain")',
        "test": GATE + "$Callability",
        "must_see": "AuditChainEndpointE2ETest$Callability"
                    ".non_hq_roles_are_denied_by_the_permission_layer",
        "expected": "hq only 断言必须变红：失去 audit:read 这一层后，area / manager / "
                    "therapist / meridian 都仍是 staff，会被 @StaffOnly 放行 ⇒ 端点变成 staff 全可调。"
                    "而 audit_log 是跨租户串联的全局单链，契约 F3 的 x-row-scope 预设了"
                    "『对象属于某租户』⇒ 那会开出一条语义裂缝。",
    },
    {
        "id": "K3",
        "gap": "能力边界被削：从 capability_envelope 里删掉「链尾截断」那一条",
        "target": CTRL,
        # 🛑 连带锚住下一条的开头，避免删完留下悬空逗号（"外锚定"全文件仅 1 次）
        "old": '                "不能证明②：链尾未被截断 —— 删除【最后若干条】记录不留任何后继可验，"\n'
               '                        + "本端点同样返回 valid=true。唯一可观测迹象是 checked 条数，"\n'
               '                        + "而 checked 只能说明『现在有多少条』、不能说明『应该有多少条』",\n'
               '                "外锚定（每条 HMAC',
        "new": '                "外锚定（每条 HMAC',
        "test": GATE + "$Envelope",
        "must_see": "AuditChainEndpointE2ETest$Envelope.capability_envelope_covers_all_four_items",
        "expected": "四条齐全断言必须变红：边界被摊平为三条。尾部截断是 C-1 真请求实测"
                    "新挖出的盲区（既有破坏性门禁只覆盖『删中间行』），"
                    "而擦掉最近的操作痕迹恰是攻击者的首选动作 —— 删掉这句话等于对读端点的人隐瞒它。",
    },
    {
        "id": "K4",
        "gap": "权限登记防线被绕：给 area 补上 audit:read（只改 dy-security 的登记表，控制器一行不动）",
        "target": REG,
        # 🛑 "doc:write")); 在 manager / area 两处各出现一次 ⇒ 必须锚住整个 area 块以唯一化
        "old": '        rolePermissions.put("area", Set.of("customer:read", "customer:write", "customer:archive",\n'
               '                "report:region", "store:read",\n'
               '                "refund:read", "refund:write", "refund:approve",\n'
               '                "verdict:read", "verdict:write",\n'
               '                "assessment:write", "fulfillment:write",\n'
               '                "doc:write"));',
        "new": '        rolePermissions.put("area", Set.of("customer:read", "customer:write", "customer:archive",\n'
               '                "report:region", "store:read",\n'
               '                "refund:read", "refund:write", "refund:approve",\n'
               '                "verdict:read", "verdict:write",\n'
               '                "assessment:write", "fulfillment:write",\n'
               '                "doc:write", "audit:read"));',
        "test": GATE + "$Callability",
        "must_see": "AuditChainEndpointE2ETest$Callability"
                    ".non_hq_roles_are_denied_by_the_permission_layer",
        "expected": "hq only 断言必须变红：控制器一字未改，但登记表多一个持有者 ⇒ "
                    "area 立刻可调。本条证明『可调角色』这件事的真正真相源是登记表，"
                    "任何只看控制器的评审都会漏掉这条路径。",
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
    args = ["-o", "-pl", "dy-app", "test",
            "-Dtest=" + test_class, "-DfailIfNoTests=false"]
    return mvn(args, PG_ENV)


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

    # 🛑 前置：登记兜底表并预检锚点 —— 让"锚点失效"在基线跑测试之前就暴露
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
        print("🛑 基线不绿。⚠️ 先确认工作区没有被上一次中断的运行留下注入残留"
              "（见本文件 _SNAPSHOT 的说明）。")
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

    out_path = os.path.join(HERE, "101_c1_audit_chain_reverse_verification.md")
    with open(out_path, "w", encoding="utf-8", newline="") as f:
        f.write("# C-1 · 审计链校验端点反向验证证据\n\n")
        f.write("四列证据：注入内容 | 预期失败点 | 实际失败断言原文 | 判定\n\n")
        f.write("> 🛑 编码须知同 94/98/99_*.md：乱码为证据原貌，判定以英文方法名/码为准。\n\n")
        for inj, verdict, proof, expected in report:
            f.write(f"## {inj['id']} · {inj['gap']}\n\n")
            f.write(f"- 目标文件：`{inj['target']}`\n")
            f.write(f"- 注入：`{inj['old'][:200]}` -> `{inj['new'][:200]}`\n")
            f.write(f"- 捕捉者：真请求 E2E `{inj['test']}`\n")
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