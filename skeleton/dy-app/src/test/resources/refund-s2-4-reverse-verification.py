"""
S2-4 · 反向验证（契约域 G 五端点的装配与门禁）

纪律（沿用本仓库既有教训，逐条保留）：
  1) 每次注入后必须【自证文件内容确实变了】—— 否则 str.replace 静默未命中，
     脚本会报"门禁没牙齿"，而真相是"根本没注入"。
  2) 恢复必须 write + os.utime(target, None) 刷新 mtime。若保留旧 mtime，
     恢复后的源文件会显得比注入期间编译出的 .class 更旧，Maven 认为"最新"
     → 不重编译 → 跑的是被污染的 class（本仓库实测踩过这个坑）。
  3) 每个注入都必须让目标测试【变红】；若仍绿 → 守卫无牙齿，必须修守卫而不是放过。
  4) 每条注入登记一个【预期变红的那条断言】。仅仅"变红了"不够 ——
     红必须红在预期的那条上。否则一次无关的编译/环境故障也会被记成"守卫有牙齿"。
  5) 🛑 注入器【不得】用"读 System 属性"之类的外部开关做门控。本仓库实测：
     那种写法在脚本未设该属性时静默走原分支 —— 注入看起来"生效了"（内容确实变了）
     却什么也没坏，于是守卫被误判为"无牙齿"。改一个布尔字面量才是可靠的形态：
     它一定改变行为，且改动本身可在 diff 里被读到。
  6) 🛑 全程零删除。本机有安全删除守卫会拦下 os.remove —— 脚本会中途退出。
     原始内容直接【持在内存里】，不落备份文件；恢复只 write + utime。

与 S2-1 / S2-2 / S2-3 的不同：本轮注入目标是【装配与门禁】——
四层叠加边界里除"客户一律 403"（在派生可见性拦截器，非本轮范围）之外的三层：
  RefundAudienceRole（token→受众映射 / 审批白名单）
  RefundVisibilityMatrix（硬锁与自相矛盾）
  RefundRecordingPolicy / RefundWorkOrderService（三字段纪律）
  RetentionPolicy（两处豁免 / 审批落点 / 归档只读 / 代录者不可审批）
  RefundWorkOrderPort（端口形状：唯一改写方法）

🛑 关于注入点的刻意选择（都是本轮实测出来的判断，不是随意挑的）：

  (a) RV-S2-4-1 注入【令牌角色映射】而不是【审批白名单】。
      往 ofTokenRole 的映射里动手会让"督导"这个 token 解析不到任何受众，
      于是 ⑤ 与 ①【同时】变红 —— 而 ① 才是"身份解析是唯一落点"的守卫
      （它断言被拒集合恰为三个，一个不多一个不少）。
      故本条的预期变红登记为 ①。

  (b) RV-S2-4-2 注入【审批白名单登记值】而不是【令牌角色映射】。
      两者改的是同一行源码的两个不同字段（approvesRefund vs tokenRole），
      但击穿的是两条完全不同的性质：
        tokenRole 改动 → 身份解析（① 红）
        approvesRefund 改动 → 白名单集合（⑥ 红）
      这一条是"可见 ≠ 可审批"唯一显式载例的守卫（差集恰为 {area}）。

  (c) RV-S2-4-5 / -6 注入【服务层 / 口径层】的对应那一层。
      requirementOf 是两处豁免的唯一真相源，注入它会让 ⑬ 与 ⑭ 同时红（两条都问它）；
      注入服务层的"是否抛"分支，则 ⑫ 在纯服务层测试里可控。
      两条各红在各自那一层，避免"哪条守卫抓住了它"变模糊。

  (d) RV-S2-4-8 注入【内存替身】而不是【端口】。
      往 RefundWorkOrderPort 加一个抽象 updateSomething 会让 RefundWorkOrderLedger
      与 InMemoryRefundWorkOrderPort 双双编译失败 —— 而"编译失败"证明的是
      "接口被改了"，不是"断言抓住了改写动作"（断言根本没跑到）。

  (e) RV-S2-4-9 注入【端口的方法名】而不是【增删方法】—— 且必须同步改【全部】
      实现与调用点。🛑 本条的初版只改了端口与内存替身，结果编译失败：
      RefundWorkOrderLedger 的 @Override 悬空、测试里四处 port.updateOutcome(...) 找不到符号。
      "编译失败"既不证明"形状断言被抓住"，也不证明别的任何事 ——
      它只说明改动不完整。故本条现在把四处调用点一并改名，
      使编译【通过】、而"写方法集恰为四个"那条变红。这才是本条要证的东西。

  (f) RV-S2-4-10 是【复合注入，同时击穿两层】"客户硬锁"：
      矩阵层 HARD_INVISIBLE 是声明、assertHardInvisible 是按角色指名的守卫。
      只改声明 → 守卫仍按角色指名拦截 → 断言仍绿（那正是"逐角色指名"的设计意图：
      删掉集合里的一项不改变行为）。只删守卫 → 声明还在、visible 里的值仍为 false → 仍绿。
      故必须【同时】把声明里那一项与它对应的守卫都改掉，才是这条性质的真正攻击面。

  (g) RV-S2-4-13 注入【三字段捆绑的来源校验】而不是【G1 立案的入参归一路径】。
      两条看似都在问"有起点无来源"，但实际的可达路径不同：
        G1 立案（CreateDiscipline.㉔）→ 走 resolveRequestedAt，
            它在"无可核实来源"时先抛错，【根本到不了】捆绑校验；
        BundleGate.㊾ → 直接调用 assertBundleCanBePersisted，是唯一触达路径。
      故本条的预期变红必须是 bundle_gate_rejects_requested_at_without_source。
      🛑 初版登记成 CreateDiscipline 那条，实测报 RED-BUT-UNEXPECTED ——
      而那个结果【是对的】：它证明"两条断言不覆盖同一件事"，而不是守卫失效。

  (h) 🛑 本轮【撤掉】了一处阴性对照（初版的 RV-S2-4-4）。
      初版假设"服务层 approve 里的可见性闸关掉后测试仍绿"（即守卫在别处），
      据此把它标为 [预期 NO-TEETH]。实测变红 —— 因为把 requireVisible 整行换成
      硬编码角色后，⑤ 依然红（督导被白名单拦下），但"别的角色"再没人拦。
      这说明该位置【确实有守卫】，阴性对照的前提不成立。

      处置不是"改判标签了事"，而是【补一条断言】：
      ⑤ter non_visible_role_cannot_reach_the_approval_gate 把"已登记但不可见"
      与"未登记"两种拒绝都钉在 G4 上。补完之后 RV-S2-4-4 成为一条常规注入，
      预期红在 ⑤ter。

      🛑 教训：阴性对照的前提是"该位置真的没有可观察效果"。一旦守卫被补上，
      阴性对照必须【撤掉】—— 否则它会把"守卫到位"误报成异常。
      （本脚本不再提供 [预期 NO-TEETH] 分支：一个没有实例支持的机制
        会让人以为"随时可以标一下免责"，而它的正确用法恰恰极其罕见。）

注入清单（预期变红 = 该注入应让哪条断言变红）：
  RV-S2-4-1   令牌角色映射把 area 指向 manager（督导身份解析不到）  → admitted_and_denied_role_sets_are_exact
  RV-S2-4-2   审批白名单把 AREA_SUPERVISOR 标记为可审批              → approver_whitelist_is_proper_subset_of_contract_admin
  RV-S2-4-3   ofTokenRole 未登记时回落为 CUSTOMER（fail-open）        → unknown_token_role_is_rejected_not_fallen_back
  RV-S2-4-4   approve 的可见性闸换成硬编码角色（客户不再被拒）        → non_visible_role_cannot_reach_the_approval_gate
  RV-S2-4-4b  关掉唯一落点（RetentionPolicy）的角色分支               → area_supervisor_is_visible_but_not_approvable
  RV-S2-4-5   挽留的豁免闸改为永不抛                                  → entry_b_rejects_retention_with_logical_contradiction
  RV-S2-4-6   两种豁免被合并（健康风险报成首周期豁免）                → two_exemptions_are_distinguishable
  RV-S2-4-7   入口 B 不再走 none()（强行安上起点）                    → entry_b_is_not_subject_to_deputy_window
  RV-S2-4-8   内存替身 updateOutcome 只做 put（乐观并发失效）          → version_conflict_is_4001_not_404
  RV-S2-4-9   端口把 updateOutcome 改名（形状断言）                    → port_has_exactly_one_mutator_and_it_only_changes_outcome
  RV-S2-4-10  【复合】客户硬锁：声明 + 逐角色守卫 同时移除             → hard_invisible_roles_are_locked_at_matrix_level
  RV-S2-4-11  assertWritable 不再拒已归档                             → archived_work_order_is_read_only
  RV-S2-4-12  延迟起点改用 claimed（不必可核实）                       → delay_is_computed_from_resolved_start_not_claimed
  RV-S2-4-13  三字段捆绑不再拒绝"有起点无来源"                         → bundle_gate_rejects_requested_at_without_source
  RV-S2-4-14  控制器 create 端点去掉 @StaffOnly（客户 403 失守）        → every_endpoint_is_staff_only_with_named_denied_fields
  RV-S2-4-15  approve 不再校验代录人身份（缺失即放行）                  → missing_deputy_identity_is_rejected_not_skipped
"""
import os
import re
import subprocess
import sys

SKEL = r"C:/Users/lenovo/WorkBuddy/2026-09-16-10-37-59/deliverables/product-strategy/skeleton"
MAIN = os.path.join(SKEL, "dy-app/src/main/java/com/diaoyuanyun/dy/app/refund")
TEST = os.path.join(SKEL, "dy-app/src/test/java/com/diaoyuanyun/dy/app/refund")
ROLE = os.path.join(MAIN, "domain/RefundAudienceRole.java")
MATRIX = os.path.join(MAIN, "domain/RefundVisibilityMatrix.java")
RECORD = os.path.join(MAIN, "domain/RefundRecordingPolicy.java")
RETENTION = os.path.join(MAIN, "domain/RetentionPolicy.java")
PORT = os.path.join(MAIN, "domain/RefundWorkOrderPort.java")
SVC = os.path.join(MAIN, "service/RefundWorkOrderService.java")
CTRL = os.path.join(MAIN, "controller/RefundWorkOrderController.java")
LEDGER = os.path.join(MAIN, "repository/RefundWorkOrderLedger.java")
MEM = os.path.join(TEST, "InMemoryRefundWorkOrderPort.java")
ENDPOINT_TEST = os.path.join(TEST, "RefundWorkOrderEndpointTest.java")
MVN = r"C:/opt/apache-maven-3.9.9/bin/mvn.cmd"
SELECTOR = "RefundWorkOrderEndpointTest"

# 🛑 只匹配【明细行 / 报错头】里那个 "$NestedCase.methodName" 形态。
#    不匹配 "Running ...$ConfigSource"（无点号）与 "Tests run: ... -- in ...$ConfigSource"。
#    四个分组名是【闭合白名单】而非 .*：本套件只有这四类 @Nested + 顶层无嵌套的用例。
#
#    🛑 (?!java\b) 这一处 lookahead 是【实测】加的：栈帧行形如
#        \tat ...RefundWorkOrderEndpointTest$ApprovalGate.area_supervisor_...(RefundWorkOrderEndpointTest.java:223)
#    其中 "RefundWorkOrderEndpointTest.java" 本身也满足 "$Nested?.methodName" 形态，
#    于是解析出方法名 "java" —— 它会混进 fails 列表，让"实际红了哪条"多一个假名字。
#    假阳性比漏报更危险：漏报只会让本项报 FAIL（人去看），
#    而假阳性可能让某条无关注入【恰好】匹配上（本脚本用 `expect in f` 做子串判定，
#    而 "java" 不可能是任何 expect 的子串 —— 但同类假名一旦撞上就是静默误判）。
_METHOD_PAT = re.compile(
    r"RefundWorkOrderEndpointTest(?:\$(VisibilityTwoStep|ApprovalGate|RetentionExemptions"
    r"|BundleGate|ArchiveAndOutcome|CreateDiscipline|ControllerShape|ConfigSource))?"
    r"\.(?!java\b)([a-z][A-Za-z0-9_]*)")


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
         改为只刷新源文件 mtime：源比 class 新时 Maven 增量编译必然重编译。
      2) 不落备份文件 —— 备份同样要删，同样会被拦。
         原始内容直接【持在内存里】，于是全程零删除。
    """
    write(p, original)
    os.utime(p, None)


def rep(s, old, new):
    """锚定替换；未命中即【抛】，而不是静默返回原文。

    🛑 为什么不用裸 str.replace：未命中时它返回原文，脚本会走"文件内容没变"
    那条分支（INJECTION-FAILED）—— 那是能查出来的，但代价是"一次没注入"
    与"守卫没牙齿"在汇总里长得像。抛出会让它在注入器那一层就炸，
    调用栈直接指向那个锚点。本脚本两个都做：rep 抛错，外层再兜一次自证。
    """
    if old not in s:
        raise AssertionError("锚点未命中，注入器写错了:\n" + old[:400])
    return s.replace(old, new, 1)


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
    """解析出本轮真正报红的那几条断言名。

    🛑 过滤规则是【实测输出形态】反推出来的，不是想当然写的。

    surefire 输出里含 "RefundWorkOrderEndpointTest" 的行有【四类】，
    逐行 dump 之后才看清（见下表），其中只有末两类是本脚本要的：

      (a) 进度行   [INFO]  Running com...RefundWorkOrderEndpointTest$ConfigSource
      (b) 汇总行   [INFO]  Tests run: 9, Failures: 0, ... -- in ...$ConfigSource
                   [ERROR] Tests run: 9, Failures: 0, Errors: 1, ... -- in ...$ApprovalGate
      (c) 报错头   [ERROR] com...RefundWorkOrderEndpointTest$ApprovalGate.area_supervisor_... -- Time elapsed
      (d) 明细行   [ERROR]   RefundWorkOrderEndpointTest$VisibilityTwoStep.admitted_...:149 ???
                    \tat ...RefundWorkOrderEndpointTest$ApprovalGate.area_supervisor_...(RefundWorkOrderEndpointTest.java:223)

    🛑 本函数首版写成 "取该行第一个 token，若不含 $ 则丢弃" —— 第一个 token 是
    "[ERROR]" / "[INFO]" 这类【日志级别前缀】，于是所有行都被丢掉，
    解析结果恒为空列表。后果不是"报错"，而是更坏的一种：
    每条注入都被判成 RED-BUT-UNEXPECTED（"红了但不是预期那条"），
    而"实际红了哪条"这一栏是空的 —— 汇总表看起来像"16 项守卫全都没牙齿"，
    与真相（16 项全部正常变红、且红在预期那条）完全相反。

    教训：解析器的失败模式必须被【打印出来看到】。空列表这件事本身就是症状，
    故此处把它当成一种独立状态（EMPTY-PARSE）上报，而不是合并进"红错位置"。
    """
    names = []
    if "COMPILATION ERROR" in out:
        names.append("<<编译失败>>")
    for ln in out.splitlines():
        if "RefundWorkOrderEndpointTest" not in ln:
            continue
        # (a) 进度行 / (b) 汇总行：不含 "$Nested.methodName" 形态，正则自然滤掉。
        # 🛑 取 group(2)（方法名）而不是 group(1)：group(1) 是【嵌套类名】，
        #    而顶层用例（无 @Nested 的那几条，如 ㊹~㊼）没有嵌套段 —— 它是 None。
        #    首版取 group(1) 就撞上了这个：names 里混进 None，sorted() 直接抛
        #    "'<' not supported between NoneType and str"。而预期值登记的是【方法名】，
        #    所以取 group(2) 才是对的。
        for m in _METHOD_PAT.finditer(ln):
            name = m.group(2)
            if name and name not in names:
                names.append(name)
    return sorted(names)


# ----------------------------------------------------------------------
# 注入器
#
# 统一契约：每个注入器返回 (changes, before)
#   changes : {绝对路径: 注入后的完整内容}
#   before  : {绝对路径: 注入前的完整内容}
# 单文件注入就是 dict 里一项；复合注入（-9 / -10）是多文件。
# ----------------------------------------------------------------------

def single(path, transform):
    """把"读 → 改 → 返回"收纳成一个只改一个文件的注入器。"""
    s = read(path)
    changed = transform(s)
    if changed == s:
        raise AssertionError("注入器未改动内容（锚点写错了）: " + path)
    return {path: changed}, {path: s}


def inj1():
    """令牌角色映射把 area 指向 manager —— 督导这个 token 解析不到任何受众。

    🛑 注释必须写在【枚举常量之前】，不能写在行尾：
    枚举常量的行尾是逗号分隔符，行尾加注释会把那个逗号一起注释掉 ——
    于是下一个常量与它之间没有分隔符，【编译失败】。
    本条的初版正是这么写的，实测报 <<编译失败>>。这不是"守卫有牙齿"，
    是注入器自己写坏了：编译失败时断言一条都没跑到。
    """
    def t(s):
        return rep(s,
                   '    AREA_SUPERVISOR("area_supervisor", "区域督导", "admin", false, "area"),',
                   '    // [RV-S2-4-1] 注入：督导与门店负责人的 token 角色重合（督导解析不到受众）\n'
                   '    AREA_SUPERVISOR("area_supervisor", "区域督导", "admin", false, "manager"),')
    return single(ROLE, t)


def inj2():
    """审批白名单把区域督导标记为可审批 —— 『可见≠可审批』唯一显式载例消失。"""
    def t(s):
        return rep(s,
                   '    AREA_SUPERVISOR("area_supervisor", "区域督导", "admin", false, "area"),',
                   '    // [RV-S2-4-2] 注入：督导被标记为可审批\n'
                   '    AREA_SUPERVISOR("area_supervisor", "区域督导", "admin", true, "area"),')
    return single(ROLE, t)


def inj3():
    """ofTokenRole 对未登记角色【回落】而不是抛 —— 拼错的角色码静默变成一次权限收紧。

    🛑 注入形态就是【把 orElseThrow 换成 orElse(CUSTOMER)】。初版写成
    "orElse(...) 之后继续链式 describeConstable()/map()/orElseThrow(...)"，
    类型根本对不上 —— 编译失败。回落注入的正确形态是最朴素的那一种：
    直接给出兜底值。任何更"高级"的写法都只是把注入自己写坏。

    🛑 也不用"把 orElseThrow 那一行注释掉"的写法：它的实参与消息体还在后面几行，
    注释掉开头会让剩下的部分悬空 → 编译失败。整段替换是唯一可靠的形态。
    """
    def t(s):
        old = """        String t = tokenRole.trim();
        return Arrays.stream(values())
                .filter(r -> t.equals(r.tokenRole))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.VISIBILITY_DENIED,
                        "token 角色不在退款受众登记表内: '" + tokenRole + "'"
                                + "（已登记: " + allTokenRoles() + "）。"
                                + "🛑 不得回落为『不可见』—— 那会让一个拼错的角色码"
                                + "静默变成一次权限收紧，而真正的成因（有人用了未登记的角色）"
                                + "在日志里查无此事"));
    }"""
        new = """        String t = tokenRole.trim();
        // [RV-S2-4-3] 注入：未登记即回落为 CUSTOMER（fail-open）
        return Arrays.stream(values())
                .filter(r -> t.equals(r.tokenRole))
                .findFirst()
                .orElse(RefundAudienceRole.CUSTOMER);
    }"""
        return rep(s, old, new)
    return single(ROLE, t)


def inj4():
    """approve 的可见性闸换成【硬编码角色】—— 于是"客户一律 403"在 G4 上失守。

    🛑 注入必须换成【另一个普通角色】而不是"删掉这一行"：
    删掉会让局部变量 role 未定义 → 编译失败 → 断言没跑到。
    换成 MERIDIAN_THERAPIST 使编译通过，且它对本条的攻击面正好：
    调用方传什么 token 都不再被解析，"客户"照样进得来。

    <p>🛑 与本条配对的是 ⑤ter（non_visible_role_cannot_reach_the_approval_gate）——
    它断言"客户 / 未登记角色连审批闸都到不了"。初版没有这条断言，
    于是本注入【只】让 ⑤ 变红（督导被白名单拦下），而被拆掉的可见性闸
    没有任何一条断言在问。见文件头 (h)。
    """
    def t(s):
        return rep(s,
                   """        // 闸 ①：角色必须是可见的（否则连工单都看不到）
        RefundAudienceRole role = requireVisible(tokenRole);""",
                   """        // [RV-S2-4-4] 注入：审批路径上的可见性闸失效（换成一个硬编码角色）
        RefundAudienceRole role = RefundAudienceRole.MERIDIAN_THERAPIST;""")
    return single(SVC, t)


def inj4b():
    """关掉唯一落点的角色分支 —— ⑤ 才应该红（督导不再被白名单拦下）。"""
    def t(s):
        return rep(s,
                   "        if (approverRole != null && !approverRole.approvesRefund()) {",
                   "        if (false && approverRole != null && !approverRole.approvesRefund()) {"
                   "   // [RV-S2-4-4b] 注入")
    return single(RETENTION, t)


def inj5():
    """挽留的豁免闸改为永不抛 —— 『入口 B 不经挽留』退回成一句文档提醒。

    🛑 用【改条件】而不是"再套一层 if"：初版往里面插了一个 if(false){…}
    然后去别处补右花括号，补错了位置 → 括号失衡 → 编译失败。
    把条件改成 `isExempt() && false` 是单行、括号天然平衡的形态。
    """
    def t(s):
        return rep(s,
                   "        if (requirement.isExempt()) {",
                   "        if (requirement.isExempt() && false) {"
                   "   // [RV-S2-4-5] 注入：豁免闸永不抛")
    return single(SVC, t)


def inj6():
    """两处豁免合并为一个判定 —— 稽核再也看不出豁免的是哪一种。"""
    def t(s):
        return rep(s,
                   """        if (reason != null && reason.isHealthRiskEvent()) {
            return RetentionRequirement.EXEMPT_HEALTH_RISK;
        }""",
                   """        // [RV-S2-4-6] 注入：两种豁免被合成一种（健康风险也报成首周期豁免）
        if (reason != null && reason.isHealthRiskEvent()) {
            return RetentionRequirement.EXEMPT_FIRST_CYCLE;
        }""")
    return single(RETENTION, t)


def inj7():
    """入口 B 不再走 none()，改为强行走归一 —— 给它安上一个与客户诉求无关的起点。"""
    def t(s):
        return rep(s,
                   """        RefundRecordingPolicy.RequestedAtResolution resolved = req.entry().isStoreDeputyEntry()
                ? recordingPolicy.resolveRequestedAt(
                        req.requestedAtClaimed(),
                        req.customerProvenAt(),
                        req.evidenceRef(),
                        req.therapistHandoffAt(),
                        req.meridianAcceptedAt())
                : RefundRecordingPolicy.RequestedAtResolution.none();""",
                   """        // [RV-S2-4-7] 注入：入口 B 也走归一（强行安上一个起点）
        RefundRecordingPolicy.RequestedAtResolution resolved = recordingPolicy.resolveRequestedAt(
                req.requestedAtClaimed(),
                req.customerProvenAt(),
                req.evidenceRef(),
                req.therapistHandoffAt(),
                req.meridianAcceptedAt() == null ? Instant.now() : req.meridianAcceptedAt());""")
    return single(SVC, t)


def inj8():
    """内存替身的 updateOutcome 退化为无条件 put —— 乐观并发在测试路径上先失守。"""
    def t(s):
        return rep(s,
                   """        RefundWorkOrderRow cur = workOrders.get(refundId);
        if (cur == null || cur.outcome() != expectedOutcome || cur.outcome().isClosed()) {
            throw new BizException(ErrorCode.VERSION_CONFLICT,
                    "[内存替身] 工单结局推进失败：期望 " + expectedOutcome.code()
                            + "，当前 " + (cur == null ? "（不存在）" : cur.outcome().code()));
        }
        workOrders.put(refundId, withOutcome(cur, nextOutcome));""",
                   """        // [RV-S2-4-8] 注入：乐观并发校验失效（无条件覆盖）
        RefundWorkOrderRow cur = workOrders.get(refundId);
        if (cur != null) {
            workOrders.put(refundId, withOutcome(cur, nextOutcome));
        }""")
    return single(MEM, t)


def inj9():
    """【复合】端口把 updateOutcome 改名 —— 且【同步改全部】实现与调用点。

    🛑 只改端口会让 RefundWorkOrderLedger 的 @Override 悬空、
    测试里四处 port.updateOutcome(...) 找不到符号 → 编译失败。
    编译失败既不证明"形状断言被抓住"、也不证明别的任何事 ——
    它只说明改动不完整。故本条改 4 个文件：端口 / JDBC 仓储 / 内存替身 / 测试调用点。
    改完编译通过，而"写方法集恰为四个"那条变红 —— 那才是本条要证的东西。
    """
    files = [PORT, LEDGER, MEM, ENDPOINT_TEST]
    before = {p: read(p) for p in files}
    changes = {}
    for p in files:
        s = before[p]
        if p == PORT:
            # 端口是 abstract 声明（无 public）
            s2 = rep(s, "    void updateOutcome(String tenantId, UUID refundId,",
                     "    // [RV-S2-4-9] 注入：改写方法被改名（形状断言应为此发声）\n"
                     "    void insertOutcome(String tenantId, UUID refundId,")
        elif p in (LEDGER, MEM):
            # 两个实现是 @Override public
            s2 = rep(s, "public void updateOutcome(", "public void insertOutcome(")
        else:
            # 测试文件只有【调用点】没有声明 —— 逐处改名，一处不落
            s2 = s
            while "port.updateOutcome(" in s2:
                s2 = rep(s2, "port.updateOutcome(", "port.insertOutcome(")
        if s2 == s:
            raise AssertionError("注入未改动内容: " + p)
        changes[p] = s2
    return changes, before


def inj10():
    """【复合注入】客户硬锁：HARD_INVISIBLE 里的项 + 对应的逐角色守卫，同时移除。

    🛑 为什么必须同时改两处：见文件头 (f)。
    只改声明 → assertHardInvisible(CUSTOMER) 仍按角色指名拦截 → 断言仍绿；
    只删守卫 → 声明还在、visible 里的值仍为 false → 断言仍绿。
    两层同时失效，才是这条性质的真正攻击面。
    """
    before = {MATRIX: read(MATRIX)}
    inj = rep(before[MATRIX],
              """    private static final Set<RefundAudienceRole> HARD_INVISIBLE = Set.of(
            RefundAudienceRole.CUSTOMER,
            RefundAudienceRole.THERAPIST,""",
              """    private static final Set<RefundAudienceRole> HARD_INVISIBLE = Set.of(
            // [RV-S2-4-10] 注入：客户从硬锁集合里被移除
            RefundAudienceRole.THERAPIST,""")
    inj = rep(inj,
              """        assertHardInvisible(RefundAudienceRole.CUSTOMER);
        assertHardInvisible(RefundAudienceRole.THERAPIST);""",
              """        // [RV-S2-4-10] 注入：客户那道逐角色守卫被移除
        assertHardInvisible(RefundAudienceRole.THERAPIST);""")
    return {MATRIX: inj}, before


def inj11():
    """assertWritable 不再拒已归档 —— 『归档后只读』退化成一句注释。"""
    def t(s):
        return rep(s,
                   "        if (current.isClosed()) {",
                   "        if (false && current.isClosed()) {   // [RV-S2-4-11] 注入：归档只读失守")
    return single(RETENTION, t)


def inj12():
    """延迟起点改用 claimed —— 让一个不可核实的主张改写 24h 计时。"""
    def t(s):
        return rep(s,
                   "        if (hasProven && hasHandoff) {",
                   """        // [RV-S2-4-12] 注入：claimed 混进"取较早"的比较域
        if (claimed != null) {
            return new RequestedAtResolution(claimed,
                    RequestedAtSource.CUSTOMER_PROVEN, claimed, evidenceRef);
        }
        if (hasProven && hasHandoff) {""")
    return single(RECORD, t)


def inj13():
    """三字段捆绑不再拒绝"有起点无来源" —— C1-2 可被"时间照填、来源不填"一次抹掉。"""
    def t(s):
        return rep(s,
                   "        if (hasRequested && !hasSource) {",
                   "        if (false && hasRequested && !hasSource) {"
                   "   // [RV-S2-4-13] 注入：来源校验失效")
    return single(RECORD, t)


def inj14():
    """控制器 create 端点去掉 @StaffOnly —— 客户调 G1 不再一律 403。"""
    def t(s):
        return rep(s,
                   """    @Idempotent
    @StaffOnly(clientDeniedFields = {"refund_id", "liable_store_id", "sla_due_at",
            "recording_delay_h", "outcome"})
    @com.diaoyuanyun.dy.security.permission.RequirePermission("refund:write")
    @PostMapping
    public Result<Object> create(@RequestBody CreateRefundRequest body) {""",
                   """    // [RV-S2-4-14] 注入：写端点的 @StaffOnly 被移除（客户 403 失守）
    @Idempotent
    @com.diaoyuanyun.dy.security.permission.RequirePermission("refund:write")
    @PostMapping
    public Result<Object> create(@RequestBody CreateRefundRequest body) {""")
    return single(CTRL, t)


def inj15():
    """代录人身份缺失时放行 —— 动机阀门在最需要它的时刻失效。

    🛑 形态是"在最前面插入一个提前 return"。它编译通过、括号平衡，
    且一定改变行为（这正是纪律 5 要的形态）。
    """
    def t(s):
        return rep(s,
                   """        if (isBlank(deputyOperatorId) || isBlank(approverId)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,""",
                   """        // [RV-S2-4-15] 注入：身份缺失即放行（动机阀门失效）
        if (isBlank(deputyOperatorId)) {
            return;
        }
        if (isBlank(deputyOperatorId) || isBlank(approverId)) {
            throw new BizException(ErrorCode.BUSINESS_RULE_VIOLATED,""")
    return single(RETENTION, t)


INJECTIONS = [
    ("RV-S2-4-1", "令牌角色映射把 area 指向 manager（督导解析不到受众）", inj1,
     "admitted_and_denied_role_sets_are_exact"),
    ("RV-S2-4-2", "审批白名单把区域督导标记为可审批", inj2,
     "approver_whitelist_is_proper_subset_of_contract_admin"),
    ("RV-S2-4-3", "ofTokenRole 未登记时回落为 CUSTOMER（fail-open）", inj3,
     "unknown_token_role_is_rejected_not_fallen_back"),
    ("RV-S2-4-4", "approve 的可见性闸换成硬编码角色（客户不再被拒）", inj4,
     "non_visible_role_cannot_reach_the_approval_gate"),
    ("RV-S2-4-4b", "关掉唯一落点（RetentionPolicy）的角色分支", inj4b,
     "area_supervisor_is_visible_but_not_approvable"),
    ("RV-S2-4-5", "挽留的豁免闸改为永不抛", inj5,
     "entry_b_rejects_retention_with_logical_contradiction"),
    ("RV-S2-4-6", "两种豁免被合并为一个判定", inj6,
     "two_exemptions_are_distinguishable"),
    ("RV-S2-4-7", "入口 B 不再走 none()（强行安上起点）", inj7,
     "entry_b_is_not_subject_to_deputy_window"),
    ("RV-S2-4-8", "内存替身 updateOutcome 只做 put", inj8,
     "version_conflict_is_4001_not_404"),
    ("RV-S2-4-9", "端口 updateOutcome 改名（+ 全部实现与调用点）", inj9,
     "port_has_exactly_one_mutator_and_it_only_changes_outcome"),
    ("RV-S2-4-10", "【复合】客户硬锁：声明 + 逐角色守卫同时移除", inj10,
     "hard_invisible_roles_are_locked_at_matrix_level"),
    ("RV-S2-4-11", "assertWritable 不再拒已归档", inj11,
     "archived_work_order_is_read_only"),
    ("RV-S2-4-12", "延迟起点改用 claimed", inj12,
     "delay_is_computed_from_resolved_start_not_claimed"),
    ("RV-S2-4-13", "三字段捆绑不再拒绝『有起点无来源』", inj13,
     "bundle_gate_rejects_requested_at_without_source"),
    ("RV-S2-4-14", "控制器 create 去掉 @StaffOnly", inj14,
     "every_endpoint_is_staff_only_with_named_denied_fields"),
    ("RV-S2-4-15", "审批不再校验代录人身份（缺失即放行）", inj15,
     "missing_deputy_identity_is_rejected_not_skipped"),
]

results = []
ORIGINALS = {}
try:
    # 先把所有注入目标的原始内容读进内存（全程零备份文件、零删除）
    for _rid, _desc, _fn, _expect in INJECTIONS:
        try:
            _changes, _before = _fn()
        except AssertionError as e:
            results.append((_rid, _desc, "INJECTOR-BROKEN", _expect, str(e)))
            print(f"[{_rid}] !! 注入器自身写错了: {e}", flush=True)
            continue
        for p in _before:
            ORIGINALS.setdefault(p, _before[p])

    for rid, desc, fn, expect in INJECTIONS:
        if any(r[0] == rid and r[2] == "INJECTOR-BROKEN" for r in results):
            continue

        # 每次注入前先确保所有目标文件处于原始状态（防止上一轮异常残留）
        for p, original in ORIGINALS.items():
            restore(p, original)

        try:
            changes, before = fn()
        except AssertionError as e:
            results.append((rid, desc, "INJECTOR-BROKEN", expect, str(e)))
            print(f"[{rid}] !! 注入器自身写错了: {e}", flush=True)
            continue

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
        elif not fails:
            # 🛑 解析不出任何断言名 = 【脚本自己坏了】，不是"守卫没牙齿"、也不是"红错位置"。
            #    必须独立上报：这两件事的处置完全不同（修脚本 vs 修守卫），
            #    而把"解析崩了"混进"红错位置"会让 16 项一并报 FAIL，
            #    看起来像"整个套件的守卫全失效"——与真相完全相反（见 failing_tests 的注释）。
            results.append((rid, desc, "EMPTY-PARSE", expect,
                            "测试变红了，但解析不出是哪条断言 —— 解析器坏了，本项结果不可采信。"
                            "请先修 failing_tests()，不要据此判断守卫"))
            print(f"[{rid}] !! 解析不出断言名（脚本问题，非守卫问题）", flush=True)
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

print("\n================ 反向验证汇总（S2-4）================")
bad = 0
for rid, desc, status, expect, detail in results:
    mark = "PASS" if status == "RED-AS-EXPECTED" else "FAIL"
    if mark == "FAIL":
        bad += 1
    print(f"{mark}  {rid}  {desc}\n      预期变红: {expect}\n      -> {status}: {detail[:300]}")
if not results:
    bad += 1
    print("FAIL  完全没跑起来：ORIGINALS 为空说明注入目标一个都没读到")
print(f"\n合计 {len(results)} 项，未达成/未生效/红错位置 {bad} 项")
sys.exit(0 if bad == 0 else 1)