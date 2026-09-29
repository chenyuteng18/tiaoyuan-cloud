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
            ops.append({
                "method": m,
                "path": p,
                "operationId": op.get("operationId"),
                "roles": list(op.get("x-callable-roles") or []),
                "row": row_match.group(1) if row_match else "",
                "summary": summary,
                "tags": list(op.get("tags") or []),
            })
    ops.sort(key=lambda o: (o["path"], o["method"]))
    return ops


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


def render_cjs(target, entries, spec_version):
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
    lines.append("const END_TOKEN_ROLES = Object.freeze([%s]);"
                 % ", ".join(js_literal(r) for r in target["token_roles"]))
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
    lines.append("  END_TOKEN_ROLES,")
    lines.append("  ENDPOINTS,")
    lines.append("  ENDPOINT_IDS,")
    lines.append("  endpointById,")
    lines.append("};")
    lines.append("")
    return "\n".join(lines)


def render_ts(target, entries, spec_version):
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
    lines.append("export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';")
    lines.append("")
    lines.append("export interface Endpoint {")
    lines.append("  readonly id: string;")
    lines.append("  readonly row: string;")
    lines.append("  readonly method: HttpMethod;")
    lines.append("  readonly path: string;")
    lines.append("  readonly grantedRoles: readonly string[];")
    lines.append("}")
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
        if target["flavor"] == "cjs":
            text = render_cjs(target, entries, spec_version)
        else:
            text = render_ts(target, entries, spec_version)

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
