#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
批次七 · 反向验证（Reversal Validation）—— 证明【refund_offline_notice 字段级约束】有牙齿。

## 本脚本要证明什么

`RlsV6RefundLedgerIsolationTest` 原有 11 条断言，**全部锚定 RLS 面 / `refund_receipt` /
`refund_statement`** —— 三张账本里 `refund_offline_notice` 的**字段级约束一条都没被断言**
（缺口由"本类断言按表清点"得出）。本批次补了三例，本脚本对每例注入一个【必须被抓】的缺陷：

| 组 | 目标断言（方法名） | 注入内容（改 V6 迁移的 CHECK 字面） | 期望 |
|---|---|---|---|
| M1 | `refund_offline_notice_channel_excludes_push_channels` | 把 channel CHECK 扩成 `('电话','当面','订阅消息')` | 变红（「订阅消息」被放行 ⇒ 线下告知可被算成推送） |
| M2 | `refund_offline_notice_required_columns_are_not_null` | 去掉 `refund_id` 的 `NOT NULL` | 变红（缺 refund_id 也能落库） |
| M3 | `refund_offline_notice_has_exactly_one_check_constraint` | 追加第二条 CHECK（`note` 长度约束） | 变红（CHECK 数 1 → 2） |

## 为什么这三组"必须真跑数据库"

它们是**真库门禁**，判据来自 `information_schema` / `pg_constraint` / 实际 INSERT 的 SQLSTATE。
**改迁移文本会改变 schema 哨兵** ⇒ `RlsGateSupport` 判定库不匹配 ⇒ **自动重建库**（见其类注释
"schema 哨兵 = 整条迁移链字节的 SHA-256"）。故本脚本的注入-还原是**安全**的：
每轮注入都会让测试在"按新迁移重建的库"上运行，还原后再重建回原 schema。

## 纪律（与 102 / 103 / 104 同口径）

- **锚点预检**：每组注入前先断言锚点原文存在且**唯一**；找不到或 >1 次 ⇒ 直接 FAIL。
- **逐字节还原**：注入后二进制快照还原，并用字节数比对证明"文件 = 注入前那份"。
- **还原后复绿**：还原后重跑同一批断言，必须复绿。
- **🛑 注入必须保持源文件语法合法**：本脚本改的是 SQL 迁移文本（改字面 / 删 `NOT NULL` / 加一条
  `ADD CONSTRAINT`），语法均合法 —— 不会出现"红的是编译器/psql 解析器"的情形。
  这是 104 第一版"删整行导致编译失败"教训的延续纪律。
- 🛑 **Windows GBK ⇒ must_see 锚点必须 ASCII**（方法名），不要用中文断言原文。
- 🛑 **Windows 下必须用 `mvn.cmd` 的 Windows 路径 + list 调用 + `shell=False`**；
  用 POSIX 路径配 `shell=True` 会走 cmd.exe 而无法解析（103 第一版的缺陷）。
- 🛑 **不能用 `-q`**（安静模式不打印 `BUILD SUCCESS`，无法据此判绿）。

用法：
    python.exe verification/105_refund_offline_notice_reverse_verification.py
"""
from __future__ import annotations

import atexit
import os
import signal
import subprocess
import sys
from pathlib import Path

# ----------------------------------------------------------------------
# 路径与运行环境（与 102 / 103 / 104 同口径）
# ----------------------------------------------------------------------

HERE = Path(__file__).resolve().parent          # .../skeleton/verification
SKEL = HERE.parent                              # .../skeleton

MAVEN_CMD = r"C:\opt\apache-maven-3.9.9\bin\mvn.cmd"
JAVA_HOME_WIN = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
PG_BIN_WIN = r"C:\Program Files\PostgreSQL\17\bin"

MODULE = "dy-app"
GATE_CLASS = "RlsV6RefundLedgerIsolationTest"

# 断言方法名（ASCII，供 must_see 锚点用）
M_CHANNEL = "refund_offline_notice_channel_excludes_push_channels"
M_NOTNULL = "refund_offline_notice_required_columns_are_not_null"
M_CHECKS = "refund_offline_notice_has_exactly_one_check_constraint"
ALL_METHODS = [M_CHANNEL, M_NOTNULL, M_CHECKS]

# 被注入的真库门禁类（V6 迁移的消费方）与迁移文件本身
F_GATE = ("dy-app/src/test/java/com/diaoyuanyun/dy/app/"
          "rls/RlsV6RefundLedgerIsolationTest.java")
F_MIG = "dy-app/src/main/resources/db/migration/V6__refund_domain_alignment_and_ledgers.sql"

# ----------------------------------------------------------------------
# 注 入 定 义
#   每组 = (标签, 目标文件, 锚点原文, 替换文本, 期望变红的断言方法名)
#   🛑 锚点必须唯一；预检会强制这一点。
# ----------------------------------------------------------------------

INJECTIONS = [
    (
        "M1",
        F_MIG,
        # V6 里 refund_offline_notice 的 channel CHECK（与 refund_receipt 的同名约束不同表）
        "    channel      VARCHAR(32) NOT NULL CHECK (channel IN ('电话', '当面')),",
        "    channel      VARCHAR(32) NOT NULL CHECK (channel IN ('电话', '当面', '订阅消息')),",
        M_CHANNEL,
    ),
    (
        "M2",
        F_MIG,
        # refund_offline_notice 建表块内的 refund_id（只在 offline_notice 段出现此缩进形态）
        "    refund_id    UUID        NOT NULL REFERENCES refund (refund_id),\n"
        "    channel      VARCHAR(32) NOT NULL CHECK (channel IN ('电话', '当面')),",
        "    refund_id    UUID        REFERENCES refund (refund_id),\n"
        "    channel      VARCHAR(32) NOT NULL CHECK (channel IN ('电话', '当面')),",
        M_NOTNULL,
    ),
    (
        "M3",
        F_MIG,
        "COMMENT ON TABLE refund_offline_notice IS",
        # 追加第二条 CHECK（合法 SQL，且会被 M3 的计数断言看见）
        "ALTER TABLE refund_offline_notice ADD CONSTRAINT refund_offline_notice_note_len_check "
        "CHECK (note IS NULL OR length(note) <= 512);\n\n"
        "COMMENT ON TABLE refund_offline_notice IS",
        M_CHECKS,
    ),
]


def log(msg: str) -> None:
    print(msg, flush=True)


# ----------------------------------------------------------------------
# 环境与 Maven 调用
# ----------------------------------------------------------------------

def build_env() -> dict:
    env = dict(os.environ)
    java_home = env.get("JAVA_HOME")
    if not java_home or not Path(java_home).is_dir():
        java_home = JAVA_HOME_WIN
    env["JAVA_HOME"] = java_home
    parts = [str(Path(java_home) / "bin"), PG_BIN_WIN, env.get("PATH", "")]
    env["PATH"] = os.pathsep.join(p for p in parts if p)
    # 真库门禁所需的环境（与 103/104 一致）
    env.setdefault("DY_PG_HOST", "127.0.0.1")
    env.setdefault("DY_PG_PORT", "5432")
    env.setdefault("DY_PG_SUPER_PASSWORD", "postgres")
    return env


def run_gate() -> tuple[int, str]:
    """跑真库门禁类，返回 (exit_code, 输出)。

    🛑 用 Windows 绝对路径的 mvn.cmd + list 参数 + shell=False；
       不用 -q（否则看不到 BUILD SUCCESS）。
    """
    cmd = [
        MAVEN_CMD,
        "-o",
        "-pl", MODULE,
        "test",
        "-Dtest=" + GATE_CLASS,
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "-Dsurefire.failIfNoTests=false",
    ]
    proc = subprocess.run(
        cmd,
        cwd=str(SKEL),
        env=build_env(),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        shell=False,
    )
    out = proc.stdout.decode("utf-8", errors="replace")
    return proc.returncode, out


def failed_methods(out: str) -> set[str]:
    """从输出里抽取【失败的测试方法名】。

    🛑 判据 = 「方法名出现」+「进程 exit != 0」两条同时成立（见 main 中的用法）。
    单看"方法名出现"是不够的：surefire 在 `-Dtest=X` 下只打印汇总行，
    但仍需以基线为证（见 main 的元层自证），否则 must_see 锚点可能恒真。
    只看 ASCII 方法名 —— GBK/UTF-8 双编码下中文断言消息可能乱码，故不解析消息。
    """
    got: set[str] = set()
    for m in ALL_METHODS:
        if m in out:
            got.add(m)
    return got


# ----------------------------------------------------------------------
# 快照 / 还原（逐字节）
# ----------------------------------------------------------------------

_SNAPSHOTS: dict[str, bytes] = {}
_RESTORED = False


def snapshot(path_rel: str) -> bytes:
    p = SKEL / path_rel
    data = p.read_bytes()
    _SNAPSHOTS[path_rel] = data
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
        ok = now == data
        log(f"  {'OK ' if ok else 'BAD'} {rel}  bytes={len(now)}/{len(data)}")
    _RESTORED = True


def _sig_handler(signum, frame):  # noqa: ARG001
    restore_all()
    sys.exit(1)


atexit.register(restore_all)
signal.signal(signal.SIGINT, _sig_handler)
signal.signal(signal.SIGTERM, _sig_handler)


# ----------------------------------------------------------------------
# 主流程
# ----------------------------------------------------------------------

def main() -> int:
    log("=" * 74)
    log("批次七 · refund_offline_notice 字段级约束反向验证")
    log("=" * 74)

    # 0) 快照
    for rel in {F_GATE, F_MIG}:
        snapshot(rel)
    log(f"[快照] {F_MIG} bytes={len(_SNAPSHOTS[F_MIG])}")
    log(f"[快照] {F_GATE} bytes={len(_SNAPSHOTS[F_GATE])}")

    # 1) 锚点预检（存在 + 唯一）
    mig_text = _SNAPSHOTS[F_MIG].decode("utf-8")
    problems = []
    for tag, rel, anchor, _repl, _expect in INJECTIONS:
        n = mig_text.count(anchor)
        flag = "OK " if n == 1 else "BAD"
        if n != 1:
            problems.append(f"{tag}: 锚点出现 {n} 次（要求恰 1 次）")
        log(f"[预检 {flag}] {tag} 锚点出现 {n} 次")
    if problems:
        log("\n[预检失败] " + " | ".join(problems))
        return 2

    # 2) 基线：注入前必须全绿
    #    🛑 兼作 must_see 锚点的【元层判别力自证】：若基线输出里就已包含待查方法名，
    #       说明"看到方法名"是恒真的，后面的"被抓=True"一律无效 —— 直接判脚本失效。
    log("\n[基线] 注入前跑门禁（期望全绿）...")
    code, out = run_gate()
    if code != 0:
        log("[基线失败] 注入前门禁未通过 —— 后续结论无意义")
        log(out[-3000:])
        return 2
    baseline_hits = failed_methods(out)
    if baseline_hits:
        log(f"[元层自证失败] 基线（全绿）输出里已出现待查方法名 {sorted(baseline_hits)} "
            f"—— must_see 锚点恒真，本脚本无判别力")
        return 2
    log("[基线 OK] 注入前全绿，且待查方法名在基线中【零出现】（锚点有判别力）")

    # 3) 逐组注入 → 必须变红 → 逐字节还原
    results = []
    for tag, rel, anchor, repl, expect in INJECTIONS:
        log(f"\n[{tag}] 注入：{anchor[:60].replace(chr(10), ' ')}...")
        p = SKEL / rel
        text = _SNAPSHOTS[rel].decode("utf-8")
        assert text.count(anchor) == 1  # 预检已保证
        p.write_text(text.replace(anchor, repl), encoding="utf-8", newline="")

        code2, out2 = run_gate()
        hits = failed_methods(out2)
        caught = (code2 != 0) and (expect in hits)
        log(f"[{tag}] 门禁 exit={code2} · 期望红的方法 `{expect}` 被抓={caught} · 红集={sorted(hits)}")
        results.append((tag, expect, caught, code2))

        # 逐字节还原
        p.write_bytes(_SNAPSHOTS[rel])
        assert p.read_bytes() == _SNAPSHOTS[rel], f"{tag} 还原后字节不一致"

    # 4) 还原后复绿
    log("\n[复绿] 全部还原后重跑门禁（期望全绿）...")
    code3, out3 = run_gate()
    green = code3 == 0
    log(f"[复绿] exit={code3} 复绿={green}")

    # 5) 汇总
    log("\n" + "=" * 74)
    log("汇 总")
    log("=" * 74)
    passed = 0
    for tag, expect, caught, code in results:
        mark = "PASS" if caught else "FAIL"
        if caught:
            passed += 1
        log(f"  [{mark}] {tag}: 期望 `{expect}` 变红 · exit={code}")
    log(f"  [{'PASS' if green else 'FAIL'}] 还原后复绿")
    total = len(results) + 1
    ok_total = passed + (1 if green else 0)
    log(f"\n总体：{ok_total}/{total} 通过")
    log("=" * 74)

    return 0 if ok_total == total else 1


if __name__ == "__main__":
    sys.exit(main())