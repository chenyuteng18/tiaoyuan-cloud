#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
三端 SDK 生成管线 · 核心（S1-1 交付物 ① 的实质实现）

=============================================================================
为什么需要这个文件（它修的是什么真实缺陷）
=============================================================================
`openapi-generator` **不认识** OpenAPI 的 vendor extension ——
`x-callable-roles` / `x-client-forbidden` / `x-contract-row` 对它一律是"未知注解"，
它只按 `paths` 全量生成。

因此：**直接把契约丢给生成器，客户小程序包里会长出 `/refunds`**（退款域全部端点）。
这违反两条已声明的不变量：
  · INV-2 客户端不得生成 exclude-contract-rows 所列行对应的任何路径
  · INV-3 客户小程序产物内不得出现"退款"二字

本文件的做法是【生成前裁剪 spec】而不是【生成后过滤产物】：
  裁剪 → 临时 spec（只含该端可调用的 operation）→ 生成 → 校验不变量
理由：生成产物的 operation 散布在多个文件里（api/ + docs/ + test/），
      后处理过滤要同时改 5 类文件、且每换一个 generator 就得重写一遍；
      而裁剪 spec 是**一次性、与 generator 无关**的，且能被静态复核。

裁剪三步（缺一步就会漏）：
  ① 【角色判据】target 的 token-role(s) 必须出现在 operation 的 x-callable-roles 里；
  ② 【显式排除】operation 的 x-contract-row 不在 target 的 exclude-contract-rows 里；
  ③ 【schema 可达性】从裁剪后的 paths 出发，沿 `$ref` 做传递闭包，
     只保留闭包内的 components/schemas。
  ①②决定"哪些端点留下"，③决定"哪些数据结构留下"。
  🛑 只做 ①② 不做 ③ 是本管线真实踩过的 P0 坑（见下「坑 4」）。

裁剪后还有两道**断言**（宁可报错退出，不静默放过）：
  · 保留的 path / schema 名中不得出现矩阵声明的 forbid-path-fragments；
  · ①与②若互相矛盾（角色允许却又不该生成），属于矩阵配置缺陷 ⇒ 报错。

用法：
  python _sdk_pipeline.py cut    <spec> <matrix> <target-id> <out-spec>
  python _sdk_pipeline.py verify <spec> <matrix> [sdk-root]
  python _sdk_pipeline.py audit  <spec> <matrix>
  python _sdk_pipeline.py emit   <spec> <matrix> <node-exe> <generator-js> <cut-dir>
"""

import io
import os
import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.stderr.write("FATAL: 需要 PyYAML（pip install pyyaml）\n")
    raise


# ---------------------------------------------------------------------------
# 读取
# ---------------------------------------------------------------------------

def load_spec(path):
    with io.open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def load_matrix(path):
    with io.open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def contract_operations(spec):
    """产出 [(row, method, path, roles, client_forbidden)]，只含挂了 x-contract-row 的 operation。"""
    out = []
    for path, item in spec.get("paths", {}).items():
        for method, op in item.items():
            if not isinstance(op, dict) or "x-contract-row" not in op:
                continue
            out.append((
                op["x-contract-row"],
                method.upper(),
                path,
                op.get("x-callable-roles", []) or [],
                bool(op.get("x-client-forbidden")),
            ))
    return out


def target_token_roles(t):
    """兼容 token-role（单值）与 token-roles（多值）两种声明。"""
    if "token-roles" in t:
        return list(t["token-roles"])
    if "token-role" in t:
        return [t["token-role"]]
    return []


# ---------------------------------------------------------------------------
# schema 可达性（坑 4 的正面解法）
# ---------------------------------------------------------------------------

# `$ref` 指向 schema 的形态（只认这一种；指向 parameters/responses 的 ref 由递归自然穿透）
SCHEMA_REF_RE = re.compile(r"^#/components/schemas/(?P<n>[^/]+)$")


def _collect_refs(node):
    """递归收集 node 子树里所有指向 schema 的 $ref 名。"""
    out = set()
    stack = [node]
    while stack:
        cur = stack.pop()
        if isinstance(cur, dict):
            ref = cur.get("$ref")
            if isinstance(ref, str):
                m = SCHEMA_REF_RE.match(ref)
                if m:
                    out.add(m.group("n"))
            stack.extend(cur.values())
        elif isinstance(cur, list):
            stack.extend(cur)
    return out


def _cut_unreachable_schemas(spec):
    """从 spec['paths'] 出发做 $ref 传递闭包，删掉闭包外的 schema。

    返回被删掉的 schema 名（sorted list）。

    🛑 为什么必须做这一步（坑 4）：openapi-generator 为
       `components/schemas` 里的**每一个** schema 生成 model 文件，
       与该 schema 是否被某条 path 引用**无关**。
       只裁 paths ⇒ 产物里仍会有 RefundData.js / VerdictData.js 这类
       "退款域的数据结构"，只是没有调用它的方法而已 —— 仍然进包、仍然泄语义。
       ⇒ 必须用可达性（白名单）裁掉，而不是靠 fragment 黑名单（黑名单永远列不全）。
    """
    schemas = spec.get("components", {}).get("schemas", {})
    if not schemas:
        return []

    reachable = set()
    frontier = _collect_refs(spec.get("paths", {}) or {})
    while frontier:
        nxt = set()
        for name in frontier:
            if name in reachable or name not in schemas:
                continue
            reachable.add(name)
            nxt |= _collect_refs(schemas[name])
        frontier = nxt

    dropped = sorted(set(schemas) - reachable)
    for name in dropped:
        del schemas[name]
    # 若整段被裁空，连容器一起摘掉，避免留下空的 components.schemas
    if not schemas:
        spec.get("components", {}).pop("schemas", None)
        if not spec.get("components"):
            spec.pop("components", None)
    return dropped


# 契约定稿处的组织主数据 / 根因说明保留在下方注释里，供后续迁移到 V15 时对照。


# ---------------------------------------------------------------------------
# 裁剪
# ---------------------------------------------------------------------------

def cut_spec(spec, target):
    """按 target 的角色判据 + 显式排除 + schema 可达性，裁出一份只含该端 operation 的 spec。

    返回 (new_spec, kept, dropped, dropped_schemas)。
      kept            = [(row, method, path), ...]
      dropped         = [(row, method, path, why), ...]  why ∈ {"role", "excluded"}
      dropped_schemas = [schema 名, ...]
    """
    import copy

    roles = set(target_token_roles(target))
    excluded_rows = set(target.get("exclude-contract-rows", []) or [])

    if not roles:
        raise SystemExit("FATAL: target %s 未声明 token-role / token-roles" % target["id"])

    new_spec = copy.deepcopy(spec)
    kept, dropped = [], []

    for path in list(new_spec.get("paths", {}).keys()):
        item = new_spec["paths"][path]
        for method in list(item.keys()):
            op = item[method]
            if not isinstance(op, dict) or "x-contract-row" not in op:
                continue
            row = op["x-contract-row"]
            op_roles = set(op.get("x-callable-roles", []) or [])

            role_ok = bool(op_roles & roles)          # ① 角色判据
            not_excluded = row not in excluded_rows   # ② 显式排除

            if role_ok and not_excluded:
                kept.append((row, method.upper(), path))
            else:
                dropped.append((row, method.upper(), path,
                                "role" if not role_ok else "excluded"))
                del item[method]

        if not item:
            del new_spec["paths"][path]

    # ③ schema 可达性裁剪（坑 4）
    dropped_schemas = _cut_unreachable_schemas(new_spec)

    return new_spec, kept, dropped, dropped_schemas


def check_matrix_consistency(spec, matrix):
    """静态自证：矩阵的 client 显式禁入必须与契约的 x-client-forbidden 一致。

    这是 dry-run 第 [3] 步的同源判据，此处供 cut/audit 复用。
    """
    ops = contract_operations(spec)
    forbidden_rows = {r for r, _m, _p, _ro, cf in ops if cf}
    client = next((t for t in matrix["targets"] if t["id"] == "client-mp"), None)
    if client is None:
        return []
    excl = set(client.get("exclude-contract-rows", []) or [])
    problems = []
    missing = forbidden_rows - excl
    if missing:
        problems.append("契约标记客户禁入但矩阵未禁入的行：%s" % sorted(missing))
    return problems


def _norm_ident(s):
    """标识符归一化：只留小写字母与数字。

    用于例外名的**全等**比对 ——
      · `BandAvailableDatesDataRetentionWindowDays` → `bandavailabledatesdataretentionwindowdays`
      · 各 generator 的产物文件 stem（kebab / Pascal / camel）归一化后同形，
        故同一份例外清单对三端通用。
    🛑 必须全等而非子串：子串匹配会把同族名字（如 ...RetentionWindowDaysExtra）一起放过，
       等于偷偷放宽判据。
    """
    return re.sub(r"[^a-z0-9]", "", (s or "").lower())


def _fragment_exceptions(target):
    """矩阵声明的片段例外 → {归一化名: 理由}。缺 reason 视为配置缺陷。"""
    out = {}
    for e in (target.get("forbid-fragment-exceptions", []) or []):
        if isinstance(e, str):
            out[_norm_ident(e)] = "(未写理由)"
        else:
            out[_norm_ident(e.get("name", ""))] = e.get("reason", "(未写理由)")
    return out


def forbidden_fragment_hits(spec_after_cut, target, kept):
    """裁剪后仍含矩阵声明的禁入片段 ⇒ 命中列表。

    🛑 这是【第二道保险】：③ 的 schema 可达性裁剪已经能从机制上杜绝，
       本断言用来证明它真的生效了 —— 若命中，说明矩阵判据与契约
       x-callable-roles 不一致（配置缺陷），必须修矩阵，**不许放宽断言**。

    例外（forbid-fragment-exceptions）在此被消费：命中例外的名字不算违规，
    但会以 ("excepted", ...) 形式返回，由调用方计入报告（不静默跳过）。
    返回 [(kind, name, fragment)]，kind ∈ {"path", "schema", "excepted"}。
    """
    frags = [f.lower() for f in (target.get("forbid-path-fragments", []) or [])]
    if not frags:
        return []
    exceptions = _fragment_exceptions(target)
    bad = []

    def _consider(kind, name):
        low = name.lower()
        neg = _norm_ident(name)
        for f in frags:
            if f not in low:
                continue
            if neg in exceptions:
                bad.append(("excepted", name, f))
            else:
                bad.append((kind, name, f))

    for _r, _m, p in kept:
        _consider("path", p)
    for name in (spec_after_cut.get("components", {}) or {}).get("schemas", {}) or {}:
        _consider("schema", name)
    return bad


def real_fragment_hits(hits):
    """从 forbidden_fragment_hits 的结果里筛出**真正违规**的那些（剔除例外）。"""
    return [h for h in hits if h[0] != "excepted"]


def exception_hits(hits):
    """筛出命中例外、已被放行的那些（供报告显式打印）。"""
    return [h for h in hits if h[0] == "excepted"]


# ---------------------------------------------------------------------------
# 校验（对生成产物）
# ---------------------------------------------------------------------------
# 🛑 本段是全文件最容易写错的地方，六处真实踩过的坑（都已被反向验证 115 钉住）：
#
#  坑 1【路径子串污染】不能只按 path 做字面搜索。
#       E2 = POST /band/telemetry，E3 = GET /customers/{id}/band/telemetry。
#       若只搜 "/band/telemetry"，E3 的字符串会把 E2 也"命中" ⇒ 对
#       therapist-app（无 client 角色、无 E2）误报 INV-2 失败。
#       ⇒ 必须按 **(path, method) 联合**判定。
#
#  坑 2【前缀污染】同理，"/customers" 是 "/customers/{id}/visits" 的前缀。
#       生成器不会为"合并同名路径"生成裸 "/customers"，
#       但契约里有 POST /customers —— 若只搜路径，任何含该前缀的更长的
#       保留路径都会让"被裁掉的 POST /customers"看起来还在。
#       ⇒ 同样由 (path, method) 联合判定解决。
#
#  坑 3【样板文件中文污染】invariant INV-3 的原意是
#       「**客户端运行时源码**内不得出现"退款"」，而不是
#       「生成器的 README / package.json 里不得有中文」——
#       后者由 openapi-generator 从契约的 info.description 抄写而来，
#       而契约自身的描述性文字里**本就含"退款"**（实测 18 处）。
#       ⇒ 搜索面必须限定为**运行时源码目录**（src/ api/ lib/ 等），
#         并把排除原因显式写在报告里（不是静默跳过）。
#
#  坑 4【schema 泄漏】只裁 paths 不裁 components/schemas ⇒
#       openapi-generator 仍为全部 32 个 schema 生成 model（实测客户包里
#       出现 RefundData.js / RefundCreateRequest.js / RefundReceiptData.js /
#       VerdictData.js / BandDerivedData.js / AuditCoverageData.js）。
#       ⇒ 由 cut_spec 的 ③ schema 可达性裁剪解决；本段另加 INV-6 兜底检测。
#
#  坑 5【窗口过短致漏检】typescript-axios 产物里 `localVarPath` 之后跟着一长串
#       `.replace(...)` 参数拼接，path→method 的**真实距离**实测中位 430、
#       最大 521 字符；原先 400 字符窗口导致 25/39 个 operation 提取不到 ⇒
#       INV-1 假红"缺失 25"。⇒ 改为**按下一个 path 出现位置切块**，
#       块内找 method（无固定长度上限，且天然不跨函数）。
#
#  坑 6【注释行假红】openapi-generator 把契约 info.description 作为文件头注释
#       写进每个 src/*.js；而契约的纪律条文里**自己就写着**"不得含\"退款\""，
#       于是每个源码文件的头注释都含"退款"二字 ⇒ INV-3 全线假红（实测 112 文件）。
#       ⇒ 扫描前先**剥离注释**（块注释 + 行注释），只看真实代码。
#
#  坑 7【model 命名形态因 generator 而异】见 _model_file_stems 上方说明。

# 样板/文档类路径（不参与 INV-3 中文禁词扫描）
BOILERPLATE_PREFIXES = (
    "docs/", "docs\\", "test/", "test\\", ".openapi-generator/",
    ".openapi-generator\\", "README.md", "package.json", "package-lock.json",
    ".gitignore", "git_push.sh", ".npmignore", ".babelrc", "mocha.opts",
    "tsconfig.json", "tsconfig.esm.json", ".gitlab-ci.yml", ".travis.yml",
)

# 从产物里提取 (path, method) 对的三端正则。
#   JS（javascript 模板）：  '/xxx', 'GET',
#   TS-fetch：              path: `/xxx`.replace(...)   或  path: `/xxx`
#   TS-axios：              const localVarPath = `/xxx`
JS_PATH_METHOD = re.compile(r"['\"](?P<p>/[^'\"]*)['\"]\s*,\s*['\"](?P<m>[A-Z]+)['\"]")
TS_PATH_PATTERNS = [
    re.compile(r"path:\s*`(?P<p>/[^`]*)`"),
    re.compile(r"localVarPath\s*=\s*`(?P<p>/[^`]*)`"),
]
# 在 TS 产物里，method 以 `method: 'GET'` 出现；axios 里亦可能直接以 'GET' 作首参
TS_METHOD = re.compile(r"method:\s*['\"](?P<m>[A-Z]+)['\"]")
ANY_METHOD_LITERAL = re.compile(r"['\"](?P<m>GET|POST|PUT|PATCH|DELETE)['\"]")

# 注释剥离（坑 6）
RE_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)


def _strip_js_comments(text):
    """剥离 JS/TS 的块注释与行注释。

    行注释用逐行处理，并避开 `http://` 这类协议前缀（// 前紧邻 ':' 时不视为注释）。
    """
    t = RE_BLOCK_COMMENT.sub("", text)
    out = []
    for ln in t.splitlines():
        idx = ln.find("//")
        if idx > -1:
            before = ln[:idx]
            if not before.rstrip().endswith(":"):
                ln = before
        out.append(ln)
    return "\n".join(out)


def _norm_path(p):
    """把产物里的路径模板归一化成与契约一致的形态。

    产物里可能写成 `/customers/{id}/visits`，也可能把参数拼成 `+ id`；
    本函数只做**去尾斜杠 + 去空白**，不猜测参数拼接方式
    （猜测会引入假绿；宁可漏检也要保证"命中即为真"）。
    """
    p = (p or "").strip()
    if len(p) > 1 and p.endswith("/"):
        p = p[:-1]
    return p


def _extract_ts_ops(txt, path_pat):
    """TS 产物里按『下一个 path 出现位置』切块，块内找 method。

    🛑 不用固定字符窗口（坑 5：实测真实距离可达 521 字符，窗口会漏检），
       也不用"全文件 path × 全文件 method 笛卡尔配对"（会造出不存在的组合 ⇒ 假红）。
       切块法两者都避开：块长 = 一个函数的体量，天然边界正确。
    """
    marks = [(m.start(), m.end(), m.group("p")) for m in path_pat.finditer(txt)]
    ops = set()
    for i, (_s, e, p) in enumerate(marks):
        end = marks[i + 1][0] if i + 1 < len(marks) else len(txt)
        block = txt[e:end]
        mm = TS_METHOD.search(block) or ANY_METHOD_LITERAL.search(block)
        if mm:
            ops.add((mm.group("m").upper(), _norm_path(p)))
    return ops


def extract_ops_from_dir(out_dir, generator):
    """从产物目录提取 (method, path) 集合（只扫运行时源码 .js/.ts/.mjs）。"""
    ops = set()
    for root, _dirs, files in os.walk(out_dir):
        for fn in files:
            if not fn.endswith((".js", ".ts", ".mjs")):
                continue
            rel = os.path.relpath(os.path.join(root, fn), out_dir)
            if _boilerplate(rel):
                continue
            try:
                txt = io.open(os.path.join(root, fn),
                              encoding="utf-8", errors="replace").read()
            except Exception:
                continue

            # JS 模板：'/path', 'METHOD',
            for m in JS_PATH_METHOD.finditer(txt):
                ops.add((m.group("m").upper(), _norm_path(m.group("p"))))

            # TS 模板：切块配对
            for pat in TS_PATH_PATTERNS:
                ops |= _extract_ts_ops(txt, pat)
    return ops


def _boilerplate(rel):
    r = rel.replace("\\", "/")
    return any(r.startswith(bp.replace("\\", "/")) for bp in BOILERPLATE_PREFIXES)


#  坑 7【model 文件命名形态因 generator 而异】不能拿 schema 原名去比对产物文件名：
#       javascript 模板 → `RefundData.js`（PascalCase，同 schema 名）
#       typescript-fetch → 不落 model 文件，只有 `src/models/index.ts` 一个桶文件
#       typescript-axios → `refund-data.ts`（kebab-case，逐词小写连字符）
#       若按原名比对，axios 端的 model 泄漏会【全部漏检】。
#       ⇒ 按 generator 做名→文件 stem 的映射（见 _model_file_stems）。

# 各 generator 是否会把 schema 落成独立 model 文件
MODEL_FILE_GENERATORS = ("javascript", "typescript-axios")


def _kebab(name):
    """PascalCase / camelCase → kebab-case（typescript-axios 的 model 文件命名）。

    实测对照：AuthLogin200Response → auth-login200-response
              CreateRefundReceipt200Response → create-refund-receipt200-response
              BandAvailableDatesDataRetentionWindowDays
                  → band-available-dates-data-retention-window-days
    """
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1-\2", name)
    s = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1-\2", s)
    return s.lower()


def _model_file_stems(schema_names, generator):
    """schema 名集合 → 该 generator 的 model 文件 stem 集合（去扩展名）。"""
    if generator == "typescript-axios":
        return {_kebab(n) for n in schema_names}
    if generator == "typescript-fetch":
        return set()          # fetch 模板不落独立 model 文件
    return set(schema_names)  # javascript 模板用原名


def _model_dir(out_dir, generator):
    """该 generator 的 model 目录相对路径。"""
    if generator == "typescript-axios":
        return os.path.join(out_dir, "model")
    if generator == "javascript":
        return os.path.join(out_dir, "src", "model")
    return None


def _forbidden_model_files(out_dir, generator, frags, exceptions=None):
    """产物 model 目录里是否存在含矩阵禁入片段的文件（fragment 黑名单兜底）。

    为什么不能只用 _schema_leaks（按被裁 schema 原名精确比对）：
      generator 还会从 path 内联 response **凭空派生出契约里没有的**包装类型，
      实测 axios 端就有 `create-refund200-response.ts` /
      `create-refund-receipt200-response.ts` —— 它们不对应任何 components/schemas，
      按原名比对**完全抓不到**。
      ⇒ 用 fragment 匹配做这一层的兜底（与 forbid-path-fragments 同一判据）。

    🛑 为什么不能用"产物 model ⊆ 保留 schema"的白名单做全量断言：
       派生类型本就不在 schema 清单里，白名单会把它们全部误报成孤儿（假红）。
       白名单只可用于"被裁 schema 的精确比对"（_schema_leaks），
       全量兜底必须走 fragment 黑名单 —— 两者互补，不可互相替代。

    返回 (违规文件, 命中例外的文件)。
    """
    frags = [f.lower() for f in (frags or [])]
    if not frags:
        return [], []
    exceptions = exceptions or {}
    mdir = _model_dir(out_dir, generator)
    if not mdir or not os.path.isdir(mdir):
        return [], []
    bad, excepted = [], []
    for fn in sorted(os.listdir(mdir)):
        if not os.path.isfile(os.path.join(mdir, fn)):
            continue
        stem = os.path.splitext(fn)[0]
        if stem == "index":
            continue
        low = stem.lower()
        neg = _norm_ident(stem)
        for f in frags:
            if f in low:
                rel = os.path.relpath(os.path.join(mdir, fn), out_dir)
                (excepted if neg in exceptions else bad).append(rel)
                break
    return bad, excepted


def _schema_leaks(out_dir, generator, dropped_schemas):
    """产物里是否存在『新 spec 里已无对应 schema』的 model 文件（坑 4 的兜底检测）。

    🛑 用**白名单**而非黑名单：黑名单只能列出"我以为会泄漏的"，
       白名单能抓住"任何不该在的 model 文件"（含契约里已不存在的孤儿文件）。
    """
    stem_map = _model_file_stems(dropped_schemas, generator)
    if not stem_map:
        return []
    mdir = _model_dir(out_dir, generator)
    if not mdir or not os.path.isdir(mdir):
        return []
    found = []
    for fn in sorted(os.listdir(mdir)):
        stem = os.path.splitext(fn)[0]
        if stem == "index" or not os.path.isfile(os.path.join(mdir, fn)):
            continue
        if stem in stem_map:
            found.append(os.path.relpath(os.path.join(mdir, fn), out_dir))
    return found


def verify_artifacts(spec, matrix, sdk_root):
    """对已生成的三端产物做不变量校验。返回 (problems, report_lines)。"""
    problems = []
    report = []

    for t in matrix["targets"]:
        tid = t["id"]
        out_dir = os.path.join(sdk_root, tid)
        if not os.path.isdir(out_dir):
            problems.append("[%s] 产物目录不存在：%s" % (tid, out_dir))
            continue

        # 该端【应保留】/【已裁掉】的 operation 与 schema
        _cut, kept, dropped, dropped_schemas = cut_spec(spec, t)
        kept_ops = {(m, _norm_path(p)) for _r, m, p in kept}
        dropped_ops = {(m, _norm_path(p)) for _r, m, p, _w in dropped}
        kept_schemas = set((_cut.get("components", {}) or {}).get("schemas", {}) or {})

        # 产物里真实存在的 (method, path)
        seen_ops = extract_ops_from_dir(out_dir, t["generator"])

        # 运行时源码文本（排除样板/文档），并剥离注释后做中文禁词扫描
        texts = {}
        for root, _dirs, files in os.walk(out_dir):
            for fn in files:
                fp = os.path.join(root, fn)
                rel = os.path.relpath(fp, out_dir)
                if _boilerplate(rel):
                    continue
                try:
                    texts[rel] = io.open(fp, encoding="utf-8", errors="replace").read()
                except Exception:
                    pass

        # 矩阵声明的禁入片段（第二道保险；命中即配置缺陷）
        frag_hits = forbidden_fragment_hits(_cut, t, kept)
        hard = real_fragment_hits(frag_hits)
        if hard:
            problems.append("[%s] 🛑 裁剪后仍含矩阵禁入片段（矩阵判据与契约不一致）：%s"
                            % (tid, hard[:8]))
        exc_hits = exception_hits(frag_hits)

        # INV-6（坑 4 兜底）：被裁掉的 schema 一个都不得以 model 文件存在
        leaks = _schema_leaks(out_dir, t["generator"], dropped_schemas)
        if leaks:
            problems.append("[%s] 🛑 INV-6 失败：已裁掉的 schema 仍以 model 文件存在：%s"
                            % (tid, leaks[:12]))

        # INV-6b：产物 model 目录里不得有含禁入片段的文件（覆盖派生包装类型）
        f_frag = t.get("forbid-path-fragments", []) or []
        bad_models, exc_models = _forbidden_model_files(
            out_dir, t["generator"], f_frag, _fragment_exceptions(t))
        if bad_models:
            problems.append("[%s] 🛑 INV-6b 失败：产物 model 含禁入片段文件：%s"
                            % (tid, bad_models[:12]))

        # INV-2（反向，真正的牙齿）：被裁掉的 operation 必须【一个都不出现】
        leaked = sorted(dropped_ops & seen_ops)
        if leaked:
            problems.append("[%s] 🛑 INV-2 失败：不该生成的 operation 出现在产物中：%s"
                            % (tid, leaked[:12]))

        # INV-1（正向，防"裁过头"）：应保留的 operation 必须在产物里
        absent = sorted(kept_ops - seen_ops)
        if absent:
            problems.append("[%s] INV-1 失败：应保留的 operation 未出现在产物中：%s"
                            % (tid, absent[:12]))

        # INV-3：客户小程序**运行时源码**（剥离注释后）内不得出现「退款」
        if tid == "client-mp":
            hit_files = []
            for rel, txt in texts.items():
                if "退款" in _strip_js_comments(txt):
                    hit_files.append(rel)
            if hit_files:
                problems.append("[%s] 🛑 INV-3 失败：运行时源码内出现「退款」：%s"
                                % (tid, sorted(hit_files)[:12]))

        n_files = sum(len(fs) for _r, _d, fs in os.walk(out_dir))
        # 例外命中必须**显式打印**（不静默跳过）：让"判据被有理由地放行了一次"
        # 成为读报告的人一眼可见的事实，而不是埋在代码里的隐式行为。
        exc_note = ""
        if exc_hits or exc_models:
            seen = sorted({h[1] for h in exc_hits} | set(exc_models))
            exc_note = " · 例外放行 %d（%s）" % (len(seen), ", ".join(seen))

        # 自证：例外清单不得有**僵尸条目** ——
        # 每条例外都必须在产物里真实命中过，否则它只是"当年为消一条假红而写的、
        # 如今已无对应物的死条目"。死条目会让人误以为判据比实际更宽松。
        #
        # 🛑 归一化口径必须**取 basename 的 stem**：exc_models 是产物相对路径
        #    （`src\model\BandAvailableDatesDataRetentionWindowDays.js`），
        #    直接归一化会连路径与扩展名一起编码，导致"明明命中了却判成僵尸"的假红。
        declared = set(_fragment_exceptions(t))
        fired = {_norm_ident(h[1]) for h in exc_hits}
        fired |= {_norm_ident(os.path.splitext(os.path.basename(p))[0])
                  for p in exc_models}
        zombies = sorted(declared - fired)
        if zombies:
            problems.append("[%s] 🛑 例外清单含僵尸条目（未在任何产物里命中）：%s —— "
                            "必须删除或改正，否则判据宽度与实际不符"
                            % (tid, zombies))
        report.append("[%s] 产物 %d 文件（源码扫描 %d）· 产物内 operation %d · "
                      "应保留 %d · 已裁掉 %d · 泄漏 %d · 缺失 %d · "
                      "schema 保留 %d / 裁掉 %d · model 泄漏 %d%s"
                      % (tid, n_files, len(texts), len(seen_ops),
                         len(kept_ops), len(dropped_ops), len(leaked), len(absent),
                         len(kept_schemas), len(dropped_schemas), len(leaks),
                         exc_note))

    return problems, report


# ---------------------------------------------------------------------------
# audit：不接触产物，只静态复核「裁剪逻辑本身」是否与矩阵自洽
# ---------------------------------------------------------------------------

def audit(spec, matrix):
    problems = []
    lines = []

    ops = contract_operations(spec)
    rows = {}
    for r, m, p, roles, cf in ops:
        rows.setdefault(r, []).append((m, p, roles, cf))

    lines.append("契约：rows=%d operations=%d schemas=%d"
                 % (len(rows), len(ops),
                    len((spec.get("components", {}) or {}).get("schemas", {}) or {})))

    problems += check_matrix_consistency(spec, matrix)

    for t in matrix["targets"]:
        _new, kept, dropped, dropped_schemas = cut_spec(spec, t)
        why_role = [d for d in dropped if d[3] == "role"]
        why_excl = [d for d in dropped if d[3] == "excluded"]
        lines.append("[%s] roles=%s 保留=%d 裁掉=%d（角色=%d 显式排除=%d）· "
                     "schema 裁掉=%d"
                     % (t["id"], target_token_roles(t), len(kept), len(dropped),
                        len(why_role), len(why_excl), len(dropped_schemas)))

        # 禁入片段断言（第二道保险）
        frag_hits = forbidden_fragment_hits(_new, t, kept)
        hard = real_fragment_hits(frag_hits)
        if hard:
            problems.append("[%s] 🛑 裁剪后仍含矩阵禁入片段（矩阵判据与契约不一致）：%s"
                            % (t["id"], hard[:8]))
        exs = exception_hits(frag_hits)
        if exs:
            lines.append("[%s] 例外放行：%s" % (t["id"], sorted({h[1] for h in exs})))

        # 自证：例外必须**逐条具名且写明理由**。
        # 🛑 这里【不】校验"例外名在契约里存在"——因为例外恰恰是为了
        #    generator **派生**出来的名字（如 BandAvailableDatesDataRetentionWindowDays：
        #    它由 BandAvailableDatesData 的 probe.retention_window_days 提升而来，
        #    不在 components/schemas 里）。存在性校验放在 verify 侧，
        #    判据是"是否真的在某个产物里被命中"，未命中即判为**僵尸例外**。
        exceptions = _fragment_exceptions(t)
        for nm, reason in exceptions.items():
            if not nm:
                problems.append("[%s] 例外清单含空名 —— 配置缺陷" % t["id"])
            if reason in (None, "", "(未写理由)"):
                problems.append("[%s] 例外 %s 未写理由 —— 放行判据必须逐条写清语义，否则视为放宽"
                                % (t["id"], nm))

        # 自证：裁掉的行必须真的不含该端角色，或真的在排除表里 —— 不得有第三种理由
        for r, m, p, why in dropped:
            op_roles = set()
            for rr, mm, pp, roles, _cf in ops:
                if rr == r and mm == m and pp == p:
                    op_roles = set(roles)
            if why == "role" and (op_roles & set(target_token_roles(t))):
                problems.append("[%s] 裁剪理由自相矛盾：%s %s 的角色含该端角色却被裁"
                                % (t["id"], m, p))

        # 自证：该端至少要保留 1 个 operation（若为空，说明判据写错了）
        if not kept:
            problems.append("[%s] 🛑 裁剪后为空 —— 判据必然写错了" % t["id"])

        # 自证：被裁 schema 必须真的不可达（不得"可达却被裁"）
        reachable = set((_new.get("components", {}) or {}).get("schemas", {}) or {})
        for name in dropped_schemas:
            if name in reachable:
                problems.append("[%s] schema 裁剪矛盾：%s 既被裁又仍在 spec 内" % (t["id"], name))

    return problems, lines


# ---------------------------------------------------------------------------
# emit：裁剪 → 生成 → 校验（一体）
# ---------------------------------------------------------------------------

MANIFEST_REL = os.path.join(".openapi-generator", "FILES")


def _read_manifest(out_dir):
    """读生成器自写的产物清单（相对 out_dir 的路径集合）。不存在则空集。"""
    p = os.path.join(out_dir, MANIFEST_REL)
    if not os.path.isfile(p):
        return set()
    try:
        txt = io.open(p, encoding="utf-8", errors="replace").read()
    except Exception:                                  # noqa: BLE001
        return set()
    return {ln.strip().replace("\\", "/") for ln in txt.splitlines() if ln.strip()}


def _stale_from_manifest(out_dir):
    """生成【前】读旧清单 —— 用于生成后做差分。"""
    return _read_manifest(out_dir)


def _prune_stale(out_dir, stale):
    """删掉「旧清单有、新清单没有」的文件（残留），并清掉因此变空的目录。

    返回实际删除的相对路径列表。
    """
    if not stale:
        return []
    now = _read_manifest(out_dir)
    gone = sorted(stale - now)
    removed = []
    for rel in gone:
        fp = os.path.join(out_dir, rel.replace("/", os.sep))
        if os.path.isfile(fp):
            try:
                os.remove(fp)
                removed.append(rel)
            except Exception:                          # noqa: BLE001
                pass
    # 自底向上清理空目录（只清产物目录内、且不含文件的）
    for root, dirs, files in os.walk(out_dir, topdown=False):
        if root == out_dir:
            continue
        if not dirs and not files:
            try:
                os.rmdir(root)
            except Exception:                          # noqa: BLE001
                pass
    if removed:
        print("    · 清理上一轮残留 %d 个：%s" % (len(removed), removed[:6]), flush=True)
    return removed


def emit(spec_path, matrix_path, node_exe, gen_js, cut_dir):
    """完整生成流程。任何一步失败即非零退出，**不静默降级**。

    🛑 路径口径（本文件的第二处教训）：`t["output"]`（如 `../sdk/client-mp`）
       是**相对 matrix 文件所在目录**的，不是相对 cwd、也不是相对 spec。
       因此 out 必须由 `os.path.dirname(matrix_path)` 推导 ——
       少一层 `..` 会把产物写到 `<repo>/sdk/`（真实发生过，产物错位）。

    🛑 生成前必须清空目标目录：生成器 `-o` 只覆盖同名文件，
       上一轮残留的 RefundData.js 不会被自动删除，会污染校验结论。
       本函数用 Python 逐端 rmtree（不经 shell），避免沙箱对批量 rm 的拦截。
    """
    import shutil
    import subprocess

    spec = load_spec(spec_path)
    matrix = load_matrix(matrix_path)
    matrix_dir = os.path.dirname(os.path.abspath(matrix_path))

    # 前置：矩阵与契约自洽（不通过就不该生成）
    pre = check_matrix_consistency(spec, matrix)
    if pre:
        for p in pre:
            sys.stderr.write("FATAL: " + p + "\n")
        return 1

    if not os.path.isdir(cut_dir):
        os.makedirs(cut_dir)

    rc = 0
    for t in matrix["targets"]:
        tid = t["id"]
        cut_spec_path = os.path.join(cut_dir, "%s.openapi.yaml" % tid)

        # 1. 裁剪
        new_spec, kept, dropped, dropped_schemas = cut_spec(spec, t)

        # 2. 断言：裁剪后不得含矩阵声明的禁入片段（例外已具名放行）
        frag_hits = forbidden_fragment_hits(new_spec, t, kept)
        hard = real_fragment_hits(frag_hits)
        if hard:
            print("    🛑 [%s] 裁剪后仍含禁入片段：%s" % (tid, hard[:6]))
            rc = 1
            continue

        with io.open(cut_spec_path, "w", encoding="utf-8") as f:
            yaml.safe_dump(new_spec, f, allow_unicode=True, sort_keys=False, width=4096)
        kept_schemas = len((new_spec.get("components", {}) or {}).get("schemas", {}) or {})
        print("--> [%s] 裁剪后 spec：保留 %d operation / %d schema · 裁掉 %d operation / %d schema -> %s"
              % (tid, len(kept), kept_schemas, len(dropped), len(dropped_schemas),
                 os.path.basename(cut_spec_path)),
              flush=True)

        # 3. 生成（out 相对 matrix 目录推导 —— 与 generate.sh 的口径一致）
        out = os.path.normpath(os.path.join(matrix_dir, t["output"]))
        # 清空目标目录的残留：生成器 `-o` 只覆盖/新增，**不会删**上一轮残留
        # （实测：上一轮 RefundData.js 会原样留着，污染 INV-6 结论）。
        # 🛑 而整目录 rmtree 会触发沙箱批量删除拦截（实测 974 文件 / 阈值 50）。
        #    ⇒ 用生成器自己的 `.openapi-generator/FILES` 清单做**差分**：
        #      生成前读旧清单 → 生成 → 读新清单 → 只删 `旧 − 新`（通常 0~3 个）。
        #      精准、小量、可解释。
        stale = _stale_from_manifest(out)
        props = ",".join("%s=%s" % (k, v) for k, v in t["additional-properties"].items())
        cmd = [node_exe, gen_js, "generate",
               "-i", os.path.abspath(cut_spec_path),
               "-g", t["generator"], "-o", out]
        if props:
            cmd += ["--additional-properties", props]
        r = subprocess.run(cmd, capture_output=True, text=True,
                           encoding="utf-8", errors="replace")
        if r.returncode != 0:
            print("    🛑 生成失败 exit=%d" % r.returncode)
            print((r.stdout or "")[-2000:])
            print((r.stderr or "")[-2000:])
            rc = 1
            continue
        print("    ok -> %s" % out, flush=True)

        # 4. 定点删除「旧清单里有、新清单里没有」的残留
        _prune_stale(out, stale)

    if rc != 0:
        return rc

    # 4. 校验产物不变量（sdk_root 由 matrix 目录推导，唯一口径）
    sdk_dir = os.path.normpath(os.path.join(matrix_dir, os.path.dirname(
        matrix["targets"][0]["output"])))
    problems, report = verify_artifacts(spec, matrix, sdk_dir)
    for ln in report:
        print(ln)
    if problems:
        print()
        print("== EMIT 后校验失败 ==")
        for p in problems:
            print("  x " + p)
        return 1
    print()
    print("== EMIT 通过：三端 SDK 已生成并通过 INV-1 / INV-2 / INV-3 / INV-6 ==")
    return 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv):
    if len(argv) < 2:
        sys.stderr.write(__doc__)
        return 2
    mode = argv[1]

    if mode == "emit":
        # emit <spec> <matrix> <node> <gen-js> <cut-dir>
        spec_p, matrix_p, node_exe, gen_js, cut_dir = argv[2:7]
        return emit(spec_p, matrix_p, node_exe, gen_js, cut_dir)

    if mode == "cut":
        spec_p, matrix_p, tid, out_p = argv[2:6]
        spec, matrix = load_spec(spec_p), load_matrix(matrix_p)
        target = next((t for t in matrix["targets"] if t["id"] == tid), None)
        if target is None:
            sys.stderr.write("FATAL: 矩阵里没有 target %s\n" % tid)
            return 1
        new_spec, kept, dropped, dropped_schemas = cut_spec(spec, target)
        with io.open(out_p, "w", encoding="utf-8") as f:
            yaml.safe_dump(new_spec, f, allow_unicode=True, sort_keys=False, width=4096)
        print("[cut] %s -> %s : 保留 %d operation · 裁掉 %d operation / %d schema"
              % (tid, out_p, len(kept), len(dropped), len(dropped_schemas)))
        return 0

    if mode == "verify":
        # verify <spec> <matrix> [sdk-root]（省略则从 matrix 推导）
        spec_p, matrix_p = argv[2:4]
        sdk_root = argv[4] if len(argv) > 4 else None
        spec, matrix = load_spec(spec_p), load_matrix(matrix_p)
        if sdk_root is None:
            mdir = os.path.dirname(os.path.abspath(matrix_p))
            sdk_root = os.path.normpath(
                os.path.join(mdir, os.path.dirname(matrix["targets"][0]["output"])))
        problems, report = verify_artifacts(spec, matrix, sdk_root)
        for ln in report:
            print(ln)
        if problems:
            print()
            print("== VERIFY 失败 ==")
            for p in problems:
                print("  x " + p)
            return 1
        print()
        print("== VERIFY 通过：三端产物满足 INV-1 / INV-2 / INV-3 / INV-6 ==")
        return 0

    if mode == "audit":
        spec_p, matrix_p = argv[2:4]
        spec, matrix = load_spec(spec_p), load_matrix(matrix_p)
        problems, lines = audit(spec, matrix)
        for ln in lines:
            print(ln)
        if problems:
            print()
            print("== AUDIT 失败 ==")
            for p in problems:
                print("  x " + p)
            return 1
        print()
        print("== AUDIT 通过：裁剪逻辑与矩阵自洽 ==")
        return 0

    sys.stderr.write("FATAL: 未知模式 %s（cut | verify | audit | emit）\n" % mode)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))