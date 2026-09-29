#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V18「设备建档通路」的**反向验证**（反证法 / 有牙齿的门禁）。

【这个脚本为什么必须存在】

  V18 是本仓第二条「不建表、只建两个 PL/pgSQL 原语」的迁移，它的自证 (b)(c)
  有 12 条断言全部是**读 prosrc 做正则匹配**。这类断言有一个共同的失效形态：

      函数体看着对、跑起来不对        → 由自证 (e) 的行为段兜住
      断言看着在测某件事，实际测的是另一件事
                                      → 只有"受控注入 + 断言必须报错"能证明它测对了
      断言看着在跑、其实证据的**前提**不成立
                                      → 只有"换掉前提再跑一次"能证明它没在假装

  第三类就是本脚本本轮抓出的东西（见下），它比前两类更隐蔽：**自证会报错，
  但报的是归因错误的错** —— 而"有一个错"看起来很像"门禁在工作"。

【🛑🛑 本脚本抓出的真实缺陷（不是"为了写脚本而写脚本"）】

  **V18 缺 (a0)/(a1) 能力守卫 + (b5)/(c4) 判据没有锚点/没剥注释**
  —— 其中 (c4) 的假绿已由受控注入**实测复现**。

  实测（本脚本 I(a0) 用例，命令 = 用 `postgres` 跑 V18 全文）：

      psql:V18__device_provisioning_primitive.sql:1314: 错误:
        V18 自证失败(e5): 租户 B 用【租户 A 已占用的 device_id】建档没有抛错
        （返回值 = ALREADY_EXISTS）。🛑 这是本迁移的核心机制二的封堵点：…

  这条消息读起来像 **register_device 坏了**。真因恰好相反：

      postgres 的 rolbypassrls = t（实测）⇒ BYPASSRLS 绕过行级安全 ⇒
      `set_config('app.tenant_id', 租户B)` 之后，register_device 里那条
          SELECT count(*) FROM device WHERE device_id = p_device_id
      **仍然看得见租户 A 的那一行** ⇒ v_mine = 1 ⇒ 判定链落到"是我的"分支
      ⇒ 返回 ALREADY_EXISTS ⇒ (e5) 报红。

  即：**函数完全正确，是证据的效力前提（角色受 RLS 约束）不成立。**
  V18 依赖 RLS 的判据共 4 条（(e3) 不落库 / (e5) 零行 / (e5) 对照 / (e8) 可见性），
  全部建立在同一个前提上。没有 (a0) 时，这 4 条会以"函数坏了"的面目报红，
  而若有人照字面去改函数，会改坏一个本来正确的实现。

  ⇒ 处置（本轮已落地）：**从 V19 逐字回补 (a0)/(a1) 到 V18 的 guard 开头**，
    并同步第 4 节文件头的设计说明与 diff 注释；文本改动**不含 DDL**，
    故按本仓纪律给出"DDL 净效果不变"的机械证据（C6）并对齐 Flyway checksum。
    证据：修复前 = (e5) 红；修复后 = 应用角色全绿 + BYPASSRLS 角色报 **(a0)**。

【🛑🛑 本脚本（同一次运行）另外抓出的两处真实缺陷 —— 判据被【注释】满足】

  成因（两者同一个）。PG 把 `AS $tag$ ... $tag$` 之间的内容**原样**存进
  pg_proc.prosrc，**注释也在里面**。V18 的函数体注释里逐字写着它自己要做的事：

      --     🛑 谓词里带 `status <> 'retired'` 是为了让这条 UPDATE 在并发下**正确**：
      --       ① INSERT ... ON CONFLICT 的 ROW_COUNT=1 ⇒ 真的是本次建出来的

  ⇒ ① **(c4)** 的初版判据 `status\s*<>\s*'retired'` **既无锚点、也没剥注释**，
       于是"删掉代码处那句谓词"**完全不会被抓住**。
     实测（I(c4)）：
         注入后、修复前 ⇒ 自证打印"自证通过"（静默假绿）
         注入后、修复后 ⇒ 自证报 (c4)
     🛑 注意这不是"少写了一个条件"：它意味着**并发正确性这条判据一直没在工作**，
        而自证每次都在绿。这是本仓定义的"假绿比假红危险得多"的教科书案例。
  ⇒ ② **(b5)** 的初版判据 `ON\s+CONFLICT` 同样无锚点，而函数体里有
       **2 处代码 + 1 处注释**同时命中 ⇒ 连"哪一处满足了判据"都说不清。
     用例 I(b5) 钉住。

  ⇒ 处置（本轮已落地，纯 PL/pgSQL，**不产生任何 DDL**）：
     把 V18 已有的"逐行剥注释"机制（原本只用在 (b1)(b2)(b3) 上）**推广到全部
     语句形态判据** —— 先 `SELECT string_agg(regexp_replace(ln,'--.*$',''),E'\n')`
     得到 `v_register_code` / `v_retire_code`，再在这些**代码态**上匹配；
     **同时保留锚点**（`INSERT INTO device\M` / `UPDATE device\M` 起锚、以 `;` 为界）。
     只做剥注释仍会丢失适用范围，只做锚点仍会被注释命中 —— **两者都需要**。

【V18 的 (a1) 与本脚本的关系】

  (a0) 排除了"角色完全绕过 RLS"；(a1) 排除"RLS 策略被改动/缺失"
  （例如被改成 `USING (true)`）—— 后者会让同样的 4 条判据**静默假绿**
  （所有租户互相可见，而自证全绿、连一条报错都没有）。用例 I(a1) 钉住。

【用例矩阵】

  注入组（必须报错，且【首报错的标签】必须对上）
    I(a0)  换成 BYPASSRLS 角色跑                        → 自证 (a0)（🆕 本轮修复的靶心）
    I(a1)  把 device 的 RLS 策略改成 USING(true)        → 自证 (a1)（🆕）
    I(a)   删掉 register_device                         → 自证 (a)
    I(b4)  把 `INSERT INTO device` 写成 `INSERT INTO "device"`（语义等价但绕过判据） → (b4)
    I(b7)  把 RAISE 里的"另一租户"改成"其他租户"（注释里仍有"另一租户"） → (b7)
              🛑 这条正是"不带锚点的判据会被注释满足 ⇒ 假绿"的机械测法
    I(b9)  让 INSERT 不含 tenant_id（列与值同步删，语法仍合法） → (b9)
    I(b5)  🆕 从 INSERT 语句里摘掉 ON CONFLICT（语法仍合法） → (b5)
              🛑 修复前该判据无锚点：`ON CONFLICT` 在函数体里有 2 处代码 + 1 处注释命中
    I(c1)  把 `UPDATE device` 写成 `UPDATE "device"`     → (c1)
    I(c4)  🆕 从状态迁移 UPDATE 里删掉 `status <> 'retired'` → (c4)
              🛑 修复前该判据被函数体【注释】满足 ⇒ 静默假绿（本脚本核心用例）
    I(c5)  只删【状态迁移 UPDATE】的租户维度（步骤(4)的 SELECT 不动） → (c5)
              🛑 与 118 的 I(b7)/I(c7) 同款：对"不带锚点的判据"必然假绿
    I(d)   抹掉迁移登记行                                → 自证 (d)
    I(d3)  摘掉授权段 + 函数新建（proacl IS NULL）        → 自证 (d3)（(d2) 此处恒真）
    I(e1)  让 e1 建档的 model 与断言值不符               → 自证 (e1)（定义段全过）
    I(e6)  让 e6 的裸 INSERT 违反 NOT NULL               → 自证 (e6)
  对照组（必须仍然全绿 —— 防"判据收紧后误伤等价形态"）
    C1 原样重跑 V18                                     → 必须全绿
    C2 给两个函数体首尾各加空行                          → 必须全绿
    C3 在 DECLARE 段加一个未使用的变量                   → 必须全绿
  元门禁
    C4 各注入组的【首报错标签必须互不相同】              → 防"整块自证只会在第一行报错"
    C5 全部失败分支字面量必须都在迁移文本里              → 防"删掉自证也算通过"
    C6 🆕 DDL 净效果等价性（本轮改 V18 文本的处置依据）
       · 把 (a0)/(a1) 两段还原掉 ⇒ 断言其 DDL 净效果与改后文本**完全相同**；
       · 判别力自证：往函数体注入一处真实改动 ⇒ 净效果比对必须变红（撤销后恢复）。

【🛑 本脚本自带的"前提对账"（比 118 多一步）】

  脚本在开跑前先做两件事，因为它们恰好是本轮缺陷的同族：
    ⑧ 库中 V18 的 Flyway checksum == 当前迁移文本的复算值
       —— 把"改了文本必须显式处置库"变成**每次运行都成立的机械断言**
          （117 的纪律；本轮 118 当年就是"全绿但 checksum 失配"）。
    ⑨ 打印当前角色属性（rolsuper / rolbypassrls）与 device 的 RLS 策略定义
       —— 让 I(a0)/I(a1) 这两条"前提类"用例的输出有可核对的现场。

【与 116 / 117 / 118 的分工】
  116 = V16 迁移【逻辑】是否正确（跨租户引用完整性）。
  117 = 迁移【文本】与已应用【库】是否一致（防"改了文本没处置库"）。
  118 = V17 迁移【自证】是否真的有牙齿（手环绑定通路）。
  119 = V18 迁移【自证】是否真的有牙齿（设备建档通路）+ 本轮 (a0)/(a1) 回补的证据。

用法:
  python verification/119_device_provisioning_reverse_verification.py
  python verification/119_device_provisioning_reverse_verification.py --keep   # 保留临时库排查
环境变量: DY_PSQL_EXE / DY_PG_HOST / DY_PG_PORT / DY_PG_USER / DY_PG_PASSWORD
          DY_PG_SUPER_USER / DY_PG_SUPER_PASSWORD / DY_PG_DB / DY_DEVICE_REV_DB
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
REV_DB = os.environ.get("DY_DEVICE_REV_DB", "diaoyuanyun_device119")

HERE = Path(__file__).resolve().parent                     # verification
SKELETON = HERE.parent                                     # skeleton
MIGRATION_DIR = SKELETON / "dy-app" / "src" / "main" / "resources" / "db" / "migration"
MIGRATION = MIGRATION_DIR / "V18__device_provisioning_primitive.sql"


def env_with(pwd: str) -> dict:
    return dict(os.environ, PGPASSWORD=pwd)


def psql(user: str, pwd: str, db: str, args, timeout: int = 900):
    cmd = [PSQL, "-h", HOST, "-p", PORT, "-U", user, "-d", db, "-X"] + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env=env_with(pwd), timeout=timeout)


def migration_scripts():
    """按【数值】版本号排序 —— 字符串排序会把 V10 排在 V2 前面（与 117 / 118 同款）。"""
    files = sorted(MIGRATION_DIR.glob("V*__*.sql"),
                   key=lambda p: int(re.match(r"V(\d+)__", p.name).group(1)))
    if not files:
        raise SystemExit(f"❌ 未在 {MIGRATION_DIR} 找到任何 V*__*.sql")
    return files


def flyway_checksum(path: Path) -> int:
    """复算 Flyway 的 checksum（与 117 / 118 同口径，CRC32 逐行 UTF-8 有符号）。"""
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

# 自证块的起点：用它把"前半"与"自证及其后"切开
GUARD_RE = re.compile(r"DO\s*\n\$v18_guard\$")


def func_body(mig: str, tag: str) -> str:
    """从迁移文本里切出 `$v18_<tag>$ ... $v18_<tag>$;` 之间的函数体原文。

    🛑 为什么能从【文件文本】切，而不是从库里读：
       118 已用探针证明 —— 库中 `pg_proc.prosrc` 与这里的切片【逐字相同】
       （bind/unbind 的 strip 后完全相等）。这是因为 PG 把 `AS $tag$ ... $tag$`
       之间的内容原样存进 prosrc。
       ⇒ 于是"按文件文本构造注入"与"库里实际存的函数体"是同一个对象，
         不需要先 DROP 再 CREATE 就能预知注入结果。
    """
    open_tag = f"$v18_{tag}$"
    a = mig.index(open_tag) + len(open_tag)
    b = mig.index(f"$v18_{tag}$;")
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
    """🛑 把注入插到 $v18_guard$ 自证块【之前】。

    注入必须【插在自证块之前】。追加在文件末尾等于"考完试再改答案" ——
    那时自证已经跑完并成功，注入的错误没有任何自证去抓，
    于是整组用例假失败（116 第一版踩过）。
    """
    m = GUARD_RE.search(mig)
    if not m:
        raise SystemExit("❌ 未在迁移中找到 $v18_guard$ 自证块 —— 迁移结构已变，"
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
       I(a0) 用 as_super=True 跑（那是"换个角色"这条前提类用例的全部内容）。
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


def apply_chain_to_template(db: str, v18_text: str):
    """把【整条迁移链】应用到临时库，其中 V18 用传入的文本（其余用磁盘原文）。

    🛑 为什么 C6 必须用【整条链】而不是只应用 V18：
       V18 的入参守卫依赖 V2 建的 `device`/`store` 表、V7 的 `customer` 等
       —— 只应用一条会在自证前置就报「表 device 不存在」。
       这与 117 / 118 的做法一致：临时库必须从零按链条建。
    """
    scripts = migration_scripts()
    # 🛑 `newline=""` 不是可选项：Windows 上 NamedTemporaryFile("w") 默认把 `\n` 转成
    #    `\r\n` ⇒ 喂给 psql 的文本与磁盘原文不再逐字相同 ⇒ 重建库里的 pg_proc.prosrc
    #    变成 `\r\r\n` 形态 ⇒ C6 的"净效果逐字比对"报出一条**完全归因错误**的红
    #    （会让人去"修"一个本来正确的迁移）。本条的邻居探针已实测踩过。
    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False,
                                     encoding="utf-8", newline="") as f:
        f.write("\\set ON_ERROR_STOP on\n")
        for p in scripts:
            text = v18_text if p.name == MIGRATION.name else p.read_text(encoding="utf-8")
            # 逐条写入（不用 \i），这样 V18 用改前/改后文本、其余用磁盘原文
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
    """V18 的「DDL 净效果」指纹。

    🛑 为什么要 Q4 型指纹而不是"全库模式对账"（117 做的那件事）：
       117 对账的是"已应用库 vs 重建库"，噪声模型是"模式 vs 模式"。
       而本脚本要回答的问题窄得多：**V18 这一条文本的两份版本，客观效果是否相同**。
       ⇒ 指纹 = V18 实际产生/改变的四类东西：
           ① 函数签名（proname + 参数 + 返回类型）
           ② 函数体原文（prosrc）—— V18 唯一有内容的产出
           ③ 函数 EXECUTE 权限（第 2 节 GRANT 段的效果）
           ④ schema_migration 中 V18 一行的 description（第 3 节登记段的效果）
       这四项覆盖 V18 第 1/2/3 节；第 4 节是自证（无产出）、第 5 节是注释（无产出）。
       🛑 本轮对 V18 的改动【只落在第 4 节自证与其说明注释】⇒ 这四项必须逐字不变。
    """
    sql = """
    SELECT 'fn|' || p.proname || '|' || pg_get_function_arguments(p.oid) || '|' ||
           pg_get_function_result(p.oid) || '|' || md5(p.prosrc)
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname IN ('register_device','retire_device')
    UNION ALL
    SELECT 'prosrc|' || p.proname || '|' || p.prosrc
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname IN ('register_device','retire_device')
    UNION ALL
    SELECT 'priv|' || p.proname || '|' ||
           COALESCE(array_to_string(p.proacl,' | '),'(NULL)') || '|' ||
           has_function_privilege(current_user, p.oid, 'EXECUTE')::text
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname IN ('register_device','retire_device')
    UNION ALL
    SELECT 'reg|V18|' || COALESCE(description,'')
      FROM schema_migration WHERE version = 'V18'
    """
    r = psql(SUPER_USER, SUPER_PWD, db, ["-A", "-t", "-c", sql])
    if r.returncode != 0:
        raise SystemExit(f"❌ DDL 净效果快照失败({db})：\n" + (r.stderr or ""))
    return "\n".join(x for x in (r.stdout or "").splitlines() if x.strip())


# ---------------------------------------------------------------------------
# 本轮 (a0)/(a1) 回补的"改前形态"（供 C6 还原用）
# ---------------------------------------------------------------------------

# 🛑 还原的取法是【保守】的：只把 (a0)/(a1) 两段**可执行语句**拿掉，
#    保留其说明注释。若净效果仍相同，那对"连注释都没有"的完整改前文本也必然相同。
A0_START = "    SELECT rolbypassrls INTO v_bypass FROM pg_roles WHERE rolname = current_user;"
A0_END = "    -- ------------------------------------------------------------------\n    -- (a) 函数存在"


def strip_capability_guards(mig: str) -> str:
    """把 (a0)/(a1) 两段还原掉 —— 即"本轮修复之前"的 V18 形态。"""
    a = mig.index(A0_START)
    b = mig.index(A0_END)
    if not (0 < a < b):
        raise SystemExit("❌ 还原 (a0)/(a1) 的切片失败 —— 迁移结构已变，本脚本锚点失效。")
    removed = mig[a:b]
    if "rolbypassrls" not in removed or "pg_policies" not in removed:
        raise SystemExit("❌ 还原切片里没有同时含 rolbypassrls 与 pg_policies —— 切片范围不对。")
    return mig[:a] + mig[b:]


def main():
    keep = "--keep" in sys.argv
    if not MIGRATION.is_file():
        print(f"❌ 找不到被测迁移: {MIGRATION}")
        return 2
    mig = MIGRATION.read_text(encoding="utf-8")

    print("== 119 V18 设备建档通路 · 反向验证 ==")
    print(f"   被测迁移 : {MIGRATION.name}")
    print(f"   行为验证 : {DEV_DB}（注入包在 BEGIN…ROLLBACK 里，不落地）")
    print(f"   等价验证 : {REV_DB}（临时库，仅用于 C6）")
    print(f"   文本校验 : checksum={flyway_checksum(MIGRATION)}  len={len(mig)}  "
          f"body_register={len(func_body(mig, 'register'))}  "
          f"body_retire={len(func_body(mig, 'retire'))}")
    print()

    # ------------------------------------------------------------------
    # ⑧ 前提对账一：库中登记的 checksum == 当前文本复算值
    #    🛑 把 117 的纪律变成"本脚本每次运行都验证"的事实 ——
    #       "改了迁移文本却没处置已应用的库"是 118 当年全绿却失配的那个洞。
    # ------------------------------------------------------------------
    r = psql(APP_USER, APP_PWD, DEV_DB, [
        "-A", "-t", "-c",
        "SELECT checksum FROM flyway_schema_history WHERE version = '18';"])
    db_cks = (r.stdout or "").strip()
    want_cks = str(flyway_checksum(MIGRATION))
    if r.returncode != 0 or not db_cks:
        raise SystemExit(f"❌ 读不到真库中 V18 的 checksum：\n{(r.stderr or '').strip()}")
    if db_cks != want_cks:
        raise SystemExit(
            f"❌ 前提对账失败：真库登记的 V18 checksum = {db_cks}，而当前迁移文本复算 = {want_cks}。\n"
            f"   ⇒ 说明【迁移文本被改过，但已应用的库没有处置】。\n"
            f"   正确处理（本仓纪律）：要么证明新旧文本的 DDL 净效果相同 ⇒ 对齐 checksum\n"
            f"   （UPDATE flyway_schema_history SET checksum = {want_cks} WHERE version = '18';），\n"
            f"   要么承认 DDL 变了 ⇒ 重建库 / 补新迁移。最坏的反应是「改个数字就完事」。")
    print(f"   前提对账 : 真库 V18 checksum = {db_cks} == 文本复算 = {want_cks}  ✅")
    print()

    # ------------------------------------------------------------------
    # ⑨ 前提对账二：把 I(a0)/I(a1) 两条"前提类"用例的现场量出来
    # ------------------------------------------------------------------
    r = psql(SUPER_USER, SUPER_PWD, DEV_DB, [
        "-A", "-t", "-F", " | ", "-c",
        "SELECT rolname, rolsuper::text, rolbypassrls::text FROM pg_roles "
        "WHERE rolname IN ('diaoyuanyun','postgres') ORDER BY rolname;",
        "-c",
        "SELECT tablename, policyname, "
        "CASE WHEN qual LIKE '%app.tenant_id%' THEN 'qual:tenant_id' ELSE 'qual:??' END, "
        "CASE WHEN with_check LIKE '%app.tenant_id%' THEN 'wc:tenant_id' ELSE 'wc:??' END "
        "FROM pg_policies WHERE schemaname='public' AND tablename='device';"])
    print("   前提现场 : 角色属性 / device 的 RLS 策略")
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

    def label_of(out: str, labels) -> str:
        """取【首个】出现的自证失败标签 —— 断言的是"谁先抓住"，不是"谁提到过"。"""
        hits = [(out.find(lab), lab) for lab in labels if lab in out]
        hits = [(i, lab) for i, lab in hits if i >= 0]
        return min(hits)[1] if hits else "(无标签)"

    # 🛑 前缀陷阱核对（本仓一贯做法：把"看起来像坑"的地方写成可核对的事实）：
    #    "自证失败(a)" 会不会被 "自证失败(a0)"/"自证失败(a1)" 误命中？
    #    —— **会**！`"V18 自证失败(a0)"` 里 find("自证失败(a)") 返回 >= 0。
    #    ⇒ 故 label_of 必须取 find() 位置最小的那个（首次出现的位置），
    #      而"A 是 B 的前缀"会让两者位置相同 ⇒ 需要按【最长匹配优先】打破平局。
    #    实测：'x 自证失败(a0): y' 中 find('自证失败(a)') = 2 与 find('自证失败(a0)') = 2 相同。
    #    故 label_of 在位置相同时取【更长的标签】—— 见下面的实现。
    ALL_LABELS = ["自证失败(a0)", "自证失败(a1)", "自证失败(a)",
                  "自证失败(b1)", "自证失败(b2)", "自证失败(b3)", "自证失败(b4)",
                  "自证失败(b5)", "自证失败(b6)", "自证失败(b7)", "自证失败(b8)",
                  "自证失败(b9)",
                  "自证失败(c1)", "自证失败(c2)", "自证失败(c3)", "自证失败(c4)",
                  "自证失败(c5)",
                  "自证失败(d)", "自证失败(d2)", "自证失败(d3)",
                  "自证失败(e1)", "自证失败(e2)", "自证失败(e3)", "自证失败(e4)",
                  "自证失败(e5)", "自证失败(e6)", "自证失败(e7)", "自证失败(e8)",
                  "自证失败(e9)", "自证失败(f2)"]

    def label_of_exact(out: str, labels) -> str:
        """取首个出现的标签；位置相同时取【最长】的那个（前缀打破平局）。"""
        hits = [(out.find(lab), -len(lab), lab) for lab in labels if lab in out]
        hits = [h for h in hits if h[0] >= 0]
        return min(hits)[2] if hits else "(无标签)"

    # ------------------------------------------------------------------
    # C1 原样重跑必须全绿
    # ------------------------------------------------------------------
    ok, out = run_sql(mig)
    check("C1 原样重跑 V18 必须全绿（含回补的 (a0)/(a1)）",
          ok and "自证通过" in out, out)

    # ------------------------------------------------------------------
    # I(a0) 🆕 换成 BYPASSRLS 角色 ⇒ 自证 (a0) 必须抓住
    #   🛑 这是本轮修复的**靶心用例**：修复之前它会给出一条 (e5) 的
    #      【归因错误】的红（消息指向 register_device 坏了）。
    # ------------------------------------------------------------------
    ok, out = run_sql(mig, as_super=True)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(a0) 🆕 用 BYPASSRLS 角色跑 ⇒ 自证 (a0) 必须抓住（修复前这里报的是 (e5) 的归因错误）",
          (not ok) and lab == "自证失败(a0)",
          f"首报错标签={lab}（期望 自证失败(a0)）\n{out}")

    # ------------------------------------------------------------------
    # I(a1) 🆕 把 device 的 RLS 策略改成 USING(true) ⇒ 自证 (a1) 必须抓住
    #   🛑 这条防的是"策略被改动/缺失"⇒ 依赖 RLS 的判据会【静默假绿】。
    #      注入经 psql 的 APP_USER 通道（策略 owner = 表 owner = diaoyuanyun，已实测可 ALTER）。
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
-- 注入：把 device 的租户隔离策略改成"人人可见"（角色属性完全正常）
ALTER POLICY tenant_isolation ON device USING (true) WITH CHECK (true);
""")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(a1) 🆕 把 device 的 RLS 策略改成 USING(true) ⇒ 自证 (a1) 必须抓住",
          (not ok) and lab == "自证失败(a1)",
          f"首报错标签={lab}（期望 自证失败(a1)）\n{out}")

    # ------------------------------------------------------------------
    # I(a) 删掉 register_device → (a) 抓
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
DROP FUNCTION register_device(uuid, uuid, uuid, text, text);
""")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(a) 删掉 register_device → 自证 (a) 必须抓住",
          (not ok) and lab == "自证失败(a)",
          f"首报错标签={lab}\n{out}")

    # ------------------------------------------------------------------
    # I(b4) 把 `INSERT INTO device` 写成 `INSERT INTO "device"` → (b4) 抓
    #   🛑 这是"判据忠实性"的标准测法：注入形态**语义完全等价**（引号是标识符引用），
    #      只有"判据是不是真的在某处匹配"被改变。若判据写成了裸子串
    #      （例如 `contains("device")`），这条注入不会被抓住。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, "register", "INSERT INTO device (device_id", 'INSERT INTO "device" (device_id',
        expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check('I(b4) 把 `INSERT INTO device` 写成 `INSERT INTO "device"`（语义等价）→ 自证 (b4) 必须抓住',
          (not ok) and lab == "自证失败(b4)",
          f"首报错标签={lab}（期望 自证失败(b4)）\n{out}")

    # ------------------------------------------------------------------
    # I(b7) 🆕 把 RAISE 里的"另一租户"改成"其他租户"（注释里仍有"另一租户"）
    #   🛑🛑 这条是本脚本的核心用例之一：它把"判据必须带锚点"钉成机械事实。
    #      函数体的**注释**里逐字含"另一租户"（prosrc 会保留注释）⇒
    #      若 (b7) 写成裸子串 `posstr(prosrc,'另一租户')`，它会被**注释**满足 ⇒ 假绿。
    #      收紧后的判据要求 `RAISE EXCEPTION` 与"另一租户"同处一条语句片段
    #      （`[^;]*` 不跨分号）⇒ 本注入（改掉 RAISE 里的那四个字）必然被抓住。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, "register", "已被【另一租户】占用", "已被【其他租户】占用", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b7) 🆕 把 RAISE 里的『另一租户』改成『其他租户』（注释里仍有该词）→ 自证 (b7) 必须抓住",
          (not ok) and lab == "自证失败(b7)",
          f"首报错标签={lab}（期望 自证失败(b7)）\n{out}")

    # ------------------------------------------------------------------
    # I(b9) 让 INSERT 不含 tenant_id（列与值同步删，语法仍合法）→ (b9) 抓
    # ------------------------------------------------------------------
    old_insert = ("    INSERT INTO device (device_id, tenant_id, store_id, model, param_template_id,\n"
                  "                        status, created_by)\n"
                  "    VALUES (p_device_id, p_tenant_id, p_store_id, p_model, p_param_template_id,\n"
                  "            'active', 'device-registration')")
    new_insert = ("    INSERT INTO device (device_id, store_id, model, param_template_id,\n"
                  "                        status, created_by)\n"
                  "    VALUES (p_device_id, p_store_id, p_model, p_param_template_id,\n"
                  "            'active', 'device-registration')")
    inj_body = replace_func_body(mig, "register", old_insert, new_insert, expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(b9) 从 INSERT 列清单里删掉 tenant_id（列与值同步删，语法仍合法）→ 自证 (b9) 必须抓住",
          (not ok) and lab == "自证失败(b9)",
          f"首报错标签={lab}（期望 自证失败(b9)）\n{out}")

    # ------------------------------------------------------------------
    # I(b5) 🆕 从 INSERT 语句里摘掉 ON CONFLICT（语法仍合法）→ (b5) 抓
    #   🛑 修复前该判据写成裸 `ON\s+CONFLICT`，而函数体里有 2 处代码 + 1 处注释命中
    #      ⇒ 摘掉 INSERT 上的 ON CONFLICT 之后，判据仍被**别处/注释**满足。
    #      🛑 摘掉之后函数仍能【创建成功】（PL/pgSQL 不校验这个），
    #         只有第一次调用才报 23505 —— 故这是"把运行期错误前移到迁移期"的用例。
    # ------------------------------------------------------------------
    old_b5 = ("    ON CONFLICT (device_id) DO NOTHING;\n"
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
    # I(c1) 把 `UPDATE device` 写成 `UPDATE "device"` → (c1) 抓
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, "retire", "    UPDATE device\n", '    UPDATE "device"\n', expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check('I(c1) 把 `UPDATE device` 写成 `UPDATE "device"`（语义等价）→ 自证 (c1) 必须抓住',
          (not ok) and lab == "自证失败(c1)",
          f"首报错标签={lab}（期望 自证失败(c1)）\n{out}")

    # ------------------------------------------------------------------
    # I(c4) 从状态迁移 UPDATE 里删掉 `status <> 'retired'` → (c4) 抓
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, "retire",
        "       AND tenant_id  = p_tenant_id\n       AND status    <> 'retired';",
        "       AND tenant_id  = p_tenant_id;", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(c4) 从状态迁移 UPDATE 里删掉 `status <> 'retired'` → 自证 (c4) 必须抓住",
          (not ok) and lab == "自证失败(c4)",
          f"首报错标签={lab}（期望 自证失败(c4)）\n{out}")

    # ------------------------------------------------------------------
    # I(c5) 🆕 只删【状态迁移 UPDATE】的租户维度（步骤 (4) 的 SELECT 不动）
    #   🛑 与 118 的 I(b7)/I(c7) 同款：这对【不带锚点的判据】必然假绿 ——
    #      retire_device 的步骤 (4) 有一条
    #          SELECT status ... WHERE device_id = p_device_id AND tenant_id = p_tenant_id;
    #      里面逐字含 `AND tenant_id = p_tenant_id` ⇒ 裸子串判据会被它满足。
    #      🛑 注意 V18 里 UPDATE 用的是【双空格】对齐、SELECT 用的是【单空格】——
    #         本注入只改双空格那一处（expect=1 已钉住），故 SELECT 不受影响。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, "retire",
        "       AND tenant_id  = p_tenant_id\n       AND status    <> 'retired';",
        "       AND status    <> 'retired';", expect=1)
    # 自证那条"裸子串判据会假绿"的机械事实：注入后函数体里仍含单空格版本
    still_has_loose = "AND tenant_id = p_tenant_id" in func_body(inj_body, "retire")
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(c5) 🆕 只删状态迁移 UPDATE 的租户维度（步骤(4)的 SELECT 不动）→ 自证 (c5) 必须抓住",
          (not ok) and lab == "自证失败(c5)",
          f"首报错标签={lab}（期望 自证失败(c5)）\n{out}")
    check("I(c5-note) 锚点必要性自证：注入后函数体里【仍含】不带锚点就会命中的 `AND tenant_id = p_tenant_id`（来自步骤(4)的 SELECT）",
          still_has_loose,
          "🛑 若这一条为 False，说明 V18 的 retire_device 里已经没有那条「同款子句」了，"
          "那么 I(c5) 就证明不了「不带锚点的判据会假绿」—— 本用例的论证力会失效。")

    # ------------------------------------------------------------------
    # I(d) 抹掉登记行 → (d) 抓
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
DELETE FROM schema_migration WHERE version = 'V18';
""")
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(d) 抹掉迁移登记行 → 自证 (d) 必须抓住",
          (not ok) and lab == "自证失败(d)",
          f"首报错标签={lab}\n{out}")

    # ------------------------------------------------------------------
    # I(d3) 摘掉授权段且函数为新建（proacl IS NULL）→ (d3) 必须抓住
    #   🛑 (d2) 在【按本仓脚本建的库】上恒真、判别力为零（118 的 I(d2) 已实测：
    #      REVOKE ... FROM current_user 之后自证仍打印"自证通过"）。
    #      成因：迁移由应用角色自己执行 ⇒ 函数 owner = current_user ⇒
    #      owner 对自有函数的 EXECUTE 是隐含的 ⇒ has_function_privilege 永远为 t。
    #   ⇒ 必须先把函数 DROP 掉再让本文件新建，proacl 初始为 NULL 才是
    #      "授权段根本没执行"的**真实形态**。
    # ------------------------------------------------------------------
    grant_re = re.compile(r"DO\s*\n\$v18_grant\$.*?\$v18_grant\$;", re.S)
    stripped, n_sub = grant_re.subn("-- 注入：整个第 2 节授权段被摘掉\n", mig, 1)
    if n_sub != 1:
        raise SystemExit(f"❌ 未能定位第 2 节 $v18_grant$ 段（匹配 {n_sub} 次）—— 迁移结构已变。")
    inj = stripped.replace(
        "CREATE OR REPLACE FUNCTION register_device(",
        "DROP FUNCTION IF EXISTS register_device(uuid,uuid,uuid,text,text);\n"
        "CREATE OR REPLACE FUNCTION register_device(", 1)
    inj = inj.replace(
        "CREATE OR REPLACE FUNCTION retire_device(",
        "DROP FUNCTION IF EXISTS retire_device(uuid,uuid);\n"
        "CREATE OR REPLACE FUNCTION retire_device(", 1)
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(d3) 摘掉授权段且函数为新建（proacl IS NULL）→ 自证 (d3) 必须抓住",
          (not ok) and lab == "自证失败(d3)",
          f"首报错标签={lab}（期望 自证失败(d3)）\n{out}")

    # ------------------------------------------------------------------
    # I(e1) 让 e1 建档的 model 与断言值不符 → (e1) 抓
    #   🛑 这是"函数体看着对、跑起来不对"的形态：定义段全过（函数体正则全中），
    #      只有行为段能抓住 —— 也是自证 (e) 存在理由的证明。
    #   🛑 锚必须带上下文：`v_mode := register_device(v_ta, v_dev_a1, v_store_a1, '杠2', 'TPL-G2-V3');`
    #      这一行在 e1 与 e2 各出现一次，裸锚会命中两次。
    # ------------------------------------------------------------------
    old_e1 = ("        -- e1 建档 → CREATED，且行确实落库\n"
              "        -- ---------------------------------------------------------------\n"
              "        v_mode := register_device(v_ta, v_dev_a1, v_store_a1, '杠2', 'TPL-G2-V3');")
    new_e1 = ("        -- e1 建档 → CREATED，且行确实落库\n"
              "        -- ---------------------------------------------------------------\n"
              "        v_mode := register_device(v_ta, v_dev_a1, v_store_a1, '现有', 'TPL-G2-V3');")
    n = mig.count(old_e1)
    if n != 1:
        raise SystemExit(f"❌ I(e1) 的锚在迁移里出现 {n} 次（期望 1）—— 锚点失效，须修脚本。")
    inj = mig.replace(old_e1, new_e1, 1)
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(e1) 让 e1 建档的 model 与断言值不符（定义段全过）→ 自证 (e1) 必须抓住",
          (not ok) and lab == "自证失败(e1)",
          f"首报错标签={lab}（期望 自证失败(e1)）\n{out}")

    # ------------------------------------------------------------------
    # I(e6) 让 e6 的裸 INSERT 违反 NOT NULL → (e6) 抓
    #   🛑 注入只改 e6 那一行的 store_id（改成 NULL），函数路径完全不受影响。
    # ------------------------------------------------------------------
    old_e6 = "            VALUES (v_dev_a2, v_ta, v_store_a1, '现有', 'active');"
    new_e6 = "            VALUES (v_dev_a2, v_ta, NULL, '现有', 'active');"
    n = mig.count(old_e6)
    if n != 1:
        raise SystemExit(f"❌ I(e6) 的锚在迁移里出现 {n} 次（期望 1）—— 锚点失效，须修脚本。")
    inj = mig.replace(old_e6, new_e6, 1)
    ok, out = run_sql(inj)
    lab = label_of_exact(out, ALL_LABELS)
    check("I(e6) 让 e6 的裸 INSERT 违反 NOT NULL（store_id 置空）→ 自证 (e6) 必须抓住",
          (not ok) and lab == "自证失败(e6)",
          f"首报错标签={lab}（期望 自证失败(e6)）\n{out}")

    # ------------------------------------------------------------------
    # C2 等价形态：给两个函数体首尾加空行 → 必须仍然全绿
    #      🛑 防"判据收紧后误伤空白形态"。PG 的 prosrc 会保留首尾空白，
    #         而所有断言都是"子串/正则存在"，不该被空行影响。
    # ------------------------------------------------------------------
    eq = mig.replace(func_body(mig, "register"), "\n" + func_body(mig, "register") + "\n\n", 1)
    eq = eq.replace(func_body(eq, "retire"), "\n" + func_body(eq, "retire") + "\n\n", 1)
    ok, out = run_sql(eq)
    check("C2 函数体首尾加空行（等价形态）→ 必须仍然全绿",
          ok and "自证通过" in out, out)

    # ------------------------------------------------------------------
    # C3 等价形态：在 DECLARE 段加一个未使用的变量 → 必须仍然全绿
    # ------------------------------------------------------------------
    eq = replace_func_body(mig, "register", "    v_store      int := 0;",
                           "    v_store      int := 0;\n    v_noise      int := 0;", expect=1)
    ok, out = run_sql(eq)
    check("C3 在 DECLARE 段加一个未使用变量（等价形态）→ 必须仍然全绿",
          ok and "自证通过" in out, out)

    # ------------------------------------------------------------------
    # C4 元门禁：各注入组的首报错标签必须互不相同
    #      🛑 若整块自证其实只会在第一行报错（例如 (a0) 排在最前而注入的是 (a0) 之外的
    #         东西却仍报 (a0)），那么"标签对上"这件事就失去意义。
    # ------------------------------------------------------------------
    seen = {}
    cases = {
        "a0": (mig, True),
        "a1": (inject_before_guard(mig, "\nALTER POLICY tenant_isolation ON device USING (true) WITH CHECK (true);\n"), False),
        "a": (inject_before_guard(mig, "\nDROP FUNCTION register_device(uuid, uuid, uuid, text, text);\n"), False),
        "b4": (inject_before_guard(replace_func_body(
            mig, "register", "INSERT INTO device (device_id", 'INSERT INTO "device" (device_id',
            expect=1), "\n"), False),
        "c1": (inject_before_guard(replace_func_body(
            mig, "retire", "    UPDATE device\n", '    UPDATE "device"\n', expect=1), "\n"), False),
        "c5": (inject_before_guard(replace_func_body(
            mig, "retire",
            "       AND tenant_id  = p_tenant_id\n       AND status    <> 'retired';",
            "       AND status    <> 'retired';", expect=1), "\n"), False),
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
            "自证失败(b9)",
            "自证失败(c1)", "自证失败(c2)", "自证失败(c3)", "自证失败(c4)",
            "自证失败(c5)",
            "自证失败(d)", "自证失败(d2)", "自证失败(d3)",
            "自证失败(e1)", "自证失败(e2)", "自证失败(e3)", "自证失败(e4)",
            "自证失败(e5)", "自证失败(e6)", "自证失败(e7)", "自证失败(e8)",
            "自证失败(e9)", "自证失败(f2)"]
    missing = [s for s in need if s not in mig]
    check("C5 全部自证失败分支字面量必须都在迁移文本里",
          not missing, f"缺失: {missing}")

    # ------------------------------------------------------------------
    # C6 🆕 DDL 净效果等价性（本轮改 V18 文本的处置依据）
    #
    #    本仓处置纪律（117 的 ⑤ / 文件头）：**迁移文本一改，已应用的库就必须
    #    做一次显式处置** —— 要么证明 DDL 净效果相同（⇒ 对齐 checksum），
    #    要么承认变了（⇒ 重建库 / 补新迁移）。
    #    本次处置是前者：本轮为回补 (a0)/(a1) 改了 V18 文本（**只落在第 4 节自证
    #    与其说明注释**，不含任何 DDL）⇒ 必须给出"净效果相同"的机械证据。
    #
    #    before = 当前文本把 (a0)/(a1) 两段可执行语句拿掉（即改前形态）。
    #    🛑 这是【保守】取法：只拿掉语句、保留注释；若净效果仍相同，
    #       那对"连注释都没有"的完整改前文本也必然相同。
    # ------------------------------------------------------------------
    print()
    print("   [C6] 构造改前/改后两份文本并分别测其 DDL 净效果 …")
    after = mig
    before = strip_capability_guards(after)
    before_ok = before != after
    print(f"        before/after 文本长度差 = {len(after) - len(before)} 字符"
          f"（(a0)+(a1) 两段可执行语句；{'' if before_ok else '🛑 还原未生效'}）")

    try:
        rebuild_db(REV_DB)
        # after 的净效果（V18 用磁盘原文，其余用链条原文）
        apply_chain_to_template(REV_DB, after)
        eff_after = ddl_effect(REV_DB)
        # before 的净效果：另建一次（同一库就地重跑会被 CREATE OR REPLACE 掩盖差异）
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
        check("C6a 改前/改后文本的 V18 DDL 净效果完全相同（签名/函数体/权限/登记）",
              before_ok and same, det or f"before_ok={before_ok}")

        # ---- C6b 判别力自证：净效果比对必须【对真实改动有反应】----
        #      注入一句真实出现在函数体里的改动。
        #      🛑 必须选"函数体内"的改动而不是"文件别处"的改动 —— 本比对只看
        #         prosrc + 签名 + 权限 + 登记。若注入落在注释里，本比对【本来就不该】
        #         有反应（那正是"净效果"的定义），那样的自证是假的。
        tampered = replace_func_body(
            after, "retire", "     WHERE device_id  = p_device_id",
            "     WHERE device_id  = p_device_id AND true", expect=1)
        rebuild_db(REV_DB)
        apply_chain_to_template(REV_DB, tampered)
        eff_tampered = ddl_effect(REV_DB)
        reacted = eff_tampered != eff_after
        # 撤销：重建并应用原文本
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
    print(f"===== 119 反向验证结果: {passed}/{len(results)} 通过 =====")
    print()
    if passed == len(results):
        print("⇒ 结论：V18 的每一类自证都【有牙齿】：")
        print("   · 注入的每一类错误都被【对应标签】的自证抓住（首报错标签精确对上）；")
        print("   · 语义等价的正确形态仍然全绿（判据没有变成过度收紧的误伤）；")
        print("   · 各注入组的标签互不相同 ⇒ '标签对上'这件事有信息来源，不是伪证；")
        print("   · DDL 净效果对真实改动有反应，而改前/改后净效果相同。")
        print()
        print("  🛑 本轮由此修掉了 V18 的真实缺陷：**缺 (a0)/(a1) 能力守卫** ——")
        print("     修复前，用 BYPASSRLS 角色跑 V18 会报 (e5) 的【归因错误】的红")
        print("     （消息指向 register_device 坏了，而函数完全正确）。")
        print("     现已从 V19 逐字回补，I(a0) / I(a1) 两条用例钉住它。")
    else:
        print("⇒ 🛑 结论：存在未通过项，V18 的自证尚不可信 —— 不得据此宣称缺口已收口。")
    if keep:
        print(f"\n（--keep：临时库 {REV_DB} 已保留）")
    else:
        print(f"\n（临时库 {REV_DB} 已清理）")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())