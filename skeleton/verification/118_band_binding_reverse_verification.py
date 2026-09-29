#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V17「手环绑定通路」的**反向验证**（反证法 / 有牙齿的门禁）。

【这个脚本为什么必须存在】

  V17 是本仓第一条「不建表、只建两个 PL/pgSQL 原语」的迁移，而它的自证 (b)(c)
  有 14 条断言全部是**读 prosrc 做正则匹配**。这类断言有一个共同的失效形态：

      函数体看着对、跑起来不对      → 由自证 (e) 的行为段兜住
      但更隐蔽的是：**断言看着在测某件事，实际测的是另一件事**
                                    → 只有"受控注入 + 断言必须报错"能证明它测对了

  本仓已有前例（116 的教训清单）：判据的适用范围 = 扫描范围；判据的锚点 = 它的适用语句。
  V17 的初版就有两处踩在这个形态上，由本脚本的**预探针**抓出（见下）。

【🛑 本脚本抓出的真实缺陷（不是"为了写脚本而写脚本"）】

  (b7) 与 (c7) 的初版正则没有锚点：

      v_bind_body   !~ 'AND\\s+tenant_id\\s*=\\s*p_tenant_id'      -- 换机路径
      v_unbind_body !~ 'AND\\s+tenant_id\\s*=\\s*p_tenant_id'      -- 解绑路径

  而两个函数的**步骤 (4) 各有一条 SELECT**，里面逐字含同样的子句：

      bind  :  WHERE id = p_customer_id AND tenant_id = p_tenant_id;
      unbind:  WHERE band_id = p_band_id AND tenant_id = p_tenant_id;

  ⇒ 实测（_work/v17_b7c7_specificity_probe.sql，已落盘）：

      单独删掉【换机 UPDATE】的租户维度 → 旧判据返回 t（= 不报错 = 【没抓住】）
      单独删掉【解绑 UPDATE】的租户维度 → 旧判据返回 t（= 【没抓住】）

  即：这两条断言对"它们本要保护的那条语句"**完全没有反应** —— 假绿。
  处置：把判据收紧为从 `UPDATE band` 起锚、以 `;` 为界（`[^;]*` 不跨语句）。
  本脚本的 I(b7) / I(c7) 两个用例就是把这次处置钉住（它们对旧判据会 FAIL）。

【🛑 本脚本另外抓出的两处缺陷（同一次运行）】

  ① 自证 (a) 在"只缺一个函数"时炸成一条**与 (a) 无关**的错误：
        错误: 有缺陷的数组常量:"bind_band"
        DETAIL: 数组值必须以 "{" 或者维度信息开始。
     成因：`v_missing` 声明为 `text[]`（且带 `:= ARRAY[]::text[]` 默认值），
     而 `string_agg()` 返回 text ⇒ 赋值时隐式 cast ⇒ 单元素串被当成数组字面量解析。
     ⇒ "判据本身也有缺陷"的形态：它想报 (a)，却报了一条没人能读懂的错误。
     处置：v_missing 改 text，判空改 `IS NOT NULL`。用例 I(a) 钉住。

  ② 自证 (d2) 在【按本仓脚本建的库】上**恒真、判别力为零**：
     实测注入 `REVOKE ... FROM current_user` 之后，自证仍然打印"自证通过"。
     成因：迁移由应用角色自己执行 ⇒ 函数 owner = current_user ⇒
     owner 对自有函数的 EXECUTE 是**隐含**的，has_function_privilege 永远为 t。
     实测 proacl = `=X/diaoyuanyun | diaoyuanyun=X/diaoyuanyun`。
     处置：新增 (d3) —— 断言 proacl 里存在【显式 ACL 项】（proacl IS NOT NULL），
     即"第 2 节授权段真的执行过"。用例 I(d3) 钉住，I(d2/d3) 钉住更宽的形态。

【用例矩阵】

  注入组（必须报错，且【首报错的标签】必须对上）
    I(a)  删掉 bind_band                        → 自证 (a)
    I(b)  把 bind_band 换成空壳函数               → 自证 (b1)  （函数存在但没建上下文）
    I(b6) 摘掉 ON CONFLICT 的 WHERE status 谓词   → 自证 (b6)  （运行期错误前移到迁移期）
    I(b7) 🆕 只删【换机 UPDATE】的租户维度         → 自证 (b7)  （旧判据此处假绿）
    I(c)  把 unbind_band 换成删除实现             → 自证 (c2)  （解绑必须是状态迁移）
    I(c7) 🆕 只删【状态迁移 UPDATE】的租户维度     → 自证 (c7)  （旧判据此处假绿）
    I(d)  抹掉迁移登记行                          → 自证 (d)
    I(d2/d3) 摘掉授权段效果                        → 自证 (d2) 或 (d3)
    I(d3) 🆕 删掉授权段 + 函数新建（proacl=NULL）    → 自证 (d3)  （(d2) 此处恒真，故必须是 (d3)）
    I(e3) 把"客户已有有效带子"那一支改成 CREATED   → 自证 (e3)  （看真行为，不看定义）
  对照组（必须仍然全绿 —— 防"判据收紧后误伤等价形态"）
    C1 原样重跑 V17                              → 必须全绿
    C2 给两个函数体首尾各加空行（纯空白形态差异）  → 必须全绿
    C3 在 DECLARE 段加一个未使用的变量            → 必须全绿
  元门禁
    C4 五个注入组的【首报错标签必须互不相同】      → 防"整块自证只会在第一行报错"
    C5 全部失败分支字面量必须都在迁移文本里        → 防"删掉自证也算通过"
    C6 🆕 DDL 净效果等价性（本仓"改迁移文本"的处置依据）
       · 取改前/改后两份文本分别重建临时库；
       · 断言：两者的**模式指纹完全相同**，且**函数体原文只差在判据那一处**；
       · 判别力自证：往改后文本的函数体里插一句 NO-OP 之外的**真实改动** ⇒ 必须报"有差异"。

【与 116 / 117 的分工】
  116 = V16 迁移【逻辑】是否正确（跨租户引用完整性）。
  117 = 迁移【文本】与已应用【库】是否一致（防"改了文本没处置库"）。
  118 = V17 迁移【自证】是否真的有牙齿（本文件），并给出"改判据后 DDL 净效果不变"的证据。

用法:
  python verification/118_band_binding_reverse_verification.py
  python verification/118_band_binding_reverse_verification.py --keep   # 保留临时库排查
环境变量: DY_PSQL_EXE / DY_PG_HOST / DY_PG_PORT / DY_PG_USER / DY_PG_PASSWORD
          DY_PG_SUPER_USER / DY_PG_SUPER_PASSWORD / DY_PG_DB / DY_BAND_REV_DB
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
REV_DB = os.environ.get("DY_BAND_REV_DB", "diaoyuanyun_band118")

HERE = Path(__file__).resolve().parent                     # verification
SKELETON = HERE.parent                                     # skeleton
MIGRATION_DIR = SKELETON / "dy-app" / "src" / "main" / "resources" / "db" / "migration"
MIGRATION = MIGRATION_DIR / "V17__band_binding_primitive.sql"


def env_with(pwd: str) -> dict:
    return dict(os.environ, PGPASSWORD=pwd)


def psql(user: str, pwd: str, db: str, args, timeout: int = 900):
    cmd = [PSQL, "-h", HOST, "-p", PORT, "-U", user, "-d", db, "-X"] + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env=env_with(pwd), timeout=timeout)


def migration_scripts():
    """按【数值】版本号排序 —— 字符串排序会把 V10 排在 V2 前面（与 117 同款）。"""
    files = sorted(MIGRATION_DIR.glob("V*__*.sql"),
                   key=lambda p: int(re.match(r"V(\d+)__", p.name).group(1)))
    if not files:
        raise SystemExit(f"❌ 未在 {MIGRATION_DIR} 找到任何 V*__*.sql")
    return files


def flyway_checksum(path: Path) -> int:
    """复算 Flyway 的 checksum（与 117 同口径，CRC32 逐行 UTF-8 有符号）。"""
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
GUARD_RE = re.compile(r"DO\s*\n\$v17_guard\$")


def func_body(mig: str, tag: str) -> str:
    """从迁移文本里切出 `$v17_<tag>$ ... $v17_<tag>$;` 之间的函数体原文。

    🛑 为什么能从【文件文本】切，而不是从库里读：
       本脚本已用探针证明 —— 库中 `pg_proc.prosrc` 与这里的切片【逐字相同】
       （bind 5633 字符 / unbind 2276 字符，strip 后完全相等）。
       这是因为 PG 把 `AS $tag$ ... $tag$` 之间的内容原样存进 prosrc。
       ⇒ 于是"按文件文本构造注入"与"库里实际存的函数体"是同一个对象，
         不需要先 DROP 再 CREATE 就能预知注入结果。
    """
    open_tag = f"$v17_{tag}$"
    a = mig.index(open_tag) + len(open_tag)
    b = mig.index(f"$v17_{tag}$;")
    return mig[a:b]


def replace_func_body(mig: str, tag: str, old: str, new: str, expect: int = 1) -> str:
    """替换函数体里的一段（精确匹配）。expect 用于钉住"注入真的生效了"。"""
    body = func_body(mig, tag)
    n = body.count(old)
    if n != expect:
        raise SystemExit(
            f"❌ 注入锚点在 {tag} 函数体里出现 {n} 次（期望 {expect}）：{old[:80]!r}\n"
            f"   ⇒ 迁移文本的结构已变，本脚本的锚点失效，必须先修脚本。")
    new_body = body.replace(old, new)
    return mig.replace(body, new_body, 1)


def inject_before_guard(mig: str, injection: str) -> str:
    """🛑 把注入插到 $v17_guard$ 自证块【之前】。

    这一步与本仓 116/v16_reverse 同款纪律：注入必须【插在自证块之前】。
    追加在文件末尾等于"考完试再改答案" —— 那时自证已经跑完并成功，
    注入的错误没有任何自证去抓，于是整组用例假失败（116 第一版踩过）。
    """
    m = GUARD_RE.search(mig)
    if not m:
        raise SystemExit("❌ 未在迁移中找到 $v17_guard$ 自证块 —— 迁移结构已变，"
                         "本脚本的注入锚点失效，必须先修脚本。")
    head, guard_and_rest = mig[:m.start()], mig[m.start():]
    return (head
            + "\n-- ===== 反向验证注入（位于自证块之前，故自证【有机会】抓住它）=====\n"
            + injection
            + "\n-- ===== 注入结束 =====\n\n"
            + guard_and_rest)


def run_sql(sql: str, timeout: int = 300):
    """在一个事务里跑一段 SQL，返回 (是否成功, 合并输出)。

    🛑 整个文件包在 BEGIN … ROLLBACK 里：注入的 DDL 绝不落地到已应用库。
       这也是本脚本可以拿 diaoyuanyun_dev 做行为验证（I(e3)/C1）的前提。
    """
    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False,
                                     encoding="utf-8", newline="") as f:
        f.write("\\set ON_ERROR_STOP on\nBEGIN;\n")
        f.write(sql)
        f.write("\nROLLBACK;\n")
        path = f.name
    try:
        p = subprocess.run([PSQL, "-h", HOST, "-p", PORT, "-U", APP_USER, "-d", DEV_DB,
                            "-X", "-f", path, "-v", "ON_ERROR_STOP=on"],
                           capture_output=True, text=True, encoding="utf-8",
                           errors="replace", env=env_with(APP_PWD), timeout=timeout)
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


def apply_chain_to_template(db: str, v17_text: str):
    """把【整条迁移链】应用到临时库，其中 V17 用传入的文本（其余用磁盘原文）。

    🛑 为什么 C6 必须用【整条链】而不是只应用 V17：
       V17 的入参守卫依赖 V2 建的 `band` 表、V7 的 `customer`、V3 的 `band_telemetry`～
       —— 只应用一条会在自证前置就报「表 band 不存在」（本脚本第一版即如此，已实测）。
       这与 117 的做法一致：临时库必须从零按链条建。
    """
    scripts = migration_scripts()
    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False,
                                     encoding="utf-8", newline="") as f:
        f.write("\\set ON_ERROR_STOP on\n")
        for p in scripts:
            text = v17_text if p.name == MIGRATION.name else p.read_text(encoding="utf-8")
            # 逐条写入（不用 \i），这样 V17 用改前/改后文本、其余用磁盘原文
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
    """V17 的「DDL 净效果」指纹。

    🛑 为什么本脚本的"净效果"不是 pg_dump 全库对账（117 做的那件事）：
       117 对账的是"已应用库 vs 重建库"，两侧都含 V8 迁移以【数据】形式插入的
       schema_migration 登记行等差异源，它的噪声模型是"模式 vs 模式"。
       而本脚本要回答的问题窄得多：**V17 这一条文本的两份版本，客观效果是否相同**。
       ⇒ 于是把指纹定义为 V17 实际产生/改变的四类东西：
           ① 函数签名（proname + 参数 + 返回类型）
           ② 函数体原文（prosrc）—— V17 唯一有内容的产出
           ③ 函数 EXECUTE 权限（第 2 节 GRANT 段的效果）
           ④ schema_migration 中 V17 一行的 description（第 3 节登记段的效果）
       这四项覆盖 V17 第 1/2/3 节；第 4 节是自证（无产出）、第 5 节是注释（无产出）。
    """
    sql = """
    SELECT 'fn|' || p.proname || '|' || pg_get_function_arguments(p.oid) || '|' ||
           pg_get_function_result(p.oid) || '|' || md5(p.prosrc)
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname IN ('bind_band','unbind_band')
    UNION ALL
    SELECT 'prosrc|' || p.proname || '|' || p.prosrc
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname IN ('bind_band','unbind_band')
    UNION ALL
    SELECT 'priv|' || p.proname || '|' ||
           COALESCE(array_to_string(p.proacl,' | '),'(NULL)') || '|' ||
           has_function_privilege(current_user, p.oid, 'EXECUTE')::text
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'public' AND p.proname IN ('bind_band','unbind_band')
    UNION ALL
    SELECT 'reg|V17|' || COALESCE(description,'')
      FROM schema_migration WHERE version = 'V17'
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

    print("== 118 V17 手环绑定通路 · 反向验证 ==")
    print(f"   被测迁移 : {MIGRATION.name}")
    print(f"   行为验证 : {DEV_DB}（注入包在 BEGIN…ROLLBACK 里，不落地）")
    print(f"   等价验证 : {REV_DB}（临时库，仅用于 C6）")
    print(f"   文本校验 : checksum={flyway_checksum(MIGRATION)}  "
          f"len={len(mig)}  body_bind={len(func_body(mig, 'bind'))}  "
          f"body_unbind={len(func_body(mig, 'unbind'))}")
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

    ALL_LABELS = ["自证失败(a)", "自证失败(b1)", "自证失败(b2)", "自证失败(b3)",
                  "自证失败(b4)", "自证失败(b5)", "自证失败(b6)", "自证失败(b7)",
                  "自证失败(c1)", "自证失败(c2)", "自证失败(c3)", "自证失败(c4)",
                  "自证失败(c5)", "自证失败(c6)", "自证失败(c7)",
                  "自证失败(d3)", "自证失败(d2)", "自证失败(d)",
                  "自证失败(e1)", "自证失败(e2)", "自证失败(e3)", "自证失败(e4)",
                  "自证失败(e5)", "自证失败(e6)", "自证失败(e7)", "自证失败(e8)",
                  "自证失败(e9)", "自证失败(f2)"]

    # 🛑 前缀陷阱的核对（本仓一贯做法：把"看起来像坑"的地方写成可核对的事实）：
    #    "自证失败(d)" 是否会被 "自证失败(d2)" 误命中？—— **不会**。
    #    因为前者要求字面量 `(d)`（右括号紧跟 d），而 `(d2)` 里 d 之后是 '2'。
    #    已实测：'V17 自证失败(d2): x' 中 find('自证失败(d)') = -1。
    #    故 (d)/(d2)/(d3) 三者在 label_of 里互不混淆。

    # ------------------------------------------------------------------
    # C1 原样重跑必须全绿
    # ------------------------------------------------------------------
    ok, out = run_sql(mig)
    check("C1 原样重跑 V17 必须全绿（含修复后的 (b7)/(c7)）",
          ok and "自证通过" in out, out)

    # ------------------------------------------------------------------
    # I(a) 删掉 bind_band → (a) 抓
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
DROP FUNCTION bind_band(uuid, uuid, uuid, text, text, date, boolean, text);
""")
    ok, out = run_sql(inj)
    check("I(a) 删掉 bind_band → 自证 (a) 必须抓住",
          (not ok) and label_of(out, ALL_LABELS) == "自证失败(a)",
          f"首报错标签={label_of(out, ALL_LABELS)}\n{out}")

    # ------------------------------------------------------------------
    # I(b) 把 bind_band 换成"空壳"：签名相同、函数体没有建上下文
    #      🛑 这一条是整个 (b)(c) 段的存在理由 ——
    #         "函数在"与"函数做对了它在"是两件事，而 (a) 只测前者。
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
CREATE OR REPLACE FUNCTION bind_band(
    p_tenant_id uuid, p_band_id uuid, p_customer_id uuid, p_vendor text,
    p_model text DEFAULT NULL, p_bound_at date DEFAULT NULL,
    p_rebind boolean DEFAULT false, p_unbind_reason text DEFAULT NULL)
    RETURNS text LANGUAGE plpgsql AS $stub$
BEGIN
    RETURN 'CREATED';
END;
$stub$;
""")
    ok, out = run_sql(inj)
    check("I(b) 把 bind_band 换成空壳（签名对、没建上下文）→ 自证 (b1) 必须抓住",
          (not ok) and label_of(out, ALL_LABELS) == "自证失败(b1)",
          f"首报错标签={label_of(out, ALL_LABELS)}\n{out}")

    # ------------------------------------------------------------------
    # I(b6) 摘掉 ON CONFLICT 的 WHERE status 谓词
    #       🛑 这条断言的价值在于"把运行期错误前移到迁移期"：
    #          注掉之后函数【仍然能创建成功】，只有第一次调用才报
    #          「没有与 ON CONFLICT 说明匹配的唯一约束」。
    #          若 (b6) 不存在，本用例会走到 (e1) 才炸 —— 那是行为段，不是定义段。
    # ------------------------------------------------------------------
    inj = inject_before_guard(
        mig,
        "-- 注入：把 ON CONFLICT 的推断目标改成不带 WHERE 的形态\n"
        "CREATE OR REPLACE FUNCTION bind_band(\n"
        "    p_tenant_id uuid, p_band_id uuid, p_customer_id uuid, p_vendor text,\n"
        "    p_model text DEFAULT NULL, p_bound_at date DEFAULT NULL,\n"
        "    p_rebind boolean DEFAULT false, p_unbind_reason text DEFAULT NULL)\n"
        "    RETURNS text LANGUAGE plpgsql AS $b6$\n"
        "DECLARE v_affected int := 0; v_superseded int := 0; v_customer int := 0;\n"
        "BEGIN\n"
        "    PERFORM set_config('app.tenant_id', p_tenant_id::text, true);\n"
        "    PERFORM assert_tenant_context();\n"
        "    IF current_setting('app.tenant_id', true) IS NULL THEN RETURN NULL; END IF;\n"
        "    SELECT count(*) INTO v_customer FROM customer\n"
        "     WHERE id = p_customer_id AND tenant_id = p_tenant_id;\n"
        "    IF p_rebind THEN\n"
        "        UPDATE band SET status = 'unbound', unbound_at = CURRENT_DATE,\n"
        "                        unbind_reason = '换机', updated_at = now()\n"
        "         WHERE tenant_id = p_tenant_id AND customer_id = p_customer_id\n"
        "           AND status IN ('active','paused');\n"
        "        GET DIAGNOSTICS v_superseded = ROW_COUNT;\n"
        "    END IF;\n"
        "    INSERT INTO band (band_id, tenant_id, customer_id, vendor, model,\n"
        "                      bound_at, status, created_by)\n"
        "    VALUES (p_band_id, p_tenant_id, p_customer_id, p_vendor, p_model,\n"
        "            COALESCE(p_bound_at, CURRENT_DATE), 'active', 'band-binding')\n"
        "    ON CONFLICT (tenant_id, customer_id) DO NOTHING;\n"
        "    GET DIAGNOSTICS v_affected = ROW_COUNT;\n"
        "    IF v_affected = 1 THEN\n"
        "        RETURN CASE WHEN v_superseded > 0 THEN 'REPLACED' ELSE 'CREATED' END;\n"
        "    END IF;\n"
        "    IF EXISTS (SELECT 1 FROM band WHERE band_id = p_band_id AND status = 'active') THEN\n"
        "        RETURN 'ALREADY_BOUND';\n"
        "    END IF;\n"
        "    RETURN 'CUSTOMER_ALREADY_HAS_ACTIVE_BAND';\n"
        "END;\n"
        "$b6$;\n")
    ok, out = run_sql(inj)
    check("I(b6) 摘掉 ON CONFLICT 的 `WHERE status='active'` → 自证 (b6) 必须抓住",
          (not ok) and label_of(out, ALL_LABELS) == "自证失败(b6)",
          f"首报错标签={label_of(out, ALL_LABELS)}\n{out}")

    # ------------------------------------------------------------------
    # I(b7) 🆕 只删【换机 UPDATE】的租户维度
    #       🛑 这是本脚本的核心用例：它对【旧判据】必然 FAIL（旧判据假绿），
    #          对【收紧后】的判据必然 PASS。它把"判据的锚点 = 它的适用语句"
    #          这条教训钉成机械事实。
    #       注入方式：只改换机 UPDATE 那一处（expect=1），步骤 (4) 的 SELECT 不动。
    #          若有人把 (b7) 改回不带锚点的写法，本用例立刻 FAIL。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, "bind", "WHERE tenant_id   = p_tenant_id\n           AND customer_id = p_customer_id",
        "WHERE true\n           AND customer_id = p_customer_id", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    check("I(b7) 🆕 只删换机 UPDATE 的租户维度（步骤(4)的 SELECT 不动）→ 自证 (b7) 必须抓住",
          (not ok) and label_of(out, ALL_LABELS) == "自证失败(b7)",
          f"首报错标签={label_of(out, ALL_LABELS)}\n{out}")

    # ------------------------------------------------------------------
    # I(c) 把 unbind_band 换成删除实现 → (c2) 抓
    #      🛑 故意让函数体【含 UPDATE band】（满足 c1）→ 这样 c2 才是首报错，
    #         从而证明 c2 不只是 c1 的影子。
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
CREATE OR REPLACE FUNCTION unbind_band(
    p_tenant_id uuid, p_band_id uuid, p_reason text DEFAULT NULL,
    p_unbound_at date DEFAULT NULL)
    RETURNS text LANGUAGE plpgsql AS $c2$
BEGIN
    PERFORM set_config('app.tenant_id', p_tenant_id::text, true);
    PERFORM assert_tenant_context();
    IF current_setting('app.tenant_id', true) IS NULL THEN RETURN NULL; END IF;
    -- 故意保留一个 UPDATE band 以满足 (c1)，让 (c2) 成为首报错
    UPDATE band SET status = 'unbound', unbound_at = CURRENT_DATE,
                    unbind_reason = p_reason, updated_at = now()
     WHERE band_id = p_band_id AND tenant_id = p_tenant_id
       AND status <> 'unbound';
    DELETE FROM band WHERE band_id = p_band_id AND tenant_id = p_tenant_id;
    RETURN 'UNBOUND';
END;
$c2$;
""")
    ok, out = run_sql(inj)
    check("I(c) 把 unbind_band 换成删除实现（仍含 UPDATE band）→ 自证 (c2) 必须抓住",
          (not ok) and label_of(out, ALL_LABELS) == "自证失败(c2)",
          f"首报错标签={label_of(out, ALL_LABELS)}\n{out}")

    # ------------------------------------------------------------------
    # I(c7) 🆕 只删【状态迁移 UPDATE】的租户维度 —— 与 I(b7) 同型
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, "unbind", "       AND tenant_id = p_tenant_id\n       AND status <> 'unbound'",
        "       AND status <> 'unbound'", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    check("I(c7) 🆕 只删状态迁移 UPDATE 的租户维度（步骤(4)的 SELECT 不动）→ 自证 (c7) 必须抓住",
          (not ok) and label_of(out, ALL_LABELS) == "自证失败(c7)",
          f"首报错标签={label_of(out, ALL_LABELS)}\n{out}")

    # ------------------------------------------------------------------
    # I(d) 抹掉登记行 → (d) 抓
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
DELETE FROM schema_migration WHERE version = 'V17';
""")
    ok, out = run_sql(inj)
    check("I(d) 抹掉迁移登记行 → 自证 (d) 必须抓住",
          (not ok) and label_of(out, ALL_LABELS) == "自证失败(d)",
          f"首报错标签={label_of(out, ALL_LABELS)}\n{out}")

    # ------------------------------------------------------------------
    # I(d2) 把【授权段】摘掉 → (d2) 与 (d3) 必须至少报一个
    #      🛑 本用例第一版注入的是 `REVOKE ... FROM current_user`，
    #         结果【自证仍然打印"自证通过"】—— 实测证明：
    #           函数 owner 就是 current_user ⇒ owner 的 EXECUTE 是隐含的 ⇒
    #           既撤销不了，(d2) 的 has_function_privilege 也永远为真。
    #         即：**原来这一整条权限断言在按本仓脚本建的库上判别力为零。**
    #         处置有两条（本轮两条都做了）：
    #           ① 注入改为"授权段未生效/未执行"的等价形态；
    #           ② 新增 (d3)：断言 proacl 里存在【显式 ACL 项】（proacl IS NOT NULL）。
    #      ⇒ 本用例现在断言 (d2)/(d3) 至少报一个，且首报错必须落在这两者之一。
    #        只做 ② 而不改注入，就等于"用一条没被证明会变红的断言去修一条恒真断言"。
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
-- 注入：把第 2 节的 GRANT 段效果整体撤销（模拟"授权段没跑到 / 被加固覆盖"）
REVOKE ALL ON FUNCTION bind_band(uuid, uuid, uuid, text, text, date, boolean, text)
    FROM PUBLIC, current_user;
REVOKE ALL ON FUNCTION unbind_band(uuid, uuid, text, date)
    FROM PUBLIC, current_user;
""")
    ok, out = run_sql(inj)
    lab = label_of(out, ALL_LABELS)
    check("I(d2/d3) 摘掉授权段效果 → 自证必须报 (d2) 或 (d3)（权限断言得有牙齿）",
          (not ok) and lab in ("自证失败(d2)", "自证失败(d3)"),
          f"首报错标签={lab}（期望 (d2) 或 (d3)）\n{out}")

    # ------------------------------------------------------------------
    # I(d3) 🆕 只把授权段【删掉】（不撤销已有 ACL）→ (d3) 必须抓住
    #      🛑 这与 I(d2/d3) 不同：ACL 是"干净新建"的状态（proacl IS NULL），
    #         才是"授权段根本没执行"的**真实形态**。
    #         用于钉住 (d3) 的存在价值：若有人删掉 (d3)，本用例会报 (d2) 或全绿。
    # ------------------------------------------------------------------
    grant_re = re.compile(r"DO\s*\n\$v17_grant\$.*?\$v17_grant\$;", re.S)
    stripped, n_sub = grant_re.subn("-- 注入：整个第 2 节授权段被摘掉\n", mig, 1)
    if n_sub != 1:
        raise SystemExit(f"❌ 未能定位第 2 节 $v17_grant$ 段（匹配 {n_sub} 次）—— 迁移结构已变。")
    # 同时把函数 drop 掉，确保它们由本文件新建 ⇒ proacl 初始为 NULL（真实形态）
    inj = stripped.replace("CREATE OR REPLACE FUNCTION bind_band(",
                           "DROP FUNCTION IF EXISTS bind_band(uuid,uuid,uuid,text,text,date,boolean,text);\n"
                           "CREATE OR REPLACE FUNCTION bind_band(", 1)
    inj = inj.replace("CREATE OR REPLACE FUNCTION unbind_band(",
                      "DROP FUNCTION IF EXISTS unbind_band(uuid,uuid,text,date);\n"
                      "CREATE OR REPLACE FUNCTION unbind_band(", 1)
    ok, out = run_sql(inj)
    lab = label_of(out, ALL_LABELS)
    check("I(d3) 🆕 摘掉授权段且函数为新建（proacl IS NULL）→ 自证 (d3) 必须抓住",
          (not ok) and lab == "自证失败(d3)",
          f"首报错标签={lab}（期望 (d3)）\n{out}")

    # ------------------------------------------------------------------
    # I(e3) 把"客户已有有效带子"那一支改成 CREATED → (e3) 抓
    #       🛑 这是"函数体看着对、跑起来不对"的形态：定义段全过，
    #          只有行为段能抓住 —— 也是自证 (e) 存在理由的证明。
    # ------------------------------------------------------------------
    inj_body = replace_func_body(
        mig, "bind", "    RETURN 'CUSTOMER_ALREADY_HAS_ACTIVE_BAND';",
        "    RETURN 'CREATED';", expect=1)
    inj = inject_before_guard(inj_body, "-- （注入已落在函数体内）\n")
    ok, out = run_sql(inj)
    check("I(e3) 把『客户已有有效带子』改成 CREATED（定义段全过）→ 自证 (e3) 必须抓住",
          (not ok) and label_of(out, ALL_LABELS) == "自证失败(e3)",
          f"首报错标签={label_of(out, ALL_LABELS)}\n{out}")

    # ------------------------------------------------------------------
    # C2 等价形态：给两个函数体首尾加空行 → 必须仍然全绿
    #      🛑 防"判据收紧后误伤空白形态"。PG 的 prosrc 会保留首尾空白，
    #         而所有断言都是"子串/正则存在"，不该被空行影响。
    # ------------------------------------------------------------------
    eq = mig.replace(func_body(mig, "bind"), "\n" + func_body(mig, "bind") + "\n\n", 1)
    eq = eq.replace(func_body(eq, "unbind"), "\n" + func_body(eq, "unbind") + "\n\n", 1)
    ok, out = run_sql(eq)
    check("C2 函数体首尾加空行（等价形态）→ 必须仍然全绿",
          ok and "自证通过" in out, out)

    # ------------------------------------------------------------------
    # C3 等价形态：在 DECLARE 段加一个未使用的变量 → 必须仍然全绿
    # ------------------------------------------------------------------
    eq = replace_func_body(mig, "bind", "    v_affected   int := 0;",
                           "    v_affected   int := 0;\n    v_noise      int := 0;", expect=1)
    ok, out = run_sql(eq)
    check("C3 在 DECLARE 段加一个未使用变量（等价形态）→ 必须仍然全绿",
          ok and "自证通过" in out, out)

    # ------------------------------------------------------------------
    # C4 元门禁：五个注入组的首报错标签必须互不相同
    #      🛑 若整块自证其实只会在第一行报错（例如 (a) 排在最前而注入的是 (a) 之外的
    #         东西却仍报 (a)），那么"标签对上"这件事就失去意义。
    #         本用例断言的是：这些标签确实来自不同的断言点。
    # ------------------------------------------------------------------
    seen = {}
    cases = {
        "a": inject_before_guard(mig, "\nDROP FUNCTION bind_band(uuid, uuid, uuid, text, text, date, boolean, text);\n"),
        "d": inject_before_guard(mig, "\nDELETE FROM schema_migration WHERE version = 'V17';\n"),
        "b7": inject_before_guard(replace_func_body(
            mig, "bind", "WHERE tenant_id   = p_tenant_id\n           AND customer_id = p_customer_id",
            "WHERE true\n           AND customer_id = p_customer_id", expect=1), "\n"),
        "c7": inject_before_guard(replace_func_body(
            mig, "unbind", "       AND tenant_id = p_tenant_id\n       AND status <> 'unbound'",
            "       AND status <> 'unbound'", expect=1), "\n"),
    }
    for k, txt in cases.items():
        ok2, out2 = run_sql(txt)
        seen[k] = label_of(out2, ALL_LABELS) if not ok2 else "(未报错)"
    uniq = len(set(seen.values())) == len(seen) and "(未报错)" not in seen.values()
    check("C4 各注入组的【首报错标签】必须互不相同（防整段只在一处报错）",
          uniq, f"标签分布 = {seen}")

    # ------------------------------------------------------------------
    # C5 元门禁：全部失败分支字面量必须都在迁移文本里（防"删掉自证也算通过"）
    # ------------------------------------------------------------------
    need = ["自证失败(a)", "自证失败(b1)", "自证失败(b2)", "自证失败(b3)",
            "自证失败(b4)", "自证失败(b5)", "自证失败(b6)", "自证失败(b7)",
            "自证失败(c1)", "自证失败(c2)", "自证失败(c3)", "自证失败(c4)",
            "自证失败(c5)", "自证失败(c6)", "自证失败(c7)",
            "自证失败(d)", "自证失败(d2)", "自证失败(d3)",
            "自证失败(e1)", "自证失败(e2)", "自证失败(e3)", "自证失败(e4)",
            "自证失败(e5)", "自证失败(e6)", "自证失败(e7)", "自证失败(e8)",
            "自证失败(e9)", "自证失败(f2)"]
    missing = [s for s in need if s not in mig]
    check("C5 全部自证失败分支字面量必须都在迁移文本里",
          not missing, f"缺失: {missing}")

    # ------------------------------------------------------------------
    # C6 🆕 DDL 净效果等价性
    #
    #    本仓的处置纪律（117 的 ⑤ / 文件头）：**迁移文本一改，已应用的库就必须
    #    做一次显式处置** —— 要么证明 DDL 净效果相同（⇒ 对齐 checksum），
    #    要么承认变了（⇒ 重建库 / 补新迁移）。本次处置是前者：
    #    本轮为修 (b7)/(c7) 的假绿而改了 V17 文本（含注释与判据，**不含 DDL**），
    #    故必须给出"净效果相同"的机械证据，而不是"我记得只改了注释"。
    #
    #    before = 当前文本把两处新判据还原为旧判据（即改前形态）。
    #    🛑 这是【保守】取法：只还原判据那一处（注释差异不回滚），
    #       若净效果仍相同，那对完整的改前文本也必然相同。
    # ------------------------------------------------------------------
    print()
    print("   [C6] 构造改前/改后两份文本并分别测其 DDL 净效果 …")
    after = mig
    before = after.replace(
        "'UPDATE\\s+band\\M[^;]*tenant_id\\s*=\\s*p_tenant_id'",
        "'AND\\s+tenant_id\\s*=\\s*p_tenant_id'")
    before_diff = (len(after) - len(before)) + 0
    before_ok = before != after
    print(f"        before 与 after 文本长度差 = {before_diff} 字符"
          f"（仅两处正则字面量；{'' if before_ok else '🛑 还原未生效'}）")

    try:
        rebuild_db(REV_DB)
        # after 的净效果（V17 用磁盘原文，其余用链条原文）
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
        check("C6a 改前/改后文本的 V17 DDL 净效果完全相同（签名/函数体/权限/登记）",
              before_ok and same, det or f"before_ok={before_ok}")

        # ---- C6b 判别力自证：净效果比对必须【对真实改动有反应】----
        #      注入一句真实出现在函数体里的改动（把换机 UPDATE 的 status 集合缩小）。
        #      🛑 必须选"函数体内"的改动而不是"文件别处"的改动 —— 本比对只看
        #         prosrc + 签名 + 权限 + 登记。若注入落在注释里，本比对【本来就不该】
        #         有反应（那正是"净效果"的定义），那样的自证是假的。
        tampered = replace_func_body(
            after, "bind", "           AND status IN ('active', 'paused');",
            "           AND status IN ('active');", expect=1)
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
    print(f"===== 118 反向验证结果: {passed}/{len(results)} 通过 =====")
    print()
    if passed == len(results):
        print("⇒ 结论：V17 的每一类自证都【有牙齿】：")
        print("   · 注入的每一类错误都被【对应标签】的自证抓住（首报错标签精确对上）；")
        print("   · 语义等价的正确形态仍然全绿（判据没有变成过度收紧的误伤）；")
        print("   · 各注入组的标签互不相同 ⇒ '标签对上'这件事有信息来源，不是伪证；")
        print("   · DDL 净效果对真实改动有反应，而改前/改后净效果相同。")
        print()
        print("  🛑 本轮由此修掉了 V17 的真实缺陷：(b7)/(c7) 的初版判据没有锚点，")
        print("     会被步骤 (4) 的 SELECT 里同款子句满足 ⇒ 对'换机/解绑 UPDATE 的")
        print("     租户维度被删掉'完全无反应（假绿）。现已收紧为从 UPDATE band 起锚。")
    else:
        print("⇒ 🛑 结论：存在未通过项，V17 的自证尚不可信 —— 不得据此宣称缺口已收口。")
    if keep:
        print(f"\n（--keep：临时库 {REV_DB} 已保留）")
    else:
        print(f"\n（临时库 {REV_DB} 已清理）")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())