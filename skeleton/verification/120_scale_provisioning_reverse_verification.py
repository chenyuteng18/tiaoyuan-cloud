#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V19「量表建档通路」的**反向验证**（反证法 / 有牙齿的门禁）。

【这个脚本为什么必须存在】

  V19 是本仓第三条「不建表、只建两个 PL/pgSQL 原语」的迁移，它的自证 (b)(c)(bb)
  有 16 条断言全部是**读 prosrc 做正则匹配**。这类断言有三层共同的失效形态：

      函数体看着对、跑起来不对        → 由自证 (e) 的行为段兜住
      断言看着在测某件事，实际测的是另一件事
                                      → 只有"受控注入 + 断言必须报错"能证明它测对了
      断言看着在跑、其实证据的**前提**不成立
                                      → 只有"换掉前提再跑一次"能证明它没在假装
      断言看着在跑，其实是被**函数体注释**满足的
                                      → 只有"注入到注释与代码分离"能证明它没被注释骗过

  第四层是本轮抓出来的（见下），它最阴险：**自证每次打印"通过"，
  而那条判据从来没有工作过。**

【🛑🛑 本脚本抓出的真实缺陷（不是"为了写脚本而写脚本"）】

  **(c4) 判据被函数体【注释】满足 ⇒ 并发正确性这条判据一直没在工作（静默假绿）。**

  成因：PG 把 `AS $tag$ ... $tag$` 之间的内容**原样**存进 pg_proc.prosrc，
  注释也在里面。V19 的 deprecate_scale 函数体注释里逐字写着它自己要做的事：

      --     🛑 谓词里带 `status <> 'deprecated'` 是为了让这条 UPDATE 在并发下**正确**：
      --        两个并发废弃都会通过第 (4) 步的读（都读到非 deprecated），
      --        若 UPDATE 不带这个条件，两次都会"成功"，第二次的 updated_at 会覆盖第一次的

  而 (c4) 的初版判据是裸的 `v_deprecate_body !~ 'status\s*<>\s*''deprecated'''` ——
  **既无锚点、也没剥注释** ⇒ **删掉代码处那句谓词完全不会被抓住**。

  实测（本脚本 I(c4)）：
      注入"删掉回归语里那句 `AND status <> 'deprecated'`"（代码改动，语法仍合法）
      修复前 ⇒ 自证打印"通过"      ← 静默假绿
      修复后 ⇒ 自证报 (c4)

  同族形态在 **(b5)** 上更隐蔽：`ON\s+CONFLICT` 在函数体里有 **2 处代码 + 1 处注释**
  同时命中 ⇒ 连"哪一处满足了判据"都说不清。用例 I(b5) 钉住。

  ⇒ 处置（本轮已落地，**纯 PL/pgSQL，不产生任何 DDL**）：
     把 V19 已有的"逐行剥注释"机制（原本只用在 (b1)(b2)(b3) 上）**推广到全部
     语句形态判据** —— 先 `SELECT string_agg(regexp_replace(ln,'--.*$',''),E'\n')`
     得到 `v_register_code` / `v_deprecate_code`，再在这些**代码态**上匹配；
     **同时保留锚点**（`INSERT INTO scale\M` / `UPDATE scale\M` 起锚、以 `;` 为界）。
     只剥注释会丢失适用范围，只加锚点仍会被注释命中 —— **两者都需要**。
     证据：修复前 (c4) 注入 = 假绿；修复后 = 报 (c4)；原文重跑仍全绿。

【V19 相对 V18 的两处特有形态 —— 本脚本的重点】

  · **(bb)/(b12) 版本冲突对**（V19 特有，V18 没有）：
      (bb) 保证有"版本冲突"的 RAISE 分支；(b12) 保证它**先读回了库中的版本**。
      两者必须成对 —— 只有 RAISE 而没有那次读，RAISE 会变成**无条件抛错**，
      把"传同一版本的幂等重放"也一起打死。用例 I(b12) / I(e3) 分别钉住
      "读回动作在不在"与"分支行为对不对"。
  · **(a0)/(a1) 能力守卫**（V19 首见，2026-09-27 由实测抓出后回补）：
      (a0) 角色不得拥有 BYPASSRLS；(a1) RLS 策略必须仍按 app.tenant_id 隔离。
      本自证里依赖 RLS 的判据共 4 条（(e4) 零行/(e4) 不可见/(e7) 可见/(e8) 不可见），
      它们全部建立在同一个前提上。用例 I(a0)/I(a1) 把前提本身变成受控输入。
      🛑 V18 当初就是缺这两条守卫，导致它在 BYPASSRLS 角色下报出【归因错误】的红
         （报 (e5)"跨租户撞号没抛错"，像函数坏了）——这条已在 119 里处置。

【用例矩阵】

  注入组（必须报错，且【首报错的标签】必须对上）
    I(a0)  换成 BYPASSRLS 角色跑                        → 自证 (a0)
    I(a1)  把 scale 的 RLS 策略改成 USING(true)         → 自证 (a1)
    I(a)   删掉 register_scale                          → 自证 (a)
    I(b4)  把 `INSERT INTO scale` 写成 `INSERT INTO "scale"`（语义等价） → (b4)
    I(b5)  🆕 从 INSERT 语句里摘掉 ON CONFLICT            → (b5)（修复前无锚点，2 代码 + 1 注释命中）
    I(b7)  把 RAISE 里的"另一租户"改成"其他租户"（注释里仍有该词） → (b7)
    I(b9)  让 INSERT 不含 tenant_id（列与值同步删，语法仍合法） → (b9)
    I(bb)  🆕 把 RAISE 里的"不可覆盖"改成"不得覆盖"（注释里仍有该词） → (bb)
    I(b12) 把 `max(scale_version)` 去掉 max               → (b12)
    I(c1)  把 `UPDATE scale` 写成 `UPDATE "scale"`        → (c1)
    I(c4)  🆕 从状态迁移 UPDATE 里删掉 `status <> 'deprecated'` → (c4)
              🛑 **本脚本核心用例**：修复前被注释满足 ⇒ 静默假绿
    I(c5)  只删【状态迁移 UPDATE】的租户维度（步骤(4)的 SELECT 不动） → (c5)
    I(d)   抹掉迁移登记行                                → 自证 (d)
    I(d3)  摘掉授权段 + 函数新建（proacl IS NULL）        → 自证 (d3)（(d2) 此处恒真）
    I(e1)  让 e1 建档的 name 与断言值不符                 → 自证 (e1)（定义段全过）
    I(e3)  🆕 让版本比对恒假（`<> ` → `false`）           → 自证 (e3)（V19 特有：版本冲突必须 RAISE）
    I(e4)  🆕 让"是本租户的"判定恒真（`v_mine = 1` → `true`） → 自证 (e4)（跨租户撞号）
    I(e6)  让 e6 的裸 INSERT 违反 NOT NULL               → 自证 (e6)
  对照组（必须仍然全绿 —— 防"判据收紧后误伤等价形态"）
    C1 原样重跑 V19                                     → 必须全绿
    C2 给两个函数体首尾各加空行                          → 必须全绿
    C3 在 DECLARE 段加一个未使用的变量                   → 必须全绿
  元门禁
    C4 各注入组的【首报错标签必须互不相同】              → 防"整块自证只会在第一行报错"
    C5 全部失败分支字面量必须都在迁移文本里              → 防"删掉自证也算通过"
    C6 DDL 净效果等价性（本轮 code 化的处置依据）
       · 把 code 化还原掉 ⇒ 断言其 DDL 净效果与改后文本**完全相同**；
       · 判别力自证：往函数体注入一处真实改动 ⇒ 净效果比对必须变红（撤销后恢复）。
    C7 🆕 全部语句形态判据必须作用在【剥注释后的代码态】上
       → 防有人把某条判据退回 `v_*_body`（那会让 (c4) 的假绿重现）

【🛑 本脚本自带的"前提对账"】

  开跑前先验证：库中 V19 的 Flyway checksum == 当前迁移文本的复算值。
  把"改了文本必须显式处置库"变成**每次运行都成立的机械断言**（117 的纪律）。

【与 116 / 117 / 118 / 119 的分工】
  116 = V16 迁移【逻辑】是否正确（跨租户引用完整性）。
  117 = 迁移【文本】与已应用【库】是否一致（防"改了文本没处置库"）。
  118 = V17 迁移【自证】是否真的有牙齿（手环绑定通路）。
  119 = V18 迁移【自证】是否真的有牙齿（设备建档通路）。
  120 = V19 迁移【自证】是否真的有牙齿（量表建档通路），本轮本文件。

用法:
  python verification/120_scale_provisioning_reverse_verification.py
  python verification/120_scale_provisioning_reverse_verification.py --keep   # 保留临时库排查
环境变量: DY_PSQL_EXE / DY_PG_HOST / DY_PG_PORT / DY_PG_USER / DY_PG_PASSWORD
          DY_PG_SUPER_USER / DY_PG_SUPER_PASSWORD / DY_PG_DB / DY_SCALE_REV_DB
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
REV_DB = os.environ.get("DY_SCALE_REV_DB", "diaoyuanyun_scale120")

HERE = Path(__file__).resolve().parent                     # verification
SKELETON = HERE.parent                                     # skeleton
MIGRATION_DIR = SKELETON / "dy-app" / "src" / "main" / "resources" / "db" / "migration"
MIGRATION = MIGRATION_DIR / "V19__scale_provisioning_primitive.sql"


def env_with(pwd: str) -> dict:
    return dict(os.environ, PGPASSWORD=pwd)


def psql(user: str, pwd: str, db: str, args, timeout: int = 900):
    cmd = [PSQL, "-h", HOST, "-p", PORT, "-U", user, "-d", db, "-X"] + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env=env_with(pwd), timeout=timeout)


def migration_scripts():
    """按【数值】版本号排序 —— 字符串排序会把 V10 排在 V2 前面（与 117~119 同款）。"""
    files = sorted(MIGRATION_DIR.glob("V*__*.sql"),
                   key=lambda p: int(re.match(r"V(\d+)__", p.name).group(1)))
    if not files:
        raise SystemExit(f"❌ 未在 {MIGRATION_DIR} 找到任何 V*__*.sql")
    return files


def flyway_checksum(path: Path) -> int:
    """复算 Flyway 的 checksum（与 117~119 同口径，CRC32 逐行 UTF-8 有符号）。"""
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

GUARD_RE = re.compile(r"DO\s*\n\$v19_guard\$")


def func_body(mig: str, tag: str) -> str:
    """从迁移文本里切出 `$v19_<tag>$ ... $v19_<tag>$;` 之间的函数体原文。

    🛑 为什么能从【文件文本】切，而不是从库里读：
       118 已用探针证明 —— 库中 `pg_proc.prosrc` 与这里的切片【逐字相同】。
       PG 把 `AS $tag$ ... $tag$` 之间的内容原样存进 prosrc ⇒
       "按文件文本构造注入"与"库里实际存的函数体"是同一个对象。
    """
    open_tag = f"$v19_{tag}$"
    a = mig.index(open_tag) + len(open_tag)
    b = mig.index(f"$v19_{tag}$;")
    return mig[a:b]


def replace_func_body(mig: str, tag: str, old: str, new: str, expect: int = 1) -> str:
    """替换函数体里的一段（精确匹配）。expect 用于钉住"注入真的生效了"。"""
    body = func_body(mig, tag)
    n = body.count(old)
    if n != expect:
        raise SystemExit(
            f"❌ 注入锚点在 {tag} 函数体里出现 {n} 次（期望 {expect}）：{old[:90]!r}\n"
            f"   ⇒ 迁移文本的结构已变，本脚本的锚点失效，必须先修脚本。")
    new_body = body.replace(old, new)
    return mig.replace(body, new_body, 1)


def inject_before_guard(mig: str, injection: str) -> str:
    """🛑 把注入插到 $v19_guard$ 自证块【之前】。

    追加在文件末尾等于"考完试再改答案" —— 那时自证已经跑完并成功，
    注入的错误没有任何自证去抓，于是整组用例假失败（116 第一版踩过）。
    """
    m = GUARD_RE.search(mig)
    if not m:
        raise SystemExit("❌ 未在迁移中找到 $v19_guard$ 自证块 —— 迁移结构已变，"
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
# 临时库：用于 C6「DDL 净效果等价性」
# ---------------------------------------------------------------------------

def rebuild_db(db: str):
    r = psql(SUPER_USER, SUPER_PWD, "postgres", [
        "-c", f"DROP DATABASE IF EXISTS {db} WITH (FORCE);",
        "-c", f"CREATE DATABASE {db} OWNER {APP_USER};",
    ])
    if r.returncode != 0:
        raise SystemExit("❌ 无法重建临时库（需要超级用户通道）：\n"
                         + (r.stdout or "") + (r.stderr or ""))


def apply_chain_to_template(db: str, v19_text: str):
    """把【整条迁移链】应用到临时库，其中 V19 用传入的文本（其余用磁盘原文）。

    🛑 为什么 C6 必须用【整条链】：V19 的 guard 依赖 V5 建的 scale 表、
       以及基线评估的复合外键 —— 只应用一条会在前置就报「表 scale 不存在」。
    🛑 `newline=""` 不是可选项：Windows 上 NamedTemporaryFile("w") 默认把 `\n` 转成
       `\r\n` ⇒ 喂给 psql 的文本与磁盘原文不再逐字相同 ⇒ 重建库里的 pg_proc.prosrc
       变成 `\r\r\n` 形态 ⇒ C6 的"净效果逐字比对"报出一条**完全归因错误**的红。
       （本仓的 V18 净效果探针已实测踩过这条，详见其 docstring。）
    """
    scripts = migration_scripts()
    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False,
                                     encoding="utf-8", newline="") as f:
        f.write("\\set ON_ERROR_STOP on\n")
        for p in scripts:
            text = v19_text if p.name == MIGRATION.name else p.read_text(encoding="utf-8")
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
    """V19 的「DDL 净效果」指纹。

    🛑 本脚本要回答的问题很窄：**V19 这一条文本的两份版本，客观效果是否相同**。
       ⇒ 指纹 = V19 实际产生/改变的四类东西：
           ① 函数签名（proname + 参数 + 返回类型）
           ② 函数体原文（prosrc）—— V19 唯一有内容的产出
           ③ 函数 EXECUTE 权限（第 2 节 GRANT 段的效果）
           ④ schema_migration 中 V19 一行的 description（第 3 节登记段的效果）
       这四项覆盖 V19 第 1/2/3 节；第 4 节是自证（无产出）、第 5 节是注释（无产出）。
       🛑 本轮对 V19 的改动【只落在第 4 节自证】⇒ 这四项必须逐字不变。
    """
    sql = """
    SELECT 'fn|' || p.proname || '|' || pg_get_function_arguments(p.oid) || '|' ||
           pg_get_function_result(p.oid) || '|' || md5(p.prosrc)
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname IN ('register_scale','deprecate_scale')
    UNION ALL
    SELECT 'prosrc|' || p.proname || '|' || p.prosrc
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname IN ('register_scale','deprecate_scale')
    UNION ALL
    SELECT 'priv|' || p.proname || '|' ||
           COALESCE(array_to_string(p.proacl,' | '),'(NULL)') || '|' ||
           has_function_privilege(current_user, p.oid, 'EXECUTE')::text
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname IN ('register_scale','deprecate_scale')
    UNION ALL
    SELECT 'reg|V19|' || COALESCE(description,'')
      FROM schema_migration WHERE version = 'V19'
    """
    r = psql(SUPER_USER, SUPER_PWD, db, ["-A", "-t", "-c", sql])
    if r.returncode != 0:
        raise SystemExit(f"❌ DDL 净效果快照失败({db})：\n" + (r.stderr or ""))
    return "\n".join(x for x in (r.stdout or "").splitlines() if x.strip())


# ---------------------------------------------------------------------------
# 本轮 code 化的"改前形态"（供 C6 还原用）
# ---------------------------------------------------------------------------

CODE_BLOCK_START = ("    SELECT string_agg(regexp_replace(ln, '--.*$', ''), E'\\n')\n"
                    "      INTO v_register_code")
CODE_BLOCK_END = "    -- (b4) register_scale 的幂等写入形态"

# 🛑 code 变量在 DECLARE 段的那两行（含其上三行说明注释）。
#    还原时**必须一并删掉**：否则下面的 `_code → _body` 改名会把它变成
#    第二次 `v_register_body text;` 声明 ⇒ PL/pgSQL「重复声明」，
#    C6 的 before 文本直接跑不起来（实测踩过：LINE 9 重复声明 v_register_body）。
_CODE_DECL_RE = re.compile(
    r"    -- 🛑 2026-09-27（\d+）：两条函数体[\s\S]*?"
    r"    v_\w+_code\s+text;\n"
    r"    v_\w+_code\s+text;\n")


def strip_code_normalization(mig: str) -> str:
    """把本轮"语句形态判据改用代码态"的改动还原掉 —— 即"修复之前"的 V19 形态。

    🛑 这是【保守】取法：只把
         · DECLARE 段那两行 `v_*_code text;`（含说明注释），
         · 两条 `SELECT ... INTO v_*_code` 构造语句，
         · 两处本轮新增的【锚点】(b5)/(c4)，
       删掉 / 还原，并把断言处的 `_code` 换回 `_body`（**保留全部说明注释**）。
       若净效果仍相同，那对"连注释都没有"的完整改前文本也必然相同。

    🛑 还原后的文本必须**仍是合法 PL/pgSQL** —— C6 要把它整条链 apply 进临时库。
       ⇒ `_code → _body` 改名之前，必须先删掉 DECLARE 段那两行声明。
    """
    # 1) 删掉 DECLARE 段的 code 变量声明（含其上的说明注释）
    m = _CODE_DECL_RE.search(mig)
    if not m:
        raise SystemExit("❌ 找不到 DECLARE 段的两行 `v_*_code text;` —— 迁移结构已变，本脚本锚点失效。")
    out = mig[:m.start()] + mig[m.end():]

    # 2) 删掉两条 code 构造语句
    a = out.index(CODE_BLOCK_START)
    b = out.index(CODE_BLOCK_END)
    if not (0 < a < b):
        raise SystemExit("❌ 还原 code 化的切片失败 —— 迁移结构已变，本脚本锚点失效。")
    removed = out[a:b]
    if removed.count("INTO v_register_code") != 1 or removed.count("INTO v_deprecate_code") != 1:
        raise SystemExit("❌ 还原切片里没有同时含两条 `INTO v_*_code` —— 切片范围不对。")
    out = out[:a] + out[b:]

    # 3) 断言处 _code → _body（此时 DECLARE 段已无 _code 声明 ⇒ 不会撞重复声明）
    out = out.replace("v_register_code", "v_register_body")
    out = out.replace("v_deprecate_code", "v_deprecate_body")

    # 4) 还原本轮新增的两处【锚点】（同样只落在 guard 里，无 DDL 产出）
    for new_anchor, old_anchor in (
        (r"IF v_register_body !~ 'INSERT\s+INTO\s+scale\M[^;]*ON\s+CONFLICT' THEN",
         r"IF v_register_body !~ 'ON\s+CONFLICT' THEN"),
        (r"IF v_deprecate_body !~ 'UPDATE\s+scale\M[^;]*status\s*<>\s*''deprecated''' THEN",
         r"IF v_deprecate_body !~ 'status\s*<>\s*''deprecated''' THEN"),
    ):
        if out.count(new_anchor) != 1:
            raise SystemExit(f"❌ 锚点还原失效（{new_anchor[:48]}… 出现 "
                             f"{out.count(new_anchor)} 次，期望 1）—— 迁移结构已变。")
        out = out.replace(new_anchor, old_anchor)

    # 5) 最后再确认还原后的文本里没有任何 `v_*_code`【标识符】残留
    #    （防漏网 ⇒ before 文本跑不起来）。注意只查标识符，不查注释里的 `v_code` 说法。
    leftovers = [t for t in ("v_register_code", "v_deprecate_code") if t in out]
    if leftovers:
        raise SystemExit(f"❌ 还原后仍残留 code 标识符 {leftovers} —— 还原不彻底，before 文本可能不合法。")
    return out


def main():
    keep = "--keep" in sys.argv
    if not MIGRATION.is_file():
        print(f"❌ 找不到被测迁移: {MIGRATION}")
        return 2
    mig = MIGRATION.read_text(encoding="utf-8")

    print("== 120 V19 量表建档通路 · 反向验证 ==")
    print(f"   被测迁移 : {MIGRATION.name}")
    print(f"   行为验证 : {DEV_DB}（注入包在 BEGIN…ROLLBACK 里，不落地）")
    print(f"   等价验证 : {REV_DB}（临时库，仅用于 C6）")
    print(f"   文本校验 : checksum={flyway_checksum(MIGRATION)}  len={len(mig)}  "
          f"body_register={len(func_body(mig, 'register'))}  "
          f"body_deprecate={len(func_body(mig, 'deprecate'))}")
    print()

    # ------------------------------------------------------------------
    # 前提对账：库中登记的 checksum == 当前文本复算值
    # ------------------------------------------------------------------
    r = psql(APP_USER, APP_PWD, DEV_DB, [
        "-A", "-t", "-c",
        "SELECT checksum FROM flyway_schema_history WHERE version = '19';"])
    db_cks = (r.stdout or "").strip()
    want_cks = str(flyway_checksum(MIGRATION))
    if r.returncode != 0 or not db_cks:
        raise SystemExit(f"❌ 读不到真库中 V19 的 checksum：\n{(r.stderr or '').strip()}")
    if db_cks != want_cks:
        raise SystemExit(
            f"❌ 前提对账失败：真库登记的 V19 checksum = {db_cks}，而当前迁移文本复算 = {want_cks}。\n"
            f"   ⇒ 说明【迁移文本被改过，但已应用的库没有处置】。\n"
            f"   正确处理（本仓纪律）：要么证明新旧文本的 DDL 净效果相同 ⇒ 对齐 checksum\n"
            f"   （UPDATE flyway_schema_history SET checksum = {want_cks} WHERE version = '19';），\n"
            f"   要么承认 DDL 变了 ⇒ 重建库 / 补新迁移。最坏的反应是「改个数字就完事」。")
    print(f"   前提对账 : 真库 V19 checksum = {db_cks} == 文本复算 = {want_cks}  ✅")
    print()

    # 前提现场
    r = psql(SUPER_USER, SUPER_PWD, DEV_DB, [
        "-A", "-t", "-F", " | ", "-c",
        "SELECT rolname, rolsuper::text, rolbypassrls::text FROM pg_roles "
        "WHERE rolname IN ('diaoyuanyun','postgres') ORDER BY rolname;",
        "-c",
        "SELECT tablename, policyname, "
        "CASE WHEN qual LIKE '%app.tenant_id%' THEN 'qual:tenant_id' ELSE 'qual:??' END, "
        "CASE WHEN with_check LIKE '%app.tenant_id%' THEN 'wc:tenant_id' ELSE 'wc:??' END "
        "FROM pg_policies WHERE schemaname='public' AND tablename='scale';"])
    print("   前提现场 : 角色属性 / scale 的 RLS 策略")
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

    ALL_LABELS = ["自证失败(a0)", "自证失败(a1)", "自证失败(a)",
                  "自证失败(b1)", "自证失败(b2)", "自证失败(b3)", "自证失败(b4)",
                  "自证失败(b5)", "自证失败(b6)", "自证失败(b7)", "自证失败(b8)",
                  "自证失败(b9)", "自证失败(b12)", "自证失败(bb)",
                  "自证失败(c1)", "自证失败(c2)", "自证失败(c3)", "自证失败(c4)",
                  "自证失败(c5)",
                  "自证失败(d)", "自证失败(d2)", "自证失败(d3)",
                  "自证失败(e1)", "自证失败(e2)", "自证失败(e3)", "自证失败(e4)",
                  "自证失败(e6)", "自证失败(e7)", "自证失败(e8)", "自证失败(e9)",
                  "自证失败(e10)", "自证失败(f2)"]

    def label_of_exact(out: str, labels) -> str:
        """取首个出现的标签；位置相同时取【最长】的那个（前缀打破平局）。

        🛑 前缀陷阱核对：`自证失败(a)` 是 `自证失败(a0)`/`自证失败(a1)` 的前缀，
           故 find() 的位置会相同 ⇒ 必须按长度打破平局，否则 I(a) 会被 I(a0) 误判。
        """
        hits = [(out.find(lab), -len(lab), lab) for lab in labels if lab in out]
        hits = [h for h in hits if h[0] >= 0]
        return min(hits)[2] if hits else "(无标签)"

    # ------------------------------------------------------------------
    # C1 原样重跑必须全绿
    # ------------------------------------------------------------------
    ok, out = run_sql(mig)
    check("C1 原样重跑 V19 必须全绿（含本轮 code 化）",
          ok and "自证通过" in out, out)

    # ------------------------------------------------------------------
    # I(a0) 换成 BYPASSRLS 角色 ⇒ 自证 (a0) 必须抓住
    # ------------------------------------------------------------------
    ok, out = run_sql(mig, as_super=True)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(a0) 用 BYPASSRLS 角色跑 ⇒ 自证 (a0) 必须抓住（依赖 RLS 的 4 条判据的前提）",
          (not ok) and lab == "自证失败(a0)",
          f"首报错标签={lab}（期望 自证失败(a0)）\n{out}")

    # ------------------------------------------------------------------
    # I(a1) 把 scale 的 RLS 策略改成 USING(true) ⇒ 自证 (a1) 必须抓住
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
-- 注入：把 scale 的租户隔离策略改成"人人可见"（角色属性完全正常）
ALTER POLICY tenant_isolation ON scale USING (true) WITH CHECK (true);
""")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(a1) 把 scale 的 RLS 策略改成 USING(true) ⇒ 自证 (a1) 必须抓住",
          (not ok) and lab == "自证失败(a1)",
          f"首报错标签={lab}（期望 自证失败(a1)）\n{out}")

    # ------------------------------------------------------------------
    # I(a) 删掉 register_scale → (a) 抓
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
DROP FUNCTION register_scale(uuid, uuid, text, text, text, jsonb);
""")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(a) 删掉 register_scale → 自证 (a) 必须抓住",
          (not ok) and lab == "自证失败(a)",
          f"首报错标签={lab}\n{out}")

    # ------------------------------------------------------------------
    # I(b4) `INSERT INTO scale` → `INSERT INTO "scale"`（语义等价）→ (b4) 抓
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, "register", "INSERT INTO scale (scale_id", 'INSERT INTO "scale" (scale_id',
        expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check('I(b4) 把 `INSERT INTO scale` 写成 `INSERT INTO "scale"`（语义等价）→ 自证 (b4) 必须抓住',
          (not ok) and lab == "自证失败(b4)",
          f"首报错标签={lab}（期望 自证失败(b4)）\n{out}")

    # ------------------------------------------------------------------
    # I(b5) 🆕 从 INSERT 语句里摘掉 ON CONFLICT → (b5) 抓
    #   🛑 修复前该判据写成裸 `ON\s+CONFLICT`：函数体里有 2 处代码 + 1 处注释命中
    #      ⇒ 摘掉 INSERT 上的它之后，判据仍会被**别处/注释**满足（假绿）。
    # ------------------------------------------------------------------
    old_b5 = ("    ON CONFLICT (scale_id) DO NOTHING;\n"
              "\n"
              "    GET DIAGNOSTICS v_affected = ROW_COUNT;")
    new_b5 = ("    ;\n"
              "\n"
              "    GET DIAGNOSTICS v_affected = ROW_COUNT;")
    n = func_body(mig, "register").count(old_b5)
    if n != 1:
        raise SystemExit(f"❌ I(b5) 的锚在 register 函数体里出现 {n} 次（期望 1）—— 锚点失效，须修脚本。")
    inj_body = replace_func_body(mig, "register", old_b5, new_b5, expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b5) 🆕 从 INSERT 语句里摘掉 ON CONFLICT（语法仍合法）→ 自证 (b5) 必须抓住",
          (not ok) and lab == "自证失败(b5)",
          f"首报错标签={lab}（期望 自证失败(b5)）\n{out}")

    # ------------------------------------------------------------------
    # I(b7) 把 RAISE 里的"另一租户"改成"其他租户"（注释里仍有该词）→ (b7) 抓
    #   🛑 证明"判据必须带锚点 + 必须剥注释"：函数体注释里逐字含"另一租户"。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, "register", "已被【另一租户】占用", "已被【其他租户】占用", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b7) 把 RAISE 里的『另一租户』改成『其他租户』（注释里仍有该词）→ 自证 (b7) 必须抓住",
          (not ok) and lab == "自证失败(b7)",
          f"首报错标签={lab}（期望 自证失败(b7)）\n{out}")

    # ------------------------------------------------------------------
    # I(b9) 让 INSERT 不含 tenant_id（列与值同步删，语法仍合法）→ (b9) 抓
    # ------------------------------------------------------------------
    old_insert = ("    INSERT INTO scale (scale_id, tenant_id, scale_type, scale_version, name,\n"
                  "                       dimension_set_json, status, created_by)\n"
                  "    VALUES (p_scale_id, p_tenant_id, p_scale_type, p_scale_version, p_name,\n"
                  "            p_dimension_set_json, 'active', 'scale-registration')")
    new_insert = ("    INSERT INTO scale (scale_id, scale_type, scale_version, name,\n"
                  "                       dimension_set_json, status, created_by)\n"
                  "    VALUES (p_scale_id, p_scale_type, p_scale_version, p_name,\n"
                  "            p_dimension_set_json, 'active', 'scale-registration')")
    inj_body = replace_func_body(mig, "register", old_insert, new_insert, expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b9) 从 INSERT 列清单里删掉 tenant_id（列与值同步删，语法仍合法）→ 自证 (b9) 必须抓住",
          (not ok) and lab == "自证失败(b9)",
          f"首报错标签={lab}（期望 自证失败(b9)）\n{out}")

    # ------------------------------------------------------------------
    # I(bb) 🆕 把 RAISE 里的"不可覆盖"改成"不得覆盖"（注释里仍有该词）→ (bb) 抓
    #   🛑 这条与 I(b7) 同型：证明 (bb) 的判据没有被注释满足。
    #   🛑🛑 必须把【同一条 RAISE 语句里的两处】"不可覆盖"都改掉：
    #      判据是 `RAISE\s+EXCEPTION[^;]*不可覆盖`，`[^;]*` 不跨语句 ⇒
    #      只要那条 RAISE（消息跨多行拼接）里还剩任意一处该字面量，判据仍成立。
    #      首跑只改了第一处（`版本【不可覆盖】`），第二处（`「不可覆盖」静默违反`）
    #      仍在同一条 RAISE 里 ⇒ 自证照常通过 ⇒ I(bb) 假失败（注入强度不足，非 V19 缺陷）。
    #      ⇒ 本用例的正确形态是"把这条 RAISE 语句里该字面量的**全部**出现都改掉"。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, "register", "版本【不可覆盖】", "版本【不得覆盖】", expect=1)
    inj_body = replace_func_body(
        inj_body, "register", "的「不可覆盖」静默违反", "的「不得覆盖」静默违反", expect=1)
    left_in_raise = re.search(r"RAISE\s+EXCEPTION[^;]*不可覆盖", func_body(inj_body, "register"))
    if left_in_raise:
        raise SystemExit("❌ I(bb) 注入后那条 RAISE 语句里【仍有】'不可覆盖' —— 注入强度不足，"
                         "本用例会假失败。须把该 RAISE 语句里的全部出现都改掉。")
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(bb) 🆕 把 RAISE 里的『不可覆盖』改成『不得覆盖』（同一 RAISE 内两处全改）→ 自证 (bb) 必须抓住",
          (not ok) and lab == "自证失败(bb)",
          f"首报错标签={lab}（期望 自证失败(bb)）\n{out}")

    # ------------------------------------------------------------------
    # I(b12) 把 `coalesce(max(scale_version), '')` 去掉 max → (b12) 抓
    #   🛑 去掉 max 后语法仍合法（单行时行为甚至相同）⇒ 这是纯粹的"判据忠实性"测法。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, "register", "coalesce(max(scale_version), '')", "coalesce(scale_version, '')",
        expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b12) 去掉 `max(scale_version)`（去掉后单行行为相同、语法仍合法）→ 自证 (b12) 必须抓住",
          (not ok) and lab == "自证失败(b12)",
          f"首报错标签={lab}（期望 自证失败(b12)）\n{out}")

    # ------------------------------------------------------------------
    # I(c1) 把 `UPDATE scale` 写成 `UPDATE "scale"`（语义等价）→ (c1) 抓
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, "deprecate", "    UPDATE scale\n", '    UPDATE "scale"\n', expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check('I(c1) 把 `UPDATE scale` 写成 `UPDATE "scale"`（语义等价）→ 自证 (c1) 必须抓住',
          (not ok) and lab == "自证失败(c1)",
          f"首报错标签={lab}（期望 自证失败(c1)）\n{out}")

    # ------------------------------------------------------------------
    # I(c4) 🎯 **本脚本核心用例**：从状态迁移 UPDATE 里删掉 `status <> 'deprecated'`
    #   🛑 修复前：判据是裸的 `status <> 'deprecated'`，被函数体**注释**满足
    #      ⇒ 本注入会打印"自证通过"（静默假绿）。
    #      修复后：判据作用在剥注释后的代码态 + 从 UPDATE scale 起锚 ⇒ 报 (c4)。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, "deprecate",
        "       AND tenant_id  = p_tenant_id\n       AND status    <> 'deprecated';",
        "       AND tenant_id  = p_tenant_id;", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(c4) 🎯 从状态迁移 UPDATE 里删掉 `status <> 'deprecated'`（注释里仍有该词）→ 自证 (c4) 必须抓住",
          (not ok) and lab == "自证失败(c4)",
          f"首报错标签={lab}（期望 自证失败(c4)）\n{out}")

    # ------------------------------------------------------------------
    # I(c5) 只删【状态迁移 UPDATE】的租户维度（步骤 (4) 的 SELECT 不动）→ (c5) 抓
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, "deprecate",
        "       AND tenant_id  = p_tenant_id\n       AND status    <> 'deprecated';",
        "       AND status    <> 'deprecated';", expect=1)
    still_has_loose = "AND tenant_id = p_tenant_id" in func_body(inj_body, "deprecate")
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(c5) 只删状态迁移 UPDATE 的租户维度（步骤(4)的 SELECT 不动）→ 自证 (c5) 必须抓住",
          (not ok) and lab == "自证失败(c5)",
          f"首报错标签={lab}（期望 自证失败(c5)）\n{out}")
    check("I(c5-note) 锚点必要性自证：注入后函数体里【仍含】不带锚点就会命中的 `AND tenant_id = p_tenant_id`（来自步骤(4)的 SELECT）",
          still_has_loose,
          "🛑 若这一条为 False，说明 V19 的 deprecate_scale 里已经没有那条「同款子句」了，"
          "那么 I(c5) 就证明不了「不带锚点的判据会假绿」—— 本用例的论证力会失效。")

    # ------------------------------------------------------------------
    # I(d) 抹掉登记行 → (d) 抓
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
DELETE FROM schema_migration WHERE version = 'V19';
""")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(d) 抹掉迁移登记行 → 自证 (d) 必须抓住",
          (not ok) and lab == "自证失败(d)",
          f"首报错标签={lab}\n{out}")

    # ------------------------------------------------------------------
    # I(d3) 摘掉授权段且函数为新建（proacl IS NULL）→ (d3) 必须抓住
    #   🛑 (d2) 在本仓脚本建的库上恒真（owner 对自有函数的 EXECUTE 隐含）⇒
    #      必须先把函数 DROP 掉再让本文件新建，proacl 初始为 NULL 才是
    #      "授权段根本没执行"的**真实形态**。
    # ------------------------------------------------------------------
    grant_re = re.compile(r"DO\s*\n\$v19_grant\$.*?\$v19_grant\$;", re.S)
    stripped, n_sub = grant_re.subn("-- 注入：整个第 3 节授权段被摘掉\n", mig, 1)
    if n_sub != 1:
        raise SystemExit(f"❌ 未能定位第 2 节 $v19_grant$ 段（匹配 {n_sub} 次）—— 迁移结构已变。")
    inj = stripped.replace(
        "CREATE OR REPLACE FUNCTION register_scale(",
        "DROP FUNCTION IF EXISTS register_scale(uuid,uuid,text,text,text,jsonb);\n"
        "CREATE OR REPLACE FUNCTION register_scale(", 1)
    inj = inj.replace(
        "CREATE OR REPLACE FUNCTION deprecate_scale(",
        "DROP FUNCTION IF EXISTS deprecate_scale(uuid,uuid);\n"
        "CREATE OR REPLACE FUNCTION deprecate_scale(", 1)
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(d3) 摘掉授权段且函数为新建（proacl IS NULL）→ 自证 (d3) 必须抓住",
          (not ok) and lab == "自证失败(d3)",
          f"首报错标签={lab}（期望 自证失败(d3)）\n{out}")

    # ------------------------------------------------------------------
    # I(e1) 让 e1 建档的 name 与断言值不符（定义段全过）→ (e1) 抓
    # ------------------------------------------------------------------
    old_e1 = ("        -- e1 建档 → CREATED，且行确实落库（6 列同时对上）\n"
              "        -- ---------------------------------------------------------------\n"
              "        v_mode := register_scale(v_ta, v_scale_a1, 'primary', 'v1', 'V19 探针量表', v_dims);")
    new_e1 = ("        -- e1 建档 → CREATED，且行确实落库（6 列同时对上）\n"
              "        -- ---------------------------------------------------------------\n"
              "        v_mode := register_scale(v_ta, v_scale_a1, 'primary', 'v1', 'V19 探针量表X', v_dims);")
    n = mig.count(old_e1)
    if n != 1:
        raise SystemExit(f"❌ I(e1) 的锚在迁移里出现 {n} 次（期望 1）—— 锚点失效，须修脚本。")
    inj = mig.replace(old_e1, new_e1, 1)
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(e1) 让 e1 建档的 name 与断言值不符（定义段全过）→ 自证 (e1) 必须抓住",
          (not ok) and lab == "自证失败(e1)",
          f"首报错标签={lab}（期望 自证失败(e1)）\n{out}")

    # ------------------------------------------------------------------
    # I(e3) 🆕 让版本比对恒假 → 版本冲突不再抛错 → (e3) 抓
    #   🛑 为什么不用"删掉 RAISE"这种注入：那会同时让 (bb) 失败（RAISE 没了），
    #      首报错标签会落到 (bb)，于是"抛的是版本分支"这件事证不出来。
    #      把 `<>` 换成 `false` 只让**行为**改变，判据 (bb)/(b12) 全部保持成立。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, "register", "IF v_existing_ver <> p_scale_version THEN", "IF false THEN",
        expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(e3) 🆕 让版本比对恒假（`<>` → `false`）→ 自证 (e3) 必须抓住（V19 特有：版本冲突必须 RAISE）",
          (not ok) and lab == "自证失败(e3)",
          f"首报错标签={lab}（期望 自证失败(e3)）\n{out}")

    # ------------------------------------------------------------------
    # I(e4) 🆕 让"是本租户的"判定恒真（`v_mine = 1` → `true`）→ (e4) 抓
    #   🛑 这会跳过"另一租户"分支：跨租户撞号时版本相同 ⇒ 返回 ALREADY_EXISTS
    #      ⇒ (e4) 报"没有抛错"。判据 (b7)/(b8) 全部保持成立（RAISE 与那次读都还在）。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, "register", "    IF v_mine = 1 THEN", "    IF true THEN", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(e4) 🆕 让『是本租户的』判定恒真（`v_mine = 1` → `true`）→ 自证 (e4) 必须抓住",
          (not ok) and lab == "自证失败(e4)",
          f"首报错标签={lab}（期望 自证失败(e4)）\n{out}")

    # ------------------------------------------------------------------
    # I(e6) 让 e6 的裸 INSERT 违反 NOT NULL → (e6) 抓
    # ------------------------------------------------------------------
    old_e6 = "            VALUES (v_scale_a2, v_ta, 'primary', 'v1', 'V19 裸插对照', v_dims, 'active');"
    new_e6 = "            VALUES (v_scale_a2, v_ta, 'primary', 'v1', NULL, v_dims, 'active');"
    n = mig.count(old_e6)
    if n != 1:
        raise SystemExit(f"❌ I(e6) 的锚在迁移里出现 {n} 次（期望 1）—— 锚点失效，须修脚本。")
    inj = mig.replace(old_e6, new_e6, 1)
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(e6) 让 e6 的裸 INSERT 违反 NOT NULL（name 置空）→ 自证 (e6) 必须抓住",
          (not ok) and lab == "自证失败(e6)",
          f"首报错标签={lab}（期望 自证失败(e6)）\n{out}")

    # ------------------------------------------------------------------
    # C2 等价形态：给两个函数体首尾加空行 → 必须仍然全绿
    # ------------------------------------------------------------------
    eq = mig.replace(func_body(mig, "register"), "\n" + func_body(mig, "register") + "\n\n", 1)
    eq = eq.replace(func_body(eq, "deprecate"), "\n" + func_body(eq, "deprecate") + "\n\n", 1)
    ok, out = run_sql(eq)
    check("C2 函数体首尾加空行（等价形态）→ 必须仍然全绿",
          ok and "自证通过" in out, out)

    # ------------------------------------------------------------------
    # C3 等价形态：在 DECLARE 段加一个未使用的变量 → 必须仍然全绿
    # ------------------------------------------------------------------
    eq = replace_func_body(mig, "register", "    v_ctx_before   text;",
                           "    v_ctx_before   text;\n    v_noise        int := 0;", expect=1)
    ok, out = run_sql(eq)
    check("C3 在 DECLARE 段加一个未使用变量（等价形态）→ 必须仍然全绿",
          ok and "自证通过" in out, out)

    # ------------------------------------------------------------------
    # C4 元门禁：各注入组的首报错标签必须互不相同
    # ------------------------------------------------------------------
    seen = {}
    cases = {
        "a0": (mig, True),
        "a1": (inject_before_guard(mig, "\nALTER POLICY tenant_isolation ON scale USING (true) WITH CHECK (true);\n"), False),
        "a": (inject_before_guard(mig, "\nDROP FUNCTION register_scale(uuid, uuid, text, text, text, jsonb);\n"), False),
        "b4": (inject_before_guard(replace_func_body(
            mig, "register", "INSERT INTO scale (scale_id", 'INSERT INTO "scale" (scale_id',
            expect=1), "\n"), False),
        "c1": (inject_before_guard(replace_func_body(
            mig, "deprecate", "    UPDATE scale\n", '    UPDATE "scale"\n', expect=1), "\n"), False),
        "c4": (inject_before_guard(replace_func_body(
            mig, "deprecate",
            "       AND tenant_id  = p_tenant_id\n       AND status    <> 'deprecated';",
            "       AND tenant_id  = p_tenant_id;", expect=1), "\n"), False),
        "bb": (inject_before_guard(replace_func_body(
            replace_func_body(mig, "register", "版本【不可覆盖】", "版本【不得覆盖】", expect=1),
            "register", "的「不可覆盖」静默违反", "的「不得覆盖」静默违反", expect=1), "\n"), False),
    }
    for k, (txt, as_super) in cases.items():
        ok2, out2 = run_sql(txt, as_super=as_super)
        seen[k] = label_of_exact(out2, ALL_LABELS) if not ok2 else "(未报错)"
    uniq = len(set(seen.values())) == len(seen) and "(未报错)" not in seen.values()
    check("C4 各注入组的【首报错标签】必须互不相同（防整段只在一处报错）",
          uniq, f"标签分布 = {seen}")

    # ------------------------------------------------------------------
    # C5 元门禁：全部失败分支字面量必须都在迁移文本里（防"删掉自证也算通过"）
    # ------------------------------------------------------------------
    need = ["自证失败(a0)", "自证失败(a1)", "自证失败(a)",
            "自证失败(b1)", "自证失败(b2)", "自证失败(b3)", "自证失败(b4)",
            "自证失败(b5)", "自证失败(b6)", "自证失败(b7)", "自证失败(b8)",
            "自证失败(b9)", "自证失败(b12)", "自证失败(bb)",
            "自证失败(c1)", "自证失败(c2)", "自证失败(c3)", "自证失败(c4)",
            "自证失败(c5)",
            "自证失败(d)", "自证失败(d2)", "自证失败(d3)",
            "自证失败(e1)", "自证失败(e2)", "自证失败(e3)", "自证失败(e4)",
            "自证失败(e6)", "自证失败(e7)", "自证失败(e8)", "自证失败(e9)",
            "自证失败(e10)", "自证失败(f2)"]
    missing = [s for s in need if s not in mig]
    check("C5 全部自证失败分支字面量必须都在迁移文本里",
          not missing, f"缺失: {missing}")

    # ------------------------------------------------------------------
    # C7 🆕 元门禁：全部语句形态判据必须作用在【剥注释后的代码态】上
    #   🛑 这条防的是"有人把某条判据退回 v_*_body" —— 那会让 (c4) 的假绿重现。
    #      判据本身很简单：迁移里不得出现 `v_*_body !~ '...'` / `v_*_body ~ '...'`
    #      这种**直接在含注释的原文上做正则**的形态。
    # ------------------------------------------------------------------
    offenders = re.findall(r"v_\w+_body\s*[!~]\s*'", mig)
    check("C7 🆕 迁移里不得直接在 v_*_body（含注释）上做正则匹配（必须用 v_*_code）",
          not offenders,
          f"违规 = {offenders}\n"
          f"🛑 修复目标：先 `SELECT string_agg(regexp_replace(ln,'--.*$',''),E'\\n') INTO v_*_code`，"
          f"再在 v_*_code 上匹配。否则判据会被 prosrc 里的注释满足（(c4) 的假绿已实测）。")

    # 同时断言 code 构造真的存在（否则上面那条会"因为变量不存在"而假通过）
    has_code_build = (mig.count("INTO v_register_code") == 1
                      and mig.count("INTO v_deprecate_code") == 1
                      and "regexp_replace(ln, '--.*$', '')" in mig)
    check("C7b 两条 code 构造语句真的存在（防 C7 因变量缺失而假通过）",
          has_code_build,
          f"INTO v_register_code={mig.count('INTO v_register_code')} "
          f"INTO v_deprecate_code={mig.count('INTO v_deprecate_code')}")

    # ------------------------------------------------------------------
    # C6 DDL 净效果等价性（本轮 code 化的处置依据）
    #
    #    本仓纪律：**迁移文本一改，已应用的库就必须做一次显式处置** ——
    #    要么证明 DDL 净效果相同（⇒ 对齐 checksum），要么承认变了（⇒ 重建库）。
    #    本轮为修 (b5)/(c4) 的假绿改了 V19 文本（**只落在第 4 节自证**，不含 DDL）
    #    ⇒ 必须给出"净效果相同"的机械证据。
    # ------------------------------------------------------------------
    print()
    print("   [C6] 构造改前/改后两份文本并分别测其 DDL 净效果 …")
    after = mig
    before = strip_code_normalization(after)
    before_ok = before != after
    print(f"        before/after 文本长度差 = {len(after) - len(before)} 字符"
          f"（两条 code 构造 + 断言处 _code→_body；{'' if before_ok else '🛑 还原未生效'}）")

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
        check("C6a 改前/改后文本的 V19 DDL 净效果完全相同（签名/函数体/权限/登记）",
              before_ok and same, det or f"before_ok={before_ok}")

        # ---- C6b 判别力自证：净效果比对必须【对真实改动有反应】----
        #      🛑 选点纪律（119 的 C6b 注释 + 本轮教训）：
        #        ① 必须是【函数体内】的真实代码改动（本比对只看 prosrc + 签名 + 权限 + 登记）；
        #        ② 必须【不触发 V19 自身的任何一条自证】—— 否则整条链 apply 会失败，
        #           C6b 变成"测不出来"而不是"测出有反应"。
        #           首跑选的是 deprecate 的 (5) UPDATE 谓词，结果 V19 自己的 (c4) 报红、
        #           链条应用失败 ⇒ C6b 无法完成（选点不当，非 V19 缺陷）。
        #        ⇒ 改用 deprecate 步骤 (4) 的 SELECT：它【不被任何 b/c 正则判据覆盖】，
        #          加 `AND true` 是语义等价的真实代码改动，行为段 (e7)(e9) 也不受影响。
        tampered = replace_func_body(
            after, "deprecate",
            "     WHERE scale_id = p_scale_id AND tenant_id = p_tenant_id;",
            "     WHERE scale_id = p_scale_id AND tenant_id = p_tenant_id\n       AND true;",
            expect=1)
        rebuild_db(REV_DB)
        apply_chain_to_template(REV_DB, tampered)
        eff_tampered = ddl_effect(REV_DB)
        reacted = eff_tampered != eff_after
        rebuild_db(REV_DB)
        apply_chain_to_template(REV_DB, after)
        eff_restored = ddl_effect(REV_DB)
        restored = eff_restored == eff_after
        check("C6b 判别力自证：往函数体注入一处真实改动 ⇒ 净效果比对必须变红（撤销后恢复）",
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
    print(f"===== 120 反向验证结果: {passed}/{len(results)} 通过 =====")
    print()
    if passed == len(results):
        print("⇒ 结论：V19 的每一类自证都【有牙齿】：")
        print("   · 注入的每一类错误都被【对应标签】的自证抓住（首报错标签精确对上）；")
        print("   · 语义等价的正确形态仍然全绿（判据没有变成过度收紧的误伤）；")
        print("   · 各注入组的标签互不相同 ⇒ '标签对上'这件事有信息来源，不是伪证；")
        print("   · 语句形态判据全部作用在剥注释后的代码态上（C7 常驻断言）；")
        print("   · DDL 净效果对真实改动有反应，而改前/改后净效果相同。")
        print()
        print("  🛑 本轮由此修掉了 V19 的真实缺陷：**(c4) 判据被函数体注释满足** ⇒")
        print("     并发正确性这条判据此前从未真正生效（每次自证都绿）。")
        print("     以及 (b5) 无锚点（2 处代码 + 1 处注释同时命中）。")
        print("     现已把'逐行剥注释'推广到全部语句形态判据，并保留锚点。")
    else:
        print("⇒ 🛑 结论：存在未通过项，V19 的自证尚不可信 —— 不得据此宣称缺口已收口。")
    if keep:
        print(f"\n（--keep：临时库 {REV_DB} 已保留）")
    else:
        print(f"\n（临时库 {REV_DB} 已清理）")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())