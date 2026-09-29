#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S2-10 · 契约域 B 反向验证（reverse verification）—— 「注入错误必须被抓住」。

本脚本针对 S2-10 这一轮修复的【全部七类缺口】逐一注入回归，并断言：
    注入后 -> 对应的门禁 / 真请求 E2E 必须【由绿变红】
    还原后 -> 必须【回到绿】
任何一条"注入了但没人红"都说明该守护是纸做的，本脚本以非零退出码报出。

为什么不自证
------------
本脚本【不】断言任何由它自己声明的值。它只断言"可观测行为从 PASS 变成 FAIL"，
并把门禁 / 测试自己的原话（failures 段原文）作为证据落盘。

锚点必须 ASCII
--------------
本仓库在中文 Windows 上运行：Maven / JDK 输出为 GBK，而 Python 源文件是 UTF-8。
若把中文（或全角冒号/引号）写进【注入锚点】，会因为编码层差异匹配不到目标文本 ——
那时脚本会报"锚点未命中"，而人会误读成"注入成功但没被抓"。故本文件内
INJECTION 的 old / new 一律纯 ASCII（目标文件里的中文注释不参与锚点）。

另外两条纪律（本轮新立，均由前几轮的真实误判逼出）
----------------------------------------------------
① **锚点必须唯一**：脚本会数 `old` 在源码里出现几次，>1 直接判 ANCHOR-AMBIGUOUS 并置 FAIL。
   原因：`replace(..., 1)` 只改"第一个出现"，而你以为改的是心里那个 ——
   红点会红在错误的方法上，或两条注入撞成同一个红点（S2-7 的 RV-10 就是这么被坑的）。
② **还原必须按字节**：注入前把整个文件以二进制快照下来，还原时原样写回，并在**编译之前**
   做逐字节比对。原因：文本模式 `read()` 会把 CRLF 归一成 LF，`write(newline="")` 又不翻译回来，
   于是"还原"这一步会把一份 CRLF 文件**永久改成 LF** —— 而且**没有任何断言会报错**。

用法
----
    python verification/93_domain_b_reverse_verification.py

退出码 0 = 每条注入都被抓住，且树已逐字节还原为全绿。
"""

from __future__ import annotations

import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SKELETON = os.path.dirname(HERE)          # .../skeleton

# 🛑 在【中文 Windows】上直接用 "mvn" 会 FileNotFoundError：
#    (a) 这里是原生的 Windows Python 进程，不经过 Git Bash，PATH 里的
#        "/c/opt/..." 是 MSYS 风格路径，Windows 的 CreateProcess 认不出；
#    (b) "mvn" 在 Windows 上的可执行体是 "mvn.cmd"（批处理），
#        即使 PATH 对了、不带扩展名也可能找不到。
#    故这里①显式给出 mvn.cmd 的 Windows 绝对路径，②把 JAVA_HOME/ PATH
#    配成 Windows 原生分隔符（';' 而非 ':'）—— 两件事都得做，缺一不可。
MAVEN_CMD = r"C:\opt\apache-maven-3.9.9\bin\mvn.cmd"
JAVA_HOME_WIN = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
PG_BIN_WIN = r"C:\Program Files\PostgreSQL\17\bin"
RUN_BASE = ["-o", "-q", "-DskipTests", "install"]

# 真库门禁所需环境（与本仓库既有 E2E 一致）
PG_ENV = {
    "DY_PG_HOST": "127.0.0.1",
    "DY_PG_PORT": "5432",
    "DY_PG_SUPER_PASSWORD": "postgres",
}


# ======================================================================
# 注入清单
# ======================================================================
# 每条 = 一类缺口的"回归复发"。
#   target   被改的文件（相对 skeleton/）
#   old/new  纯 ASCII 锚点（见文件头"锚点必须 ASCII"）
#   runner   用哪个测试捕捉：gate = 码级门禁；e2e = 真请求端到端
#   must_see 断言"确实红了"时，输出里必须出现的片段（取测试名）
#
# 🔴 特别标注 I2：它证明"码级门禁【看不见】角色级缺口，只能靠真请求 E2E 抓"。
#    这是本会话最重要的机制发现 —— 码级门禁只问"这个码有没有主"，
#    而 customer:write 有主（manager 等），故 I2 注入后码级门禁【仍然全绿】。
#    因此 I2 的 runner 必须用 e2e，且本脚本会【额外】断言
#    "码级门禁在 I2 下依然绿"，把这件事变成可读的事实而不是口头结论。
INJECTIONS = [
    {
        "id": "I1",
        "gap": "缺口A 的最严重形态（码【彻底无主】）：把 B1 的码改成一个注册表里不存在的码",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/controller/CustomerController.java",
        # 🛑 锚点必须【唯一】：@RequirePermission("customer:archive") 在四个端点各出现一次，
        #    单行锚点会让 replace(...,1) 命中"第一个出现的" —— 结论就依赖"B1 恰好排在最前"
        #    这个隐含假设。故锚点取"注解 + 其正下方的方法映射"两行，语义明确且唯一。
        "old": '@RequirePermission("customer:archive")\n    @PostMapping("/screening-records")',
        "new": '@RequirePermission("customer:phantom")\n    @PostMapping("/screening-records")',
        "runner": "gate",
        "test": "PermissionCodeRegistrationGateTest",
        "must_see": "every_require_permission_code_has_at_least_one_holder",
        "expected": "码级门禁第②条必须报出 orphans 非空（customer:phantom 被声明、却无人持有）——"
                    "这证明【门禁本身有效】，能为『码无主』兜底",
    },
    {
        "id": "I1b",
        "gap": "缺口A 的【隐蔽】形态（码仍有主，但【契约点名的角色】丢了它）：单摘 therapist 的 customer:archive",
        "target": "dy-security/src/main/java/com/diaoyuanyun/dy/security/permission/PermissionRegistry.java",
        "old": 'rolePermissions.put("therapist", Set.of("customer:read", "customer:archive", "store:read"));',
        "new": 'rolePermissions.put("therapist", Set.of("customer:read", "store:read"));',
        "runner": "e2e",
        "test": "DomainBEndpointsE2ETest",
        "must_see": "contract_callable_frontline_roles_reach_b1",
        "expected": "真请求 E2E 必须变红（therapist 调 B1 拿到 403）。"
                    "🛑 关键：此时码级门禁【依然全绿】—— 因为 customer:archive 还剩 meridian 等 7 个持有者，"
                    "而门禁只问『有没有【任何】角色持有』。这正是本仓库第七次同型缺口能长期潜伏的机制，"
                    "故本脚本额外断言门禁在 I1b 下绿，把『盲区』变成可读事实而不是口头结论",
        "assert_gate_still_green": True,
    },
    {
        "id": "I2",
        "gap": "缺口A 的另一形态（码有主但角色不匹配）：把 B1 的码改回 customer:write",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/controller/CustomerController.java",
        # 🛑 同 I1：锚点取"注解 + 方法映射"两行，保唯一且语义明确（针对 B1）。
        "old": '@RequirePermission("customer:archive")\n    @PostMapping("/screening-records")',
        "new": '@RequirePermission("customer:write")\n    @PostMapping("/screening-records")',
        "runner": "e2e",
        "test": "DomainBEndpointsE2ETest",
        "must_see": "contract_callable_frontline_roles_reach_b1",
        "expected": "真请求 E2E 必须变红（一线角色调 B1 拿到 403）；而【码级门禁仍全绿】—— 故本脚本额外断言门禁在 I2 下绿",
        "assert_gate_still_green": True,
    },
    {
        "id": "I3",
        "gap": "缺口B 复发：给 E5 端点贴回一个（有主的）@RequirePermission",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/band/controller/BandAvailableDatesController.java",
        "old": '    @PostMapping("/available-dates")',
        "new": '    @com.diaoyuanyun.dy.security.permission.RequirePermission("customer:read")\n'
               '    @PostMapping("/available-dates")',
        "runner": "e2e",
        "test": "DerivedVisibilityE2ETest",
        "must_see": "legitimate_client_probe_without_derived_keys_must_reach_200",
        "expected": "E5 守护用例必须变红：合法 client 探测（无派生键）拿到 403 —— 因注册表刻意不登记 client",
    },
    {
        "id": "I4",
        "gap": "自环门禁复发：B2 的门禁换回 assertAdmissionChain（内含 assertProfiled）",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/service/CustomerService.java",
        "old": 'CustomerGateGuard.assertScreeningResult("B2 POST /customers", hasPassing, current);',
        "new": 'CustomerGateGuard.assertAdmissionChain("B2 POST /customers", hasPassing, current);',
        "runner": "e2e",
        "test": "DomainBEndpointsE2ETest",
        "must_see": "contract_callable_frontline_roles_reach_b2",
        "expected": "真请求 E2E 必须变红：B2 恒 403 GATE_MISSING(PROFILED) —— 守卫挂在了它自己的产出上",
    },
    {
        "id": "I5",
        "gap": "SQL 类型缺陷复发：UPDATE_PROFILE_SQL 的 owner_store_id 去掉 ::uuid",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/repository/CustomerLedger.java",
        "old": '+ "  owner_store_id = ?::uuid, serving_store_id = ?::uuid,"',
        "new": '+ "  owner_store_id = ?, serving_store_id = ?::uuid,"',
        "runner": "e2e",
        "test": "DomainBEndpointsE2ETest",
        "must_see": "contract_callable_frontline_roles_reach_b2",
        "expected": "真请求 E2E 必须变红：B2 恒 500 BadSqlGrammarException（uuid 列收到 varchar 参数）",
    },
    {
        "id": "I6",
        "gap": "NPE 缺陷复发：修订快照换回 Map.copyOf（拒收 null 值）",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/domain/IntakeProfileRevisionRow.java",
        "old": 'snapshot = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(snapshot));',
        "new": 'snapshot = Map.copyOf(snapshot);',
        "runner": "e2e",
        "test": "DomainBEndpointsE2ETest",
        "must_see": "b6_first_write_creates_profile_with_revision_one",
        "expected": "真请求 E2E 必须变红：B6 首次写入恒 500 NullPointerException（快照含 null 值）",
    },
]


# ======================================================================
# 基础设施
# ======================================================================

def read_bytes(path: str) -> bytes:
    """🛑 一律以【二进制】读，用它做"还原"与"逐字节校验"的唯一真相源。

    为什么不能用文本模式：`open(..., 'r')` 默认 `newline=None`，会把 CRLF 归一成 LF。
    配合 `write(newline="")`（不翻译）写回，就会把一份 CRLF 文件【永久改成 LF】——
    而 git 视角这是"整个文件都改了"，且**没有任何断言会报错**（编译照样过）。
    本仓库上一轮（S2-7/S2-9）踩过同一个坑，故此处直接以字节为准，从根上消除该可能。
    """
    with open(path, "rb") as f:
        return f.read()


def read_text(path: str) -> str:
    """编译/匹配用的文本视图（CRLF 归一）。只用于"找锚点/做替换"，不用于还原。"""
    return read_bytes(path).decode("utf-8").replace("\r\n", "\n")


def write_text(path: str, content: str) -> None:
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(content)


def restore_bytes(path: str, raw: bytes) -> None:
    """把文件还原成"注入前的原始字节"。这是唯一正确的还原方式。"""
    with open(path, "wb") as f:
        f.write(raw)


def mvn(args: list[str], extra_env: dict | None = None):
    env = dict(os.environ)
    # 🛑 用 Windows 原生 ';' 分隔（不是 MSYS 的 ':'）—— 本进程是原生 Windows
    #    Python，走 CreateProcess，':' 分隔的 PATH 会被整体当成一个非法路径。
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
    """抽出 maven 自己打印的 failures/errors 段（作为"门禁的原话"证据）。"""
    lines = output.splitlines()
    out = []
    in_block = False
    for ln in lines:
        if re.match(r"^\[ERROR\] (Failures|Errors):", ln):
            in_block = True
        if in_block:
            out.append(ln)
    return "\n".join(out) if out else "(no [ERROR] Failures/Errors block found)"


def main() -> int:
    report = []
    ok = True

    # 基线：先证明【未注入时全绿】。否则"注入后变红"可能只是因为本来就红。
    print("== 基线（注入前必须全绿）==", flush=True)
    bc, bo = build()
    if bc != 0:
        print(bo[-3000:])
        print("🛑 基线 install 失败 —— 反向验证无意义，先修构建")
        return 2
    for cls, pg in (("PermissionCodeRegistrationGateTest", False),
                    ("DomainBEndpointsE2ETest", True),
                    ("DerivedVisibilityE2ETest", True)):
        rc, ro = run_test(cls, pg)
        status = "PASS" if rc == 0 else "FAIL"
        print(f"  baseline {cls}: {status}", flush=True)
        if rc != 0:
            print(extract_failure_block(ro)[-2500:])
            print(f"🛑 基线 {cls} 不是绿的 —— 反向验证无意义")
            return 2

    # 逐条注入
    touched: dict[str, bytes] = {}   # path -> 注入前的原始字节（供末尾逐字节还原校验）
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
            report.append((inj, "ANCHOR-MISS", "", "锚点未命中 —— 目标文件已改，请同步锚点"))
            continue
        if hits > 1:
            # 🛑 锚点必须在源码里【唯一】。否则 replace(..., 1) 改的是"第一个出现的"
            #    那个，而你以为改的是你心里那个 —— 红点会出现在错误的方法上，
            #    或与另一条注入撞成同一个红点（S2-7 的 RV-10 正是这么被坑的）。
            print(f"🛑 锚点不唯一（出现 {hits} 次）—— 注入位置不确定，结论不可采信：")
            print("   anchor: " + inj["old"][:160])
            ok = False
            report.append((inj, "ANCHOR-AMBIGUOUS", "",
                           f"锚点在源码中出现 {hits} 次 —— 必须唯一，请加足上下文"))
            continue

        injected = original.replace(inj["old"], inj["new"], 1)
        write_text(path, injected)

        try:
            bc, bo = build()
            if bc != 0:
                # 注入导致编译失败也算"被抓"（但形态不同，单列记录）
                print("  注入后编译失败（也算被抓住，但形态是编译而非断言）", flush=True)
                report.append((inj, "COMPILE-FAIL", extract_failure_block(bo),
                               "注入导致编译失败（而非断言失败）"))
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
                               "注入了错误但测试仍绿（或红在了别的用例）"))
            else:
                report.append((inj, "CAUGHT", extract_failure_block(ro),
                               inj["expected"]))

            # I2 额外断言：码级门禁在"角色不匹配"注入下【依然绿】
            if inj.get("assert_gate_still_green"):
                grc, gro = run_test("PermissionCodeRegistrationGateTest", need_pg=False)
                if grc != 0:
                    ok = False
                    print("  ⚠️ I2 附加断言失败：码级门禁竟然变红了 —— "
                          "这说明它【看得见】角色不匹配，与本仓库此前的结论不符，需重新评估")
                    report.append((inj, "GATE-NOT-BLIND", extract_failure_block(gro),
                                   "码级门禁在角色不匹配下本应仍绿，实测却红了"))
                else:
                    print(f"  ✔ 附加断言成立：码级门禁在 {inj['id']} 下【依然全绿】—— "
                          "证明它看不见'码有主但角色不匹配'这类缺口", flush=True)
        finally:
            # 无论成败都必须还原（按【原始字节】还原，见 read_bytes 的说明）
            restore_bytes(path, touched[path])
            print("  已还原: " + inj["target"], flush=True)

    # 末尾①：逐字节还原校验（必须在**编译/测试之前**做，否则"改坏了"会被后面的绿掩盖）
    print("\n== 逐字节还原校验 ==", flush=True)
    for path, raw in touched.items():
        now = read_bytes(path)
        rel = os.path.relpath(path, SKELETON).replace(os.sep, "/")
        if now == raw:
            crlf = raw.count(b"\r\n")
            print(f"  ✔ 逐字节一致: {rel}  ({len(raw)} 字节, {'CRLF' if crlf else 'LF'}"
                  f"{'/' + str(crlf) if crlf else ''})", flush=True)
        else:
            ok = False
            print(f"  🛑 还原后与原始字节不一致: {rel}  {len(raw)} -> {len(now)}", flush=True)

    # 末尾②：还原后必须全绿
    print("\n== 还原后基线（必须回到全绿）==", flush=True)
    bc, bo = build()
    if bc != 0:
        print(bo[-3000:]); ok = False
    else:
        for cls, pg in (("PermissionCodeRegistrationGateTest", False),
                        ("DomainBEndpointsE2ETest", True),
                        ("DerivedVisibilityE2ETest", True)):
            rc, ro = run_test(cls, pg)
            print(f"  final {cls}: {'PASS' if rc == 0 else 'FAIL'}", flush=True)
            if rc != 0:
                ok = False
                print(extract_failure_block(ro)[-2500:])

    # 落盘证据
    out_path = os.path.join(HERE, "94_domain_b_reverse_verification.md")
    with open(out_path, "w", encoding="utf-8", newline="") as f:
        f.write("# S2-10 · 契约域 B 反向验证证据\n\n")
        f.write("三列证据：注入内容 | 预期失败点 | 实际失败断言原文\n\n")
        f.write("> 🛑 **阅读须知（编码）**：下文的「实际失败断言原文」是从 Maven 输出**原样落盘**的字节。\n")
        f.write("> 中文 Windows 上 Maven 前缀行（`[ERROR]` / `Tests run:`）是 **GBK**，而 surefire fork 进程"
                "打印的断言消息是 **UTF-8** ——\n")
        f.write("> **同一份输出里两种编码并存，不存在单一正确解码策略**（S2-9 留下的失效模式 **13-b**）。"
                "因此原文块里会出现乱码，\n")
        f.write("> 这是**刻意保留的证据原貌**（证明\"看的就是构建真正吐出的东西\"），**不是文档损坏**。\n")
        f.write("> ⚠️ **不要\"顺手修一下乱码\"** —— 修了就失去\"逐字原样\"的证据价值。\n")
        f.write("> 判定一律以**英文可读的部分**为准：测试方法名、`expected: <true> but was: <false>`、"
                "权限码、枚举字面\n")
        f.write("> （**反向验证的预期锚点一律只用 ASCII**，原因即此）。\n")
        f.write("> 🛑 **还原校验**：脚本退出前对**全部被触碰的源文件**做**逐字节**还原校验"
                "（以二进制读写，防文本模式把行尾翻译掉）。\n\n")
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