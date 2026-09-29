#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
B-3 · 配置真相源落库（V14）的反向验证 —— 「注入错误必须被抓住」。

B-3 落地了三样东西，本脚本对每一样都注入一个「让防线失效」的错误：

  K1  同步防线失效（真源 ↔ V14 内联段分叉）
      只改 V14 内联段的一行列宽（不改 01）⇒ ConfigTruthSourceMigrationSyncTest 的
      段 A 逐字比较必须变红。
      🛑 这条是"改一处不改另一处"的最小真实形态：门禁库/口径来源读真源文件，
         应用库执行 V14 副本 ⇒ 分叉会**不报错**，只让三方口径不一致。

  K2  编号域上界被放宽（#48 之后还能继续加号，编号位移将静默错位）
      V14 与 01 【同步】把 `<= 48` 改成 `<= 49`（保持两侧一致 ⇒ 同步门禁仍绿）
      ⇒ 只有真库门禁的域断言能抓住它。
      🛑 刻意做成"两侧同步改"：这样才能证明真库门禁（而不是同步门禁）在守着域上界。

  K3  #42 空号裁定被摘掉（一条 INSERT 就能占用 #42，历史编号引用静默错位）
      V14 与 01 【同步】删掉 `config_slot_no_42_stays_vacant` 约束行
      ⇒ 真库门禁必须变红（它断言该约束名在 pg_constraint 里存在）。

  K4  幂等偏离退化成静默丢值（改了 initial_value 却不生效）
      V14 的 `ON CONFLICT (config_no) DO UPDATE SET …` 改成 `DO NOTHING`
      ⇒ 同步门禁的段 B 比较 + "不得 DO NOTHING" 断言必须变红。

  K5  历史表 append-only 从「响亮失败」退化成另一种错误码（或退化成本可绕过）
      V14 的 `USING ERRCODE = '42501';` 改成 `'23514'`
      ⇒ 真库门禁的 SQLSTATE 断言必须变红（42501 是"权限不足"这一语义的载体）。

  K6  租户表覆盖登记被摘掉（登记表变一纸空文）
      删掉 RlsCoverageGateTest 里 `Map.entry("app_config", V14),`
      ⇒ 覆盖门禁必须变红（三方交叉 / 僵尸登记）。

锚点 ASCII / 唯一 / 逐字节还原 —— 同 93/95/96/97/98/99_*.py 纪律。

🛑 must_see 必须是 **ASCII**（见 decode 说明）：
   初版 99_*.py 在这里踩过——must_see 写中文断言原文，而 Maven/surefire 在 Windows
   上把输出编成 GBK 字节流；主流程按 UTF-8 解码后中文全是乱码 ⇒ `must_see in ro`
   恒为 False ⇒ 注入**其实被抓到了**却被判"未被抓住"（假阴性）。
   故本文件的 must_see 一律用 ASCII 的全限定方法名或约束名。
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

# 🛑 兜底还原登记表：{绝对路径: 原始字节}
# 为什么需要它（99_*.py 的真实事故）：只在注入点的 try/finally 里还原，
# 但本脚本要跑多轮 install+test（数分钟）。若进程被 SIGTERM/超时中断，
# finally 不会执行 —— 于是"注入态"被留在工作区，下一次运行会在【基线】阶段发现
# 文件不干净（基线红），而症状看起来像"测试自己坏了"，与真因（残留注入）隔了一层。
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

P_V14 = "dy-app/src/main/resources/db/migration/V14__config_truth_source_tables.sql"
P_01 = "dy-config/src/main/resources/db/config/01_truth_source_ddl.sql"
P_COVER = "dy-app/src/test/java/com/diaoyuanyun/dy/app/rls/RlsCoverageGateTest.java"

T_SYNC = "ConfigTruthSourceMigrationSyncTest"
T_V14 = "RlsV14ConfigTruthSourceIsolationTest"
T_COVER = "RlsCoverageGateTest"

INJECTIONS = [
    {
        "id": "K1",
        "gap": "同步防线失效：只改 V14 内联段一行（列宽 64→63），不改真源 01",
        "targets": [{
            "path": P_V14,
            "old": "    config_key       VARCHAR(64) NOT NULL,",
            "new": "    config_key       VARCHAR(63) NOT NULL,",
        }],
        "test": T_SYNC,
        "must_see": "ConfigTruthSourceMigrationSyncTest.section_a_ddl_is_byte_identical",
        "expected": "同步门禁必须变红：段 A（DDL）逐行比较会在这一行上报不一致。"
                    "这正是「改一处不改另一处」的最小真实形态 —— 应用库执行 V14、"
                    "门禁库与口径来源读真源文件，分叉会让三方口径静默不一致。",
    },
    {
        "id": "K2",
        "gap": "编号域上界被放宽：V14 与 01 同步把 <= 48 改成 <= 49",
        "targets": [
            {"path": P_V14,
             "old": "    CONSTRAINT config_slot_no_in_domain CHECK (config_no >= 1 AND config_no <= 48),",
             "new": "    CONSTRAINT config_slot_no_in_domain CHECK (config_no >= 1 AND config_no <= 49),"},
            {"path": P_01,
             "old": "    CONSTRAINT config_slot_no_in_domain CHECK (config_no >= 1 AND config_no <= 48),",
             "new": "    CONSTRAINT config_slot_no_in_domain CHECK (config_no >= 1 AND config_no <= 49),"},
        ],
        "test": T_V14,
        # 🛑 锚点必须是 ASCII 的【全限定方法名】，不能写约束名 config_slot_no_in_domain：
        # 实测该断言位于 config_slot_declares_46_rows_and_keeps_the_two_vacant_numbers_closed
        # 内（第 458 行）的 assertTrue(domain.contains("48"), …)，而 PG 已把 CHECK 定义
        # 规范化成 `CHECK (((config_no >= 1) AND (config_no <= 49)))` —— 失败消息里
        # 既不含约束名、也不含 ASCII 的 config_slot_no_in_domain ⇒ 首版判定出现
        # 「其实红了却报未被抓住」的假阴性（同 99_*.py 的 GBK 教训）。
        "must_see": "RlsV14ConfigTruthSourceIsolationTest"
                    ".config_slot_declares_46_rows_and_keeps_the_two_vacant_numbers_closed",
        "expected": "真库门禁必须变红：它断言编号域上界恰为 48（#48 已落 PRD 的最大号）。"
                    "放宽上界会让「编号漂移到域外」重新变成可写 —— 而编号位移是静默错位，"
                    "所有既有编号引用会一起失真。",
    },
    {
        "id": "K3",
        "gap": "#42 空号裁定被摘掉：V14 与 01 同步删掉 no_42_stays_vacant 约束行",
        # 🛑 本组必须【把前一行尾逗号一并摘掉】，不能只删约束行（K3 首版的真事故）：
        #    no_42_stays_vacant 是 config_slot 建表语句里的【最后一项】，前一行
        #    no_in_domain 带着尾逗号。只删最后一行 ⇒ 尾逗号悬空 ⇒ V14 在 psql 里
        #    报 `syntax error at or near ")"`（实测 V14:120 / LINE 36: );）。
        #    那样测试虽然也 RED，但"被抓住"的是【SQL 语法】而非【#42 断言】——
        #    证明力归零：门禁可能根本没执行到断言就红了。
        #    故注入后 DDL 必须仍然合法可执行，让失败唯一来自
        #    pg_constraint 里缺 config_slot_no_42_stays_vacant。
        "targets": [
            {"path": P_V14,
             "old": "    CONSTRAINT config_slot_no_in_domain CHECK (config_no >= 1 AND config_no <= 48),\n",
             "new": "    CONSTRAINT config_slot_no_in_domain CHECK (config_no >= 1 AND config_no <= 48)\n"},
            {"path": P_V14,
             "old": "    CONSTRAINT config_slot_no_42_stays_vacant CHECK (config_no <> 42)\n",
             "new": ""},
            {"path": P_01,
             "old": "    CONSTRAINT config_slot_no_in_domain CHECK (config_no >= 1 AND config_no <= 48),\n",
             "new": "    CONSTRAINT config_slot_no_in_domain CHECK (config_no >= 1 AND config_no <= 48)\n"},
            {"path": P_01,
             "old": "    CONSTRAINT config_slot_no_42_stays_vacant CHECK (config_no <> 42)\n",
             "new": ""},
        ],
        "test": T_V14,
        # 🛑 #42 有【两道防线】，任一道红都证明该裁定被守着，故用多锚点：
        #    ① V14 文件自身的 `$v14_guard$` 自证守卫（第 748 行附近）会先一步
        #       RAISE EXCEPTION `V14 自证失败(e): config_slot 的约束缺失 ->
        #       config_slot_no_42_stays_vacant`，此时 schema 未建成、Java 断言跑不到；
        #    ② 真库门禁的 pg_constraint 断言（第 447 行）是第二道。
        #    首版只认 ①、第二版只认 ②，两次都误报「未被抓住」——实为同一裁定的
        #    两道防线谁先响应而已。锚点全部取 ASCII（守卫消息里的约束名是 ASCII，
        #    不受 GBK 乱码影响；方法名同理）。
        "must_see": [
            "config_slot_no_42_stays_vacant",
            "RlsV14ConfigTruthSourceIsolationTest"
            ".config_slot_declares_46_rows_and_keeps_the_two_vacant_numbers_closed",
        ],
        "expected": "真库门禁必须变红：它断言 pg_constraint 里存在 config_slot_no_42_stays_vacant。"
                    "缺这条约束，一条 INSERT 就能占用 #42，而 2026-09-18 新增的"
                    "「手环数据可见性矩阵」之所以编为 #43（而非 #42）正是因为 #42 被预留。"
                    "⚠️ 本组已把前一行尾逗号一并摘掉，故失败【只可能】来自该断言；"
                    "若证据里出现 `syntax error` / `LINE 36` 字样，说明注入又坏在语法上、证明力不足。",
    },
    {
        "id": "K4",
        "gap": "幂等偏离退化成静默丢值：DO UPDATE SET 7 列 → DO NOTHING",
        "targets": [{
            "path": P_V14,
            "old": "ON CONFLICT (config_no) DO UPDATE\n"
                   "   SET config_key       = EXCLUDED.config_key,\n"
                   "       value_type       = EXCLUDED.value_type,\n"
                   "       allowed_values   = EXCLUDED.allowed_values,\n"
                   "       forbidden_values = EXCLUDED.forbidden_values,\n"
                   "       initial_value    = EXCLUDED.initial_value,\n"
                   "       prd_item_name    = EXCLUDED.prd_item_name,\n"
                   "       description      = EXCLUDED.description;",
            "new": "ON CONFLICT (config_no) DO NOTHING;",
        }],
        "test": T_SYNC,
        "must_see": "ConfigTruthSourceMigrationSyncTest.section_b_declaration_matches_except_the_one_registered_divergence",
        "expected": "同步门禁必须变红：段 B 比较会发现 V14 与登记规则重建的结果不一致，"
                    "且「不得 DO NOTHING」断言同时报警。DO NOTHING 正是 02 原注释"
                    "逐字想避免的效果 ——「改了 initial_value 却不会生效，迁移看着成功、值没变」。",
    },
    {
        "id": "K5",
        "gap": "append-only 的语义载体被换掉：ERRCODE 42501 → 23514",
        "targets": [{
            "path": P_V14,
            "old": "        USING ERRCODE = '42501';",
            "new": "        USING ERRCODE = '23514';",
        }],
        "test": T_V14,
        # 🛑 统一为全限定方法名（surefire 失败行是 `类名.方法名:行号`，含类名更不易误判）。
        "must_see": "RlsV14ConfigTruthSourceIsolationTest"
                    ".history_is_append_only_and_written_by_the_trigger",
        "expected": "真库门禁必须变红：它断言历史行的 UPDATE/DELETE 以 42501"
                    "（insufficient_privilege ——「不可改不可删」这一语义的载体）被拒。"
                    "换成 23514 后，客户端会把「审计记录不可篡改」误读成「取值非法」，"
                    "而错误码是运维与契约的唯一判据。",
    },
    {
        "id": "K6",
        "gap": "覆盖登记被摘掉：删掉 RlsCoverageGateTest 里的 app_config 登记",
        "targets": [{
            "path": P_COVER,
            "old": '            Map.entry("app_config", V14),\n',
            "new": "",
        }],
        "test": T_COVER,
        "must_see": "RlsCoverageGateTest.every_tenant_table_has_a_registered_isolation_test",
        "expected": "覆盖门禁必须变红：迁移里有 app_config 却没有登记 ⇒ "
                    "「无隔离测试即构建失败」这条 ADR-02 L3 裁定被守住。"
                    "缺它则配置表可以在没有任何隔离断言的情况下上线。",
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


def build():
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
        out = [ln for ln in lines if "AssertionFailedError" in ln or "Tests run" in ln]
    return "\n".join(out)


def main() -> int:
    report = []
    ok = True

    # ---- 前置：登记兜底表 + 锚点预检（再花时间跑 install 之前就把"锚点失效"暴露出来）----
    for inj in INJECTIONS:
        for t in inj["targets"]:
            path = os.path.join(SKELETON, t["path"])
            if path not in _SNAPSHOT:
                _SNAPSHOT[path] = read_bytes(path)
            hits = read_text(path).count(t["old"])
            if hits != 1:
                print(f"🛑 {inj['id']} 锚点在 {t['path']} 命中 {hits} 次（要求恰 1 次）: "
                      f"{t['old'][:140]!r}")
                ok = False
    if not ok:
        print("🛑 锚点预检未通过，终止（未改动任何文件）")
        return 2
    print(f"锚点预检通过：{len(INJECTIONS)} 组注入，{len(_SNAPSHOT)} 个目标文件", flush=True)

    print("== 基线 ==", flush=True)
    bc, bo = build()
    if bc != 0:
        print(bo[-3000:]); print("🛑 基线 install 失败"); return 2
    for cls in (T_SYNC, T_V14):
        rc, ro = run_test(cls)
        print(f"  baseline {cls}: {'PASS' if rc == 0 else 'FAIL'}", flush=True)
        if rc != 0:
            print(extract_failure_block(ro)[-2500:])
            print("🛑 基线不绿。⚠️ 先确认工作区没有被上一次中断的运行留下注入残留"
                  "（见本文件 _SNAPSHOT 的说明）。")
            return 2

    for inj in INJECTIONS:
        print(f"\n== {inj['id']} · {inj['gap']} ==", flush=True)

        # 注入全部目标（多文件 / 同文件多处注入）。
        # 🛑 逐处必须【在上一处的结果上继续改】，不能每处都从 pristine 重读：
        #    同一文件出现多个注入点时（如 K3 对 V14 的两处），后一处会把前一处覆盖掉，
        #    于是「两处同删」退化成「只删了后一处」⇒ 前一行尾逗号悬空 ⇒ SQL 语法错。
        #    首版即因此把 K3 的证明力归零（见 K3 的 targets 注释）。
        originals: list[tuple[str, bytes]] = []
        buffers: dict[str, str] = {}
        possible = True
        for t in inj["targets"]:
            path = os.path.join(SKELETON, t["path"])
            if path not in buffers:
                buffers[path] = _SNAPSHOT[path].decode("utf-8").replace("\r\n", "\n")
                originals.append((path, _SNAPSHOT[path]))
            current = buffers[path]
            if current.count(t["old"]) != 1:
                possible = False
                break
            buffers[path] = current.replace(t["old"], t["new"], 1)
        if possible:
            # 守卫：注入自身不得制造「悬空逗号」（`,<换行>)`）。出现即说明这组注入的
            # 失败会来自 SQL 语法而非目标防线 ⇒ 证明力归零，必须当失败处理。
            for path, text in buffers.items():
                if re.search(r",[ \t]*\n[ \t]*\)", text):
                    possible = False
                    print(f"  🛑 注入在 {os.path.relpath(path, SKELETON)} 制造了悬空逗号"
                          f"（注入自身的语法事故，证明力归零）")
        if possible:
            for path, text in buffers.items():
                write_text(path, text)
        if not possible:
            print("  🛑 锚点二次校验失败")
            ok = False
            report.append((inj, "ANCHOR-MISS", "", "锚点未命中"))
            continue

        try:
            bc, bo = build()
            if bc != 0:
                print("  注入后编译/打包失败（也算被抓住）", flush=True)
                report.append((inj, "BUILD-FAIL", extract_failure_block(bo), "构建失败"))
                continue
            rc, ro = run_test(inj["test"])
            needles = ([inj["must_see"]] if isinstance(inj["must_see"], str)
                       else list(inj["must_see"]))
            hit = [n for n in needles if n in ro]
            caught = (rc != 0) and bool(hit)
            print(f"  {inj['id']} 注入后 {inj['test']}: "
                  f"{'RED(被抓)' if rc != 0 else 'GREEN(未被抓!)'}", flush=True)
            # 🛑 证明力检查：证据里若出现 SQL 语法错 / provision 失败，说明门禁是
            #    在【执行断言之前】因 schema 建不起来而红的 —— 那证明的不是目标防线，
            #    必须判失败（K3 首版即栽在这里：删掉最后一项约束却留下悬空逗号，
            #    测试 RED 了，但红在 `syntax error at or near ")"` 上）。
            syntax_smell = [
                m for m in ("syntax error", "LINE 36") if m in ro
            ]
            if caught and syntax_smell:
                ok = False
                print("  🛑 虽红但证明力不足：证据里出现 " + " / ".join(syntax_smell)
                      + "（疑似 schema 未建成即失败，不是目标断言红的）")
                report.append((inj, "WEAK-PROOF", extract_failure_block(ro),
                               inj["expected"] + " ／ ⚠️ 证据含语法/schema 字样，证明力不足"))
            elif not caught:
                ok = False
                print("  🛑 未被抓住: " + " | ".join(needles))
                print(extract_failure_block(ro)[-2000:])
                report.append((inj, "NOT-CAUGHT", extract_failure_block(ro), inj["expected"]))
            else:
                report.append((inj, "CAUGHT", extract_failure_block(ro), inj["expected"]))
        finally:
            for path, raw in originals:
                restore_bytes(path, raw)
            print("  已还原: " + ", ".join(os.path.relpath(p, SKELETON).replace(os.sep, "/")
                                          for p, _ in originals), flush=True)

    print("\n== 逐字节还原校验 ==", flush=True)
    for path, raw in _SNAPSHOT.items():
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
        for cls in (T_SYNC, T_V14):
            rc, ro = run_test(cls)
            print(f"  final {cls}: {'PASS' if rc == 0 else 'FAIL'}", flush=True)
            if rc != 0:
                ok = False

    out_path = os.path.join(HERE, "100_config_truth_source_reverse_verification.md")
    with open(out_path, "w", encoding="utf-8", newline="") as f:
        f.write("# B-3 · 配置真相源落库（V14）反向验证证据\n\n")
        f.write("三列证据：注入内容 | 预期失败点 | 实际失败断言原文\n\n")
        f.write("> 🛑 编码须知同 94/98/99_*.md：乱码为证据原貌，判定以 ASCII 方法名/码为准。\n\n")
        for inj, verdict, proof, expected in report:
            f.write(f"## {inj['id']} · {inj['gap']}\n\n")
            f.write("- 目标文件：")
            f.write("、".join(f"`{t['path']}`" for t in inj["targets"]))
            f.write("\n")
            f.write(f"- 注入（首处）：`{inj['targets'][0]['old'][:200]}` -> "
                    f"`{inj['targets'][0]['new'][:200]}`\n")
            f.write(f"- 捕捉者：`{inj['test']}`\n")
            f.write(f"- 捕捉锚点：{needles}\n")
            f.write(f"- 预期失败点：{expected}\n")
            f.write(f"- 判定：**{verdict}**\n")
            f.write("- 实际失败断言原文：\n\n```\n")
            f.write((proof or "(empty)")[:4000])
            f.write("\n```\n\n")
        f.write("\n被触碰源文件（逐字节还原校验）：\n\n")
        for path in _SNAPSHOT:
            rel = os.path.relpath(path, SKELETON).replace(os.sep, "/")
            f.write(f"- `{rel}`\n")
        f.write(f"\n总体：{'全部被抓 且 已还原全绿' if ok else '存在未通过项（见上）'}\n")

    print("\n证据已落盘: " + out_path, flush=True)
    print("\n总体: " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())