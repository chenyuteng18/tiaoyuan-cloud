# -*- coding: utf-8 -*-
"""独立证明：v1 规范串的单射性不是修辞 —— 它堵掉的是一条**真实存在**的碰撞路径。

<h2>为什么需要这份证明</h2>
AuditChainHash 的类注释声称"naive 的 '|'.join 不是单射，会让人跨字段边界挪字节伪造，
故用转义 + 空值标记"。这句话如果只是注释，就只是**一个未经检验的设计主张** ——
代码看着"有转义"，谁也不能保证它真的消除了碰撞（比如转义顺序写错、漏转义某个字符，
都仍然写出"有转义"的代码，却留着碰撞路径）。

故本脚本用 **CPython 的 hashlib 独立复算**（不使用被测 Java 代码），构造出
**naive 实现下真实碰撞**的两对输入，并同时算出 v1 实现下它们**不碰撞**。
这样"转义是承重的"就有了可复现的阴性对照。

<h2>这个脚本本身就是被测对象（负向守护）</h2>
{@code AuditChainInjectivityEvidenceTest} 会执行本脚本，并断言：
  ① naive 一侧**必须**碰撞（若脚本改了、或 naive 定义被"修好"了，阴性对照失效 -> 测试红）；
  ② v1 一侧**必须不**碰撞。
若有人今后把生产代码的转义改回 naive 拼接，①仍会通过（脚本独立于生产代码），
但 golden vector 与 gate 测试会红 —— 两条防线各管一段。

<h2>用法</h2>
    python injectivity_proof.py            # 人类可读输出
    python injectivity_proof.py --machine  # 机器可判输出（供测试断言，键=值 一行一项）

退出码：0 = 证明成立（naive 碰撞 + v1 不碰撞）；1 = 证明不成立。
"""

import hashlib
import sys

# ---- v1 规范（与 AuditChainHash 的冻结常量一致）----
V1_LABEL = "dy-audit-chain/v1"
NULL_PREFIX = "~null~"
STRING_PREFIX = "~s~"


def escape(s: str) -> str:
    """与 AuditChainHash.escape 同序：反斜杠**首先**替换，否则会二次转义。"""
    return (s.replace("\\", "\\\\")
             .replace("|", "\\|")
             .replace("\n", "\\n")
             .replace("\r", "\\r"))


def field_v1(value):
    """v1 规范：null 与字符串是两种不同编码，字符串带前缀 + 转义。"""
    return NULL_PREFIX if value is None else STRING_PREFIX + escape(value)


def field_naive(value):
    """naive 规范：直接拼接，不转义、不区分 null。
    这正是"看起来也能用"的那种实现 —— 单元测试会过，碰撞路径却留着。"""
    return "" if value is None else value


def sha256_hex(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()


# 固定上下文（值本身与是否碰撞无关，只要两边一致即可）
TENANT = "11111111-1111-1111-1111-111111111111"
TARGET_TYPE = "customer"
TARGET_ID = "c-1"
PREV_HASH = "0" * 64


def canonical(actor, action, payload, field_fn) -> str:
    parts = [TENANT, actor, action, TARGET_TYPE, TARGET_ID, payload, PREV_HASH]
    return V1_LABEL + "\n" + "|".join(field_fn(p) for p in parts)


# ============================================================
# 碰撞对 1：把 '|' 在 actor / action 两个字段之间搬移
#   naive: "a|b" | "c"   ==   "a" | "b|c"   （字段边界不可辨 -> 同一规范串）
# ============================================================
PAIR1_A = ("a|b", "c", '{"n":1}')
PAIR1_B = ("a", "b|c", '{"n":1}')


# ============================================================
# 碰撞对 2：null 与"空串"在 naive 下不可区分
#   naive: None -> "" ，空串 -> "" ，两者同一规范串
# ============================================================
PAIR2_A = ("actor", "ACT", None)
PAIR2_B = ("actor", "ACT", "")


def main() -> int:
    machine = "--machine" in sys.argv
    out = []

    def emit(line: str = ""):
        out.append(line)
        if not machine:
            print(line)

    # ---- 碰撞对 1 ----
    n1a = canonical(*PAIR1_A, field_naive)
    n1b = canonical(*PAIR1_B, field_naive)
    v1a = canonical(*PAIR1_A, field_v1)
    v1b = canonical(*PAIR1_B, field_v1)

    naive_collides_1 = (n1a == n1b) and (sha256_hex(n1a) == sha256_hex(n1b))
    v1_distinct_1 = (v1a != v1b) and (sha256_hex(v1a) != sha256_hex(v1b))

    emit("=" * 78)
    emit("碰撞对 1: A=(actor='a|b', action='c')  vs  B=(actor='a', action='b|c')")
    emit("=" * 78)
    emit(f"naive 规范串 A == B ? {n1a == n1b}")
    emit(f"naive 哈希   A == B ? {sha256_hex(n1a) == sha256_hex(n1b)}   {sha256_hex(n1a)}")
    emit(f"v1    规范串 A == B ? {v1a == v1b}")
    emit(f"v1    哈希   A == B ? {sha256_hex(v1a) == sha256_hex(v1b)}")
    emit(f"  v1 A 哈希 = {sha256_hex(v1a)}")
    emit(f"  v1 B 哈希 = {sha256_hex(v1b)}")

    # ---- 碰撞对 2 ----
    n2a = canonical(*PAIR2_A, field_naive)
    n2b = canonical(*PAIR2_B, field_naive)
    v2a = canonical(*PAIR2_A, field_v1)
    v2b = canonical(*PAIR2_B, field_v1)

    naive_collides_2 = (n2a == n2b) and (sha256_hex(n2a) == sha256_hex(n2b))
    v1_distinct_2 = (v2a != v2b) and (sha256_hex(v2a) != sha256_hex(v2b))

    emit()
    emit("=" * 78)
    emit("碰撞对 2: A=(payload=None)  vs  B=(payload='')")
    emit("=" * 78)
    emit(f"naive: None 与 空串 规范串相同 ? {n2a == n2b}  (两者都变空)")
    emit(f"naive: 哈希相同 ? {sha256_hex(n2a) == sha256_hex(n2b)}   {sha256_hex(n2a)}")
    emit(f"v1   : None 与 空串 规范串相同 ? {v2a == v2b}")
    emit(f"v1   : 哈希相同 ? {sha256_hex(v2a) == sha256_hex(v2b)}")
    emit(f"  v1 None  = {sha256_hex(v2a)}")
    emit(f"  v1 空串  = {sha256_hex(v2b)}")

    emit()
    ok = (naive_collides_1 and v1_distinct_1
          and naive_collides_2 and v1_distinct_2)
    emit("结论: 转义 + 空值前缀把『两类不同输入产生同一哈希』的路径堵掉。")
    emit("      这不是修辞 —— naive 一侧是真碰撞（同一 SHA-256），v1 一侧不是。")
    emit(f"阴性对照成立: {ok}")

    if machine:
        # 供测试解析的机器可判格式
        print(f"naive_collides_pair1={str(naive_collides_1).lower()}")
        print(f"v1_distinct_pair1={str(v1_distinct_1).lower()}")
        print(f"naive_collides_pair2={str(naive_collides_2).lower()}")
        print(f"v1_distinct_pair2={str(v1_distinct_2).lower()}")
        print(f"proof_holds={str(ok).lower()}")
        print(f"naive_collision_hash_pair1={sha256_hex(n1a)}")
        print(f"v1_hash_pair1_A={sha256_hex(v1a)}")
        print(f"v1_hash_pair1_B={sha256_hex(v1b)}")

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())