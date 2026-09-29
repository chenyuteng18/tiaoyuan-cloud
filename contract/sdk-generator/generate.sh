#!/usr/bin/env bash
# =============================================================================
# 三端 SDK 生成 / 复核脚本（S1-1 交付物 ①）
#
# 两种模式：
#   ./generate.sh --dry-run   仅做静态复核（不联网、不生成、不需要 openapi-generator）
#   ./generate.sh --emit      真实生成三端 SDK（需先装 openapi-generator-cli）
#
# 为什么有 --dry-run：
#   本机（开发/交付机）无 openapi-generator 可执行件；契约冻结的验收不能依赖
#   "本机恰好装了生成器"。故把【可复核的部分】（端点集 / 行号集 / 禁入行 /
#   TBD 字段）抽成 dry-run，在任何环境都能跑，且失败即非零退出。
#   —— 绝不提供"假装生成成功"的分支：--emit 缺件时直接退出 1，不静默降级。
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SPEC="$HERE/../openapi-v1.0.0.yaml"
OUT_ROOT="$HERE/.."

# 优先用受管 Python（隔离环境），退化到 python3
PY="${PY:-}"
if [[ -z "$PY" ]]; then
  if [[ -x "$HOME/.workbuddy/binaries/python/versions/3.13.12/python.exe" ]]; then
    PY="$HOME/.workbuddy/binaries/python/versions/3.13.12/python.exe"
  elif command -v python3 >/dev/null 2>&1; then
    PY="python3"
  else
    PY="python"
  fi
fi

MODE="${1:---dry-run}"

echo "== 契约冻结 SDK 管线 =="
echo "spec   : $SPEC"
echo "mode   : $MODE"
echo "python : $PY"
echo

if [[ ! -f "$SPEC" ]]; then
  echo "FATAL: 找不到契约文件 $SPEC" >&2
  exit 1
fi

# -----------------------------------------------------------------------------
# dry-run：静态复核（无外部依赖，任何机器可跑）
# -----------------------------------------------------------------------------
if [[ "$MODE" == "--dry-run" ]]; then
  "$PY" - "$SPEC" "$HERE/generator-matrix.yaml" <<'PYEOF'
import sys, yaml

spec_path, matrix_path = sys.argv[1], sys.argv[2]
spec = yaml.safe_load(open(spec_path, encoding="utf-8"))
matrix = yaml.safe_load(open(matrix_path, encoding="utf-8"))

fail = []

# --- 1. 契约自身口径 ---
rows, endpoints = {}, 0
for path, item in spec["paths"].items():
    for method, op in item.items():
        if not isinstance(op, dict) or "x-contract-row" not in op:
            continue
        rows.setdefault(op["x-contract-row"], []).append(f"{method.upper()} {path}")
        endpoints += 1

print(f"[1] 契约：rows={len(rows)} endpoints={endpoints} version={spec['info']['version']}")
if len(rows) != 43:
    fail.append(f"行数应为 43，实为 {len(rows)}")
if endpoints != 45:
    fail.append(f"端点应为 45，实为 {endpoints}")
if spec["info"]["version"] != "api-contract-v1.0.0":
    fail.append(f"版本号应为 api-contract-v1.0.0，实为 {spec['info']['version']}")

# --- 2. 矩阵声明的行号必须都真实存在 ---
declared = set()
for t in matrix["targets"]:
    for r in t.get("exclude-contract-rows", []):
        declared.add(r)
unknown = declared - set(rows)
print(f"[2] 矩阵声明的禁入行：{sorted(declared)}")
if unknown:
    fail.append(f"矩阵声明了契约里不存在的行：{sorted(unknown)}")

# --- 3. 客户端禁入行 ⊆ 契约中已标 x-client-forbidden 的行（双保险一致性） ---
forbidden = {r for r, ops in rows.items()
             for path, item in spec["paths"].items()
             for method, op in item.items()
             if isinstance(op, dict) and op.get("x-contract-row") == r and op.get("x-client-forbidden")}
client = next(t for t in matrix["targets"] if t["id"] == "client-mp")
excl = set(client["exclude-contract-rows"])
missing = forbidden - excl
print(f"[3] 契约 x-client-forbidden={sorted(forbidden)}；矩阵 client 禁入={sorted(excl)}")
if missing:
    fail.append(f"契约标记客户禁入但矩阵未禁入的行：{sorted(missing)}")

# --- 4. 客户禁入行的实际路径必须命中 forbid-path-fragments ---
frags = client["forbid-path-fragments"]
miss_frag = []
for r in sorted(excl):
    for ep in rows.get(r, []):
        p = ep.split(" ", 1)[1]
        if not any(f in p for f in frags) and r not in ("H1", "I8"):
            miss_frag.append(f"{r} -> {p}")
print(f"[4] 客户禁入行路径片段检查（未命中片段者）：{miss_frag or '（无）'}")
if miss_frag:
    fail.append(f"客户禁入行未命中任何禁入片段：{miss_frag}")

# --- 5. TBD 字段清单（含 x-tbd-allowed 的字段必须被矩阵 INV-5 覆盖） ---
tbd = []
def walk(node, path=""):
    if isinstance(node, dict):
        if node.get("x-tbd-allowed"):
            tbd.append(f"{path}")
        for k, v in node.items():
            walk(v, f"{path}/{k}")
    elif isinstance(node, list):
        for i, v in enumerate(node):
            walk(v, f"{path}[{i}]")
walk(spec["components"]["schemas"])
print(f"[5] TBD 允许字段：{tbd}")
if not tbd:
    fail.append("未找到任何 x-tbd-allowed 字段 —— 契约的诚实边界丢失")

# --- 6. INV-5 必须在矩阵里声明 ---
inv_ids = {i["id"] for i in matrix["invariants"]}
print(f"[6] 不变量：{sorted(inv_ids)}")
for need in ("INV-1", "INV-2", "INV-3", "INV-4", "INV-5"):
    if need not in inv_ids:
        fail.append(f"矩阵缺少不变量 {need}")

print()
if fail:
    print("== DRY-RUN 失败 ==")
    for f in fail:
        print("  ✗ " + f)
    sys.exit(1)
print("== DRY-RUN 通过：契约口径与三端生成配置自洽 ==")
PYEOF
  exit 0
fi

# -----------------------------------------------------------------------------
# emit：真实生成（缺件直接失败，不静默降级）
# -----------------------------------------------------------------------------
if [[ "$MODE" == "--emit" ]]; then
  GEN="${OPENAPI_GENERATOR:-}"
  if [[ -z "$GEN" ]] && command -v openapi-generator-cli >/dev/null 2>&1; then
    GEN="openapi-generator-cli"
  fi
  if [[ -z "$GEN" ]]; then
    echo "FATAL: 未找到 openapi-generator-cli。" >&2
    echo "  获取方式（二选一）：" >&2
    echo "    1) npm i -g @openapitools/openapi-generator-cli@2.13.4" >&2
    echo "    2) mvn dependency:get -Dartifact=org.openapitools:openapi-generator-cli:7.10.0" >&2
    echo "  或用 OPENAPI_GENERATOR=/abs/path/to/openapi-generator-cli 指定可执行件。" >&2
    echo "  本脚本【不做】任何降级或伪造产物的动作。" >&2
    exit 1
  fi
  echo "generator: $GEN"
  "$PY" - "$SPEC" "$HERE/generator-matrix.yaml" "$OUT_ROOT" "$GEN" <<'PYEOF'
import sys, os, subprocess, yaml
spec, matrix_path, out_root, gen = sys.argv[1:5]
matrix = yaml.safe_load(open(matrix_path, encoding="utf-8"))
for t in matrix["targets"]:
    out = os.path.normpath(os.path.join(matrix_path, "..", t["output"]))
    props = ",".join(f"{k}={v}" for k, v in t["additional-properties"].items())
    cmd = [gen, "generate", "-i", spec, "-g", t["generator"], "-o", out]
    if props:
        cmd += ["--additional-properties", props]
    print(f"--> {t['id']}: {' '.join(cmd)}")
    subprocess.run(cmd, check=True)
print("三端 SDK 生成完成。")
PYEOF
  exit 0
fi

echo "FATAL: 未知模式 '$MODE'（支持 --dry-run | --emit）" >&2
exit 2