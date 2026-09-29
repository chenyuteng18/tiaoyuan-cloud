"""
S2-3 · 反向验证（可见性矩阵 + 回执三态留痕的语义守卫）

纪律（沿用本仓库既有教训，逐条保留）：
  1) 每次注入后必须【自证文件内容确实变了】—— 否则 str.replace 静默未命中，
     脚本会报"门禁没牙齿"，而真相是"根本没注入"。
  2) 恢复必须 write + os.utime(target, None) 刷新 mtime。若保留旧 mtime，
     恢复后的源文件会显得比注入期间编译出的 .class 更旧，Maven 认为"最新"
     → 不重编译 → 跑的是被污染的 class（本仓库实测踩过这个坑）。
  3) 每个注入都必须让目标测试【变红】；若仍绿 → 守卫无牙齿，必须修守卫而不是放过。
  4) 每条注入登记一个【预期变红的那条断言】。仅仅"变红了"不够 ——
     红必须红在预期的那条上。否则一次无关的编译/环境故障也会被记成"守卫有牙齿"。

与 S2-1 / S2-2 的不同：本轮注入目标是【回执三态的语义载体】——
ReceiptState（三态唯一的语义分歧点）/ ReceiptInterpreter（两段式形状与兜底断言）/
RefundReceiptService（调用顺序与结案门禁）/ 内存实现（append-only）/
RefundVisibilityMatrix（四条硬锁）。

🛑 关于注入点的四处刻意选择（都是本轮实测出来的判断，不是随意挑的）：

  (a) RV-S2-3-4 注入【内存实现】而不是【端口】。
      往 RefundReceiptPort 加一个抽象 updateState 会让 RefundReceiptLedger 与
      InMemoryRefundReceiptPort 双双编译失败 —— 而"编译失败"证明的是
      "接口被改了"，不是"断言抓住了改写动作"（断言根本没跑到）。
      把注入点放在内存实现上：编译通过、只有动词扫描那条断言变红，
      这才是"守卫有牙齿"的证据。

  (b) RV-S2-3-3 注入【新增一个单方法】而不是【给 decideAfterPush 加第 6 个参数】。
      后者会先编译失败（调用方签名不匹配），同样属"证明错了事情"。同理 (a)。

  (c) RV-S2-3-12 是【复合注入，同时击穿两层】。
      "三态齐备"实际由两处独立承担：内存实现 countByState 预填三态
      （与生产实现 RefundReceiptLedger 的"返回前补零"保持一致），
      且服务层 stateDistribution 再补一次 getOrDefault。
      🛑 只注入其中一层，断言【仍然绿】—— 这不是"守卫无牙齿"，是"双保险"。
      但反向验证要问的是"这条性质在最坏情况下是否仍被守住"，
      故本项同时击穿两层：那才是这条性质的真正攻击面。
      （若将来把冗余收敛为一层，本项应改回单层注入。）

  (d) RV-S2-3-12 的注入点必须在【服务层 + 内存实现】，而不是【仓储】。
      仓储的预填零会被内存实现与服务层各覆盖一次 —— 改仓储对本断言毫无影响，
      注入会静默无效、脚本误报"守卫无牙齿"。

注入清单（预期变红 = 该注入应让哪条断言变红）：
  RV-S2-3-1   PUSH_FAILED 的 pushAttempted 由 true 改 false   → push_attempted_differs_exactly_on_unauthorized
  RV-S2-3-2   第一段额度判断 > 0 改为 >= 0（未授权路径失守）  → unauthorized_path_never_invokes_the_pusher
  RV-S2-3-3   决策入口多出第三个方法（两段式被合并）          → there_is_no_single_shot_decide_method
  RV-S2-3-4   内存实现新增 update 动词方法（append-only 失守） → in_memory_implementation_exposes_no_mutating_method
  RV-S2-3-5   hasOfflineNotice 改查回执行（分表语义失守）      → has_offline_notice_ignores_receipt_rows
  RV-S2-3-6   coverageOfDist 对空键静默归入某一态             → null_key_in_distribution_is_rejected
  RV-S2-3-7   canSee 对客户返回 true（硬锁①被破）            → visible_set_is_exactly_the_four_frozen_roles
  RV-S2-3-8   督导 approve 声明改为静默取严（不抛）            → corrupted_matrix_is_rejected_at_construction
  RV-S2-3-9   assertThreeNumbersShown 去掉分母为 0 检查        → zero_denominator_must_not_be_shown_as_zero_percent
  RV-S2-3-10  requireLegalChannel 接受『订阅消息』             → closing_gate_passes_once_notice_is_recorded
  RV-S2-3-11  结案门禁改为只告警不抛                          → closing_gate_blocks_when_notice_is_missing
  RV-S2-3-12  【复合】内存实现不预填 + 服务层不补零            → state_distribution_always_has_all_three_keys
  RV-S2-3-13  ReceiptState.parse 对空白返默认态               → parsing_blank_state_is_rejected
"""
import os
import subprocess
import sys

SKEL = r"C:/Users/lenovo/WorkBuddy/2026-09-16-10-37-59/deliverables/product-strategy/skeleton"
MAIN = os.path.join(SKEL, "dy-app/src/main/java/com/diaoyuanyun/dy/app/refund")
TEST = os.path.join(SKEL, "dy-app/src/test/java/com/diaoyuanyun/dy/app/refund")
STATE = os.path.join(MAIN, "domain/ReceiptState.java")
INTERP = os.path.join(MAIN, "domain/ReceiptInterpreter.java")
MATRIX = os.path.join(MAIN, "domain/RefundVisibilityMatrix.java")
NOTICE = os.path.join(MAIN, "domain/RefundOfflineNoticeRow.java")
SVC = os.path.join(MAIN, "service/RefundReceiptService.java")
MEM = os.path.join(TEST, "InMemoryRefundReceiptPort.java")
MVN = r"C:/opt/apache-maven-3.9.9/bin/mvn.cmd"
SELECTOR = "RefundVisibilityAndReceiptTest"


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
         原始内容直接【持在内存里】，于是全程零删除。
    """
    write(p, original)
    os.utime(p, None)


def run_tests():
    env = dict(os.environ)
    env["PATH"] = r"C:/opt/apache-maven-3.9.9/bin;" + env.get("PATH", "")
    proc = subprocess.run(
        [MVN, "-o", "-pl", "dy-app", "test",
         "-Dtest=" + SELECTOR, "-DfailIfNoTests=false"],
        cwd=SKEL, env=env, capture_output=True, text=True,
        encoding="utf-8", errors="replace")
    out = (proc.stdout or "") + (proc.stderr or "")
    green = "BUILD SUCCESS" in out
    return green, out


def failing_tests(out):
    """解析出本轮真正报红的那几条断言名（过滤掉 "Running ..." 噪声行）。

    🛑 同时识别"编译失败"：那种情况下没有任何测试跑起来，
    而"变了红"不代表守卫有效 —— 必须把两者区分开。
    """
    names = []
    if "COMPILATION ERROR" in out:
        names.append("<<编译失败>>")
    for ln in out.splitlines():
        if "RefundVisibilityAndReceiptTest$" not in ln:
            continue
        if "Running " in ln:
            continue
        s = ln.strip()
        if s not in names:
            names.append(s)
    return names


# ----------------------------------------------------------------------
# 注入器
#
# 统一契约：每个注入器返回 (changes, before)
#   changes : {绝对路径: 注入后的完整内容}
#   before  : {绝对路径: 注入前的完整内容}
# 单文件注入就是 dict 里一项；复合注入（RV-S2-3-12）是两项。
# ----------------------------------------------------------------------

def single(path, transform):
    """把"读 → 改 → 返回"收纳成一个只改一个文件的注入器。"""
    s = read(path)
    return {path: transform(s)}, {path: s}


def inj1():
    """PUSH_FAILED 的 pushAttempted 由 true 改 false（三态语义分歧点被抹掉）。"""
    def t(s):
        return s.replace(
            '    PUSH_FAILED("推送失败", true);',
            '    PUSH_FAILED("推送失败", false);   // [RV-S2-3-1] 注入：语义分歧点被抹掉',
            1)
    return single(STATE, t)


def inj2():
    """第一段的额度判断由 > 0 改为 >= 0 —— 额度为 0 也放行到推送路径。"""
    def t(s):
        return s.replace(
            "        if (subscriptionQuota > 0) {",
            "        if (subscriptionQuota >= 0) {   // [RV-S2-3-2] 注入：额度 0 也放行到推送",
            1)
    return single(INTERP, t)


def inj3():
    """决策入口多出第三个方法 —— 模拟"有人把两段式合并成一个方法"。

    🛑 用一次【编译通过】的注入：加一个 private static 的单方法入口。
    它不会被任何调用方使用，但 getDeclaredMethods() 会看见它，
    于是"决策入口恰为两个"这条形状断言变红。
    """
    def t(s):
        anchor = """    // ==================================================================
    // 二、落库前校验（与库层 CHECK 同口径，但报错更早、更可读）
    // =================================================================="""
        injected = """    /** [RV-S2-3-3] 注入：一个"一次判完"的单方法入口（形状断言应抓住它）。 */
    private static ReceiptDecision decideAll(long subscriptionQuota,
                                             boolean succeeded,
                                             String failureReason,
                                             Instant pushedAt,
                                             Instant decidedAt,
                                             String templateId) {
        if (subscriptionQuota <= 0) {
            return new ReceiptDecision(ReceiptState.UNAUTHORIZED, CHANNEL_SUBSCRIPTION, null,
                    decidedAt, null, null);
        }
        if (succeeded) {
            return new ReceiptDecision(ReceiptState.PUSHED, CHANNEL_SUBSCRIPTION, templateId,
                    decidedAt, pushedAt, null);
        }
        return new ReceiptDecision(ReceiptState.PUSH_FAILED, CHANNEL_SUBSCRIPTION, templateId,
                decidedAt, null, failureReason);
    }

""" + anchor
        return s.replace(anchor, injected, 1)
    return single(INTERP, t)


def inj4():
    """内存实现新增一个带 update 动词的方法（append-only 在测试路径上先失守）。

    🛑 注入点刻意不放在端口上：往 RefundReceiptPort 加抽象方法会让两个实现
    双双编译失败，而编译失败证明不了"动词扫描这条断言有牙齿"。
    """
    def t(s):
        anchor = '    /** 测试自证：本实现确实收到了若干行（防"没写进去所以断言假绿"）。 */'
        injected = """    /** [RV-S2-3-4] 注入：就地改写入口（append-only 在测试路径上先失守）。 */
    void updateReceiptState(UUID refundId, ReceiptState next) {
        List<RefundReceiptRow> rows = receipts.get(refundId);
        if (rows != null && !rows.isEmpty()) {
            rows.set(0, rows.get(0));
        }
    }

""" + anchor
        return s.replace(anchor, injected, 1)
    return single(MEM, t)


def inj5():
    """hasOfflineNotice 改查回执行 —— 分表语义失守，"漏发"退化成永远为真。"""
    def t(s):
        return s.replace(
            "        return !notices.getOrDefault(refundId, List.of()).isEmpty();",
            "        return !receipts.getOrDefault(refundId, List.of()).isEmpty();"
            "   // [RV-S2-3-5] 注入：把回执行也算成人工告知",
            1)
    return single(MEM, t)


def inj6():
    """coverageOfDist 对空键静默归入『已推送』（未知状态静默进入分母）。"""
    def t(s):
        old = """            if (e.getKey() == null) {
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "覆盖率状态分布含空键 —— 空键无从归入三态之一，"
                                + "不得默认归到某一态（那会让一个未知状态静默进入分母或逃出分母）");
            }
            long c = e.getValue() == null ? 0 : e.getValue();"""
        new = """            if (false && e.getKey() == null) {   // [RV-S2-3-6] 注入：空键守卫失效
                throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,
                        "覆盖率状态分布含空键 —— 空键无从归入三态之一，"
                                + "不得默认归到某一态（那会让一个未知状态静默进入分母或逃出分母）");
            }
            if (e.getKey() == null) {
                pushed += e.getValue() == null ? 0 : e.getValue();   // 静默归入"已推送"
                continue;
            }
            long c = e.getValue() == null ? 0 : e.getValue();"""
        return s.replace(old, new, 1)
    return single(INTERP, t)


def inj7():
    """canSee 对客户返回 true —— 硬锁①被破（§2.2 冻结值）。"""
    def t(s):
        old = """    public boolean canSee(RefundAudienceRole role) {
        Boolean v = visible.get(role);"""
        new = """    public boolean canSee(RefundAudienceRole role) {
        if (role == RefundAudienceRole.CUSTOMER) {
            return true;   // [RV-S2-3-7] 注入：客户可见（硬锁①被破）
        }
        Boolean v = visible.get(role);"""
        return s.replace(old, new, 1)
    return single(MATRIX, t)


def inj8():
    """督导的显式 approve 声明改为静默取严（不再抛）——『可见≠可审批』被绕过。"""
    def t(s):
        old = """                if (hasApproveKey) {
                    assertApproveDeclarationHonored(role, declaredApprove, v);
                }"""
        new = """                if (false && hasApproveKey) {   // [RV-S2-3-8] 注入：声明冲突静默吞掉
                    assertApproveDeclarationHonored(role, declaredApprove, v);
                }"""
        return s.replace(old, new, 1)
    return single(MATRIX, t)


def inj9():
    """assertThreeNumbersShown 去掉分母为 0 检查 —— 『无样本』退化成 0%/100%。"""
    def t(s):
        old = """        public void assertThreeNumbersShown() {
            if (denominator() == 0) {"""
        new = """        public void assertThreeNumbersShown() {
            if (false && denominator() == 0) {   // [RV-S2-3-9] 注入：无样本检查失效"""
        return s.replace(old, new, 1)
    return single(INTERP, t)


def inj10():
    """requireLegalChannel 接受『订阅消息』—— 人工告知与推送事件重新混成一类。"""
    def t(s):
        return s.replace(
            "        if (!CHANNELS.contains(c)) {",
            '        if (!CHANNELS.contains(c) && !"订阅消息".equals(c)) {'
            '   // [RV-S2-3-10] 注入：推送事件被允许写进告知表',
            1)
    return single(NOTICE, t)


def inj11():
    """结案门禁改为只告警不抛 ——『漏发视同未回执』退回成一句文档提醒。"""
    def t(s):
        return s.replace(
            "                ReceiptInterpreter.assertOfflineFallbackRecorded(r.state(), hasNotice);",
            "                // [RV-S2-3-11] 注入：结案门禁只告警不抛\n"
            "                boolean ignoredForReverseVerification = hasNotice;",
            1)
    return single(SVC, t)


def inj12():
    """【复合注入】同时击穿两层"三态齐备"保护。

    🛑 为什么必须同时改两个文件：见文件头说明 (c)。
    只改服务层 → 内存实现预填的三态被原样透出 → 断言仍绿；
    只改内存实现 → 服务层 getOrDefault 补齐 → 断言仍绿。
    两层同时失效，才是这条性质的真正攻击面。
    """
    mem_s = read(MEM)
    svc_s = read(SVC)
    before = {MEM: mem_s, SVC: svc_s}

    mem_old = """        Map<ReceiptState, Long> out = new LinkedHashMap<>();
        for (ReceiptState s : ReceiptState.values()) {
            out.put(s, 0L);
        }
        for (List<RefundReceiptRow> rows : receipts.values()) {"""
    mem_new = """        Map<ReceiptState, Long> out = new LinkedHashMap<>();
        // [RV-S2-3-12] 注入：不预填三态（只回传库里出现过的键）
        for (List<RefundReceiptRow> rows : receipts.values()) {"""
    mem_inj = mem_s.replace(mem_old, mem_new, 1)

    svc_old = """        Map<ReceiptState, Long> out = new LinkedHashMap<>();
        for (ReceiptState s : ReceiptState.values()) {
            out.put(s, dist.getOrDefault(s, 0L));
        }
        return out;"""
    svc_new = """        Map<ReceiptState, Long> out = new LinkedHashMap<>();
        for (ReceiptState s : dist.keySet()) {   // [RV-S2-3-12] 注入：缺态不补零
            out.put(s, dist.getOrDefault(s, 0L));
        }
        return out;"""
    svc_inj = svc_s.replace(svc_old, svc_new, 1)

    return {MEM: mem_inj, SVC: svc_inj}, before


def inj13():
    """ReceiptState.parse 对空白返默认态 —— 缺状态静默变成『已推送』。"""
    def t(s):
        old = """        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "回执状态（receipt_state）必填 —— 三态之一缺落即证据链断链（P0-19）");
        }
        String c = code.trim();"""
        new = """        if (false && (code == null || code.isBlank())) {   // [RV-S2-3-13] 注入：守卫失效
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "回执状态（receipt_state）必填 —— 三态之一缺落即证据链断链（P0-19）");
        }
        if (code == null || code.isBlank()) {
            return ReceiptState.PUSHED;   // 静默默认态：缺状态被读成"已推送"
        }
        String c = code.trim();"""
        return s.replace(old, new, 1)
    return single(STATE, t)


INJECTIONS = [
    ("RV-S2-3-1", "PUSH_FAILED 的 pushAttempted 由 true 改 false", inj1,
     "push_attempted_differs_exactly_on_unauthorized"),
    ("RV-S2-3-2", "第一段额度判断 > 0 改为 >= 0", inj2,
     "unauthorized_path_never_invokes_the_pusher"),
    ("RV-S2-3-3", "决策入口多出第三个方法（两段式被合并）", inj3,
     "there_is_no_single_shot_decide_method"),
    ("RV-S2-3-4", "内存实现新增 update 动词方法", inj4,
     "in_memory_implementation_exposes_no_mutating_method"),
    ("RV-S2-3-5", "hasOfflineNotice 改查回执行", inj5,
     "has_offline_notice_ignores_receipt_rows"),
    ("RV-S2-3-6", "coverageOfDist 对空键静默归入", inj6,
     "null_key_in_distribution_is_rejected"),
    ("RV-S2-3-7", "canSee 对客户返回 true", inj7,
     "visible_set_is_exactly_the_four_frozen_roles"),
    ("RV-S2-3-8", "督导 approve 声明改为静默取严", inj8,
     "corrupted_matrix_is_rejected_at_construction"),
    ("RV-S2-3-9", "assertThreeNumbersShown 去掉分母为 0 检查", inj9,
     "zero_denominator_must_not_be_shown_as_zero_percent"),
    ("RV-S2-3-10", "requireLegalChannel 接受『订阅消息』", inj10,
     "closing_gate_passes_once_notice_is_recorded"),
    ("RV-S2-3-11", "结案门禁改为只告警不抛", inj11,
     "closing_gate_blocks_when_notice_is_missing"),
    ("RV-S2-3-12", "【复合】内存实现不预填 + 服务层不补零", inj12,
     "state_distribution_always_has_all_three_keys"),
    ("RV-S2-3-13", "ReceiptState.parse 对空白返默认态", inj13,
     "parsing_blank_state_is_rejected"),
]

results = []
ORIGINALS = {}
try:
    # 先把所有注入目标的原始内容读进内存（全程零备份文件、零删除）
    for _rid, _desc, _fn, _expect in INJECTIONS:
        changes, before = _fn()
        for p in before:
            ORIGINALS.setdefault(p, before[p])

    for rid, desc, fn, expect in INJECTIONS:
        # 每次注入前先确保所有目标文件处于原始状态（防止上一轮异常残留）
        for p, original in ORIGINALS.items():
            restore(p, original)

        changes, before = fn()

        # 自证①：注入器确实给出了与原文不同的内容
        unchanged = [p for p in changes if changes[p] == before[p]]
        if unchanged:
            results.append((rid, desc, "INJECTION-FAILED", expect,
                            "替换未命中：文件内容没变 -> " + "; ".join(unchanged)))
            print(f"[{rid}] !! 注入未生效 —— 锚点没匹配上，结果不可采信", flush=True)
            continue

        for p, content in changes.items():
            write(p, content)

        # 自证②：落盘后的内容确实与注入前不同
        persisted_bad = [p for p in changes if read(p) == before[p]]
        if persisted_bad:
            results.append((rid, desc, "INJECTION-FAILED", expect,
                            "写盘后内容与注入前相同 -> " + "; ".join(persisted_bad)))
            continue

        green, out = run_tests()
        fails = failing_tests(out)
        if green:
            results.append((rid, desc, "NO-TEETH", expect,
                            "注入后仍然 BUILD SUCCESS —— 守卫没抓住"))
            print(f"[{rid}] !! 守卫无牙齿：{desc}", flush=True)
        elif any(expect in f for f in fails):
            results.append((rid, desc, "RED-AS-EXPECTED", expect, "; ".join(fails)))
            print(f"[{rid}] OK 红在预期那条（{expect}）: {desc}", flush=True)
        else:
            results.append((rid, desc, "RED-BUT-UNEXPECTED", expect, "; ".join(fails)))
            print(f"[{rid}] !! 变红了但【不是】预期那条 —— 预期 {expect}，实际 {fails}", flush=True)

finally:
    # 双保险：无论中途怎么退出，都把全部注入目标恢复为原始内容
    for p, original in ORIGINALS.items():
        restore(p, original)

print("\n================ 反向验证汇总（S2-3）================")
bad = 0
for rid, desc, status, expect, detail in results:
    mark = "PASS" if status == "RED-AS-EXPECTED" else "FAIL"
    if mark == "FAIL":
        bad += 1
    print(f"{mark}  {rid}  {desc}\n      预期变红: {expect}\n      -> {status}: {detail[:260]}")
print(f"\n合计 {len(results)} 项，无牙齿/未生效/红错位置 {bad} 项")
sys.exit(0 if bad == 0 else 1)