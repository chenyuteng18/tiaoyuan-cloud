#!/usr/bin/env python3
# -*- coding: utf-8 -*-
r"""
V20「结案归档写入通路」的**反向验证**（反证法 / 有牙齿的门禁）。

【这个脚本为什么必须存在】

  V20 是本仓第四条「不建表、只建 PL/pgSQL 原语」的迁移（V19 之后），
  其自证 (b)(c)(e) 有 30+ 条断言，分两类：

      · 静态类 —— 读 prosrc 做正则匹配（(b1)~(b12c) / (c1)~(c4)）
      · 行为类 —— 真的调用函数跑一遍，看返回值 / 数据库状态（(e1)~(e12) / (f2)）

  这两类各有自己的失效形态，而**只有"受控注入 + 断言必须报错"能证明它们测对了**：

      静态断言看着在测某件事，实际测的是另一件事（无锚点 / 被注释或字面量满足）
      行为断言看着在跑，其实是被**另一层防线**满足的（防御纵深掩盖了本层的失效）
      行为断言看着在跑，其实**只有最弱的那一种输入**被验过（强形态下假的）

  本轮（121）在 V20 上抓出了**四处真实缺陷**（见下），其中两处是**静默假绿** ——
  自证每次都打印"通过"，而判据从来没有工作过。

【🛑🛑 本脚本抓出的真实缺陷（四条，全部实测）】

  **缺陷 ①：(b6) 判据可被【函数体内 RAISE 消息的字面量】满足 ⇒ 双面失效。**

  函数体里那条跨租户撞号的 RAISE，其"判定依据"那句话**逐字**写着
  `INSERT ... ON CONFLICT (archive_id) DO NOTHING`（是给运维看的解释文本）。
  而 (b6) 的初版判据是**裸的** `ON\s+CONFLICT\s*\(\s*archive_id\s*\)` —— 无锚点。

  实测（本脚本 I(b6-a) / I(b6-b)）：
      把真正的 INSERT 改成 `ON CONFLICT (tenant_id, archive_id)`（**错误的**改法）
        ⇒ 判据被那条 RAISE 字面量满足 ⇒ **自证照常"通过"**（静默假绿：
           跨租户撞号的 RAISE 分支已成死代码，而没有任何断言报红）
      把真正的 INSERT 改成 `ON CONFLICT ON CONSTRAINT case_archive_pkey`
        （**语义等价的正确**改法）⇒ 判据不匹配 ⇒ **报 (b6)**（假红）

  ⇒ 处置：① 判据带锚点（从 `INSERT INTO case_archive` 起锚、以 `;` 为界）；
           ② 接受两种语义等价形态（列推断 / 约束名推断）—— 断言的对象是
              "推断目标不含 tenant_id 这个**语义**"，不是"某一种语法写法"；
           ③ 补一条**(b6-负向)**：锚点段内不得出现含 tenant_id 的推断目标
              （正向判据兜不住 `ON CONFLICT (archive_id, tenant_id)`）。

  **缺陷 ②：(e3) 只断言"抛了异常" ⇒ 被库层复合外键（V16 的成果）掩盖（静默假绿）。**

  实测（本脚本 I(e3-a)）：把函数里 (4) 的客户存在性检查改成 `IF false THEN`
  ⇒ INSERT 落到库层外键 `case_archive_customer_id_fkey` 上 ⇒ 收到 23503
  ⇒ 内层 EXCEPTION 捕获 ⇒ **自证照常"通过"**。

  这是本仓最典型的一族形态：**防御纵深把一条函数层判据的失效掩盖了** ——
  拒绝仍然发生，只是理由完全不同（23503 vs P0001）。而后果天差地别：
  函数层给的是可归因消息（"客户 X 在租户 Y 内不存在"+ 实际上下文值），
  外键只给一条 23503；且**函数可以被人绕过（直接写 SQL）**，归因质量只能来自函数层。

  ⇒ 处置：断言 `SQLSTATE = P0001` 且消息含"客户"。(e3) 与 (e8) 互补：
           e8 断言库层外键必须给 23503；e3 断言函数层必须给 P0001。
           同款补强一并加在 (e4)/(e6)/(e10)/(e11)（它们原先也只断言"抛了"）。

  **缺陷 ③：(e2) 的幂等重放传入**与首次完全相同**的内容 ⇒ 对 `DO UPDATE` 毫无反应。**

  实测（本脚本 I(e2-a) / I(e2-b) / I(e2-c)）：
      把 `ON CONFLICT (archive_id) DO NOTHING` 改成
      `ON CONFLICT (archive_id) DO UPDATE SET metrics_trend = excluded.metrics_trend`
      （**"用新参数改写既有证据"** —— 正是 (b10) 那条静态断言要防的事，
        而它在运行期的孪生形态）：
        修复前（两次传同样内容）⇒ 自证"通过"（UPDATE 写回的正是同样的值）
        修复后（第二次传**不同**内容）⇒ 报 (e2)

  ⇒ 处置：第二次调用传入另一份内容（5 项硬门禁保持 true 以保证能到 ON CONFLICT，
           改的是签名/结论/趋势/脱敏授权/created_by/警告项），
           然后断言"该行仍逐列等于**第一次**那份形态"（13 个特征列）。

  **缺陷 ④：(b8) 判据把"语义等价的谓词书写"判成错（假红）。**

  实测（本脚本 I(b8-a) / C5）：`WHERE p_archive_id = archive_id` 在初版判据下报 (b8)。
  ⇒ 处置：判据接受两种等价的谓词书写（断言"按 archive_id 做等值匹配"这个语义）。

【V20 相对 V19 的两处特有形态 —— 本脚本的重点】

  · **手环门禁不得反转为阻断**（合规红线，V20 独有）：
      V20 是第一个把"硬门禁 / 警告门禁"**分成两个数组**的原语，而 PRD P0-25
      与 README §5.3「三条不得触碰」③ 逐字要求
      「**任何"手环缺项反转为阻断"的写法一律违规**」。
      ⇒ 静态防线 (b12)（不得在硬门禁数组里）/ (b12c)（必须在警告数组里），
        行为防线 (e5-a)（键**缺席** ⇒ 必须成功）/ (e5-b)（值 **false** ⇒ 必须成功）。
      用例 I(b12) / I(b12c) / I(e5-a) / I(e5-b) 逐一钉住。
      🛑 这条单独立用例的理由：**"把它并进硬门禁"看起来是"更安全"的改动** ——
         它有极大的动机被做出，而它恰好违规。

  · **(a0)/(a1) 能力守卫仍是全部 RLS 判据的前提**（与 V18/V19 同型）：
      (a0) 角色不得 BYPASSRLS；(a1) 策略必须仍按 app.tenant_id 隔离。
      本自证里依赖 RLS 的判据共 4 条（(e3) 不落库 / (e6) 零行 / (e6) 对照 /
      (e12) 正向计数）。用例 I(a0) / I(a1) 把前提本身变成受控输入。

【用例矩阵】（共 45 条）

  注入组（必须报错，且【首报错的标签】必须对上）
    I(a0)  换成 BYPASSRLS 角色跑                              → (a0)
    I(a1)  把 case_archive 的 RLS 策略改成 USING(true)          → (a1)
    I(a)   删掉 register_case_archive                          → (a)
    I(b1)  去掉 set_config(app.tenant_id, ..., true)           → (b1)
    I(b2)  去掉 assert_tenant_context()                        → (b2)
    I(b3)  去掉 current_setting(app.tenant_id) 一致性守卫        → (b3)
    I(b5)  从 INSERT 里摘掉 ON CONFLICT                         → (b5)
    I(b6-a) 🎯 `(archive_id)` → `(tenant_id, archive_id)`（错误改法） → (b6)
    I(b6-b) 🎯 `(archive_id)` → `ON CONSTRAINT case_archive_pkey`（等价改法） → 必须**全绿**
    I(b6-c) 🎯 `(archive_id)` → `(archive_id, tenant_id)`        → (b6)
           🛑 正向判据执行顺序在前 ⇒ 首报错是 (b6)（而非 (b6-负向)）；
              (b6-负向) 的**独立判别力**由 I(b6-c2) 单独证明。
    I(b6-c2) 🎯 放宽正向判据后，仅由 (b6-负向) 抓                   → (b6-负向)
    I(b7)  把 RAISE 的"另一租户"改成"其他租户"                    → (b7)
    I(b9)  让 INSERT 不含 tenant_id（列与值同步删）               → (b9)
    I(b12-a) 🎯 把手环键**并进硬门禁数组**（合规红线）              → (b12)
    I(b12-b) 🎯 把硬门禁里的 owner_signed 换成别的键              → (b12b)
    I(b12-c) 🎯 把手环键从警告数组里**整个删掉**                  → (b12c)
    I(b12-d) 删掉硬门禁数组（锚点消失）                           → (b12)
    I(c1)  latest_archive_of 去掉 `tenant_id = p_tenant_id`      → (c1)
    I(c2)  去掉 ORDER BY 的 tie-breaker                         → (c2)
    I(c3)  去掉 LIMIT 1                                         → (c3)
    I(d)   抹掉迁移登记行                                        → (d)
    I(d3)  摘掉授权段 + 函数新建（proacl IS NULL）                → (d3)
    I(e1)  改动 e1 的断言期望值（定义段全过）                      → (e1)
    I(e3-a) 🎯 摘掉 (4) 的客户存在性检查（库层外键兜底）           → (e3)
    I(e4)  让硬门禁缺项不被拒（IF v_missing_gate → IF false）      → (e4)
    I(e5-a) 🎯 手环**缺席**时被反转为阻断（合规红线）              → (e5-a)
    I(e5-b) 🎯 手环 = **false** 时被反转为阻断（合规红线）         → (e5-b)
    I(e6-a) 🎯 幂等判定改成无条件 RETURN（RAISE 成死代码）         → (e6)
    I(e2-a) 🎯 DO NOTHING → DO UPDATE SET final_conclusion/signs  → (e2)
    I(e2-b) 🎯 DO NOTHING → DO UPDATE SET metrics_trend          → (e2)
    I(e2-c) 🎯 DO NOTHING → DO UPDATE SET desensitize_authorized → (e2)
    I(b8-a) 🎯 真正摘掉那次读（WHERE false）                      → (b8)
    I(b8-b) 让 v_cust 判定恒真（客户不存在也放行）                  → (e3)
    I(f2)  让探针清场失效（**整段清场**含 tenant 全部加 AND false）   → (f2)
           🛑 必须整段 no-op：case_archive 有复合外键指向 customer，
              只让 case_archive 清场失效的话，紧接着的 `DELETE FROM customer`
              会撞外键 ⇒ 报库层 **23503**（归因错误的红），执行流走不到 (f2)（首跑实测）；
              改成"删全表"也不行（残留被删掉 ⇒ 变绿）。
              这条同时验证了 (f2) 断言的是**清场结果**，而非"DELETE 有没有被执行"。

  对照组（必须仍然全绿 —— 防"判据收紧后误伤等价形态"）
    C1 原样重跑 V20                                              → 必须全绿
    C2 给两个函数体首尾各加空行                                   → 必须全绿
    C3 在 DECLARE 段加一个未使用的变量                             → 必须全绿
    C4 🎯 用 `ON CONFLICT ON CONSTRAINT case_archive_pkey` 重写真正的 INSERT → 必须全绿
    C5 🎯 `WHERE p_archive_id = archive_id`（谓词左右交换，等价）   → 必须全绿

  元门禁
    C6 各注入组的【首报错标签必须落在各自判据上】+ 除已声明的等价对外两两互异
       → 防"整块自证只会在第一行报错"（b6/b6neg 同报 (b6) 是设计使然，
          (b6-负向) 的独立判别力由 I(b6-c2) 专门证明）
    C7 全部失败分支字面量必须都在迁移文本里                         → 防"删掉自证也算通过"
    C8 DDL 净效果等价性（本轮弱化处置的依据）
       · 改前文本（checksum 与真库登记值相同 ⇒ 身份机械可证）与改后文本，
         其 DDL 净效果必须**完全相同**（本轮改动只落在第 4 节自证）；
       · 判别力自证：往函数体注入一处真实改动 ⇒ 净效果比对必须变红（撤销后恢复）。
    C9 🎯 全部静态语句形态判据必须作用在【剥注释后的代码态】(v_*_code) 上
       → 防有人把某条判据退回 v_*_body（那会让 (b12) 的假绿重现）

【🛑 本脚本自带的"前提对账"】

  开跑前先验证：库中 V20 的 Flyway checksum 是否 == 当前迁移文本的复算值。
  本轮**预期不一致**（121 改了自证段）⇒ 本脚本不把"不一致"当成失败，
  而是把"两账的对应关系"显式打印出来，并要求：
     · 当前文本复算值 == 改后 DDL 净效果的载体（C8 证明二者净效果相同）；
     · 改前文本的复算值 == 真库登记值（这正是"改前"身份的机械证据）。
  ⇒ 库侧的 checksum 对齐（UPDATE flyway_schema_history）作为**收口动作**，
     由 121 的 run log 与 README 记录，脚本只负责"证明可以对齐"。

【与 116 ~ 120 的分工】
  116 = V16 迁移【逻辑】是否正确（跨租户引用完整性）。
  117 = 迁移【文本】与已应用【库】是否一致。
  118 = V17 迁移【自证】是否真的有牙齿（手环绑定通路）。
  119 = V18 迁移【自证】是否真的有牙齿（设备建档通路）。
  120 = V19 迁移【自证】是否真的有牙齿（量表建档通路）。
  121 = V20 迁移【自证】是否真的有牙齿（结案归档通路），本轮本文件。

用法:
  python verification/121_case_archive_reverse_verification.py
  python verification/121_case_archive_reverse_verification.py --keep   # 保留临时库排查
环境变量: DY_PSQL_EXE / DY_PG_HOST / DY_PG_PORT / DY_PG_USER / DY_PG_PASSWORD
          DY_PG_SUPER_USER / DY_PG_SUPER_PASSWORD / DY_PG_DB / DY_ARCHIVE_REV_DB
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
REV_DB = os.environ.get("DY_ARCHIVE_REV_DB", "diaoyuanyun_archive121")

HERE = Path(__file__).resolve().parent                     # verification
SKELETON = HERE.parent                                     # skeleton
MIGRATION_DIR = SKELETON / "dy-app" / "src" / "main" / "resources" / "db" / "migration"
MIGRATION = MIGRATION_DIR / "V20__case_archive_provisioning.sql"
# 🛑 改前文本基线：其 checksum 与真库 flyway_schema_history 中 V20 的登记值相同
#    ⇒ "它就是改前那一份"这句话是**机械可证**的，不是我手写还原出来的近似物。
BASELINE = HERE / "baselines" / "V20__case_archive_provisioning.before-121.sql"
# 🛑 改前文本在真库里的登记 checksum（改前文本复算值；见 C8 的断言）
BEFORE_CHECKSUM = 2041181838


def env_with(pwd: str) -> dict:
    return dict(os.environ, PGPASSWORD=pwd)


def psql(user: str, pwd: str, db: str, args, timeout: int = 900):
    cmd = [PSQL, "-h", HOST, "-p", PORT, "-U", user, "-d", db, "-X"] + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env=env_with(pwd), timeout=timeout)


def migration_scripts():
    """按【数值】版本号排序 —— 字符串排序会把 V10 排在 V2 前面（与 117~120 同款）。"""
    files = sorted(MIGRATION_DIR.glob("V*__*.sql"),
                   key=lambda p: int(re.match(r"V(\d+)__", p.name).group(1)))
    if not files:
        raise SystemExit(f"❌ 未在 {MIGRATION_DIR} 找到任何 V*__*.sql")
    return files


def flyway_checksum(path: Path) -> int:
    """复算 Flyway 的 checksum（与 117~120 同口径，CRC32 逐行 UTF-8 有符号）。"""
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

GUARD_RE = re.compile(r"DO\s*\n\$v20_guard\$")
GRANT_RE = re.compile(r"DO\s*\n\$v20_grant\$.*?\$v20_grant\$;", re.S)

# 🛑 两个函数体的 tag（V20 用 register / latest；不是 V19 的 register / deprecate）
TAG_REG = "register"
TAG_LAT = "latest"


def func_body(mig: str, tag: str) -> str:
    """从迁移文本里切出 `$v20_<tag>$ ... $v20_<tag>$;` 之间的函数体原文。

    🛑 为什么能从【文件文本】切，而不是从库里读：
       119 已用探针证明 —— 库中 `pg_proc.prosrc` 与这里的切片【逐字相同】。
       PG 把 `AS $tag$ ... $tag$` 之间的内容原样存进 prosrc ⇒
       "按文件文本构造注入"与"库里实际存的函数体"是同一个对象。
    """
    open_tag = f"$v20_{tag}$"
    a = mig.index(open_tag) + len(open_tag)
    b = mig.index(f"$v20_{tag}$;")
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


def replace_in_mig(mig: str, old: str, new: str, expect: int = 1) -> str:
    """替换迁移文本里的一段（不限于函数体）。"""
    n = mig.count(old)
    if n != expect:
        raise SystemExit(
            f"❌ 注入锚点在迁移文本里出现 {n} 次（期望 {expect}）：{old[:90]!r}\n"
            f"   ⇒ 迁移文本的结构已变，本脚本的锚点失效，必须先修脚本。")
    return mig.replace(old, new, 1)


def inject_before_guard(mig: str, injection: str) -> str:
    """🛑 把注入插到 $v20_guard$ 自证块【之前】。

    追加在文件末尾等于"考完试再改答案" —— 那时自证已经跑完并成功，
    注入的错误没有任何自证去抓，于是整组用例假失败（116 第一版踩过）。
    """
    m = GUARD_RE.search(mig)
    if not m:
        raise SystemExit("❌ 未在迁移中找到 $v20_guard$ 自证块 —— 迁移结构已变，"
                         "本脚本的注入锚点失效，必须先修脚本。")
    head, guard_and_rest = mig[:m.start()], mig[m.start():]
    return (head
            + "\n-- ===== 反向验证注入（位于自证块之前，故自证【有机会】抓住它）=====\n"
            + injection
            + "\n-- ===== 注入结束 =====\n\n"
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
# 临时库：用于 C8「DDL 净效果等价性」
# ---------------------------------------------------------------------------

def rebuild_db(db: str):
    r = psql(SUPER_USER, SUPER_PWD, "postgres", [
        "-c", f"DROP DATABASE IF EXISTS {db} WITH (FORCE);",
        "-c", f"CREATE DATABASE {db} OWNER {APP_USER};",
    ])
    if r.returncode != 0:
        raise SystemExit("❌ 无法重建临时库（需要超级用户通道）：\n"
                         + (r.stdout or "") + (r.stderr or ""))


def apply_chain_to_template(db: str, v20_text: str):
    """把【整条迁移链】应用到临时库，其中 V20 用传入的文本（其余用磁盘原文）。

    🛑 为什么 C8 必须用【整条链】：V20 的 guard 依赖 V5 建的 case_archive 表、
       V16 的复合外键、以及 customer 的 (tenant_id,id) 唯一载体 ——
       只应用一条会在前置就报「表 case_archive 不存在」。
    🛑 `newline=""` 不是可选项：Windows 上 NamedTemporaryFile("w") 默认把 `\\n` 转成
       `\\r\\n` ⇒ 喂给 psql 的文本与磁盘原文不再逐字相同 ⇒ 重建库里的
       pg_proc.prosrc 变成 `\\r\\r\\n` 形态 ⇒ C8 的"净效果逐字比对"报出一条
       **完全归因错误**的红。（V18/V19 的净效果探针已实测踩过这条。）
    """
    scripts = migration_scripts()
    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False,
                                     encoding="utf-8", newline="") as f:
        f.write("\\set ON_ERROR_STOP on\n")
        for p in scripts:
            text = v20_text if p.name == MIGRATION.name else p.read_text(encoding="utf-8")
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
    """V20 的「DDL 净效果」指纹。

    🛑 本脚本要回答的问题很窄：**V20 这一条文本的两份版本，客观效果是否相同**。
       ⇒ 指纹 = V20 实际产生/改变的四类东西：
           ① 函数签名（proname + 参数 + 返回类型）
           ② 函数体原文（prosrc）—— V20 唯一有内容的产出
           ③ 函数 EXECUTE 权限（第 2 节 GRANT 段的效果）
           ④ schema_migration 中 V20 一行的 description（第 3 节登记段的效果）
       这四项覆盖 V20 第 1/2/3 节（第 0 节是前置检查、第 4 节是自证、第 5 节是注释）。
       🛑 本轮对 V20 的改动【只落在第 4 节自证】⇒ 这四项必须逐字不变。
    """
    sql = """
    SELECT 'fn|' || p.proname || '|' || pg_get_function_arguments(p.oid) || '|' ||
           pg_get_function_result(p.oid) || '|' || md5(p.prosrc)
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname IN ('register_case_archive','latest_archive_of')
    UNION ALL
    SELECT 'prosrc|' || p.proname || '|' || p.prosrc
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname IN ('register_case_archive','latest_archive_of')
    UNION ALL
    SELECT 'priv|' || p.proname || '|' ||
           COALESCE(array_to_string(p.proacl,' | '),'(NULL)') || '|' ||
           has_function_privilege(current_user, p.oid, 'EXECUTE')::text
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname IN ('register_case_archive','latest_archive_of')
    UNION ALL
    SELECT 'reg|V20|' || COALESCE(description,'')
      FROM schema_migration WHERE version = 'V20'
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
    if not BASELINE.is_file():
        print(f"❌ 找不到改前文本基线: {BASELINE}")
        return 2
    before = BASELINE.read_text(encoding="utf-8")

    print("== 121 V20 结案归档写入通路 · 反向验证 ==")
    print(f"   被测迁移 : {MIGRATION.name}")
    print(f"   行为验证 : {DEV_DB}（注入包在 BEGIN…ROLLBACK 里，不落地）")
    print(f"   等价验证 : {REV_DB}（临时库，仅用于 C8）")
    ck_after = flyway_checksum(MIGRATION)
    ck_before = flyway_checksum(BASELINE)
    print(f"   改后文本 : checksum={ck_after}  len={len(mig)}  "
          f"body_reg={len(func_body(mig, TAG_REG))}  body_lat={len(func_body(mig, TAG_LAT))}")
    print(f"   改前文本 : checksum={ck_before}  len={len(before)}（基线文件）")
    print()

    # ------------------------------------------------------------------
    # 前提对账
    # ------------------------------------------------------------------
    r = psql(APP_USER, APP_PWD, DEV_DB, [
        "-A", "-t", "-c",
        "SELECT checksum FROM flyway_schema_history WHERE version = '20';"])
    db_cks = (r.stdout or "").strip()
    if r.returncode != 0 or not db_cks:
        raise SystemExit(f"❌ 读不到真库中 V20 的 checksum：\n{(r.stderr or '').strip()}")

    if ck_before != BEFORE_CHECKSUM:
        raise SystemExit(
            f"❌ 基线文件的自洽性失败：复算 = {ck_before}，而 V20 的“改前登记值” = "
            f"{BEFORE_CHECKSUM}。\n   ⇒ 基线文件被改动过，它不再是“改前那一份”，"
            f"本脚本的 C8 论据（改前/改后净效果相同）会失去立足点。")
    print(f"   基线自洽 : 改前文本复算 = {ck_before} == 常量 {BEFORE_CHECKSUM}  ✅")
    print(f"              （常量 {BEFORE_CHECKSUM} 即改前在真库 flyway_schema_history "
          f"中的登记值 —— 改前身份的机械证据）")
    if db_cks == str(ck_after):
        print(f"   库文本一致 : 真库 V20 checksum = {ck_after} == 当前文本复算  ✅"
              f"（本轮改动已对齐）")
    else:
        print(f"   🛑 库文本待对齐 : 真库 V20 checksum = {db_cks}，当前文本复算 = {ck_after}。")
        print(f"      ⇒ 若当前文本刚为修 (b6)/(b8)/(e2)/(e3) 的缺陷改过 V20 的"
              f"【第 4 节自证】（不含 DDL），则：")
        print(f"        C8 会机械证明两份文本的 DDL 净效果完全相同；"
              f"对齐由收口动作完成（见 run log）。")
        print(f"      ⇒ 收口动作：UPDATE flyway_schema_history SET checksum = {ck_after} "
              f"WHERE version = '20';  （在 C8a 证明净效果相同之后执行）")
    print()

    # 前提现场
    r = psql(SUPER_USER, SUPER_PWD, DEV_DB, [
        "-A", "-t", "-F", " | ", "-c",
        "SELECT rolname, rolsuper::text, rolbypassrls::text FROM pg_roles "
        "WHERE rolname IN ('diaoyuanyun','postgres') ORDER BY rolname;",
        "-c",
        "SELECT c.relname, c.relrowsecurity::text, c.relforcerowsecurity::text, "
        "pg_get_userbyid(c.relowner) FROM pg_class c JOIN pg_namespace n "
        "ON n.oid = c.relnamespace WHERE n.nspname='public' AND c.relname='case_archive';",
        "-c",
        "SELECT tablename, policyname, "
        "CASE WHEN qual LIKE '%app.tenant_id%' THEN 'qual:tenant_id' ELSE 'qual:??' END, "
        "CASE WHEN with_check LIKE '%app.tenant_id%' THEN 'wc:tenant_id' ELSE 'wc:??' END "
        "FROM pg_policies WHERE schemaname='public' AND tablename='case_archive';",
        "-c",
        "SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint "
        "WHERE conrelid='public.case_archive'::regclass ORDER BY contype, conname;"])
    print("   前提现场 : 角色属性 / case_archive 的 RLS 与约束")
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

    ALL_LABELS = ["V20 自证失败(a0)", "V20 自证失败(a1)", "V20 自证失败(a)",
                  "V20 自证失败(b)", "V20 自证失败(b1)", "V20 自证失败(b2)",
                  "V20 自证失败(b3)", "V20 自证失败(b4)", "V20 自证失败(b5)",
                  "V20 自证失败(b6-负向)", "V20 自证失败(b6)",
                  "V20 自证失败(b7)", "V20 自证失败(b8)", "V20 自证失败(b9)",
                  "V20 自证失败(b10)", "V20 自证失败(b11)",
                  "V20 自证失败(b12)", "V20 自证失败(b12b)", "V20 自证失败(b12c)",
                  "V20 自证失败(c)", "V20 自证失败(c1)", "V20 自证失败(c2)",
                  "V20 自证失败(c3)", "V20 自证失败(c4)",
                  "V20 自证失败(d)", "V20 自证失败(d2)", "V20 自证失败(d3)",
                  "V20 自证失败(e1)", "V20 自证失败(e2)", "V20 自证失败(e3)",
                  "V20 自证失败(e4)", "V20 自证失败(e5-a)", "V20 自证失败(e5-b)",
                  "V20 自证失败(e6)", "V20 自证失败(e6-对照)", "V20 自证失败(e7)",
                  "V20 自证失败(e8)", "V20 自证失败(e9-a)", "V20 自证失败(e9-b)",
                  "V20 自证失败(e9-c)", "V20 自证失败(e10)", "V20 自证失败(e11)",
                  "V20 自证失败(e12)", "V20 自证失败(f2)"]

    def label_of_exact(out: str, labels) -> str:
        """取首个出现的标签；位置相同时取【最长】的那个（前缀打破平局）。

        🛑 前缀陷阱核对：`V20 自证失败(b6)` 是 `V20 自证失败(b6-负向)` 的**后缀**干扰源
           （前者是后者的子串，但 find() 位置不同）—— 而 `V20 自证失败(b)` 是
           `V20 自证失败(b1)` 的前缀 ⇒ 必须按长度打破平局。
        """
        hits = [(out.find(lab), -len(lab), lab) for lab in labels if lab in out]
        hits = [h for h in hits if h[0] >= 0]
        return min(hits)[2] if hits else "(无标签)"

    # ------------------------------------------------------------------
    # C1 原样重跑必须全绿
    # ------------------------------------------------------------------
    ok, out = run_sql(mig)
    check("C1 原样重跑 V20 必须全绿（含本轮 (b6)/(b8)/(e2)/(e3) 的补强）",
          ok and "V20 自证通过" in out, out)

    # ------------------------------------------------------------------
    # I(a0) 换成 BYPASSRLS 角色 ⇒ 自证 (a0) 必须抓住
    # ------------------------------------------------------------------
    ok, out = run_sql(mig, as_super=True)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(a0) 用 BYPASSRLS 角色跑 ⇒ 自证 (a0) 必须抓住（依赖 RLS 的 4 条判据的前提）",
          (not ok) and lab == "V20 自证失败(a0)",
          f"首报错标签={lab}（期望 V20 自证失败(a0)）\n{out}")

    # ------------------------------------------------------------------
    # I(a1) 把 case_archive 的 RLS 策略改成 USING(true) ⇒ 自证 (a1) 必须抓住
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
-- 注入：把 case_archive 的租户隔离策略改成"人人可见"（角色属性完全正常）
ALTER POLICY tenant_isolation ON case_archive USING (true) WITH CHECK (true);
""")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(a1) 把 case_archive 的 RLS 策略改成 USING(true) ⇒ 自证 (a1) 必须抓住",
          (not ok) and lab == "V20 自证失败(a1)",
          f"首报错标签={lab}（期望 V20 自证失败(a1)）\n{out}")

    # ------------------------------------------------------------------
    # I(a) 删掉 register_case_archive → (a) 抓
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
DROP FUNCTION register_case_archive(uuid, uuid, uuid, jsonb, jsonb,
                                    text, jsonb, boolean, text);
""")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(a) 删掉 register_case_archive → 自证 (a) 必须抓住",
          (not ok) and lab == "V20 自证失败(a)",
          f"首报错标签={lab}\n{out}")

    # ------------------------------------------------------------------
    # I(b1) 去掉 set_config(app.tenant_id, ..., true) → (b1) 抓
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, TAG_REG,
        "    PERFORM set_config('app.tenant_id', p_tenant_id::text, true);",
        "    PERFORM 1;", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b1) 去掉 register 的 set_config(app.tenant_id, ..., true) → 自证 (b1) 必须抓住",
          (not ok) and lab == "V20 自证失败(b1)",
          f"首报错标签={lab}（期望 V20 自证失败(b1)）\n{out}")

    # ------------------------------------------------------------------
    # I(b2) 去掉 assert_tenant_context() → (b2) 抓
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, TAG_REG, "    v_ctx := assert_tenant_context();", "    v_ctx := p_tenant_id::text;",
        expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b2) 去掉 register 的 assert_tenant_context() → 自证 (b2) 必须抓住",
          (not ok) and lab == "V20 自证失败(b2)",
          f"首报错标签={lab}（期望 V20 自证失败(b2)）\n{out}")

    # ------------------------------------------------------------------
    # I(b3) 去掉 current_setting(app.tenant_id) 一致性守卫 → (b3) 抓
    #   🛑 注入形态：把整条 IF 守卫的**条件**改成恒假（代码仍在，正则却不再命中
    #      `current_setting('app.tenant_id'`）——故分两步：先把读语句搬走。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, TAG_REG,
        "    v_ctx_before := current_setting('app.tenant_id', true);",
        "    v_ctx_before := NULL;", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b3) 去掉 register 的 current_setting(app.tenant_id) 一致性守卫 → 自证 (b3) 必须抓住",
          (not ok) and lab == "V20 自证失败(b3)",
          f"首报错标签={lab}（期望 V20 自证失败(b3)）\n{out}")

    # ------------------------------------------------------------------
    # I(b5) 从 INSERT 里摘掉 ON CONFLICT → (b5) 抓
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, TAG_REG,
        "    ON CONFLICT (archive_id) DO NOTHING;",
        "    ;", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b5) 从 INSERT 里摘掉 ON CONFLICT（语法仍合法）→ 自证 (b5) 必须抓住",
          (not ok) and lab == "V20 自证失败(b5)",
          f"首报错标签={lab}（期望 V20 自证失败(b5)）\n{out}")

    # ------------------------------------------------------------------
    # I(b6-a) 🎯 本脚本核心用例之一：`(archive_id)` → `(tenant_id, archive_id)`
    #   🛑 修复前：判据无锚点 ⇒ 被函数体内那条 RAISE 的**消息字面量**满足
    #      ⇒ 自证照常"通过"（静默假绿：跨租户 RAISE 已成死代码而无人报红）。
    #      修复后：判据带锚点 ⇒ 报 (b6)。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, TAG_REG,
        "    ON CONFLICT (archive_id) DO NOTHING;",
        "    ON CONFLICT (tenant_id, archive_id) DO NOTHING;", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b6-a) 🎯 把推断目标改成 (tenant_id, archive_id)（使跨租户 RAISE 成死代码）"
          "→ 自证 (b6) 必须抓住（修复前为静默假绿）",
          (not ok) and lab == "V20 自证失败(b6)",
          f"首报错标签={lab}（期望 V20 自证失败(b6)）\n{out}")

    # ------------------------------------------------------------------
    # I(b6-b) 🎯 等价形态：`ON CONFLICT ON CONSTRAINT case_archive_pkey`
    #   🛑 修复前：判据写成"必须逐字是 ON CONFLICT (archive_id)" ⇒ 这个
    #      **语义等价的正确写法**会被判红（假红）。
    #      修复后：接受两种形态 ⇒ 必须全绿。
    # ------------------------------------------------------------------
    eq = replace_func_body(
        mig, TAG_REG,
        "    ON CONFLICT (archive_id) DO NOTHING;",
        "    ON CONFLICT ON CONSTRAINT case_archive_pkey DO NOTHING;", expect=1)
    ok, out = run_sql(eq)
    check("I(b6-b) 🎯 用 `ON CONFLICT ON CONSTRAINT case_archive_pkey`（语义等价的正确写法）"
          "→ 必须仍然全绿（判据不得过度收紧）",
          ok and "V20 自证通过" in out,
          f"（修复前此形态报红 = 假红）\n{out}")

    # ------------------------------------------------------------------
    # I(b6-c) `(archive_id)` → `(archive_id, tenant_id)`
    #   🛑 正向判据 (b6) 兜不住它（archive_id 后面跟逗号 ⇒ 不匹配 `\(\s*archive_id\s*\)`）
    #      ⇒ 需要 (b6-负向)。
    #   🛑 实测说明（本脚本首跑的教训）：把真 INSERT 改成 `(archive_id, tenant_id)` 时，
    #      虽然 (b6) 也已被破坏（不再是"只含 archive_id 的推断目标"）⇒ **首报错标签是 (b6)**
    #      （判据顺序在前）。故本用例断言 **(b6)**；
    #      而 (b6-负向) 的**独立判别力**由 I(b6-c2) 单独证明
    #      （把正向判据临时放宽成"匹配任意 ON CONFLICT 推断"，只剩负向判据能抓）。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, TAG_REG,
        "    ON CONFLICT (archive_id) DO NOTHING;",
        "    ON CONFLICT (archive_id, tenant_id) DO NOTHING;", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b6-c) 把推断目标改成 (archive_id, tenant_id) → 自证 (b6) 必须抓住",
          (not ok) and lab == "V20 自证失败(b6)",
          f"首报错标签={lab}（期望 V20 自证失败(b6)）\n{out}")

    # ------------------------------------------------------------------
    # I(b6-c2) 🎯 (b6-负向) 的**独立判别力**自证
    #   🛑 只断言 (b6) 不够：一个判据可能因为"另一条判据先报"而从未生效
    #      （正是 C6 要防的事）。故本用例把**正向判据临时放宽**
    #      （改成只要求"存在任意 ON CONFLICT 推断目标"），
    #      使 (b6-负向) 成为唯一的防线 ⇒ 若它没生效，自证会全绿。
    # ------------------------------------------------------------------
    inj_body = replace_in_mig(
        mig,
        "    IF v_reg_code !~ 'INSERT\\s+INTO\\s+case_archive\\M[^;]*'\n"
        "                   '(ON\\s+CONFLICT\\s+ON\\s+CONSTRAINT\\s+case_archive_pkey\\M'\n"
        "                   '|ON\\s+CONFLICT\\s*\\(\\s*archive_id\\s*\\))' THEN",
        "    IF v_reg_code !~ 'INSERT\\s+INTO\\s+case_archive\\M[^;]*ON\\s+CONFLICT' THEN",
        expect=1)
    inj_body = replace_func_body(
        inj_body, TAG_REG,
        "    ON CONFLICT (archive_id) DO NOTHING;",
        "    ON CONFLICT (archive_id, tenant_id) DO NOTHING;", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b6-c2) 🎯 放宽正向判据后，`(archive_id, tenant_id)` 必须由 (b6-负向) 单独抓住",
          (not ok) and lab == "V20 自证失败(b6-负向)",
          f"首报错标签={lab}（期望 V20 自证失败(b6-负向)）\n"
          f"🛑 若这一条为 FAIL 而只是【全绿】，说明 (b6-负向) 从未真正生效 —— "
          f"它被 (b6) 挡住了，属于【判据存在但没有判别力】。\n{out}")

    # ------------------------------------------------------------------
    # I(b7) 把 RAISE 里的"另一租户"改成"其他租户"（注释里仍有该词）→ (b7) 抓
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, TAG_REG, "已被【另一租户】占用", "已被【其他租户】占用", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b7) 把 RAISE 里的『另一租户』改成『其他租户』（注释里仍有该词）→ 自证 (b7) 必须抓住",
          (not ok) and lab == "V20 自证失败(b7)",
          f"首报错标签={lab}（期望 V20 自证失败(b7)）\n{out}")

    # ------------------------------------------------------------------
    # I(b9) 让 INSERT 不含 tenant_id（列与值同步删，语法仍合法）→ (b9) 抓
    # ------------------------------------------------------------------
    old_cols = ("    INSERT INTO case_archive (archive_id, tenant_id, customer_id,\n"
                "                              archive_checklist, staff_signs,")
    new_cols = ("    INSERT INTO case_archive (archive_id, customer_id,\n"
                "                              archive_checklist, staff_signs,")
    old_vals = ("    VALUES (p_archive_id, p_tenant_id, p_customer_id,\n"
                "            p_checklist, p_staff_signs,")
    new_vals = ("    VALUES (p_archive_id, p_customer_id,\n"
                "            p_checklist, p_staff_signs,")
    inj_body = replace_func_body(mig, TAG_REG, old_cols, new_cols, expect=1)
    inj_body = replace_func_body(inj_body, TAG_REG, old_vals, new_vals, expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b9) 从 INSERT 列清单里删掉 tenant_id（列与值同步删，语法仍合法）→ 自证 (b9) 必须抓住",
          (not ok) and lab == "V20 自证失败(b9)",
          f"首报错标签={lab}（期望 V20 自证失败(b9)）\n{out}")

    # ------------------------------------------------------------------
    # I(b12-a) 🛑🛑🛑 合规红线：把手环键**并进硬门禁数组**
    #   🛑 这是"看起来更安全"的改动 —— 而 PRD P0-25 与 README §5.3③ 逐字禁止。
    #      判据必须抓住它。若抓不住 ⇒ 一条违规实现可以带着"自证全绿"上线。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, TAG_REG,
        "        'archive_plan_exec_archived'    -- ARC-13 客户档案/方案/执行/评估已归档\n"
        "    ];",
        "        'archive_plan_exec_archived',   -- ARC-13\n"
        "        'handband_recorded_as_reference' -- 🛑 注入：把手环并进硬门禁（违规）\n"
        "    ];", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b12-a) 🎯 把手环键并进【硬门禁数组】（合规红线违规）→ 自证 (b12) 必须抓住",
          (not ok) and lab == "V20 自证失败(b12)",
          f"首报错标签={lab}（期望 V20 自证失败(b12)）\n{out}")

    # ------------------------------------------------------------------
    # I(b12-b) 把硬门禁里的 owner_signed 换成别的键（数量不变）→ (b12b) 抓
    #   🛑 证明"逐项断言"比"只数个数"强：个数不变时只数个数会漏掉。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, TAG_REG,
        "        'owner_signed',                 -- ARC-12 最终处理结论已经负责人签字",
        "        'owner_signed_typo',            -- 注入：拼错（数量不变）", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b12-b) 🎯 把硬门禁里的 owner_signed 换成拼错的键（数量不变）→ 自证 (b12b) 必须抓住",
          (not ok) and lab == "V20 自证失败(b12b)",
          f"首报错标签={lab}（期望 V20 自证失败(b12b)）\n{out}")

    # ------------------------------------------------------------------
    # I(b12-c) 🎯 把手环键从【警告数组】里整个删掉 → (b12c) 抓
    #   🛑 只有 (b12)（不得在硬门禁里）是不够的：整个删掉同样能让它通过，
    #      而那会让 ARC-11 失去清单载体。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, TAG_REG,
        "    v_warn_gate_keys text[] := ARRAY[\n"
        "        'handband_recorded_as_reference'\n"
        "    ];",
        "    v_warn_gate_keys text[] := ARRAY[]::text[];", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b12-c) 🎯 把手环键从【警告门禁数组】里整个删掉 → 自证 (b12c) 必须抓住",
          (not ok) and lab == "V20 自证失败(b12c)",
          f"首报错标签={lab}（期望 V20 自证失败(b12c)）\n{out}")

    # ------------------------------------------------------------------
    # I(b12-d) 删掉硬门禁数组的声明（锚点消失）→ (b12) 抓
    #   🛑 证明"找不到锚点也必须报错" —— 一条"找不到锚点就跳过"的判据等于没有判据。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, TAG_REG,
        "    v_hard_gate_keys text[] := ARRAY[",
        "    v_hard_gate_keys text[] := (SELECT ARRAY[", expect=1)
    inj_body = replace_func_body(
        inj_body, TAG_REG,
        "        'archive_plan_exec_archived'    -- ARC-13 客户档案/方案/执行/评估已归档\n"
        "    ];",
        "        'archive_plan_exec_archived']);", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b12-d) 改掉硬门禁数组的声明形式（锚点消失）→ 自证 (b12) 必须抓住（找不到锚点也要报）",
          (not ok) and lab == "V20 自证失败(b12)",
          f"首报错标签={lab}（期望 V20 自证失败(b12)）\n{out}")

    # ------------------------------------------------------------------
    # I(c1) latest_archive_of 去掉 `tenant_id = p_tenant_id` → (c1) 抓
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, TAG_LAT,
        "     WHERE tenant_id = p_tenant_id\n       AND customer_id = p_customer_id",
        "     WHERE customer_id = p_customer_id", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(c1) latest_archive_of 去掉 `tenant_id = p_tenant_id`（RLS 仍兜住，但可读性没了）"
          "→ 自证 (c1) 必须抓住",
          (not ok) and lab == "V20 自证失败(c1)",
          f"首报错标签={lab}（期望 V20 自证失败(c1)）\n{out}")

    # ------------------------------------------------------------------
    # I(c2) 去掉 ORDER BY 的 tie-breaker → (c2) 抓
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, TAG_LAT,
        "     ORDER BY archived_at DESC, archive_id DESC",
        "     ORDER BY archived_at DESC", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(c2) 去掉 ORDER BY 的 tie-breaker `archive_id DESC`（语义变成非确定）"
          "→ 自证 (c2) 必须抓住",
          (not ok) and lab == "V20 自证失败(c2)",
          f"首报错标签={lab}（期望 V20 自证失败(c2)）\n{out}")

    # ------------------------------------------------------------------
    # I(c3) 去掉 LIMIT 1 → (c3) 抓
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, TAG_LAT, "     LIMIT 1;", "     ;", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(c3) 去掉 latest_archive_of 的 LIMIT 1 → 自证 (c3) 必须抓住",
          (not ok) and lab == "V20 自证失败(c3)",
          f"首报错标签={lab}（期望 V20 自证失败(c3)）\n{out}")

    # ------------------------------------------------------------------
    # I(d) 抹掉登记行 → (d) 抓
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
DELETE FROM schema_migration WHERE version = 'V20';
""")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(d) 抹掉迁移登记行 → 自证 (d) 必须抓住",
          (not ok) and lab == "V20 自证失败(d)",
          f"首报错标签={lab}\n{out}")

    # ------------------------------------------------------------------
    # I(d3) 摘掉授权段且函数为新建（proacl IS NULL）→ (d3) 必须抓住
    #   🛑 (d2) 在本仓脚本建的库上恒真（owner 对自有函数的 EXECUTE 隐含）⇒
    #      必须先把函数 DROP 掉再让本文件新建，proacl 初始为 NULL 才是
    #      "授权段根本没执行"的**真实形态**。
    # ------------------------------------------------------------------
    stripped, n_sub = GRANT_RE.subn("-- 注入：整个第 2 节授权段被摘掉\n", mig, 1)
    if n_sub != 1:
        raise SystemExit(f"❌ 未能定位第 2 节 $v20_grant$ 段（匹配 {n_sub} 次）—— 迁移结构已变。")
    inj = stripped.replace(
        "CREATE OR REPLACE FUNCTION register_case_archive(",
        "DROP FUNCTION IF EXISTS register_case_archive(uuid,uuid,uuid,jsonb,jsonb,"
        "text,jsonb,boolean,text);\nCREATE OR REPLACE FUNCTION register_case_archive(", 1)
    inj = inj.replace(
        "CREATE OR REPLACE FUNCTION latest_archive_of(",
        "DROP FUNCTION IF EXISTS latest_archive_of(uuid,uuid);\n"
        "CREATE OR REPLACE FUNCTION latest_archive_of(", 1)
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(d3) 摘掉授权段且函数为新建（proacl IS NULL）→ 自证 (d3) 必须抓住",
          (not ok) and lab == "V20 自证失败(d3)",
          f"首报错标签={lab}（期望 V20 自证失败(d3)）\n{out}")

    # ------------------------------------------------------------------
    # I(e1) 改动 e1 的断言期望值（定义段全过）→ (e1) 抓
    #   🛑 注入点选在断言（把 btrim(staff_signs ->> 'store_owner') = '王五' 改成 '赵六'）：
    #      函数体全不动 ⇒ 静态判据全过 ⇒ 只有行为断言能抓。
    # ------------------------------------------------------------------
    old_e1 = "                          AND btrim(staff_signs ->> 'store_owner') = '王五'"
    inj = replace_in_mig(mig, old_e1,
                         "                          AND btrim(staff_signs ->> 'store_owner') = '赵六'",
                         expect=1)
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(e1) 改动 e1 的行断言期望值（函数体不动、静态判据全过）→ 自证 (e1) 必须抓住",
          (not ok) and lab == "V20 自证失败(e1)",
          f"首报错标签={lab}（期望 V20 自证失败(e1)）\n{out}")

    # ------------------------------------------------------------------
    # I(e3-a) 🎯 本脚本核心用例之二：摘掉 (4) 的客户存在性检查
    #   🛑 修复前：只断言"抛了异常" ⇒ 库层复合外键（V16 的成果）会抛 23503
    #      ⇒ 被内层 EXCEPTION 捕获 ⇒ **自证照常"通过"**（静默假绿）。
    #      修复后：断言 SQLSTATE = P0001 ⇒ 报 (e3)。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(mig, TAG_REG, "    IF v_cust <> 1 THEN", "    IF false THEN",
                                 expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(e3-a) 🎯 摘掉 register 的 (4) 客户存在性检查（库层外键会兜底抛 23503）"
          "→ 自证 (e3) 必须抓住（修复前为静默假绿）",
          (not ok) and lab == "V20 自证失败(e3)",
          f"首报错标签={lab}（期望 V20 自证失败(e3)）\n{out}")

    # ------------------------------------------------------------------
    # I(e4) 让硬门禁缺项不被拒（IF v_missing_gate → IF false）→ (e4) 抓
    #   🛑 注意本注入**同时**会打掉 (b7) 的行为（无关）—— 静态判据 (b7) 仍成立。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(mig, TAG_REG, "    IF v_missing_gate IS NOT NULL THEN",
                                 "    IF false THEN", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(e4) 让硬门禁缺项不被拒（IF v_missing_gate IS NOT NULL → IF false）→ 自证 (e4) 必须抓住",
          (not ok) and lab == "V20 自证失败(e4)",
          f"首报错标签={lab}（期望 V20 自证失败(e4)）\n{out}")

    # ------------------------------------------------------------------
    # I(e5-a) 🛑🛑🛑 合规红线行为防线一：手环**缺席**时被反转为阻断 → (e5-a) 抓
    # ------------------------------------------------------------------
    warn_anchor = ("    v_warn_val := p_checklist ->> 'handband_recorded_as_reference';")
    inj_body = replace_func_body(
        mig, TAG_REG, warn_anchor,
        warn_anchor + """
    -- 🛑 注入：把"手环缺席"反转为阻断（合规红线违规）
    IF NOT (p_checklist ? 'handband_recorded_as_reference') THEN
        RAISE EXCEPTION '注入：手环项缺席被反转为阻断（合规红线违规）';
    END IF;""", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(e5-a) 🎯 把『手环项缺席』反转为阻断（合规红线违规）→ 自证 (e5-a) 必须抓住",
          (not ok) and lab == "V20 自证失败(e5-a)",
          f"首报错标签={lab}（期望 V20 自证失败(e5-a)）\n{out}")

    # ------------------------------------------------------------------
    # I(e5-b) 🛑🛑🛑 合规红线行为防线二：手环 = **false** 时被反转为阻断 → (e5-b) 抓
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, TAG_REG, warn_anchor,
        warn_anchor + """
    -- 🛑 注入：把"手环 = false"反转为阻断（合规红线违规）
    IF (p_checklist ? 'handband_recorded_as_reference')
       AND (p_checklist ->> 'handband_recorded_as_reference') = 'false' THEN
        RAISE EXCEPTION '注入：手环项为 false 被反转为阻断（合规红线违规）';
    END IF;""", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(e5-b) 🎯 把『手环项 = false』反转为阻断（合规红线违规）→ 自证 (e5-b) 必须抓住",
          (not ok) and lab == "V20 自证失败(e5-b)",
          f"首报错标签={lab}（期望 V20 自证失败(e5-b)）\n{out}")

    # ------------------------------------------------------------------
    # I(e6-a) 🎯 靶心用例：把幂等判定改成无条件 RETURN（跨租户 RAISE 成死代码）
    #   🛑 静态判据 (b7)/(b8) 全部保持成立（RAISE 语句与那次读都还在）⇒
    #      只有行为断言 (e6) 能抓。这是"静态断言证明结构、行为断言证明效果"的体现。
    # ------------------------------------------------------------------
    old_e6 = ("    IF v_mine = 1 THEN\n"
              "        RETURN 'ALREADY_EXISTS';\n"
              "    END IF;")
    inj_body = replace_func_body(mig, TAG_REG, old_e6,
                                 "    RETURN 'ALREADY_EXISTS';", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(e6-a) 🎯 把幂等判定改成无条件 RETURN『ALREADY_EXISTS』"
          "（跨租户 RAISE 成死代码，静态判据全过）→ 自证 (e6) 必须抓住",
          (not ok) and lab == "V20 自证失败(e6)",
          f"首报错标签={lab}（期望 V20 自证失败(e6)）\n{out}")

    # ------------------------------------------------------------------
    # I(e2-a)(e2-b)(e2-c) 🎯 本脚本核心用例之三：DO NOTHING → DO UPDATE SET ...
    #   🛑 修复前（第二次重放传"相同内容"）：UPDATE 写回的正是同样的值
    #      ⇒ 自证照常"通过"（静默假绿）。
    #      修复后（第二次重放传"不同内容"）：报 (e2)。
    #   🛑 三个变体覆盖"改明显列"到"改最隐蔽列"：
    #      a) final_conclusion + staff_signs（明显的改写）
    #      b) metrics_trend（单列、业务上像"补充说明"）
    #      c) desensitize_authorized（合规开关 —— 最危险的一处被改写）
    # ------------------------------------------------------------------
    do_nothing = "    ON CONFLICT (archive_id) DO NOTHING;"
    for label, sets in (
        ("I(e2-a)", "        final_conclusion = excluded.final_conclusion,\n"
                    "        staff_signs      = excluded.staff_signs;"),
        ("I(e2-b)", "        metrics_trend = excluded.metrics_trend;"),
        ("I(e2-c)", "        desensitize_authorized = excluded.desensitize_authorized;"),
    ):
        inj_body = replace_func_body(
            mig, TAG_REG, do_nothing,
            "    ON CONFLICT (archive_id) DO UPDATE SET\n" + sets, expect=1)
        inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
        ok, out = run_sql(inj)
        lab = label_of_exact(out, ALL_LABELS)
        check(f"{label} 🎯 把 DO NOTHING 改成 DO UPDATE SET ...（改写既有证据）"
              f"→ 自证 (e2) 必须抓住（修复前为静默假绿）",
              (not ok) and lab == "V20 自证失败(e2)",
              f"首报错标签={lab}（期望 V20 自证失败(e2)）\n{out}")

    # ------------------------------------------------------------------
    # I(b8-a) 🎯 真正摘掉那次读（WHERE archive_id = p_archive_id → WHERE false）→ (b8) 抓
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, TAG_REG, "     WHERE archive_id = p_archive_id;", "     WHERE false;", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b8-a) 🎯 真正摘掉 v_mine 那次读（WHERE archive_id = p_archive_id → WHERE false）"
          "→ 自证 (b8) 必须抓住",
          (not ok) and lab == "V20 自证失败(b8)",
          f"首报错标签={lab}（期望 V20 自证失败(b8)）\n{out}")

    # ------------------------------------------------------------------
    # I(b8-b) 让 v_cust 判定恒真（客户不存在也放行）→ 由行为断言 (e3) 抓
    #   🛑 与 I(e3-a) 的区别：I(e3-a) 把**整条检查**摘掉（库层外键兜底）；
    #      本条把**判定**改成恒真但保留 LIMIT/结构 —— 两者都应当被 (e3) 抓住。
    #      这一条同时证明 (e3) 的新断言（SQLSTATE=D0001... 实为 P0001 + 消息含"客户"）
    #      对"检查失效"是敏感的。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(mig, TAG_REG, "    IF v_cust <> 1 THEN", "    IF 1 = 0 THEN",
                                 expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b8-b) 让客户存在性判定恒真（IF v_cust <> 1 → IF 1 = 0）→ 由行为断言 (e3) 抓住",
          (not ok) and lab == "V20 自证失败(e3)",
          f"首报错标签={lab}（期望 V20 自证失败(e3)）\n{out}")

    # ------------------------------------------------------------------
    # I(f2) 让探针清场失效（整段清场全部 no-op）→ (f2) 抓
    #   🛑 证明"清场自证"有牙齿：若清场不彻底而不报错，残留会在下次运行时
    #      以"INSERT 撞主键"的面目出现（归因完全错误的红）。
    #
    #   🛑🛑 为什么必须【整段 no-op】，不能只改一处（这是本用例两次踩坑后的结论）：
    #      case_archive 有复合外键指向 customer（V16 的成果）。于是：
    #      ① 只让租户 A 的 case_archive DELETE 失效 ⇒ 残留仍在 ⇒ 紧接着的
    #         `DELETE FROM customer WHERE tenant_id = v_ta` 会撞上复合外键
    #         `case_archive_customer_id_fkey` ⇒ 报**库层 23503**（归因完全错误的红），
    #         执行流根本走不到 (f2)（121 首跑实测）。
    #      ② 只让 case_archive 失效、customer 也失效但 tenant 不失效 ⇒
    #         `DELETE FROM tenant` 仍会被 customer 的 FK 挡住 ⇒ 同样 23503。
    #      ③ 改成"删全表" ⇒ 残留被删掉，(f2) 反而变绿（测不到）。
    #      ⇒ 唯一能让执行流走到 (f2) 的注入是：**把整段清场（含 tenant）全部 no-op**。
    #        这恰好精确验证了 (f2) 断言的是**清场结果**（残留是否存在），
    #        而不是"DELETE 语句有没有被执行" —— 因为 DELETEs 都在，只是没生效。
    # ------------------------------------------------------------------
    cleanup_before = (
        "        DELETE FROM case_archive WHERE tenant_id = v_ta;\n"
        "        DELETE FROM customer     WHERE tenant_id = v_ta;\n"
        "\n"
        "        PERFORM set_config('app.tenant_id', v_tb::text, true);\n"
        "        DELETE FROM case_archive WHERE tenant_id = v_tb;\n"
        "        DELETE FROM customer     WHERE tenant_id = v_tb;\n"
        "\n"
        "        -- tenant 表无 RLS，可直接删\n"
        "        DELETE FROM tenant WHERE id IN (v_ta, v_tb);")
    cleanup_after = (
        "        DELETE FROM case_archive WHERE tenant_id = v_ta AND false;\n"
        "        DELETE FROM customer     WHERE tenant_id = v_ta AND false;\n"
        "\n"
        "        PERFORM set_config('app.tenant_id', v_tb::text, true);\n"
        "        DELETE FROM case_archive WHERE tenant_id = v_tb AND false;\n"
        "        DELETE FROM customer     WHERE tenant_id = v_tb AND false;\n"
        "\n"
        "        -- tenant 表无 RLS，可直接删\n"
        "        DELETE FROM tenant WHERE id IN (v_ta, v_tb) AND false;")
    inj_body = replace_in_mig(mig, cleanup_before, cleanup_after, expect=1)
    ok, out = run_sql(inj_body)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(f2) 让探针清场失效（租户 A 的 case_archive DELETE 加 AND false）"
          "→ 自证 (f2) 必须抓住",
          (not ok) and lab == "V20 自证失败(f2)",
          f"首报错标签={lab}（期望 V20 自证失败(f2)）\n{out}")

    # ------------------------------------------------------------------
    # C2 等价形态：给两个函数体首尾加空行 → 必须仍然全绿
    # ------------------------------------------------------------------
    eq = mig.replace(func_body(mig, TAG_REG),
                     "\n" + func_body(mig, TAG_REG) + "\n\n", 1)
    eq = eq.replace(func_body(eq, TAG_LAT),
                    "\n" + func_body(eq, TAG_LAT) + "\n\n", 1)
    ok, out = run_sql(eq)
    check("C2 函数体首尾加空行（等价形态）→ 必须仍然全绿",
          ok and "V20 自证通过" in out, out)

    # ------------------------------------------------------------------
    # C3 等价形态：在 DECLARE 段加一个未使用的变量 → 必须仍然全绿
    # ------------------------------------------------------------------
    eq = replace_func_body(mig, TAG_REG, "    v_ctx_before     text;",
                           "    v_ctx_before     text;\n    v_noise          int := 0;", expect=1)
    ok, out = run_sql(eq)
    check("C3 在 DECLARE 段加一个未使用变量（等价形态）→ 必须仍然全绿",
          ok and "V20 自证通过" in out, out)

    # ------------------------------------------------------------------
    # C4 🎯 等价形态：用约束名推断重写真正的 INSERT → 必须仍然全绿
    #   🛑 与 I(b6-b) 的区别：I(b6-b) 只换了那一句；C4 是**完整重写整条 INSERT**
    #      （列清单改成另一种语义等价写法，看看判据是否仍然稳）。
    #   🛑 判据 (b9)（INSERT 语句里含 tenant_id）与 (b5)（含 ON CONFLICT）都必须仍然成立。
    # ------------------------------------------------------------------
    eq = replace_func_body(
        mig, TAG_REG,
        "    ON CONFLICT (archive_id) DO NOTHING;\n\n    GET DIAGNOSTICS v_affected = ROW_COUNT;",
        "    ON CONFLICT ON CONSTRAINT case_archive_pkey DO NOTHING;\n\n"
        "    GET DIAGNOSTICS v_affected = ROW_COUNT;", expect=1)
    ok, out = run_sql(eq)
    check("C4 🎯 用 `ON CONFLICT ON CONSTRAINT case_archive_pkey`（另一种等价推断写法）"
          "→ 必须仍然全绿",
          ok and "V20 自证通过" in out, out)

    # ------------------------------------------------------------------
    # C5 🎯 等价形态：谓词左右交换（`p_archive_id = archive_id`）→ 必须仍然全绿
    #   🛑 修复前该形态报 (b8)（假红）。
    # ------------------------------------------------------------------
    eq = replace_func_body(
        mig, TAG_REG, "     WHERE archive_id = p_archive_id;",
        "     WHERE p_archive_id = archive_id;", expect=1)
    ok, out = run_sql(eq)
    check("C5 🎯 把 v_mine 的谓词左右交换（`p_archive_id = archive_id`，语义等价）"
          "→ 必须仍然全绿（修复前此形态报红 = 假红）",
          ok and "V20 自证通过" in out, out)

    # ------------------------------------------------------------------
    # C6 元门禁：各注入组的首报错标签必须互不相同
    # ------------------------------------------------------------------
    seen = {}
    cases = {
        "a0": (mig, True),
        "a1": (inject_before_guard(
            mig, "\nALTER POLICY tenant_isolation ON case_archive USING (true) "
                 "WITH CHECK (true);\n"), False),
        "a": (inject_before_guard(
            mig, "\nDROP FUNCTION register_case_archive(uuid, uuid, uuid, jsonb, jsonb, "
                 "text, jsonb, boolean, text);\n"), False),
        "b1": (inject_before_guard(replace_func_body(
            mig, TAG_REG,
            "    PERFORM set_config('app.tenant_id', p_tenant_id::text, true);",
            "    PERFORM 1;", expect=1), "\n"), False),
        "b2": (inject_before_guard(replace_func_body(
            mig, TAG_REG, "    v_ctx := assert_tenant_context();",
            "    v_ctx := p_tenant_id::text;", expect=1), "\n"), False),
        "b3": (inject_before_guard(replace_func_body(
            mig, TAG_REG,
            "    v_ctx_before := current_setting('app.tenant_id', true);",
            "    v_ctx_before := NULL;", expect=1), "\n"), False),
        "b5": (inject_before_guard(replace_func_body(
            mig, TAG_REG, "    ON CONFLICT (archive_id) DO NOTHING;", "    ;", expect=1),
            "\n"), False),
        "b6": (inject_before_guard(replace_func_body(
            mig, TAG_REG, "    ON CONFLICT (archive_id) DO NOTHING;",
            "    ON CONFLICT (tenant_id, archive_id) DO NOTHING;", expect=1), "\n"), False),
        "b6neg": (inject_before_guard(replace_func_body(
            mig, TAG_REG, "    ON CONFLICT (archive_id) DO NOTHING;",
            "    ON CONFLICT (archive_id, tenant_id) DO NOTHING;", expect=1), "\n"), False),
        "b7": (inject_before_guard(replace_func_body(
            mig, TAG_REG, "已被【另一租户】占用", "已被【其他租户】占用", expect=1), "\n"), False),
        "b12": (inject_before_guard(replace_func_body(
            mig, TAG_REG,
            "        'archive_plan_exec_archived'    -- ARC-13 客户档案/方案/执行/评估已归档\n"
            "    ];",
            "        'archive_plan_exec_archived',   -- ARC-13\n"
            "        'handband_recorded_as_reference' -- 注入\n    ];", expect=1), "\n"), False),
        "b12b": (inject_before_guard(replace_func_body(
            mig, TAG_REG,
            "        'owner_signed',                 -- ARC-12 最终处理结论已经负责人签字",
            "        'owner_signed_typo',            -- 注入", expect=1), "\n"), False),
        "b12c": (inject_before_guard(replace_func_body(
            mig, TAG_REG,
            "    v_warn_gate_keys text[] := ARRAY[\n"
            "        'handband_recorded_as_reference'\n    ];",
            "    v_warn_gate_keys text[] := ARRAY[]::text[];", expect=1), "\n"), False),
        "c1": (inject_before_guard(replace_func_body(
            mig, TAG_LAT,
            "     WHERE tenant_id = p_tenant_id\n       AND customer_id = p_customer_id",
            "     WHERE customer_id = p_customer_id", expect=1), "\n"), False),
        "c2": (inject_before_guard(replace_func_body(
            mig, TAG_LAT, "     ORDER BY archived_at DESC, archive_id DESC",
            "     ORDER BY archived_at DESC", expect=1), "\n"), False),
        "c3": (inject_before_guard(replace_func_body(
            mig, TAG_LAT, "     LIMIT 1;", "     ;", expect=1), "\n"), False),
        "e3": (inject_before_guard(replace_func_body(
            mig, TAG_REG, "    IF v_cust <> 1 THEN", "    IF false THEN", expect=1), "\n"), False),
        "e4": (inject_before_guard(replace_func_body(
            mig, TAG_REG, "    IF v_missing_gate IS NOT NULL THEN", "    IF false THEN",
            expect=1), "\n"), False),
        "e5a": (inject_before_guard(replace_func_body(
            mig, TAG_REG,
            "    v_warn_val := p_checklist ->> 'handband_recorded_as_reference';",
            "    v_warn_val := p_checklist ->> 'handband_recorded_as_reference';\n"
            "    IF NOT (p_checklist ? 'handband_recorded_as_reference') THEN\n"
            "        RAISE EXCEPTION '注入：手环缺席被反转为阻断';\n    END IF;", expect=1),
            "\n"), False),
        "e5b": (inject_before_guard(replace_func_body(
            mig, TAG_REG,
            "    v_warn_val := p_checklist ->> 'handband_recorded_as_reference';",
            "    v_warn_val := p_checklist ->> 'handband_recorded_as_reference';\n"
            "    IF (p_checklist ? 'handband_recorded_as_reference')\n"
            "       AND (p_checklist ->> 'handband_recorded_as_reference') = 'false' THEN\n"
            "        RAISE EXCEPTION '注入：手环 false 被反转为阻断';\n    END IF;", expect=1),
            "\n"), False),
        "e6": (inject_before_guard(replace_func_body(
            mig, TAG_REG,
            "    IF v_mine = 1 THEN\n        RETURN 'ALREADY_EXISTS';\n    END IF;",
            "    RETURN 'ALREADY_EXISTS';", expect=1), "\n"), False),
        "e2": (inject_before_guard(replace_func_body(
            mig, TAG_REG, "    ON CONFLICT (archive_id) DO NOTHING;",
            "    ON CONFLICT (archive_id) DO UPDATE SET\n"
            "        metrics_trend = excluded.metrics_trend;", expect=1), "\n"), False),
        "b8": (inject_before_guard(replace_func_body(
            mig, TAG_REG, "     WHERE archive_id = p_archive_id;", "     WHERE false;",
            expect=1), "\n"), False),
    }
    for k, (txt, as_super) in cases.items():
        ok2, out2 = run_sql(txt, as_super=as_super)
        seen[k] = label_of_exact(out2, ALL_LABELS) if not ok2 else "(未报错)"
    # ------------------------------------------------------------------
    # 🛑 C6 判据的精确口径（为什么不是简单的"全网互不相同"）：
    #    C6 要防的是「整块自证只会在**第一行**报错」—— 即某一条注入改错了地方，
    #    却因为另一条**先执行**的判据顺手报了红而被误认为"这条判据有牙齿"。
    #    因此判据是双重的：
    #      ① 每一条注入的【首报错标签】必须**等于它自己的判据标签**（下面 EXPECT 表）；
    #      ② 除已声明的等价对外，各标签两两互异。
    #    · b6 / b6neg 两条注入同报 (b6) 是**设计使然**：`ON CONFLICT (archive_id, tenant_id)`
    #      同时违反正向后缀判据与负向判据，而正向判据执行顺序在前 ⇒ 首报错是 (b6)。
    #      (b6-负向) 的**独立判别力**由专门用例 I(b6-c2) 证明（放宽正向判据后单独抓）。
    # ------------------------------------------------------------------
    EXPECT = {
        "a0": "V20 自证失败(a0)", "a1": "V20 自证失败(a1)", "a": "V20 自证失败(a)",
        "b1": "V20 自证失败(b1)", "b2": "V20 自证失败(b2)", "b3": "V20 自证失败(b3)",
        "b5": "V20 自证失败(b5)", "b6": "V20 自证失败(b6)", "b6neg": "V20 自证失败(b6)",
        "b7": "V20 自证失败(b7)", "b12": "V20 自证失败(b12)",
        "b12b": "V20 自证失败(b12b)", "b12c": "V20 自证失败(b12c)",
        "c1": "V20 自证失败(c1)", "c2": "V20 自证失败(c2)", "c3": "V20 自证失败(c3)",
        "e3": "V20 自证失败(e3)", "e4": "V20 自证失败(e4)",
        "e5a": "V20 自证失败(e5-a)", "e5b": "V20 自证失败(e5-b)",
        "e6": "V20 自证失败(e6)", "e2": "V20 自证失败(e2)", "b8": "V20 自证失败(b8)",
    }
    expect_dup_ok = {("b6", "b6neg")}          # 允许同标签的组（须有独立用例兜底）
    wrong_label = [(k, seen.get(k), EXPECT[k]) for k in EXPECT if seen.get(k) != EXPECT[k]]
    no_err = [k for k, v in seen.items() if v == "(未报错)"]
    dup_report = []
    keys = list(seen)
    for i in range(len(keys)):
        for j in range(i + 1, len(keys)):
            if seen[keys[i]] == seen[keys[j]] and (keys[i], keys[j]) not in expect_dup_ok:
                dup_report.append((keys[i], keys[j], seen[keys[i]]))
    uniq = (not no_err) and (not dup_report) and (not wrong_label)
    check("C6 各注入组的【首报错标签】必须落在各自判据上、且除已声明的等价对外两两互异"
          "（防整段只在一处报错）",
          uniq,
          f"落错判据的组={wrong_label}；未报错组={no_err}；意外同标签={dup_report}\n"
          f"标签分布 = {seen}")

    # ------------------------------------------------------------------
    # C7 元门禁：全部失败分支字面量必须都在迁移文本里（防"删掉自证也算通过"）
    # ------------------------------------------------------------------
    need = ["V20 自证失败(a0)", "V20 自证失败(a1)", "V20 自证失败(a)",
            "V20 自证失败(b)", "V20 自证失败(b1)", "V20 自证失败(b2)",
            "V20 自证失败(b3)", "V20 自证失败(b4)", "V20 自证失败(b5)",
            "V20 自证失败(b6)", "V20 自证失败(b6-负向)",
            "V20 自证失败(b7)", "V20 自证失败(b8)", "V20 自证失败(b9)",
            "V20 自证失败(b10)", "V20 自证失败(b11)",
            "V20 自证失败(b12)", "V20 自证失败(b12b)", "V20 自证失败(b12c)",
            "V20 自证失败(c)", "V20 自证失败(c1)", "V20 自证失败(c2)",
            "V20 自证失败(c3)", "V20 自证失败(c4)",
            "V20 自证失败(d)", "V20 自证失败(d2)", "V20 自证失败(d3)",
            "V20 自证失败(e1)", "V20 自证失败(e2)", "V20 自证失败(e3)",
            "V20 自证失败(e4)", "V20 自证失败(e5-a)", "V20 自证失败(e5-b)",
            "V20 自证失败(e6)", "V20 自证失败(e6-对照)", "V20 自证失败(e7)",
            "V20 自证失败(e8)", "V20 自证失败(e9-a)", "V20 自证失败(e9-b)",
            "V20 自证失败(e9-c)", "V20 自证失败(e10)", "V20 自证失败(e11)",
            "V20 自证失败(e12)", "V20 自证失败(f2)"]
    missing = [s for s in need if s not in mig]
    check("C7 全部自证失败分支字面量必须都在迁移文本里",
          not missing, f"缺失: {missing}")

    # ------------------------------------------------------------------
    # C9 🎯 元门禁：全部静态语句形态判据必须作用在【剥注释后的代码态】上
    #   🛑 防的是"有人把某条判据退回 v_*_body" —— 那会让 (b12) 的假绿重现
    #      （函数体内注释里逐字写着 'handband_recorded_as_reference'）。
    # ------------------------------------------------------------------
    offenders = re.findall(r"v_\w+_body\s*[!~]\s*'", mig)
    check("C9 🎯 迁移里不得直接在 v_*_body（含注释）上做正则匹配（必须用 v_*_code）",
          not offenders,
          f"违规 = {offenders}\n"
          f"🛑 修复目标：先 `SELECT string_agg(regexp_replace(ln,'--.*$',''),E'\\n') INTO v_*_code`，"
          f"再在 v_*_code 上匹配。否则判据会被 prosrc 里的注释满足。")
    has_code_build = (mig.count("INTO v_reg_code") == 1
                      and mig.count("INTO v_lat_code") == 1
                      and "regexp_replace(ln, '--.*$', '')" in mig)
    check("C9b 两条 code 构造语句真的存在（防 C9 因变量缺失而假通过）",
          has_code_build,
          f"INTO v_reg_code={mig.count('INTO v_reg_code')} "
          f"INTO v_lat_code={mig.count('INTO v_lat_code')}")

    # ------------------------------------------------------------------
    # C8 DDL 净效果等价性（本轮弱化处置的依据）
    #
    #    本仓纪律：**迁移文本一改，已应用的库就必须做一次显式处置** ——
    #    要么证明 DDL 净效果相同（⇒ 对齐 checksum），要么承认变了（⇒ 重建库）。
    #    本轮为修 (b6)/(b8)/(e2)/(e3) 的缺陷改了 V20 文本（**只落在第 4 节自证**）
    #    ⇒ 必须给出"净效果相同"的机械证据。
    #    🛑 改前文本来自 `verification/baselines/`，其 checksum 与真库登记值相同
    #       ⇒ "它就是改前那一份"是**机械可证**的（见前提对账），
    #         不是我手写还原出来的近似物（这比 120 的手写还原更可靠）。
    # ------------------------------------------------------------------
    print()
    print("   [C8] 用改前/改后两份文本分别重建临时库并测其 DDL 净效果 …")
    after = mig
    before_ok = before != after
    print(f"        改前/改后文本长度差 = {len(after) - len(before)} 字符"
          f"（{'两份文本不同 ✅' if before_ok else '🛑 两份文本相同，C8 无意义'}）")

    try:
        rebuild_db(REV_DB)
        apply_chain_to_template(REV_DB, after)
        eff_after = ddl_effect(REV_DB)
        rebuild_db(REV_DB)
        apply_chain_to_template(REV_DB, before)
        eff_before = ddl_effect(REV_DB)

        same = (eff_before == eff_after)
        det = ""
        if not same:
            a = set(eff_after.splitlines())
            b = set(eff_before.splitlines())
            det = ("仅在 after 净效果里:\n" + "\n".join(sorted(a - b)[:20]) +
                   "\n仅在 before 净效果里:\n" + "\n".join(sorted(b - a)[:20]))
        check("C8a 改前/改后文本的 V20 DDL 净效果完全相同（签名/函数体/权限/登记）",
              before_ok and same, det or f"before_ok={before_ok}")

        # ---- C8b 判别力自证：净效果比对必须【对真实改动有反应】----
        #      🛑 选点纪律：必须是【函数体内】的真实代码改动；必须【不触发 V20 自身的
        #         任何一条自证】—— 否则整条链 apply 会失败，C8b 变成"测不出来"。
        #         故选 archive 的 (5d) 签名校验里的 `array_length(v_sign_keys, 1)`
        #         这类**只用于 RAISE 消息**的表达式：它不被任何 b/c 正则判据覆盖，
        #         且行为段（只有签名缺项时才走到）不受影响。
        tampered = replace_func_body(
            after, TAG_REG,
            "            v_missing_sign, array_length(v_sign_keys, 1);",
            "            v_missing_sign, array_length(v_sign_keys, 2);", expect=1)
        rebuild_db(REV_DB)
        apply_chain_to_template(REV_DB, tampered)
        eff_tampered = ddl_effect(REV_DB)
        reacted = eff_tampered != eff_after
        rebuild_db(REV_DB)
        apply_chain_to_template(REV_DB, after)
        eff_restored = ddl_effect(REV_DB)
        restored = eff_restored == eff_after
        check("C8b 判别力自证：往函数体注入一处真实改动 ⇒ 净效果比对必须变红（撤销后恢复）",
              reacted and restored,
              f"注入后是否报差异 = {reacted}（期望 True）；撤销后是否恢复 = {restored}（期望 True）")
    finally:
        if not keep:
            psql(SUPER_USER, SUPER_PWD, "postgres",
                 ["-c", f"DROP DATABASE IF EXISTS {REV_DB} WITH (FORCE);"])

    # ------------------------------------------------------------------
    print()
    passed = sum(1 for _, k, _ in results if k)
    for name, k, d in results:
        print(f"  {'[OK]' if k else '[XX]'} {name}")
    print()
    print(f"===== 121 反向验证结果: {passed}/{len(results)} 通过 =====")
    print()
    if passed == len(results):
        print("⇒ 结论：V20 的每一类自证都【有牙齿】：")
        print("   · 注入的每一类错误都被【对应标签】的自证抓住（首报错标签精确对上）；")
        print("   · 语义等价的正确形态仍然全绿（判据没有变成过度收紧的误伤）；")
        print("   · 各注入组的标签互不相同 ⇒ '标签对上'这件事有信息来源，不是伪证；")
        print("   · 静态语句形态判据全部作用在剥注释后的代码态上（C9 常驻断言）；")
        print("   · DDL 净效果对真实改动有反应，而改前/改后净效果相同。")
        print()
        print("  🛑 本轮由此修掉了 V20 的四处真实缺陷（两处为静默假绿）：")
        print("     ① (b6) 判据被函数体内 RAISE 消息的**字面量**满足 ——")
        print("        既会让错误改法静默假绿，也会让等价改法假红；已加锚点 + 双向判据。")
        print("     ② (e3) 只断言『抛了异常』—— 被库层复合外键（V16 的成果）掩盖；")
        print("        已改为断言 SQLSTATE = P0001 且消息含『客户』（同款补强到 e4/e6/e10/e11）。")
        print("     ③ (e2) 幂等重放传入相同内容 ⇒ 对 `DO UPDATE SET ...` 毫无反应；")
        print("        已改为对抗性重放（第二次传不同内容）+ 13 特征列全匹配。")
        print("     ④ (b8) 判据把语义等价的谓词书写判成错（假红）；已接受两种等价写法。")
    else:
        print("⇒ 🛑 结论：存在未通过项，V20 的自证尚不可信 —— 不得据此宣称缺口已收口。")
    if keep:
        print(f"\n（--keep：临时库 {REV_DB} 已保留）")
    else:
        print(f"\n（临时库 {REV_DB} 已清理）")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())