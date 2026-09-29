#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""V18「DDL 净效果」对账探针：**磁盘文本** vs **已应用库**。

【为什么需要这个探针】

  117 的对账 ⑤ 报红：
      库中 V18 checksum = -1553162480  ≠  当前文本复算 = -1932910031

  按本仓纪律，此时**不得**直接 `UPDATE flyway_schema_history` 改数字
  —— 那等于用一行 UPDATE 掩盖一个未经验证的断言。
  唯一可接受的路径是：**证明两份文本的 DDL 净效果相同**（⇒ 允许对齐 checksum），
  否则承认 DDL 变了（⇒ 重建库 / 补新迁移）。

  🛑 注意本探针的"两侧"与 119 的 C6 不同：
     119 的 C6 比的是"当前文本 vs 当前文本的还原版"；
     本探针比的是"**当前文本重建出来的库** vs **真正已应用的那个库**"，
     这才是 ⑤ 报红时要回答的问题。

【净效果口径（对 V18 而言 = 它的第 1/2/3 节的产出）】

  ① 函数签名        proname + 参数 + 返回类型
  ② 函数体原文      prosrc（逐字 + md5）
  ③ 函数 EXECUTE 权限 proacl + has_function_privilege
  ④ 迁移登记行      schema_migration 中 V18 的 description

  第 4 节（自证）与第 5 节（注释）无产出，故不在口径内 ——
  这正是"guard 里改了判据不影响净效果"的根据。
"""
import hashlib
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
SKELETON = HERE.parent
MIGRATION_DIR = SKELETON / "dy-app" / "src" / "main" / "resources" / "db" / "migration"

PSQL = os.environ.get("DY_PSQL_EXE", r"C:/Program Files/PostgreSQL/17/bin/psql.exe")
HOST = os.environ.get("DY_PG_HOST", "127.0.0.1")
PORT = os.environ.get("DY_PG_PORT", "5432")
APP_USER = os.environ.get("DY_PG_USER", "diaoyuanyun")
APP_PWD = os.environ.get("DY_PG_PASSWORD", "diaoyuanyun")
SUPER_USER = os.environ.get("DY_PG_SUPER_USER", "postgres")
SUPER_PWD = os.environ.get("DY_PG_SUPER_PASSWORD", "postgres")
DEV_DB = os.environ.get("DY_PG_DB", "diaoyuanyun_dev")
REV_DB = os.environ.get("DY_V18_EFF_REV_DB", "diaoyuanyun_v18eff")


def env_with(pwd):
    return dict(os.environ, PGPASSWORD=pwd)


def psql(user, pwd, db, args, timeout=900):
    cmd = [PSQL, "-h", HOST, "-p", PORT, "-U", user, "-d", db, "-X"] + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", env=env_with(pwd), timeout=timeout)


def migration_scripts():
    files = sorted(MIGRATION_DIR.glob("V*__*.sql"),
                   key=lambda p: int(re.match(r"V(\d+)__", p.name).group(1)))
    if not files:
        raise SystemExit(f"❌ 未在 {MIGRATION_DIR} 找到任何 V*__*.sql")
    return files


def rebuild_db(db):
    r = psql(SUPER_USER, SUPER_PWD, "postgres", [
        "-c", f"DROP DATABASE IF EXISTS {db} WITH (FORCE);",
        "-c", f"CREATE DATABASE {db} OWNER {APP_USER};"])
    if r.returncode != 0:
        raise SystemExit("❌ 无法重建临时库：\n" + (r.stdout or "") + (r.stderr or ""))


def apply_chain(db):
    """把整条迁移链（全部用磁盘原文）应用到临时库。

    🛑🛑 必须 `newline=""` —— 这是本探针第一版踩过的坑，也是本仓的一条通用坑：
       Windows 上 `NamedTemporaryFile("w")` 默认 newline=None ⇒ Python 会把
       文本里的 `\n` **转成** `\\r\\n` 写进临时文件 ⇒ psql 读到的每行末尾多一个 CR ⇒
       重建库里的 `pg_proc.prosrc` 变成 `\\r\\r\\n` 形态（比真库多一个 CR）。
       实测后果：净效果比对报"不同"，**而 V18 文本其实一个字都没白改** ——
       一条完全归因错误的红（会让人去"修"一个本来正确的迁移）。
       ⇒ 凡"把迁移文本写进临时文件再喂 psql"的脚本，一律 newline=""。
    """
    scripts = migration_scripts()
    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False,
                                     encoding="utf-8", newline="") as f:
        f.write("\\set ON_ERROR_STOP on\n")
        for p in scripts:
            f.write(f"\n\\echo '-- chain apply: {p.name} --'\n")
            f.write(p.read_text(encoding="utf-8"))
            f.write("\n")
        path = f.name
    try:
        r = psql(APP_USER, APP_PWD, db, ["-1", "-v", "ON_ERROR_STOP=on", "-f", path])
        if r.returncode != 0:
            raise SystemExit(f"❌ 迁移链在 {db} 应用失败：\n"
                             + (r.stdout or "")[-4000:] + (r.stderr or "")[-4000:])
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass


EFFECT_SQL = r"""
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
       has_function_privilege('diaoyuanyun', p.oid, 'EXECUTE')::text
  FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
 WHERE n.nspname = 'public' AND p.proname IN ('register_device','retire_device')
UNION ALL
SELECT 'reg|V18|' || COALESCE(description,'')
  FROM schema_migration WHERE version = 'V18'
"""


def effect(db):
    r = psql(APP_USER, APP_PWD, db, ["-A", "-t", "-c", EFFECT_SQL])
    if r.returncode != 0:
        raise SystemExit(f"❌ 采集净效果失败({db})：\n" + (r.stderr or ""))
    return "\n".join(x for x in (r.stdout or "").splitlines() if x.strip())


def flyway_checksum(path: Path) -> int:
    import zlib
    text = path.read_text(encoding="utf-8")
    if text.startswith("\ufeff"):
        text = text[1:]
    crc = 0
    for line in text.splitlines():
        crc = zlib.crc32(line.encode("utf-8"), crc)
    return crc - 2 ** 32 if crc >= 2 ** 31 else crc


def main():
    mig = MIGRATION_DIR / "V18__device_provisioning_primitive.sql"
    text_cks = flyway_checksum(mig)
    r = psql(APP_USER, APP_PWD, DEV_DB, [
        "-A", "-t", "-c",
        "SELECT checksum FROM flyway_schema_history WHERE version='18';"])
    db_cks = (r.stdout or "").strip()

    print("== V18 DDL 净效果对账探针 ==")
    print(f"   磁盘文本 checksum = {text_cks}")
    print(f"   库中登记 checksum = {db_cks}")
    print(f"   两者是否相等      = {str(text_cks) == db_cks}")
    print()

    eff_applied = effect(DEV_DB)
    print("-- 已应用库（diaoyuanyun_dev）的 V18 净效果 --")
    for ln in eff_applied.splitlines():
        print("   " + (ln[:120] + ("…" if len(ln) > 120 else "")))
    print()

    rebuild_db(REV_DB)
    try:
        apply_chain(REV_DB)
        eff_rebuilt = effect(REV_DB)
    finally:
        pass

    print("-- 用【当前磁盘文本】重建出来的库的 V18 净效果 --")
    for ln in eff_rebuilt.splitlines():
        print("   " + (ln[:120] + ("…" if len(ln) > 120 else "")))
    print()

    same = eff_applied == eff_rebuilt
    print(f"===== 净效果是否相同: {'相同 ✅' if same else '不同 🛑'} =====")
    if same:
        print("⇒ 证据充分：当前磁盘文本重建出的 V18 产出，与已应用库逐字相同。")
        print("   ⇒ 按本仓纪律【允许】把 checksum 对齐到磁盘文本的复算值：")
        print(f"      UPDATE flyway_schema_history SET checksum = {text_cks} "
              f"WHERE version = '18';")
        print("   🛑 这仍然只是处置的一半 —— 另一半是：把 target/classes 里的旧副本")
        print("      也同步掉（mvn -pl dy-app process-resources），否则下次测试读旧文本。")
    else:
        a = set(eff_applied.splitlines())
        b = set(eff_rebuilt.splitlines())
        print("仅在【已应用库】:")
        for x in sorted(a - b):
            print("   " + x[:160])
        print("仅在【重建库】:")
        for x in sorted(b - a):
            print("   " + x[:160])
        print("⇒ 🛑 不得对齐 checksum —— DDL 净效果确实变了。")
        print("  正确处理：重建库（本仓 dev 库可重建）/ 补一条新迁移。")

    if "--keep" not in sys.argv:
        psql(SUPER_USER, SUPER_PWD, "postgres",
             ["-c", f"DROP DATABASE IF EXISTS {REV_DB} WITH (FORCE);"])
    return 0 if same else 1


if __name__ == "__main__":
    sys.exit(main())