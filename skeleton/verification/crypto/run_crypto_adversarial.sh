#!/usr/bin/env bash
# ==============================================================================
# dy-crypto 对抗性验证探针 —— 独立 runner（第二步／门禁形态）
#
# 与实现方零共享面：
#   - 探针源码在 verification/crypto/AdversarialVerificationProbe.java
#     （不在任何模块的 src/test 下 ⇒ 不影响 `mvn -pl dy-crypto test` 的 Tests run）
#   - 编译产物落在【仓库外】的临时目录 ⇒ 不碰 dy-crypto/target，不与 a8 的构建抢锁
#   - 只【读取】dy-crypto/target/classes 作为 classpath，不写入
#
# 退出码：
#   0  全部探针 PASS
#   1  存在 FAIL（发现缺陷）—— 门禁应判红
#   2  存在 N/A（未能验证）—— 不算通过，门禁应判黄/红
#   3  runner 自身环境问题（找不到 javac / 未能编译 / 找不到 classes）
#
# 用法：
#   bash verification/crypto/run_crypto_adversarial.sh
# 可选环境变量：
#   CRYPTO_ADV_KEEP=1   保留临时编译目录（排障用）
# ==============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
PROBE_SRC="${SCRIPT_DIR}/AdversarialVerificationProbe.java"
CLASSES_DIR="${REPO_ROOT}/dy-crypto/target/classes"
EVIDENCE_DIR="${SCRIPT_DIR}/evidence"

# --- 平台适配：Windows 上的 java.exe 用 ';' 分隔 classpath，且不认 /c/... 形式的路径 ---
UNAME_S="$(uname -s 2>/dev/null || echo unknown)"
case "${UNAME_S}" in
  MINGW*|MSYS*|CYGWIN*) IS_WINDOWS=1 ;;
  *)                     IS_WINDOWS=0 ;;
esac
if [ "${IS_WINDOWS}" = "1" ]; then
  PATH_SEP=';'
  to_native() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
else
  PATH_SEP=':'
  to_native() { printf '%s' "$1"; }
fi

echo "======================================================================"
echo "dy-crypto ADVERSARIAL VERIFICATION PROBE"
echo "repo-root   : ${REPO_ROOT}"
echo "probe-src   : ${PROBE_SRC}"
echo "classes-dir : ${CLASSES_DIR}"
echo "evidence    : ${EVIDENCE_DIR}"
echo "======================================================================"

command -v javac >/dev/null 2>&1 || { echo "[RUNNER-ERROR] 找不到 javac（需要 JDK 17+ 在 PATH 上）"; exit 3; }
command -v java  >/dev/null 2>&1 || { echo "[RUNNER-ERROR] 找不到 java"; exit 3; }

if [ ! -f "${PROBE_SRC}" ]; then
  echo "[RUNNER-ERROR] 探针源码不存在：${PROBE_SRC}"
  exit 3
fi
if [ ! -d "${CLASSES_DIR}" ]; then
  echo "[RUNNER-ERROR] 找不到 dy-crypto 的编译产物：${CLASSES_DIR}"
  echo "              请先构建该模块（注意与并行构建者互斥）："
  echo "              mvn -pl dy-crypto -am test-compile"
  exit 3
fi

TMPDIR_ADV="$(mktemp -d -t crypto-adv-XXXXXX)"
# mktemp 在 MSYS 下可能返回 "C:\...\Temp/xxx" 这种混合形式；
# rm 需要 POSIX 形式，否则会被路径校验拒绝（表现为临时目录残留）。
if [ "${IS_WINDOWS}" = "1" ] && command -v cygpath >/dev/null 2>&1; then
  TMPDIR_ADV_POSIX="$(cygpath -u "${TMPDIR_ADV}")"
else
  TMPDIR_ADV_POSIX="${TMPDIR_ADV}"
fi
cleanup() {
  if [ "${CRYPTO_ADV_KEEP:-0}" = "1" ]; then
    echo "[runner] 保留临时目录：${TMPDIR_ADV_POSIX}"
  else
    rm -rf "${TMPDIR_ADV_POSIX}"
  fi
}
trap cleanup EXIT

echo "[runner] 临时编译目录：${TMPDIR_ADV}"
echo "[runner] 编译探针（仅 javac，不动 Maven 生命周期）..."
# javac 读得了 MSYS 风格路径；java -cp 则必须用平台原生路径与分隔符
if ! javac -encoding UTF-8 -d "$(to_native "${TMPDIR_ADV}")" \
        -cp "$(to_native "${CLASSES_DIR}")" \
        "$(to_native "${PROBE_SRC}")"; then
  echo "[RUNNER-ERROR] 探针编译失败"
  exit 3
fi

mkdir -p "${EVIDENCE_DIR}"

JAVA_CP="$(to_native "${TMPDIR_ADV}")${PATH_SEP}$(to_native "${CLASSES_DIR}")"
echo "[runner] java classpath：${JAVA_CP}"
echo "[runner] 执行探针矩阵..."
java -Dfile.encoding=UTF-8 \
     -Dcrypto.repo.root="$(to_native "${REPO_ROOT}")" \
     -Dcrypto.evidence.dir="$(to_native "${EVIDENCE_DIR}")" \
     -cp "${JAVA_CP}" \
     AdversarialVerificationProbe
EXIT_CODE=$?

echo "======================================================================"
echo "[runner] 探针退出码 = ${EXIT_CODE}"
case "${EXIT_CODE}" in
  0) echo "[runner] 结论：全部探针 PASS —— 门禁判绿";;
  1) echo "[runner] 结论：存在 FAIL —— 门禁判红（发现缺陷）";;
  2) echo "[runner] 结论：存在 N/A —— 未能验证不等于通过，门禁判红";;
  *) echo "[runner] 结论：探针异常退出（${EXIT_CODE}）—— 门禁判红";;
esac
echo "[runner] 报告：${EVIDENCE_DIR}/adversarial-report.txt"
echo "======================================================================"

exit "${EXIT_CODE}"