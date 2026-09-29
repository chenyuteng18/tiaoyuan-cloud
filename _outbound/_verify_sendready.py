#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
独立验收 _send-ready/ 纯正文版 —— 不复用 _build_sendready.py 的任何代码（避免自证）。

验收项：
  V1 旧名「调元云」残留            = 0
  V2 段锚点标题（内部字样）        = 0
  V3 内部团队/智能体名             = 0
  V4 待填残留（排除对方回填模板）  = 0
  V5 内部内容泄漏：产物任一行不得出现在**原函件内部段**的行集合中
  V6 邮箱位/电话位结构正确
  V7 落款唯一（每封恰好 1 个落款块）
  V8 连续分隔线                    = 0
  V9 主题标题存在

用法：python _verify_sendready.py            # 验收
     python _verify_sendready.py --probe    # 反向验证（注入内部字样，必须被 V3 抓住）
     python _verify_sendready.py --probe-g7 # 反向验证 G7 专属判据（W1/W2 必须报红，EXIT=2）
"""
import os, re, sys, io

BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "_send-ready")

JOBS = [
    ("G2-内容方224题交付规格-2026-09-19.md", "G2-对外正文.md", "# 【内部附注】"),
    ("G3-法务协议电子签催办-2026-09-19.md", "G3-对外正文.md", "## 【第二部分 · 内部附注"),
    ("G4-厂商SDK问询发件-2026-09-19.md", "G4-对外正文.md", "# 【第二段 · 内部附注"),
    ("G5-研发回执催办-2026-09-22.md", "G5-对外正文.md", "## 【第二部分 · 内部附注】"),
]

INTERNAL_NAME = re.compile(r"调元云|产品战略团队|用户研究员|需求分析师|数据分析师|路线图规划|执笔|析客|数析|瑞思|智能体")
ANCHOR_TITLE  = re.compile(r"【第[一二](部分|段)[^】]*】")
LEAK_HINT     = re.compile(r"本函分两部分|请勿混发|不要随正文|内部附注|仅供我方内部|待发出")
FILL          = re.compile(r"【待填】|【发出前必填】|____________")

results = {}

def v(name, ok, detail=""):
    results.setdefault(name, []).append((ok, detail))
    return ok

for src, dst, ea in JOBS:
    p = os.path.join(OUT, dst)
    if not os.path.exists(p):
        v("存在", False, dst + " 不存在"); continue
    text = io.open(p, encoding="utf-8").read()
    lines = text.split("\n")
    label = dst.replace("-对外正文.md", "")

    # V1 旧名
    m = [i+1 for i, l in enumerate(lines) if "调元云" in l]
    v("V1 旧名", not m, "%s 命中行 %s" % (label, m))

    # V2 段锚点标题
    m = [i+1 for i, l in enumerate(lines) if ANCHOR_TITLE.search(l)]
    v("V2 段锚点标题", not m, "%s 命中行 %s" % (label, m))

    # V3 内部团队/智能体名
    m = [i+1 for i, l in enumerate(lines) if INTERNAL_NAME.search(l)]
    v("V3 内部名", not m, "%s 命中行 %s: %s" % (label, m, [lines[i-1].strip()[:50] for i in m]))

    # V4 待填（排除 G2 对方回填模板：同行含 ___ 或 2026-__-__）
    m = [i+1 for i, l in enumerate(lines)
         if FILL.search(l) and "___" not in l and "2026-__-__" not in l]
    v("V4 待填残留", not m, "%s 命中行 %s: %s" % (label, m, [lines[i-1].strip()[:60] for i in m]))

    # V5 内部内容泄漏（与原函件内部段做行级交集）
    srcp = os.path.join(BASE, src)
    raw = io.open(srcp, encoding="utf-8").read().split("\n")
    i1 = next((i for i, l in enumerate(raw) if l.startswith(ea)), None)
    if i1 is None:
        v("V5 内部泄漏", False, label + " 找不到内部段锚点")
    else:
        internal = set(l.strip() for l in raw[i1:] if len(l.strip()) >= 12)
        # 排除无实义的结构行（表格分隔 |---|---|、分隔线 ---、纯符号行）——
        # 这类行对外段与内部段都会出现，属正常，不是泄漏。
        junk = re.compile(r"^[\|\s\-:=—_]+$")
        hit = [l.strip()[:60] for l in lines
               if len(l.strip()) >= 12 and l.strip() in internal and not junk.match(l.strip())]
        v("V5 内部泄漏", not hit, "%s 泄漏 %d 行: %s" % (label, len(hit), hit[:3]))

    # V6 邮箱位/电话位
    bad = []
    for i, l in enumerate(lines):
        mm = re.search(r"邮箱\*{0,2}[:：]\s*([^\s　|]+)", l)
        mt = re.search(r"电话\*{0,2}[:：]\s*([^\s　|]+)", l)
        if mm and "@" not in mm.group(1):
            bad.append((i+1, "邮箱位非邮箱", mm.group(1)))
        if mt and ("@" in mt.group(1) or not re.search(r"\d", mt.group(1))):
            bad.append((i+1, "电话位非电话", mt.group(1)))
    v("V6 邮箱电话位", not bad, "%s %s" % (label, bad))

    # V7 落款唯一 —— 🛑 只看**文末 18 行**：抬头的「发件方」不是落款，
    #     若不限范围会把「抬头 1 + 文末 1」误判为重复落款。
    tail = "\n".join(lines[-18:])
    sign = len(re.findall(
        r"(?m)^\s*(?:\*\*发件方\*\*\s*[:：]|\*\*发件方\s*[:：]|\*\*落款\*\*\s*[:：])", tail))
    if sign == 0:
        sign = len(re.findall(r"(?m)^\s*联系人\s*[:：]", tail))   # 兜底：联系人行
    v("V7 落款唯一", sign == 1, "%s 文末落款块 = %d" % (label, sign))

    # V8 连续分隔线
    cont = bool(re.search(r"(?m)^\s*---\s*$\n(?:\s*\n)*^\s*---\s*$", text))
    v("V8 连续分隔线", not cont, label + (" 仍有连续 ---" if cont else ""))

    # V9 主题标题（须为 h1；`## TL;DR` 这类小节标题不算）
    v("V9 主题标题", bool(re.match(r"^\s*#\s", text)), label + " 首行非 h1 标题")

    # 补充：内部提示词
    m = [i+1 for i, l in enumerate(lines) if LEAK_HINT.search(l)]
    v("V10 内部提示词", not m, "%s 命中行 %s: %s" % (label, m, [lines[i-1].strip()[:50] for i in m]))

print("=" * 74)
print("独立验收：_send-ready/ 纯正文版（G2/G3/G4/G5/G7）")
print("=" * 74)
allok = True
for name in sorted(results, key=lambda s: (s.split()[0])):
    items = results[name]
    fails = [d for ok, d in items if not ok]
    status = "✅ PASS" if not fails else "❌ FAIL"
    if fails:
        allok = False
    print("%-18s %s" % (name, status))
    for d in fails:
        print("      ✗ %s" % d)
print("-" * 74)

# ============================================================================
# G7（2026-09-22 新增 · 无源函件）
# ----------------------------------------------------------------------------
# G7 是**全新撰写**的对外函（致合规方/测评机构），**没有**内部附注段，
# 因此 G2/G3/G4/G5 的 V5「与原函件内部段做行级交集」对它**不适用**。
# 它特有的风险不是"漏删内部段"，而是"**把内部编号体系/内部路径带进对外正文**"
# —— 因为该函由内部裁定件与缺口总控表提炼而来，极易夹带内部坐标。
# 故新增两条**只针对 G7** 的检查：
#   W1 内部编号体系（缺口总控表编号 / 裁定项编号 / 内部批次号）  = 0
#   W2 内部文件路径（_work/ · deliverables/ · skeleton/ · _outbound/）= 0
# ============================================================================
G7 = "G7-对外正文.md"
G7_INTERNAL_ID = re.compile(
    r"缺口总控表|裁定请求书|裁定单|R-\d{1,2}\b|G[1-6]\b|Q-E[12]|卡口\s*[1-4]|"
    r"第\s*[123]\s*批|BD-\d|F-[1-9]\b|U-1[0-9]\b|QC-[1-6]\b|B[1-6]\s*(类|依赖)|"
    r"dev-kickoff|readiness-check|gap-control|signoff-page|sprint1-kickoff")
G7_INTERNAL_PATH = re.compile(r"_work/|deliverables/|skeleton/|_outbound/|_send-ready/|\.md\b")

p7 = os.path.join(OUT, G7)
if not os.path.exists(p7):
    print("%-18s ❌ FAIL" % "G7")
    print("      ✗ G7-对外正文.md 不存在")
    allok = False
else:
    t7 = io.open(p7, encoding="utf-8").read()
    l7 = t7.split("\n")
    f7 = []
    m = [i+1 for i, l in enumerate(l7) if G7_INTERNAL_ID.search(l)]
    if m:
        f7.append("W1 内部编号 %s: %s" % (m, [l7[i-1].strip()[:55] for i in m[:3]]))
    m = [i+1 for i, l in enumerate(l7) if G7_INTERNAL_PATH.search(l)]
    if m:
        f7.append("W2 内部路径 %s: %s" % (m, [l7[i-1].strip()[:55] for i in m[:3]]))
    m = [i+1 for i, l in enumerate(l7) if "调元云" in l]
    if m:
        f7.append("V1 旧名 %s" % m)
    m = [i+1 for i, l in enumerate(l7) if INTERNAL_NAME.search(l)]
    if m:
        f7.append("V3 内部名 %s: %s" % (m, [l7[i-1].strip()[:50] for i in m[:3]]))
    m = [i+1 for i, l in enumerate(l7) if FILL.search(l)]
    if m:
        f7.append("V4 待填残留 %s" % m)
    m = [i+1 for i, l in enumerate(l7) if LEAK_HINT.search(l)]
    if m:
        f7.append("V10 内部提示词 %s" % m)
    if not re.match(r"^\s*#\s", t7):
        f7.append("V9 主题标题 首行非 h1")
    if re.search(r"(?m)^\s*---\s*$\n(?:\s*\n)*^\s*---\s*$", t7):
        f7.append("V8 连续分隔线")
    # 落款唯一（文末 18 行）
    tail7 = "\n".join(l7[-18:])
    s7 = len(re.findall(r"(?m)^\s*\*\*落款\*\*\s*[:：]", tail7))
    if s7 != 1:
        f7.append("V7 落款唯一 文末落款块 = %d" % s7)
    # 邮箱/电话位
    bad7 = []
    for i, l in enumerate(l7):
        mm = re.search(r"邮箱\*{0,2}[:：]\s*([^\s　|]+)", l)
        mt = re.search(r"电话\*{0,2}[:：]\s*([^\s　|]+)", l)
        if mm and "@" not in mm.group(1):
            bad7.append((i+1, "邮箱位非邮箱", mm.group(1)))
        if mt and ("@" in mt.group(1) or not re.search(r"\d", mt.group(1))):
            bad7.append((i+1, "电话位非电话", mt.group(1)))
    if bad7:
        f7.append("V6 邮箱电话位 %s" % bad7)

    print("%-18s %s" % ("G7", "✅ PASS" if not f7 else "❌ FAIL"))
    print("      覆盖：W1 内部编号 / W2 内部路径 / V1 旧名 / V3 内部名 / V4 待填 / "
          "V6 邮箱电话 / V7 落款 / V8 分隔线 / V9 标题 / V10 提示词")
    for d in f7:
        print("      ✗ %s" % d)
    if f7:
        allok = False

print("=" * 74)
print("总判定：%s" % ("✅ 全部通过" if allok else "❌ 存在未通过项"))

if "--probe" in sys.argv:
    print()
    print("=" * 74)
    print("反向验证：注入内部字样，V3 必须抓住")
    print("=" * 74)
    probe = os.path.join(OUT, "G3-对外正文.md")
    orig = io.open(probe, encoding="utf-8").read()
    io.open(probe, "w", encoding="utf-8").write(
        orig.replace("**发件方**：", "**发件方**：产品战略团队 · ", 1))
    txt = io.open(probe, encoding="utf-8").read()
    caught = bool(INTERNAL_NAME.search(txt)) or "产品战略团队" in txt
    io.open(probe, "w", encoding="utf-8").write(orig)   # 立即还原
    restored = io.open(probe, encoding="utf-8").read() == orig
    print("  注入「产品战略团队」→ 检测器%s" % ("抓住 ✅" if caught else "漏掉 ❌"))
    print("  注入撤销后内容完全还原：%s" % ("✅" if restored else "❌"))
    if not (caught and restored):
        sys.exit(3)

if "--probe-g7" in sys.argv:
    # ------------------------------------------------------------------
    # G7 专属判据（W1 内部编号 / W2 内部路径）的**可重复**反向验证。
    # 动机：W1/W2 是 2026-09-22 新增判据，当时只用**一次性手工注入**验过，
    #       没有留下可复跑的探针 —— 属"判据无自证"。此处补成常驻探针。
    # 纪律：探针注入后**必须**在 finally 里还原（crash-safe），否则会在
    #       `_send-ready/` 留下**永久幻影违规**（本项目已有先例：探针残留纪律）。
    # ------------------------------------------------------------------
    print()
    print("=" * 74)
    print("反向验证（G7 专属）：注入内部编号 + 内部路径，W1/W2 必须报红")
    print("=" * 74)
    import subprocess
    probe7 = os.path.join(OUT, G7)
    orig7 = io.open(probe7, encoding="utf-8").read()
    # 注入两行：一行内部编号（R-14 / 缺口总控表），一行内部路径（_work/...）
    injected = orig7.rstrip("\n") + (
        "\n\n> 附注：本条对应内部件 R-14 与 `_work/dev-doc-gap-control-2026-09-19.md` §一。\n")
    rc_caught = None
    try:
        io.open(probe7, "w", encoding="utf-8", newline="\n").write(injected)
        proc = subprocess.run([sys.executable, os.path.abspath(__file__)],
                              capture_output=True, text=True, encoding="utf-8")
        rc_caught = proc.returncode
        out7 = (proc.stdout or "") + (proc.stderr or "")
        got_w1 = "W1 内部编号" in out7
        got_w2 = "W2 内部路径" in out7
    finally:
        io.open(probe7, "w", encoding="utf-8", newline="\n").write(orig7)
    restored7 = io.open(probe7, encoding="utf-8").read() == orig7
    print("  注入「R-14 + 缺口总控表 + _work/ 路径」：")
    print("    W1 内部编号 报红：%s" % ("✅" if got_w1 else "❌ 未报"))
    print("    W2 内部路径 报红：%s" % ("✅" if got_w2 else "❌ 未报"))
    print("    子进程 EXIT = %s（期望 2）" % rc_caught)
    print("  注入撤销后 G7 完全还原：%s" % ("✅" if restored7 else "❌"))
    ok7 = bool(got_w1 and got_w2 and rc_caught == 2 and restored7)
    print("  %s" % ("✅ G7 专属判据有牙（不是恒绿）" if ok7 else "❌ G7 专属判据无牙或缺还原"))
    if not ok7:
        sys.exit(3)
    # 还原后必须回到全绿
    proc2 = subprocess.run([sys.executable, os.path.abspath(__file__)],
                           capture_output=True, text=True, encoding="utf-8")
    print("  还原后复跑 EXIT = %s（期望 0）" % proc2.returncode)
    if proc2.returncode != 0:
        sys.exit(3)

sys.exit(0 if allok else 2)