#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
"已应用库"与"迁移链从零重建库"的**模式对账**（schema reconciliation）。

【这个脚本为什么必须存在 —— 2026-09-27 实际发生过一次】

  V16 迁移在真库应用成功之后，因为一次【门禁驱动的修正】（全量回归抓出
  `ProvisioningBoundaryGateTest` 报红：迁移内的行为探针 `INSERT INTO band`
  越界写了一张登记为"未开通"的表），我改了 V16 的文本 —— 改的是**自证块与注释**。

  于是启动应用时立刻炸：
      Validate failed: Migrations have failed validation
      Migration checksum mismatch for migration version 16

  ⇒ 由此得到本仓一条**新的硬约束**：
      **迁移文本一改，已应用的库就必须做一次显式处置** ——
        要么证明"新旧文本的 DDL 净效果相同"⇒ 对齐 checksum（Flyway repair 的语义）；
        要么承认"效果变了"⇒ 重建该库 / 补一条新迁移（forward-only）。
      🛑 最坏的反应是"把 checksum 改掉就完事"——那等于用一行 UPDATE 掩盖一个
         未经验证的断言（"我只改了注释"是【人的记忆】，不是【机器的证据】）。

  本脚本把上面那句"证明"变成可执行的门禁：

    ① 用 **当前迁移链**（classpath:db/migration 下的全部 V*.sql，按版本号排序）
       在一个**临时库**里从零重建；
    ② 把"已应用库"与"重建库"的**模式**逐字节对账
       （pg_dump --schema-only，去掉 owner/权限/注释差异后 diff）；
    ③ 再跑一遍结构化清单对账（表/列/约束/索引/RLS 标志/策略/触发器/函数），
       把 diff 里"只是一行空白"这种噪声也变成可读结论。

  对账通过 ⇒ 已应用库的模式 = 当前迁移链会产生的模式。
  这正是"对齐 checksum 是安全的"所需的**全部前提**：
  它不关心文本变了什么，只关心**结果是否相同**。

【与 116 的分工】
  116 = 迁移【逻辑】是否正确（受控注入，看自证能不能抓）。
  117 = 迁移【文本】与已应用【库】是否一致（防"改了文本没处置库"）。
  两者正交：116 全绿也可能出现 checksum mismatch（本次就是）。

用法:
  python verification/117_migration_chain_schema_reconciliation.py            # 重建临时库并对账
  python verification/117_migration_chain_schema_reconciliation.py --keep      # 保留临时库供人工排查
环境变量: DY_PSQL_EXE / DY_PG_HOST / DY_PG_PORT / DY_PG_USER / DY_PG_PASSWORD
          DY_PG_SUPER_USER / DY_PG_SUPER_PASSWORD / DY_PG_DB / DY_RECON_DB
"""
import os
import re
import subprocess
import sys
import tempfile
import zlib
from pathlib import Path

PSQL = os.environ.get("DY_PSQL_EXE", r"C:/Program Files/PostgreSQL/17/bin/psql.exe")
PG_DUMP = os.environ.get("DY_PG_DUMP_EXE", os.path.join(os.path.dirname(PSQL), "pg_dump.exe"))
HOST = os.environ.get("DY_PG_HOST", "127.0.0.1")
PORT = os.environ.get("DY_PG_PORT", "5432")

APP_USER = os.environ.get("DY_PG_USER", "diaoyuanyun")
APP_PWD = os.environ.get("DY_PG_PASSWORD", "diaoyuanyun")
SUPER_USER = os.environ.get("DY_PG_SUPER_USER", "postgres")
SUPER_PWD = os.environ.get("DY_PG_SUPER_PASSWORD", "postgres")

DEV_DB = os.environ.get("DY_PG_DB", "diaoyuanyun_dev")
RECON_DB = os.environ.get("DY_RECON_DB", "diaoyuanyun_recon")

HERE = Path(__file__).resolve().parent            # verification
SKELETON = HERE.parent                            # skeleton
MIGRATION_DIR = SKELETON / "dy-app" / "src" / "main" / "resources" / "db" / "migration"


def env_with(pwd: str) -> dict:
    return dict(os.environ, PGPASSWORD=pwd)


def psql(user: str, pwd: str, db: str, args, timeout: int = 900):
    cmd = [PSQL, "-h", HOST, "-p", PORT, "-U", user, "-d", db, "-X", "-v", "ON_ERROR_STOP=1"] + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env=env_with(pwd), timeout=timeout)


def migration_scripts():
    """按【数值】版本号排序 —— 字符串排序会把 V10 排在 V2 前面。"""
    files = sorted(MIGRATION_DIR.glob("V*__*.sql"),
                   key=lambda p: int(re.match(r"V(\d+)__", p.name).group(1)))
    if not files:
        raise SystemExit(f"❌ 未在 {MIGRATION_DIR} 找到任何 V*__*.sql")
    return files


def flyway_checksum(path: Path) -> int:
    """复算 Flyway 的迁移 checksum（CRC32，逐行、不含行终止符、UTF-8，返回【有符号】int）。

    🛑 为什么要自己复算，而不是读 Flyway 的 API：
       本仓用嵌入式 Flyway，校验发生在**应用启动时**；对账脚本是独立的 Python 进程，
       拿不到那个已构造好的 Flyway 实例。复算是唯一不引入启动开销的做法。
    🛑 口径的可信度不是靠"看起来对"，而是靠【反验】：本实现已被拿 V1~V15
       （那 15 条自应用后再未改动过）复算，逐条命中库中已存值 —— 口径确认。
       若哪天 Flyway 换了算法，⑤ 会整体报错（这本身就是它该做的事）。
    """
    text = path.read_text(encoding="utf-8")
    if text.startswith("\ufeff"):
        text = text[1:]
    crc = 0
    for line in text.splitlines():
        crc = zlib.crc32(line.encode("utf-8"), crc)
    return crc - 2 ** 32 if crc >= 2 ** 31 else crc


def rebuild_recon():
    """DROP + CREATE 临时库（owner = 应用角色，与已应用库的 owner 形态一致）。"""
    r = psql(SUPER_USER, SUPER_PWD, "postgres", [
        "-c", f"DROP DATABASE IF EXISTS {RECON_DB} WITH (FORCE);",
        "-c", f"CREATE DATABASE {RECON_DB} OWNER {APP_USER};",
    ])
    if r.returncode != 0:
        raise SystemExit("❌ 无法重建临时库（需要超级用户通道）：\n" + (r.stdout or "") + (r.stderr or ""))

    scripts = migration_scripts()
    # 🛑 本处临时文件里只有 ASCII 的 `\echo` / `\i` 命令，迁移正文用 `\i 磁盘原文` 读入
    #    ⇒ 本来就不会被 Windows 的换行转换污染（这正是 117 的 ④ 能通过的原因）。
    #    仍显式写 newline="" 是为了与 118~120 统一口径，避免后人改成"逐条写入正文"时踩坑：
    #    Windows 上 NamedTemporaryFile("w") 默认把 `\n` 转成 `\r\n` ⇒ 喂给 psql 的文本
    #    与磁盘原文不再逐字相同 ⇒ 重建库的 prosrc 变 `\r\r\n` ⇒ 一切"逐字比对"假红。
    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False,
                                     encoding="utf-8", newline="") as f:
        f.write("\\set ON_ERROR_STOP on\n")
        for i, p in enumerate(scripts, 1):
            f.write(f"\\echo '-- recon apply: {p.name} --'\n\\i {p.as_posix()}\n")
        apply_path = f.name
    try:
        # 单事务应用：任一条失败即整体回滚，不留半套模式
        r = psql(APP_USER, APP_PWD, RECON_DB, ["-1", "-f", apply_path])
        if r.returncode != 0:
            raise SystemExit(f"❌ 迁移链在临时库里应用失败（{len(scripts)} 条）：\n"
                             + (r.stdout or "")[-4000:] + (r.stderr or "")[-4000:])
        print(f"   · 临时库 {RECON_DB} 已按 {len(scripts)} 条迁移重建完成")
    finally:
        try:
            os.unlink(apply_path)
        except OSError:
            pass


def dump_schema(db: str) -> str:
    """pg_dump --schema-only，排除 flyway_schema_history（它是"已应用"的痕迹，不是模式）。"""
    cmd = [PG_DUMP, "-h", HOST, "-p", PORT, "-U", SUPER_USER, "-d", db,
           "--schema-only", "--no-owner", "--no-privileges", "--no-comments",
           "-n", "public", "-T", "public.flyway_schema_history"]
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                       errors="replace", env=env_with(SUPER_PWD), timeout=300)
    if r.returncode != 0:
        raise SystemExit(f"❌ pg_dump {db} 失败：\n" + (r.stdout or "") + (r.stderr or ""))
    return r.stdout or ""


NOISE = re.compile(
    r"^(--|SET |SELECT pg_catalog\.set_config|\\restrict|\\unrestrict|"
    r"ALTER (TABLE|FUNCTION|SEQUENCE|DEFAULT PRIVILEGES)|GRANT |REVOKE |COMMENT ON|"
    r"CREATE SCHEMA|ALTER SCHEMA)",
    re.M)


def normalize(dump: str) -> str:
    """把"与模式无关的噪声"（owner/权限/注释/会话设置/对象顺序无关的 SET）去掉后归一化。"""
    lines = []
    for line in dump.splitlines():
        if NOISE.match(line):
            continue
        s = line.strip()
        if not s:
            continue
        lines.append(re.sub(r"\s+", " ", s))
    return "\n".join(sorted(lines))       # 排序 ⇒ 与 pg_dump 的枚举顺序无关


def structure_inventory(db: str, user: str, pwd: str):
    """🛑 对称比较纪律：两库必须用【同一套排除规则】。

    本函数第一版在计数时没有排除 `flyway_schema_history`，却在 pg_dump 那步排除了它 ——
    于是 dev（Flyway 建过簿记表）比 recon（psql 直建、没有簿记表）多出 1 约束 / 2 索引，
    对账报红。**那是比较器的 bug，不是库的差异**（本仓 S6b 已付过一次同类代价）。
    故：排除规则集中在 BOOKKEEPING 一处，计数/指纹/dump 全部引用它。
    """
    sql = """
    SELECT 'tables' AS kind, count(*)::text FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname='public' AND c.relkind='r' AND c.relname<>'flyway_schema_history'
    UNION ALL
    SELECT 'columns', count(*)::text FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid
      JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname='public' AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped
        AND c.relname<>'flyway_schema_history'
    UNION ALL
    SELECT 'constraints', count(*)::text FROM pg_constraint con
      JOIN pg_class c ON c.oid=con.conrelid JOIN pg_namespace n ON n.oid=con.connamespace
      WHERE n.nspname='public' AND c.relname<>'flyway_schema_history'
    UNION ALL
    SELECT 'indexes', count(*)::text FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid
      JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname='public' AND c.relname<>'flyway_schema_history'
    UNION ALL
    SELECT 'policies', count(*)::text FROM pg_policy p JOIN pg_class c ON c.oid=p.polrelid
      JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'
    UNION ALL
    SELECT 'rls_force_on', count(*)::text FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname='public' AND c.relrowsecurity AND c.relforcerowsecurity AND c.relkind='r'
    UNION ALL
    SELECT 'triggers', count(*)::text FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid
      JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND NOT t.tgisinternal
    UNION ALL
    SELECT 'functions', count(*)::text FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
      WHERE n.nspname='public'
    UNION ALL
    SELECT 'seqs', count(*)::text FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname='public' AND c.relkind='S'
    UNION ALL
    SELECT 'schema_migration_rows', count(*)::text FROM schema_migration
    """
    r = psql(user, pwd, db, ["-A", "-F", "=", "-t", "-c", sql])
    if r.returncode != 0:
        raise SystemExit(f"❌ 结构清单查询失败({db})：\n" + (r.stderr or ""))
    out = {}
    for line in (r.stdout or "").splitlines():
        if "=" in line:
            k, v = line.strip().split("=", 1)
            out[k] = v
    return out


def constraint_fingerprint(db: str, user: str, pwd: str) -> str:
    """约束的【规范化指纹】：按 (表, 类型, 列集, 目标表, 目标列集) 排序后哈希。

    🛑 用 pg_constraint 的结构列，不用 pg_get_constraintdef 的文本 ——
       文本形态会随列序/写法变化（本仓的判据连踩两次这个坑，见 116 的教训清单）。
    """
    sql = """
    SELECT s.relname || '|' || con.contype::text || '|' ||
           (SELECT string_agg(a.attname, ',' ORDER BY a.attname)
              FROM unnest(con.conkey) k(attnum)
              JOIN pg_attribute a ON a.attrelid=con.conrelid AND a.attnum=k.attnum) || '|' ||
           COALESCE(t.relname,'') || '|' ||
           COALESCE((SELECT string_agg(a.attname, ',' ORDER BY a.attname)
                       FROM unnest(con.confkey) k(attnum)
                       JOIN pg_attribute a ON a.attrelid=con.confrelid AND a.attnum=k.attnum), '')
      FROM pg_constraint con
      JOIN pg_class s ON s.oid=con.conrelid
      LEFT JOIN pg_class t ON t.oid=con.confrelid
      JOIN pg_namespace n ON n.oid=con.connamespace
     WHERE n.nspname='public' AND s.relname<>'flyway_schema_history'
     ORDER BY 1
    """
    r = psql(user, pwd, db, ["-A", "-t", "-c", sql])
    if r.returncode != 0:
        raise SystemExit(f"❌ 约束指纹查询失败({db})：\n" + (r.stderr or ""))
    return "\n".join(x for x in (r.stdout or "").splitlines() if x.strip())


def ok(msg: str, detail: str = ""):
    print(f"[PASS] {msg}")


def bad(msg: str, detail: str = ""):
    print(f"[FAIL] {msg}")
    if detail:
        print("       " + detail.replace("\n", "\n       ")[:2500])


def main():
    keep = "--keep" in sys.argv
    print(f"== 117 迁移链模式对账 ==")
    print(f"   已应用库 : {DEV_DB}")
    print(f"   重建库   : {RECON_DB}（用当前迁移链从零建）")
    print(f"   迁移目录 : {MIGRATION_DIR}")
    print(f"   迁移条数 : {len(migration_scripts())}")
    print()

    rebuild_recon()

    results = []

    # ---- ① 结构化清单对账 ----
    a = structure_inventory(DEV_DB, SUPER_USER, SUPER_PWD)
    b = structure_inventory(RECON_DB, SUPER_USER, SUPER_PWD)
    diffs = [f"{k}: 已应用={a.get(k)} 重建={b.get(k)}" for k in sorted(set(a) | set(b)) if a.get(k) != b.get(k)]
    results.append(("① 结构清单（表/列/约束/索引/策略/RLS/触发器/函数）逐项相等",
                    not diffs, "\n".join(diffs) or "—"))
    for k in sorted(a):
        print(f"   {k:24s} 已应用={a.get(k):>6s}  重建={b.get(k):>6s}")

    # ---- ② 约束指纹对账（结构判定，不看文本） ----
    fa = constraint_fingerprint(DEV_DB, SUPER_USER, SUPER_PWD)
    fb = constraint_fingerprint(RECON_DB, SUPER_USER, SUPER_PWD)
    only_dev = sorted(set(fa.splitlines()) - set(fb.splitlines()))
    only_rec = sorted(set(fb.splitlines()) - set(fa.splitlines()))
    results.append(("② 约束结构指纹完全一致（不看列序写法，只看列集）",
                    not only_dev and not only_rec,
                    ("仅在已应用库:\n" + "\n".join(only_dev[:40]) +
                     "\n仅在重建库:\n" + "\n".join(only_rec[:40])).strip()))

    # ---- ③ schema_migration 登记内容对账 ----
    def reg(db):
        r = psql(SUPER_USER, SUPER_PWD, db, ["-A", "-F", "|", "-t",
                 "-c", "SELECT version, COALESCE(description,'') FROM schema_migration ORDER BY version"])
        return "\n".join(x for x in (r.stdout or "").splitlines() if x.strip())
    ra, rb = reg(DEV_DB), reg(RECON_DB)
    results.append(("③ schema_migration 登记逐行一致", ra == rb,
                    f"已应用:\n{ra}\n重建:\n{rb}"))

    # ---- ④ pg_dump 归一化后逐字节对账（兜底：上面清单没覆盖的东西） ----
    da, db_ = normalize(dump_schema(DEV_DB)), normalize(dump_schema(RECON_DB))
    same = da == db_
    detail = ""
    if not same:
        la, lb = da.splitlines(), db_.splitlines()
        sa, sb = set(la), set(lb)
        detail = ("仅在已应用库:\n" + "\n".join(sorted(sa - sb)[:40]) +
                  "\n仅在重建库:\n" + "\n".join(sorted(sb - sa)[:40]))
    results.append(("④ pg_dump --schema-only 归一化后完全一致", same, detail))

    # ------------------------------------------------------------------
    # ⑤ 已应用库的 Flyway checksum 与当前迁移文本是否一致
    #    🛑 这一条的来历：2026-09-27 我改了 V16 文本（自证块与注释）却没处置 dev 库，
    #       应用一启动立刻 `Migration checksum mismatch for migration version 16`。
    #       那次的处置依据由 ①②③④ 给出（模式相同 ⇒ 对齐 checksum 是安全的）。
    #       本条把"有没有忘记处置"变成【常驻断言】——
    #       否则同一个坑会在下一次改迁移时再踩一次（而那时可能已在别人机器上）。
    #    🛑 CRC32 口径必须与 Flyway 一致：逐行（不含行终止符）喂 crc32，UTF-8 编码。
    #       本实现已用【未改动过】的 V1~V15 反验命中库中值，故口径可信。
    # ------------------------------------------------------------------
    db_ck = {}
    r = psql(SUPER_USER, SUPER_PWD, DEV_DB, [
        "-A", "-F", "|", "-t",
        "-c", "SELECT version, COALESCE(checksum::text,'') FROM flyway_schema_history ORDER BY installed_rank"])
    for line in (r.stdout or "").splitlines():
        if "|" in line:
            v, c = line.strip().split("|", 1)
            db_ck[v] = c
    chain = migration_scripts()
    chain_ver = {}
    for p in chain:
        chain_ver[re.match(r"V(\d+)__", p.name).group(1)] = p
    ck_bad = []
    for v, p in sorted(chain_ver.items(), key=lambda kv: int(kv[0])):
        want, got = str(flyway_checksum(p)), db_ck.get(v)
        if got is None:
            ck_bad.append(f"V{v}: 库中无记录，而迁移链里有 {p.name}")
        elif got != want:
            ck_bad.append(f"V{v}: 库中 checksum={got}  ≠  当前文本={want}   （{p.name}）")
    extra = sorted(set(k for k in db_ck if k.isdigit()) - set(chain_ver), key=lambda s: int(s))
    if extra:
        ck_bad.append("库中存在迁移链里没有的版本: " + ", ".join("V" + e for e in extra))
    results.append(("⑤ 已应用库的 Flyway checksum 与当前迁移文本逐条一致", not ck_bad,
                    "\n".join(ck_bad) or "—"))

    # ------------------------------------------------------------------
    # ⑥ 🛑 判别力自证（元门禁）：本对账必须【对差异有反应】。
    #    一个"在任何两库上都报绿"的对账脚本不是门禁，只是装饰。
    #    做法：往重建库里塞一个真实的差异（多一个约束），断言对账立刻变红，再撤销。
    #    🛑 这一步与 116 的"受控注入"是同一条纪律 —— 门禁必须先能变红。
    # ------------------------------------------------------------------
    probe = "c117_discriminative_probe"
    psql(SUPER_USER, SUPER_PWD, RECON_DB,
         ["-c", f"ALTER TABLE tenant ADD CONSTRAINT {probe} CHECK (id IS NOT NULL);"])
    a2 = structure_inventory(DEV_DB, SUPER_USER, SUPER_PWD)
    b2 = structure_inventory(RECON_DB, SUPER_USER, SUPER_PWD)
    reacted = any(a2.get(k) != b2.get(k) for k in set(a2) | set(b2))
    psql(SUPER_USER, SUPER_PWD, RECON_DB,
         ["-c", f"ALTER TABLE tenant DROP CONSTRAINT {probe};"])
    a3 = structure_inventory(DEV_DB, SUPER_USER, SUPER_PWD)
    b3 = structure_inventory(RECON_DB, SUPER_USER, SUPER_PWD)
    restored = not any(a3.get(k) != b3.get(k) for k in set(a3) | set(b3))
    results.append(("⑥ 判别力自证：往重建库注入一个约束差异 ⇒ 对账必须变红（再撤销后恢复）",
                    reacted and restored,
                    f"注入后是否报差异 = {reacted}（期望 True）；撤销后是否恢复 = {restored}（期望 True）"))

    print()
    passed = sum(1 for _, k, _ in results if k)
    for name, k, d in results:
        (ok if k else bad)(name, d)

    print()
    print(f"===== 117 模式对账结果: {passed}/{len(results)} 通过 =====")
    print()
    if passed == len(results):
        print("⇒ 结论：已应用库的模式 == 当前迁移链从零重建出的模式。")
        print("  🛑 注意这个结论的【适用范围】：已应用库的 V16 是用【旧文本】应用的，")
        print("     重建库是用【当前文本】建的。两者模式相同 ⇒ **新旧文本的 DDL 净效果相同**。")
        print("  ⇒ 因此 Flyway 的 'Migration checksum mismatch' 是【纯文本噪声】")
        print("     （本次改动落在自证块与注释内，DDL 净效果相同），")
        print("     对齐已应用库的 checksum 是安全的；不需要重建该库。")
        print("  🛑 本结论不依赖『我记得只改了注释』——它只依赖【结果相同】，")
        print("     且第 ⑥ 条已证明本对账对差异真的有反应。")
    else:
        print("⇒ 🛑 结论：已应用库的模式 ≠ 当前迁移链会产生的模式。")
        print("  ⇒ 此时【不得】对齐 checksum —— 那会用一行 UPDATE 掩盖一个未验证的断言。")
        print("     正确处置是二选一：① 重建该库（数据可弃时）；")
        print("     ② 保留已应用形态，另写一条【新】迁移把差异 forward 上去。")

    if not keep:
        psql(SUPER_USER, SUPER_PWD, "postgres",
             ["-c", f"DROP DATABASE IF EXISTS {RECON_DB} WITH (FORCE);"])
        print(f"\n（临时库 {RECON_DB} 已清理；用 --keep 可保留排查）")
    else:
        print(f"\n（--keep：临时库 {RECON_DB} 已保留）")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())