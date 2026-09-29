#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
反向验证脚本 —— 判定链落库（S2-7）。

原理：好的测试不是因为"现在绿"而可信，而是因为"注入对应的错误时它会红"。
本脚本对每一处关键防线做一次【注入 → 跑测试 → 断言必须红在预期的那条 → 还原】。

不注入的反向验证（"把代码改对，看测试绿不绿"）证明不了任何事：
它对一个恒绿的测试信心完全相同。
"""
import io
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
# resources → test → src → dy-app → skeleton 共四层，别数错一层
# （数错时 APP 会变成 .../dy-app/src/dy-app，报一个"源码不存在"的错，
#  那个错看起来像"文件被删了"，实际只是这里少了 ..）
SKEL = os.path.normpath(os.path.join(ROOT, "..", "..", "..", ".."))
APP = os.path.join(SKEL, "dy-app")
TEST_DIR = os.path.join(APP, "src", "test", "java", "com", "diaoyuanyun", "dy", "app", "derived")
MAIN_DERIVED = os.path.join(APP, "src", "main", "java", "com", "diaoyuanyun", "dy", "app", "derived")
MVN = r"C:\opt\apache-maven-3.9.9\bin\mvn.cmd"

# 🛑 必须显式给出 Maven 的 PATH：本脚本不继承交互 shell 的 export PATH=...
#    否则 mvn.cmd 找不到自己依赖的 java/javac，会以 "不是有效的 Win32 应用" 或
#    "JAVA_HOME not found" 的形式失败 —— 而那种失败看起来像"注入把编译搞坏了"。
MVN_ENV = dict(os.environ)
MVN_ENV["PATH"] = r"C:\opt\apache-maven-3.9.9\bin;" + MVN_ENV.get("PATH", "")
MVN_ENV.setdefault("JAVA_HOME", os.environ.get("JAVA_HOME", ""))


def _decode(blob):
    """Maven/JDK 在中文 Windows 上的输出是 **GBK**（本机代码页），不是 UTF-8。

    🛑 这是本轮反向验证最贵的一个坑，形态与本项目已登记的缺陷 #23 同源
    （"门禁自身崩溃被读成成功检出"）—— 只是方向相反：
    **测试明明红了且红在对的方法上，却被读成"没抓住"**。
    用 errors="replace" 解码永远不会抛错，所以它不会以任何形式报警 ——
    它只是让每条中文锚点默默地匹配不上。
    解 GBK 优先，失败再退 UTF-8（英文输出两种都对）。
    """
    for enc in ("gbk", "utf-8"):
        try:
            return blob.decode(enc)
        except UnicodeDecodeError:
            continue
    return blob.decode("utf-8", "replace")


def run_tests(test_filter):
    """跑指定测试，返回 (成功?, 输出)。

    🛑 这里【不能】加 -q：-q 只放行 WARN/ERROR，
    而断言的自定义消息（"D4 必须排在效果与依从分支"之类）是 surefire 在
    测试失败时随堆栈一起打印的，属于被抑制的那一档。
    于是 expect_in_output 会永远匹配不上，每条注入都被误判成"红错了地方"。
    加 -Dsurefire.useFile=false 是让失败详情走控制台而不是只写进 txt 报告。
    """
    cmd = [MVN, "-o", "surefire:test", "-Dsurefire.useFile=false",
           "-Dtest=" + test_filter, "-Dsurefire.failIfNoSpecifiedTests=false"]
    p = subprocess.run(cmd, cwd=APP, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, shell=False, env=MVN_ENV)
    out = _decode(p.stdout)
    ok = ("Tests run:" in out and "Failures: 0" in out and "Errors: 0" in out
          and "BUILD FAILURE" not in out and "BUILD SUCCESS" in out)
    return ok, out


def compile_main():
    cmd = [MVN, "-o", "-q", "test-compile"]
    p = subprocess.run(cmd, cwd=APP, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, shell=False, env=MVN_ENV)
    return p.returncode == 0, _decode(p.stdout)


# 🛑 newline="" 是【必须】的，不是风格偏好：
#    文本模式的默认行为在 Windows 上会把写入的 "\n" 翻成 "\r\n"。
#    于是"还原"这一步会把一个纯 LF 的源文件永久改成 CRLF ——
#    脚本自己成了工作区污染源，而注入验证的全部价值就在于"还原后与注入前逐字节相同"。
def read(path):
    return io.open(path, encoding="utf-8", newline="").read()


def write(path, s):
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(s)


def inject_and_check(name, path, old, new, test_filter, expect_in_output=None, also=None):
    """注入一处改动，跑测试，要求【红】且报错文本含 expect_in_output。

    :param also: 额外的注入点 ``[(path, old, new), ...]``。
        🛑 有些防线**必须同时改两处才能编译通过**，只改一处会得到"编译失败" ——
        而"注入后编译失败"不是有效的反向验证（它证明的是 Java 的语义，
        不是测试有牙齿）。典型：给一个接口加方法而不给实现类加实现。
    """
    edits = [(path, old, new)] + list(also or [])
    backups = {}
    for pth, _o, _n in edits:
        if pth not in backups:
            backups[pth] = read(pth)
    for pth, o, _n in edits:
        if o not in backups[pth]:
            print("  [SKIP] %s —— 注入锚点未找到（源码已变？）: %r" % (name, o[:60]))
            return None
    try:
        staged = dict(backups)
        for pth, o, n in edits:
            if o not in staged[pth]:
                print("  [SKIP] %s —— 同一文件上的第 2 处锚点已被前一处替换覆盖" % name)
                return None
            staged[pth] = staged[pth].replace(o, n, 1)
        for pth, text in staged.items():
            write(pth, text)

        ok, out = compile_main()
        if not ok:
            print("  [ !! ] %s —— 注入后【编译失败】，这不是有效的反向验证" % name)
            print("         " + "\n         ".join(out.strip().splitlines()[-4:]))
            return False
        ok, out = run_tests(test_filter)
        if ok:
            print("  [FAIL] %s —— 注入后测试【仍然全绿】，说明这条防线没有测试守着" % name)
            return False
        # 断言红在预期那条
        if expect_in_output:
            if expect_in_output in out:
                print("  [ OK ] %s —— 红了，且命中预期断言: %s" % (name, expect_in_output))
                return True
            print("  [ !! ] %s —— 红了，但报错里没找到预期的锚点 %r" % (name, expect_in_output))
            # 打印失败的测试名，便于判断是不是"红错了地方"
            for line in out.splitlines():
                if "FAILURE!" in line or "AssertionFailedError" in line:
                    print("         " + line.strip()[:200])
            return False
        print("  [ OK ] %s —— 红了（未指定预期锚点）" % name)
        return True
    finally:
        for pth, text in backups.items():
            write(pth, text)


def main():
    checks = []

    routing = os.path.join(TEST_DIR, "VerdictBranchRoutingTest.java")
    persistence = os.path.join(TEST_DIR, "VerdictPersistenceBoundaryTest.java")
    controller = os.path.join(TEST_DIR, "VerdictControllerContractTest.java")

    branch_engine = os.path.join(MAIN_DERIVED, "domain", "VerdictBranchEngine.java")
    disposition = os.path.join(MAIN_DERIVED, "domain", "Disposition.java")
    ledger = os.path.join(MAIN_DERIVED, "repository", "VerdictLedger.java")
    ctrl = os.path.join(MAIN_DERIVED, "controller", "VerdictController.java")
    port = os.path.join(MAIN_DERIVED, "domain", "VerdictPort.java")
    vb = os.path.join(MAIN_DERIVED, "domain", "VerdictBranch.java")
    row = os.path.join(MAIN_DERIVED, "domain", "VerdictRow.java")

    # 🛑 快照：脚本被中断（Ctrl-C / 超时 / 异常）也不能把注入留在工作区。
    #    每条 inject_and_check 自己也有 finally 还原，但那是"逐条"的保险；
    #    这里是"全局"的保险 —— 两者都需要，因为前一层的 finally 在进程被
    #    强杀时不会执行。
    touched = [branch_engine, disposition, ledger, ctrl, port, vb, row]
    snapshot = {p: open(p, "rb").read() for p in touched}

    print("=" * 78)
    print("反向验证 · 判定链落库（S2-7）")
    print("=" * 78)

    try:
        checks = _run_all_checks(
            checks, routing, persistence, controller,
            branch_engine, disposition, ledger, ctrl, port, vb, row)
    finally:
        for p, blob in snapshot.items():
            open(p, "wb").write(blob)
        print("\n[还原] 已把 %d 个被触碰的源文件写回快照" % len(touched))
        dirty = [p for p, blob in snapshot.items() if open(p, "rb").read() != blob]
        if dirty:
            print("[还原] 🛑 还原后仍有差异（这是脚本自身的 bug）: %s" % dirty)
        else:
            print("[还原] 逐字节校验通过：工作区与脚本运行前完全一致")

    # ---- 汇总 ----
    print("\n" + "=" * 78)
    done = [c for c in checks if c is not None]
    passed = [c for c in done if c]
    print("反向验证结果: %d/%d 通过（跳过 %d）"
          % (len(passed), len(done), len(checks) - len(done)))
    if len(passed) == len(done) and done:
        print("[REVERSE-VERIFICATION] PASS —— 每处注入都被预期的断言抓住")
        return 0
    print("[REVERSE-VERIFICATION] FAIL —— 有注入未被抓住，防线没有测试守着")
    return 1


def _run_all_checks(checks, routing, persistence, controller,
                    branch_engine, disposition, ledger, ctrl, port, vb, row):

    # ---- RV-1：把 D4 降级为「排在效果分支之后」→ 新发信号被 D1 吞掉 ----
    print("\nRV-1  D4 顺序（安全优先）：把 D4 移到 D1 之后")
    checks.append(inject_and_check(
        "RV-1 D4 排在效果分支之后",
        branch_engine,
        "        // ── D4：风险标签命中即全面评估（安全优先，排在效果分支之前）──\n"
        "        if (in.riskFlag().triggersFullAssessment()) {",
        "        // [INJECTED] D4 被挪到后面\n"
        "        if (false && in.riskFlag().triggersFullAssessment()) {",
        "VerdictBranchRoutingTest",
        "D4 必须排在效果与依从分支"))

    # ---- RV-2：让自动路径可以产出「退款终止」→ P0-14 被破 ----
    print("\nRV-2  P0-14：让 resolveDisposition 对最不利组合返回「退款终止」")
    checks.append(inject_and_check(
        "RV-2 自动路径产出退款终止",
        branch_engine,
        "                    Disposition.ADJUST_AND_CONTINUE;",
        "                    Disposition.REFUND_TERMINATE;",
        "VerdictBranchRoutingTest",
        "产出了『退款终止』"))

    # ---- RV-3：把 disposition 的 systemMayAutoRaise 全开 → 自动集合变大 ----
    print("\nRV-3  Disposition.systemMayAutoRaise：让「退款终止」也可自动提出")
    checks.append(inject_and_check(
        "RV-3 退款终止被标为可自动提出",
        disposition,
        "        return this != REFUND_TERMINATE;",
        "        return true;   // [INJECTED] 让『退款终止』也可自动提出",
        "VerdictBranchRoutingTest",
        "自动可提出的处置集合必须"))

    # ---- RV-4：端口加一个 update 方法 → append-only 门禁 ----
    print("\nRV-4  端口 append-only：加一个 updateBranch（**接口 + 实现同改**）")
    # 🛑 只改接口 ⇒ 实现类缺实现 ⇒ 编译失败 ⇒ 拿到的是"编译失败"而不是"测试变红"，
    #    那证明的是 Java 的语义，不是测试有牙齿。故必须两处同改。
    checks.append(inject_and_check(
        "RV-4 端口出现 update 方法",
        port,
        "    List<VerdictRow> findVerdictsByCycle(String tenantId, UUID cycleId);",
        "    List<VerdictRow> findVerdictsByCycle(String tenantId, UUID cycleId);\n"
        "    void updateBranch(String tenantId, UUID verdictId, String branch);",
        "VerdictPersistenceBoundaryTest",
        "就地改写类方法",
        also=[(
            ledger,
            "    private static final Pattern UUID_PATTERN =",
            "    @Override\n"
            "    public void updateBranch(String tenantId, UUID verdictId, String branch) {\n"
            "        // [INJECTED] 一条就地改写路径\n"
            "    }\n\n"
            "    private static final Pattern UUID_PATTERN =",
        )]))

    # ---- RV-5：仓储加一条 UPDATE SQL → SQL 字面量门禁 ----
    print("\nRV-5  仓储 append-only：加一条 UPDATE 语句")
    checks.append(inject_and_check(
        "RV-5 仓储出现 UPDATE",
        ledger,
        "    private static final String SELECT_CYCLE_SQL =",
        "    private static final String UPDATE_BRANCH_SQL =\n"
        "            \"UPDATE verdict SET branch = ? WHERE verdict_id = ?::uuid\";\n\n"
        "    private static final String SELECT_CYCLE_SQL =",
        "VerdictPersistenceBoundaryTest",
        "改写字面量"))

    # ---- RV-6：把 visible_to_customer 的显式 FALSE 绑成 TRUE ----
    print("\nRV-6  visible_to_customer：把显式常量改成 TRUE")
    checks.append(inject_and_check(
        "RV-6 visible_to_customer = TRUE",
        ledger,
        " ?::uuid, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, FALSE, ?)\";",
        " ?::uuid, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, TRUE, ?)\";",
        "VerdictPersistenceBoundaryTest",
        "显式绑定的值必须是常量 FALSE"))

    # ---- RV-7：把 INSERT 列清单加上 updated_at ----
    print("\nRV-7  写路径不得产生 updated_at")
    checks.append(inject_and_check(
        "RV-7 INSERT 列清单含 updated_at",
        ledger,
        " threshold_version, band_trend_note, created_at, created_by) \"",
        " threshold_version, band_trend_note, created_at, updated_at, created_by) \"",
        "VerdictPersistenceBoundaryTest",
        "的 INSERT 列清单里含 updated_at"))

    # ---- RV-8：把 @StaffOnly 贴到类级（F1 被连带收紧）----
    print("\nRV-8  @StaffOnly 贴类级 → F1 被超出契约地收紧")
    checks.append(inject_and_check(
        "RV-8 @StaffOnly 贴到类级",
        ctrl,
        "@RestController\n@RequestMapping(\"/api/v1\")\npublic class VerdictController {",
        "@RestController\n@RequestMapping(\"/api/v1\")\n"
        "@com.diaoyuanyun.dy.security.visibility.StaffOnly(clientDeniedFields = {\"branch\"})\n"
        "public class VerdictController {",
        "VerdictControllerContractTest",
        "不得有【类级】@StaffOnly"))

    # ---- RV-9：给 F1 也贴上 @StaffOnly ----
    print("\nRV-9  F1 被贴上 @StaffOnly")
    checks.append(inject_and_check(
        "RV-9 F1 被贴 @StaffOnly",
        ctrl,
        "    @Idempotent\n"
        "    @RequirePermission(\"verdict:write\")\n"
        "    @PostMapping(\"/cycle-assessments/{id}/verdicts\")",
        "    @Idempotent\n"
        "    @com.diaoyuanyun.dy.security.visibility.StaffOnly(clientDeniedFields = {\"branch\"})\n"
        "    @RequirePermission(\"verdict:write\")\n"
        "    @PostMapping(\"/cycle-assessments/{id}/verdicts\")",
        "VerdictControllerContractTest",
        "F1（createVerdict）不得贴 @StaffOnly"))

    # ---- RV-10：把 F1 的 @Idempotent 去掉 ----
    print("\nRV-10  F1 去幂等")
    checks.append(inject_and_check(
        "RV-10 F1 无 @Idempotent",
        ctrl,
        "    @Idempotent\n"
        "    @RequirePermission(\"verdict:write\")\n"
        "    @PostMapping(\"/cycle-assessments/{id}/verdicts\")",
        "    @RequirePermission(\"verdict:write\")\n"
        "    @PostMapping(\"/cycle-assessments/{id}/verdicts\")",
        "VerdictControllerContractTest",
        "F1 必须上 @Idempotent"))

    # ---- RV-11：把 risk_flag 的读回解析改成回落 NONE ----
    #     这一条正是"反向验证逼出了新测试"的实证：在 veredict_readback_never_falls_back_to_none
    #     存在之前，把这个空值分支改成 RiskFlag.NONE，全部判定链测试【仍然全绿】。
    print("\nRV-11  risk_flag fail-closed：读回回落成 NONE")
    checks.append(inject_and_check(
        "RV-11 risk_flag 回落 NONE",
        ledger,
        "    public static RiskFlag parseRiskOrNull(String raw) {\n"
        "        return (raw == null || raw.isBlank()) ? null : RiskFlag.parse(raw);\n"
        "    }",
        "    public static RiskFlag parseRiskOrNull(String raw) {\n"
        "        return (raw == null || raw.isBlank()) ? RiskFlag.NONE : RiskFlag.parse(raw);\n"
        "    }",
        "VerdictPersistenceBoundaryTest",
        "必须原样返回 null"))

    # ---- RV-12：读回侧把未知字面静默回落（而不是抛）----
    print("\nRV-12  读回侧未知 risk_flag 静默回落 NONE")
    checks.append(inject_and_check(
        "RV-12 risk_flag 未知字面不抛只回落",
        ledger,
        "        return (raw == null || raw.isBlank()) ? null : RiskFlag.parse(raw);",
        "        if (raw == null || raw.isBlank()) { return null; }\n"
        "        try { return RiskFlag.parse(raw); } catch (RuntimeException e) { return RiskFlag.NONE; }",
        "VerdictPersistenceBoundaryTest",
        "risk_flag_readback_never_falls_back_to_none"))

    # ---- RV-13：入站侧把未传的 risk_flag 擦成"无" ----
    print("\nRV-13  入站侧 risk_flag 未传被擦成 NONE")
    checks.append(inject_and_check(
        "RV-13 入站 risk_flag 擦成 NONE",
        ctrl,
        "    private static RiskFlag parseRiskOrNull(String raw) {\n"
        "        return (raw == null || raw.isBlank()) ? null : RiskFlag.parse(raw);\n"
        "    }",
        "    private static RiskFlag parseRiskOrNull(String raw) {\n"
        "        return (raw == null || raw.isBlank()) ? RiskFlag.NONE : RiskFlag.parse(raw);\n"
        "    }",
        "VerdictPersistenceBoundaryTest",
        "直接使 D4 该触发而不触发"))

    # ---- RV-14：VerdictRow 放宽「人工复核 + 触发标签」的矛盾拦截 ----
    print("\nRV-14  VerdictRow 放宽矛盾组合拦截")
    checks.append(inject_and_check(
        "RV-14 VerdictRow 不拦矛盾组合",
        row,
        "        if (branch == VerdictBranch.HUMAN_REVIEW && riskFlag != null\n"
        "                && riskFlag.triggersFullAssessment()) {",
        "        if (false && branch == VerdictBranch.HUMAN_REVIEW && riskFlag != null\n"
        "                && riskFlag.triggersFullAssessment()) {",
        "VerdictPersistenceBoundaryTest",
        "verdict_row_rejects_human_review_with_triggering_risk_flag"))

    # ---- RV-15：出口组合改回「可压平为单一 enum」（允许空事实）----
    print("\nRV-15  组合出口允许缺事实（压平为单一 enum）")
    checks.append(inject_and_check(
        "RV-15 组合出口允许空事实",
        branch_engine,
        "    public Disposition resolveDisposition(EffectVerdict effect, AdherenceState adherence, RiskFlag risk) {\n"
        "        if (effect == null || adherence == null || risk == null) {",
        "    public Disposition resolveDisposition(EffectVerdict effect, AdherenceState adherence, RiskFlag risk) {\n"
        "        if (false) {",
        "VerdictBranchRoutingTest",
        "缺 effect 就不是组合"))

    # ---- RV-16：VerdictBranch.parse 把未知字面静默回落 HUMAN_REVIEW ----
    #     🛑 注入点【不能】放在方法体开头（那样会连"合法字面"也变成 HUMAN_REVIEW，
    #     于是 round-trip 断言先红，与 RV-9 撞成同一个红点，掩盖本条真正要证的语义）。
    #     正确注入：把最后的 orElseThrow 换成 orElse(HUMAN_REVIEW) ——
    #     即"合法字面照样通过，只有未知字面被静默改写成挂起"。
    print("\nRV-16  VerdictBranch.parse 把未知字面静默回落 HUMAN_REVIEW")
    checks.append(inject_and_check(
        "RV-16 parse 未知字面回落 HUMAN_REVIEW",
        vb,
        "        return hit.orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,",
        "        if (hit.isEmpty()) { return HUMAN_REVIEW; }\n"
        "        return hit.orElseThrow(() -> new BizException(ErrorCode.VALIDATION_FAILED,",
        "VerdictBranchRoutingTest",
        None))

    return checks


if __name__ == "__main__":
    sys.exit(main())