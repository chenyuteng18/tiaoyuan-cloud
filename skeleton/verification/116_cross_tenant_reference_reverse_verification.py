#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
V16 跨租户引用完整性的**反向验证**（反证法 / 有牙齿的门禁）。

门禁纪律：一个门禁若只会在"正确实现"下变绿，它就不是门禁。
本脚本对 V16 迁移文本做**受控注入**，断言：
  · 每一类错误都必须被【对应的那条自证】抓住（且报错理由必须对上）；
  · 每一个【语义等价但形态不同】的正确实现都必须仍然变绿（防误伤）。

🛑 关键设计（本脚本第一版失败过一次，原因在此）：注入语句必须
   【插在 $v16_guard$ 自证块之前】，而不是追加在迁移全文之后。
   第一版把注入追加在文件末尾 —— 那时自证块已经跑完并且成功了，
   于是"注入的错误没有任何自证去抓"，I1~I4 全部假失败。
   等价于"考完试再改答案"。本版用 SENTINEL 正则把迁移切两半再拼。

用例矩阵：
  注入组（必须报错，且理由对上）
    I1 把一处复合外键改回单列        → 自证 (a)「仍有 N 处未被复合化」
    I2 把一处复合外键整个删掉        → 自证 (b)「复合外键数 = 46（期望 47）」
    I3 抹掉迁移登记行                → 自证 (c)「登记行数 = 0」
    I4 摘掉载体唯一约束(CASCADE)     → 自证 (d)「缺少 (tenant_id, <pk>)」
  行为组（真库行为，不是看定义）
    I5 迁移后注入跨租户引用          → 数据库必须拒绝
  对照组（必须仍然成功）
    C1 原样重跑 V16                  → 必须全绿
    C2 同租户引用                    → 必须成功（防"一拒了之"）
    C3 🆕 列序交换的等价形态          → 必须仍然全绿（防判据误伤）
  元门禁
    C5 🆕 五条自证失败分支字面量       → 必须都在迁移文本里（防"删了自证也算通过"）

🛑 【为什么本脚本可以用 band→customer，而迁移里的常驻探针必须换成 store→region】
   判据的适用范围 = 扫描范围。
   · 迁移(`dy-app/src/main/resources/db/migration/*.sql`)在
     `ProvisioningBoundaryGateTest` 的扫描面内 —— 该门禁断言
     「登记为"未开通"的表（含 band），生产代码里必须真的没有 INSERT INTO」。
     迁移里写一行 `INSERT INTO band` ⇒ 门禁报红（2026-09-27 全量回归实测），
     且【门禁是对的】：一条完整性修复迁移不该成为 band 的第一个写入方。
   · 本脚本在 verification/ 下，不在扫描面内；且它的注入 SQL 只写进
     NamedTemporaryFile，整个文件包在 BEGIN … ROLLBACK 里执行，
     既不落盘到迁移链，也不改动生产代码 ⇒ 用它构造"跨租户引用"证据是安全的。
   ⇒ 结论：I5/C2 保留 band→customer（它正是原始缺口的证据表对，与本文件头
     第 1 节的实测记录同源，可相互对读）；迁移内探针改用 store→region。
     这不是"漏改脚本"，而是一条要留在注释里的边界。

用法: python verification/116_cross_tenant_reference_reverse_verification.py
"""
import os
import re
import subprocess
import sys
import tempfile

PSQL = os.environ.get("DY_PSQL_EXE", r"C:/Program Files/PostgreSQL/17/bin/psql.exe")
DB = os.environ.get("DY_PG_DB", "diaoyuanyun_dev")
USER = os.environ.get("DY_PG_USER", "diaoyuanyun")
PWD = os.environ.get("DY_PG_PASSWORD", "diaoyuanyun")
HOST = os.environ.get("DY_PG_HOST", "127.0.0.1")
PORT = os.environ.get("DY_PG_PORT", "5432")
ENV = dict(os.environ, PGPASSWORD=PWD)

HERE = os.path.dirname(os.path.abspath(__file__))          # verification
SKELETON = os.path.dirname(HERE)                            # skeleton
MIGRATION = os.path.join(SKELETON, "dy-app", "src", "main", "resources",
                         "db", "migration", "V16__cross_tenant_reference_integrity.sql")

# 自证块的起点：用它把迁移切成"前半"与"自证及其后"
GUARD_RE = re.compile(r"DO\s*\n\$v16_guard\$")


def run_sql(sql: str, timeout: int = 300):
    """在一个事务里跑一段 SQL，返回 (是否成功, 合并输出)。"""
    with tempfile.NamedTemporaryFile("w", suffix=".sql", delete=False,
                                     encoding="utf-8") as f:
        f.write("\\set ON_ERROR_STOP on\nBEGIN;\n")
        f.write(sql)
        f.write("\nROLLBACK;\n")
        path = f.name
    try:
        p = subprocess.run([PSQL, "-h", HOST, "-p", PORT, "-U", USER, "-d", DB,
                            "-X", "-f", path, "-v", "ON_ERROR_STOP=on"],
                           capture_output=True, text=True, encoding="utf-8",
                           errors="replace", env=ENV, timeout=timeout)
        return p.returncode == 0, (p.stdout or "") + (p.stderr or "")
    finally:
        try:
            os.unlink(path)
        except OSError:
            pass


def inject_before_guard(mig: str, injection: str) -> str:
    """🛑 把注入插到 $v16_guard$ 自证块【之前】。这一步是本脚本的核心。"""
    m = GUARD_RE.search(mig)
    if not m:
        raise SystemExit("❌ 未在迁移中找到 $v16_guard$ 自证块 —— 迁移结构已变，"
                         "本脚本的注入锚点失效，必须先修脚本。")
    head, guard_and_rest = mig[:m.start()], mig[m.start():]
    return (head
            + "\n-- ===== 反向验证注入（位于自证块之前，故自证【有机会】抓住它）=====\n"
            + injection
            + "\n-- ===== 注入结束 =====\n\n"
            + guard_and_rest)


def main():
    if not os.path.isfile(MIGRATION):
        print(f"❌ 找不到被测迁移: {MIGRATION}")
        return 2
    with open(MIGRATION, encoding="utf-8") as f:
        mig = f.read()

    results = []

    def check(name, ok, detail):
        results.append((name, ok, detail))
        print(f"[{'PASS' if ok else 'FAIL'}] {name}")
        if not ok:
            print("       " + detail.replace("\n", "\n       ")[:3000])

    # ------------------------------------------------------------------
    # C1 原样重跑必须全绿
    # ------------------------------------------------------------------
    ok, out = run_sql(mig)
    check("C1 原样重跑 V16 必须全绿", ok and "自证通过" in out, out)

    # ------------------------------------------------------------------
    # I1 漏改一处 → (a) 抓
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
ALTER TABLE band DROP CONSTRAINT band_customer_id_fkey;
ALTER TABLE band ADD CONSTRAINT band_customer_id_fkey
    FOREIGN KEY (customer_id) REFERENCES customer (id);
""")
    ok, out = run_sql(inj)
    check("I1 漏改一处单列外键 → 自证 (a) 必须抓住",
          (not ok) and ("自证失败(a)" in out), out)

    # ------------------------------------------------------------------
    # I2 删掉一处复合 → (b) 抓（(a) 仍为 0，因为删掉后既非单列也非复合）
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
ALTER TABLE band DROP CONSTRAINT band_customer_id_fkey;
""")
    ok, out = run_sql(inj)
    check("I2 删掉一处复合外键 → 自证 (b) 必须抓住",
          (not ok) and ("自证失败(b)" in out), out)

    # ------------------------------------------------------------------
    # I3 抹掉登记 → (c) 抓
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
DELETE FROM schema_migration WHERE version = 'V16';
""")
    ok, out = run_sql(inj)
    check("I3 删掉迁移登记 → 自证 (c) 必须抓住",
          (not ok) and ("自证失败(c)" in out), out)

    # ------------------------------------------------------------------
    # I4 摘掉载体 → (d) 抓
    #     CASCADE 连依赖它的 FK 一起摘：这样 (a) 不会先报，(d) 才是第一报错。
    #     🛑 这正好验证了"(d) 已前置为根因断言"——若 (d) 仍在 (a)(b) 之后，
    #        本用例会报 (a) 而不是 (d)，断言即失败。
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
ALTER TABLE customer DROP CONSTRAINT uq_tenant_customer_id CASCADE;
""")
    ok, out = run_sql(inj)
    check("I4 摘掉载体唯一约束 → 自证 (d) 必须抓住（且必须是 (d) 而非 (a)）",
          (not ok) and ("自证失败(d)" in out), out)

    # ------------------------------------------------------------------
    # I5 迁移后注入跨租户引用 → 数据库必须拒绝（看真行为，不看定义）
    # ------------------------------------------------------------------
    inj = mig + """
SET LOCAL app.tenant_id = '16000000-0000-0000-0000-00000000000a';
INSERT INTO tenant (id, name, status) VALUES
  ('16000000-0000-0000-0000-00000000000a','I5探针A','active') ON CONFLICT DO NOTHING;
INSERT INTO customer (id, tenant_id, name, status) VALUES
  ('16000000-0000-0000-0000-0000000000c1','16000000-0000-0000-0000-00000000000a','I5探针客户','active');
SET LOCAL app.tenant_id = '16000000-0000-0000-0000-00000000000b';
INSERT INTO band (band_id, tenant_id, customer_id, vendor, bound_at, status) VALUES
  ('16000000-0000-0000-0000-0000000000e2','16000000-0000-0000-0000-00000000000b',
   '16000000-0000-0000-0000-0000000000c1','GTL1',CURRENT_DATE,'active');
"""
    ok, out = run_sql(inj)
    check("I5 迁移后注入跨租户引用 → 必须被数据库拒绝",
          (not ok) and ("band_customer_id_fkey" in out or "违反外键约束" in out), out)

    # ------------------------------------------------------------------
    # C2 同租户引用必须成功（防"一拒了之"）
    # ------------------------------------------------------------------
    ctl = mig + """
SET LOCAL app.tenant_id = '16000000-0000-0000-0000-00000000000a';
INSERT INTO tenant (id, name, status) VALUES
  ('16000000-0000-0000-0000-00000000000a','C2探针A','active') ON CONFLICT DO NOTHING;
INSERT INTO customer (id, tenant_id, name, status) VALUES
  ('16000000-0000-0000-0000-0000000000c1','16000000-0000-0000-0000-00000000000a','C2客户','active');
INSERT INTO band (band_id, tenant_id, customer_id, vendor, bound_at, status) VALUES
  ('16000000-0000-0000-0000-0000000000e2','16000000-0000-0000-0000-00000000000a',
   '16000000-0000-0000-0000-0000000000c1','GTL1',CURRENT_DATE,'active');
SELECT count(*) AS same_tenant_rows FROM band
 WHERE band_id='16000000-0000-0000-0000-0000000000e2';
"""
    ok, out = run_sql(ctl)
    check("C2 同租户引用必须成功（对照，防一拒了之）",
          ok and "same_tenant_rows" in out, out)

    # ------------------------------------------------------------------
    # C3 列序交换的等价形态必须仍然全绿（防判据误伤）
    #     语义：FOREIGN KEY (customer_id, tenant_id) REFERENCES customer(id, tenant_id)
    #     与迁移产生的 (tenant_id, customer_id)→(tenant_id, id) 完全等价
    #     （都要求目标行 tenant_id 与源行相同）。旧判据（字符串 LIKE / conkey[1] 位置）
    #     会把它判为"不是本迁移的产物"⇒ 数出 46 而误报。本用例锁住"判据已改结构判定"。
    # ------------------------------------------------------------------
    inj = inject_before_guard(mig, """
ALTER TABLE band DROP CONSTRAINT band_customer_id_fkey;
ALTER TABLE band ADD CONSTRAINT band_customer_id_fkey
    FOREIGN KEY (customer_id, tenant_id) REFERENCES customer (id, tenant_id);
""")
    ok, out = run_sql(inj)
    check("C3 列序交换的等价形态必须仍然全绿（防判据误伤）",
          ok and "自证通过" in out, out)

    # ------------------------------------------------------------------
    # C5 元门禁：五条自证失败分支的字面量必须都在（防"删掉自证也算通过"）
    # ------------------------------------------------------------------
    need = ["自证失败(d)", "自证失败(a)", "自证失败(b)", "自证失败(c)",
            "自证失败(e)", "自证失败(e2)", "自证失败(e3)"]
    missing = [s for s in need if s not in mig]
    check("C5 五条自证失败分支字面量必须都在迁移文本里",
          not missing, f"缺失: {missing}")

    print()
    passed = sum(1 for _, ok, _ in results if ok)
    print(f"===== 反向验证结果: {passed}/{len(results)} 通过 =====")
    for name, ok, _ in results:
        print(f"  {'[OK]' if ok else '[XX]'} {name}")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())