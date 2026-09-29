"""
S2-1 收尾 · 反向验证（V6 退款域留痕账本 RLS 门禁）

纪律（沿用本仓库 S2-1 的既有教训）：
  1) 每次注入后必须【自证文件内容确实变了】——否则 str.replace 静默未命中，
     脚本会报"门禁没牙齿"，而真相是"根本没注入"。
  2) 恢复必须用 copyfile + os.utime(target, None) 刷新 mtime。
     若用 copy2（保留旧 mtime），恢复后的源文件会显得比注入期间编译出的 .class 更旧，
     Maven 认为"最新" → 不重编译 → 跑的是被污染的 class（本仓库实测踩过这个坑）。
  3) 每个注入都必须让目标门禁【变红】；若仍绿 → 门禁无牙齿，必须修门禁而不是放过。

注入清单（目标 = 让对应的门禁断言变红）：
  RV-V6-1  refund_receipt 去掉 ENABLE ROW LEVEL SECURITY      → RlsV6 元数据断言
  RV-V6-2  refund_receipt 的 pushed_at 等价 CHECK 改为恒真      → RlsV6 等价关系反向验证
  RV-V6-3  refund_receipt 的 receipt_state 枚举放宽（收半角括号）→ RlsV6 契约字面绑定
  RV-V6-4  refund_statement 去掉 FORCE ROW LEVEL SECURITY       → RlsV6 的 FORCE 断言
  RV-V6-5  RlsCoverageGateTest 抹掉 V6 三表登记                 → 覆盖率三方交叉门禁
  RV-V6-6  refund_receipt 增加 updated_at 列                    → RlsV6 append-only 结构断言
"""
import os
import shutil
import subprocess
import sys

SKEL = r"C:/Users/lenovo/WorkBuddy/2026-09-16-10-37-59/deliverables/product-strategy/skeleton"
MIG = os.path.join(SKEL, "dy-app/src/main/resources/db/migration/"
                          "V6__refund_domain_alignment_and_ledgers.sql")
GATE = os.path.join(SKEL, "dy-app/src/test/java/com/diaoyuanyun/dy/app/rls/RlsCoverageGateTest.java")
MVN = r"C:/opt/apache-maven-3.9.9/bin/mvn.cmd"


def read(p):
    with open(p, "r", encoding="utf-8", newline="") as f:
        return f.read()


def write(p, s):
    with open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)


def restore(backup, target):
    """copyfile + 刷新 mtime（绝不用 copy2：保留旧 mtime 会导致 Maven 不重编译）。"""
    shutil.copyfile(backup, target)
    os.utime(target, None)
    # 顺带清掉被注入期间编译/拷贝出的产物，杜绝"跑的是旧 class"
    for sub in ("dy-app/target/classes/db/migration", "dy-app/target/test-classes/com/diaoyuanyun/dy/app/rls"):
        d = os.path.join(SKEL, sub)
        if os.path.isdir(d):
            for f in os.listdir(d):
                if f.startswith("V6") or f.startswith("RlsCoverageGateTest"):
                    try:
                        os.remove(os.path.join(d, f))
                    except OSError:
                        pass


def run_tests(selector):
    env = dict(os.environ)
    env["PATH"] = r"C:/opt/apache-maven-3.9.9/bin;" + env.get("PATH", "")
    proc = subprocess.run(
        [MVN, "-o", "-pl", "dy-app", "test",
         "-Dtest=" + selector, "-DfailIfNoTests=false"],
        cwd=SKEL, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace")
    out = (proc.stdout or "") + (proc.stderr or "")
    green = "BUILD SUCCESS" in out
    return green, out


def first_failure_lines(out, n=6):
    lines = []
    for ln in out.splitlines():
        if "FAIL" in ln or "AssertionError" in ln or "Tests run:" in ln and "Failures: 0, Errors: 0" not in ln:
            lines.append(ln.strip())
    return lines[:n]


INJECTIONS = []

# ---- RV-V6-1: refund_receipt 去掉 ENABLE ROW LEVEL SECURITY ----
def inj1():
    s = read(MIG)
    before = s
    # 只改 refund_receipt 那一段（它在 refund_statement 之后、refund_offline_notice 之前）
    anchor = "ALTER TABLE refund_receipt ENABLE ROW LEVEL SECURITY;"
    s = s.replace(anchor, "-- [RV-V6-1] ENABLE 被移除", 1)
    return s, before


# ---- RV-V6-2: pushed_at 等价 CHECK 改为恒真 ----
def inj2():
    s = read(MIG)
    before = s
    old = """    CONSTRAINT refund_receipt_pushed_at_iff_pushed CHECK (
        (receipt_state =  '已推送' AND pushed_at IS NOT NULL)
     OR (receipt_state <> '已推送' AND pushed_at IS NULL))"""
    new = """    CONSTRAINT refund_receipt_pushed_at_iff_pushed CHECK (true)"""
    s = s.replace(old, new, 1)
    return s, before


# ---- RV-V6-3: receipt_state 枚举放宽，收下半角括号形态 ----
def inj3():
    s = read(MIG)
    before = s
    old = """    receipt_state   VARCHAR(32) NOT NULL CHECK (receipt_state IN (
                        '已推送', '未授权（转线下）', '推送失败')),"""
    new = """    receipt_state   VARCHAR(32) NOT NULL CHECK (receipt_state IN (
                        '已推送', '未授权（转线下）', '推送失败', '未授权(转线下)')),"""
    s = s.replace(old, new, 1)
    return s, before


# ---- RV-V6-4: refund_statement 去掉 FORCE ROW LEVEL SECURITY ----
def inj4():
    s = read(MIG)
    before = s
    anchor = "ALTER TABLE refund_statement FORCE ROW LEVEL SECURITY;"
    s = s.replace(anchor, "-- [RV-V6-4] FORCE 被移除", 1)
    return s, before


# ---- RV-V6-5: 覆盖率门禁登记表里抹掉 V6 三表 ----
def inj5():
    s = read(GATE)
    before = s
    # 一次替换到位：不能留下重复的 band_daily_coverage 键 —— 那会让 Map.ofEntries
    # 在类初始化时抛 IllegalArgumentException，红的原因就变成"写错了 Map"而不是
    # "门禁发现了未登记的租户表"，反向验证的结论随之失真。
    old = """            Map.entry("band_daily_coverage", V5),
            // ---- V6 · S2-1 退款域留痕账本 3 表（2026-09-24）----
            Map.entry("refund_statement", V6),
            Map.entry("refund_receipt", V6),
            Map.entry("refund_offline_notice", V6));"""
    new = """            Map.entry("band_daily_coverage", V5));"""
    s = s.replace(old, new, 1)
    return s, before


# ---- RV-V6-6: refund_receipt 增加 updated_at 列 ----
def inj6():
    s = read(MIG)
    before = s
    old = """    failure_reason  VARCHAR(256),
    operator_id     UUID        REFERENCES staff (staff_id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      VARCHAR(128),"""
    new = """    failure_reason  VARCHAR(256),
    operator_id     UUID        REFERENCES staff (staff_id),
    updated_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by      VARCHAR(128),"""
    s = s.replace(old, new, 1)
    return s, before


INJECTIONS = [
    ("RV-V6-1", "refund_receipt 去掉 ENABLE RLS", MIG, inj1, "RlsV6RefundLedgerIsolationTest"),
    ("RV-V6-2", "pushed_at 等价 CHECK 改恒真", MIG, inj2, "RlsV6RefundLedgerIsolationTest"),
    ("RV-V6-3", "receipt_state 枚举放宽（收半角括号）", MIG, inj3, "RlsV6RefundLedgerIsolationTest"),
    ("RV-V6-4", "refund_statement 去掉 FORCE RLS", MIG, inj4, "RlsV6RefundLedgerIsolationTest"),
    ("RV-V6-5", "覆盖率门禁抹掉 V6 三表登记", GATE, inj5, "RlsCoverageGateTest"),
    ("RV-V6-6", "refund_receipt 增加 updated_at 列", MIG, inj6, "RlsV6RefundLedgerIsolationTest"),
]

results = []
try:
    for rid, desc, target, fn, selector in INJECTIONS:
        backup = target + ".rv-backup"
        shutil.copyfile(target, backup)
        os.utime(backup, None)

        injected, before = fn()
        if injected == before:
            results.append((rid, desc, "INJECTION-FAILED",
                            "替换未命中：文件内容没变，本次结果不可采信"))
            print(f"[{rid}] !! 注入未生效 —— 锚点没匹配上，结果不可采信", flush=True)
            restore(backup, target)
            os.remove(backup)
            continue

        write(target, injected)
        # 自证：落盘后的内容确实与注入前不同
        if read(target) == before:
            results.append((rid, desc, "INJECTION-FAILED", "写盘后内容与注入前相同"))
            restore(backup, target)
            os.remove(backup)
            continue

        green, out = run_tests(selector)
        if green:
            results.append((rid, desc, "NO-TEETH", "注入后仍然 BUILD SUCCESS —— 门禁没抓住"))
            print(f"[{rid}] !! 门禁无牙齿：{desc}", flush=True)
        else:
            results.append((rid, desc, "RED-AS-EXPECTED", "; ".join(first_failure_lines(out))))
            print(f"[{rid}] OK 注入后变红: {desc}", flush=True)

        restore(backup, target)
        os.remove(backup)
finally:
    # 双保险：无论中途怎么退出，都恢复原始文件
    for rid, desc, target, fn, selector in INJECTIONS:
        backup = target + ".rv-backup"
        if os.path.exists(backup):
            restore(backup, target)
            os.remove(backup)

print("\n================ 反向验证汇总 ================")
bad = 0
for rid, desc, status, detail in results:
    mark = "PASS" if status == "RED-AS-EXPECTED" else "FAIL"
    if mark == "FAIL":
        bad += 1
    print(f"{mark}  {rid}  {desc}\n      -> {status}: {detail[:400]}")
print(f"\n合计 {len(results)} 项，无牙齿/未生效 {bad} 项")
sys.exit(0 if bad == 0 else 1)