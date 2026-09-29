# -*- coding: utf-8 -*-
"""
S2-1 反向验证（reverse-verification-gate）

原理：把源码里的关键断言【精确失效】，跑测试，断言测试【必红】。
      红不了 = 该断言是装饰品（测试全绿但不具备判别力）。
      跑完一律恢复原文件。

🛑 注入器必须自证"真的改动了文件"——初版 RV-3 用了不含 'if (' 的锚点，
   替换静默失败（str.replace 找不到就原样返回），于是"注入"根本没发生，
   而脚本仍报"测试全绿 ⇒ 无牙齿"。假阴性本身成了第二条缺陷。
   故本版每次注入后断言"文件内容确实变了"，变了才跑测试。

四处注入：
  RV-1  RefundPolicy.FulfillmentFormula：effectJudgementAllowed 断言失效
  RV-2  RefundPolicy.DeputyEntry：24h 窗口断言失效
  RV-3  RefundVisibilityMatrix.assertHardLocks：删掉 assertHardInvisible(CUSTOMER) 那一行
  RV-4  RefundVisibilityMatrix：督導 approve 断言失效
"""
import subprocess
import sys
import shutil
import os

ROOT = r"C:\Users\lenovo\WorkBuddy\2026-09-16-10-37-59\deliverables\product-strategy\skeleton"
DOMAIN = os.path.join(ROOT, r"dy-app\src\main\java\com\diaoyuanyun\dy\app\refund\domain")
POLICY = os.path.join(DOMAIN, "RefundPolicy.java")
MATRIX = os.path.join(DOMAIN, "RefundVisibilityMatrix.java")
BACKUP_DIR = os.path.join(ROOT, "_work_backups")
MVN = r"C:\opt\apache-maven-3.9.9\bin\mvn.cmd"


def read(p):
    with open(p, "r", encoding="utf-8") as f:
        return f.read()


def write(p, s):
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(s)


def run_test(test_name):
    cmd = [MVN, "-o", "-pl", "dy-app", "test",
           "-Dtest=" + test_name, "-Dsurefire.failIfNoSpecifiedTests=false", "-q"]
    env = dict(os.environ)
    env["PATH"] = r"C:\opt\apache-maven-3.9.9\bin;" + env.get("PATH", "")
    r = subprocess.run(cmd, cwd=ROOT, capture_output=True, env=env)
    out = (r.stdout or b"").decode("utf-8", "replace") + (r.stderr or b"").decode("utf-8", "replace")
    return (r.returncode == 0), out


def check(rv_id, desc, target, transform, test_name):
    """transform: str -> str，必须真的改变内容（否则视为注入失败）。"""
    print("=" * 78)
    print("[%s] %s" % (rv_id, desc))
    print("  注入 -> %s" % test_name)
    bak = target + ".rv-backup"
    shutil.copyfile(target, bak)
    try:
        before = read(target)
        after = transform(before)
        if after == before:
            print("  ‼️  注入未生效：变换前后内容相同 —— 锚点已失效，本次结果不可采信")
            print("     （这类假阴性本身是缺陷：它会让『无牙齿』的报告看起来像真的）")
            return None
        write(target, after)

        # 自证：写回后重新读盘，确认改动真的落盘
        on_disk = read(target)
        if on_disk == before:
            print("  ‼️  改动未落盘，本次结果不可采信")
            return None

        passed, out = run_test(test_name)
        if passed:
            print("  ❌ 失败：注入错误后测试仍然全绿 —— 该断言没有牙齿！")
            return False
        print("  ✅ 通过：注入后测试变红（门禁有牙齿）")
        for l in [x for x in out.splitlines() if "Tests run" in x or "Expected" in x][:3]:
            print("     | " + l.strip())
        return True
    finally:
        # 🛑 必须【用 copyfile 而不是 copy2】并显式刷新 mtime。
        #    copy2 会保留备份的原始 mtime，于是恢复后的源码 mtime 早于
        #    注入期间编译出的 .class —— Maven 的"按时间戳判断新旧"会认为类是最新的、
        #    于是【不重新编译】，后续构建跑的是被注入过错误的那个 class。
        #    （实证：某次全量回归里 area_supervisor_approval_is_rejected 变红，
        #     而源码完好 —— 根因就是这个假阳性的残留类。）
        #    这是比"门禁没牙齿"更隐蔽的一类失效：验证工具污染了被测物。
        shutil.copyfile(bak, target)
        os.utime(target, None)   # 把 mtime 刷成"现在"，强制 Maven 重新编译
        os.remove(bak)
        print("  （已恢复 %s，并刷新 mtime 以强制重编译）" % os.path.basename(target))


def main():
    results = []

    def disable_if(src, anchor):
        """把 `if (<anchor>) {` 变成 `if (false && <anchor>) {`。"""
        needle = "if (" + anchor
        if needle not in src:
            return src
        return src.replace(needle, "if (false && " + anchor, 1)

    results.append(check(
        "RV-1",
        "RefundPolicy#FulfillmentFormula：effectJudgementAllowed 断言失效",
        POLICY,
        lambda s: disable_if(s, "effectJudgementAllowed) {"),
        "RefundPolicyContractTest#mutant_fulfillment_with_effect_judgement_is_rejected"))

    results.append(check(
        "RV-2",
        "RefundPolicy#DeputyEntry：24h 代录窗口断言失效",
        POLICY,
        lambda s: disable_if(s, "recordWithinHours != DEPUTY_RECORD_WINDOW_HOURS_REQUIRED) {"),
        "RefundPolicyContractTest#mutant_deputy_window_other_than_24h_is_rejected"))

    # RV-3：删掉"客户硬锁"那一行调用（逐角色指名断言的判别力）
    results.append(check(
        "RV-3",
        "RefundVisibilityMatrix：删掉 assertHardInvisible(CUSTOMER) 这一行",
        MATRIX,
        lambda s: s.replace("        assertHardInvisible(RefundAudienceRole.CUSTOMER);\n", "", 1),
        "RefundPolicyContractTest#hard_lock_customer_visibility_is_rejected"))

    # RV-4：督导 approve 断言失效
    results.append(check(
        "RV-4",
        "RefundVisibilityMatrix：区域督导 approve 断言失效",
        MATRIX,
        lambda s: disable_if(s, "declaredApprove && !role.approvesRefund()) {"),
        "RefundPolicyContractTest#area_supervisor_approval_is_rejected"))

    print("=" * 78)
    ok = [r for r in results if r is True]
    bad = [r for r in results if r is False]
    skip = [r for r in results if r is None]
    print("反向验证汇总：有牙齿 %d / 无牙齿 %d / 注入未生效 %d" % (len(ok), len(bad), len(skip)))
    return 0 if (not bad and not skip) else 1


if __name__ == "__main__":
    sys.exit(main())