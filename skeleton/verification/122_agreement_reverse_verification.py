#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
V21「调理协议书离线签署写入通路」的**反向验证**（反证法 / 有牙齿的门禁）。

【这个脚本为什么必须存在】

  V21 是本仓第五条「不建表、只建 PL/pgSQL 原语」的迁移（V19 之后），
  与前四条相比它**多承担一件事**：它的静态判据载体**曾经选错**，
  而选错的后果是「判据存在、每次都打印通过、但从来没有生效」。

  本轮（122）在 V21 上抓出了**三处真实缺陷**（全部实测，见下），
  其中前两处是**静默假绿**，第三处让**整个验收判据不可判**。

【🛑🛑 本脚本抓出的真实缺陷（三条，全部实测）】

  **缺陷 ①：(b5)/(b6) 被【同一函数体里 RAISE 消息的字符串字面量】满足 ⇒ 静默假绿。**

  函数体末尾那条跨租户撞号的 RAISE，其消息里逐字写着
  `INSERT ... ON CONFLICT (agreement_id) DO NOTHING 被【主键冲突】拦下`（给运维看的解释文本）。
  而旧判据的载体是 `substring(v_reg_body ... for 3000)` —— 一个**固定长度窗口**。

  实测（本脚本 I(b6-a) / I(b6-b)）：
      真正的 INSERT 语句在函数体的 2206 字符处结束，而窗口开到 3000
      ⇒ 那条 RAISE 消息**被包进了判据的载体** ⇒
      把真正的 `ON CONFLICT (agreement_id)` 改成 `ON CONFLICT (tenant_id, agreement_id)`
      （**错误改法**：使跨租户撞号的 RAISE 分支成为死代码）后，
      **(b6) 仍被那条消息字面量满足 ⇒ 自证照常"通过"**（静默假绿）。
      改一个**语义等价**的写法（`ON CONFLICT ON CONSTRAINT agreement_pkey`）
      ⇒ 判据不匹配 ⇒ 报 (b6)（假红）。

  ⇒ 处置：① 新增 (b0) 统一构造**剥注释代码态**（v_reg_code / v_lat_code / v_reg_nocomment）；
           ② 语句切片改用**语义终点**（`split_part(..., ';', 1)`），不再是拍出来的 3000；
           ③ 全部静态形态判据的载体切到代码态。

  **缺陷 ②：(c1) 被【同一函数体的注释】满足 ⇒ 静默假绿。**

  latest_agreement_of 的注释里逐字写着
      `` -- 🛑 同时写 `tenant_id = p_tenant_id`：让这条 SELECT 的… ``
  而旧判据的载体是 v_lat_body（**prosrc 原文**，含注释）。

  实测（本脚本 I(c1-a)）：删掉真正的 `WHERE tenant_id = p_tenant_id`
  ⇒ **(c1) 仍被注释满足 ⇒ 自证照常"通过"**。
  （实测计数：注释命中 1 处、代码命中 1 处，两态都为 True。）

  ⇒ 处置：载体切到 v_lat_code（剥注释）。与 (b9) 同款的"可读性即安全"纪律，
     在这里被**注释的存在本身**架空了 —— 注释写了这句话，判据就以为代码做到了。

  **缺陷 ③：自证成功时【不打印任何标记】⇒ 本脚本的 C1 判据恒假（验收不可判）。**

  V20 在 (f2) 之后有一条 `RAISE NOTICE 'V20 自证通过: …'`，V21 初版**没有**。
  成功时的 psql 输出是六个句子（DO / CREATE FUNCTION ×2 / DO / INSERT 0 0 / DO），
  **没有任何一个字说明自证跑过**。

  后果（按严重度）：
      ① 本脚本 C1（原样重跑必须全绿）的判据是 `ok and "V21 自证通过" in out`
         ⇒ **恒假** ⇒ 会报一条**归因错误**的红（"自证没通过"），
         而真相是"通过了但没说话"；
      ② "跑过了"与"根本没跑"**同形**：若 DO 块被误删/被条件包住，输出逐字相同；
      ③ 运维不可判：生产上跑迁移的人无法回答"自证过了吗"。

  ⇒ 处置：新增 (g) 成功标记，内容是"通过了**什么**"（本域特有项逐条列出），
     而不只是四个字。

【V21 相对 V20 的三处特有形态 —— 本脚本的重点】

  · **plan_version 门禁（V21 独有）**：agreement 有复合外键 (plan_id, plan_version)
    → plan(plan_id, version) ⇒ "方案存在但那一版不存在"必须被判为**不存在**。
    缺了它，一份写着 plan_version=99 的协议会绕过函数层检查，
    然后以一条 **23503** 撞复合外键（归因质量退化）。
    ⇒ 静态判据 (b14) + 行为判据 (e4)/(e5)。用例 I(e4) / I(e5) 逐一钉住。
    🛑 (e5) 的形态更细：plan_version=0 必须以**业务错误**被拒，
      而**不是** 23514（表 CHECK `ck_agreement_plan_version_positive`）。

  · **模板指针成对 + 版本相符（V21 独有）**：doc_template 的主键是**单列**
    template_id ⇒ 每个版本是一行、有自己的 id ⇒ doc_template_version 是**冗余副本**，
    判据必须是"它等于该行的 version"，而不是"存在某一版叫这个名字"。
    ⇒ 用例 I(b14) / I(e12)。

  · **latest_agreement_of 按 signed_at 全序（V21 独有）**：离线协议可以"先签、后补录"
    ⇒ 若按 created_at 排序，"最新"会变成"最新录入"。而 G1 的取数口径是**签署时刻**。
    ⇒ 本脚本用 I(c2-a)（去掉 tie-breaker）/ I(c2-b)（改成 created_at）两半钉住。

【用例矩阵】（共 28 条）

  注入组（必须报错，且【首报错的标签】必须对上）
    I(a0)  换成 BYPASSRLS 角色跑                                → (a0)
    I(a1)  把 agreement 的 RLS 策略改成 USING(true)              → (a1)
    I(a)   删掉 register_agreement                              → (a)
    I(c)   删掉 latest_agreement_of                             → (a)
    I(b1)  去掉 set_config(app.tenant_id, ..., true)            → (b1)
    I(b2)  去掉 assert_tenant_context()                         → (b2)
    I(b3)  去掉 current_setting(app.tenant_id) 一致性守卫        → (b3)
    I(b5)  从 INSERT 里摘掉 ON CONFLICT                         → (b5)
    I(b6-a) 🎯 `(agreement_id)` → `(tenant_id, agreement_id)`（错误改法） → (b6)
    I(b6-b) 🎯 `(agreement_id)` → `ON CONSTRAINT agreement_pkey`（等价改法） → 必须**全绿**
    I(b6-c) 🎯 `(agreement_id)` → `(agreement_id, tenant_id)`    → (b6)
    I(b6-d) 🎯 **只改 INSERT、保留 DECLARE 里的推断目标**（旧判据的欺骗形态） → (b6)
    I(b9)  让 INSERT 不含 tenant_id（列与值同步删）              → (b9)
    I(b7-a) 🎯 从四方键集里去掉 store_owner（硬门禁削弱）         → (b7)
    I(b7-b) 🎯 给四方键集**加一个第五方**（witness）              → (b7b)
    I(b8-a) 🎯 把 V20 的 v_warn_gate_keys 结构照抄进来            → (b8)
    I(b8-b) 只写 warn_gate 这个词                                → (b8)
    I(b12) 把 hash 正则退回只收小写 `^[0-9a-f]{64}$`             → (b12)
    I(b13) 删掉 INSERT 里的 lower(p_rendered_hash)               → (b13)
    I(b14) 摘掉模板指针成对判定                                  → (b14)
    I(b10) 给函数体加一条 UPDATE agreement                       → (b10)
    I(b11) 给函数体加一条 DELETE FROM agreement                  → (b11)
    I(c1-a) 🎯 删掉 `WHERE tenant_id = p_tenant_id`（旧载体含注释 ⇒ 修复前假绿） → (c1)
    I(c2-a) 去掉 ORDER BY 的 tie-breaker                         → (c2)
    I(c2-b) 把排序键 signed_at 改成 created_at                   → (c2)
    I(c3)  去掉 LIMIT 1                                          → (c3)
    I(d)   抹掉迁移登记行                                        → (d)
    I(e4)  让方案版本存在性判定恒真（v_plan 判定失效）            → (e4)
    I(e5)  让 plan_version<1 的显式判定失效（落到表 CHECK，报 23514）→ (e5)
    I(e12) 摘掉模板版本相符判定                                  → (e12)
    I(e14) 摘掉跨租户撞号的 RAISE（改成 return ALREADY_EXISTS）    → (e14)
    I(e16) 把 latest 的排序改成 created_at（函数体级）            → (e16)
    I(e18) 摘掉读函数的上下文一致性守卫                          → (e18)
    I(g)   🎯 抹掉成功标记 NOTICE ⇒ C1 必须变红（判据自证）        → C1 变红

  对照组（必须仍然全绿 —— 防"判据收紧后误伤等价形态"）
    C1 原样重跑 V21（且必须含"V21 自证通过"字样）                → 必须全绿
    C2 给两个函数体首尾各加空行                                  → 必须全绿
    C3 在 DECLARE 段加一个未使用的变量                            → 必须全绿
    C4 🎯 用 `ON CONFLICT ON CONSTRAINT agreement_pkey` 重写真正的 INSERT → 必须全绿
    C5 🎯 `WHERE p_tenant_id = tenant_id`（谓词左右交换，等价）  → 必须全绿
    C6 🎯 给注释里加一句含 `v_warn_gate_keys` 的解释文字          → 必须全绿（剥注释生效）

  元门禁
    C7 🛑🛑 **自证块内不得有直接作用在 prosrc 原文上的正则判据**
       （本仓第 44 条缺陷的常驻守卫；本轮实测两处复发）
    C8 全部失败分支字面量必须都在迁移文本里                       → 防"删掉自证也算通过"
    C9 🎯 代码态载体必须**从彼此派生**（v_reg_nocomment 从 v_reg_code 派生），
       不得各自 `regexp_replace(v_reg_body)`（防"两个不同的量"）
    C10 🛑🛑 **迁移文本里不得出现自己的美元引用标签字面量**
       （含注释里 —— 实测会让整条迁移语法错误）

【🛑 本脚本自带的"前提对账"】

  开跑前先验证：库中 V21 的 Flyway checksum 是否 == 当前迁移文本的复算值。
  V21 **尚未登记进 flyway_schema_history**（psql 直连应用的迁移不走 Flyway）
  ⇒ 本脚本不把"未登记"当成失败，而是把它显式打印出来，并要求：
     · 若已登记，则两者必须相等；
     · 若未登记，则**必须先由收口动作登记**（`INSERT INTO flyway_schema_history …`），
       且登记值 = 当前复算值 —— 本脚本打印出该 SQL，由 run log 记录。

【与 116 ~ 121 的分工】
  116 = V16 迁移【逻辑】是否正确（跨租户引用完整性）。
  117 = 迁移【文本】与已应用【库】是否一致。
  118 = V17 迁移【自证】是否真的有牙齿（手环绑定通路）。
  119 = V18 迁移【自证】是否真的有牙齿（设备建档通路）。
  120 = V19 迁移【自证】是否真的有牙齿（量表建档通路）。
  121 = V20 迁移【自证】是否真的有牙齿（结案归档通路）。
  122 = V21 迁移【自证】是否真的有牙齿（协议书离线签署通路），本轮本文件。
        🛑 122 与 118~121 的**一处差别**：V21 的缺陷不在"判据写得对不对"，
           而在"判据的**载体**选错了"（原文 vs 代码态）。
           ⇒ 故 122 多了一整组元门禁（C7/C9/C10）专门守载体。

用法:
  python verification/122_agreement_reverse_verification.py
  python verification/122_agreement_reverse_verification.py --keep   # 保留临时库排查
环境变量: DY_PSQL_EXE / DY_PG_HOST / DY_PG_PORT / DY_PG_USER / DY_PG_PASSWORD
          DY_PG_SUPER_USER / DY_PG_SUPER_PASSWORD / DY_PG_DB / DY_AGREEMENT_REV_DB
"""
import os
import re
import subprocess
import sys
import tempfile
import zlib
from pathlib import Path

PSQL = os.environ.get("DY_PSQL_EXE", r"C:/Program Files/PostgreSQL/17/bin/psql.exe")
HOST = os.environ.get("DY_PG_HOST", "127.0.0.1")
PORT = os.environ.get("DY_PG_PORT", "5432")

APP_USER = os.environ.get("DY_PG_USER", "diaoyuanyun")
APP_PWD = os.environ.get("DY_PG_PASSWORD", "diaoyuanyun")
SUPER_USER = os.environ.get("DY_PG_SUPER_USER", "postgres")
SUPER_PWD = os.environ.get("DY_PG_SUPER_PASSWORD", "postgres")

DEV_DB = os.environ.get("DY_PG_DB", "diaoyuanyun_dev")
REV_DB = os.environ.get("DY_AGREEMENT_REV_DB", "diaoyuanyun_agreement122")

HERE = Path(__file__).resolve().parent                     # verification
SKELETON = HERE.parent                                     # skeleton
MIGRATION_DIR = SKELETON / "dy-app" / "src" / "main" / "resources" / "db" / "migration"
MIGRATION = MIGRATION_DIR / "V21__agreement_offline_signing_provisioning.sql"

# 🛑 V21 的"改前文本"基线：它**不在** git 里（V21 从未提交），
#    也不在 flyway_schema_history 里（psql 直连应用的迁移不走 Flyway）。
#    故本轮**没有**机械可证的"改前那一份" —— 这是与 121 的一处实质差别。
#    ⇒ 本脚本因此**不主张**"改前/改后 DDL 净效果相同"（那需要改前文本的机械身份）。
#      替代做法：C8 系列只证明"**改后的 DDL 净效果**与**真库实际状态**一致" ——
#      真库是那条"改后文本被应用过"的机械证据（psql apply log 的 EXIT=0）。
BASELINE = None

# 🛑 迁移成功标记（缺陷 ③ 修复后新增；C1 的判据依赖它）
OK_MARKER = "V21 自证通过"

# 🛑 自证块的美元引用标签（不加 $）
GUARD_TAG = "v21_guard"
REG_TAG = "v21_register"
LAT_TAG = "v21_latest"


def env_with(pwd: str) -> dict:
    return dict(os.environ, PGPASSWORD=pwd)


def psql(user: str, pwd: str, db: str, args, timeout: int = 900):
    cmd = [PSQL, "-h", HOST, "-p", PORT, "-U", user, "-d", db, "-X"] + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env=env_with(pwd), timeout=timeout)


def migration_scripts():
    """按【数值】版本号排序 —— 字符串排序会把 V10 排在 V2 前面（与 117~121 同款）。"""
    files = sorted(MIGRATION_DIR.glob("V*__*.sql"),
                   key=lambda p: int(re.match(r"V(\d+)__", p.name).group(1)))
    if not files:
        raise SystemExit(f"❌ 未在 {MIGRATION_DIR} 找到任何 V*__*.sql")
    return files


def flyway_checksum(path: Path) -> int:
    """复算 Flyway 的 checksum（与 117~121 同口径，CRC32 逐行 UTF-8 有符号）。"""
    text = path.read_text(encoding="utf-8")
    if text.startswith("\ufeff"):
        text = text[1:]
    crc = 0
    for line in text.splitlines():
        crc = zlib.crc32(line.encode("utf-8"), crc)
    return crc - 2 ** 32 if crc >= 2 ** 31 else crc


# ---------------------------------------------------------------------------
# 迁移文本的切片工具
# ---------------------------------------------------------------------------

def open_tag(tag: str) -> str:
    return "$" + tag + "$"


def func_body(mig: str, tag: str) -> str:
    """从迁移文本里切出 `$<tag>$ ... $<tag>$;` 之间的原文。

    🛑 为什么能从【文件文本】切，而不是从库里读：
       119 已用探针证明 —— 库中 `pg_proc.prosrc` 与这里的切片【逐字相同】。
       PG 把 `AS $tag$ ... $tag$` 之间的内容原样存进 prosrc ⇒
       "按文件文本构造注入"与"库里实际存的函数体"是同一个对象。
    """
    t = open_tag(tag)
    a = mig.index(t) + len(t)
    b = mig.index(t + ";", a)
    return mig[a:b]


def replace_func_body(mig: str, tag: str, old: str, new: str, expect: int = 1) -> str:
    """替换函数体里的一段（精确匹配）。expect 用于钉住"注入真的生效了"。"""
    body = func_body(mig, tag)
    n = body.count(old)
    if n != expect:
        raise SystemExit(
            f"❌ 注入锚点在 {tag} 函数体里出现 {n} 次（期望 {expect}）：{old[:90]!r}\n"
            f"   ⇒ 迁移文本的结构已变，本脚本的锚点失效，必须先修脚本。")
    return mig.replace(body, body.replace(old, new), 1)


def replace_func_body_all(mig: str, tag: str, old: str, new: str, expect: int = 1) -> str:
    """同上，但把函数体内【全部】匹配都换掉。"""
    body = func_body(mig, tag)
    n = body.count(old)
    if n != expect:
        raise SystemExit(
            f"❌ 注入锚点在 {tag} 函数体里出现 {n} 次（期望 {expect}）：{old[:90]!r}")
    return mig.replace(body, body.replace(old, new), 1)


def replace_in_mig(mig: str, old: str, new: str, expect: int = 1) -> str:
    """替换迁移文本里的一段（不限于函数体）。"""
    n = mig.count(old)
    if n != expect:
        raise SystemExit(
            f"❌ 注入锚点在迁移文本里出现 {n} 次（期望 {expect}）：{old[:90]!r}\n"
            f"   ⇒ 迁移文本的结构已变，本脚本的锚点失效，必须先修脚本。")
    return mig.replace(old, new, 1)


def inject_before_guard(mig: str, injection: str) -> str:
    """🛑 把注入插到自证块【之前】。

    追加在文件末尾等于"考完试再改答案" —— 那时自证已经跑完并成功，
    注入的错误没有任何自证去抓，于是整组用例假失败（116 第一版踩过）。
    """
    t = open_tag(GUARD_TAG)
    # 文件里有两处该标签（开、闭）⇒ 取**第一处**（开标签前的 DO）
    i = mig.index(t)
    # 再往前找到该 DO 的起始（保证注入落在 DO 之外、但在 DO 之前）
    m = re.search(r"\nDO\s*\n" + re.escape(t), mig)
    if not m:
        raise SystemExit(f"❌ 未在迁移中找到 {t} 自证块 —— 迁移结构已变，"
                         "本脚本的注入锚点失效，必须先修脚本。")
    head, guard_and_rest = mig[:m.start()], mig[m.start():]
    return (head
            + "\n-- ===== 反向验证注入（位于自证块之前，故自证【有机会】抓住它）=====\n"
            + injection
            + "\n-- ===== 注入结束 =====\n"
            + guard_and_rest)


def run_sql(sql: str, as_super: bool = False, timeout: int = 300):
    """在一个事务里跑一段 SQL，返回 (是否成功, 合并输出)。

    🛑 整个文件包在 BEGIN … ROLLBACK 里：注入的 DDL 绝不落地到已应用库。
    """
    user, pwd = (SUPER_USER, SUPER_PWD) if as_super else (APP_USER, APP_PWD)
    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False,
                                     encoding="utf-8", newline="") as f:
        f.write("\\set ON_ERROR_STOP on\nBEGIN;\n")
        f.write(sql)
        f.write("\nROLLBACK;\n")
        path = f.name
    try:
        p = subprocess.run([PSQL, "-h", HOST, "-p", PORT, "-U", user, "-d", DEV_DB,
                            "-X", "-f", path, "-v", "ON_ERROR_STOP=on"],
                           capture_output=True, text=True, encoding="utf-8",
                           errors="replace", env=env_with(pwd), timeout=timeout)
        return p.returncode == 0, (p.stdout or "") + (p.stderr or "")
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass


# ---------------------------------------------------------------------------
# 临时库：用于"整条链能否应用"的端到端验证
# ---------------------------------------------------------------------------

def rebuild_db(db: str):
    r = psql(SUPER_USER, SUPER_PWD, "postgres", [
        "-c", f"DROP DATABASE IF EXISTS {db} WITH (FORCE);",
        "-c", f"CREATE DATABASE {db} OWNER {APP_USER};",
    ])
    if r.returncode != 0:
        raise SystemExit("❌ 无法重建临时库（需要超级用户通道）：\n"
                         + (r.stdout or "") + (r.stderr or ""))


def apply_chain(db: str, v21_text: str):
    """把【整条迁移链】应用到临时库，其中 V21 用传入的文本（其余用磁盘原文）。

    🛑 为什么必须用【整条链】：V21 的自证依赖 V1 建的 tenant/customer/plan/doc_template、
       V5 建的 agreement 及其 RLS、V16 的复合外键、以及 assert_tenant_context() 等 ——
       只应用一条会在前置就报「表 agreement 不存在」。
    🛑 `newline=""` 不是可选项：Windows 上 NamedTemporaryFile("w") 默认把 `\n` 转成
       `\r\n` ⇒ 喂给 psql 的文本与磁盘原文不再逐字相同 ⇒ 重建库里的
       pg_proc.prosrc 变成 `\r\r\n` 形态 ⇒ 净效果比对报出**归因错误**的红。
    """
    scripts = migration_scripts()
    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False,
                                     encoding="utf-8", newline="") as f:
        f.write("\\set ON_ERROR_STOP on\n")
        for p in scripts:
            text = v21_text if p.name == MIGRATION.name else p.read_text(encoding="utf-8")
            f.write(f"\n\\echo '-- chain apply: {p.name} --'\n")
            f.write(text)
            f.write("\n")
        path = f.name
    try:
        r = psql(APP_USER, APP_PWD, db, ["-1", "-v", "ON_ERROR_STOP=on", "-f", path])
        if r.returncode != 0:
            raise SystemExit(f"❌ 迁移链在 {db} 应用失败：\n"
                             + (r.stdout or "")[-3000:] + (r.stderr or "")[-3000:])
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass


def ddl_effect(db: str) -> str:
    """V21 的「DDL 净效果」指纹。

    🛑 指纹 = V21 实际产生/改变的四类东西：
         ① 函数签名（proname + 参数 + 返回类型）
         ② 函数体原文（prosrc）—— V21 唯一有内容的产出
         ③ 函数 EXECUTE 权限（第 2 节 GRANT 段的效果）
         ④ schema_migration 中 V21 一行的 description（第 3 节登记段的效果）
       V21 第 0 节是前置检查、第 4 节是自证、第 5 节是注释 ⇒ 四类覆盖 1/2/3 节。
    """
    sql = """
    SELECT 'fn|' || p.proname || '|' || pg_get_function_arguments(p.oid) || '|' ||
           pg_get_function_result(p.oid) || '|' || md5(p.prosrc)
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname IN ('register_agreement','latest_agreement_of')
    UNION ALL
    SELECT 'prosrc|' || p.proname || '|' || p.prosrc
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname IN ('register_agreement','latest_agreement_of')
    UNION ALL
    SELECT 'priv|' || p.proname || '|' ||
           COALESCE(array_to_string(p.proacl,' | '),'(NULL)') || '|' ||
           has_function_privilege(current_user, p.oid, 'EXECUTE')::text
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname IN ('register_agreement','latest_agreement_of')
    UNION ALL
    SELECT 'reg|V21|' || COALESCE(description,'')
      FROM schema_migration WHERE version = 'V21'
    """
    r = psql(SUPER_USER, SUPER_PWD, db, ["-A", "-t", "-c", sql])
    if r.returncode != 0:
        raise SystemExit(f"❌ DDL 净效果快照失败({db})：\n" + (r.stderr or ""))
    return "\n".join(x for x in (r.stdout or "").splitlines() if x.strip())


def main():
    keep = "--keep" in sys.argv
    if not MIGRATION.is_file():
        print(f"❌ 找不到被测迁移: {MIGRATION}")
        return 2
    mig = MIGRATION.read_text(encoding="utf-8")

    print("== 122 V21 协议书离线签署写入通路 · 反向验证 ==")
    print(f"   被测迁移 : {MIGRATION.name}")
    print(f"   行为验证 : {DEV_DB}（注入包在 BEGIN…ROLLBACK 里，不落地）")
    print(f"   端到端   : {REV_DB}（临时库，整条链应用）")
    ck_now = flyway_checksum(MIGRATION)
    print(f"   当前文本 : checksum={ck_now}  len={len(mig)}  "
          f"body_reg={len(func_body(mig, REG_TAG))}  body_lat={len(func_body(mig, LAT_TAG))}")
    print()

    # ------------------------------------------------------------------
    # 前提对账（🛑 V21 的一处实质差别：它**没有机械可证的改前基线**）
    # ------------------------------------------------------------------
    r = psql(APP_USER, APP_PWD, DEV_DB, [
        "-A", "-t", "-c",
        "SELECT checksum FROM flyway_schema_history WHERE version = '21';"])
    db_cks = (r.stdout or "").strip()
    r2 = psql(APP_USER, APP_PWD, DEV_DB, [
        "-A", "-t", "-c",
        "SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
        "WHERE n.nspname='public' AND p.proname IN ('register_agreement','latest_agreement_of');"])
    fn_cnt = (r2.stdout or "").strip()
    print(f"   真库对账 : flyway V21 checksum = {db_cks or '(未登记)'}；"
          f"两函数在 pg_proc 中 = {fn_cnt} 个")
    if db_cks and db_cks == str(ck_now):
        print(f"              ✅ 已登记且与当前文本一致")
    else:
        print(f"   🛑 待收口 : 真库 flyway_schema_history 里 V21 "
              f"{'未登记' if not db_cks else '登记的 checksum=' + db_cks + ' ≠ 当前复算=' + str(ck_now)}")
        print(f"      ⇒ 收口动作（在 C8 证明'当前文本的净效果 == 真库实际状态'之后执行）：")
        print(f"        INSERT INTO flyway_schema_history "
              f"(installed_rank, version, description, type, script, checksum, installed_by, "
              f"execution_time, success)")
        print(f"        SELECT COALESCE(max(installed_rank),0)+1, '21', "
              f"'agreement offline signing primitive', 'SQL', "
              f"'V21__agreement_offline_signing_provisioning.sql', {ck_now}, "
              f"current_user, 0, true FROM flyway_schema_history;")
        print(f"      🛑 为什么必须登记：V21 此前是用 psql 直连应用的（那是本仓验收入口），")
        print(f"         而 Flyway 通道**看不到**它 ⇒ 下一次 spring 启动会尝试重跑 V21。")
        print(f"         V21 是幂等的（CREATE OR REPLACE + ON CONFLICT），但 Flyway 会先报")
        print(f"         『Detected resolved migration not applied to database』"
              f"（或 out-of-order）⇒ 启动被拦。")
    print(f"   🛑 基线说明 : V21 **不在 git**（从未提交）、**不在** flyway_schema_history")
    print(f"      ⇒ 本脚本**不主张**'改前/改后 DDL 净效果相同'（没有机械可证的'改前那一份'）。")
    print(f"      替代：证明'当前文本的净效果' == '真库实际状态'（见 C8）。")
    print()

    # 前提现场
    r = psql(SUPER_USER, SUPER_PWD, DEV_DB, [
        "-A", "-t", "-F", " | ", "-c",
        "SELECT rolname, rolsuper::text, rolbypassrls::text FROM pg_roles "
        "WHERE rolname IN ('diaoyuanyun','postgres') ORDER BY rolname;",
        "-c",
        "SELECT c.relname, c.relrowsecurity::text, c.relforcerowsecurity::text, "
        "pg_get_userbyid(c.relowner) FROM pg_class c JOIN pg_namespace n "
        "ON n.oid = c.relnamespace WHERE n.nspname='public' AND c.relname='agreement';",
        "-c",
        "SELECT tablename, policyname, "
        "CASE WHEN qual LIKE '%app.tenant_id%' THEN 'qual:tenant_id' ELSE 'qual:??' END, "
        "CASE WHEN with_check LIKE '%app.tenant_id%' THEN 'wc:tenant_id' ELSE 'wc:??' END "
        "FROM pg_policies WHERE schemaname='public' AND tablename='agreement';",
        "-c",
        "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint "
        "WHERE conrelid='public.agreement'::regclass ORDER BY contype, conname;"])
    print("   前提现场 : 角色属性 / agreement 的 RLS 与约束")
    for line in (r.stdout or "").splitlines():
        if line.strip():
            print("              " + line.strip())
    print()

    results = []

    def check(name, ok, detail):
        results.append((name, ok, detail))
        print(f"[{'PASS' if ok else 'FAIL'}] {name}")
        if not ok:
            print("       " + detail.replace("\n", "\n       ")[:2200])

    # 全部失败标签（按 V21 实际 RAISE 的字面量；含 (b0b)/(b0c)/(g) 的新增）
    ALL_LABELS = [
        "V21 自证失败(a0)", "V21 自证失败(a1)", "V21 自证失败(a)",
        "V21 自证失败(b)", "V21 自证失败(b0b)", "V21 自证失败(b0c)",
        "V21 自证失败(b1)", "V21 自证失败(b2)", "V21 自证失败(b3)",
        "V21 自证失败(b4)", "V21 自证失败(b5)", "V21 自证失败(b6)",
        "V21 自证失败(b7)", "V21 自证失败(b7b)", "V21 自证失败(b8)",
        "V21 自证失败(b9)", "V21 自证失败(b10)", "V21 自证失败(b11)",
        "V21 自证失败(b12)", "V21 自证失败(b13)", "V21 自证失败(b14)",
        "V21 自证失败(c)", "V21 自证失败(c1)", "V21 自证失败(c2)",
        "V21 自证失败(c3)", "V21 自证失败(c9)",
        "V21 自证失败(d)", "V21 自证失败(d2)", "V21 自证失败(d3)",
        "V21 自证失败(e1)", "V21 自证失败(e2)", "V21 自证失败(e3)",
        "V21 自证失败(e4)", "V21 自证失败(e5)", "V21 自证失败(e6)",
        "V21 自证失败(e7)", "V21 自证失败(e8)", "V21 自证失败(e9)",
        "V21 自证失败(e10)", "V21 自证失败(e11)", "V21 自证失败(e12)",
        "V21 自证失败(e13)", "V21 自证失败(e14)", "V21 自证失败(e15)",
        "V21 自证失败(e16)", "V21 自证失败(e17)", "V21 自证失败(e18)",
        "V21 自证失败(f)", "V21 自证失败(f2)",
    ]

    def label_of_exact(out: str, labels) -> str:
        """取首个出现的标签；位置相同时取【最长】的那个（前缀打破平局）。

        🛑 前缀陷阱核对：`V21 自证失败(b)` 是 `V21 自证失败(b1)` 的前缀
           ⇒ 必须按长度打破平局（与 121 同款）。
        """
        hits = [(out.find(lab), -len(lab), lab) for lab in labels if lab in out]
        hits = [h for h in hits if h[0] >= 0]
        return min(hits)[2] if hits else "(无标签)"

    # ------------------------------------------------------------------
    # C1 🛑 原样重跑必须全绿 —— 且**必须含成功标记**（缺陷 ③ 的守卫）
    # ------------------------------------------------------------------
    ok, out = run_sql(mig)
    check("C1 原样重跑 V21 必须全绿，且**必须打印成功标记**"
          "（缺陷③：初版成功时没有任何字说明自证跑过 ⇒ 本判据曾恒假）",
          ok and OK_MARKER in out,
          f"ok={ok} 含'{OK_MARKER}'={OK_MARKER in out}\n{out}")

    # ------------------------------------------------------------------
    # I(a0) BYPASSRLS 角色 ⇒ (a0)
    # ------------------------------------------------------------------
    ok, out = run_sql(mig, as_super=True)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(a0) 用 BYPASSRLS 角色跑 ⇒ 自证 (a0) 必须抓住（依赖 RLS 的 3 条判据的前提）",
          (not ok) and lab == "V21 自证失败(a0)",
          f"首报错标签={lab}（期望 V21 自证失败(a0)）\n{out}")

    # ------------------------------------------------------------------
    # I(a1) 把 agreement 的 RLS 策略改成 USING(true) ⇒ (a1)
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
-- 注入：把 agreement 的租户隔离策略改成"人人可见"（角色属性完全正常）
ALTER POLICY tenant_isolation ON agreement USING (true) WITH CHECK (true);
""")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(a1) 把 agreement 的 RLS 策略改成 USING(true) ⇒ 自证 (a1) 必须抓住",
          (not ok) and lab == "V21 自证失败(a1)",
          f"首报错标签={lab}（期望 V21 自证失败(a1)）\n{out}")

    # ------------------------------------------------------------------
    # I(a) 删掉 register_agreement / I(c) 删掉 latest_agreement_of ⇒ (a)
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
DROP FUNCTION register_agreement(uuid, uuid, uuid, uuid, int, jsonb,
                                 timestamptz, jsonb, text, text, jsonb, uuid, int, text);
""")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(a) 删掉 register_agreement → 自证 (a) 必须抓住",
          (not ok) and lab == "V21 自证失败(a)",
          f"首报错标签={lab}\n{out}")

    inj = inject_before_guard(mig, """
DROP FUNCTION latest_agreement_of(uuid, uuid);
""")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(c) 删掉 latest_agreement_of → 自证 (a) 必须抓住（两条函数名都在 (a) 的断言里）",
          (not ok) and lab == "V21 自证失败(a)",
          f"首报错标签={lab}\n{out}")

    # ------------------------------------------------------------------
    # I(b1) 去掉 set_config ⇒ (b1)
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, REG_TAG,
        "    PERFORM set_config('app.tenant_id', p_tenant_id::text, true);\n"
        "\n"
        "    -- (3b) 自证上下文确实生效",
        "    PERFORM 1;\n"
        "\n"
        "    -- (3b) 自证上下文确实生效", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b1) 去掉 register 的 set_config(app.tenant_id, ..., true) → 自证 (b1) 必须抓住",
          (not ok) and lab == "V21 自证失败(b1)",
          f"首报错标签={lab}（期望 V21 自证失败(b1)）\n{out}")

    # ------------------------------------------------------------------
    # I(b2) 去掉 assert_tenant_context() ⇒ (b2)
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, REG_TAG, "    v_ctx := assert_tenant_context();",
        "    v_ctx := p_tenant_id::text;", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b2) 去掉 register 的 assert_tenant_context() → 自证 (b2) 必须抓住",
          (not ok) and lab == "V21 自证失败(b2)",
          f"首报错标签={lab}（期望 V21 自证失败(b2)）\n{out}")

    # ------------------------------------------------------------------
    # I(b3) 去掉 current_setting 一致性守卫 ⇒ (b3)
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, REG_TAG,
        "    v_ctx_before := current_setting('app.tenant_id', true);",
        "    v_ctx_before := NULL;", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b3) 去掉 register 的 current_setting(app.tenant_id) 一致性守卫 → 自证 (b3) 必须抓住",
          (not ok) and lab == "V21 自证失败(b3)",
          f"首报错标签={lab}（期望 V21 自证失败(b3)）\n{out}")

    # ------------------------------------------------------------------
    # I(b5) 摘掉 ON CONFLICT ⇒ (b5)
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, REG_TAG, "    ON CONFLICT (agreement_id) DO NOTHING;",
        "    ;", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b5) 从 INSERT 里摘掉 ON CONFLICT（语法仍合法）→ 自证 (b5) 必须抓住",
          (not ok) and lab == "V21 自证失败(b5)",
          f"首报错标签={lab}（期望 V21 自证失败(b5)）\n{out}")

    # ------------------------------------------------------------------
    # I(b6-a) 🎯 核心用例之一：`(agreement_id)` → `(tenant_id, agreement_id)`
    #   🛑 修复前：判据载体是 `for 3000` 的窗口 ⇒ 被 RAISE 消息的字面量满足
    #      ⇒ 自证照常"通过"（静默假绿：跨租户 RAISE 已成死代码而无人报红）。
    #      修复后：载体 = 代码态 + 语句切片 ⇒ 报 (b6)。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, REG_TAG, "    ON CONFLICT (agreement_id) DO NOTHING;",
        "    ON CONFLICT (tenant_id, agreement_id) DO NOTHING;", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b6-a) 🎯 把推断目标改成 (tenant_id, agreement_id)（使跨租户 RAISE 成死代码）"
          "→ 自证 (b6) 必须抓住（修复前为静默假绿）",
          (not ok) and lab == "V21 自证失败(b6)",
          f"首报错标签={lab}（期望 V21 自证失败(b6)）\n{out}")

    # ------------------------------------------------------------------
    # I(b6-b) 🎯 等价形态：`ON CONFLICT ON CONSTRAINT agreement_pkey` ⇒ 必须全绿
    # ------------------------------------------------------------------
    eq = replace_func_body(
        mig, REG_TAG, "    ON CONFLICT (agreement_id) DO NOTHING;",
        "    ON CONFLICT ON CONSTRAINT agreement_pkey DO NOTHING;", expect=1)
    ok, out = run_sql(eq)
    check("I(b6-b) 🎯 用 `ON CONFLICT ON CONSTRAINT agreement_pkey`（语义等价的正确写法）"
          "→ 必须仍然全绿（判据不得过度收紧）",
          ok and OK_MARKER in out, out)

    # ------------------------------------------------------------------
    # I(b6-c) `(agreement_id)` → `(agreement_id, tenant_id)`
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, REG_TAG, "    ON CONFLICT (agreement_id) DO NOTHING;",
        "    ON CONFLICT (agreement_id, tenant_id) DO NOTHING;", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b6-c) 把推断目标改成 (agreement_id, tenant_id) → 自证 (b6) 必须抓住",
          (not ok) and lab == "V21 自证失败(b6)",
          f"首报错标签={lab}（期望 V21 自证失败(b6)）\n{out}")

    # ------------------------------------------------------------------
    # I(b6-d) 🎯 只改 INSERT、保留"判定依据"消息里的推断目标
    #   🛑 这条是本轮缺陷①的**最小复现**：把真正的 INSERT 改掉，而在别处
    #      （消息字面量里）**保留正确的推断目标** —— 旧判据会被那处满足。
    #      修复后（载体=语句切片）：必须报 (b6)。
    #      若某天这条变绿，说明有人把载体退回"整个函数体"。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, REG_TAG, "    ON CONFLICT (agreement_id) DO NOTHING;",
        "    ON CONFLICT (tenant_id, agreement_id) DO NOTHING;", expect=1)
    # 在函数体末尾再塞一句"看起来正确"的消息（模拟旧载体被满足的形态）
    inj_body = replace_func_body(
        inj_body, REG_TAG,
        "        p_agreement_id, p_tenant_id::text;",
        "        p_agreement_id, p_tenant_id::text;\n"
        "    -- 🛑 注入：冗余消息（旧载体 = 整个函数体 时会被它满足）\n"
        "    PERFORM 1; -- ON CONFLICT (agreement_id) DO NOTHING", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b6-d) 🎯 只改真 INSERT、而在消息里保留正确的推断目标"
          "（旧载体的欺骗形态）→ 自证 (b6) 必须抓住",
          (not ok) and lab == "V21 自证失败(b6)",
          f"首报错标签={lab}（期望 V21 自证失败(b6)）\n{out}")

    # ------------------------------------------------------------------
    # I(b9) INSERT 不含 tenant_id ⇒ (b9)
    # ------------------------------------------------------------------
    old_cols = ("    INSERT INTO agreement (agreement_id, tenant_id, customer_id,\n"
                "                           plan_id, plan_version,")
    new_cols = ("    INSERT INTO agreement (agreement_id, customer_id,\n"
                "                           plan_id, plan_version,")
    old_vals = ("    VALUES (p_agreement_id, p_tenant_id, p_customer_id,\n"
                "            p_plan_id, p_plan_version,")
    new_vals = ("    VALUES (p_agreement_id, p_customer_id,\n"
                "            p_plan_id, p_plan_version,")
    inj_body = replace_func_body(mig, REG_TAG, old_cols, new_cols, expect=1)
    inj_body = replace_func_body(inj_body, REG_TAG, old_vals, new_vals, expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b9) 从 INSERT 列清单里删掉 tenant_id（列与值同步删，语法仍合法）→ 自证 (b9) 必须抓住",
          (not ok) and lab == "V21 自证失败(b9)",
          f"首报错标签={lab}（期望 V21 自证失败(b9)）\n{out}")

    # ------------------------------------------------------------------
    # I(b7-a) 🛑 从四方键集里去掉 store_owner ⇒ (b7)
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, REG_TAG,
        "        'therapist',           -- 调理师（PRD §八 第 3 方）\n"
        "        'store_owner'          -- 门店负责人（PRD §八 第 4 方）\n"
        "    ];",
        "        'therapist'            -- 调理师（PRD §八 第 3 方）\n"
        "    ];", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b7-a) 🎯 从四方键集里去掉 store_owner（硬门禁削弱）→ 自证 (b7) 必须抓住",
          (not ok) and lab == "V21 自证失败(b7)",
          f"首报错标签={lab}（期望 V21 自证失败(b7)）\n{out}")

    # ------------------------------------------------------------------
    # I(b7-b) 🎯 加一个第五方（witness）⇒ (b7b)
    #   🛑 子串匹配挡不住扩张：(b7) 只要求"四个都在"，五个也满足 ⇒ 需要 (b7b)。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, REG_TAG,
        "        'store_owner'          -- 门店负责人（PRD §八 第 4 方）\n"
        "    ];",
        "        'store_owner',         -- 门店负责人（PRD §八 第 4 方）\n"
        "        'witness'              -- 注入：凭空加一个第五方\n"
        "    ];", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b7-b) 🎯 给四方键集加一个第五方（witness）→ 自证 (b7b) 必须抓住"
          "（(b7) 的子串匹配挡不住扩张）",
          (not ok) and lab == "V21 自证失败(b7b)",
          f"首报错标签={lab}（期望 V21 自证失败(b7b)）\n{out}")

    # ------------------------------------------------------------------
    # I(b8-a) 🛑🛑 把 V20 的 v_warn_gate_keys 结构照抄进来 ⇒ (b8)
    #   🛑 这是"看起来更安全"的改动 —— 而 PRD §八 四方全部是硬门禁，
    #      宽免是**凭空造出来的**。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, REG_TAG,
        "    v_ctx_before     text;\n"
        "    v_ctx            text;",
        "    v_ctx_before     text;\n"
        "    v_ctx            text;\n"
        "    v_warn_gate_keys text[] := ARRAY['store_owner'];  -- 🛑 注入：照抄 V20 的宽免结构",
        expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b8-a) 🎯 把 V20 的 v_warn_gate_keys 结构照抄进来（凭空造宽免）"
          "→ 自证 (b8) 必须抓住",
          (not ok) and lab == "V21 自证失败(b8)",
          f"首报错标签={lab}（期望 V21 自证失败(b8)）\n{out}")

    inj_body = replace_func_body(
        mig, REG_TAG,
        "    v_ctx_before     text;\n"
        "    v_ctx            text;",
        "    v_ctx_before     text;\n"
        "    v_ctx            text;\n"
        "    v_x              int := length('warn_gate');   -- 注入：代码态里出现 warn_gate",
        expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b8-b) 只写 warn_gate 这个词（不带 v_ 前缀）→ 自证 (b8) 必须抓住",
          (not ok) and lab == "V21 自证失败(b8)",
          f"首报错标签={lab}（期望 V21 自证失败(b8)）\n{out}")

    # ------------------------------------------------------------------
    # I(b12) hash 正则退回只收小写 ⇒ (b12)
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, REG_TAG,
        "    IF p_rendered_hash !~ '^[0-9a-fA-F]{64}$' THEN",
        "    IF p_rendered_hash !~ '^[0-9a-f]{64}$' THEN", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b12) 把 hash 正则退回只收小写 `^[0-9a-f]{64}$`（lower() 成死代码）"
          "→ 自证 (b12) 必须抓住",
          (not ok) and lab == "V21 自证失败(b12)",
          f"首报错标签={lab}（期望 V21 自证失败(b12)）\n{out}")

    # ------------------------------------------------------------------
    # I(b13) 删掉 lower(p_rendered_hash) ⇒ (b13)
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, REG_TAG, "            p_rendered_snapshot, lower(p_rendered_hash),",
        "            p_rendered_snapshot, p_rendered_hash,", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b13) 删掉 INSERT 里的 lower(p_rendered_hash)（与 (b12) 是成对判据）"
          "→ 自证 (b13) 必须抓住",
          (not ok) and lab == "V21 自证失败(b13)",
          f"首报错标签={lab}（期望 V21 自证失败(b13)）\n{out}")

    # ------------------------------------------------------------------
    # I(b14) 摘掉模板指针成对判定 ⇒ (b14)
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, REG_TAG,
        "    IF (p_doc_template_id IS NULL) <> (p_doc_template_version IS NULL) THEN",
        "    IF false THEN", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b14) 摘掉模板指针的成对判定 → 自证 (b14) 必须抓住",
          (not ok) and lab == "V21 自证失败(b14)",
          f"首报错标签={lab}（期望 V21 自证失败(b14)）\n{out}")

    # ------------------------------------------------------------------
    # I(b10) / I(b11) 改写通路 ⇒ (b10) / (b11)
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, REG_TAG, "    GET DIAGNOSTICS v_affected = ROW_COUNT;",
        "    UPDATE agreement SET created_by = created_by WHERE agreement_id = p_agreement_id;\n"
        "    GET DIAGNOSTICS v_affected = ROW_COUNT;", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b10) 给函数体加一条 `UPDATE agreement`（改写通路）→ 自证 (b10) 必须抓住",
          (not ok) and lab == "V21 自证失败(b10)",
          f"首报错标签={lab}（期望 V21 自证失败(b10)）\n{out}")

    inj_body = replace_func_body(
        mig, REG_TAG, "    GET DIAGNOSTICS v_affected = ROW_COUNT;",
        "    DELETE FROM agreement WHERE agreement_id = '00000000-0000-0000-0000-000000000000';\n"
        "    GET DIAGNOSTICS v_affected = ROW_COUNT;", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b11) 给函数体加一条 `DELETE FROM agreement`（抹掉举证材料）→ 自证 (b11) 必须抓住",
          (not ok) and lab == "V21 自证失败(b11)",
          f"首报错标签={lab}（期望 V21 自证失败(b11)）\n{out}")

    # ------------------------------------------------------------------
    # I(c1-a) 🎯 核心用例之二：删掉 `WHERE tenant_id = p_tenant_id`
    #   🛑 修复前：载体是 v_lat_body（含注释），而注释里逐字写着该谓词
    #      ⇒ 删掉真谓词后仍被注释满足（静默假绿）。修复后：报 (c1)。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, LAT_TAG,
        "     WHERE tenant_id = p_tenant_id\n       AND customer_id = p_customer_id",
        "     WHERE customer_id = p_customer_id", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(c1-a) 🎯 删掉 latest 的 `WHERE tenant_id = p_tenant_id`"
          "（旧载体含注释 ⇒ 修复前假绿）→ 自证 (c1) 必须抓住",
          (not ok) and lab == "V21 自证失败(c1)",
          f"首报错标签={lab}（期望 V21 自证失败(c1)）\n{out}")

    # ------------------------------------------------------------------
    # I(c2-a) 去掉 tie-breaker ⇒ (c2)
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, LAT_TAG, "     ORDER BY signed_at DESC, agreement_id DESC",
        "     ORDER BY signed_at DESC", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(c2-a) 去掉 ORDER BY 的 tie-breaker `agreement_id DESC`（语义变成非确定）"
          "→ 自证 (c2) 必须抓住",
          (not ok) and lab == "V21 自证失败(c2)",
          f"首报错标签={lab}（期望 V21 自证失败(c2)）\n{out}")

    # ------------------------------------------------------------------
    # I(c2-b) 排序键 signed_at → created_at ⇒ (c2)
    #   🛑 本域特有：离线补录会让"最新录入"与"最新签署"分叉。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, LAT_TAG, "     ORDER BY signed_at DESC, agreement_id DESC",
        "     ORDER BY created_at DESC, agreement_id DESC", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(c2-b) 🎯 把排序键从 signed_at（业务时刻）改成 created_at（登记时刻）"
          "→ 自证 (c2) 必须抓住",
          (not ok) and lab == "V21 自证失败(c2)",
          f"首报错标签={lab}（期望 V21 自证失败(c2)）\n{out}")

    # ------------------------------------------------------------------
    # I(c3) 去掉 LIMIT 1 ⇒ (c3)
    # ------------------------------------------------------------------
    inj_body = replace_func_body(mig, LAT_TAG, "     LIMIT 1;", "     ;", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(c3) 去掉 latest_agreement_of 的 LIMIT 1 → 自证 (c3) 必须抓住",
          (not ok) and lab == "V21 自证失败(c3)",
          f"首报错标签={lab}（期望 V21 自证失败(c3)）\n{out}")

    # ------------------------------------------------------------------
    # I(d) 抹掉登记行 ⇒ (d)
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
DELETE FROM schema_migration WHERE version = 'V21';
""")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(d) 抹掉迁移登记行 → 自证 (d) 必须抓住",
          (not ok) and lab == "V21 自证失败(d)",
          f"首报错标签={lab}\n{out}")

    # ------------------------------------------------------------------
    # I(e4) 让方案版本存在性判定恒真 ⇒ (e4)
    #   🛑 注入形态：把 `IF v_plan <> 1 THEN` 改成 `IF false THEN`
    #      ⇒ 函数不拒 ⇒ 落到复合外键上（23503）⇒ 行为断言 (e4) 必抓。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, REG_TAG, "    IF v_plan <> 1 THEN", "    IF false THEN", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(e4) 让方案版本存在性判定恒真（IF v_plan <> 1 → IF false）"
          "→ 自证 (e4) 必须抓住（库层复合外键会兜底抛 23503）",
          (not ok) and lab == "V21 自证失败(e4)",
          f"首报错标签={lab}（期望 V21 自证失败(e4)）\n{out}")

    # ------------------------------------------------------------------
    # I(e5) 让 plan_version < 1 的显式判定失效 ⇒ (e5)（且必须是 23514 的面目）
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, REG_TAG, "    IF p_plan_version < 1 THEN", "    IF false THEN", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(e5) 摘掉 plan_version<1 的显式判定 ⇒ 落到表 CHECK（报 23514）"
          "→ 自证 (e5) 必须抓住（归因质量）",
          (not ok) and lab == "V21 自证失败(e5)",
          f"首报错标签={lab}（期望 V21 自证失败(e5)）\n{out}")

    # ------------------------------------------------------------------
    # I(e12) 摘掉模板版本相符判定 ⇒ (e12)
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, REG_TAG,
        "        IF v_tpl_version IS DISTINCT FROM p_doc_template_version THEN",
        "        IF false THEN", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(e12) 摘掉模板版本相符判定（指针说 v9、模板自报 v1 却放行）"
          "→ 自证 (e12) 必须抓住",
          (not ok) and lab == "V21 自证失败(e12)",
          f"首报错标签={lab}（期望 V21 自证失败(e12)）\n{out}")

    # ------------------------------------------------------------------
    # I(e14) 🎯 靶心用例：摘掉跨租户撞号的 RAISE（幂等判定无条件 RETURN）
    #   🛑 静态判据 (b4)/(b5)/(b6) 全部保持成立（RAISE 语句还在）⇒
    #      只有行为断言 (e14) 能抓。这是"静态断言证明结构、行为断言证明效果"。
    # ------------------------------------------------------------------
    old_e14 = ("    IF v_mine = 1 THEN\n"
               "        RETURN 'ALREADY_EXISTS';\n"
               "    END IF;")
    inj_body = replace_func_body(
        mig, REG_TAG, old_e14, "    RETURN 'ALREADY_EXISTS';", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(e14) 🎯 把幂等判定改成无条件 RETURN『ALREADY_EXISTS』"
          "（跨租户 RAISE 成死代码，静态判据全过）→ 自证 (e14) 必须抓住",
          (not ok) and lab == "V21 自证失败(e14)",
          f"首报错标签={lab}（期望 V21 自证失败(e14)）\n{out}")

    # ------------------------------------------------------------------
    # I(e16) 把 latest 的排序改成 created_at（函数体级）⇒ (e16)
    #   🛑 与 I(c2-b) 的区别：I(c2-b) 被**静态**判据 (c2) 抓住（先报）；
    #      本条把静态判据也一起改掉，使**只有行为断言 (e16)** 能抓 ——
    #      这证明 (e16) 的"刻意让最后写入的是中间时间"那个构造真的有判别力。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, LAT_TAG, "     ORDER BY signed_at DESC, agreement_id DESC",
        "     ORDER BY created_at DESC, agreement_id DESC", expect=1)
    #   🛑🛑 放宽**静态判据本体**：把 (c2) 的 IF 条件改成恒假。
    #      初版替换的是那条 RAISE EXCEPTION 的**消息文本**，
    #      而 IF 条件没动 ⇒ 判据仍然触发，只是消息变了 ⇒
    #      报出的标签变成注入文本里的"(无标签)" ⇒ 归因错误的红。
    #      ⇒ 必须改条件本身：`IF v_lat_code !~* '...' THEN` → `IF false THEN`。
    inj_body = replace_in_mig(
        inj_body,
        "    IF v_lat_code !~* 'ORDER\\s+BY\\s+signed_at\\s+DESC\\s*,\\s*agreement_id\\s+DESC' THEN",
        "    IF false THEN   -- 注入：静态判据 (c2) 已放宽", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(e16) 🎯 放宽静态判据 (c2) 后，把排序改成 created_at "
          "⇒ 必须由**行为断言 (e16)** 单独抓住",
          (not ok) and lab == "V21 自证失败(e16)",
          f"首报错标签={lab}（期望 V21 自证失败(e16)）\n"
          f"🛑 若这一条为 FAIL 而只是【全绿】，说明 (e16) 从未真正生效。\n{out}")

    # ------------------------------------------------------------------
    # I(e18) 摘掉读函数的上下文一致性守卫 ⇒ (e18)
    #   🛑🛑 必须**同时**放宽静态断言 (b3) 的读侧分支，否则首报错是 (b3) 而非 (e18)
    #      ⇒ 归因错误的红（"一次注入、一条根因，却有两个标签在抢"）。
    #      这与 I(e16) 是同一手法：要证明**行为断言**有判别力，
    #      就必须把会先报的静态断言一并放宽，让行为断言单独上场。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, LAT_TAG,
        "    v_ctx_before := current_setting('app.tenant_id', true);",
        "    v_ctx_before := NULL;", expect=1)
    inj_body = replace_in_mig(
        inj_body,
        "    IF v_lat_code !~ 'current_setting\\s*\\(\\s*''app\\.tenant_id''' THEN",
        "    IF false THEN   -- 注入：静态判据 (b3) 读侧已放宽", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(e18) 摘掉 latest_agreement_of 的上下文一致性守卫"
          "→ 自证 (e18) 必须抓住（读函数也会静默改写调用方上下文）",
          (not ok) and lab == "V21 自证失败(e18)",
          f"首报错标签={lab}（期望 V21 自证失败(e18)）\n{out}")

    # ------------------------------------------------------------------
    # I(g) 🎯 抹掉成功标记 ⇒ C1 必须变红（判据自身有判别力）
    #   🛑🛑 注入形态必须把**整条** NOTICE 语句拿掉，而不是只把它开头注释掉：
    #      V21 的这条 NOTICE 是**跨多行的隐式字符串拼接**（相邻字符串字面量连接）。
    #      初版注入只改了第一行 ⇒ 剩下几行变成"悬空的字符串字面量" ⇒
    #      报的是**语法错误**而不是"(g) 消失" ⇒ 归因错误的红。
    #      ⇒ 现改为：用正则把从 `RAISE NOTICE 'V21 自证通过:` 到该语句的
    #        结束分号之间的**整段**替换为空语句。
    # ------------------------------------------------------------------
    m_notice = re.search(r"\n\s*RAISE NOTICE 'V21 自证通过:.*?;\n", mig, re.S)
    if not m_notice:
        raise SystemExit("❌ I(g) 找不到 V21 的成功标记 NOTICE —— 脚本锚点失效")
    inj = mig[:m_notice.start()] + "\n    PERFORM 1;  -- 注入：成功标记已抹掉\n" + mig[m_notice.end():]
    ok, out = run_sql(inj)
    check("I(g) 🎯 抹掉成功标记 NOTICE ⇒ 'V21 自证通过' 必须消失"
          "（证明 C1 的判据真的有判别力，而不是恒真）",
          ok and OK_MARKER not in out,
          f"ok={ok} 含'{OK_MARKER}'={OK_MARKER in out}（期望 ok=True 且不含）\n{out}")

    # ------------------------------------------------------------------
    # C2 等价形态：函数体首尾加空行 ⇒ 必须全绿
    # ------------------------------------------------------------------
    eq = mig.replace(func_body(mig, REG_TAG),
                     "\n" + func_body(mig, REG_TAG) + "\n\n", 1)
    eq = eq.replace(func_body(eq, LAT_TAG),
                    "\n" + func_body(eq, LAT_TAG) + "\n\n", 1)
    ok, out = run_sql(eq)
    check("C2 函数体首尾加空行（等价形态）→ 必须仍然全绿",
          ok and OK_MARKER in out, out)

    # ------------------------------------------------------------------
    # C3 等价形态：DECLARE 段加一个未使用变量 ⇒ 必须全绿
    # ------------------------------------------------------------------
    eq = replace_func_body(mig, REG_TAG, "    v_ctx_before     text;",
                           "    v_ctx_before     text;\n    v_noise          int := 0;", expect=1)
    ok, out = run_sql(eq)
    check("C3 在 DECLARE 段加一个未使用变量（等价形态）→ 必须仍然全绿",
          ok and OK_MARKER in out, out)

    # ------------------------------------------------------------------
    # C4 🎯 用约束名推断重写真正的 INSERT ⇒ 必须全绿
    # ------------------------------------------------------------------
    eq = replace_func_body(
        mig, REG_TAG,
        "    ON CONFLICT (agreement_id) DO NOTHING;\n\n    GET DIAGNOSTICS v_affected = ROW_COUNT;",
        "    ON CONFLICT ON CONSTRAINT agreement_pkey DO NOTHING;\n\n"
        "    GET DIAGNOSTICS v_affected = ROW_COUNT;", expect=1)
    ok, out = run_sql(eq)
    check("C4 🎯 用 `ON CONFLICT ON CONSTRAINT agreement_pkey`（另一种等价推断写法）"
          "→ 必须仍然全绿",
          ok and OK_MARKER in out, out)

    # ------------------------------------------------------------------
    # C5 🎯 谓词左右交换（`p_tenant_id = tenant_id`）⇒ 必须全绿
    # ------------------------------------------------------------------
    eq = replace_func_body(
        mig, LAT_TAG, "     WHERE tenant_id = p_tenant_id\n       AND customer_id = p_customer_id",
        "     WHERE p_tenant_id = tenant_id\n       AND customer_id = p_customer_id", expect=1)
    ok, out = run_sql(eq)
    check("C5 🎯 把 latest 的谓词左右交换（`p_tenant_id = tenant_id`，语义等价）"
          "→ 必须仍然全绿（判据不得过度收紧）",
          ok and OK_MARKER in out, out)

    # ------------------------------------------------------------------
    # C6 🎯 给注释加一句含 `v_warn_gate_keys` 的解释文字 ⇒ 必须全绿
    #   🛑 这条证明"剥注释"在 (b8) 上**真的生效**：注释里出现该词不该报红。
    #      （V21 本来就有一条这样的注释，本条再补一条更强的。）
    # ------------------------------------------------------------------
    eq = replace_func_body(
        mig, REG_TAG,
        "    v_ctx_before     text;\n"
        "    v_ctx            text;",
        "    v_ctx_before     text;\n"
        "    v_ctx            text;\n"
        "    -- 🛑 注入：注释里写 v_warn_gate_keys / warn_gate / _warn_key —— 不得报红\n"
        "    v_noise_c6       int := 0;", expect=1)
    ok, out = run_sql(eq)
    check("C6 🎯 在注释里写 `v_warn_gate_keys` / `warn_gate` / `_warn_key`"
          "→ 必须仍然全绿（证明 (b8) 的剥注释真的生效，不得假红）",
          ok and OK_MARKER in out,
          f"🛑 若这条报 (b8)，说明载体退回含注释态 ⇒ 一条正确实现会假红。\n{out}")

    # ------------------------------------------------------------------
    # C7 🛑🛑 元门禁：自证块内不得有直接作用在 prosrc 原文上的正则判据
    #   🛑 这是本仓第 44 条缺陷的**常驻守卫**（本轮实测两处复发）。
    #   实现：对**迁移文本**扫源（这是库层做不到的事，见 V21 自证 (c9) 的分工说明）。
    # ------------------------------------------------------------------
    def raw_body_uses(text: str):
        """返回自证块内"直接作用在 prosrc 原文上"的违规行。

        🛑 允许的原文用途（且只有这四种 —— 都是"不构成形态判据"的用法）：
            ① `length(v_reg_body)`        —— 长度比较（比长度不需要剥注释）
            ② `v_reg_body IS [NOT] NULL`  —— NULL 判断
            ③ `regexp_replace(v_reg_body)`—— 构造代码态本身
            ④ `v_reg_body text;` / `prosrc INTO v_reg_body`
                                         —— 变量声明、从 pg_proc 取值
        🛑 本函数被 C7（真判据）与 C7b（自证有判别力）**共用** ——
           共用本身也是纪律："自证"必须与"被判据"用同一套口径（否则自证无效）。
        """
        guard_src = text[text.index(open_tag(GUARD_TAG)):]
        out = []
        for m in re.finditer(r"(v_reg_body|v_lat_body)", guard_src):
            ls = guard_src.rfind("\n", 0, m.start()) + 1
            le = guard_src.find("\n", m.start())
            line = guard_src[ls:le if le > 0 else len(guard_src)]
            if "--" in line:
                continue                      # 注释里的提及不算
            if re.search(r"(length\s*\(\s*(v_reg_body|v_lat_body)\s*\)"
                         r"|(v_reg_body|v_lat_body)\s+IS\s+(NOT\s+)?NULL"
                         r"|regexp_replace\s*\(\s*(v_reg_body|v_lat_body)"
                         r"|(v_reg_body|v_lat_body)\s+text\s*;"
                         r"|prosrc\s+INTO\s+(v_reg_body|v_lat_body))", line):
                continue                      # 四种允许用途：跳过
            out.append(line.strip())
        return out

    raw_uses = raw_body_uses(mig)
    check("C7 🛑🛑 元门禁：自证块内不得有直接作用在 prosrc 原文上的正则判据"
          "（第 44 条缺陷的常驻守卫；本轮实测两处复发）",
          not raw_uses,
          f"违规行 =\n" + "\n".join(raw_uses) +
          "\n🛑 修复目标：静态形态判据的载体必须是 v_reg_code / v_lat_code / v_reg_nocomment / v_slice。"
          "\n   允许的原文用途：length(v_reg_body)（长度比较）、IS NULL 判断、"
          "构造代码态的 regexp_replace、变量声明与 prosrc INTO 取值。")

    # ------------------------------------------------------------------
    # C7b 元门禁自证：C7 的判据必须**真的能抓住**违规
    #   🛑 "判据存在但从不生效"正是本轮的主题 ⇒ C7 自己也要被反向验证。
    # ------------------------------------------------------------------
    fake = mig.replace(
        "    IF v_reg_code !~ 'INSERT\\s+INTO\\s+agreement' THEN",
        "    IF v_reg_body !~ 'INSERT\\s+INTO\\s+agreement' THEN", 1)
    fake_raw = raw_body_uses(fake)
    check("C7b 元门禁自证：把 (b4) 的载体退回 v_reg_body ⇒ C7 必须能抓住"
          "（证明 C7 不是一条永远为真的判据）",
          len(fake_raw) >= 1,
          f"篡改后 C7 检出的违规行数 = {len(fake_raw)}（期望 ≥1）")

    # ------------------------------------------------------------------
    # C8 元门禁：全部失败分支字面量必须都在迁移文本里
    # ------------------------------------------------------------------
    need = [f"V21 自证失败({x})" for x in [
        "a0", "a1", "a", "b", "b0b", "b0c", "b1", "b2", "b3", "b4", "b5", "b6",
        "b7", "b7b", "b8", "b9", "b10", "b11", "b12", "b13", "b14",
        "c", "c1", "c2", "c3", "d", "d2", "d3",
        "e1", "e2", "e3", "e4", "e5", "e6", "e7", "e8", "e9", "e10", "e11",
        "e12", "e13", "e14", "e15", "e16", "e17", "e18", "f", "f2"]]
    missing = [s for s in need if s not in mig]
    check("C8 全部自证失败分支字面量必须都在迁移文本里（防'删掉自证也算通过'）",
          not missing, f"缺失: {missing}")

    # ------------------------------------------------------------------
    # C9 🎯 元门禁：代码态载体必须**从彼此派生**
    #   🛑 v_reg_nocomment 必须由 v_reg_code 派生（一个 regexp_replace 链），
    #      不得各自 `regexp_replace(v_reg_body)` —— 那又是"两个不同的量"。
    # ------------------------------------------------------------------
    ok_c9 = ("v_reg_nocomment := regexp_replace(v_reg_code" in mig
             and "v_reg_code := regexp_replace(v_reg_body" in mig
             and "v_lat_code := regexp_replace(v_lat_body" in mig)
    # 🛑 "各自从原文派生"的违规形态：出现第二次 `regexp_replace(v_reg_body)`。
    #   🛑🛑 必须**排除注释行** —— 本判据初版直接在整份迁移文本上跑正则，
    #      于是把 (b0)/(c9) 的**说明性注释**里那句
    #      `不得各自 \`regexp_replace(v_reg_body)\`` 也数成了一次 ⇒ 假红。
    #      这与第 44 条是同一族（注释被当成代码），只是这次伤的是**元门禁自己**。
    offenders_c9 = [m.group(0) for line in mig.splitlines()
                    if not line.strip().startswith("--")
                    for m in re.finditer(r"regexp_replace\s*\(\s*v_reg_body[^;]*;", line)]
    check("C9 🎯 代码态载体必须从彼此派生（v_reg_nocomment ← v_reg_code）"
          "，不得各自 regexp_replace(v_reg_body)（防'两个不同的量'）",
          ok_c9 and len(offenders_c9) == 1,
          f"三处派生是否齐备={ok_c9}；"
          f"regexp_replace(v_reg_body…) 出现次数={len(offenders_c9)}（期望 1）\n"
          f"逐条 = {offenders_c9}")

    # ------------------------------------------------------------------
    # C10 🛑🛑 元门禁：迁移文本里不得出现**自己的美元引用标签字面量**
    #   （含注释里 —— 实测会让整条迁移语法错误：PG 词法分析在识别注释之前
    #     就先扫美元引用标签 ⇒ 注释里的标签会提前终止 DO 块）
    # ------------------------------------------------------------------
    own_tags = [open_tag(t) for t in (REG_TAG, LAT_TAG, GUARD_TAG, "v21_precond", "v21_grant")]
    counts = {t: mig.count(t) for t in own_tags}
    # 每个标签**恰好** 2 次（开 + 闭）；多于 2 说明文件里另有字面量（注释/消息里）
    bad_tags = {t: c for t, c in counts.items() if c != 2}
    check("C10 🛑🛑 迁移文本里不得出现自己的美元引用标签字面量（含注释里）"
          "——实测会让整条迁移语法错误（PG 在识别注释之前先扫标签）",
          not bad_tags,
          f"标签出现次数异常（应为 2：开+闭）= {bad_tags}\n"
          f"🛑 修复：把注释/消息里的标签改写成「自证块」等描述性说法，"
          f"不要写出标签字面量。")

    # ------------------------------------------------------------------
    # C10b 元门禁自证：C10 的判据必须能抓住违规
    # ------------------------------------------------------------------
    fake2 = mig.replace("-- ==================================================================\n"
                        "    -- (a0) 🛑🛑 能力守卫一",
                        "-- 注入：" + open_tag(GUARD_TAG) + " 段内\n"
                        "    -- (a0) 🛑🛑 能力守卫一", 1)
    c2 = fake2.count(open_tag(GUARD_TAG))
    check("C10b 元门禁自证：往注释里塞一个裸标签 ⇒ C10 必须能抓住",
          c2 == 3,
          f"篡改后该标签出现次数 = {c2}（期望 3 = 2+1）")

    # ------------------------------------------------------------------
    # C11 🎯 端到端：整条迁移链在干净库里应用必须成功（含 V21 的当前文本）
    #   🛑 与前面全部用例的区别：那些都在**已应用的库**上把注入包在 ROLLBACK 里跑；
    #      本条验的是"从零建库"这条通道 —— 它是 Flyway 真实走的通道。
    #      本仓教训：**"迁移能被 psql 应用到已应用的库"≠"迁移能在干净库上跑通"**
    #      （V21 的 (f2) 清场自证与复合外键链正是只有从零建库才暴露的那类问题）。
    # ------------------------------------------------------------------
    print()
    print("   [C11] 用当前文本重建临时库并应用整条链 …")
    c11_ok = True
    c11_detail = ""
    try:
        rebuild_db(REV_DB)
        apply_chain(REV_DB, mig)
        eff = ddl_effect(REV_DB)
        c11_ok = ("fn|register_agreement" in eff
                  and "fn|latest_agreement_of" in eff
                  and "reg|V21|" in eff)
        c11_detail = "净效果指纹片段:\n" + "\n".join(eff.splitlines()[:6])
    except SystemExit as e:
        c11_ok = False
        c11_detail = str(e)
    check("C11 🎯 整条迁移链在【干净库】上应用必须成功（V21 的 (f2) 清场自证 + "
          "两条复合外键链只有从零建库才暴露）",
          c11_ok, c11_detail)

    # ------------------------------------------------------------------
    # C12 🎯 无 HTTP 组件（A-1 硬边界：只做离线通路）
    #   🛑 契约 H1 receiveEsignCallback 被**逐字禁止**由本批实现 ——
    #      那等于开一条"未验签回调即可自证已签"的通道。
    #      本判据证明"本批真的没有加任何 HTTP 映射"。
    # ------------------------------------------------------------------
    java_root = SKELETON / "dy-app" / "src" / "main" / "java" / "com" / "diaoyuanyun" / "dy" / "app" / "agreement"
    java_files = sorted(java_root.rglob("*.java")) if java_root.is_dir() else []
    http_annos = ["RestController", "Controller", "RequestMapping", "PostMapping",
                  "PutMapping", "PatchMapping", "DeleteMapping", "GetMapping"]
    offenders = []
    for jf in java_files:
        txt = jf.read_text(encoding="utf-8")
        txt = re.sub(r"/\*.*?\*/", "", txt, flags=re.S)
        txt = re.sub(r"//[^\n]*", "", txt)
        for a in http_annos:
            if re.search(r"@(\w+\.)*" + a + r"\b", txt):
                offenders.append(f"{jf.name}: @{a}")
    check("C12 🎯 agreement 包内不得出现任何 HTTP 映射注解"
          "（A-1 硬边界：只做离线运维通路；H1 回调被逐字禁止）",
          not offenders,
          f"违规 = {offenders}\n"
          f"🛑 H1 receiveEsignCallback = 一条'未验签回调即可自证已签'的通道 ⇒ 禁止。")

    # ------------------------------------------------------------------
    # C13 🎯 无改写通路（本域红线：协议是举证材料）
    #   🛑 本判据与 (b10)/(b11) 的区别：那两条查**库里的函数体**，
    #      本条查**应用层的 Java 源码** —— 任何"UPDATE/DELETE agreement"的
    #      应用层通路都等于绕过库层红线。
    # ------------------------------------------------------------------
    app_offenders = []
    for jf in java_files:
        txt = jf.read_text(encoding="utf-8")
        txt = re.sub(r"/\*.*?\*/", "", txt, flags=re.S)
        txt = re.sub(r"//[^\n]*", "", txt)
        for m in re.finditer(r"\b(UPDATE|DELETE\s+FROM)\s+agreement\b", txt, re.I):
            app_offenders.append(f"{jf.name}: {m.group(0)}")
    check("C13 🎯 agreement 包的应用层源码里不得出现 UPDATE / DELETE agreement"
          "（协议是举证材料，只提供'登记新的一份'）",
          not app_offenders, f"违规 = {app_offenders}")

    # ------------------------------------------------------------------
    print()
    passed = sum(1 for _, k, _ in results if k)
    for name, k, d in results:
        print(f"  {'[OK]' if k else '[XX]'} {name}")
    print()
    print(f"===== 122 反向验证结果: {passed}/{len(results)} 通过 =====")
    print()
    if passed == len(results):
        print("⇒ 结论：V21 的每一类自证都【有牙齿】：")
        print("   · 注入的每一类错误都被【对应标签】的自证抓住（首报错标签精确对上）；")
        print("   · 语义等价的正确形态仍然全绿（判据没有变成过度收紧的误伤）；")
        print("   · 静态形态判据全部作用在【剥注释后的代码态】上（C7 常驻断言 + C7b 自证）；")
        print("   · 代码态载体从彼此派生，不是'两个不同的量'（C9）；")
        print("   · 迁移文本里没有自己的美元引用标签字面量（C10 + C10b）；")
        print("   · 整条链能在【干净库】上从零跑通（C11 —— Flyway 真实走的通道）；")
        print("   · 本批真的没有引入任何 HTTP 通路与改写通路（C12/C13，A-1 硬边界）。")
        print()
        print("  🛑 本轮由此修掉了 V21 的三处真实缺陷（前两处为静默假绿）：")
        print("     ① (b5)/(b6) 的载体是 `for 3000` 的固定长度窗口 ⇒ 被同一函数体内")
        print("        那条跨租户 RAISE 的**消息字面量**满足 ⇒ 把 ON CONFLICT 改成")
        print("        (tenant_id, agreement_id) 后自证照常'通过'（跨租户 RAISE 成死代码）。")
        print("        已改为：剥注释代码态 + 语句切片（split_part 到分号）。")
        print("     ② (c1) 的载体是 prosrc 原文 ⇒ 被同一函数体的**注释**满足")
        print("        （注释里逐字写着 `tenant_id = p_tenant_id`）⇒ 删掉真谓词后仍'通过'。")
        print("        已改为：v_lat_code（剥注释）。")
        print("     ③ 自证成功时**不打印任何标记** ⇒ C1 的判据恒假（会报归因错误的红），")
        print("        且'跑过了'与'根本没跑'同形。已新增 (g) 成功标记，")
        print("        内容列出本域特有项（不是四个字）。")
    else:
        print("⇒ 🛑 结论：存在未通过项，V21 的自证尚不可信 —— 不得据此宣称缺口已收口。")
    if keep:
        print(f"\n（--keep：临时库 {REV_DB} 已保留）")
    else:
        psql(SUPER_USER, SUPER_PWD, "postgres",
             ["-c", f"DROP DATABASE IF EXISTS {REV_DB} WITH (FORCE);"])
        print(f"\n（临时库 {REV_DB} 已清理）")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())