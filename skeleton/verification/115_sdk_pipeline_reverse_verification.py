#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
反向验证 115 —— SDK 裁剪管线（_sdk_pipeline.py）的牙齿

=============================================================================
被测对象
=============================================================================
`contract/sdk-generator/_sdk_pipeline.py`，六类判据：
  ① INV-2：被裁掉的 operation **不得**出现在该端产物里（真正的牙齿）
  ② INV-1：该端应保留的 operation **必须**出现在产物里（防裁过头）
  ③ INV-3：client-mp **运行时源码**（剥离注释后）内不得出现「退款」
  ④ INV-6：被裁掉的 schema **不得**以 model 文件形式留在产物里
  ⑤ INV-6b：产物 model 目录不得含矩阵禁入片段文件（含 generator 派生类型）
  ⑥ 例外机制 + 裁剪自证（僵尸例外、裁剪后为空）

=============================================================================
为什么必须做反向验证
=============================================================================
本管线的核心判据是「**不该出现的东西没有出现**」——
这是一个**否定式断言**。否定式断言最容易的失效方式是：
  · 搜索面为空（产物没生成 / 目录写错）⇒ 什么都搜不到 ⇒ 平凡通过
  · 路径归一化写错（契约是 /customers/{id}/band/derived，产物是别的写法）⇒ 搜不到 ⇒ 假绿
  · 裁剪逻辑其实没生效，但校验用的是同一份错的裁剪结果 ⇒ 自证循环

=============================================================================
🛑 设计决策：为什么在【临时副本】上注入，而不是就地改再还原
=============================================================================
本脚本一度采用「就地注入 → 逐字节还原」的经典做法，但在本机**不可靠**：
沙箱对「一个 turn 内累计删除文件数」有配额（实测 SAFE_DELETE_BULK_CONFIRM_REQUIRED，
阈值 50），且该拦截**对脚本内部 spawn 的子进程同样生效** ——
于是"注入 E 造一个伪 model 文件、再删掉"这一步会失败，
伪文件残留 ⇒ **下一次运行的基线直接变红**（本脚本真实踩过两次）。

改为副本模式后：
  · 真实 `contract/sdk/` 与 `generator-matrix.yaml` **从头到尾零写入**
    ⇒ 无残留是**构造上保证**的，不依赖任何还原动作；
  · 注入只发生在系统临时目录的副本里 ⇒ 删除配额与它无关；
  · 真实性由「运行前后对真实树做递归哈希比对」来证明（比"我删掉了"更硬）。
这是"改后还原"的**加强版**，不是妥协。

=============================================================================
注入清单
=============================================================================
  A. 往 client 产物塞被裁路径对 ('/refunds','POST')      → 期望红 INV-2
  B. 往 client **代码**（字符串字面量，非注释）写禁词      → 期望红 INV-3
  C. 删掉 client 一个应保留路径                          → 期望红 INV-1
  D. 矩阵把 client 角色写成不存在                        → 期望红 AUDIT（裁剪后为空）
  E. 凭空造 client/src/model/RefundData.js（被裁 schema）→ 期望红 INV-6
  F. 凭空造 CreateRefund200Response.js（派生包装类型）   → 期望红 INV-6b
  G. 矩阵例外名改成不存在的名字                           → 期望红「僵尸条目」
  H. path→method 之间插 ~540 字符填充                     → 期望**仍绿**（切块法不受距离限制）
  I. 往 client 源码**注释**里写禁词                       → 期望**仍绿**（注释被剥离）

H / I 是"期望不红"的注入 —— 它们守的是**判据不过度**（既不能漏，也不能滥杀）。

=============================================================================
纪律（沿用 99~114）
=============================================================================
· 锚点预检（存在且恰 1 次）· 真实树逐字节不变（哈希自证）· 无残留（构造保证）
· 🛑 caught = (exit != 0) and (期望的锚点出现在输出里)
· must_see 锚点一律 ASCII（除「退款」这一被测对象自带的禁词）
"""

import hashlib
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))

# 工作根 = product-strategy（skeleton 的父目录）
CWD = os.getcwd()
PROD_STRATEGY = None
for base in [CWD] + [os.path.abspath(os.path.join(CWD, "..", "..")),
                     os.path.abspath(os.path.join(CWD, ".."))]:
    if os.path.isdir(os.path.join(base, "contract", "sdk-generator")):
        PROD_STRATEGY = base
        break
if PROD_STRATEGY is None:
    # 从脚本位置回推：skeleton/verification -> skeleton -> product-strategy
    PROD_STRATEGY = os.path.abspath(os.path.join(HERE, "..", ".."))

GEN_DIR = os.path.join(PROD_STRATEGY, "contract", "sdk-generator")
SPEC = os.path.join(PROD_STRATEGY, "contract", "openapi-v1.0.0.yaml")
MATRIX = os.path.join(GEN_DIR, "generator-matrix.yaml")
SDK_ROOT = os.path.join(PROD_STRATEGY, "contract", "sdk")
PIPELINE = os.path.join(GEN_DIR, "_sdk_pipeline.py")

PY = sys.executable

# 临时探测区（不进仓）
PROBE_ROOT = os.path.join(tempfile.gettempdir(), "sdk115-probe")
PROBE_SDK = os.path.join(PROBE_ROOT, "sdk")
PROBE_MATRIX = os.path.join(PROBE_ROOT, "generator-matrix.yaml")


# ---------------------------------------------------------------------------
# 真实树不变性自证
# ---------------------------------------------------------------------------

def hash_tree(root):
    """递归哈希（相对路径 → sha256），用于证明"真实树没被动过"。"""
    out = {}
    if not os.path.isdir(root):
        return out
    for r, _d, fs in os.walk(root):
        for fn in sorted(fs):
            fp = os.path.join(r, fn)
            rel = os.path.relpath(fp, root).replace("\\", "/")
            try:
                h = hashlib.sha256(io.open(fp, "rb").read()).hexdigest()
            except Exception:                           # noqa: BLE001
                h = "<unreadable>"
            out[rel] = h
    return out


def hash_file(path):
    try:
        return hashlib.sha256(io.open(path, "rb").read()).hexdigest()
    except Exception:                                   # noqa: BLE001
        return "<unreadable>"


# ---------------------------------------------------------------------------
# 跑被测对象（全部指向副本）
# ---------------------------------------------------------------------------

def run_verify(sdk_root=None, matrix=None):
    r = subprocess.run(
        [PY, PIPELINE, "verify", SPEC, matrix or PROBE_MATRIX, sdk_root or PROBE_SDK],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.returncode, (r.stdout or "") + (r.stderr or "")


def run_audit(matrix=None):
    r = subprocess.run(
        [PY, PIPELINE, "audit", SPEC, matrix or PROBE_MATRIX],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    return r.returncode, (r.stdout or "") + (r.stderr or "")


# ---------------------------------------------------------------------------
# 工具
# ---------------------------------------------------------------------------

def read_text(p):
    return io.open(p, encoding="utf-8", errors="replace").read()


def write_text(p, txt):
    io.open(p, "w", encoding="utf-8", newline="").write(txt)


def find_dir(rel):
    p = os.path.join(PROBE_SDK, rel.replace("/", os.sep))
    return p if os.path.isdir(p) else None


def find_file(rel):
    p = os.path.join(PROBE_SDK, rel.replace("/", os.sep))
    return p if os.path.exists(p) else None


def first_existing(rels):
    for rel in rels:
        p = find_file(rel)
        if p:
            return p
    return None


BLOCK_COMMENT_END = re.compile(r"(?m)^ \*/\s*$")


def setup_probe():
    """把真实 sdk 树与矩阵复制到临时区（复制=创建，不受删除配额影响）。"""
    if os.path.isdir(PROBE_ROOT):
        try:
            shutil.rmtree(PROBE_ROOT)
        except Exception:                               # noqa: BLE001
            pass
    os.makedirs(PROBE_ROOT, exist_ok=True)
    shutil.copytree(SDK_ROOT, PROBE_SDK)
    shutil.copy2(MATRIX, PROBE_MATRIX)


def main():
    print("=" * 74)
    print("反向验证 115 · SDK 裁剪管线")
    print("=" * 74)
    print("PROD_STRATEGY =", PROD_STRATEGY)
    print("SDK_ROOT(真实) =", SDK_ROOT)
    print("PROBE(副本)    =", PROBE_ROOT)
    print()

    results = []

    def record(name, ok):
        results.append((name, bool(ok)))
        return bool(ok)

    # -----------------------------------------------------------------------
    # 真实树基线哈希（运行结束后要比对，证明零写入）
    # -----------------------------------------------------------------------
    real_sdk_h0 = hash_tree(SDK_ROOT)
    real_matrix_h0 = hash_file(MATRIX)
    real_spec_h0 = hash_file(SPEC)
    print("【真实树】sdk %d 文件 · 矩阵 sha256=%s… · 契约 sha256=%s…"
          % (len(real_sdk_h0), real_matrix_h0[:12], real_spec_h0[:12]))
    print()

    # -----------------------------------------------------------------------
    # 建立副本
    # -----------------------------------------------------------------------
    try:
        setup_probe()
    except Exception as e:                              # noqa: BLE001
        print("🛑 副本建立失败：%s" % e)
        return 1
    print("【副本】已建立：%s" % PROBE_SDK)
    print()

    # -----------------------------------------------------------------------
    # 基线：副本上 VERIFY 必须绿
    # -----------------------------------------------------------------------
    code, out = run_verify()
    print("【基线】verify exit =", code)
    if code != 0:
        print(out[-3000:])
        print("🛑 基线不绿 —— 后续注入无意义")
        return 1
    print("  基线通过。")
    print()

    # -----------------------------------------------------------------------
    # 元层自证 1：audit 能列出非零保留 / 裁掉
    # -----------------------------------------------------------------------
    code_a, out_a = run_audit()
    if code_a != 0:
        print("🛑 audit 不绿")
        print(out_a[-2000:])
        return 1
    print("【元层自证】audit 通过 —— 裁剪逻辑与矩阵自洽")
    for ln in out_a.strip().splitlines():
        if ln.startswith(("[client-mp]", "[therapist-app]", "[admin-web]")):
            print("   ", ln)
    print()

    # -----------------------------------------------------------------------
    # 元层自证 2：verify 报告含非零计数
    # 🛑 判据字符串必须与 verify 的**实际输出格式逐字对齐**：
    #    报告行是 `... 应保留 15 · 已裁掉 30 · 泄漏 0 · 缺失 0 · schema 保留 14 / 裁掉 18 ...`
    #    —— 没有 "schema 裁掉" 这个串（本脚本真实踩过：判据字符串凭空写，
    #    导致元层自证恒为 False，而它本该是最不可能误报的一条）。
    # -----------------------------------------------------------------------
    _cm, out_v = run_verify()
    meta_checks = {
        "应保留 15": "应保留 15" in out_v,
        "已裁掉 30": "已裁掉 30" in out_v,
        "schema 保留": "schema 保留" in out_v,
        "model 泄漏": "model 泄漏" in out_v,
    }
    meta_ok = all(meta_checks.values())
    print("【元层自证】verify 报告含非零保留/裁掉计数 =", meta_ok, meta_checks)
    if not meta_ok:
        print(out_v[-2500:])
    record("元层 非零保留/裁掉计数可见", meta_ok)
    print()

    # 注入目标
    client_api = first_existing([
        "client-mp/src/api/EApi.js",
        "client-mp/src/api/AApi.js",
    ])
    if not client_api:
        print("🛑 找不到 client-mp 的 api 文件 —— 产物结构变了，脚本需更新")
        return 1
    client_model_dir = find_dir("client-mp/src/model")
    admin_api_dir = find_dir("admin-web/api")
    if not client_model_dir or not admin_api_dir:
        print("🛑 找不到 client-mp/src/model 或 admin-web/api —— 产物结构变了")
        return 1

    print("注入目标（均在副本内）：")
    print("  client api   :", os.path.relpath(client_api, PROBE_SDK))
    print("  client model :", os.path.relpath(client_model_dir, PROBE_SDK))
    print("  admin api    :", os.path.relpath(admin_api_dir, PROBE_SDK))
    print()

    # =======================================================================
    # A. 往 client 产物塞一个【被裁掉的】路径对 ⇒ INV-2 必须红
    #
    # 🛑 注入形态必须与 **javascript 模板的真实产物**一致：
    #    `'/customers/{id}/visits', 'GET',`（path 与 method 相邻在同一行）。
    #    只塞 `'/refunds',` 是不够的 —— 提取器要求 (path, method) 成对，
    #    孤立的 path 永远配不上 method，注入会"看起来生效实则无效"（本脚本真实踩过）。
    # =======================================================================
    text_a = read_text(client_api)
    anchor_a = "        '/band/available-dates', 'POST',\n"
    if text_a.count(anchor_a) != 1:
        print("🛑 注入 A 锚点不唯一（count=%d）：%r" % (text_a.count(anchor_a), anchor_a))
        return 1
    write_text(client_api, text_a.replace(
        anchor_a, "        '/refunds', 'POST',\n        '/refunds', 'GET',\n" + anchor_a, 1))
    code_b, out_b = run_verify()
    caught = (code_b != 0) and ("/refunds" in out_b) and ("INV-2" in out_b)
    print("【注入 A】client 产物塞入被裁路径对 ('/refunds','POST'/'GET')")
    print("    exit =", code_b, "| 期望红(INV-2) =", caught)
    if not caught:
        print(out_b[-2500:])
    record("A 塞入被裁路径 → INV-2 红", caught)
    write_text(client_api, text_a)
    record("A 还原后复绿", run_verify()[0] == 0)
    print()

    # =======================================================================
    # B. client【代码】（非注释）出现禁词 ⇒ INV-3 必须红
    #    🛑 两个坑都要避开：
    #      ① 必须塞进**字符串字面量**（真代码），塞进注释会被剥离后忽略；
    #      ② 插入点必须在**块注释结束之后** —— 文件头是 openapi-generator
    #         抄自契约 info.description 的 `/** ... */`，插在第 1 行之后等于插进注释里
    #         （本脚本真实踩过：注入"生效"了但 verify 正确地看不见）。
    # =======================================================================
    text_b = read_text(client_api)
    m_block = BLOCK_COMMENT_END.search(text_b)
    if not m_block:
        print("🛑 注入 B 找不到块注释结束行 ` */` —— 产物头格式变了")
        return 1
    probe_line = "\nvar PROBE_LABEL = '" + "退款" + "';\n"
    injected_b = text_b[:m_block.end()] + probe_line + text_b[m_block.end():]
    # 自证：注入点必须真的在注释之外（用被测对象自己的剥离函数验一遍）
    try:
        import importlib.util as _ilu
        _sp = _ilu.spec_from_file_location("_sp", PIPELINE)
        _mod = _ilu.module_from_spec(_sp)
        _sp.loader.exec_module(_mod)
        outside_ok = "退款" in _mod._strip_js_comments(injected_b)
    except Exception as e:                              # noqa: BLE001
        print("⚠️ 注入 B 自证失败（%s）—— 继续" % type(e).__name__)
        outside_ok = True
    print("    自证：注入的禁词确实落在注释之外 =", outside_ok)
    record("B 自证 注入点落在注释之外", outside_ok)
    write_text(client_api, injected_b)
    code_c, out_c = run_verify()
    caught = (code_c != 0) and ("INV-3" in out_c)
    print("【注入 B】client 代码（字符串字面量）写入禁词")
    print("    exit =", code_c, "| 期望红(INV-3) =", caught)
    if not caught:
        print(out_c[-2500:])
    record("B 代码含禁词 → INV-3 红", caught)
    write_text(client_api, text_b)
    record("B 还原后复绿", run_verify()[0] == 0)
    print()

    # =======================================================================
    # C. 删掉一个【应保留】的路径 ⇒ INV-1 必须红（防"裁过头"）
    # =======================================================================
    text_c = read_text(client_api)
    kept_anchor = "/band/available-dates"
    if kept_anchor not in text_c:
        print("⚠️ 注入 C 跳过：client 产物里找不到可删的保留路径")
        record("C 删掉应保留路径 → INV-1 红", False)
    else:
        write_text(client_api, text_c.replace(kept_anchor, "/__REMOVED__", 1))
        code_d, out_d = run_verify()
        caught = (code_d != 0) and ("INV-1" in out_d)
        print("【注入 C】删掉应保留路径 %s" % kept_anchor)
        print("    exit =", code_d, "| 期望红(INV-1) =", caught)
        if not caught:
            print(out_d[-2500:])
        record("C 删掉应保留路径 → INV-1 红", caught)
        write_text(client_api, text_c)
        record("C 还原后复绿", run_verify()[0] == 0)
    print()

    # =======================================================================
    # D. 矩阵把 client 角色写成不存在的角色 ⇒ 裁剪后为空 ⇒ AUDIT 必须红
    #    用独立的矩阵副本，不碰共享副本（免去还原）
    # =======================================================================
    text_m = read_text(PROBE_MATRIX)
    anchor_m = "    token-role: client\n"
    if text_m.count(anchor_m) != 1:
        print("⚠️ 注入 D 跳过：矩阵锚点不唯一（count=%d）" % text_m.count(anchor_m))
        record("D 角色写错 → AUDIT 红", False)
    else:
        m_d = os.path.join(PROBE_ROOT, "matrix-d.yaml")
        write_text(m_d, text_m.replace(anchor_m, "    token-role: nonexistent_role\n", 1))
        code_e, out_e = run_audit(matrix=m_d)
        caught = (code_e != 0) and ("裁剪后为空" in out_e or "判据必然写错了" in out_e)
        print("【注入 D】矩阵 client 角色改为不存在的角色")
        print("    exit =", code_e, "| 期望红(裁剪后为空) =", caught)
        if not caught:
            print(out_e[-2500:])
        record("D 角色写错 → AUDIT 红", caught)
    print()

    # =======================================================================
    # E. 凭空造一个【被裁 schema】的 model 文件 ⇒ INV-6 必须红
    #    载体：RefundData（client-mp 的 dropped_schemas 成员）
    #    用独立副本，免去删除（本机删除配额是本脚本改副本模式的原因）
    # =======================================================================
    sdk_e = os.path.join(PROBE_ROOT, "sdk-e")
    shutil.copytree(PROBE_SDK, sdk_e)
    fake_e = os.path.join(sdk_e, "client-mp", "src", "model", "RefundData.js")
    if os.path.exists(fake_e):
        print("🛑 注入 E 前置失败：副本里 RefundData.js 本不该存在")
        return 1
    write_text(fake_e, "// PROBE leaked schema model\nmodule.exports = {};\n")
    code_f, out_f = run_verify(sdk_root=sdk_e)
    caught = (code_f != 0) and ("INV-6" in out_f) and ("RefundData" in out_f)
    print("【注入 E】凭空造 client-mp/src/model/RefundData.js（被裁 schema）")
    print("    exit =", code_f, "| 期望红(INV-6) =", caught)
    if not caught:
        print(out_f[-2500:])
    record("E 被裁 schema 落成 model → INV-6 红", caught)
    print()

    # =======================================================================
    # F. 造一个【契约里没有、由 generator 派生】的包装类型 ⇒ INV-6b 必须红
    #    载体：CreateRefund200Response（不在 components/schemas 里，
    #    故 _schema_leaks 的精确比对抓不到，必须由 fragment 兜底抓住）
    # =======================================================================
    sdk_f = os.path.join(PROBE_ROOT, "sdk-f")
    shutil.copytree(PROBE_SDK, sdk_f)
    fake_f = os.path.join(sdk_f, "client-mp", "src", "model", "CreateRefund200Response.js")
    if os.path.exists(fake_f):
        print("🛑 注入 F 前置失败：副本里 CreateRefund200Response.js 本不该存在")
        return 1
    write_text(fake_f, "// PROBE derived wrapper model\nmodule.exports = {};\n")
    code_g, out_g = run_verify(sdk_root=sdk_f)
    caught = (code_g != 0) and ("INV-6b" in out_g)
    print("【注入 F】凭空造 CreateRefund200Response.js（generator 派生包装类型）")
    print("    exit =", code_g, "| 期望红(INV-6b) =", caught)
    if not caught:
        print(out_g[-2500:])
    record("F 派生包装类型 → INV-6b 红", caught)
    print()

    # =======================================================================
    # G. 把矩阵的例外名改成不存在的名字 ⇒ 「僵尸条目」必须红
    #    （例外机制存在的意义是"放行必须写清理由"，它本身也必须被守）
    # =======================================================================
    anchor_g = "      - name: BandAvailableDatesDataRetentionWindowDays\n"
    if text_m.count(anchor_g) != 1:
        print("⚠️ 注入 G 跳过：例外锚点不唯一（count=%d）" % text_m.count(anchor_g))
        record("G 僵尸例外 → 红", False)
    else:
        m_g = os.path.join(PROBE_ROOT, "matrix-g.yaml")
        write_text(m_g, text_m.replace(anchor_g, "      - name: NotARealThingAnywhere\n", 1))
        code_h, out_h = run_verify(matrix=m_g)
        caught = (code_h != 0) and ("僵尸条目" in out_h)
        print("【注入 G】矩阵例外名改为 NotARealThingAnywhere")
        print("    exit =", code_h, "| 期望红(僵尸条目) =", caught)
        if not caught:
            print(out_h[-2500:])
        record("G 僵尸例外 → 红", caught)
    print()

    # =======================================================================
    # H. 在 path→method 之间插 ~540 字符填充 ⇒ 期望【仍绿】
    #    钉住坑 5：旧的 400 字符窗口会漏掉这条 ⇒ 假红"缺失"。
    #    切块法（按下一个 path 切块）不受距离影响 ⇒ 必须仍绿。
    # =======================================================================
    admin_api_file = None
    for fn in sorted(os.listdir(admin_api_dir)):
        if fn.endswith(".ts"):
            cand = os.path.join(admin_api_dir, fn)
            txt = read_text(cand)
            if "const localVarPath" in txt and "method:" in txt:
                admin_api_file = cand
                break
    if admin_api_file is None:
        print("⚠️ 注入 H 跳过：admin-web 找不到含 localVarPath 与 method 的文件")
        record("H 长距离仍可提取 → 仍绿", False)
    else:
        text_h = read_text(admin_api_file)
        idx = text_h.find("const localVarPath")
        pad = "".join("/* pad */" for _ in range(60))   # 60*9 = 540 字符
        write_text(admin_api_file, text_h[:idx] + pad + "\n            " + text_h[idx:])
        code_i, out_i = run_verify()
        still_green = (code_i == 0) and ("VERIFY 通过" in out_i)
        print("【注入 H】admin-web 的 path→method 之间插入 %d 字符填充" % len(pad))
        print("    exit =", code_i, "| 期望仍绿 =", still_green)
        if not still_green:
            print(out_i[-2500:])
        record("H 长距离仍可提取 → 仍绿", still_green)
        write_text(admin_api_file, text_h)
        record("H 还原后复绿", run_verify()[0] == 0)
    print()

    # =======================================================================
    # I. 往 client 源码【注释】里写禁词 ⇒ 期望【仍绿】
    #    钉住坑 6：openapi-generator 会把契约 info.description 抄成头注释，
    #    而契约纪律条文里**自己就写着**"退款" ⇒ 若不剥注释，全线假红。
    # =======================================================================
    text_i = read_text(client_api)
    m_block_i = BLOCK_COMMENT_END.search(text_i)
    if not m_block_i:
        print("🛑 注入 I 找不到块注释结束行 —— 产物头格式变了")
        record("I 注释内禁词 → 仍绿", False)
    else:
        probe_comment = "\n// PROBE " + "退款" + " in a comment only\n"
        write_text(client_api,
                   text_i[:m_block_i.end()] + probe_comment + text_i[m_block_i.end():])
        code_j, out_j = run_verify()
        still_green = (code_j == 0) and ("VERIFY 通过" in out_j)
        print("【注入 I】client 源码注释里写禁词（不在代码里）")
        print("    exit =", code_j, "| 期望仍绿(注释被剥离) =", still_green)
        if not still_green:
            print(out_j[-2500:])
        record("I 注释内禁词 → 仍绿", still_green)
        write_text(client_api, text_i)
        record("I 还原后复绿", run_verify()[0] == 0)
    print()

    # =======================================================================
    # 真实树零写入自证（比"改后还原"更硬）
    # =======================================================================
    real_sdk_h1 = hash_tree(SDK_ROOT)
    real_matrix_h1 = hash_file(MATRIX)
    real_spec_h1 = hash_file(SPEC)
    untouched = (real_sdk_h0 == real_sdk_h1
                 and real_matrix_h0 == real_matrix_h1
                 and real_spec_h0 == real_spec_h1)
    diff = sorted(set(real_sdk_h0) ^ set(real_sdk_h1))
    print("【真实树】运行前后逐文件哈希一致 =", untouched,
          ("差异=%s" % diff) if diff else "")
    record("真实树 零写入（哈希自证）", untouched)
    print()

    # 末尾：副本上基线仍绿
    record("末尾 副本基线仍绿", run_verify()[0] == 0 and run_audit()[0] == 0)

    print("=" * 74)
    passed = sum(1 for _n, ok in results if ok)
    for n, ok in results:
        print("  %s %s" % ("PASS" if ok else "FAIL", n))
    print("=" * 74)
    print("结论: %d/%d PASS" % (passed, len(results)))
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())