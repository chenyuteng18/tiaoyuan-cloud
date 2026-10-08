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
    node frontends/tools/gen-endpoints.mjs            # 写出三端产物（推荐入口）
    node frontends/tools/gen-endpoints.mjs --check    # 只校验产物与契约一致（不写）

    🛑 为什么推荐走 `.mjs` 而不是直接 `python .../gen-endpoints.py`：
       Windows 上 `python` 与 `py` 落到**两个不同解释器**（实测其中一个没有 PyYAML），
       且 `py` 启动器还会读本文件首行的 shebang `#!/usr/bin/env python` 再改一次解释器
       ⇒ 探针与真调用**不同形**。`tools/py.mjs` 用"自报 sys.executable 绝对路径"的方式解析，
       是本仓访问本脚本的**唯一**入口。
       若你确信自己的解释器已装 PyYAML，直接跑本脚本是**等价**的：
           python frontends/tools/gen-endpoints.py [--check]

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
    # 🛑 原生 Android 端（Q17 裁定 B · 原生双端 · 安卓侧）
    # -------------------------------------------------------------------------
    # `cut` 与 `id` **刻意分开**：本端读的是**同一份**裁剪契约
    # `_cut/therapist-app.openapi.yaml`（token-roles 同为 therapist ∪ meridian），
    # 但报告名与产物路径独立 —— 若 id 也写成 therapist-app，`--check` 会出现
    # 两行同名结果，既有门禁按行内关键字取行时会取错。
    # ⇒ 一个契约真相源，两个产物（Web 骨架 + 原生 Android），各有一条独立产物线。
    {
        "id": "therapist-android",
        "cut": "therapist-app",
        "label": "端 B · 调理师 / 经络师 APP（原生 Android · Kotlin）",
        "package": "com.diaoyuanyun.therapist.contract",
        "token_roles": ["therapist", "meridian"],
        "out": os.path.join(SKELETON_ROOT, "frontends", "therapist-android", "app",
                            "src", "main", "kotlin", "com", "diaoyuanyun", "therapist",
                            "contract", "Endpoints.kt"),
        "flavor": "kt",
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
    schemas = ((doc.get("components") or {}).get("schemas") or {})
    # 🛑 命名参数表（本仓第 71 条）：契约用 `$ref: '#/components/parameters/XxxId'`
    #    复用参数定义（实测被引用 23 次，含 `CustomerId` / `DocTemplateId` 两个
    #    required **path** 参数）。此前的 `prm.get("in")` 在 `$ref` 字典上返回 None
    #    ⇒ 这 23 处**全部静默跳过** —— 契约 P2 明写 "path 参数恒 required，
    #    即 `{id}` 占位符必须在 URL 里被替换"，而生成物与判据从未见过它们。
    named_params = ((doc.get("components") or {}).get("parameters") or {})

    def resolve_param(prm):
        """解引用 `$ref` 参数（本仓第 71 条）。解析失败即返回空 dict（安全跳过）。"""
        if not isinstance(prm, dict):
            return {}
        ref = prm.get("$ref")
        if ref:
            return named_params.get(str(ref).rsplit("/", 1)[-1]) or {}
        return prm

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
            # ---------------- 🛑 必需参数（本仓第 65 条 · 第 71 条补 path） ----------------
            # 只转出"调用方**必须**提供"的项：
            #   · query 里 required=true 的（第 65 条）；
            #   · **path 占位符**（第 71 条）—— 契约 P2：path 参数恒 required，`{id}`
            #     必须在 URL 里被替换；调用方由 `params` 传入，漏传会拼出 `{id}` 字面量；
            #   · requestBody schema 的 required 字段名。
            #
            # 🛑 两条曾被静默跳过的形态（第 71 条）：
            #   ① `$ref: '#/components/parameters/XxxId'` —— 逐字引用命名参数，
            #      原代码 `prm.get("in")` 对 `$ref` 字典返回 None ⇒ **23 处全跳过**；
            #   ② 内联 `in: path` —— 原代码只认 `in: query` ⇒ 全部跳过。
            #   （注意注释与代码曾经不符：L156-157 声称"路径参数…只登记名字供判据核对"，
            #     而代码从未实现 —— 意图写进注释、没有判据会发现这个落差。）
            # 为什么必须转出：门禁此前只判"端点有没有被调用"，**不判实参** ⇒
            # 端 A 的 C1 调用漏了契约 required 的 age_group、C2 给三个必需字段
            # 填了臆造值，后端必然 400，而 tsc / 构建 / 全部门禁一律绿。
            # 这与第 54 条同族：契约写下的约束，中间任何一环丢项都不会报错。
            req_query = []
            req_path = []
            for raw_prm in (op.get("parameters") or []):
                prm = resolve_param(raw_prm)
                if not prm or not prm.get("name"):
                    continue
                where = prm.get("in")
                if where == "query" and prm.get("required"):
                    req_query.append(str(prm["name"]))
                elif where == "path":
                    # path 参数按 OpenAPI 规范恒为必需；即便作者漏写 required 也登记，
                    # 因为 `{name}` 占位符本身就在 URL 里（判据以占位符为准）。
                    req_path.append(str(prm["name"]))
            entry["requiredQuery"] = sorted(req_query)
            entry["requiredPath"] = sorted(req_path)

            # 🛑 生成器自己的自检（本仓第 71 条）：URL 占位符集合必须**恰好等于**
            #    转录出来的 path 参数集合。二者不等的两种情形都曾是真实缺陷：
            #      · 占位符多、req_path 少 ⇒ 逐字复现第 71 条（`$ref` / 内联
            #        `in: path` 被静默跳过）⇒ 漏传会在 URL 里留下字面量 `{id}`；
            #      · req_path 多、占位符少 ⇒ 契约自身不一致（参数声明了却没写进 path）。
            #    有了这条断言，"生成器漏认一种形态"就不可能再静默通过。
            ph = sorted(set(re.findall(r"\{(\w+)\}", str(p))))
            if ph != sorted(set(req_path)):
                raise SystemExit(
                    "MISCONFIGURED: %s %s 的 URL 占位符 %s 与转录出的 path 参数 %s 不一致"
                    "（本仓第 71 条：生成器漏认 path 参数形态会让漏传静默通过）"
                    % (m, p, ph, sorted(set(req_path)))
                )

            req_body = []
            body = (op.get("requestBody") or {}).get("content") or {}
            for media, spec in body.items():
                sch = (spec or {}).get("schema") or {}
                ref = sch.get("$ref")
                if ref:
                    sch = schemas.get(str(ref).rsplit("/", 1)[-1]) or {}
                for name in (sch.get("required") or []):
                    req_body.append(str(name))
            # 去重且稳定排序（同一字段在多 media type 下重复声明是合法的）
            entry["requiredBody"] = sorted(set(req_body))

            # ---------------- 🛑 200 响应 `data` 载荷的具名 schema（本仓第 66 条） ----------------
            # 只登记"契约确实声明了形状"的那些；未声明（`data: {type: object, nullable: true}`）
            # 保持缺省、不写这个键 —— 与 `domain/Models.kt` 头部的登记必须**一一对应**，
            # 由门禁 `android-check.mjs` 的 `contract-schema-fields` 机械核对
            # （声明数 / 端点→schema 名 / DTO 字段集合全等）。
            #
            # 🛑 为什么必须**生成**而不是让 DTO 层自己维护一份
            # ---------------------------------------------------------------------------
            # 本端 Kotlin **没有** SDK 代码生成（`generator-matrix` 只覆盖三端 Web/小程序），
            # 故 DTO 是手写的。若不把"这个端点的 data 是哪个 schema"变成生成物事实，
            # "DTO ↔ 契约 schema"就只能靠人眼，而漂移的表现是**字段静默变 null**
            # （`@SerializedName` 拼错 / 契约改名都只是取不到值）：登录后 `token` 为 null、
            # 列表全空、详情全白 —— 而编译 / 构建 / 门禁**全绿**。
            # 这与第 57/58 条同族：契约写下的事实，中间任何一环丢项都不会报错。
            resp_map = op.get("responses") or {}
            resp_200 = resp_map.get("200") or resp_map.get(200) or {}
            sch_200 = (((resp_200.get("content") or {}).get("application/json") or {})
                       .get("schema")) or {}
            for branch in (sch_200.get("allOf") or []):
                if not isinstance(branch, dict):
                    continue
                data_node = (branch.get("properties") or {}).get("data")
                if isinstance(data_node, dict) and isinstance(data_node.get("$ref"), str):
                    entry["dataSchema"] = data_node["$ref"].rsplit("/", 1)[-1]

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
    err_fields = proto.get("error-data-fields")
    if not isinstance(err_fields, dict) or not err_fields:
        raise SystemExit(
            "MISCONFIGURED: %s 的 x-api-protocol 缺 error-data-fields 结构化块 —— "
            "「不得模糊报错」的机器可读那一半（2002→missing_items / 2001→denied_fields）"
            "必须写死，否则三端出站层在错误路径丢弃 data、错误层也从不读这两个字段名，"
            "而契约 / tsc / 门禁全绿（本仓第 61 条）。" % path)
    for k, v in err_fields.items():
        if str(k).strip() == "" or str(v).strip() == "":
            raise SystemExit(
                "MISCONFIGURED: %s 的 x-api-protocol.error-data-fields 出现空键/空值：%r=%r"
                % (path, k, v))
        if not str(k).isdigit():
            raise SystemExit(
                "MISCONFIGURED: %s 的 x-api-protocol.error-data-fields 的键必须是 code 数字串"
                "（与 x-error-codes.code 逐字一致），实为 %r。" % (path, k))
    for must in ("2002", "2001"):
        if must not in {str(k) for k in err_fields}:
            raise SystemExit(
                "MISCONFIGURED: %s 的 x-api-protocol.error-data-fields 缺 code=%s —— "
                "契约明文要求该 403 给出原因名（不得模糊报错），它的 data 字段名必须可机械取用。"
                % (path, must))
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
        "error_data_fields": {str(k): str(v) for k, v in err_fields.items()},
        "error_data_field_rule": str(proto.get("error-data-field-rule") or ""),
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


def _err(proto, code):
    """取某 code 的拒绝响应 data 载荷字段名（第 61 条）—— 缺键即报错，不兜底。"""
    fields = proto["error_data_fields"]
    if code not in fields:
        raise SystemExit(
            "MISCONFIGURED: x-api-protocol.error-data-fields 缺 code=%s —— "
            "该 code 的 data 载荷字段名必须可机械取用（不得让错误层手写字面量）。" % code)
    return fields[code]


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
        "  // 拒绝响应的 data 载荷字段名（第 61 条）—— 「不得模糊报错」的机器可读那一半。",
        "  // 错误层必须用 ERROR_DATA_FIELDS[code] 取字段名，不得手写 'missing_items'。",
        "  ERROR_DATA_FIELDS: Object.freeze({",
        "    2002: %s," % js_literal(_err(proto, "2002")),
        "    2001: %s," % js_literal(_err(proto, "2001")),
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
        "  /**",
        "   * 拒绝响应的 data 载荷字段名（契约 x-api-protocol.error-data-fields，第 61 条）。",
        "   *",
        "   * 🛑 「不得模糊报错」有两半：`message` 是给人读的，**本表是给机器读的**。",
        "   *    三端出站层此前在错误路径丢弃 `body.data`、错误层也从不读这两个字段名 ⇒",
        "   *    用户只看到静态文案，「缺失项 / 被拒字段到底是哪些」在客户端【完全没有到达】。",
        "   *    故错误层必须 `err.data[PROTOCOL.ERROR_DATA_FIELDS[code]]` 取，不得手写字面量。",
        "   */",
        "  readonly ERROR_DATA_FIELDS: Readonly<Record<number, string>>;",
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
        "  ERROR_DATA_FIELDS: Object.freeze({",
        "    2002: %s," % js_literal(_err(proto, "2002")),
        "    2001: %s," % js_literal(_err(proto, "2001")),
        "  }) as Readonly<Record<number, string>>,",
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
    lines.append(" * 重跑: node frontends/tools/gen-endpoints.mjs")
    lines.append(" * 校验: node frontends/tools/gen-endpoints.mjs --check")
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
        lines.append("    requiredQuery: Object.freeze([%s]),"
                     % ", ".join(js_literal(x) for x in e.get("requiredQuery", [])))
        lines.append("    requiredBody: Object.freeze([%s]),"
                     % ", ".join(js_literal(x) for x in e.get("requiredBody", [])))
        lines.append("    requiredPath: Object.freeze([%s]),"
                     % ", ".join(js_literal(x) for x in e.get("requiredPath", [])))
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
    lines.append(" * 重跑: node frontends/tools/gen-endpoints.mjs")
    lines.append(" * 校验: node frontends/tools/gen-endpoints.mjs --check")
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
    lines.append("  /**")
    lines.append("   * 🛑 契约 required 的 query 参数名（本仓第 65 条）—— 调用点必须带上它们。")
    lines.append("   *")
    lines.append("   * 门禁此前只判「端点有没有被调用」，不判「实参对不对」⇒ 调用点可以")
    lines.append("   * 漏掉必需参数、甚至给必需字段填臆造值，后端必然 400，而")
    lines.append("   * tsc / 构建 / 全部门禁一律绿。故把它机械转录出来供判据核对。")
    lines.append("   */")
    lines.append("  readonly requiredQuery: readonly string[];")
    lines.append("  /**")
    lines.append("   * 🛑 契约 **path 参数**名（本仓第 71 条）—— URL 里的 `{id}` 占位符。")
    lines.append("   *")
    lines.append("   * 漏传的后果是**静默**的：`fillPath()` 只替换 params 里出现过的键、")
    lines.append("   * 不做残留检查 ⇒ 会把字面量 `{id}` 拼进 URL 发出去（后端路由不匹配）。")
    lines.append("   * 契约 P2 逐字：path 参数恒 required，即 `{id}` 占位符必须在 URL 里被替换。")
    lines.append("   *")
    lines.append("   * 🛑 为什么此前一直没被转录（两种形态各有一份静默）：")
    lines.append("   *   ① 契约用 `$ref: '#/components/parameters/XxxId'` 复用命名参数（23 处）")
    lines.append("   *      ⇒ `prm.get(\"in\")` 对 `$ref` 字典返回 None ⇒ 全部跳过；")
    lines.append("   *   ② 内联 `in: path` ⇒ 原代码只认 `in: query` ⇒ 全部跳过。")
    lines.append("   */")
    lines.append("  readonly requiredPath: readonly string[];")
    lines.append("  /** 🛑 契约 requestBody schema 的 required 字段名（未声明 requestBody 时为空）。 */")
    lines.append("  readonly requiredBody: readonly string[];")
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
        lines.append("    requiredQuery: Object.freeze([%s]),"
                     % ", ".join(js_literal(x) for x in e.get("requiredQuery", [])))
        lines.append("    requiredBody: Object.freeze([%s]),"
                     % ", ".join(js_literal(x) for x in e.get("requiredBody", [])))
        lines.append("    requiredPath: Object.freeze([%s]),"
                     % ", ".join(js_literal(x) for x in e.get("requiredPath", [])))
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


def kt_literal(value: str) -> str:
    """Kotlin 普通字符串字面量（在 ``"..."`` 里转义）。

    🛑 为什么 ``$`` 必须转义
    ---------------------------------------------------------------------------
    Kotlin 的 ``"..."`` 里 ``$`` 是**字符串模板起始符**。契约的 path 参数占位符
    形如 ``{id}``（不含 ``$``），当前所有转录值也都不含 ``$`` —— 但**这不构成
    "可以不转义"的理由**：一旦契约某天出现 ``$``（例如计价口径串），
    生成的 Kotlin 会变成编译期模板注入，而**编译报错的位置会在别处**。
    这与本仓第 40 条（换行污染）同类：在一个与内容无关的维度上静默出错。
    """
    s = (value.replace("\\", "\\\\")
              .replace('"', '\\"')
              .replace("$", "\\$")
              .replace("\r", "")
              .replace("\n", "\\n")
              .replace("\t", "\\t"))
    return '"%s"' % s


def _pagination_container_field(pag):
    """分页响应里的**列表容器键**（契约 ``pagination.response-fields`` 首位）。

    🛑 位置化取值的依据与失效面
    ---------------------------------------------------------------------------
    契约把容器列在 ``response-fields`` 首位（``[items, total, page, page_size]``），
    prose 与结构化块**两处同序**（``data.{items[], total, page, page_size}``）。
    本函数与 ``request-fields[0]/[1]`` 的位置化取法同源（既有先例）。

    假设一旦被破坏必须**当场报错**而不是静默换键 —— 故三条断言：
      ① 首位不得是 request-fields 成员（否则说明容器键已被换成 page/page_size）；
      ② request-fields 必须整体包含在 response-fields 内（契约自身的同源约束）；
      ③ response-fields 长度须为 request-fields 长度 + 2（容器 + total）。

    🛑 如实登记的**残余风险**：这三条抓不到「容器与 total 对调」（两者都不是
       request-field）。对调后 ``ITEMS_FIELD`` 会变成 ``"total"``，出站层取到
       整数而非数组 ⇒ 列表静默为空。该风险极低（契约已冻结，调序属 docs-only 变更），
       且改动契约必须重跑生成器 + ``--check``，故此处只登记、不另加机制。
    """
    resp = [str(x) for x in pag["response_fields"]]
    req = [str(x) for x in pag["request_fields"]]
    if not resp:
        raise SystemExit("MISCONFIGURED: x-api-protocol.pagination.response-fields 为空")
    if resp[0] in req:
        raise SystemExit(
            "MISCONFIGURED: pagination.response-fields 首位 %r 是请求字段名，不是列表容器键"
            " —— 「顺序即语义」的位置化取键前提已不成立" % resp[0]
        )
    if not set(req) <= set(resp):
        raise SystemExit(
            "MISCONFIGURED: pagination.request-fields %s 未被 response-fields %s 覆盖"
            "（契约自身的同源约束）" % (req, resp)
        )
    if len(resp) != len(req) + 2:
        raise SystemExit(
            "MISCONFIGURED: pagination.response-fields 应为 request-fields + 2（容器 + total），"
            "实际 %d vs %d" % (len(resp), len(req))
        )
    return resp[0]


def _kt_envelope_const_name(field):
    """信封字段名 → Kotlin 常量名。非合法标识符 ⇒ MISCONFIGURED（fail-closed）。"""
    if not re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", field):
        raise SystemExit(
            "MISCONFIGURED: 信封字段名 %r 不是合法 Kotlin 标识符，无法派生常量名"
            "（契约 x-api-protocol.envelope-fields）" % field
        )
    return "ENVELOPE_FIELD_" + field.upper()


def _kt_envelope_consts(proto):
    """信封字段的**具名常量**（逐条取自 ``x-api-protocol.envelope-fields``）。

    🛑 为什么必须拆成具名常量，而不是只留一个 ``ENVELOPE_FIELDS`` 列表
    ---------------------------------------------------------------------------
    实测（2026-10-08）：本端生成物里 ``ENVELOPE_FIELDS`` **零引用**，而出站层
    ``ApiClient.unwrap()`` 手写了全部四个字段名 —— 典型的"**死权威**"：
    权威声明在生成物里躺着，取用点却是手抄的第二份。契约把 ``trace_id`` 改名后，
    重跑生成器**只会改这个列表**，出站层一行不动 ⇒ 信封解不开、``data`` 变 null、
    列表全空 / 详情全白，而 debug 包完全正常、构建与门禁**全绿**。

    ⇒ 拆成具名常量，出站层写 ``obj.get(Protocol.ENVELOPE_FIELD_CODE)``：
      契约改名 ⇒ 常量跟着变 ⇒ 取用点自动正确。手写字面量由门禁
      ``android-check.mjs`` 的 ``protocol-fields-from-generated`` 判红。
    """
    out = [
        "    // ---------- 响应信封字段名（契约 x-api-protocol.envelope-fields） ----------",
        "    // 🛑 出站层解信封【必须】引用下面的具名常量，不得写 \"code\" / \"data\" 这类字面量 ——",
        "    //    契约改名后字面量不会跟着改，而「取不到字段」多数情况下表现为静默拿到 null",
        "    //    （data 变 null ⇒ 列表全空 / 详情全白），且 debug 包正常、构建与门禁全绿。",
    ]
    names = []
    for f in proto["envelope_fields"]:
        const = _kt_envelope_const_name(str(f))
        names.append(const)
        out.append("    /** 信封字段 %s。 */" % kt_literal(str(f)))
        out.append("    const val %s: String = %s" % (const, kt_literal(str(f))))
    out.append("    /** 响应信封字段全集（由上面具名常量组成 —— 两处各写一份就会漂移）。 */")
    out.append("    val ENVELOPE_FIELDS: List<String> = listOf(%s)" % ", ".join(names))
    return out


def _kt_opt_args(e, indent):
    """可选契约字段 → Kotlin 具名实参（缺省即不写，与 TS 侧 OPTIONAL_FIELDS 同序）。"""
    out = []
    for field, kind in OPTIONAL_FIELDS:
        if field not in e:
            continue
        v = e[field]
        if kind == "bool":
            out.append("%s%s = %s," % (indent, field, "true" if v else "false"))
        else:
            out.append("%s%s = %s," % (indent, field, kt_literal(v)))
    return out


def _render_protocol_kt(proto):
    """跨端协议片段常量（Kotlin 形态）。

    与 TS/CJS 两形态**逐字段同值**，取自同一份 ``x-api-protocol`` ——
    第 57 条纪律：协议片段只能有一个来源，出站层不得出现字面量。
    """
    pag = _pag(proto)
    out = [
        "/**",
        " * 跨端协议片段（契约 x-api-protocol 的机械转录）—— **出站层一律引用本组常量**。",
        " *",
        " * 🛑 为什么不能在本端出站层手写字面量（本仓第 57 条）",
        " *    头名 / 令牌前缀 / 信封成功码此前在三个端各写一遍。`X-Trace-Id` 更彻底：",
        " *    契约里一个字都没有，只活在后端 TraceIdFilter 与各端字面量里。",
        " *    改一处 ⇒ 各处静默分叉 ⇒ 全量 401 / 幂等去重失效 / 留痕断链，而构建全绿。",
        " */",
        "object Protocol {",
        "    /** 鉴权头名。 */",
        "    const val AUTH_HEADER: String = %s" % kt_literal(proto["auth_header"]),
        "    /** 令牌前缀 —— 拼 `${AUTH_SCHEME} ${token}`。 */",
        "    const val AUTH_SCHEME: String = %s" % kt_literal(proto["auth_scheme"]),
        "    /** 租户一致性校验头（服务端仅校验、不采纳其值）。 */",
        "    const val TENANT_HEADER: String = %s" % kt_literal(proto["tenant_header"]),
        "    /** 留痕头 —— 与响应体 trace_id 并存，用于日志双向检索。 */",
        "    const val TRACE_HEADER: String = %s" % kt_literal(proto["trace_header"]),
        "    /** 幂等键请求头名（写请求必带）。 */",
        "    const val IDEMPOTENCY_HEADER: String = %s" % kt_literal(proto["idempotency_header"]),
        *_kt_envelope_consts(proto),
        "    /** 信封成功码 —— 契约 §2.0 逐字「code != 0 时 data 为空」，故成功码为 0。 */",
        "    const val ENVELOPE_OK_CODE: Int = %d" % proto["envelope_ok_code"],
        "",
        "    // ---------------- 分页协议（权威定义见 x-api-protocol.pagination） -------------",
        "    /** 页码参数名（出站拼接用）。 */",
        "    const val PAGE_FIELD: String = %s" % kt_literal(pag["request_fields"][0]),
        "    /** 每页条数参数名。 */",
        "    const val PAGE_SIZE_FIELD: String = %s" % kt_literal(pag["request_fields"][1]),
        "    /**",
        "     * 分页**响应**里的列表容器键（契约 pagination.response-fields 首位）。",
        "     *",
        "     * 🛑 出站层取列表【必须】用本常量：`ApiClient.items()` 此前手写 `get(\"items\")`，",
        "     *    契约改名后所有列表端点会**静默返回空列表**（取不到键 ⇒ 当成无数据），",
        "     *    而 debug 包正常、构建与门禁全绿。",
        "     */",
        "    const val ITEMS_FIELD: String = %s" % kt_literal(_pagination_container_field(pag)),
        "    /** page 下界（< 该值一律 400，不静默纠正）。 */",
        "    const val PAGE_MIN: Int = %d" % pag["page_min"],
        "    /** page_size 下界。 */",
        "    const val PAGE_SIZE_MIN: Int = %d" % pag["page_size_min"],
        "    /** page_size 上界（> 该值一律 400，不夹逼）。 */",
        "    const val PAGE_SIZE_MAX: Int = %d" % pag["page_size_max"],
        "    /** 未传 page_size 时的缺省值。 */",
        "    const val PAGE_SIZE_DEFAULT: Int = %d" % pag["page_size_default"],
        "    /** 越界处置。本仓唯一合法取值 'reject-400'（越界直接拒，不得静默夹逼）。 */",
        "    const val OVER_RANGE_POLICY: String = %s" % kt_literal(pag["over_range_policy"]),
        "    /** 越界对应的错误码名。 */",
        "    const val OVER_RANGE_ERROR: String = %s" % kt_literal(pag["over_range_error"]),
        "",
        "    /**",
        "     * 拒绝响应的 data 载荷字段名（第 61 条）—— 「不得模糊报错」的机器可读那一半。",
        "     *",
        "     * message 是给人读的；本表是给机器读的。错误层必须用",
        "     * ERROR_DATA_FIELDS[code] 取字段名，不得手写 \"missing_items\"。",
        "     */",
        "    val ERROR_DATA_FIELDS: Map<Int, String> = mapOf(",
        "        2002 to %s," % kt_literal(_err(proto, "2002")),
        "        2001 to %s," % kt_literal(_err(proto, "2001")),
        "    )",
        "}",
        "",
    ]
    return out


def render_kt(target, entries, spec_version, role_expansion, api_base_path, proto):
    """端 B 原生 Android（Kotlin）端点层。

    🛑 与 TS/CJS 两形态的关键差别（不是"换个语法"，是**换一个失效面**）
    ---------------------------------------------------------------------------
    TS 侧靠 `tsc --noEmit` 把"类型用错"变成编译错误；Kotlin 侧同理靠编译器，
    但**两端都挡不住"端点封装了却没人调用"**（第 63 条）、也挡不住
    "封装了但页面打不开"（第 64 条）。故本端产物必须与三端**同受**那两条判据
    保护 —— 见 `tools/android-check.mjs`，以及 `frontends/README.md` 新增章节。
    """
    pkg = target["package"]
    lines = []
    lines.append("// ============================================================================")
    lines.append("// GENERATED FILE — DO NOT EDIT.")
    lines.append("//")
    lines.append("// 真源: contract/sdk-generator/_cut/%s.openapi.yaml" % target.get("cut", target["id"]))
    lines.append("// 生成: frontends/tools/gen-endpoints.py")
    lines.append("// 重跑: node frontends/tools/gen-endpoints.mjs")
    lines.append("// 校验: node frontends/tools/gen-endpoints.mjs --check")
    lines.append("//")
    lines.append("// 这一份是【%s】的可用端点清单，逐条机械转录自契约 ——" % target["label"])
    lines.append("// 一条 operation 属于本端，当且仅当它的 x-callable-roles 与")
    lines.append("// 本端 token-roles (%s) 有交集。" % ", ".join(target["token_roles"]))
    lines.append("//")
    lines.append("// 🛑 手改本文件会在下次 --check 时被判红；要改请改契约后重跑生成器。")
    lines.append("// 🛑 本文件只回答「本端可以调用哪些端点」，不回答「某个角色看见哪些字段」")
    lines.append("//    —— 可见性永远由服务端 403 与 A2 档位解算决定（X-1）。")
    lines.append("// ============================================================================")
    lines.append("")
    lines.append("package %s" % pkg)
    lines.append("")
    lines.append("/** 契约版本（契约 info.version 的机械转录）。 */")
    lines.append("const val CONTRACT_VERSION: String = %s" % kt_literal(spec_version))
    lines.append("")
    lines.append("/**")
    lines.append(" * 契约 servers[0].url（§2.0 Base Path）—— **本端拼 URL 的唯一前缀**。")
    lines.append(" *")
    lines.append(" * 🛑 出站 URL 必须写成 API_BASE_PATH + endpoint.path：")
    lines.append(" *    endpoint.path 是契约 paths 键（如 /auth/me），**不含** /api/v1；")
    lines.append(" *    前缀由本常量承载。漏掉它 ⇒ 全量 404，且编译/构建/门禁全绿（第 56 条）。")
    lines.append(" */")
    lines.append("object Contract {")
    lines.append("    const val VERSION: String = CONTRACT_VERSION")
    lines.append("    const val API_BASE_PATH: String = %s" % kt_literal(api_base_path))
    lines.append("    /** 本端 token-roles（取自 generator-matrix.yaml 的声明）。 */")
    lines.append("    val END_TOKEN_ROLES: List<String> = listOf(%s)"
                 % ", ".join(kt_literal(r) for r in target["token_roles"]))
    lines.append("}")
    lines.append("")
    lines.extend(_render_protocol_kt(proto))
    lines.append("/** 契约 paths 允许的 HTTP 方法。 */")
    lines.append("enum class HttpMethod { GET, POST, PUT, PATCH, DELETE }")
    lines.append("")
    lines.append("/** 一个契约 operation 的机械转录。 */")
    lines.append("data class Endpoint(")
    lines.append("    val id: String,")
    lines.append("    val row: String,")
    lines.append("    val method: HttpMethod,")
    lines.append("    val path: String,")
    lines.append("    val grantedRoles: List<String>,")
    lines.append("    /** 契约 required 的 query 参数名（第 65 条）—— 调用点必须带上它们。 */")
    lines.append("    val requiredQuery: List<String>,")
    lines.append("    /** 契约 path 参数名（第 71 条）—— URL 里的 {id} 占位符，漏传会拼出字面量。 */")
    lines.append("    val requiredPath: List<String>,")
    lines.append("    /** 契约 requestBody schema 的 required 字段名。 */")
    lines.append("    val requiredBody: List<String>,")
    lines.append("    /**")
    lines.append("     * 契约 200 响应 `data` 载荷的**具名 schema 名**（`components.schemas` 的键）。")
    lines.append("     *")
    lines.append("     * 未声明形状的端点（`data: {type: object, nullable: true}`）为 null ——")
    lines.append("     * 与 `domain/Models.kt` 头部的登记**一一对应**，由门禁")
    lines.append("     * `contract-schema-fields` 核对（声明数 / 名字 / 字段集合全等）。")
    lines.append("     */")
    lines.append("    val dataSchema: String? = null,")
    lines.append("    /** 契约 x-row-scope：行级范围随 admin 子档位变化。 */")
    lines.append("    val rowScope: String? = null,")
    lines.append("    /** 契约 x-super-admin-only：仅超管。 */")
    lines.append("    val superAdminOnly: Boolean? = null,")
    lines.append("    /** 契约 x-ruling-pending：取值系推断、**待裁定** —— 不得当定论实现。 */")
    lines.append("    val rulingPending: String? = null,")
    lines.append("    /** 契约 x-frontier：占位待冻结 —— 不得当已冻结契约用。 */")
    lines.append("    val frontier: String? = null,")
    lines.append("    /** 契约 x-idempotency-key：幂等键构成说明。 */")
    lines.append("    val idempotencyKeySpec: String? = null,")
    lines.append(")")
    lines.append("")
    lines.append("/** 契约角色由哪些 token-role 构成（逐条取自契约 x-roles）。 */")
    lines.append("data class RoleExpansion(")
    lines.append("    val tokens: List<String>,")
    lines.append("    val end: String,")
    lines.append("    val display: String,")
    lines.append(")")
    lines.append("")
    lines.append("/** 本端全部可用端点 + 角色展开表的唯一索引点。 */")
    lines.append("object Endpoints {")
    lines.append("")
    lines.append("    val ALL: List<Endpoint> = listOf(")
    for e in entries:
        lines.append("        Endpoint(")
        lines.append("            id = %s," % kt_literal(e["operationId"]))
        lines.append("            row = %s," % kt_literal(e["row"]))
        lines.append("            method = HttpMethod.%s," % e["method"])
        lines.append("            path = %s," % kt_literal(e["path"]))
        lines.append("            grantedRoles = listOf(%s),"
                     % ", ".join(kt_literal(r) for r in e["grantedRoles"]))
        lines.append("            requiredQuery = listOf(%s),"
                     % ", ".join(kt_literal(x) for x in e.get("requiredQuery", [])))
        lines.append("            requiredPath = listOf(%s),"
                     % ", ".join(kt_literal(x) for x in e.get("requiredPath", [])))
        lines.append("            requiredBody = listOf(%s),"
                     % ", ".join(kt_literal(x) for x in e.get("requiredBody", [])))
        # 只在契约确实声明了形状时写该键（缺省即不写，保持生成物最小）
        if e.get("dataSchema"):
            lines.append("            dataSchema = %s," % kt_literal(e["dataSchema"]))
        lines.extend(_kt_opt_args(e, "            "))
        lines.append("        ),")
    lines.append("    )")
    lines.append("")
    lines.append("    val IDS: List<String> = ALL.map { it.id }")
    lines.append("")
    lines.append("    private val BY_ID: Map<String, Endpoint> = ALL.associateBy { it.id }")
    lines.append("")
    lines.append("    /** 按 operationId 取端点；未收录返回 null（调用方必须 fail-closed）。 */")
    lines.append("    fun byId(id: String): Endpoint? = BY_ID[id]")
    lines.append("")
    lines.append("    /** 契约 x-roles 的 token-role 展开表（本端相关项）。 */")
    lines.append("    val ROLE_EXPANSION: Map<String, RoleExpansion> = mapOf(")
    for end_role in sorted(role_expansion):
        spec = role_expansion[end_role]
        lines.append("        %s to RoleExpansion(" % kt_literal(end_role))
        lines.append("            tokens = listOf(%s),"
                     % ", ".join(kt_literal(t) for t in spec["tokens"]))
        lines.append("            end = %s," % kt_literal(spec["end"]))
        lines.append("            display = %s," % kt_literal(spec["display"]))
        lines.append("        ),")
    lines.append("    )")
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
        # 🛑 `cut` 与 `id` 分开：一个契约真相源可以产出多份产物
        #    （端 B 既有 Web 骨架也有原生 Android 端），但报告名各自独立。
        cut_id = target.get("cut", target["id"])
        ops = load_operations(cut_id)
        entries = allowed_roles_of(ops, target["token_roles"])
        role_expansion = load_role_expansion(cut_id, target["token_roles"])
        api_base_path = load_api_base_path(cut_id)
        proto = load_api_protocol(cut_id)
        if target["flavor"] == "cjs":
            text = render_cjs(target, entries, spec_version, role_expansion, api_base_path, proto)
        elif target["flavor"] == "kt":
            text = render_kt(target, entries, spec_version, role_expansion, api_base_path, proto)
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
                    "       重跑: node frontends/tools/gen-endpoints.mjs\n" % target["id"])
                ok = False
            else:
                sys.stdout.write("[OK]   %-18s operations=%d  (与契约一致)\n"
                                 % (target["id"], len(entries)))
        else:
            write_text(out, text)
            sys.stdout.write("[WROTE] %-18s operations=%d -> %s\n"
                             % (target["id"], len(entries), os.path.relpath(out, SKELETON_ROOT)))

    if not ok:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
