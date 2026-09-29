#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
_build_sendkit.py —— 把五封函件的「纯正文版」拼成**一次复制粘贴**的发送包。

动机
----
用户的目标是「一小时内整包开工」。发信是整条链上**唯一只有他能做**的动作，
却是 5 封邮件 × 5 次翻文件 × 5 次复制。本脚本把它压成：打开一个文件、按顺序复制 5 段
（拼装件共 5 个可复制段 = 5 段正文）。

历史说明：G1（业务方裁定催办）已于 2026-09-23 因用户裁定「不用签件」移出本链，
其专属的「B 案约定条款」可复制段机制随之删除；本包现为 G2/G3/G4/G5/G7 五封。

纪律（与 _build_sendready.py 同）
--------------------------------
1. 只读 `_send-ready/`（已过 V1~V10 独立验收的纯正文版），**不改任何既有函件**。
2. 可复制区【逐字节等于】源文件 —— 用显式起止标记包起来，越界即校验失败。
3. 本包属**调度层**（_dispatch/），本身**不对外**；可复制区之外的行一律标了内部提示。
4. 生成后由 `_verify_sendkit.py`（**独立实现，不复用本脚本逻辑**）复核。

输出
----
`_dispatch/00-一次复制-五封正文.md`
"""

import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
READY = os.path.join(HERE, "_send-ready")
DISPATCH = os.path.join(HERE, "_dispatch")
OUT = os.path.join(DISPATCH, "00-一次复制-五封正文.md")

BEGIN = "▼▼▼ 从这里开始复制（含此标记行以下，直到『到此结束』） ▼▼▼"
END = "▲▲▲ 复制到此结束（不要复制本行） ▲▲▲"

# (编号, 收件方, 主题行, 附件说明)
LETTERS = [
    ("G2", "经络师团队 / 吕老师方 内容负责人",
     "上医智养堂 · 分龄评估量表内容交付规格：224 题结构化定稿 + 56 题改写与逐题签核，请回交付日期与角色",
     "**无**（本封不含我方附件；正文里的「附件 1 张表」是**贵方回交件**，不是我方外发附件）"),
    ("G3", "法务部（主）　抄送：业务方",
     "上医智养堂 · 法务协议文本与电子签选型催办：请交付《知情同意书》《调理协议书》法律文本，退款与终止条款请留占位",
     "**无**"),
    ("G4", "星迈科技（istarmax）/ 即米 Runmefit —— 商务与技术支持",
     "上医智养堂 · 星迈 GTL1 智能手环 SDK 与样机索取及 13 条 P0 技术问询（另 1 条附加），请 5 个工作日内书面答复",
     "**无**（索取的是**对方提供**的 SDK 与样机）"),
    ("G5", "研发负责人（评审与合规构建责任人）",
     "上医智养堂 · 研发回执催办：请对四项评审问项出具正式回执，并在 owners.csv 写入 4 个 subject 的实名 owner",
     "**无**"),
    ("G7", "合规方（数据合规负责人）· 测评机构（等保测评 / 密码应用测评）",
     "上医智养堂 · 四项合规事项请求确认：SM4 是否强制 / 是否触发 PIPL 跨境 / IETF 草案状态 / 审计不可删与删除权的口径冲突",
     "**无**（本封为专业确认请求，我方附材料清单可索取）"),
]


def read(path):
    with io.open(path, "r", encoding="utf-8") as fh:
        return fh.read()


def main():
    os.makedirs(DISPATCH, exist_ok=True)

    buf = []
    w = buf.append

    w("# 00 · 一次复制 —— 五封函件正文（调度层 · **不对外**）")
    w("")
    w("| 项目 | 内容 |")
    w("|---|---|")
    w("| **用途** | 把「5 封邮件 × 翻 5 个文件 × 复制 5 次」压成**按顺序复制 5 段** |")
    w("| **正文来源** | `../_send-ready/*-对外正文.md`（**已过 V1~V10 独立验收**的纯正文版，逐字节等同） |")
    w("| **可复制区** | 每封由 `▼▼▼ … ▼▼▼` 与 `▲▲▲ … ▲▲▲` 包住；**只复制两标记之间** |")
    w("| **校验** | `python _verify_sendkit.py`（独立实现）—— 逐字节比对可复制区与源文件 |")
    w("| **日期依据** | `README.md` 第七节「假期日历（2026）」（国办发明电〔2025〕7号） |")
    w("")
    w("> 🛑 **只复制标记之间的内容。** 标记之外的行是我方内部提示（含收件方口径、并行提醒），")
    w("> 粘进邮件会对外泄漏内部信息。")
    w("")
    w("---")
    w("")
    w("## 发送顺序与前 3 项要素（对应 `sending-checklist.md` %d 张卡）" % len(LETTERS))
    w("")
    w("| 序 | 函件 | 收件方 | 主题行 | 附件 |")
    w("|---|---|---|---|---|")
    for i, (gid, to, subject, att) in enumerate(LETTERS, 1):
        att_short = "无"
        w("| %d | **%s** | %s | %s | %s |" % (i, gid, to, subject, att_short))
    w("")
    w("> **G4 / G5 的主题行是「5 个工作日」这种相对写法 —— 不要改成「9 月 30 日」。**")
    w("")
    w("---")
    w("")

    # ---- 逐封 ----
    for idx, (gid, to, subject, att) in enumerate(LETTERS, 1):
        src = os.path.join(READY, "%s-对外正文.md" % gid)
        body = read(src)

        w("## 第 %d 封 / 共 %d 封 —— %s" % (idx, len(LETTERS), gid))
        w("")
        w("| 要素 | 值 |")
        w("|---|---|")
        w("| **收件方** | %s |" % to)
        w("| **邮件主题** | %s |" % subject)
        w("| **正文来源** | `../_send-ready/%s-对外正文.md`（%d 行） |"
          % (gid, len(body.splitlines())))
        w("| **附件** | %s |" % att)
        w("")
        w("### %s · 邮件正文（纯正文版，逐字节等同源文件）" % gid)
        w("")
        w(BEGIN)
        w(body.rstrip("\n"))
        w(END)
        w("")
        w("---")
        w("")

    w("> **本件为调度层附件，只读 `_send-ready/`，不改任何既有函件。**")
    w("> **生成于 2026-09-22。**")

    out = "\n".join(buf) + "\n"
    with io.open(OUT, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(out)

    print("[sendkit] 写入: %s" % OUT)
    print("[sendkit] 字节: %d" % len(out.encode("utf-8")))
    print("[sendkit] 行数: %d" % len(out.splitlines()))
    return 0


if __name__ == "__main__":
    sys.exit(main())