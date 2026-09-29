"""
S2-2 · 反向验证（代录纪律与挽留 / 审批口径的语义守卫）

纪律（沿用本仓库既有教训，逐条保留）：
  1) 每次注入后必须【自证文件内容确实变了】—— 否则 str.replace 静默未命中，
     脚本会报"门禁没牙齿"，而真相是"根本没注入"。
  2) 恢复必须用 copyfile + os.utime(target, None) 刷新 mtime。
     若用 copy2（保留旧 mtime），恢复后的源文件会显得比注入期间编译出的 .class 更旧，
     Maven 认为"最新" → 不重编译 → 跑的是被污染的 class（本仓库实测踩过这个坑）。
  3) 每个注入都必须让目标测试【变红】；若仍绿 → 守卫无牙齿，必须修守卫而不是放过。

与 S2-1 / V6 的不同：那两轮注入的是 SQL 迁移与门禁登记表，本轮注入的是
【领域策略源码本身】—— 因为 S2-2 的守卫全在域对象里（resolveRequestedAt /
evaluateDelay / requirementOf / outcomeApproval / assertApproverIsNotDeputy /
requireValue），注入口径就是注入策略。

注入清单（目标 = 让 RefundDeputyDisciplineAndRetentionTest 里对应的断言变红）：
  RV-S2-2-1   requested_at 全空时改用 claimed 顶上        → requested_at 归一 · 全空必抛
  RV-S2-2-2   24h 边界由【含 24】改为【不含 24】           → 边界与客户文案同口径
  RV-S2-2-3   去掉负延迟守卫                              → 时间戳写错不得被静默修正
  RV-S2-2-4   去掉『客户自证须附凭据』守卫                 → 无凭据自述不得进可核实集合
  RV-S2-2-5   去掉『有 requested_at 须有来源标注』守卫      → 三字段拆分收益靠此守住
  RV-S2-2-6   缺口 requireValue 改为返回建议值             → 缺口不得被静默填平
  RV-S2-2-7   分段边界缺口补一个建议值 0                    → 『无建议值』须可区分
  RV-S2-2-8   缺口总数 GAP_COUNT 3 → 2                     → 缺口增删须被看见
  RV-S2-2-9   免审批例外扩大到入口 A 的终止                 → 免审批只允许一条路径
  RV-S2-2-10  健康风险判定降到入口 B 之后                   → 安全事件的名义不得被覆盖
  RV-S2-2-11  去掉『豁免挽留却出现挽留结论』守卫            → 不该存在的挽留记录须被拒
  RV-S2-2-12  去掉『代录者不可审批』同人比对               → 动机阀门（自建自批）
  RV-S2-2-13  已归档工单的写操作不再被拒                   → 『归档后只读』须与幂等配套
"""
import os
import shutil
import subprocess
import sys

SKEL = r"C:/Users/lenovo/WorkBuddy/2026-09-16-10-37-59/deliverables/product-strategy/skeleton"
DOMAIN = os.path.join(SKEL, "dy-app/src/main/java/com/diaoyuanyun/dy/app/refund/domain")
REC = os.path.join(DOMAIN, "RefundRecordingPolicy.java")
GAP = os.path.join(DOMAIN, "RefundConfigGap.java")
RET = os.path.join(DOMAIN, "RetentionPolicy.java")
MVN = r"C:/opt/apache-maven-3.9.9/bin/mvn.cmd"
SELECTOR = "RefundDeputyDisciplineAndRetentionTest"


def read(p):
    with open(p, "r", encoding="utf-8", newline="") as f:
        return f.read()


def write(p, s):
    with open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)


def restore(p, original):
    """把原始内容写回 + 刷新 mtime（绝不用 copy2：保留旧 mtime 会导致 Maven 不重编译）。

    🛑 两条"不做什么"，都是本机实测踩出来的：
      1) 不在 target/ 下删 .class —— 删除会被本机安全删除守卫拦下，脚本中途退出。
         改为只刷新源文件 mtime：源比 class 新时 Maven 增量编译必然重编译，
         足以杜绝"跑的是旧 class"。
      2) 不落备份文件 —— 备份同样要删，同样会被拦。
         原始内容直接【持在内存里】（本轮的注入目标都是小文件），于是全程零删除。
    """
    write(p, original)
    os.utime(p, None)


def run_tests():
    env = dict(os.environ)
    env["PATH"] = r"C:/opt/apache-maven-3.9.9/bin;" + env.get("PATH", "")
    proc = subprocess.run(
        [MVN, "-o", "-pl", "dy-app", "test",
         "-Dtest=" + SELECTOR, "-DfailIfNoTests=false"],
        cwd=SKEL, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace")
    out = (proc.stdout or "") + (proc.stderr or "")
    green = "BUILD SUCCESS" in out
    return green, out


def first_failure_lines(out, n=6):
    lines = []
    for ln in out.splitlines():
        if "ERROR]   RefundDeputy" in ln:
            lines.append(ln.strip())
    return lines[:n] if lines else ["（未解析到具体失败项，见构建输出）"]


# ----------------------------------------------------------------------
# 注入器
# ----------------------------------------------------------------------

def inj1():
    """requested_at 全空时改用 claimed 顶上。"""
    s = read(REC)
    before = s
    old = """            if (meridianAccepted == null) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "requested_at 无法归一：既无可核实来源（客户自证 / 调理师转交），也无受理时间。"
                                + "P0-19 C1-2：24h 一律从 requested_at 起算 —— "
                                + "起点算不出来就不得接单，绝不用 claimed（客户主张）顶上（它永不进 24h 计时）");
            }"""
    new = """            if (meridianAccepted == null) {
                // [RV-S2-2-1] 注入：全空时改用 claimed 顶上（应被门禁抓住）
                return new RequestedAtResolution(claimed, RequestedAtSource.CUSTOMER_PROVEN,
                        claimed, evidenceRef);
            }"""
    s = s.replace(old, new, 1)
    return s, before


def inj2():
    """24h 边界由【含 24】改为【不含 24】。"""
    s = read(REC)
    before = s
    old = "        boolean within = hours <= recordWindowHours();"
    new = "        boolean within = hours < recordWindowHours();   // [RV-S2-2-2] 注入：边界改为不含 24"
    s = s.replace(old, new, 1)
    return s, before


def inj3():
    """去掉负延迟守卫。"""
    s = read(REC)
    before = s
    old = """        double hours = millis / 3_600_000.0;
        if (hours < 0) {"""
    new = """        double hours = millis / 3_600_000.0;
        if (hours < -1_000_000) {   // [RV-S2-2-3] 注入：负延迟守卫失效"""
    s = s.replace(old, new, 1)
    return s, before


def inj4():
    """去掉『客户自证须附凭据』守卫。

    🛑 必须真正让守卫失效，不能只加一个"读 System 属性"的门控 ——
    那会被属性未设置短路回原行为，注入形同未发生，脚本会误报"守卫无牙齿"。
    """
    s = read(REC)
    before = s
    old = "        if (customerProven != null && isBlank(evidenceRef)) {"
    new = ("        if (false && customerProven != null && isBlank(evidenceRef)) {"
           "   // [RV-S2-2-4] 注入：凭据守卫失效")
    s = s.replace(old, new, 1)
    return s, before


def inj5():
    """去掉『有 requested_at 须有来源标注』守卫（真正失效，同理不可用属性门控）。"""
    s = read(REC)
    before = s
    old = "        if (hasRequested && !hasSource) {"
    new = ("        if (false && hasRequested && !hasSource) {"
           "   // [RV-S2-2-5] 注入：来源标注守卫失效")
    s = s.replace(old, new, 1)
    return s, before


def inj6():
    """缺口 requireValue 改为返回建议值（缺口被静默填平）。

    🛑 必须让 throw 真的被绕过：注入后凡 advisoryValue 非空的缺口都会返回一个数，
    只有 PRD 未给建议值的那条（#29 分段边界）仍会抛 —— 门禁应抓住这个差别。
    """
    s = read(GAP)
    before = s
    old = """    public int requireValue() {
        throw new com.diaoyuanyun.dy.common.exception.BizException("""
    new = """    public int requireValue() {
        // [RV-S2-2-6] 注入：缺口回落成建议值（应被门禁抓住）
        if (advisoryValue != null) {
            return advisoryValue;
        }
        throw new com.diaoyuanyun.dy.common.exception.BizException("""
    s = s.replace(old, new, 1)
    return s, before


def inj7():
    """分段边界缺口补一个建议值 0（掩盖『无建议值』这一事实）。"""
    s = read(GAP)
    before = s
    old = """            "P0-14 第二层 + config #29（让步金额分段阈值上收）",
            null,"""
    new = """            "P0-14 第二层 + config #29（让步金额分段阈值上收）",
            0,   // [RV-S2-2-7] 注入：给一个本不存在的建议值"""
    s = s.replace(old, new, 1)
    return s, before


def inj8():
    """缺口总数 GAP_COUNT 3 → 2。"""
    s = read(GAP)
    before = s
    old = "    public static final int GAP_COUNT = 3;"
    new = "    public static final int GAP_COUNT = 2;   // [RV-S2-2-8] 注入：缺口数量与集合脱钩"
    s = s.replace(old, new, 1)
    return s, before


def inj9():
    """免审批例外扩大到入口 A 的终止。"""
    s = read(RET)
    before = s
    old = """            case TERMINATE -> entry.isStoreDeputyEntry()
                    ? ApprovalTarget.ESCALATE_HEADQUARTERS
                    : ApprovalTarget.FULL_EXEMPT_FIRST_CYCLE;"""
    new = """            case TERMINATE -> ApprovalTarget.FULL_EXEMPT_FIRST_CYCLE;   // [RV-S2-2-9] 注入：例外扩大"""
    s = s.replace(old, new, 1)
    return s, before


def inj10():
    """健康风险判定降到入口 B 之后。"""
    s = read(RET)
    before = s
    old = """        if (reason != null && reason.isHealthRiskEvent()) {
            return RetentionRequirement.EXEMPT_HEALTH_RISK;
        }
        if (!entry.isStoreDeputyEntry()) {
            return RetentionRequirement.EXEMPT_FIRST_CYCLE;
        }
        return RetentionRequirement.REQUIRED;"""
    new = """        // [RV-S2-2-10] 注入：入口 B 判定排在健康风险之前
        if (!entry.isStoreDeputyEntry()) {
            return RetentionRequirement.EXEMPT_FIRST_CYCLE;
        }
        if (reason != null && reason.isHealthRiskEvent()) {
            return RetentionRequirement.EXEMPT_HEALTH_RISK;
        }
        return RetentionRequirement.REQUIRED;"""
    s = s.replace(old, new, 1)
    return s, before


def inj11():
    """去掉『豁免挽留却出现挽留结论』守卫。"""
    s = read(RET)
    before = s
    old = "        } else if (result != null) {"
    new = ("        } else if (false && result != null) {"
           "   // [RV-S2-2-11] 注入：豁免守卫失效")
    s = s.replace(old, new, 1)
    return s, before


def inj12():
    """去掉『代录者不可审批』同人比对。"""
    s = read(RET)
    before = s
    old = "        if (deputyOperatorId.trim().equals(approverId.trim())) {"
    new = ("        if (false && deputyOperatorId.trim().equals(approverId.trim())) {"
           "   // [RV-S2-2-12] 注入：动机阀门失效")
    s = s.replace(old, new, 1)
    return s, before


def inj13():
    """已归档工单的写操作不再被拒。"""
    s = read(RET)
    before = s
    old = "        if (current.isClosed()) {"
    new = ("        if (false && current.isClosed()) {"
           "   // [RV-S2-2-13] 注入：归档后只读失效")
    s = s.replace(old, new, 1)
    return s, before


INJECTIONS = [
    ("RV-S2-2-1", "requested_at 全空时改用 claimed 顶上", REC, inj1),
    ("RV-S2-2-2", "24h 边界改为不含 24", REC, inj2),
    ("RV-S2-2-3", "去掉负延迟守卫", REC, inj3),
    ("RV-S2-2-4", "去掉『客户自证须附凭据』守卫", REC, inj4),
    ("RV-S2-2-5", "去掉『有 requested_at 须有来源标注』守卫", REC, inj5),
    ("RV-S2-2-6", "缺口 requireValue 改为返回建议值", GAP, inj6),
    ("RV-S2-2-7", "分段边界缺口补一个建议值 0", GAP, inj7),
    ("RV-S2-2-8", "缺口总数 GAP_COUNT 3 → 2", GAP, inj8),
    ("RV-S2-2-9", "免审批例外扩大到入口 A 的终止", RET, inj9),
    ("RV-S2-2-10", "健康风险判定降到入口 B 之后", RET, inj10),
    ("RV-S2-2-11", "去掉『豁免挽留却出现挽留结论』守卫", RET, inj11),
    ("RV-S2-2-12", "去掉『代录者不可审批』同人比对", RET, inj12),
    ("RV-S2-2-13", "已归档工单的写操作不再被拒", RET, inj13),
]

results = []
ORIGINALS = {}
try:
    # 先把三个注入目标的原始内容读进内存（全程零备份文件、零删除）
    for _rid, _desc, _target, _fn in INJECTIONS:
        ORIGINALS.setdefault(_target, read(_target))

    for rid, desc, target, fn in INJECTIONS:
        # 每次注入前先确保源文件处于原始状态（防止上一轮异常残留）
        restore(target, ORIGINALS[target])

        injected, before = fn()
        if injected == before:
            results.append((rid, desc, "INJECTION-FAILED",
                            "替换未命中：文件内容没变，本次结果不可采信"))
            print(f"[{rid}] !! 注入未生效 —— 锚点没匹配上，结果不可采信", flush=True)
            restore(target, ORIGINALS[target])
            continue

        write(target, injected)
        # 自证：落盘后的内容确实与注入前不同
        if read(target) == before:
            results.append((rid, desc, "INJECTION-FAILED", "写盘后内容与注入前相同"))
            restore(target, ORIGINALS[target])
            continue

        green, out = run_tests()
        if green:
            results.append((rid, desc, "NO-TEETH", "注入后仍然 BUILD SUCCESS —— 守卫没抓住"))
            print(f"[{rid}] !! 守卫无牙齿：{desc}", flush=True)
        else:
            results.append((rid, desc, "RED-AS-EXPECTED", "; ".join(first_failure_lines(out))))
            print(f"[{rid}] OK 注入后变红: {desc}", flush=True)

        restore(target, ORIGINALS[target])
finally:
    # 双保险：无论中途怎么退出，都把三个文件恢复为原始内容
    for p, original in ORIGINALS.items():
        restore(p, original)

print("\n================ 反向验证汇总（S2-2）================")
bad = 0
for rid, desc, status, detail in results:
    mark = "PASS" if status == "RED-AS-EXPECTED" else "FAIL"
    if mark == "FAIL":
        bad += 1
    print(f"{mark}  {rid}  {desc}\n      -> {status}: {detail[:400]}")
print(f"\n合计 {len(results)} 项，无牙齿/未生效 {bad} 项")
sys.exit(0 if bad == 0 else 1)