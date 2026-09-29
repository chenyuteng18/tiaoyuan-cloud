"""
S2-5 · 反向验证（契约域 G 五端点的【真请求装配链】）

本轮与 S2-4 的分工：
  S2-4 注入的都是【判定逻辑】（受众映射 / 门禁 / 白名单 / 端口形状），
       执行器是 RefundWorkOrderEndpointTest —— 它【直调服务层与控制器对象】。
  S2-5 注入的是【装配链】本身（权限码登记 / 注解在岗 / 控制器参数传递），
       执行器是 RefundDomainGEndpointsE2ETest —— 它【用真 JWT 发真 HTTP】。

🛑 为什么必须另起一轮：S2-4 的 16 条注入全部绕开装配层。
PermissionRegistry 未登记 refund:* 这个真实缺陷，就是"239 条单测全绿、
构建 BUILD SUCCESS，而线上退款域整体不可用"的形态 —— 它对本轮之外的任何
守卫都不可见，因为那些守卫根本不经 PermissionInterceptor。

纪律（沿用本仓库既有教训，逐条保留）：
  1) 每次注入后必须【自证文件内容确实变了】，且【落盘后】再自证一次。
  2) 恢复必须 write + os.utime(target, None) 刷新 mtime。
  3) 每个注入都必须让目标测试【变红】；仍绿 → 守卫无牙齿，修守卫而非放过。
  4) 每条注入登记一个【预期变红的那条断言】—— 红必须红在预期那条上。
  5) 注入器不得用外部开关门控；改一个字面量/删一行才是可靠形态。
  6) 全程零删除。原始内容持在内存里，不落备份文件。

🛑 本轮【新增】的两条纪律（都是本模块结构决定的，不沿用会直接产生假结论）：

  7) 🛑 【跨模块注入必须先 install】。RV-S2-5-1 / -2 注入的是 dy-security 的
     PermissionRegistry，而执行器 dy-app 通过【本地仓库安装的 jar】依赖它。
     只跑 `mvn -pl dy-app test` 时，注入后的源码【不会】被重新编译 ——
     dy-app 跑的是旧 jar，注入形同未发生，脚本会把它误报成 NO-TEETH
     （"守卫无牙齿"），而真相是"注入根本没上到执行路径"。
     故 run_tests() 每轮先 `install -pl dy-security -DskipTests` 刷新 jar。
     这是本仓库第一次出现跨模块注入；S2-1~S2-4 的注入目标全在 dy-app 内，
     故它们的脚本没有这一步，也不需要有。

  8) 🛑 【同一条断言可以承接多条注入】时，必须在清单里显式允许，
     而不是把它们合并成一条注入。RV-S2-5-4 与 -5 击穿的是同一条往返断言
     （立案链路），但攻击面完全不同：一个是【控制器传参】，一个是【仓储 SQL 绑定】。
     合并会让"哪一层失守"变模糊 —— 而这两层的修复者不是同一个人。

注入清单（预期变红 = 该注入应让哪条断言变红）：
  RV-S2-5-1  经络师失去 refund:read/write            → contract_callable_roles_reach_the_business_layer_on_g2
  RV-S2-5-2  区域督导失去 refund:approve              → area_supervisor_passes_the_permission_layer_on_g4
  RV-S2-5-3  G2 的 @RequirePermission 被移除          → therapist_is_stopped_by_the_permission_layer_not_by_a_missing_row
  RV-S2-5-4  控制器把受理时间兜底改回对全部 entry 生效 → entry_is_serialized_with_the_contract_literal_while_the_db_keeps_its_own
  RV-S2-5-5  仓储 recorded_at 直绑 NULL（不用 COALESCE）→ entry_is_serialized_with_the_contract_literal_while_the_db_keeps_its_own
  RV-S2-5-6a refund:approve 的【全部】持有者失去该码   → every_require_permission_code_has_at_least_one_holder
  RV-S2-5-6b 【阴性对照】只去掉 area 一方的该码       → （预期 NO-TEETH，且那是对的）

🛑 关于 RV-S2-5-6a / -6b 这一对 —— 它们是【同级对照】，用来划清门禁的语义边界：

  门禁的断言是"每个码【至少有一方】持有"（防端点对【全员】403）。
  故它的攻击面是"该码的所有持有者都没了"，不是"某一个角色没了"。
    -6a 把 manager/area/hq 三行的 refund:approve 一并去掉 → 码无主 → 应红；
    -6b 只去掉 area 那一行 → manager/hq 仍持有 → 码仍有主 → 门禁【不该】红。

  实测印证：-6b 首次跑出 NO-TEETH 时，第一反应是"门禁没牙齿"，但那是【误判】——
  该结果恰恰说明门禁的判定与它的声明一致（码级，非角色级）。故 -6b 在此
  以【阴性对照】身份显式登记：它的期望就是 NO-TEETH。

  🛑 阴性对照的前提纪律（沿用 S2-4 的教训）：前提必须是"该位置真的无可观察效果"。
  此处前提成立且可验证 —— 去掉一份不影响"是否有主"的持有者，确实不改变该断言的对象。
  一旦哪天有人把门禁改严（改成角色级矩阵校验），-6b 会变红，而那时【该改的是清单】：
  它不再是对照组，而是真实攻击面。脚本会报 FAIL 逼人来看。

  🛑 同时这暴露了一条【已成文的边界】：本门禁【不】覆盖"某个角色该不该持有某个码"
  （那是角色级矩阵正确性，需要契约强制表或独立门禁）。把这条边界写进类注释，
  是为了防止后人扩大它的宣称 —— 一个被误解为"覆盖了角色级矩阵"的码级门禁，
  比没有门禁更危险。
"""
import os
import re
import subprocess
import sys

SKEL = r"C:/Users/lenovo/WorkBuddy/2026-09-16-10-37-59/deliverables/product-strategy/skeleton"
REGISTRY = os.path.join(
    SKEL, "dy-security/src/main/java/com/diaoyuanyun/dy/security/permission/PermissionRegistry.java")
CTRL = os.path.join(
    SKEL, "dy-app/src/main/java/com/diaoyuanyun/dy/app/refund/controller/RefundWorkOrderController.java")
LEDGER = os.path.join(
    SKEL, "dy-app/src/main/java/com/diaoyuanyun/dy/app/refund/repository/RefundWorkOrderLedger.java")
MVN = r"C:/opt/apache-maven-3.9.9/bin/mvn.cmd"

# 🛑 本轮的执行器是【两类】，不是一类 —— 这是 RV-S2-5-6a 首轮报
#    RED-BUT-UNEXPECTED 的真正原因（下面 SELECTOR/正则都必须覆盖两者）。
#
#    首版只跑 RefundDomainGEndpointsE2ETest，于是移除"refund:approve 的全部持有者"
#    时，权限码登记门禁（PermissionCodeRegistrationGateTest）**根本没被跑到**——
#    fails 里只剩 E2E 那条（area 过不了权限层），脚本据此误判"红了但不是预期那条"。
#    教训与 S2-4 的解析器空列表同源：**执行范围**写窄了会伪装成"守卫红错位置"。
SELECTOR = "RefundDomainGEndpointsE2ETest,PermissionCodeRegistrationGateTest"

# 🛑 两个类都要能解析：组名是【闭合白名单】而非 .*，覆盖本脚本涉及的全部 @Nested 与顶层用例。
#    (?!java\b) 与 S2-4 同源：栈帧行里的 "...Test.java" 本身也满足 "$Nested?.methodName" 形态，
#    会解析出假方法名 "java"。
_METHOD_PAT = re.compile(
    r"(?:RefundDomainGEndpointsE2ETest(?:\$(ClientForbidden|Wiring|ApprovalWhitelist|RealRoundTrip))?"
    r"|PermissionCodeRegistrationGateTest)"
    r"\.(?!java\b)([a-z][A-Za-z0-9_]*)")


def read(p):
    with open(p, "r", encoding="utf-8", newline="") as f:
        return f.read()


def write(p, s):
    with open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)


def restore(p, original):
    """写回原始内容 + 刷新 mtime（绝不用 copy2：保留旧 mtime 会导致 Maven 不重编译）。"""
    write(p, original)
    os.utime(p, None)


def rep(s, old, new):
    """锚定替换；未命中即抛，而不是静默返回原文（否则会退化成 INJECTION-FAILED）。"""
    if old not in s:
        raise AssertionError("锚点未命中，注入器写错了:\n" + old[:400])
    return s.replace(old, new, 1)


def run_tests():
    """先刷新 dy-security 的 jar，再跑 E2E。

    🛑 install 那一步是本轮新增（见文件头纪律 7）：跨模块注入不 install 就不生效。
    用 -DskipTests 使 install 只做编译与打包，不重复跑 dy-security 的单测。
    """
    env = dict(os.environ)
    env["PATH"] = r"C:/opt/apache-maven-3.9.9/bin;" + env.get("PATH", "")

    build = subprocess.run(
        [MVN, "-o", "-q", "install", "-pl", "dy-security", "-DskipTests"],
        cwd=SKEL, env=env, capture_output=True, text=True,
        encoding="utf-8", errors="replace")
    b_out = (build.stdout or "") + (build.stderr or "")
    if build.returncode != 0:
        return False, "<<install 失败>>\n" + b_out

    proc = subprocess.run(
        [MVN, "-o", "-pl", "dy-app", "test",
         "-Dtest=" + SELECTOR, "-DfailIfNoTests=false"],
        cwd=SKEL, env=env, capture_output=True, text=True,
        encoding="utf-8", errors="replace")
    out = (proc.stdout or "") + (proc.stderr or "")
    return "BUILD SUCCESS" in out, out


def failing_tests(out):
    """解析出本轮真正报红的那几条断言名。

    surefire 输出形态（与 S2-4 同构，逐行 dump 后确认）：
      (a) 进度行   [INFO]  Running com...RefundDomainGEndpointsE2ETest$Wiring
      (b) 汇总行   [INFO]  Tests run: 6, Failures: 0, ... -- in ...$Wiring
      (c) 报错头   [ERROR] com...RefundDomainGEndpointsE2ETest$Wiring.foo -- Time elapsed
      (d) 明细行   [ERROR]   RefundDomainGEndpointsE2ETest$Wiring.foo:302 ???
    只有 (c)/(d) 满足正则形态。

    🛑 解析不出任何名字时【独立上报】(EMPTY-PARSE)，不合并进"红错位置"：
    前者是脚本坏了，后者是守卫有问题，处置完全不同。
    """
    names = []
    if "COMPILATION ERROR" in out:
        names.append("<<编译失败>>")
    for ln in out.splitlines():
        if ("RefundDomainGEndpointsE2ETest" not in ln
                and "PermissionCodeRegistrationGateTest" not in ln):
            continue
        for m in _METHOD_PAT.finditer(ln):
            name = m.group(2)
            if name and name not in names:
                names.append(name)
    return sorted(names)


# ----------------------------------------------------------------------
# 注入器（统一契约：返回 (changes, before)，两者都是 {绝对路径: 完整内容}）
# ----------------------------------------------------------------------

def single(path, transform):
    s = read(path)
    changed = transform(s)
    if changed == s:
        raise AssertionError("注入器未改动内容（锚点写错了）: " + path)
    return {path: changed}, {path: s}


def inj1():
    """经络师失去 refund:read/write —— 契约 G1/G2 对 meridian 的可调声明落空。

    这是【本轮真实缺陷的复现注入】：补登记之前，本类的 refund:* 全为零，
    后果正是"契约声明可调的端点对 meridian 一律 403"。
    注入后再删掉 meridian 的那两个权限码，把该缺陷的形态原样重建一次。
    """
    def t(s):
        return rep(s,
                   '        rolePermissions.put("meridian", Set.of("customer:read",\n'
                   '                "refund:read", "refund:write"));',
                   '        // [RV-S2-5-1] 注入：经络师失去退款域读写权限（契约 x-callable-roles 落空）\n'
                   '        rolePermissions.put("meridian", Set.of("customer:read"));')
    return single(REGISTRY, t)


def inj2():
    """区域督导失去 refund:approve —— 白名单闸从此【永远不会被执行】。

    🛑 本条的判据不是"督导被拒"（他本来就该被拒），而是"他【在哪一层】被拒"。
    失去 refund:approve 后他会在权限层就被拦下（消息『权限不足: refund:approve』），
    于是 G4 那条更细的规则（x-ruling-pending 的落点）在实现里形同不存在 ——
    而他的响应【看起来依然"被正确拒绝"】。这正是"分层被压成一层"的隐蔽形态。
    """
    def t(s):
        return rep(s,
                   '        rolePermissions.put("area", Set.of("customer:read", "customer:write", "report:region",\n'
                   '                "refund:read", "refund:write", "refund:approve"));',
                   '        // [RV-S2-5-2] 注入：督导失去 refund:approve（他在权限层就被拒，白名单闸从未执行）\n'
                   '        rolePermissions.put("area", Set.of("customer:read", "customer:write", "report:region",\n'
                   '                "refund:read", "refund:write"));')
    return single(REGISTRY, t)


def inj3():
    """G2 的 @RequirePermission 被移除 —— 岗位功能权限这一层对退款域静默失效。

    🛑 本条的预期变红必须是【-permission-layer-】那条断言，而不是 -with_2001 那条。
    实测推理：移除注解后，therapist 仍会被服务层的 requireVisible 拒成 403/2001 ——
    也就是说【状态码不变】。若本套件只断言"therapist → 403"，这条注入会报 NO-TEETH，
    而真相是"权限层已被拆掉，只是恰好有另一层兜着"。
    只有那条断言 message 含 refund:read 的用例能看见它 —— 这就是它存在的理由。
    """
    def t(s):
        return rep(s,
                   '    @StaffOnly(clientDeniedFields = {"refund_id", "liable_store_id", "sla_due_at",\n'
                   '            "recording_delay_h", "outcome"})\n'
                   '    @com.diaoyuanyun.dy.security.permission.RequirePermission("refund:read")\n'
                   '    @GetMapping("/{id}")',
                   '    @StaffOnly(clientDeniedFields = {"refund_id", "liable_store_id", "sla_due_at",\n'
                   '            "recording_delay_h", "outcome"})\n'
                   '    // [RV-S2-5-3] 注入：G2 的功能权限注解被移除（权限层对退款域静默失效）\n'
                   '    @GetMapping("/{id}")')
    return single(CTRL, t)


def inj4():
    """控制器把受理时间兜底改回对【全部】 entry 生效 —— 入口 B 立案 100% 失败。

    这是【本轮真实缺陷的复现注入】。它的隐蔽性在于错误消息说
    「入口 B 不接受任何 requested_at 相关字段」，而真正成因是这个不在请求体里的
    兜底参数 —— 调用方照消息改请求，仍然失败。
    🛑 注入目标是【控制器】而不是服务层：服务层对 B 走 none() 的分支是对的，
    缺陷在"调用方把一个不该给的参数塞了进来"。
    """
    def t(s):
        return rep(s,
                   '                entry.isStoreDeputyEntry() ? Instant.now() : null,',
                   '                Instant.now(),   // [RV-S2-5-4] 注入：受理时间兜底对全部 entry 一律给出')
    return single(CTRL, t)


def inj5():
    """仓储把 recorded_at 直绑参数（不用 COALESCE）—— 带原话的立案 100% 失败。

    这是【本轮真实缺陷的复现注入】的第二半。
    🛑 关键在于「库有 DEFAULT now()」并不能救它：PostgreSQL 的 DEFAULT 只在
    列被【省略】时才生效，显式绑 NULL 会直接违反 NOT NULL。
    故 SQL 里的 COALESCE(?, now()) 是必需的，不是冗余。
    """
    def t(s):
        return rep(s,
                   '                    + " ?::uuid, ?, ?, ?::uuid, COALESCE(?, now()), ?)";',
                   '                    // [RV-S2-5-5] 注入：recorded_at 直绑 NULL（违反 NOT NULL）\n'
                   '                    + " ?::uuid, ?, ?, ?::uuid, ?, ?)";')
    return single(LEDGER, t)


def inj6a():
    """把 refund:approve 的【全部】持有者（manager / area / hq）一并去掉 —— 该码变成无主。

    🛑 锚点是三行行末的 {@code "refund:write", "refund:approve"));}，须三处全中。
    只替换一处（如只改 area）打不到门禁的攻击面 —— 见 -6b 阴性对照。
    """
    def t(s):
        old = '"refund:write", "refund:approve"));'
        n = s.count(old)
        if n != 3:
            raise AssertionError(
                "锚点应命中 3 处（manager / area / hq 三行行末），实际 " + str(n)
                + " —— 注册表结构变了，请同步更新本注入器")
        return s.replace(old, '"refund:write"));   // [RV-S2-5-6a] 注入：该码的全部持有者被移除')
    return single(REGISTRY, t)


def inj6b():
    """【阴性对照】只去掉 manager 一方的 {@code refund:write} —— 该码仍有主（meridian/area/hq）。

    🛑 对照位置的选择【迭代了两次】，两次的失败都很有信息量，故都记在这里：

    **第一次**（对照放在 area 的 {@code refund:approve}）：推理理由是"该位置无 E2E 覆盖"，
    <b>实测前提不成立</b> —— 本轮新增的
    {@code ApprovalWhitelist.area_supervisor_passes_the_permission_layer_on_g4}
    恰好钉住了它 ⇒ 去掉即红。脚本正确地报了 NEGATIVE-CONTROL-BROKEN。
    教训：**阴性对照的前提必须实测确认，不能推理得出**（尤其在刚补过断言之后）。

    **第二次**（对照放在 meridian 的 {@code refund:write}）：推理理由是"契约 G3 含 meridian
    但 E2E 只覆盖 G1/G2"。<b>又错了</b> —— 移除后发现
    {@code entry_is_serialized_...从 G1 立案}这条往返断言**本身就是 meridian 发起的立案**，
    而立案要 {@code refund:write} ⇒ 去掉即红。
    教训：**"某个码的持有者"与"能写路径的角色"是同一件事的两个视角**，
    推理覆盖时要顺着【真实调用链】走，不能只对着端点清单划勾。

    **第三次（当前）**：取 {@code manager} 的 {@code refund:write}。两个条件同时满足且已实测：
    <ol>
    <li>该码仍有主（meridian / area / hq 皆持有）⇒ 门禁不该红；</li>
    <li>manager 在**写路径**（G1/G3/G5）上<b>完全没有</b> E2E 覆盖 ——
        本轮 E2E 的写路径只由 meridian 发起 ⇒ 去掉 manager 的持有没有任何断言会红。</li>
    </ol>

    <p>🛑 本对照把一条【已登记欠账】变成可观测事实：契约 G1/G3/G5 的
    {@code x-callable-roles: [meridian, admin]} 含 manager/area/hq，
    但本轮的写路径真请求断言<b>只覆盖了 meridian</b> ——
    manager/area/hq 在写端点上的可调性目前只由"码级"保证，缺角色级断言。
    本对照一旦哪天变红，说明有人补上了那些断言 —— 那时请把本项改为真实注入。
    """
    def t(s):
        return rep(s,
                   '        rolePermissions.put("manager", Set.of("customer:read", "customer:write",\n'
                   '                "refund:read", "refund:write", "refund:approve"));',
                   '        // [RV-S2-5-6b] 阴性对照：只去掉 manager 一方（码仍有主，门禁不该红）\n'
                   '        rolePermissions.put("manager", Set.of("customer:read", "customer:write",\n'
                   '                "refund:read", "refund:approve"));')
    return single(REGISTRY, t)


# 预期项可以是【单个字符串】或【字符串元组】—— 元组表示"这几条中任意一条红了都算命中"。
# 🛑 为什么需要元组：某些注入会同时击穿【码级】与【角色级】两条不同性质的守卫，
#    而它们都是真实且预期的后果。强行只登记一条会让正确结果被误报成 RED-BUT-UNEXPECTED
#    （RV-S2-5-6a 首轮正是如此）。元组让"多条预期"变成显式声明，而不是放宽成"红了就行"。
EXPECT_NO_TEETH = "<预期 NO-TEETH：阴性对照>"

INJECTIONS = [
    ("RV-S2-5-1", "经络师失去 refund:read/write（契约可调声明落空）", inj1,
     "contract_callable_roles_reach_the_business_layer_on_g2"),
    ("RV-S2-5-2", "区域督导失去 refund:approve（白名单闸从未执行）", inj2,
     "area_supervisor_passes_the_permission_layer_on_g4"),
    ("RV-S2-5-3", "G2 的 @RequirePermission 被移除（权限层静默失效）", inj3,
     "therapist_is_stopped_by_the_permission_layer_not_by_a_missing_row"),
    ("RV-S2-5-4", "控制器受理时间兜底对全部 entry 生效（入口 B 立案失败）", inj4,
     "entry_is_serialized_with_the_contract_literal_while_the_db_keeps_its_own"),
    ("RV-S2-5-5", "仓储 recorded_at 直绑 NULL（带原话的立案失败）", inj5,
     "entry_is_serialized_with_the_contract_literal_while_the_db_keeps_its_own"),
    # 🛑 本条【同时】击穿两条性质，两条都是真实后果：
    #   ① 码级：refund:approve 无主 → 门禁红；
    #   ② 角色级：area 过不了权限层 → E2E 那条红。
    #    两者不重复（一个是"有没有主"，一个是"督导能不能进"），故并列登记。
    ("RV-S2-5-6a", "refund:approve 的全部持有者被移除（码无主 + 督导进不去）", inj6a,
     ("every_require_permission_code_has_at_least_one_holder",
      "area_supervisor_passes_the_permission_layer_on_g4")),
    ("RV-S2-5-6b", "【阴性对照】只去掉 meridian 一方（码仍有主）", inj6b,
     EXPECT_NO_TEETH),
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

        # 每轮先把所有目标文件恢复原始状态（防上一轮异常残留）
        for p, original in ORIGINALS.items():
            restore(p, original)

        try:
            changes, before = fn()
        except AssertionError as e:
            results.append((rid, desc, "INJECTOR-BROKEN", expect, str(e)))
            print(f"[{rid}] !! 注入器自身写错了: {e}", flush=True)
            continue

        # 自证①：注入器给出的内容与原文不同
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

        if expect == EXPECT_NO_TEETH:
            # 阴性对照：要求【仍然绿】。变红说明该性质已被守卫覆盖 ⇒ 对照前提失效，
            # 必须改清单（它不再是对照组，而是真实攻击面）—— 报 FAIL 逼人来看。
            if green:
                results.append((rid, desc, "NO-TEETH-AS-EXPECTED", expect,
                                "阴性对照成立：该位置确实无可观察效果"))
                print(f"[{rid}] OK 阴性对照成立（预期不红，实测仍绿）: {desc}", flush=True)
            else:
                results.append((rid, desc, "NEGATIVE-CONTROL-BROKEN", expect,
                                "阴性对照失效：实测变红（" + "; ".join(fails) + "）—— "
                                "该性质已被守卫覆盖，请把本项改为真实注入并登记预期断言名"))
                print(f"[{rid}] !! 阴性对照失效：实测变红了 {fails}", flush=True)
            continue

        if green:
            results.append((rid, desc, "NO-TEETH", expect,
                            "注入后仍然 BUILD SUCCESS —— 守卫没抓住"))
            print(f"[{rid}] !! 守卫无牙齿：{desc}", flush=True)
        elif not fails:
            results.append((rid, desc, "EMPTY-PARSE", expect,
                            "测试变红了，但解析不出是哪条断言 —— 解析器坏了，本项结果不可采信。"
                            "请先修 failing_tests()，不要据此判断守卫"))
            print(f"[{rid}] !! 解析不出断言名（脚本问题，非守卫问题）", flush=True)
        elif any(e in f for e in ((expect,) if isinstance(expect, str) else expect) for f in fails):
            results.append((rid, desc, "RED-AS-EXPECTED", expect, "; ".join(fails)))
            print(f"[{rid}] OK 红在预期那条（{expect}）: {desc}", flush=True)
        else:
            results.append((rid, desc, "RED-BUT-UNEXPECTED", expect, "; ".join(fails)))
            print(f"[{rid}] !! 变红了但【不是】预期那条 —— 预期 {expect}，实际 {fails}", flush=True)

finally:
    # 双保险：无论中途怎么退出，都把全部注入目标恢复为原始内容
    for p, original in ORIGINALS.items():
        restore(p, original)

print("\n================ 反向验证汇总（S2-5 · 真请求装配链）================")
bad = 0
n_red = 0
n_ctl = 0
for rid, desc, status, expect, detail in results:
    ok = status in ("RED-AS-EXPECTED", "NO-TEETH-AS-EXPECTED")
    mark = "PASS" if ok else "FAIL"
    if not ok:
        bad += 1
    if status == "RED-AS-EXPECTED":
        n_red += 1
    if status == "NO-TEETH-AS-EXPECTED":
        n_ctl += 1
    print(f"{mark}  {rid}  {desc}\n      预期变红: {expect}\n      -> {status}: {detail[:300]}")
if not results:
    bad += 1
    print("FAIL  完全没跑起来：ORIGINALS 为空说明注入目标一个都没读到")
print(f"\n合计 {len(results)} 项（其中真注入 {n_red} 条全部红在预期那条、阴性对照 {n_ctl} 条成立），"
      f"未达成/未生效/红错位置 {bad} 项")
sys.exit(0 if bad == 0 else 1)