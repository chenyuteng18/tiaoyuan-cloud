#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
反向验证脚本 —— 契约域 A 可见性声明与门店行级过滤（S2-9）。

原理：好的测试不是因为"现在绿"而可信，而是因为"注入对应的错误时它会红"。
本脚本对 S2-9 的每一处关键防线做一次【注入 → 跑测试 → 断言必须红在预期的那条 → 还原】。

不注入的反向验证（"把代码改对，看测试绿不绿"）证明不了任何事：
它对一个恒绿的测试信心完全相同。

🛑 S2-9 的防线有一类此前从未有过的形态：**契约声明的响应集决定错误码**。
A2 只声明 '200' + '401'（无 403），A3 只声明 '200' + '403'（无 401）。
这两个集合是【逐字从契约读出来的】，而不是"HTTP 常规"推出来的 ——
故"无身份报 403"这种看起来很合理的写法，在 A2 上是契约违约。
本脚本必须能抓住它（RV-2 与 RV-3 是一对反向的注入）。

🛑 另一类新形态：**真请求 E2E 抓出单测抓不到的缺陷**。
S2-9 的三处真实缺陷（旧 jar、无 token 报 403、List 未展开成 varargs）
全部通过了编译与单测，只在"真 JWT + 真 HTTP + 真库"下暴露。
故本脚本的多数注入都跑 DomainAEndpointsE2ETest，而不是跑单测。
"""
import io
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
# resources → test → src → dy-app → skeleton 共四层，别数错一层
SKEL = os.path.normpath(os.path.join(ROOT, "..", "..", "..", ".."))
APP = os.path.join(SKEL, "dy-app")
IDENTITY = os.path.join(APP, "src", "main", "java", "com", "diaoyuanyun", "dy", "app", "identity")
SECURITY = os.path.join(SKEL, "dy-security", "src", "main", "java",
                        "com", "diaoyuanyun", "dy", "security", "permission")
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

    🛑🛑 **S2-9 的新形态：同一次输出里两种编码并存**（本函数无法解决）。
    Maven 自己的前缀行（`[ERROR]`）是 **GBK**，而 surefire **fork 出来的**
    测试进程打印的断言消息是 **UTF-8** —— 一次 `subprocess` 的 stdout 把两者
    混在一起。任何单一解码策略都必然打乱其中一半：
      · 按 GBK 解 ⇒ Maven 行对，断言消息成 ``；
      · 按 UTF-8 解 ⇒ 断言消息对，Maven 行成乱码或直接 UnicodeDecodeError。
    故 🛑 **预期锚点一律只能取 ASCII**（测试方法名 / 权限码 / 枚举字面），
    **绝不能用中文断言消息**做锚 —— 那会让"明明抓住了"被读成"没抓住"。
    这就是本函数只能"尽最大努力"、而锚点纪律必须另行遵守的原因。
    （与技能「失效模式 13」同源，但成因不同：那条是"解码策略选错"，
      本条是"一份输出里根本不存在单一正确策略"。）
    """
    for enc in ("gbk", "utf-8"):
        try:
            return blob.decode(enc)
        except UnicodeDecodeError:
            continue
    return blob.decode("utf-8", "replace")


# 🛑 从 surefire 的失败行里抽出【失败的方法名】——它是 ASCII，不受编码影响。
#    形如：`[ERROR] com.a.b.C$D.some_method -- Time elapsed: 0.1 s <<< FAILURE!`
#    这是本项目在"输出双编码"下唯一可靠的锚点形态。
_FAILED_METHOD = re.compile(r"\[ERROR\]\s+\S+\.(\w+)\s+--\s+Time elapsed:[^\n]*<<< FAILURE!")


def failed_methods(out):
    """本次输出里每个失败用例的**方法名**（ASCII，双编码安全）。"""
    return sorted(set(_FAILED_METHOD.findall(out)))


# 🛑 这里【不能】加 -q：-q 只放行 WARN/ERROR，而断言的自定义消息是
#    surefire 在测试失败时随堆栈一起打印的，属于被抑制的那一档。
#    加 -Dsurefire.useFile=false 让失败详情走控制台。
def run_tests(test_filter):
    cmd = [MVN, "-o", "surefire:test", "-Dsurefire.useFile=false",
           "-Dtest=" + test_filter, "-Dsurefire.failIfNoSpecifiedTests=false"]
    p = subprocess.run(cmd, cwd=APP, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, shell=False, env=MVN_ENV)
    out = _decode(p.stdout)
    ok = ("Tests run:" in out and "Failures: 0" in out and "Errors: 0" in out
          and "BUILD FAILURE" not in out and "BUILD SUCCESS" in out)
    return ok, out


# 🛑 跨模块注入（dy-security）时必须先 install，再在本模块编译测试：
#    dy-app 依赖的是 ~/.m2 里的 dy-security jar，不是它的 target/classes。
#    这正是 S2-9 第一个真实缺陷的成因 —— S2-9 的 store:read 登记没进 jar，
#    于是 A3 对 therapist 恒 403，而源码里明明是登记过的。
def compile_all():
    cmd = [MVN, "-o", "-q", "-DskipTests", "install"]
    p = subprocess.run(cmd, cwd=SKEL, stdout=subprocess.PIPE,
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


def inject_and_check(name, path, old, new, test_filter, expect_method=None, also=None):
    """注入一处改动，跑测试，要求【红】且红在预期的那条【方法】上。

    :param expect_method: 预期变红的**测试方法名**（ASCII）。
        🛑 **必须是 ASCII**（方法名 / 权限码 / 枚举字面），不能是中文断言消息 ——
        本项目的 Maven 输出里 Maven 行是 GBK、surefire fork 的断言消息是 UTF-8，
        中文锚点会因解码而永远匹配不上，把"抓住了"读成"没抓住"。
        （见 `_decode` 文档与技能「失效模式 13」的新形态。）
        传 None ⇒ 只要求"红了"，不校验红在哪条 —— **这不构成证据**（失效模式 11），
        故能用方法名钉住的一律钉住。
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

        ok, out = compile_all()
        if not ok:
            print("  [ !! ] %s —— 注入后【编译失败】，这不是有效的反向验证" % name)
            print("         " + "\n         ".join(out.strip().splitlines()[-4:]))
            return False
        ok, out = run_tests(test_filter)
        if ok:
            print("  [FAIL] %s —— 注入后测试【仍然全绿】，说明这条防线没有测试守着" % name)
            return False
        hits = failed_methods(out)
        if expect_method:
            if expect_method in hits:
                print("  [ OK ] %s —— 红了，且红的正是预期那条: %s" % (name, expect_method))
                return True
            print("  [ !! ] %s —— 红了，但【不是】预期的那条。" % name)
            print("         预期: %s" % expect_method)
            print("         实际: %s" % (hits or "（未能从输出里解析出失败方法名）"))
            return False
        print("  [ OK ] %s —— 红了（失败方法: %s）" % (name, hits or "?"))
        return True
    finally:
        for pth, text in backups.items():
            write(pth, text)


def main():
    e2e = "DomainAEndpointsE2ETest"
    gate = "PermissionCodeRegistrationGateTest"
    domain = "BandVisibilityMatrixTest"
    e2e_and_gate = e2e + "," + gate

    auth_me_svc = os.path.join(IDENTITY, "service", "AuthMeService.java")
    store_svc = os.path.join(IDENTITY, "service", "StoreListService.java")
    store_ctrl = os.path.join(IDENTITY, "controller", "StoreListController.java")
    auth_ctrl = os.path.join(IDENTITY, "controller", "AuthMeController.java")
    store_repo = os.path.join(IDENTITY, "repository", "StoreRepository.java")
    declaration = os.path.join(IDENTITY, "domain", "AuthMeDeclaration.java")
    resolver = os.path.join(IDENTITY, "domain", "StoreScopeResolver.java")
    matrix = os.path.join(IDENTITY, "domain", "BandVisibilityMatrix.java")
    registry = os.path.join(SECURITY, "PermissionRegistry.java")

    touched = [auth_me_svc, store_svc, store_ctrl, auth_ctrl, store_repo,
               declaration, resolver, matrix, registry]
    snapshot = {p: open(p, "rb").read() for p in touched}

    print("=" * 78)
    print("反向验证 · 契约域 A 可见性声明与门店行级过滤（S2-9）")
    print("=" * 78)

    checks = []
    try:
        checks = _run_all_checks(
            checks, e2e, gate, domain, e2e_and_gate,
            auth_me_svc, store_svc, store_ctrl, auth_ctrl, store_repo,
            declaration, resolver, matrix, registry)
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


def _run_all_checks(checks, e2e, gate, domain, e2e_and_gate,
                    auth_me_svc, store_svc, store_ctrl, auth_ctrl, store_repo,
                    declaration, resolver, matrix, registry):

    # ==============================================================
    # RV-1：给 A3 摘掉 @RequirePermission("store:read")
    # ==============================================================
    # 契约 A3 的 x-callable-roles 不含 client，客户对门店台账没有数据面。
    # 摘掉权限码后，客户仍会被服务层 requireCallable 挡住（纵深防御的第二条）——
    # 故这条要看的是【客户被服务层挡住】而不是"权限层挡住"，
    # 以及【扫描器自证】那条会红（"必然存在 store:read"清单）。
    # 🛑 它证明的是"两道防线各自独立成立"，而不是"少一道就漏"。
    print("\nRV-1  摘掉 A3 的 @RequirePermission(\"store:read\")")
    checks.append(inject_and_check(
        "RV-1 A3 无权限码（扫描器必然存在清单应红）",
        store_ctrl,
        '    @RequirePermission("store:read")\n'
        '    @GetMapping("/stores")',
        '    @GetMapping("/stores")   // [INJECTED] 摘掉权限码',
        gate,
        "scanner_actually_sees_the_annotations_and_their_codes"))

    # ==============================================================
    # RV-2：把 A2 的"无身份报 401"改成 403
    # ==============================================================
    # 🛑 这是 S2-9 的【契约违约】注入：契约 A2 只声明 '200' + '401'，
    #    而"无 token 报 403"是一个非常自然、非常合理的写法（HTTP 常规里
    #    "权限不足"就是 403）。它躲过了单测（单测直调服务层时 TenantContext 为空，
    #    断言的是"抛了什么码"而非"HTTP 状态码"），
    #    只有真请求能把 403 这个契约未声明的状态码暴露出来。
    print("\nRV-2  A2 无身份改报 403（契约只声明 200/401 —— 这是契约违约）")
    checks.append(inject_and_check(
        "RV-2 A2 无身份报 403 而非 401",
        auth_me_svc,
        "            throw new BizException(ErrorCode.UNAUTHENTICATED,\n"
        "                    \"请求未携带身份 —— A2 是可见性档位的权威下发点，不设匿名通道。\"",
        "            throw new BizException(ErrorCode.VISIBILITY_DENIED,   // [INJECTED] 改报 403\n"
        "                    \"请求未携带身份 —— A2 是可见性档位的权威下发点，不设匿名通道。\"",
        e2e,
        "anonymous_request_gets_401_not_403"))

    # ==============================================================
    # RV-3：把 A2 的身份校验顺序颠倒（requireTenant 抢答）
    # ==============================================================
    # 🛑 这正是 S2-9 抓出的真缺陷的【原样复现】：describeCurrent 先调 requireTenant，
    #    无 token 时租户为空 ⇒ 抛 2003（403）⇒ 抢在 requireCallable 的 401 之前。
    #    两处都映射 403，故"调换顺序"这件事在源码上只是一行位移 ——
    #    而它决定了"无 token"到底报 401 还是 403。
    #    必须【同时】改回去：只改顺序不改回此处已被 RV-2 验过的 401 分支，
    #    会得到一条消息不同的失败，锚点就对不上了。
    print("\nRV-3  A2 身份/租户校验顺序颠倒（无 token 被 requireTenant 抢答成 403）")
    checks.append(inject_and_check(
        "RV-3 A2 顺序颠倒 ⇒ 无 token 报 403",
        auth_me_svc,
        "        String tokenRole = TenantContext.role();\n"
        "\n"
        "        // 第 ① 跳：token 角色 → 端角色（无角色即 401；角色不可信即 403）；并校验它在可调集合内\n"
        "        requireCallable(tokenRole);\n"
        "\n"
        "        // 🛑 顺序纪律：身份校验【必须先于】租户校验 —— 否则\"无 token\"会走到 requireTenant\n"
        "        //    并抛 403（2003），而契约 A2 只声明 '200' 与 '401'，403 是契约未声明的分支。\n"
        "        //    这两句曾经反序，被 DomainAEndpointsE2ETest 的真请求抓出（单测不会红）。\n"
        "        String tenantId = requireTenant();",
        "        String tokenRole = TenantContext.role();\n"
        "\n"
        "        // [INJECTED] 顺序颠倒：租户校验抢在身份校验之前\n"
        "        String tenantId = requireTenant();\n"
        "        requireCallable(tokenRole);",
        e2e,
        "anonymous_request_gets_401_not_403"))

    # ==============================================================
    # RV-4：给 A2 补一个【有主】的权限码
    # ==============================================================
    # 🛑 这是 PermissionCodeRegistrationGateTest 第②条（"无主码"）【抓不到】的那一类：
    #    customer:read 是有主的，故"无主码集合为空"仍然绿。
    #    只有真请求（客户 token 调 A2 ⇒ 客户不持 customer:read ⇒ 403）能抓住。
    #    这正是 E2E 存在的理由：它补的是"门禁的盲区"，而不是重复门禁的结论。
    print("\nRV-4  给 A2 补一个【有主】的权限码（门禁第②条抓不到，只有真请求能抓）")
    checks.append(inject_and_check(
        "RV-4 A2 被补上有主权限码（客户静默 403）",
        auth_ctrl,
        '    @GetMapping("/auth/me")\n'
        '    public Result<Map<String, Object>> me() {',
        '    @RequirePermission("customer:read")   // [INJECTED] 客户不持该码\n'
        '    @GetMapping("/auth/me")\n'
        '    public Result<Map<String, Object>> me() {',
        e2e,
        "client_can_read_its_own_declaration",
        also=[(auth_ctrl,
               "import com.diaoyuanyun.dy.common.result.Result;\n",
               "import com.diaoyuanyun.dy.common.result.Result;\n"
               "import com.diaoyuanyun.dy.security.permission.RequirePermission;\n")]))

    # ==============================================================
    # RV-5：给 A3 贴 @StaffOnly
    # ==============================================================
    # 契约 A3 是 x-client-forbidden: false 且【没有】x-client-explicitly-denied。
    # 两个键语义不同：前者说"小程序端是否生成调用面"，后者才是"仅 staff 可调"。
    # 据前者贴注解会让客户拿到注解式拒绝，其错误码与契约声明的 403 语义不同 ——
    # 而错误码是客户端分支的依据。
    #
    # 🛑 注入形态踩过坑（首次编译失败）：`@StaffOnly` 的 `clientDeniedFields()` **无默认值**，
    #    裸写 `@StaffOnly` 是编译错误 —— 必须带参数才能构成"编译通过但语义违规"的有效注入。
    #    这正是技能「失效模式 15」的连带要求：注入必须落在"能编译"的形态上。
    print("\nRV-5  给 A3 贴 @StaffOnly（把 x-client-forbidden 误读成 x-client-explicitly-denied）")
    checks.append(inject_and_check(
        "RV-5 A3 贴上 @StaffOnly",
        store_ctrl,
        '    @RequirePermission("store:read")\n'
        '    @GetMapping("/stores")',
        '    @RequirePermission("store:read")\n'
        '    @StaffOnly(clientDeniedFields = {"store_id"})   // [INJECTED]\n'
        '    @GetMapping("/stores")',
        gate,
        "identity_domain_keeps_client_out_and_auth_me_unannotated",
        also=[(store_ctrl,
               "import com.diaoyuanyun.dy.security.permission.RequirePermission;\n",
               "import com.diaoyuanyun.dy.security.permission.RequirePermission;\n"
               "import com.diaoyuanyun.dy.security.visibility.StaffOnly;\n")]))

    # ==============================================================
    # RV-6：把 page_size 越界从"拒"改成"夹逼到 100"
    # ==============================================================
    # 🛑 这是"静默改动用户输入"的典型：夹逼看起来更友好，代价是客户端
    #    无从知道自己拿到的是第几页 —— 它以为自己要了 500 条、实际拿到 100 条，
    #    于是把"还有 400 条"当成"只有 100 条"。这类缺陷不报错，只能靠断言钉住。
    print("\nRV-6  page_size 越界从『拒』改成『静默夹逼到 100』")
    checks.append(inject_and_check(
        "RV-6 page_size 越界被夹逼而非拒绝",
        store_svc,
        "        if (pageSize < 1 || pageSize > StoreListPage.MAX_PAGE_SIZE) {\n"
        "            throw new BizException(ErrorCode.VALIDATION_FAILED,",
        "        if (pageSize > StoreListPage.MAX_PAGE_SIZE) {\n"
        "            pageSize = StoreListPage.MAX_PAGE_SIZE;   // [INJECTED] 夹逼而非拒\n"
        "        }\n"
        "        if (pageSize < 1) {\n"
        "            throw new BizException(ErrorCode.VALIDATION_FAILED,",
        e2e,
        "out_of_range_page_size_is_rejected_not_clamped"))

    # ==============================================================
    # RV-7：把 total 改成"本页条数"（分页最典型的静默缺陷）
    # ==============================================================
    # 🛑 total 与 items 不同源时，客户端会算出错误的页数，翻过去拿空数组
    #    而【不报错】，只是列表看起来少了一截。这条注入把 total 从
    #    "过滤后的总数"改成"本页条数" —— 表现只有在 page_size < total 时才可见，
    #    故 E2E 的断言特意用 page_size=1（total 应为 3）。
    print("\nRV-7  total 退化为『本页条数』（与 items 不再同源）")
    checks.append(inject_and_check(
        "RV-7 total 取自本页条数",
        store_svc,
        "        int total = stores.countByScope(tenantId, rowLevel, anchor);",
        "        int total = items.size();   // [INJECTED] 本页条数，而非过滤后总数",
        e2e,
        "pagination_total_and_items_come_from_the_same_where_clause"))

    # ==============================================================
    # RV-8：让行级过滤"失效"（all 也走无附加条件——但这是对的；改成忽略范围）
    # ==============================================================
    # 🛑 注入形态：把 filterOf 的 REGION 分支改成返回空条件（等同于 all）。
    #    这会让区域督导看到全租户 3 家而非辖区 2 家 ——
    #    "过滤没生效"与"过滤生效"在别的数据集上可能给出相同数字，
    #    故 E2E 特意把三家门店分成两个辖区，使这个错误必然可被看见。
    print("\nRV-8  REGION 过滤失效（返回全租户门店，等同 all）")
    checks.append(inject_and_check(
        "RV-8 region 过滤被写成全租户",
        store_repo,
        '                return new ScopeFilter(" WHERE region_id = ?::uuid",\n'
        '                        List.of(anchor.regionId().toString()));',
        '                return new ScopeFilter("", List.of());   // [INJECTED] 退化为全租户',
        e2e,
        "row_level_filtering_is_actually_applied"))

    # ==============================================================
    # RV-9：把 row_level=all 的 store_ids 从"空数组"改成"全量枚举"
    # ==============================================================
    # 🛑 契约 A2 是"声明端点"，不下发业务字段。把全租户门店枚举进去会让
    #    一次登录相关调用退化成"拉全量组织台账"，且下发一份会过期的快照
    #    （门店新增后客户端拿到的仍是旧列表，不报错、只少显示几家）。
    print("\nRV-9  row_level=all 的 store_ids 从『空数组』改成『全量枚举』")
    checks.append(inject_and_check(
        "RV-9 all 时枚举全租户门店",
        resolver,
        "            case ALL:\n"
        "                // 🛑 刻意的空列表（\"全量、不枚举\"），理由见类注释第 3 节。\n"
        "                return List.of();",
        "            case ALL:\n"
        "                return List.of(\"11111111-1111-1111-1111-111111111111\");   // [INJECTED] 全量枚举",
        e2e,
        "headquarters_gets_empty_store_ids_on_purpose"))

    # ==============================================================
    # RV-10：放开客户的 refund_visibility（把它改成下发 false）
    # ==============================================================
    # 🛑 契约的 x-visible-to: [meridian, admin] 语义是"该键对他不存在"。
    #    下发 false 等于告诉客户"存在这个档位" —— 这是两个不同的渲染分支。
    #    断言用 has() 判（而非取值判），正是为了让这条注入可被抓住。
    print("\nRV-10 客户的 refund_visibility 从『不下发』改成『下发 false』")
    checks.append(inject_and_check(
        "RV-10 客户收到 refund_visibility=false",
        declaration,
        "        data.put(\"role\", role);\n"
        "        data.put(\"band_visibility\", bandVisibility.contractBandVisibility());\n"
        "        if (refundVisibility != null) {\n"
        "            data.put(\"refund_visibility\", refundVisibility);\n"
        "        }",
        "        data.put(\"role\", role);\n"
        "        data.put(\"band_visibility\", bandVisibility.contractBandVisibility());\n"
        "        data.put(\"refund_visibility\", Boolean.FALSE);   // [INJECTED] 对客户也下发\n"
        "        if (refundVisibility != null) {\n"
        "            data.put(\"refund_visibility\", refundVisibility);\n"
        "        }",
        e2e,
        "client_response_omits_the_two_role_scoped_keys_entirely"))

    # ==============================================================
    # RV-11：放开客户的 ③④ 硬锁（把客户行的 ③④ 改成 true）
    # ==============================================================
    # 🛑 这是"变更业务裁定"级别的事（config #43 行语义逐字：客户侧 ③④ 为硬约束、
    #    不得通过配置放开）。注入点在【矩阵构造期的硬锁断言】——
    #    即便把配置值改成 true，硬锁也应立刻拒绝启用，而不是静默放行。
    #    故本注入改的是 assertHardLocks 里对 ③ 的那一条断言（删掉它），
    #    然后单测侧"注入一行 ③=true 的配置应被拒"这条会红。
    print("\nRV-11 删掉客户 ③ 缺口原因的硬锁断言（放开方向）")
    checks.append(inject_and_check(
        "RV-11 客户 ③ 硬锁断言被删",
        matrix,
        "        assertCustomerInvisible(FieldGroup.GAP_REASON,",
        "        if (false) assertCustomerInvisible(FieldGroup.GAP_REASON,   // [INJECTED] 硬锁被停用",
        domain,
        "un_locking_gap_reason_for_customer_throws"))

    # ==============================================================
    # RV-12：让 store:read 从注册表消失（无主码 ⇒ A3 对全员 403）
    # ==============================================================
    # 🛑 这是本仓库已发生过三次的同类缺陷（角色码大小写分叉 / refund:* 从未登记 /
    #    S2-9 的 store:read 未进 jar）。它躲过单测的方式与前两次一模一样：
    #    端点单测直调控制器对象、不经过 PermissionInterceptor。
    #    本注入同时会被【门禁第②条】与【真请求 E2E】抓住 —— 两条都红才是对的。
    #
    # 🛑 注入形态踩过坑（首版把预期方法名对错了）：必须摘掉【全部五个角色】持有的
    #    store:read，才是真正的"无主码"。只摘 therapist 一处时 manager/area/hq/meridian
    #    仍持有它 ⇒ `every_require_permission_code_has_at_least_one_holder`【不应红】，
    #    那时红的是 E2E（therapist 拿 403）—— 但 therapist 只持 customer:read，
    #    故它俩红得"对但不对题"。**"无主码"这条断言的前提是"一个持有者都没有"**，
    #    注入必须满足该前提，否则锚点必然对不上（技能「失效模式 3」：锚点与注入不自洽）。
    print("\nRV-12  store:read 从注册表消失（A3 对全员 403）")
    checks.append(inject_and_check(
        "RV-12 store:read 无主",
        registry,
        '        rolePermissions.put("therapist", Set.of("customer:read", "store:read"));',
        '        rolePermissions.put("therapist", Set.of("customer:read"));   // [INJECTED] 摘掉 store:read',
        e2e_and_gate,
        "every_require_permission_code_has_at_least_one_holder",
        also=[
            (registry,
             'rolePermissions.put("manager", Set.of("customer:read", "customer:write", "store:read",',
             'rolePermissions.put("manager", Set.of("customer:read", "customer:write",'),
            (registry,
             'rolePermissions.put("area", Set.of("customer:read", "customer:write", "report:region", "store:read",',
             'rolePermissions.put("area", Set.of("customer:read", "customer:write", "report:region",'),
            (registry,
             'rolePermissions.put("hq", Set.of("customer:read", "customer:write", "report:region", "store:read",',
             'rolePermissions.put("hq", Set.of("customer:read", "customer:write", "report:region",'),
            (registry,
             'rolePermissions.put("meridian", Set.of("customer:read", "store:read",',
             'rolePermissions.put("meridian", Set.of("customer:read",'),
        ]))

    # ==============================================================
    # RV-13：让仓储的 count 侧重新踩"List 未展开成 varargs"
    # ==============================================================
    # 🛑 这是 S2-9 抓出的【第三个真缺陷】的原样复现：
    #    queryForObject(sql, Class, Object...) 的形参是 varargs，
    #    直接把 List 传进去不会展开，它被当作【单个】参数 ⇒ BadSqlGrammarException ⇒ 500。
    #    这个缺陷在 listByScope 里看不见（那边走 append(...) 返回 Object[]）。
    #    🛑 它证明的是"total 与 items 同源"这条纪律有测试守着，
    #    而不是"代码注释写得对"。
    print("\nRV-13  仓储 count 侧重新把 List 直接当 varargs（BadSqlGrammar ⇒ 500）")
    checks.append(inject_and_check(
        "RV-13 count 侧 List 未展开",
        store_repo,
        "                    Integer.class, filter.args().toArray());",
        "                    Integer.class, filter.args());   // [INJECTED] List 未展开成 varargs",
        e2e,
        "pagination_total_and_items_come_from_the_same_where_clause"))

    # ==============================================================
    # RV-14：让客户的 ① 可见被关掉（权利方向的误改）
    # ==============================================================
    # 🛑 硬锁 2 挡的是与硬锁 1 相反的方向：把客户自己的手环原始数据对他关掉。
    #    它只会让客户"看不到自己的手环数据"，没有任何合规理由 ——
    #    故这条断言必须独立存在，不能被"客户 ③④ 不可见"那条覆盖。
    print("\nRV-14  删掉客户 ① 恒可见的硬锁断言（权利方向）")
    checks.append(inject_and_check(
        "RV-14 客户 ① 可见硬锁断言被删",
        matrix,
        "        assertCustomerVisible(FieldGroup.RAW_DATA,",
        "        if (false) assertCustomerVisible(FieldGroup.RAW_DATA,   // [INJECTED] 硬锁被停用",
        domain,
        "locking_raw_data_away_from_customer_also_throws"))

    return checks


if __name__ == "__main__":
    sys.exit(main())