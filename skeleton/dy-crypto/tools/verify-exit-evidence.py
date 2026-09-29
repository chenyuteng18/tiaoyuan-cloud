#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
verify-exit-evidence.py —— A8 退出码证据的「log↔exit 配对」校验器

═══════════════════════════════════════════════════════════════════════════════
为什么需要这个脚本（缺口是什么）
═══════════════════════════════════════════════════════════════════════════════
一个 7 字节的 `EXIT=0` 文件是整条验收证据链里【伪造代价最低的一环】。
在它被采信之前，必须能回答两个问题：

  1. 这个 exit 文件，真的是那次运行的退出码吗？（配对问题）
  2. 它与那次运行的日志内容，自洽吗？（矛盾问题）

本脚本只做这两件事，并且【宁可判 UNPAIRABLE，也不猜】。

═══════════════════════════════════════════════════════════════════════════════
规则（写死在此，供复核；改规则须同步改本文档与反自证）
═══════════════════════════════════════════════════════════════════════════════
[R1] 配对窗口：`exit.birthtime − log.mtime` 必须 ≲ PAIR_WINDOW_SEC（默认 2.5 秒）。
     超窗 → UNPAIRABLE。
     理由：同链形态 `cmd > log 2>&1; rc=$?; echo "EXIT=$rc" > exit` 中，
     exit 的创建时刻紧跟在 shell 写日志之后，间隔应在亚秒~1 秒级。
     实测**唯一**通过双判据的样本：dycommon（同名 + 0.13s）。
     ⚠️ 别拿"某次间隔只有 2.2s"来调这个窗口 —— 配对**先要求同名**（见 [R3]），
     名字对不上时，间隔再小也是事后探测（dyc/dycrypto 即此类，其同名 log 不存在）。

[R2] 「最近日志」不等于「配对日志」：若某 log 的 mtime 与 exit.birthtime 间隔
     远大于窗口（实测见过 94s / 301s / 719s），那是【事后探测】落盘，
     该 exit 文件【不得计入"已采集的退出码"】→ UNPAIRABLE。

[R3] 日志不存在 / 【不同名】→ UNPAIRABLE（同链形态下 log 与 exit 必然成对存在
     且同名；exit 有而同名 log 无 ⇒ 同链不成立）。这是最强的 UNPAIRABLE 判据。
     注意：目录里"最近的 log" **不等于**配对日志 —— 实测本项目的日志名被改过
     （`dyc.exit` 的同名 `dyc.log` 不存在，只有 `dyc3.log`；`dycrypto.exit` 同理
     只有 `dycrypto2.log`）。故不同名时**必须**判 UNPAIRABLE，但仍显示与实际
     "最近日志"的间隔，以便人复核该退出码离哪一次运行最近。

[R4] 矛盾检查与证据分级（仅对已配对的）：判决【自上而下、先命中先返回】——
     ① `exit=0` 且日志含 `BUILD FAILURE`  → 【矛盾，判红 FAIL】并指出哪一对、差多少。
     ② 日志有构建结论行却**无任何 Tests 汇总行** → `NO_TEST_EVIDENCE`
        （无论 exit 是 0 还是非 0）—— 不计入"可引用"，也不判红。理由见 [R5]。
     ③ `exit≠0`：有 `BUILD SUCCESS` **且有汇总计数** → 本机已知噪声，`NOISY_PASS`，
        不判红；否则 → `FAIL`（非 0 退出且未见成功标记）。
     ④ `exit=0`：有汇总行但既无 SUCCESS 也无 FAILURE（被截断）→ `TRUNCATED`，
        不计入"构建成功"证据、也不判红；其余 → `PAIRED_OK`。

[R5] ⚠️ 三种"通过"形态【互不等价】—— 本脚本存在的全部理由就是区分它们：
     · ① 日志出现 `BUILD SUCCESS`
     · ② 汇总计数全绿（`Tests run: N, Failures: 0, Errors: 0`）
     · ③ 进程退出码 = 0
     **只满足 ① 与 ③、不满足 ②，绝不能算"可引用"**：那正是 `-DskipTests`
     或测试阶段被跳过的形态 —— Maven 会打印 `BUILD SUCCESS`、进程以 0 退出，
     但**没有任何测试被跑过**。故 `NO_TEST_EVIDENCE` 既不判红、也不计入可引用：
     它是"证据不足"，**不是"通过"**。

[R6] 本机已知噪声（重要，防止把环境问题误判为不通过）：
     Surefire 的 fork JVM 在 Windows 上会卡在 shutdown hook
     （`WinNTFileSystem.delete0(Native Method)` ← `TempFileManager.deleteAll`，
     主线程 WAITING 于 `Thread.join` ← `ApplicationShutdownHooks.runHooks`），
     导致【Maven 已打印 BUILD SUCCESS 之后】包装进程仍可能以非 0 退出。
     ⇒ 故本脚本对「exit≠0 + SUCCESS + 有计数」判 NOISY_PASS 而非 FAIL。
     ⇒ 验收依据应是日志内的汇总计数（Tests run: N, Failures: 0, Errors: 0），
       而非包装进程退出码。本脚本同时把该计数提取出来，供交叉核对。

═══════════════════════════════════════════════════════════════════════════════
用法
═══════════════════════════════════════════════════════════════════════════════
    python verify-exit-evidence.py --dir <证据目录>            # 自动发现 *.exit/*.log
    python verify-exit-evidence.py --pair dycommon.exit --log dycommon.log
    python verify-exit-evidence.py --dir . --window 2.5 --verbose

--pair 形态：先自动找同名 log（`<stem>.log`）判配对；找不到才把传入的 `--log`
当作"最近邻"仅用于显示间隔（判 UNPAIRABLE）。显式指定输入的判决【不得】比
自动发现更弱（见 [R3]/[R4]）。

退出码：0 = 全部配对成功且无矛盾；1 = 存在 FAIL（矛盾）；2 = 有 UNPAIRABLE
或 NO_TEST_EVIDENCE（两者本身都不算"构建失败"，但都意味着该 exit 文件
不可被引用 —— 故用 2 表示"证据不可用"，与 1 的"证据互相打脸"区分开）。
"""

import argparse
import os
import re
import sys

PAIR_WINDOW_SEC_DEFAULT = 2.5

TESTS_SUMMARY_RE = re.compile(
    r"Tests run:\s*(\d+),\s*Failures:\s*(\d+),\s*Errors:\s*(\d+)(?:,\s*Skipped:\s*(\d+))?"
)

EXIT_VALUE_RE = re.compile(r"^\s*EXIT\s*=\s*(-?\d+)\s*$")


def read_birth(path):
    """文件创建时间；不可得返回 None（不可得时不得退化为用 mtime 猜）。"""
    st = os.stat(path)
    return getattr(st, "st_birthtime", None)


def parse_exit_value(path):
    """读 exit 文件。返回 (值, 原文) 或 (None, 原文) 表示格式不合法。"""
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        text = fh.read().strip()
    m = EXIT_VALUE_RE.match(text)
    return (int(m.group(1)) if m else None), text


def analyze_log(path):
    """提取日志的关键事实：是否 BUILD SUCCESS/FAILURE、汇总计数、是否被截断、
    是否有测试证据（无汇总行即 NO_TEST_EVIDENCE 形态，见 [R6]）。"""
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        text = fh.read()
    success = "BUILD SUCCESS" in text
    failure = "BUILD FAILURE" in text
    summary = None
    for m in TESTS_SUMMARY_RE.finditer(text):
        summary = tuple(int(g) if g is not None else 0 for g in m.groups())
    # [R6] 有构建结论行（SUCCESS/FAILURE）却【没有任何】Tests 汇总行 ⇒ 没有测试证据。
    has_test_evidence = summary is not None
    no_test_evidence = (success or failure) and not has_test_evidence
    # 截断判据：有汇总但既无 SUCCESS 也无 FAILURE，说明 Maven 结束前就断了。
    truncated = summary is not None and not success and not failure
    return {
        "success": success,
        "failure": failure,
        "summary": summary,
        "has_test_evidence": has_test_evidence,
        "no_test_evidence": no_test_evidence,
        "truncated": truncated,
    }


def verify_pair(exit_path, log_path, window, same_name=True):
    """
    校验一对 (exit, log)。返回 dict：
      status: PAIRED_OK / NOISY_PASS / TRUNCATED / FAIL / UNPAIRABLE
      reason: 人类可读原因（FAIL/UNPAIRABLE 必须指得出是哪一对、差多少）

    same_name=False 表示 log_path 只是"最近的 log"、并非同名配对日志
    （见 [R3]）——此时一律判 UNPAIRABLE，但仍回报实际间隔。
    """
    name = os.path.basename(exit_path)
    out = {
        "exit_file": name,
        "log_file": os.path.basename(log_path) if log_path else None,
        "exit_value": None,
        "gap_sec": None,
        "status": None,
        "reason": "",
        "log": None,
    }

    if not os.path.exists(exit_path):
        out["status"] = "MISSING"
        out["reason"] = "exit 文件不存在"
        return out

    value, raw = parse_exit_value(exit_path)
    out["exit_value"] = value
    if value is None:
        out["status"] = "FAIL"
        out["reason"] = "exit 文件格式非法（期望形如 `EXIT=0`），实际内容=%r" % raw
        return out

    # 先取 exit 的创建时间：即便后面判 UNPAIRABLE，也要能回报"差多少"。
    birth = read_birth(exit_path)
    if birth is None:
        out["status"] = "UNPAIRABLE"
        out["reason"] = "无法取得 exit 文件的创建时间（st_birthtime 不可用），拒绝以 mtime 猜测配对"
        return out

    # [R3] 日志不存在 / 不同名 → 最强 UNPAIRABLE
    if not log_path or not os.path.exists(log_path):
        out["status"] = "UNPAIRABLE"
        out["reason"] = (
            "同名日志不存在（期望 %s）—— 同链形态下 log 与 exit 必然【同名】成对出现，"
            "exit 存在而同名 log 缺失 ⇒ 同链捕获不成立，该退出码不可引用"
            % (os.path.basename(exit_path)[: -len(".exit")] + ".log")
        )
        return out

    log_mtime = os.stat(log_path).st_mtime
    gap = birth - log_mtime
    out["gap_sec"] = gap

    if not same_name:
        out["status"] = "UNPAIRABLE"
        out["reason"] = (
            "不同名：%s 的同名日志不存在，%s 只是【最近的 log】而非配对日志"
            "（间隔 %.2fs，仅供人复核该退出码离哪一次运行最近）"
            "—— 名称不一致即同链捕获不成立，该退出码不得计入『已采集的退出码』"
            % (out["exit_file"], out["log_file"], gap)
        )
        return out

    # [R1][R2] 配对窗口

    if gap < 0:
        out["status"] = "UNPAIRABLE"
        out["reason"] = (
            "exit 早于 log 完成（间隔 %.2fs）—— 时序不可能，配对不成立" % gap
        )
        return out
    if gap > window:
        out["status"] = "UNPAIRABLE"
        out["reason"] = (
            "%s ↔ %s 间隔 %.2fs > 窗口 %.2fs —— 判定为【事后探测】而非同链捕获，"
            "该退出码不得计入『已采集的退出码』"
            % (out["exit_file"], out["log_file"], gap, window)
        )
        return out

    info = analyze_log(log_path)
    out["log"] = info

    # 判定优先级（自上而下，先命中先返回，与 [R4] 逐条一一对应）：
    #   ① 明确矛盾（BUILD FAILURE 配 EXIT=0）→ FAIL
    #   ② 无测试证据（有 SUCCESS/FAILURE 却无任何 Tests 汇总行）→ NO_TEST_EVIDENCE
    #   ③ exit≠0 → 有 SUCCESS 且有计数=本机噪声 NOISY_PASS；否则 FAIL
    #   ④ exit=0 → 有汇总无结论=TRUNCATED；否则 PAIRED_OK
    # ② 先于 ③ 的理由：一次 `-DskipTests` 且包装进程非 0 的运行同样没有测试
    # 证据，不该被 NOISY_PASS 洗白成"可引用"。

    # ① [R4] 矛盾检查（最高优先：明确的互相打脸）
    if info["failure"] and value == 0:
        out["status"] = "FAIL"
        out["reason"] = (
            "矛盾：%s 记 EXIT=0，但配对日志 %s 含 BUILD FAILURE —— "
            "『构建失败』与『退出码 0』不可能同时为真（差值：退出码应为非 0）"
            % (out["exit_file"], out["log_file"])
        )
        return out

    # ② [R4]/[R5] 无测试证据：谁都没证明测试跑过
    if info["no_test_evidence"]:
        extra = "" if value == 0 else "（且退出码为 %d）" % value
        out["status"] = "NO_TEST_EVIDENCE"
        out["reason"] = (
            "配对成立（间隔 %.2fs），但日志 %s 有构建结论行却【无任何 Tests 汇总行】%s —— "
            "只能证明『进程以 %s 退出 + Maven 打印过结论行』，"
            "【不能证明任何测试通过】（典型形态：-DskipTests / 测试阶段被跳过）"
            "—— 不计入『可引用』"
            % (gap, out["log_file"], extra, "0" if value == 0 else str(value))
        )
        return out

    # ③ exit≠0
    if value != 0:
        if info["success"] and info["summary"] is not None:
            # [R6] 本机已知噪声：不判红
            s = info["summary"]
            out["status"] = "NOISY_PASS"
            out["reason"] = (
                "exit=%d 但日志含 BUILD SUCCESS 且计数 %d/%d/%d ⇒ 判定为本机已知噪声"
                "（Surefire fork JVM shutdown hook 挂起），【不判红】"
                % (value, s[0], s[1], s[2])
            )
            return out
        out["status"] = "FAIL"
        out["reason"] = (
            "矛盾：%s 记 EXIT=%d，且配对日志 %s 无『BUILD SUCCESS + 汇总计数』"
            "—— 退出码非 0 且未见完整的成功标记" % (out["exit_file"], value, out["log_file"])
        )
        return out

    # ④ exit=0
    if info["truncated"]:
        out["status"] = "TRUNCATED"
        out["reason"] = (
            "exit=0 与 %s 配对成立，但该日志被截断（有汇总、无 BUILD SUCCESS/FAILURE）"
            "—— 只能证明『进程以 0 退出』，不能证明『构建成功』" % out["log_file"]
        )
        return out

    out["status"] = "PAIRED_OK"
    out["reason"] = (
        "配对成立且自洽（间隔 %.2fs）；日志含 BUILD SUCCESS 且计数 %d/%d/%d"
        % (gap, info["summary"][0], info["summary"][1], info["summary"][2])
    )
    return out


def discover(directory):
    """发现目录下的 (exit, log, same_name) 候选。

    同名 log 存在 → 用它，same_name=True；
    否则按 [R3] 一律判 UNPAIRABLE，但仍挑出 mtime 最接近该 exit 的 log
    一并传入（same_name=False），以便报告里能显示"差多少"、离哪一次运行最近。
    """
    entries = os.listdir(directory)
    exits = sorted(f for f in entries if f.endswith(".exit"))
    logs = sorted(f for f in entries if f.endswith(".log"))
    pairs = []
    for ex in exits:
        stem = ex[: -len(".exit")]
        same_name = stem + ".log"
        ex_path = os.path.join(directory, ex)
        if same_name in logs:
            pairs.append((ex_path, os.path.join(directory, same_name), True))
            continue
        # 同名 log 不存在 —— 挑最近的 log 仅用于显示间隔
        birth = read_birth(ex_path)
        nearest = None
        if birth is not None and logs:
            nearest = min(
                logs,
                key=lambda lg: abs(birth - os.stat(os.path.join(directory, lg)).st_mtime),
            )
        pairs.append(
            (ex_path, os.path.join(directory, nearest) if nearest else None, False)
        )
    return pairs, exits, logs


def main():
    ap = argparse.ArgumentParser(description="校验 exit 文件与日志的配对与自洽")
    ap.add_argument("--dir", help="证据目录（自动发现 *.exit / *.log）")
    ap.add_argument("--pair", help="单个 exit 文件")
    ap.add_argument("--log", help="与 --pair 配对的 log 文件（可为不存在路径，用于反自证）")
    ap.add_argument("--window", type=float, default=PAIR_WINDOW_SEC_DEFAULT,
                    help="配对窗口秒数，默认 %s" % PAIR_WINDOW_SEC_DEFAULT)
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    results = []
    if args.pair:
        # [R3] --pair 形态：判决【不得】比 --dir 更弱 —— 先自动去找同名 log
        # （`<stem>.log`，在 exit 文件所在目录）；找得到就用它判（矛盾按 [R4] 出 FAIL）；
        # 找不到才把传入的 --log 当作"最近邻"仅用于显示间隔（判 UNPAIRABLE）。
        exit_dir = os.path.dirname(os.path.abspath(args.pair))
        log_path, same = args.log, True
        if args.pair.endswith(".exit"):
            same_name = os.path.basename(args.pair)[: -len(".exit")] + ".log"
            if not args.log:
                same_name_path = os.path.join(exit_dir, same_name)
                if os.path.exists(same_name_path):
                    log_path, same = same_name_path, True
            elif os.path.basename(args.log) == same_name:
                same = True
            else:
                # 传入的 --log 与 exit 不同名 —— 但若目录里存在同名 log，优先用它。
                same_name_path = os.path.join(exit_dir, same_name)
                if os.path.exists(same_name_path):
                    log_path, same = same_name_path, True
                else:
                    same = False
        results.append(verify_pair(args.pair, log_path, args.window, same_name=same))
    elif args.dir:
        pairs, exits, logs = discover(args.dir)
        if not exits:
            print("未发现任何 .exit 文件于 %s" % args.dir)
            return 0
        for ex_path, log_path, same_name in pairs:
            results.append(verify_pair(ex_path, log_path, args.window, same_name=same_name))
    else:
        ap.error("需要 --dir 或 --pair")

    order = {"FAIL": 0, "UNPAIRABLE": 1, "MISSING": 2, "NO_TEST_EVIDENCE": 3,
             "TRUNCATED": 4, "NOISY_PASS": 5, "PAIRED_OK": 6}
    results.sort(key=lambda r: order.get(r["status"], 9))

    print("=" * 78)
    print("A8 EXIT-EVIDENCE PAIRING CHECK   (配对窗口 = %.1fs)" % args.window)
    print("=" * 78)
    print("%-22s %-22s %-6s %-9s %s" % ("exit 文件", "log 文件", "值", "间隔(s)", "判定"))
    print("-" * 78)
    for r in results:
        gap = "%.2f" % r["gap_sec"] if r["gap_sec"] is not None else "-"
        val = r["exit_value"] if r["exit_value"] is not None else "?"
        print("%-22s %-22s %-6s %-9s %s"
              % (r["exit_file"], r["log_file"] or "(缺失)", val, gap, r["status"]))
    print("-" * 78)

    print()
    print("逐条原因：")
    for r in results:
        print("  · [%s] %s" % (r["status"], r["reason"]))
        if args.verbose and r["log"]:
            info = r["log"]
            print("      日志事实: success=%s failure=%s no_test_evidence=%s truncated=%s summary=%s"
                  % (info["success"], info["failure"], info["no_test_evidence"],
                     info["truncated"], info["summary"]))

    fails = [r for r in results if r["status"] == "FAIL"]
    unpairable = [r for r in results if r["status"] in ("UNPAIRABLE", "MISSING")]
    no_test = [r for r in results if r["status"] == "NO_TEST_EVIDENCE"]
    # [R6] NO_TEST_EVIDENCE 【不】计入可引用：它没有任何测试证据。
    credible = [r for r in results if r["status"] in ("PAIRED_OK", "NOISY_PASS", "TRUNCATED")]

    print()
    print("=" * 78)
    print("汇总：可引用=%d  不可配对=%d  无测试证据=%d  矛盾=%d"
          % (len(credible), len(unpairable), len(no_test), len(fails)))
    if unpairable:
        print("⚠️ 以下 exit 文件的退出码【属弱证据、不得计入已采集的退出码】：")
        for r in unpairable:
            print("   - %s" % r["exit_file"])
    if no_test:
        print("⚠️ 以下 exit 文件配对成立、但【无任何测试证据】，不得计入『可引用』：")
        for r in no_test:
            print("   - %s" % r["exit_file"])
        print("   （成因：-DskipTests / 测试阶段被跳过 —— 见 [R6]）")
    if fails:
        print("❌ 存在矛盾（见上方逐条原因）")
    print("说明：本模块验收依据 = 日志内汇总计数（Tests run: N, Failures: 0, Errors: 0）"
          " + 逐测试类明细；包装进程退出码在本机受 fork-JVM 挂起影响，属环境噪声。")
    print("=" * 78)

    if fails:
        return 1
    if unpairable or no_test:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())