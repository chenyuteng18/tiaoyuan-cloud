#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
_verify_sendkit.py —— 发送包（00-一次复制-五封正文.md）的**独立**验收器。

与构建器的关系：**不复用其任何函数/常量**（避免自证）。本脚本只做一件事：
证明「可复制区的内容」与「源文件的当前内容」**逐字节一致** —— 即
"你复制走的，就是那份过了 V1~V10 验收的正文，没有第 3 版出现"。

另做 4 项结构检查：
  C1  五封各出现一次且封序正确（G2/G3/G4/G5/G7），每封恰有 1 个 BEGIN/END 对
  C2  可复制区内**不含**内部提示词（调度层/内部路径/标记符 等）
  C3  标记之外**不得**出现源正文的任何一级标题（防"正文漏在标记外"）
  C4  主题行口径：G4 保持「5 个工作日」相对写法、且**不带**具体日期

退出码：0 = 全绿；1 = 存在硬失败；2 = 用法/环境错误。

历史说明：G1（业务方裁定催办）已于 2026-09-23 因用户裁定「不用签件」移出本链，
  B 案约定条款可复制段与其 C1/C4 专属断言随之删除。
"""

import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
READY = os.path.join(HERE, "_send-ready")
KIT = os.path.join(HERE, "_dispatch", "00-一次复制-五封正文.md")

# --probe：反向验证用。把一个源文件在【内存里】替换为注入版本，
#   期望验收器报红（证明判据有牙），**不落盘、不改任何文件**。
PROBE_TARGET = "G5-对外正文.md"
PROBE_INJECT = "\n\n（调度层内部泄漏注入探针：本行不应出现在可复制区）\n"

B = "▼▼▼ 从这里开始复制（含此标记行以下，直到『到此结束』） ▼▼▼"
E = "▲▲▲ 复制到此结束（不要复制本行） ▲▲▲"

# 内部标记：只针对**调度层独有的**字样。
# 🛑 不把「内部」列为内部词 —— 它在对外函件里是**合法用词**：
#     G4/G5「贵司内部确认」均为正当表述。
#     把常用词当泄漏词会制造永久误报（本项目已有先例：门禁词表拒绝裸词「判定」）。
LEAK = ["调度层", "_send-ready", "_dispatch", "▼▼▼", "▲▲▲", "只复制标记之间"]

TOTAL = 5   # 本包函件封数（G2/G3/G4/G5/G7）

fails, passes = [], []


def ok(msg):
    passes.append(msg)


def bad(msg):
    fails.append(msg)


def blocks(text):
    """返回所有标记之间的可复制段（按顺序）。"""
    out, pos = [], 0
    while True:
        i = text.find(B, pos)
        if i < 0:
            break
        j = text.find(E, i + len(B))
        if j < 0:
            bad("存在未闭合的起始标记（BEGIN 之后没有 END）")
            break
        out.append(text[i + len(B):j].strip("\n"))
        pos = j + len(E)
    return out


def region_outside(text):
    """返回所有标记之外的部分（用于 C3）。"""
    out, pos = [], 0
    while True:
        i = text.find(B, pos)
        if i < 0:
            out.append(text[pos:])
            break
        out.append(text[pos:i])
        j = text.find(E, i)
        if j < 0:
            break
        pos = j + len(E)
    return "\n".join(out)


def main():
    probe = "--probe" in sys.argv[1:]

    if not os.path.isfile(KIT):
        sys.stderr.write("[verify-sendkit] 发送包不存在: %s\n" % KIT)
        return 2

    with io.open(KIT, "r", encoding="utf-8") as fh:
        kit = fh.read()

    segs = blocks(kit)
    if not segs:
        bad("未能解析出任何可复制段")
        return report()

    # ---- 期望的可复制段序列（五封正文，无 B 案条款段）----
    expected = []
    for gid in ("G2", "G3", "G4", "G5", "G7"):
        path = os.path.join(READY, "%s-对外正文.md" % gid)
        with io.open(path, "r", encoding="utf-8") as fh:
            txt = fh.read()
        if probe and os.path.basename(path) == PROBE_TARGET:
            # 内存注入：让"源文件"多出一行内部泄漏 → 应触发 C2 与逐字节比对同时报红
            txt = txt.rstrip("\n") + PROBE_INJECT
        expected.append(txt.rstrip("\n"))

    # ---- 核心断言：逐段、逐字节 ----
    if len(segs) != len(expected):
        bad("可复制段数量不符：实际 %d、期望 %d" % (len(segs), len(expected)))
    else:
        ok("可复制段数量 = %d（五封正文）" % len(segs))

    # ---- C1：五封各出现一次、每封恰 2 个 BEGIN/END 标记（1 对）----
    # 🛑 逐封核验标题唯一 + 封序，而非只看总段数。总段数对得上但封序错位
    #    （例如复制粘贴时把 G4 段贴到 G3 位置）也会造成对外误发。
    seq = [("G2", 1), ("G3", 2), ("G4", 3), ("G5", 4), ("G7", 5)]
    bad_seq = []
    for gid, n in seq:
        hdr = "## 第 %d 封 / 共 %d 封 —— %s" % (n, TOTAL, gid)
        if kit.count(hdr) != 1:
            bad_seq.append("%s 标题出现 %d 次（期望 1）" % (gid, kit.count(hdr)))
    n_b, n_e = kit.count(B), kit.count(E)
    if bad_seq:
        bad("C1 封标题异常：%s" % bad_seq)
    elif n_b != TOTAL or n_e != TOTAL:
        bad("C1 BEGIN/END 标记数异常：BEGIN=%d、END=%d（各期望 %d）" % (n_b, n_e, TOTAL))
    else:
        ok("C1 五封各出现一次且封序正确；BEGIN/END 标记各 %d 个（每封 1 对）" % n_b)

    for i, exp in enumerate(expected):
        if i >= len(segs):
            break
        got = segs[i].strip("\n")
        if got == exp:
            ok("第 %d 段逐字节等同（%d 字节）" % (i + 1, len(exp.encode("utf-8"))))
        else:
            # 给出首个差异位置，便于定位
            n = min(len(got), len(exp))
            k = next((x for x in range(n) if got[x] != exp[x]), n)
            bad("第 %d 段与源文件不一致（首个差异在第 %d 字符；实际 %d 字符、期望 %d 字符）"
                % (i + 1, k, len(got), len(exp)))

    # ---- C2：可复制区内不含内部标记 ----
    for i, s in enumerate(segs, 1):
        hard = [t for t in LEAK if t in s]
        if hard:
            bad("第 %d 段可复制区内出现内部标记：%s" % (i, hard))
    if not any("可复制区内出现内部标记" in f for f in fails):
        ok("可复制区内无内部标记（调度层字样 / 路径 / 标记符）")

    # ---- C3：标记之外不得出现源正文的一级标题 ----
    outside = region_outside(kit)
    titles = []
    for gid in ("G2", "G3", "G4", "G5", "G7"):
        with io.open(os.path.join(READY, "%s-对外正文.md" % gid),
                     "r", encoding="utf-8") as fh:
            first = fh.readline().strip()
        if first and first in outside:
            titles.append(first)
    if titles:
        bad("正文标题出现在标记之外（可能漏在可复制区外）：%s" % titles)
    else:
        ok("标记之外未出现任何源正文一级标题")

    # ---- C4：主题行日期口径（G4 保持相对写法、不带具体日期）----
    # 🛑 封序以实际生成为准：新顺序 G2/G3/G4/G5/G7 ⇒ G4 = 第 3 封。
    m4 = re.search(r"## 第 3 封 / 共 5 封 —— G4(.*?)(?=\n## 第 4 封)", kit, re.S)
    if m4 and "9 月 30 日" not in m4.group(0) and "5 个工作日" in m4.group(0):
        ok("G4 主题行保持「5 个工作日」相对写法（未误写成固定日期）")
    else:
        bad("G4 主题行口径可疑（应含『5 个工作日』、不含『9 月 30 日』）")

    return report()


def report(probe=False):
    print("=" * 74)
    print("SENDKIT VERIFY (独立验收器 · 不复用构建器逻辑)%s"
          % ("  [--probe 反向验证模式]" if probe else ""))
    print("=" * 74)
    for p in passes:
        print("  [PASS] %s" % p)
    for f in fails:
        print("  [FAIL] %s" % f)
    print("-" * 74)
    print("PASS=%d  FAIL=%d" % (len(passes), len(fails)))
    print("=" * 74)
    return 1 if fails else 0


if __name__ == "__main__":
    _probe = "--probe" in sys.argv[1:]
    rc = main()
    if _probe:
        # 反向验证：注入后**必须**报红，否则说明判据无牙
        print()
        print("反向验证（--probe）：注入内部泄漏到 %s 的源文件" % PROBE_TARGET)
        if rc == 1:
            print("  ✅ 注入被抓住（EXIT=1）—— 判据有牙，不是恒绿")
        else:
            print("  ❌ 注入未被抓住（EXIT=%d）—— 判据无牙！" % rc)
    sys.exit(rc)