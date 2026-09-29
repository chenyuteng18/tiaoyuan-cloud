#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
三端前端工程 · 契约驱动端点生成器
=============================================================================

唯一真源 = ``contract/sdk-generator/_cut/<end>.openapi.yaml``
（该文件由 ``contract/sdk-generator/_sdk_pipeline.py`` 从 ``contract/openapi-v1.0.0.yaml``
按 ``generator-matrix.yaml`` 的角色声明裁剪而来 —— 反向验证 ``verification/115_*`` 守护它的牙齿。）

为什么必须"生成"而不是"手写"
=============================================================================
本仓已有过一次同型失败（记在 ``client-package/api/clientPaths.js`` 文件头，并酿成
S1-8 FACE 1 的立项动机）：一份**手写**的"客户端可达路径清单"会腐烂 —— 原 8 条里
只有 2 条与冻结契约相符，其余是"看起来对的名字"和"契约里根本不存在的路径"。

那份清单的失效方式是**漏项与错名**，而漏项最危险：``assertPathAllowed`` 是**运行时
强制**（不在白名单即抛错），所以"漏一条"等于"客户这个功能直接不可用"，且**不会变红**
（门禁当时只校验"引注为真"，不校验"契约里允许 client 的都列上了"）。

故本生成器把方向反过来：端点清单**不由人写**，而是从裁剪后的契约机械转录，
并自带 ``--check`` 模式 —— 契约一改而产物未重跑，检查即红。
这正是"把主张换成事实"这条纪律在前端工程上的落点。

产出
=============================================================================
  client-mp      -> frontends/client-mp/miniprogram/contract/endpoints.js   (CommonJS)
  therapist-app  -> frontends/therapist-app/src/contract/endpoints.ts       (ESM + const断言)
  admin-web      -> frontends/admin-web/src/contract/endpoints.ts           (ESM + const断言)

🛑 角色准入的口径
=============================================================================
一个 operation 属于某端，当且仅当它的 ``x-callable-roles`` 与该端的 token-roles
**有交集**。token-roles 取自 ``generator-matrix.yaml``：

  client-mp     : [client]
  therapist-app : [therapist, meridian]
  admin-web     : [admin]

🛑 可见性仍是服务端的事（X-1）：本文件只回答"这一端可以调用哪些 operation"，
**不**回答"某个角色能不能看见某个字段" —— 后者由服务端 403 与 A2 档位解算决定。

用法
=============================================================================
    python frontends/tools/gen-endpoints.py            # 写出三端产物
    python frontends/tools/gen-endpoints.py --check    # 只校验产物与契约一致（不写）

退出码
=============================================================================
    0 一致（或已写出）      1 产物与契约不一致 / 缺件      2 环境缺 PyYAML
"""

from __future__ import annotations

import argparse
import io
import os
import re
import sys

try:
    import yaml
except ImportError:  # pragma: no cover
    sys.stderr.write("MISCONFIGURED: PyYAML is required (exit 2)\n")
    raise SystemExit(2)

HERE = os.path.dirname(os.path.abspath(__file__))
SKELETON_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
CUT_DIR = os.path.abspath(
    os.path.join(SKELETON_ROOT, "..", "contract", "sdk-generator", "_cut")
)

# token-roles 逐条转录自 contract/sdk-generator/generator-matrix.yaml。
# 🛑 它们是"声明"，不得在此处自行增删；改角色声明请先改矩阵，再重跑本脚本。
TARGETS = [
    {
        "id": "client-mp",
        "label": "端 C · 客户小程序",
        "token_roles": ["client"],
        "out": os.path.join(SKELETON_ROOT, "frontends", "client-mp",
                           "miniprogram", "contract", "endpoints.js"),
        "flavor": "cjs",
    },
    {
        "id": "therapist-app",
        "label": "端 B · 调理师 / 经络师 APP",
        "token_roles": ["therapist", "meridian"],
        "out": os.path.join(SKELETON_ROOT, "frontends", "therapist-app",
                            "src", "contract", "endpoints.ts"),
        "flavor": "ts",
    },
    {
        "id": "admin-web",
        "label": "端 A · 管理员 Web",
        "token_roles": ["admin"],
        "out": os.path.join(SKELETON_ROOT, "frontends", "admin-web",
                            "src", "contract", "endpoints.ts"),
        "flavor": "ts",
    },
]

HTTP_METHODS = ("GET", "POST", "PUT", "PATCH", "DELETE")

# 契约条目的行标识写在 summary 开头，形如 "A1 登录" / "D5-b 方案查阅" / "E5 日期探测…"。
ROW_RE = re.compile(r"^([A-Z]\d+(?:-[a-z])?)\s")

# -----------------------------------------------------------------------------
# operation 级契约元信息 —— 对【界面】有约束力，必须转出
# -----------------------------------------------------------------------------
# 🛑 为什么这些必须转出（本仓第 54 条系统性缺陷）
# -----------------------------------------------------------------------------
# 初版只转出 id / row / method / path / grantedRoles，**丢掉了下列 4 类 x- 键**。
# 它们在契约里对界面有硬约束，丢掉之后前端只能"凭记忆"实现：
#   · x-row-scope          —— 同一端点对不同 admin 子档位的行级范围不同
#                              （F3「门店负责人仅本店、区域督导仅辖区、总部全量」）
#   · x-super-admin-only   —— I7 仅超管（tenant 级，不跨租户）
#   · x-ruling-pending     —— G4 审批角色白名单系推断、**待裁定**（不得当定论用）
#   · x-frontier           —— I1~I7「占位待冻结」（不得当已冻结契约用）
# 丢掉的表现是**静默**的：生成物看起来完全正常，--check 也绿 ——
# 只有人肉读契约才发现"界面少了一道约束"。与第 50 条同族：
# **"契约写下的约束"与"前端拿到的约束"是两件事**，中间任何一环丢项都不会报错。
OP_X_KEYS = (
    ("x-row-scope", "rowScope", "str"),
    ("x-super-admin-only", "superAdminOnly", "bool"),
    ("x-ruling-pending", "rulingPending", "str"),
    ("x-frontier", "frontier", "str"),
    ("x-idempotency-key", "idempotencyKeySpec", "str"),
)


def load_operations(end_id: str):
    """从裁剪后的契约读出该端的全部 HTTP operation（不管角色）。"""
    path = os.path.join(CUT_DIR, "%s.openapi.yaml" % end_id)
    if not os.path.isfile(path):
        raise SystemExit("MISCONFIGURED: missing cut contract: %s" % path)
    doc = yaml.safe_load(io.open(path, encoding="utf-8").read())
    ops = []
    for p, item in (doc.get("paths") or {}).items():
        for method, op in (item or {}).items():
            m = method.upper()
            if m not in HTTP_METHODS:
                continue
            summary = (op.get("summary") or "").strip()
            row_match = ROW_RE.match(summary)
            entry = {
                "method": m,
                "path": p,
                "operationId": op.get("operationId"),
                "roles": list(op.get("x-callable-roles") or []),
                "row": row_match.group(1) if row_match else "",
                "summary": summary,
                "tags": list(op.get("tags") or []),
            }
            for yaml_key, field, kind in OP_X_KEYS:
                raw = op.get(yaml_key)
                if raw is None:
                    continue
                if kind == "bool":
                    entry[field] = bool(raw)
                else:
                    entry[field] = str(raw)
            ops.append(entry)
    ops.sort(key=lambda o: (o["path"], o["method"]))
    return ops


def load_role_expansion(end_id: str, token_roles):
    """
    从裁剪契约顶层的 ``x-roles`` 读出**本端角色 → token-role** 的展开表。

    🛑 为什么必须从契约读，而不是在生成器或前端手写
    ---------------------------------------------------------------------------
    ``x-roles.admin.token-role = [manager, area, hq]`` 是契约的声明；
    前端若手写一份 ``{admin: ['manager','area','hq']}``，就又造出了第二份清单
    （本仓第 46 条同型：契约一改就漂移，且漂移不会让门禁变红）。
    ⇒ 一律从契约现取。

    🛑 一处必须如实登记的落差
    ---------------------------------------------------------------------------
    ``admin`` 的展开值是 **3 个 token-role**，而本端产物里每个端点的
    ``grantedRoles`` 只有 ``["admin"]`` 一项（裁剪管线判的是"契约角色 ∩ 本端
    token-roles"，其中本端 token-roles 取自矩阵的 ``token-role: admin``）。
    两者**不是同一层**：前者是"admin 这个角色由哪几种账号构成"，
    后者是"这个端点允许 admin 这一角色"。故本函数单独产出 ``ROLE_EXPANSION``，
    首页与它**不得**混算（混算会得出"39 端点 × 3 子角色"这种假数量）。
    """
    path = os.path.join(CUT_DIR, "%s.openapi.yaml" % end_id)
    doc = yaml.safe_load(io.open(path, encoding="utf-8").read())
    roles_block = doc.get("x-roles") or {}
    out = {}
    for end_role, spec in roles_block.items():
        if not isinstance(spec, dict):
            continue
        tr = spec.get("token-role")
        if tr is None:
            continue  # store_customer_service：token-role 为 null（无端、无接口）
        tokens = tr if isinstance(tr, list) else [tr]
        out[end_role] = {
            "tokens": [str(t) for t in tokens],
            "end": spec.get("end") or "",
            "display": spec.get("display") or "",
        }
    # 只保留与本端 token-roles 相关的那几条（其余端不关心）+ 如实标注"本端可见"
    tokens_of_end = set(token_roles)
    trimmed = {}
    for end_role, spec in out.items():
        related = bool(set(spec["tokens"]) & tokens_of_end) or end_role in tokens_of_end
        if related:
            trimmed[end_role] = spec
    return trimmed


def load_api_base_path(end_id: str):
    """读裁剪契约的 ``servers[0].url`` —— 即真实 URL 的前缀（契约 §2.0 Base Path）。

    🛑 为什么必须转出（本仓第 56 条：跨端"隐式协议"缺口）
    ---------------------------------------------------------------------------
    契约的 ``paths`` 键是 ``/auth/me``，而**真实 URL 是 ``/api/v1/auth/me``** ——
    前缀由 ``servers.url`` 承载（契约第 43 行，description 逐字写着
    「Base Path（契约 §2.0 全局约定）」）。后端 ``AuthMeController`` 的类注释
    也把这件事写成了纪律：「本类**不得**只写 ``@RequestMapping("/auth/me")``：
    那会让端点在 ``/auth/me`` 落地，而三端 UI 按契约请求 ``/api/v1/auth/me``
    会拿到 404 —— 且不会有任何测试红」。

    🛑 后端那一侧已有机械守卫（``EndpointCoverageLedgerTest`` 把契约 path
    与真实 Spring 注解路由逐条比对），**但前端这一侧没有**：
    三端出站 URL 一律是 ``env.baseUrl + endpoint.path``，而 ``path`` **不含**
    ``/api/v1`` —— 前缀被写进了三份 env 的**注释**里（端 C env.js 逐字写着
    「契约 servers.url = /api/v1，故基址只到网关根」），是一句**不可执行**的话。
    运维按注释把 baseUrl 配成网关根 ⇒ 三端全量 404 ⇒ 而
    ``check:a`` / ``check:x3`` / ``build-check`` / tsc / vite **全部仍绿**
    （它们从不发真实请求）。这正是本仓反复出现的形态：契约写下的约束与
    各端拿到的约束是两件事，中间丢项**不报错**。

    故把 ``servers[0].url`` 机械转录为生成物常量，三端 URL 一律由
    ``BASE + path`` 构成 —— 前缀从"注释里的约定"变成"生成物里的常量"。

    🛑 第 57 条的补充（本条修法自己的一个漏洞，已订正）
    ---------------------------------------------------------------------------
    初版实现**没有从 ``servers[0].url`` 取值**，而是把 ``base_path`` 当作入参
    从 ``x-global-conventions['base-path']`` 读出后，**再把字面量手抄进模板**
    （``lines.append("const API_BASE_PATH = %s;" % js_literal(api_base_path))``）
    并配一句注释「Value = /api/v1」。于是：**契约改 Base Path ⇒ 生成物照旧**，
    而门禁只断言"常量存在"、不断言"值等于契约" ⇒ 值漂移静默通过。
    这正是第 52 条（判据太宽 ⇒ 假绿）的形态，且是本条修法自己引入的。
    订正：本函数读 ``servers[0].url`` 作为 **权威值**（与契约冻结门禁的
    ``servers 必须包含 /api/v1`` 断言同源），并与 ``x-global-conventions['base-path']``
    **互查** —— 两者不一致即 ``MISCONFIGURED`` 退出。于是全链只剩一个真源。
    """
    path = os.path.join(CUT_DIR, "%s.openapi.yaml" % end_id)
    doc = yaml.safe_load(io.open(path, encoding="utf-8").read())
    servers = doc.get("servers") or []
    if not servers or not isinstance(servers[0], dict) or not servers[0].get("url"):
        raise SystemExit(
            "MISCONFIGURED: %s 的 servers[0].url 缺失 —— 契约 §2.0 的 Base Path "
            "是三端拼 URL 的唯一依据，不得为空。" % path)
    url = str(servers[0]["url"])
    conv = doc.get("x-global-conventions") or {}
    declared = conv.get("base-path")
    if declared is not None and str(declared) != url:
        raise SystemExit(
            "MISCONFIGURED: %s 的 servers[0].url=%r 与 x-global-conventions['base-path']=%r "
            "不一致 —— 两处都是「Base Path」的声明，必须同值。" % (path, url, declared))
    return url


def load_api_protocol(end_id: str):
    """读裁剪契约的 ``x-api-protocol`` —— 跨端共同遵守的**协议片段**（第 57 条）。

    🛑 为什么必须转出（第 57 条：另三类跨端隐式协议）
    ---------------------------------------------------------------------------
    第 56 条只修了 URL 前缀。但"跨端共同遵守的协议片段"还有三类同样只写在
    注释 / prose / 各处手抄的字面量里：

      · **鉴权头名 + 令牌前缀** —— 三端出站层各自手写
        ``headers.Authorization = `Bearer ${token}```（端 A/B）、
        ``header.Authorization = 'Bearer ' + token``（端 C）；
      · **幂等头名** —— 三端各自手写 ``'Idempotency-Key'``；
      · **追踪头名** —— 端 A/B ``res.headers.get('X-Trace-Id')``、
        端 C ``res.header['X-Trace-Id']``。**这个头连 prose 里都没有**：
        契约全域零声明，它只活在后端 ``TraceIdFilter.HEADER`` 常量与前端
        字面量里（实测 6 处）。
      · **信封成功码** —— 三端各自写 ``body.code !== 0`` / ``body.code === 0``。

    失效方式与第 56 条完全同型：契约（或后端）改一处 ⇒ 各处静默分叉 ⇒
    全量 401 / 幂等去重失效 / 留痕断链，而 tsc / vite / 全部门禁**全部仍绿**。

    故把 ``x-api-protocol`` 机械转录为生成物常量，三端出站层一律引用常量。
    缺失即 ``MISCONFIGURED`` 退出 —— 与 ``servers[0].url`` 同等对待，不给兜底默认值
    （默认值会让"契约里没有这条约定"变成"悄悄用了旧约定"，比报错更坏）。
    """
    path = os.path.join(CUT_DIR, "%s.openapi.yaml" % end_id)
    doc = yaml.safe_load(io.open(path, encoding="utf-8").read())
    proto = doc.get("x-api-protocol")
    if not isinstance(proto, dict):
        raise SystemExit(
            "MISCONFIGURED: %s 缺根级 x-api-protocol —— 跨端协议片段（鉴权/幂等/追踪头名、"
            "令牌前缀、信封成功码）必须是可机械读取的结构化事实，不得只写在 prose 里。" % path)
    required = ("auth-header", "auth-scheme", "tenant-header", "trace-header",
                "idempotency-header", "envelope-fields", "envelope-ok-code")
    missing = [k for k in required if proto.get(k) in (None, "")]
    if missing:
        raise SystemExit(
            "MISCONFIGURED: %s 的 x-api-protocol 缺键 %s —— 三端出站层无法机械取用。"
            % (path, missing))
    fields = proto.get("envelope-fields")
    if not isinstance(fields, list) or not fields:
        raise SystemExit("MISCONFIGURED: %s 的 x-api-protocol.envelope-fields 必须是非空列表。" % path)
    ok_code = proto.get("envelope-ok-code")
    if not isinstance(ok_code, int):
        raise SystemExit(
            "MISCONFIGURED: %s 的 x-api-protocol.envelope-ok-code 必须是整数，实为 %r。"
            % (path, ok_code))
    pag = proto.get("pagination")
    if not isinstance(pag, dict):
        raise SystemExit(
            "MISCONFIGURED: %s 的 x-api-protocol 缺 pagination 结构化块 —— 越界【处置】"
            "（reject-400 vs 静默夹逼）必须写死，否则同一契约下两个列表端点会各走一路"
            "且两端 200 响应体、tsc、门禁全绿（本仓第 58 条）。" % path)
    pag_required = ("request-fields", "response-fields", "page-min", "page-size-min",
                    "page-size-max", "page-size-default", "over-range-policy", "over-range-error")
    pag_missing = [k for k in pag_required if pag.get(k) in (None, "")]
    if pag_missing:
        raise SystemExit(
            "MISCONFIGURED: %s 的 x-api-protocol.pagination 缺键 %s —— 生成物无法机械取用。"
            % (path, pag_missing))
    policy = str(pag["over-range-policy"])
    if policy != "reject-400":
        raise SystemExit(
            "MISCONFIGURED: %s 的 x-api-protocol.pagination.over-range-policy=%r —— "
            "本仓唯一合法取值是 'reject-400'（越界直接拒，不夹逼）。静默夹逼会让客户端"
            "拿到 100 条却以为请求了 101 条，且无从察觉。" % (path, policy))
    return {
        "auth_header": str(proto["auth-header"]),
        "auth_scheme": str(proto["auth-scheme"]),
        "tenant_header": str(proto["tenant-header"]),
        "trace_header": str(proto["trace-header"]),
        "idempotency_header": str(proto["idempotency-header"]),
        "envelope_fields": [str(f) for f in fields],
        "envelope_ok_code": ok_code,
        "idempotency_window_hours": proto.get("idempotency-window-hours"),
        "envelope_rule": str(proto.get("envelope-rule") or ""),
        "pagination": {
            "request_fields": [str(f) for f in pag["request-fields"]],
            "response_fields": [str(f) for f in pag["response-fields"]],
            "page_min": int(pag["page-min"]),
            "page_size_min": int(pag["page-size-min"]),
            "page_size_max": int(pag["page-size-max"]),
            "page_size_default": int(pag["page-size-default"]),
            "over_range_policy": policy,
            "over_range_error": str(pag["over-range-error"]),
        },
    }


def allowed_roles_of(ops, token_roles):
    """按 token-roles 与 x-callable-roles 的交集筛选本端可用 operation。"""
    tokens = set(token_roles)
    out = []
    for o in ops:
        if set(o["roles"]) & tokens:
            entry = dict(o)
            # 本端实际可用的角色（交集），保序按契约声明顺序
            entry["grantedRoles"] = [r for r in o["roles"] if r in tokens]
            out.append(entry)
    return out


def js_literal(value: str) -> str:
    return '"%s"' % value.replace("\\", "\\\\").replace('"', '\\"')


# 契约元信息字段 → 输出顺序（只输出确实存在的，缺省即不写该键）
OPTIONAL_FIELDS = (
    ("rowScope", "str"),
    ("superAdminOnly", "bool"),
    ("rulingPending", "str"),
    ("frontier", "str"),
    ("idempotencyKeySpec", "str"),
)


def _opt_lines(e, indent):
    """按 OPTIONAL_FIELDS 顺序输出存在值（缺省不写，保持生成物最小）。"""
    out = []
    for field, kind in OPTIONAL_FIELDS:
        if field not in e:
            continue
        v = e[field]
        if kind == "bool":
            out.append("%s%s: %s," % (indent, field, "true" if v else "false"))
        else:
            out.append("%s%s: %s," % (indent, field, js_literal(v)))
    return out


def _render_role_expansion(role_expansion, indent, ts):
    """角色展开表的字面量（逐条从契约现取，不手写）。"""
    out = []
    for end_role in sorted(role_expansion):
        spec = role_expansion[end_role]
        toks = ", ".join(js_literal(t) for t in spec["tokens"])
        out.append("%s%s: {" % (indent, js_literal(end_role)))
        out.append("%s  tokens: Object.freeze([%s])," % (indent, toks))
        out.append("%s  end: %s," % (indent, js_literal(spec["end"])))
        out.append("%s  display: %s," % (indent, js_literal(spec["display"])))
        out.append("%s}," % indent)
    return out


def _pag(proto):
    """取分页结构化块（gen 后的 proto dict 内）—— 少写一层 .get 噪音。"""
    return proto["pagination"]


def _render_protocol_cjs(proto):
    """跨端协议片段常量（CommonJS 形态）。逐条转录自契约 x-api-protocol。"""
    return [
        "/**",
        " * 跨端协议片段（契约 x-api-protocol 的机械转录）—— **出站层一律引用本组常量**。",
        " *",
        " * 🛑 为什么不能在各端出站层手写字面量（本仓第 57 条）",
        " *    头名 / 令牌前缀 / 信封成功码此前在三个端各写一遍。",
        " *    `X-Trace-Id` 更彻底：契约里【一个字都没有】，只活在后端 TraceIdFilter",
        " *    与三端字面量里（实测 6 处）。契约或后端改一处 ⇒ 各处静默分叉 ⇒",
        " *    全量 401 / 幂等去重失效 / 留痕断链，而 tsc / 构建 / 门禁全绿。",
        " *    故本组常量是唯一来源，出站层必须用 PROTOCOL.*（由 build-check ④d 守）。",
        " */",
        "const PROTOCOL = Object.freeze({",
        "  AUTH_HEADER: %s," % js_literal(proto["auth_header"]),
        "  AUTH_SCHEME: %s," % js_literal(proto["auth_scheme"]),
        "  TENANT_HEADER: %s," % js_literal(proto["tenant_header"]),
        "  TRACE_HEADER: %s," % js_literal(proto["trace_header"]),
        "  IDEMPOTENCY_HEADER: %s," % js_literal(proto["idempotency_header"]),
        "  ENVELOPE_FIELDS: Object.freeze([%s]),"
        % ", ".join(js_literal(f) for f in proto["envelope_fields"]),
        "  ENVELOPE_OK_CODE: %d," % proto["envelope_ok_code"],
        "  PAGINATION: Object.freeze({",
        "    PAGE_FIELD: %s," % js_literal(_pag(proto)["request_fields"][0]),
        "    PAGE_SIZE_FIELD: %s," % js_literal(_pag(proto)["request_fields"][1]),
        "    PAGE_MIN: %d," % _pag(proto)["page_min"],
        "    PAGE_SIZE_MIN: %d," % _pag(proto)["page_size_min"],
        "    PAGE_SIZE_MAX: %d," % _pag(proto)["page_size_max"],
        "    PAGE_SIZE_DEFAULT: %d," % _pag(proto)["page_size_default"],
        "    OVER_RANGE_POLICY: %s," % js_literal(_pag(proto)["over_range_policy"]),
        "    OVER_RANGE_ERROR: %s," % js_literal(_pag(proto)["over_range_error"]),
        "  }),",
        "});",
        "",
    ]


def _render_protocol_ts(proto):
    """跨端协议片段常量（ESM/TS 形态）。含类型，使"忘改"变成编译错误。"""
    return [
        "/**",
        " * 跨端协议片段（契约 x-api-protocol 的机械转录）—— **出站层一律引用本组常量**。",
        " *",
        " * 🛑 为什么不能在各端出站层手写字面量（本仓第 57 条）",
        " *    头名 / 令牌前缀 / 信封成功码此前在三个端各写一遍。",
        " *    `X-Trace-Id` 更彻底：契约里【一个字都没有】，只活在后端 TraceIdFilter",
        " *    与三端字面量里（实测 6 处）。契约或后端改一处 ⇒ 各处静默分叉 ⇒",
        " *    全量 401 / 幂等去重失效 / 留痕断链，而 tsc / 构建 / 门禁全绿。",
        " *    故本组常量是唯一来源，出站层必须用 PROTOCOL.*（由 build-check ④d 守）。",
        " */",
        "export interface ProtocolSpec {",
        "  /** 鉴权头名（契约 x-api-protocol.auth-header）。 */",
        "  readonly AUTH_HEADER: string;",
        "  /** 令牌前缀（契约 x-api-protocol.auth-scheme）—— 拼 `${SCHEME} ${token}`。 */",
        "  readonly AUTH_SCHEME: string;",
        "  /** 租户一致性校验头（服务端仅校验、不采纳其值）。 */",
        "  readonly TENANT_HEADER: string;",
        "  /** 留痕头 —— 与响应体 trace_id 并存，用于日志双向检索。 */",
        "  readonly TRACE_HEADER: string;",
        "  /** 幂等键请求头名（写请求必带）。 */",
        "  readonly IDEMPOTENCY_HEADER: string;",
        "  /** 响应信封字段全集。 */",
        "  readonly ENVELOPE_FIELDS: readonly string[];",
        "  /** 信封成功码 —— 契约 §2.0 逐字「code != 0 时 data 为空」，故成功码为 0。 */",
        "  readonly ENVELOPE_OK_CODE: number;",
        "  /** 分页协议（契约 x-api-protocol.pagination）—— 上下界与【越界处置】。 */",
        "  readonly PAGINATION: PaginationSpec;",
        "}",
        "",
        "export interface PaginationSpec {",
        "  /** 页码参数名（契约 pagination.request-fields[0]）—— 出站拼接用。 */",
        "  readonly PAGE_FIELD: string;",
        "  /** 每页条数参数名（契约 pagination.request-fields[1]）。 */",
        "  readonly PAGE_SIZE_FIELD: string;",
        "  /** page 下界（< 该值一律 400，不静默纠正）。 */",
        "  readonly PAGE_MIN: number;",
        "  /** page_size 下界。 */",
        "  readonly PAGE_SIZE_MIN: number;",
        "  /** page_size 上界（> 该值一律 400，不夹逼 —— 唯一合法处置见 OVER_RANGE_POLICY）。 */",
        "  readonly PAGE_SIZE_MAX: number;",
        "  /** 未传 page_size 时的缺省值。 */",
        "  readonly PAGE_SIZE_DEFAULT: number;",
        "  /** 越界处置。本仓唯一合法取值 'reject-400'（越界直接拒，不得静默夹逼）。 */",
        "  readonly OVER_RANGE_POLICY: 'reject-400';",
        "  /** 越界对应的错误码名（契约 pagination.over-range-error）。 */",
        "  readonly OVER_RANGE_ERROR: string;",
        "}",
        "",
        "export const PROTOCOL: ProtocolSpec = Object.freeze({",
        "  AUTH_HEADER: %s," % js_literal(proto["auth_header"]),
        "  AUTH_SCHEME: %s," % js_literal(proto["auth_scheme"]),
        "  TENANT_HEADER: %s," % js_literal(proto["tenant_header"]),
        "  TRACE_HEADER: %s," % js_literal(proto["trace_header"]),
        "  IDEMPOTENCY_HEADER: %s," % js_literal(proto["idempotency_header"]),
        "  ENVELOPE_FIELDS: Object.freeze([%s]),"
        % ", ".join(js_literal(f) for f in proto["envelope_fields"]),
        "  ENVELOPE_OK_CODE: %d," % proto["envelope_ok_code"],
        "  PAGINATION: Object.freeze({",
        "    PAGE_FIELD: %s," % js_literal(_pag(proto)["request_fields"][0]),
        "    PAGE_SIZE_FIELD: %s," % js_literal(_pag(proto)["request_fields"][1]),
        "    PAGE_MIN: %d," % _pag(proto)["page_min"],
        "    PAGE_SIZE_MIN: %d," % _pag(proto)["page_size_min"],
        "    PAGE_SIZE_MAX: %d," % _pag(proto)["page_size_max"],
        "    PAGE_SIZE_DEFAULT: %d," % _pag(proto)["page_size_default"],
        "    OVER_RANGE_POLICY: %s," % js_literal(_pag(proto)["over_range_policy"]),
        "    OVER_RANGE_ERROR: %s," % js_literal(_pag(proto)["over_range_error"]),
        "  } as PaginationSpec),",
        "});",
        "",
    ]


def render_cjs(target, entries, spec_version, role_expansion, api_base_path, proto):
    lines = []
    lines.append("/**")
    lines.append(" * GENERATED FILE — DO NOT EDIT.")
    lines.append(" *")
    lines.append(" * 真源: contract/sdk-generator/_cut/%s.openapi.yaml" % target["id"])
    lines.append(" * 生成: frontends/tools/gen-endpoints.py")
    lines.append(" * 重跑: python frontends/tools/gen-endpoints.py")
    lines.append(" * 校验: python frontends/tools/gen-endpoints.py --check")
    lines.append(" *")
    lines.append(" * 这一份是【端 %s】的可用端点清单，逐条机械转录自契约 ——" % target["label"])
    lines.append(" * 一条 operation 属于本端，当且仅当它的 x-callable-roles 与")
    lines.append(" * 本端 token-roles (%s) 有交集。" % ", ".join(target["token_roles"]))
    lines.append(" *")
    lines.append(" * 🛑 手改本文件会在下次 --check 时被判红；要改请改契约后重跑生成器。")
    lines.append(" * 🛑 本文件只回答「本端可以调用哪些端点」，不回答「某个角色看见哪些字段」")
    lines.append(" *    —— 可见性永远由服务端 403 与 A2 档位解算决定（X-1）。")
    lines.append(" */")
    lines.append("'use strict';")
    lines.append("")
    lines.append("const CONTRACT_VERSION = %s;" % js_literal(spec_version))
    lines.append("")
    lines.append("/**")
    lines.append(" * 契约 servers[0].url（§2.0 Base Path）—— **三端拼 URL 的唯一前缀**。")
    lines.append(" *")
    lines.append(" * 🛑 出站 URL 必须写成 BASE + endpoint.path，不得只写 baseUrl + path：")
    lines.append(" *    endpoint.path 是契约 paths 键（如 /auth/me），**不含** /api/v1；")
    lines.append(" *    前缀由本常量承载。漏掉它 ⇒ 全量 404，且 tsc/构建/门禁全绿。")
    lines.append(" */")
    lines.append("const API_BASE_PATH = %s;" % js_literal(api_base_path))
    lines.append("")
    lines.extend(_render_protocol_cjs(proto))
    lines.append("const END_TOKEN_ROLES = Object.freeze([%s]);"
                 % ", ".join(js_literal(r) for r in target["token_roles"]))
    lines.append("")
    lines.append("/** 契约 x-roles 的 token-role 展开表（本端相关项）。 */")
    lines.append("const ROLE_EXPANSION = Object.freeze({")
    lines.extend(_render_role_expansion(role_expansion, "  ", False))
    lines.append("});")
    lines.append("")
    lines.append("const ENDPOINTS = Object.freeze([")
    for e in entries:
        lines.append("  {")
        lines.append("    id: %s," % js_literal(e["operationId"]))
        lines.append("    row: %s," % js_literal(e["row"]))
        lines.append("    method: %s," % js_literal(e["method"]))
        lines.append("    path: %s," % js_literal(e["path"]))
        lines.append("    grantedRoles: Object.freeze([%s]),"
                     % ", ".join(js_literal(r) for r in e["grantedRoles"]))
        lines.extend(_opt_lines(e, "    "))
        lines.append("  },")
    lines.append("]);")
    lines.append("")
    lines.append("const ENDPOINT_IDS = Object.freeze(ENDPOINTS.map(function (e) { return e.id; }));")
    lines.append("")
    lines.append("function endpointById(id) {")
    lines.append("  for (let i = 0; i < ENDPOINTS.length; i += 1) {")
    lines.append("    if (ENDPOINTS[i].id === id) { return ENDPOINTS[i]; }")
    lines.append("  }")
    lines.append("  return null;")
    lines.append("}")
    lines.append("")
    lines.append("module.exports = {")
    lines.append("  CONTRACT_VERSION,")
    lines.append("  API_BASE_PATH,")
    lines.append("  PROTOCOL,")
    lines.append("  END_TOKEN_ROLES,")
    lines.append("  ROLE_EXPANSION,")
    lines.append("  ENDPOINTS,")
    lines.append("  ENDPOINT_IDS,")
    lines.append("  endpointById,")
    lines.append("};")
    lines.append("")
    return "\n".join(lines)


def render_ts(target, entries, spec_version, role_expansion, api_base_path, proto):
    lines = []
    lines.append("/**")
    lines.append(" * GENERATED FILE — DO NOT EDIT.")
    lines.append(" *")
    lines.append(" * 真源: contract/sdk-generator/_cut/%s.openapi.yaml" % target["id"])
    lines.append(" * 生成: frontends/tools/gen-endpoints.py")
    lines.append(" * 重跑: python frontends/tools/gen-endpoints.py")
    lines.append(" * 校验: python frontends/tools/gen-endpoints.py --check")
    lines.append(" *")
    lines.append(" * 这一份是【端 %s】的可用端点清单，逐条机械转录自契约 ——" % target["label"])
    lines.append(" * 一条 operation 属于本端，当且仅当它的 x-callable-roles 与")
    lines.append(" * 本端 token-roles (%s) 有交集。" % ", ".join(target["token_roles"]))
    lines.append(" *")
    lines.append(" * 🛑 手改本文件会在下次 --check 时被判红；要改请改契约后重跑生成器。")
    lines.append(" * 🛑 本文件只回答「本端可以调用哪些端点」，不回答「某个角色看见哪些字段」")
    lines.append(" *    —— 可见性永远由服务端 403 与 A2 档位解算决定（X-1）。")
    lines.append(" */")
    lines.append("")
    lines.append("export const CONTRACT_VERSION = %s;" % js_literal(spec_version))
    lines.append("")
    lines.append("/**")
    lines.append(" * 契约 servers[0].url（§2.0 Base Path）—— **三端拼 URL 的唯一前缀**。")
    lines.append(" *")
    lines.append(" * 🛑 出站 URL 必须写成 API_BASE_PATH + endpoint.path：")
    lines.append(" *    endpoint.path 是契约 paths 键（如 /auth/me），**不含** /api/v1；")
    lines.append(" *    前缀由本常量承载。漏掉它 ⇒ 全量 404，且 tsc/构建/门禁全绿。")
    lines.append(" */")
    lines.append("export const API_BASE_PATH: string = %s;" % js_literal(api_base_path))
    lines.append("")
    lines.extend(_render_protocol_ts(proto))
    lines.append("export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';")
    lines.append("")
    lines.append("export interface Endpoint {")
    lines.append("  readonly id: string;")
    lines.append("  readonly row: string;")
    lines.append("  readonly method: HttpMethod;")
    lines.append("  readonly path: string;")
    lines.append("  readonly grantedRoles: readonly string[];")
    lines.append("  /** 契约 x-row-scope：行级范围随 admin 子档位变化（缺省 = 契约未声明）。 */")
    lines.append("  readonly rowScope?: string;")
    lines.append("  /** 契约 x-super-admin-only：仅超管（tenant 级）。 */")
    lines.append("  readonly superAdminOnly?: boolean;")
    lines.append("  /** 契约 x-ruling-pending：取值系推断、**待裁定** —— 不得当定论实现。 */")
    lines.append("  readonly rulingPending?: string;")
    lines.append("  /** 契约 x-frontier：占位待冻结 —— 不得当已冻结契约用。 */")
    lines.append("  readonly frontier?: string;")
    lines.append("  /** 契约 x-idempotency-key：幂等键构成说明。 */")
    lines.append("  readonly idempotencyKeySpec?: string;")
    lines.append("}")
    lines.append("")
    lines.append("/** 契约角色由哪些 token-role 构成（逐条取自契约 x-roles）。 */")
    lines.append("export interface RoleExpansion {")
    lines.append("  readonly tokens: readonly string[];")
    lines.append("  readonly end: string;")
    lines.append("  readonly display: string;")
    lines.append("}")
    lines.append("")
    lines.append("/** 本端相关角色 → token-role 展开（契约 x-roles 的机械转录）。 */")
    lines.append("export const ROLE_EXPANSION: Readonly<Record<string, RoleExpansion>> = Object.freeze({")
    lines.extend(_render_role_expansion(role_expansion, "  ", True))
    lines.append("});")
    lines.append("")
    lines.append("export const END_TOKEN_ROLES: readonly string[] = Object.freeze([")
    for r in target["token_roles"]:
        lines.append("  %s," % js_literal(r))
    lines.append("]);")
    lines.append("")
    lines.append("export const ENDPOINTS: readonly Endpoint[] = Object.freeze([")
    for e in entries:
        lines.append("  {")
        lines.append("    id: %s," % js_literal(e["operationId"]))
        lines.append("    row: %s," % js_literal(e["row"]))
        lines.append("    method: %s," % js_literal(e["method"]))
        lines.append("    path: %s," % js_literal(e["path"]))
        lines.append("    grantedRoles: Object.freeze([%s]),"
                     % ", ".join(js_literal(r) for r in e["grantedRoles"]))
        lines.extend(_opt_lines(e, "    "))
        lines.append("  },")
    lines.append("]);")
    lines.append("")
    lines.append("export const ENDPOINT_IDS: readonly string[] = Object.freeze(")
    lines.append("  ENDPOINTS.map((e) => e.id),")
    lines.append(");")
    lines.append("")
    lines.append("export function endpointById(id: string): Endpoint | null {")
    lines.append("  for (const e of ENDPOINTS) {")
    lines.append("    if (e.id === id) { return e; }")
    lines.append("  }")
    lines.append("  return null;")
    lines.append("}")
    lines.append("")
    return "\n".join(lines)


def write_text(path: str, text: str) -> None:
    # 🛑 Windows 换行污染（本仓第 40 条缺陷）：必须显式 newline="\n"，
    #    否则逐字比对 / 逐字节还原会在一个与内容无关的维度上报红。
    parent = os.path.dirname(path)
    if parent and not os.path.isdir(parent):
        os.makedirs(parent)
    with io.open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def main() -> int:
    parser = argparse.ArgumentParser(description="契约驱动三端端点生成 / 校验")
    parser.add_argument("--check", action="store_true",
                        help="只校验产物与契约一致，不写入")
    args = parser.parse_args()

    spec_version = ""
    first = os.path.join(CUT_DIR, "client-mp.openapi.yaml")
    if os.path.isfile(first):
        spec_version = (yaml.safe_load(
            io.open(first, encoding="utf-8").read()).get("info") or {}).get("version") or ""

    ok = True
    for target in TARGETS:
        ops = load_operations(target["id"])
        entries = allowed_roles_of(ops, target["token_roles"])
        role_expansion = load_role_expansion(target["id"], target["token_roles"])
        api_base_path = load_api_base_path(target["id"])
        proto = load_api_protocol(target["id"])
        if target["flavor"] == "cjs":
            text = render_cjs(target, entries, spec_version, role_expansion, api_base_path, proto)
        else:
            text = render_ts(target, entries, spec_version, role_expansion, api_base_path, proto)

        out = target["out"]
        if args.check:
            if not os.path.isfile(out):
                sys.stderr.write("[FAIL] %s: 产物缺失 %s\n" % (target["id"], out))
                ok = False
                continue
            current = io.open(out, encoding="utf-8", newline="").read()
            if current != text:
                sys.stderr.write(
                    "[FAIL] %s: 产物与契约不一致（契约已改但未重跑生成器？）\n"
                    "       重跑: python frontends/tools/gen-endpoints.py\n" % target["id"])
                ok = False
            else:
                sys.stdout.write("[OK]   %-14s operations=%d  (与契约一致)\n"
                                 % (target["id"], len(entries)))
        else:
            write_text(out, text)
            sys.stdout.write("[WROTE] %-14s operations=%d -> %s\n"
                             % (target["id"], len(entries), os.path.relpath(out, SKELETON_ROOT)))

    if not ok:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
