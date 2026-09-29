#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成「可直接发送」的纯正文版函件（_send-ready/）—— v3 形态收口。

v3 相对 v2 新增（2026-09-21，均属「对外件的形态正确性」）：
  1. 剥掉对外段锚点标题行（【第X部分 · 对外正文 · 可直接转发】这类**内部字样**）
  2. 剥掉内部团队/智能体署名（如「产品战略团队（用户研究员 ××× 执笔）」）
  3. 收敛尾部连续分隔线（--- 叠 2~4 个）
  4. 无落款者补统一落款；已有落款者不重复补（G4 曾因无条件追加出两套落款）
  5. G4 抬头补回信方式（原件抬头只有「发件方 / 日期」，厂商不知回给谁）

硬约束（不变量）：
  - G3/G4 对外段一律从**锚点行之后**起 —— 其文件头含内部元信息表
    （`出具 | 析客（需求分析师）`、`状态 | 待发出`），从文件头起会把内部信息发给对方。
  - 不碰「对方要填的回填模板」（G2 表格里的 ___ / 2026-__-__）
  - 任一自检不通过 → 立即 die（纪律 13：报错即停，不做"尽力而为"）

历史说明：G1（业务方裁定催办）已于 2026-09-23 因用户裁定「不用签件」移出本链，
  本脚本现只处理 G2/G3/G4/G5 四封。
"""
import os, re, sys, io

BASE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(BASE, "_send-ready")
os.makedirs(OUT, exist_ok=True)

SIGNER, DEPT = "方向明", "产品组"
MAIL = "zksy@zksylf.com"
TEL = "010-53395939"
BRAND = "上医智养堂"
REPLY_DAYS = "5"      # G4 首轮书面答复时限（我方建议值，可改）

# (源文件, 输出名, 对外段锚点行|None, 内部段锚点行, 主题名)
#   sa=None  → 文件头即对外正文；锚点标题行由 LEAK_RE 逐行删除
#              （G2/G5 本就无段锚点）
#   sa=<行>  → 从该锚点行的**下一行**开始
#              （G3/G4 文件头含内部元信息表「出具 | ××（××分析师）/ 状态 | 待发出」，
#               必须跳过，否则把内部信息发给对方；主题名须另行补回）
JOBS = [
    ("G2-内容方224题交付规格-2026-09-19.md", "G2-对外正文.md", None, "# 【内部附注】", None),
    ("G3-法务协议电子签催办-2026-09-19.md", "G3-对外正文.md", "## 【第一部分 · 对外正文", "## 【第二部分 · 内部附注",
     "# %s · 法务协议文本交付与电子签选型 · 催办函" % BRAND),
    ("G4-厂商SDK问询发件-2026-09-19.md", "G4-对外正文.md", "# 【第一段 · 对外正文", "# 【第二段 · 内部附注",
     "# %s · 星迈 GTL1 智能手环 · 厂商问询函" % BRAND),
    ("G5-研发回执催办-2026-09-22.md", "G5-对外正文.md", None, "## 【第二部分 · 内部附注】", None),
]

def die(m):
    print("✗ " + m); sys.exit(2)

# 内部编排导读块（整行删除，不进对外正文）
NOISE = re.compile(
    r"^\s*>\s*[-–—]\s*\*{0,2}【第[一二]?[部段]分?[^】]*】"
    r"|^\s*>\s*⚠️\s*\*{0,2}本[函件]分两部分"
    r"|^\s*>\s*以下内容仅供我方内部使用"
)

# 🛑 内部字样黑名单（出现在对外正文即视为泄漏，硬报错）
LEAK_RE = [
    ("段锚点标题", re.compile(r"^\s*#*\s*【第[一二](部分|段)[^】]*】")),
    ("内部团队署名", re.compile(r"产品战略团队|用户研究员|需求分析师|数据分析师|路线图规划|执笔")),
    ("内部状态字段", re.compile(r"\|\s*\*{0,2}状态\*{0,2}\s*\|\s*\*{0,2}待发出|文档类型\s*\|\s*对外发件件")),
]

def fix_line(l, fn):
    """逐行精确替换；返回 (新行|None, 替换次数说明)"""
    hits = []

    # 1) 删除内部编排导读块（"本函分两部分 / 【第二部分·内部附注】/ 不要随正文发出"）
    if NOISE.match(l):
        return None, ["删导读"]

    # 2) 内部团队署名行：**整行重写**为对外口径（须先于泄漏检查，
    #    否则「产品战略团队」会先被判为泄漏而整行删掉，导致抬头缺「出具」）
    if re.match(r"^\s*\*\*出具\*\*：", l):
        return "**出具**：%s · %s" % (BRAND, DEPT), ["内部署名"]

    # 3) 剥掉段锚点标题行 / 内部团队署名残留 / 内部状态字段（内部字样，不得发给对方）
    for name, rx in LEAK_RE:
        if rx.search(l):
            return None, ["删" + name]

    # 4) 发件人抬头整行：发件人 / 部门 / 邮箱 / 电话
    #    去掉「（署名）」与内部标记「〔发出前必填〕」——对外正文不得出现内部标记
    #    🛑 本规则**非 G1 专属**：G2/G5 源件第 6 行同为 `____________` 占位格式，共用本规则。
    #       （2026-09-23 移除 G1 时实测：禁用本规则 → G2 立即触发 slot_check「邮箱位不是邮箱」而 die）
    if "**发件人**：" in l:
        return ("**发件人**：%s　**部门**：%s　**邮箱**：%s　**电话**：%s"
                % (SIGNER, DEPT, MAIL, TEL)).rstrip(), ["抬头"]

    # 5) G3 抬头：联系人（带反引号）—— 反引号是为方便替换，对外须去掉
    #    🛑 必须【分别】定位邮箱位与电话位；不可用两次相同的 replace（第一次会替换掉全部，
    #       第二次落空 => 电话被填成邮箱。2026-09-21 实际踩到过这个坑）。
    if re.search(r"\*\*联系人\*\*：\s*`?【待填】`?", l):
        l = re.sub(r"\*\*联系人\*\*：\s*`?【待填】`?", "**联系人**：" + SIGNER, l)
        l = re.sub(r"\*\*邮箱\*\*：\s*`?【待填】`?", "**邮箱**：" + MAIL, l)
        l = re.sub(r"\*\*电话\*\*：\s*`?【待填】`?", "**电话**：" + TEL, l)
        l = l.replace("`", "")
        hits.append("G3抬头")

    # 6) G4 抬头：原件只有「发件方 / 日期」，厂商无从得知回给谁 → 在日期行前补联系人行
    if fn.startswith("G4") and re.match(r"^\s*\*\*日期[:：]", l):
        return ("**联系人：%s　邮箱：%s　电话：%s**\n" % (SIGNER, MAIL, TEL)) + l.strip(), ["G4抬头联系"]

    # 7) G4 回复方式行：回复请发送至：联系人 **【待填】** ／ 邮箱 **【待填】** ／ 电话 **【待填】**。
    if "回复请发送至" in l:
        l = re.sub(r"联系人\s*\*\*\s*【待填】\s*\*\*", "联系人 **%s**" % SIGNER, l)
        l = re.sub(r"邮箱\s*\*\*\s*【待填】\s*\*\*", "邮箱 **%s**" % MAIL, l)
        l = re.sub(r"电话\s*\*\*\s*【待填】\s*\*\*", "电话 **%s**" % TEL, l)
        hits.append("G4回复方式")

    # 8) G4 落款三行
    if re.match(r"^\s*联系人：\s*\*\*【待填】\*\*\s*$", l):
        return "联系人：%s" % SIGNER, ["G4落款"]
    if re.match(r"^\s*邮箱：\s*\*\*【待填】\*\*\s*$", l):
        return "邮箱：%s" % MAIL, ["G4落款"]
    if re.match(r"^\s*电话：\s*\*\*【待填】\*\*\s*$", l):
        return "电话：%s" % TEL, ["G4落款"]

    # 8) 答复时限
    if "【待填】个工作日内" in l:
        l = l.replace("【待填】个工作日内", "%s 个工作日内" % REPLY_DAYS); hits.append("答复时限")

    # 9) 旧名
    if "调元云" in l:
        l = l.replace("调元云项目组", BRAND + "项目组").replace("调元云产品组", BRAND + "产品组").replace("调元云", BRAND)
        hits.append("旧名")

    # 10) 统一部门口径：项目组 → 产品组
    #     依据 `SENDER-发件人信息.md` 已裁定「部门 = 产品组」。
    #     旧名替换后 G3/G4 会得到「上医智养堂项目组」，而 G2/G5 用的是
    #     「上医智养堂 · 产品组」—— 四封抬头/落款口径必须一致，否则同一批发出的
    #     函件来自两个不同的署名主体（对外件形态缺陷）。
    if "上医智养堂项目组" in l:
        l = l.replace("上医智养堂项目组", "%s · %s" % (BRAND, DEPT))
        hits.append("统一部门")

    return l, hits


def collapse_hr(text):
    """把「只隔空行的连续 ---」压成 1 个（G2 曾叠 4 个）"""
    out = []
    for ln in text.split("\n"):
        if ln.strip() == "---":
            j = len(out) - 1
            while j >= 0 and out[j].strip() == "":
                j -= 1
            if j >= 0 and out[j].strip() == "---":
                continue
        out.append(ln)
    return "\n".join(out)


SIGNOFF = re.compile(
    r"(?m)^\s*(?:\*\*发件方\*\*\s*[:：]|\*\*发件方\s*[:：]|\*\*落款\*\*\s*[:：]|联系人\s*[:：])")


def has_signoff(text):
    """文末 18 行内是否已有落款（有则不重复追加；G3 正文自带「**落款**」、
    G4 自带「**发件方：…**」+ 联系人三行，均不应再追加，否则一封出现两套落款）"""
    return bool(SIGNOFF.search("\n".join(text.split("\n")[-18:])))


def slot_check(text, name):
    """结构性自检：邮箱位必须是邮箱、电话位必须是电话（防互换）"""
    for ln in text.split("\n"):
        m_mail = re.search(r"邮箱\*{0,2}[:：]\s*`?([^\s　`／|]+)", ln)
        m_tel = re.search(r"电话\*{0,2}[:：]\s*`?([^\s　`／|]+)", ln)
        if m_mail and "@" not in m_mail.group(1):
            die("%s 邮箱位不是邮箱: %r" % (name, ln.strip()[:80]))
        if m_tel and ("@" in m_tel.group(1) or not re.search(r"\d", m_tel.group(1))):
            die("%s 电话位不是电话: %r" % (name, ln.strip()[:80]))


def leak_check(text, name):
    """六类自检，任一命中即停"""
    bad = []
    if re.search(r"调元云", text):
        bad.append("旧名残留")
    for label, rx in LEAK_RE:
        if rx.search(text):
            bad.append(label)
    for ln in text.split("\n"):
        if "【待填】" in ln or "【发出前必填】" in ln or "____________" in ln:
            if "___" not in ln:          # G2 的对方回填模板属正常
                bad.append("待填残留: " + ln.strip()[:60])
    if re.search(r"(?m)^\s*---\s*$\n(?:\s*\n)*^\s*---\s*$", text):
        bad.append("连续分隔线未收敛")
    if not re.match(r"^\s*#\s", text):      # 🛑 必须是 h1 主题标题，`## TL;DR` 不算
        bad.append("缺主题标题")
    if bad:
        die("%s 自检未通过 → %s" % (name, " / ".join(sorted(set(bad)))))


report = []
for src, dst, sa, ea, title in JOBS:
    p = os.path.join(BASE, src)
    if not os.path.exists(p):
        die("源文件不存在: " + src)
    lines = io.open(p, encoding="utf-8").read().split("\n")

    start = 0
    if sa:
        i0 = next((i for i, l in enumerate(lines) if l.startswith(sa)), None)
        if i0 is None:
            die("未找到对外段锚点 %r in %s" % (sa, src))
        start = i0 + 1                   # 🛑 跳过锚点行本身
    i1 = next((i for i, l in enumerate(lines) if l.startswith(ea)), None)
    if i1 is None:
        die("未找到内部段锚点 %r in %s" % (ea, src))

    body_lines, tally = [], {}
    for l in lines[start:i1]:
        nl, hits = fix_line(l, dst)
        if nl is None:                   # 整行删除
            for h in hits:
                tally[h] = tally.get(h, 0) + 1
            continue
        for h in hits:
            tally[h] = tally.get(h, 0) + 1
        body_lines.append(nl)

    body = "\n".join(body_lines)
    body = collapse_hr(body)
    body = re.sub(r"^\s*---\s*\n+", "", body)          # 去掉开头的孤立分隔线
    body = re.sub(r"\n{3,}", "\n\n", body)
    body = body.strip()

    if title:                                          # 锚点截断后补回主题标题
        body = title + "\n\n" + body
        tally["补主题"] = 1

    if not has_signoff(body):                          # 无落款者补统一落款
        body += ("\n\n---\n\n**发件方**：%s · %s\n"
                 "**联系人**：%s　**邮箱**：%s　**电话**：%s\n"
                 % (BRAND, DEPT, SIGNER, MAIL, TEL))
        body = collapse_hr(body)
        tally["补落款"] = 1

    slot_check(body, dst)
    leak_check(body, dst)
    body = re.sub(r"\n+---\s*$", "", body).rstrip()   # 清掉文末孤立分隔线

    io.open(os.path.join(OUT, dst), "w", encoding="utf-8").write(body + "\n")
    report.append((dst, len(body.split("\n")), tally))

print("%-22s %6s  %s" % ("输出", "行数", "替换统计"))
print("-" * 70)
for dst, n, tally in report:
    print("%-22s %6d  %s" % (dst, n, tally if tally else "-"))
print()
print("输出目录:", OUT)
print("✅ 发件人信息已回填（署名 %s / %s / %s / %s）" % (SIGNER, DEPT, MAIL, TEL))
print("✅ 六项自检全通过：旧名 / 段锚点标题 / 内部署名 / 内部状态字段 / 待填 / 邮箱电话位 / 连续分隔线")
print("⚠️ G2 文内 `___` 与 `2026-__-__` 为【对方回填模板】，属正常保留。")