#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
B-2 · 手环域幂等的反向验证 —— 「注入错误必须被抓住」。

针对 B-2（幂等为内存 Map ⇒ 多实例部署幂等失效）修复后落地的三条防线：
  J1  库层原子轴被抽掉：删掉 INSERT 的 `ON CONFLICT DO NOTHING`
      ⇒ 并发同键写入会撞唯一索引（23505 / DuplicateKeyException，真链路 = 500）
  J2  内存防线复发：给 BandService 加回一个 Map 实例字段
      ⇒ 结构性断言必须随即变红（不依赖并发时序，确定性）
  J3  日型谓词被削弱：删掉 findTelemetryId 日型分支的 `AND sport_id IS NULL`
      ⇒ 游标型行会被日型键误判为幂等命中（两个分支的幂等键混同）

锚点 ASCII / 唯一 / 逐字节还原 —— 同 93/95/96/97/98_*.py 纪律。

🛑 与 98_*.py 的关系：98 的 I1 锚点是 `BandService` 里那份**已删除**的内存 Map
   命中判断（`if (hit != null && hit.expiry() > ...)`）。B-2 收口时该字段已从源码移除，
   故 98 的 I1 锚点必然失效（ANCHOR-MISS）。**本文件是 98 的 I1 的替代与加强**：
   不仅覆盖"幂等被短路"，还把"内存防线复发"与"双分支谓词"一并纳入。
   98 的 I1 条目须相应更新为 SKIP 并指向本文件（见其 INJECTIONS 注释）。
"""

from __future__ import annotations

import atexit
import os
import re
import signal
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SKELETON = os.path.dirname(HERE)

# 🛑 兜底还原登记表：{绝对路径: 原始字节}
# 为什么需要它（一次真实事故）：初版只在注入点的 try/finally 里还原。
# 但本脚本要跑 3 轮 install+test（数分钟），若进程被 SIGTERM/超时中断，
# finally 不会执行 —— 于是"注入态"被留在工作区里，
# 下一次运行会在【基线】阶段就发现文件不干净（基线红），
# 而症状看起来像"测试自己坏了"，与真实原因（残留注入）隔了一层。
# 修法：进程退出/收到信号时，从本表无条件还原。
_SNAPSHOT: dict[str, bytes] = {}


def _restore_all() -> None:
    for path, raw in list(_SNAPSHOT.items()):
        try:
            current = read_bytes(path)
        except OSError:
            continue
        if current != raw:
            try:
                restore_bytes(path, raw)
                sys.stderr.write(f"\n[兜底还原] {path}\n")
            except OSError as e:
                sys.stderr.write(f"\n[兜底还原失败] {path}: {e}\n")


def _on_signal(signum, _frame):
    _restore_all()
    sys.stderr.write(f"\n🛑 收到信号 {signum}，已兜底还原后退出\n")
    sys.exit(3)


atexit.register(_restore_all)
for _sig in (signal.SIGTERM, signal.SIGINT):
    try:
        signal.signal(_sig, _on_signal)
    except (ValueError, OSError):
        # 非主线程或平台不支持时忽略 —— 不是致命问题
        pass

MAVEN_CMD = r"C:\opt\apache-maven-3.9.9\bin\mvn.cmd"
JAVA_HOME_WIN = r"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
PG_BIN_WIN = r"C:\Program Files\PostgreSQL\17\bin"
RUN_BASE = ["-o", "-q", "-DskipTests", "install"]

PG_ENV = {
    "DY_PG_HOST": "127.0.0.1",
    "DY_PG_PORT": "5432",
    "DY_PG_SUPER_PASSWORD": "postgres",
}

GATE = "BandIdempotencyConcurrencyTest"

INJECTIONS = [
    {
        "id": "J1",
        "gap": "库层原子轴被抽掉：删掉 INSERT 的 ON CONFLICT DO NOTHING",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/band/repository/BandLedger.java",
        # 逐字锚点：INSERT_TELEMETRY_SQL 末尾那一行（唯一）
        "old": '                    + " ON CONFLICT DO NOTHING";',
        "new": '                    + "";',
        "test": GATE + "$ConcurrentSameKey",
        # 🛑 must_see 必须是 **ASCII** 标记（见文件头 decode_output 的说明）：
        #    初版这里写的是中文断言原文「并发写入出现了」，而 Maven/surefire 在 Windows
        #    上把输出编成 GBK 字节流；主流程按 UTF-8 解码后中文全是乱码，
        #    于是 `must_see in ro` 恒为 False —— 注入**其实被抓到了**，
        #    却被判成"未被抓住"（假阴性）。教训：判定锚点不得依赖非 ASCII 文本。
        "must_see": "BandIdempotencyConcurrencyTest$ConcurrentSameKey"
                    ".concurrent_writes_on_same_key_yield_exactly_one_row_and_no_failure",
        "expected": "并发门禁必须变红：同键并发写入时，非首位的线程会撞 "
                    "uq_bt_daily_idempotent（23505 / DuplicateKeyException）。"
                    "真 HTTP 链路上那是一个 500 —— 正是 B-2 修复前让客户端收 500 的成因。",
    },
    {
        "id": "J2",
        "gap": "内存防线复发：给 BandService 加回一个 Map 实例字段",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/band/service/BandService.java",
        "old": "    private final BandLedger ledger;",
        "new": "    private final java.util.Map<String,String> probeCache"
               " = new java.util.concurrent.ConcurrentHashMap<>();\n"
               "    private final BandLedger ledger;",
        "test": GATE + "$NoInProcessMap",
        "must_see": "BandIdempotencyConcurrencyTest$NoInProcessMap"
                    ".band_service_has_no_in_process_map_field",
        "expected": "结构性断言必须变红：BandService 一旦长出进程内 Map 字段，"
                    "B-2 的缺陷形态即复发（幂等退化成单实例快路径）。"
                    "本条不依赖并发时序，是最可靠的确定性防线。",
    },
    {
        "id": "J3",
        "gap": "日型谓词被削弱：删掉 findTelemetryId 日型分支的 AND sport_id IS NULL",
        "target": "dy-app/src/main/java/com/diaoyuanyun/dy/app/band/repository/BandLedger.java",
        "old": '                                + " AND COALESCE(hour,-1) = ? AND COALESCE(minute,-1) = ?"\n'
               '                                + " AND sport_id IS NULL",',
        "new": '                                + " AND COALESCE(hour,-1) = ? AND COALESCE(minute,-1) = ?",',
        "test": GATE + "$BranchIsolation",
        "must_see": "BandIdempotencyConcurrencyTest$BranchIsolation"
                    ".sport_cursor_row_is_not_falsely_matched_by_the_daily_key",
        "expected": "分支隔离断言必须变红：日型键会命中一条 sport_id 非空的行，"
                    "两个分支的幂等键被混同（契约 §4.3 明令运动数据不得复用按日型幂等键）。",
    },
]


def read_bytes(path: str) -> bytes:
    with open(path, "rb") as f:
        return f.read()


def read_text(path: str) -> str:
    return read_bytes(path).decode("utf-8").replace("\r\n", "\n")


def write_text(path: str, content: str) -> None:
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(content)


def restore_bytes(path: str, raw: bytes) -> None:
    with open(path, "wb") as f:
        f.write(raw)


def mvn(args: list[str], extra_env: dict | None = None):
    env = dict(os.environ)
    parts = [os.path.dirname(MAVEN_CMD), PG_BIN_WIN]
    if os.path.isdir(JAVA_HOME_WIN):
        parts.append(os.path.join(JAVA_HOME_WIN, "bin"))
        env["JAVA_HOME"] = JAVA_HOME_WIN
    env["PATH"] = os.pathsep.join(parts + [env.get("PATH", "")])
    if extra_env:
        env.update(extra_env)
    proc = subprocess.run([MAVEN_CMD] + args, cwd=SKELETON, env=env,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    return proc.returncode, proc.stdout.decode("utf-8", errors="replace")


def build() -> tuple[int, str]:
    return mvn(RUN_BASE)


def run_test(test_class: str):
    args = ["-o", "-pl", "dy-app", "test",
            "-Dtest=" + test_class, "-DfailIfNoTests=false"]
    return mvn(args, PG_ENV)


def extract_failure_block(output: str) -> str:
    lines = output.splitlines()
    out = []
    in_block = False
    for ln in lines:
        if re.match(r"^\[ERROR\] (Failures|Errors):", ln):
            in_block = True
        if in_block:
            out.append(ln)
        if in_block and ln.startswith("[INFO]"):
            break
    if not out:
        # 断言原文也可能直接出现在 ERROR 行里（surefire 的 trimStackTrace=false 配置）
        out = [ln for ln in lines if "AssertionFailedError" in ln or "🛑" in ln]
    return "\n".join(out)


def main() -> int:
    report = []
    ok = True

    # 🛑 前置：先把所有目标文件登记进兜底表并检查锚点是否都命中。
    #    提前做这一步（而不是等循环到再查）是为了让"锚点失效"在【基线跑测试之前】
    #    就暴露 —— 否则要等几分钟的 install+test 白跑完才发现锚点早就不匹配了。
    for inj in INJECTIONS:
        path = os.path.join(SKELETON, inj["target"])
        if path not in _SNAPSHOT:
            _SNAPSHOT[path] = read_bytes(path)
        hits = read_text(path).count(inj["old"])
        if hits != 1:
            print(f"🛑 {inj['id']} 锚点命中 {hits} 次（要求恰 1 次）: {inj['old'][:140]}")
            ok = False
    if not ok:
        print("🛑 锚点预检未通过，终止（未改动任何文件）")
        return 2

    print("== 基线 ==", flush=True)
    bc, bo = build()
    if bc != 0:
        print(bo[-3000:]); print("🛑 基线 install 失败"); return 2
    rc, ro = run_test(GATE)
    print(f"  baseline {GATE}: {'PASS' if rc == 0 else 'FAIL'}", flush=True)
    if rc != 0:
        print(extract_failure_block(ro)[-2500:])
        print("🛑 基线不绿。⚠️ 先确认工作区没有被上一次中断的运行留下注入残留"
              "（见本文件 _SNAPSHOT 的说明）；若确有残留，用 git/备份还原后再跑。")
        return 2

    touched: dict[str, bytes] = dict(_SNAPSHOT)
    for inj in INJECTIONS:
        path = os.path.join(SKELETON, inj["target"])
        raw = _SNAPSHOT[path]
        original = raw.decode("utf-8").replace("\r\n", "\n")

        print(f"\n== {inj['id']} · {inj['gap']} ==", flush=True)
        hits = original.count(inj["old"])
        if hits == 0:
            print("🛑 锚点未命中: " + inj["old"][:160])
            ok = False
            report.append((inj, "ANCHOR-MISS", "", "锚点未命中"))
            continue
        if hits > 1:
            print(f"🛑 锚点不唯一（{hits} 次）")
            ok = False
            report.append((inj, "ANCHOR-AMBIGUOUS", "", f"{hits} 次"))
            continue

        write_text(path, original.replace(inj["old"], inj["new"], 1))
        try:
            bc, bo = build()
            if bc != 0:
                print("  注入后编译失败（也算被抓住）", flush=True)
                report.append((inj, "COMPILE-FAIL", extract_failure_block(bo), "编译失败"))
                ok = False
                continue
            rc, ro = run_test(inj["test"])
            caught = (rc != 0) and (inj["must_see"] in ro)
            print(f"  {inj['id']} 注入后 {inj['test']}: "
                  f"{'RED(被抓)' if rc != 0 else 'GREEN(未被抓!)'}", flush=True)
            if not caught:
                ok = False
                print("  🛑 未被抓住: " + inj["must_see"])
                print(extract_failure_block(ro)[-2000:])
                report.append((inj, "NOT-CAUGHT", extract_failure_block(ro), inj["expected"]))
            else:
                report.append((inj, "CAUGHT", extract_failure_block(ro), inj["expected"]))
        finally:
            restore_bytes(path, raw)
            print("  已还原: " + inj["target"], flush=True)

    print("\n== 逐字节还原校验 ==", flush=True)
    for path, raw in touched.items():
        now = read_bytes(path)
        rel = os.path.relpath(path, SKELETON).replace(os.sep, "/")
        if now == raw:
            print(f"  ✔ 逐字节一致: {rel}  ({len(raw)} 字节)", flush=True)
        else:
            ok = False
            print(f"  🛑 还原后不一致: {rel}", flush=True)

    print("\n== 还原后基线 ==", flush=True)
    bc, bo = build()
    if bc != 0:
        print(bo[-3000:]); ok = False
    else:
        rc, ro = run_test(GATE)
        print(f"  final {GATE}: {'PASS' if rc == 0 else 'FAIL'}", flush=True)
        if rc != 0:
            ok = False

    out_path = os.path.join(HERE, "99_band_idempotency_reverse_verification.md")
    with open(out_path, "w", encoding="utf-8", newline="") as f:
        f.write("# B-2 · 手环域幂等（并发/多实例）反向验证证据\n\n")
        f.write("三列证据：注入内容 | 预期失败点 | 实际失败断言原文\n\n")
        f.write("> 🛑 编码须知同 94/98_*.md：乱码为证据原貌，判定以英文方法名/码为准。\n\n")
        for inj, verdict, proof, expected in report:
            f.write(f"## {inj['id']} · {inj['gap']}\n\n")
            f.write(f"- 目标文件：`{inj['target']}`\n")
            f.write(f"- 注入：`{inj['old'][:140]}` -> `{inj['new'][:140]}`\n")
            f.write(f"- 捕捉者：真库并发门禁 `{inj['test']}`\n")
            f.write(f"- 预期失败点：{expected}\n")
            f.write(f"- 判定：**{verdict}**\n")
            f.write("- 实际失败断言原文：\n\n```\n")
            f.write((proof or "(empty)")[:4000])
            f.write("\n```\n\n")
        f.write("\n被触碰源文件（逐字节还原校验）：\n\n")
        for path in touched:
            rel = os.path.relpath(path, SKELETON).replace(os.sep, "/")
            f.write(f"- `{rel}`\n")
        f.write(f"\n总体：{'全部被抓 且 已还原全绿' if ok else '存在未通过项（见上）'}\n")

    print("\n证据已落盘: " + out_path, flush=True)
    print("\n总体: " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())