#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
反向验证脚本 —— threshold_version 溯源回放闭环（S2-8 / ADR-11）。

原理：好的测试不是因为"现在绿"而可信，而是因为"注入对应的错误时它会红"。
本脚本对 S2-8 的每一处关键防线做一次【注入 → 跑测试 → 断言必须红在预期的那条 → 还原】。

不注入的反向验证（"把代码改对，看测试绿不绿"）证明不了任何事：
它对一个恒绿的测试信心完全相同。

🛑 S2-8 的防线与 S2-7 有一个本质差别：S2-7 守的是"不许某件事发生"
（不可就地覆盖、不可回落默认值），断言形态是"扫描源码里没有 X"。
S2-8 守的是"某件事必须真的做了"——版本号必须由口径算出、回放必须真的重算、
漂移必须真的先于重算。这类断言更容易写成恒真的空壳（例如"回放器返回了一个结论"），
故本脚本的目的正是证伪那些空壳。
"""
import io
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
# resources → test → src → dy-app → skeleton 共四层，别数错一层
SKEL = os.path.normpath(os.path.join(ROOT, "..", "..", "..", ".."))
APP = os.path.join(SKEL, "dy-app")
TEST_DIR = os.path.join(APP, "src", "test", "java", "com", "diaoyuanyun", "dy", "app", "derived")
MAIN_DERIVED = os.path.join(APP, "src", "main", "java", "com", "diaoyuanyun", "dy", "app", "derived")
MVN = r"C:\opt\apache-maven-3.9.9\bin\mvn.cmd"

# 🛑 必须显式给出 Maven 的 PATH：本脚本不继承交互 shell 的 export PATH=...
MVN_ENV = dict(os.environ)
MVN_ENV["PATH"] = r"C:\opt\apache-maven-3.9.9\bin;" + MVN_ENV.get("PATH", "")
MVN_ENV.setdefault("JAVA_HOME", os.environ.get("JAVA_HOME", ""))


def _decode(blob):
    """Maven/JDK 在中文 Windows 上的输出是 **GBK**（本机代码页），不是 UTF-8。

    🛑 这个坑的形态是"测试明明红了且红在对的方法上，却被读成没抓住"：
    用 errors="replace" 解码永远不会抛错，所以它不会以任何形式报警 ——
    它只是让每条中文锚点默默地匹配不上。解 GBK 优先，失败再退 UTF-8。
    """
    for enc in ("gbk", "utf-8"):
        try:
            return blob.decode(enc)
        except UnicodeDecodeError:
            continue
    return blob.decode("utf-8", "replace")


def run_tests(test_filter):
    """跑指定测试，返回 (成功?, 输出)。

    🛑 这里【不能】加 -q：-q 只放行 WARN/ERROR，而断言的自定义消息
    （"漂移先于重算"之类）是 surefire 在测试失败时随堆栈一起打印的，
    属于被抑制的那一档。加 -Dsurefire.useFile=false 让失败详情走控制台。
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
#    文本模式的默认行为在 Windows 上会把写入的 "\n" 翻成 "\r\n"，
#    于是"还原"会把一个纯 LF 的源文件永久改成 CRLF —— 脚本自己成了污染源，
#    而注入验证的全部价值就在于"还原后与注入前逐字节相同"。
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
        不是测试有牙齿）。
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
        if expect_in_output:
            if expect_in_output in out:
                print("  [ OK ] %s —— 红了，且命中预期断言: %s" % (name, expect_in_output))
                return True
            print("  [ !! ] %s —— 红了，但报错里没找到预期的锚点 %r" % (name, expect_in_output))
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
    fingerprint_test = "ThresholdVersionFingerprintTest"
    replay_test = "ThresholdVersionReplayTest"
    wiring_test = "ThresholdVersionPersistenceWiringTest"
    both_tests = fingerprint_test + "," + replay_test + "," + wiring_test

    fingerprint = os.path.join(MAIN_DERIVED, "domain", "ThresholdVersionFingerprint.java")
    replay = os.path.join(MAIN_DERIVED, "service", "ThresholdVersionReplay.java")
    service = os.path.join(MAIN_DERIVED, "service", "VerdictService.java")
    # 🛑 值对象在 S2-8 收尾时从 service 类里拆出来，住进 domain
    #    （ArchitectureBoundaryTest 的 R4/DIP 禁止 record 依赖 service 包 ——
    #     那处违规是全量回归 `mvn -o clean install` 才暴露的，见 RV-15）
    replay_result = os.path.join(MAIN_DERIVED, "domain", "ReplayResult.java")

    touched = [fingerprint, replay, service, replay_result]
    snapshot = {p: open(p, "rb").read() for p in touched}

    print("=" * 78)
    print("反向验证 · threshold_version 溯源回放闭环（S2-8 / ADR-11）")
    print("=" * 78)

    checks = []
    try:
        checks = _run_all_checks(checks, fingerprint, replay, service, replay_result,
                                 fingerprint_test, replay_test, both_tests)
    finally:
        for p, blob in snapshot.items():
            open(p, "wb").write(blob)
        print("\n[还原] 已把 %d 个被触碰的源文件写回快照" % len(touched))
        dirty = [p for p, blob in snapshot.items() if open(p, "rb").read() != blob]
        if dirty:
            print("[还原] 🛑 还原后仍有差异（这是脚本自身的 bug）: %s" % dirty)
        else:
            print("[还原] 逐字节校验通过：工作区与脚本运行前完全一致")

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


def _run_all_checks(checks, fingerprint, replay, service, replay_result,
                    fingerprint_test, replay_test, both_tests):

    # ---- RV-1：把"入参不一致即拒"放宽成"静默改用权威版本" ---- 
    #     这是 S2-8 最核心的一处防线：调用方带了一个与当前口径不符的版本号，
    #     服务端若"宽容地"改用自己算的，调用方会以为自己指定成功了 ——
    #     而它记下来的那个版本号与库里落的值不一致，复盘时两边对不上。
    print("\nRV-1  版本一致性断言：入参不一致时静默改用权威版本（不拒）")
    checks.append(inject_and_check(
        "RV-1 不一致时静默改用权威版本",
        service,
        "        String r = requested.trim();\n"
        "        if (computed.equals(r)) {\n"
        "            return computed;\n"
        "        }",
        "        String r = requested.trim();\n"
        "        if (true) {   // [INJECTED] 不一致时也静默返回权威版本\n"
        "            return computed;\n"
        "        }",
        both_tests,
        None))

    # ---- RV-2：把落库值改回入参（"版本号可随便传"这一缺口的成因） ----
    #     只改一处：把 assertThresholdVersion 的产出换成 req.thresholdVersion()。
    #     这一改会让"服务端算版本号"这件事退化成"服务端信任入参"。
    print("\nRV-2  落库值退化为入参（服务端不再算版本号）")
    checks.append(inject_and_check(
        "RV-2 落库值取自入参",
        service,
        "        String thresholdVersion = assertThresholdVersion(req.thresholdVersion());",
        "        String thresholdVersion = req.thresholdVersion();   // [INJECTED] 取自入参",
        both_tests,
        None,
        also=[]) )

    # ---- RV-3：指纹段数从 9 减到 8（删掉 module_mapping）----
    #     这一条特别要紧：模块映射段是 PRD L856 逐字要求的落点。
    #     删掉它版本号照样算得出来 —— 没有任何报错。
    #     🛑 必须同时改 SEGMENTS 与 canonicalSegments，否则段缺失会触发
    #        内部不变式（抛异常）而不是"少一段却正常" —— 那样注入就不成立。
    print("\nRV-3  指纹少一段（删掉 module_mapping，SEGMENTS 与 canonical 同改）")
    checks.append(inject_and_check(
        "RV-3 指纹少一段",
        fingerprint,
        "            \"scale_structure\",\n"
        "            \"module_mapping\");",
        "            \"scale_structure\");   // [INJECTED] 少了 module_mapping",
        fingerprint_test,
        "段数应为 9",
        also=[(
            fingerprint,
            "        out.put(\"module_mapping\", moduleMappingSegment());",
            "        // [INJECTED] module_mapping 段被移除",
        )]))

    # ---- RV-4：去掉 BigDecimal 规范化（0.80 与 0.8 变成两个口径）----
    print("\nRV-4  数值规范化：stripTrailingZeros 被去掉（0.80 ≠ 0.8）")
    checks.append(inject_and_check(
        "RV-4 不做数值规范化",
        fingerprint,
        "        return v.stripTrailingZeros().toPlainString();",
        "        return v.toPlainString();   // [INJECTED] 不规范化",
        fingerprint_test,
        None))

    # ---- RV-5：段级指纹改成"全部相同"（漂移无法定位到段）----
    #     把每段的指纹都设成总指纹 ⇒ 漂移段清单会永远是空 ⇒ 定位能力消失。
    print("\nRV-5  段级指纹塌缩为同一个值（漂移无法定位到段）")
    checks.append(inject_and_check(
        "RV-5 段级指纹塌缩",
        fingerprint,
        "            segmentFingerprints.put(name, hex(body));",
        "            segmentFingerprints.put(name, hex(String.join(\"\\n\", joined)));   // [INJECTED]",
        fingerprint_test,
        None))

    # ---- RV-6：driftedSegments 恒返回空（"没有漂移"与"定位不到"被合并）----
    #     这一改会让复盘时任何口径事故都显示为"无漂移段"。
    print("\nRV-6  driftedSegments 恒返回空（漂移信息被吞掉）")
    checks.append(inject_and_check(
        "RV-6 漂移段恒为空",
        fingerprint,
        "        Map<String, String> drifted = new LinkedHashMap<>();\n"
        "        if (storedSegmentFingerprints == null || storedSegmentFingerprints.isEmpty()) {\n"
        "            return drifted;\n"
        "        }",
        "        Map<String, String> drifted = new LinkedHashMap<>();\n"
        "        if (true) { return drifted; }   // [INJECTED] 恒空",
        fingerprint_test,
        None))

    # ---- RV-7：自描述里混入口径取值（泄漏内部口径到端侧）----
    print("\nRV-7  自描述泄漏口径取值（evidence_snapshot 会下发到端侧）")
    checks.append(inject_and_check(
        "RV-7 自描述混入取值",
        fingerprint,
        "        m.put(\"segment_fingerprints\", segmentFingerprints);",
        "        m.put(\"segment_fingerprints\", segmentFingerprints);\n"
        "        m.put(\"debug_values\", \"pass_threshold=0.80;AS_refund.A1=0.571\");   // [INJECTED]",
        fingerprint_test,
        None))

    # ---- RV-8：漂移判定挪到重算之后（用巧合给漂移背书）----
    #     🛑 这是本类最要紧的一处设计。改法：把"版本不一致即返回"这一块整体禁用，
    #        于是流程会先重算、再落到 REPRODUCED/DIVERGED —— 于是
    #        "旧版本号 + 新旧口径同结果"会被报成"可复现"。
    print("\nRV-8  漂移判定被挪到重算之后（巧合给漂移背书）")
    checks.append(inject_and_check(
        "RV-8 漂移不再先于重算",
        replay,
        "        if (!versionMatches) {\n"
        "            return new ReplayResult(ReplayOutcome.DRIFTED, storedVersion, current.version(),",
        "        if (false) {\n"
        "            return new ReplayResult(ReplayOutcome.DRIFTED, storedVersion, current.version(),",
        replay_test,
        None,
        also=[]) )

    # ---- RV-9：把"缺输入 ⇒ 不可判"改回"算不出来也算 DIVERGED"（假指控）----
    #     这一条正是本轮反向验证逼出来的真实缺陷：初期实现把 recomputed == null
    #     也报成 DIVERGED，而它的说明文字会宣称"branch 的算法被改过"。
    #     改回去即复现那个假指控。
    print("\nRV-9  缺输入被误报为 DIVERGED（假指控：算法漂移）")
    checks.append(inject_and_check(
        "RV-9 缺输入误报 DIVERGED",
        replay,
        "        if (recomputed == null) {\n"
        "            return new ReplayResult(ReplayOutcome.INCOMPARABLE_DOMAIN, storedVersion,\n"
        "                    current.version(), Map.of(), storedBranch, null, false,",
        "        if (false) {\n"
        "            return new ReplayResult(ReplayOutcome.INCOMPARABLE_DOMAIN, storedVersion,\n"
        "                    current.version(), Map.of(), storedBranch, null, false,",
        replay_test,
        None,
        also=[(
            replay,
            "        boolean sameBranch = storedBranch.equals(recomputed.dbLabel());",
            "        boolean sameBranch = recomputed != null\n"
            "                && storedBranch.equals(recomputed.dbLabel());   // [INJECTED] 允许 null",
        )]))

    # ---- RV-10：回放结论被标为可对外（漂移被当成客户属性）----
    print("\nRV-10  回放结论标为可对外（customerFacing 恒 false 被破）")
    checks.append(inject_and_check(
        "RV-10 回放可对外",
        replay_result,
        "    public boolean customerFacing() {\n"
        "        return false;\n"
        "    }",
        "    public boolean customerFacing() {\n"
        "        return true;   // [INJECTED]\n"
        "    }",
        replay_test,
        None))

    # ---- RV-11：把"域外判定"排到漂移之后（E5 会先撞上版本/重算）----
    print("\nRV-11  E5 域外判定被排到末位")
    checks.append(inject_and_check(
        "RV-11 E5 域外判定排到末位",
        replay,
        "        boolean domainExcluded = isDomainExcluded(effectRaw);\n"
        "        if (domainExcluded) {",
        "        boolean domainExcluded = isDomainExcluded(effectRaw);\n"
        "        if (false) {   // [INJECTED] 域外判定被禁用",
        replay_test,
        None))

    # ---- RV-12：版本号为空时静默返回（不拒）----
    print("\nRV-12  版本号为空时不拒（硬约束②的破坏被吞掉）")
    checks.append(inject_and_check(
        "RV-12 空版本号被放过",
        replay,
        "        if (storedVersion == null || storedVersion.isBlank()) {\n"
        "            throw new BizException(ErrorCode.VALIDATION_FAILED,",
        "        if (false) {\n"
        "            throw new BizException(ErrorCode.VALIDATION_FAILED,",
        replay_test,
        None))

    # ---- RV-13：依据不可解析时静默继续（而不是拒）----
    print("\nRV-13  依据快照不可解析时静默继续")
    checks.append(inject_and_check(
        "RV-13 不可解析被放过",
        replay,
        "        if (snapshot == null) {\n"
        "            throw new BizException(ErrorCode.VALIDATION_FAILED,",
        "        if (snapshot == null) {\n"
        "            snapshot = new java.util.LinkedHashMap<>();   // [INJECTED] 静默给空快照\n"
        "        }\n"
        "        if (false) {\n"
        "            throw new BizException(ErrorCode.VALIDATION_FAILED,",
        replay_test,
        None))

    # ---- RV-14：段级指纹的 Map.copyOf 换回来（保序纪律被破）----
    #     🛑 这一条是本轮反向验证逼出的第二个真实缺陷：Map.copyOf 的迭代顺序
    #        在 JDK 上不保证，会让同一份口径在不同 JVM 启动间写出 key 顺序不同的快照。
    print("\nRV-14  段级指纹丢序（Map.copyOf 的迭代顺序不保证）")
    checks.append(inject_and_check(
        "RV-14 段级指纹丢序",
        fingerprint,
        "        this.segmentFingerprints =\n"
        "                Collections.unmodifiableMap(new LinkedHashMap<>(segmentFingerprints));",
        "        this.segmentFingerprints = Map.copyOf(segmentFingerprints);   // [INJECTED]",
        fingerprint_test,
        "键序应与 SEGMENTS 一致"))

    # ---- RV-15：让 record 重新依赖 service 包（ArchitectureBoundaryTest 的 R4/DIP）----
    #     🛑 这一条是本轮反向验证逼出的【第三个真实缺陷】，而且它的暴露方式本身就是
    #        一条纪律：**定向跑判定域测试永远发现不了它** ——
    #        ArchitectureBoundaryTest 住在 `com.diaoyuanyun.dy.app`（不在 derived 包下），
    #        `-Dtest=Threshold*` 根本不会加载它。
    #     🛑 注入形态【必须】是"新增一个对 service 类型的引用"，而不是"改 package"：
    #        后者会断开 controller 的 import ⇒ 编译失败 ⇒ 拿到的是 Java 的语义，
    #        不是门禁有牙齿。
    print("\nRV-15  record 重新依赖 service 包（ArchitectureBoundaryTest R4/DIP）")
    checks.append(inject_and_check(
        "RV-15 record 依赖 service 包",
        replay_result,
        "    public ReplayResult {",
        "    // [INJECTED] 让这个 record 重新引用 service 包的类型\n"
        "    private static final Class<?> WIRING_PROBE =\n"
        "            com.diaoyuanyun.dy.app.derived.service.ThresholdVersionReplay.class;\n\n"
        "    public ReplayResult {",
        "ArchitectureBoundaryTest",
        "R4-domain-model-must-not-depend-on-service"))

    return checks


if __name__ == "__main__":
    sys.exit(main())