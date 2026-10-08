/**
 * Python 解释器解析 —— 本仓所有「要跑 .py 脚本」的调用点共用的**唯一一份**实现
 * ============================================================================
 *
 * 为什么单独抽出这个模块
 * ---------------------------------------------------------------------------
 * 本仓有两类地方要跑 Python：
 *   · `frontends/tools/build-check.mjs`（端 C 构建自检 → `gen-endpoints.py --check`）
 *   · `package.json` 的 `gen:endpoints` / `check:contract`（→ `gen-endpoints.py`）
 * 原先两类的写法不同：前者有解析器，后者直接写 `python ../tools/gen-endpoints.py`。
 * 后果实测（2026-10-08）：`npm run check:contract` **exit 2**
 * （`MISCONFIGURED: PyYAML is required`），而 **CI 的 frontend job 正是跑这条命令**。
 * ⇒ 同一件事两套写法 ⇒ 其中一套没有走到正确解释器。
 * 故抽成一份，两处共用；新增调用点时也只应 `import { runPythonScript } from './py.mjs'`。
 *
 * 🛑 解释器为什么会"选错"（本仓"验证手段本身会骗人"第二例）
 * ---------------------------------------------------------------------------
 * 探针与真实调用**必须同形**，否则探针测的不是你要跑的那个东西：
 *   · 探针跑的是 `-c "import yaml"`（**没有脚本文件**）；
 *   · 真实跑的是 `gen-endpoints.py`（**是一个脚本文件**）。
 * 而 Windows 的 `py` 启动器会读脚本首行的 shebang —— `gen-endpoints.py` 首行是
 * `#!/usr/bin/env python`，启动器于是**用 PATH 上的 `python` 重新解析**。
 * 实测（同一个 `py`、同一条命令，只差"有没有脚本文件"）：
 *
 *     py -c "import sys;print(sys.executable)"   → Python312      + yaml 6.0.3
 *     py <带 shebang 的 .py>                      → 3.13.12        No module named 'yaml'
 *     py <去掉 shebang 的 .py>                    → Python312      + yaml 6.0.3
 *
 * ⇒ 只要探针只测 `-c`，就会出现"探针通过、真调用 exit 2"，
 *   而报错指向"契约不一致"这个**完全错误**的方向。
 *
 * 解法不是"把 shebang 删掉"（那是把宿主平台的怪癖写进语言规范），而是：
 * **让候选自报绝对解释器路径，之后一律用该绝对路径执行脚本** ——
 * 从此不再经过 `py` 启动器，shebang 也就不参与解释器选择。
 * 这一条对 `python` / `python3` 同样成立（Windows 上 PATH 里的 `python`
 * 可能是 Microsoft Store 的 0 字节别名）。
 */

import { existsSync, readdirSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';

const HERE = dirname(fileURLToPath(import.meta.url));

/**
 * 跑一条命令，返回 `{ code, out }`（out 同时收 stdout 与 stderr）。
 *
 * 🛑 用异步 spawn 而非 execFileSync：受限环境里**同步**派生对任何命令都 EBUSY，
 *    但**异步**可用（见 `build-check.mjs` 文件头"派生子进程"一节）。
 *    同步接口会把可做的检查误判为"环境受限"。
 */
export function runProc(cmd, argv, cwd) {
  return new Promise((res) => {
    let out = '';
    let child;
    try {
      child = spawn(cmd, argv, { cwd, stdio: ['ignore', 'pipe', 'pipe'] });
    } catch (e) {
      res({ code: -1, out: String((e && e.message) || e) });
      return;
    }
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { out += d; });
    child.on('error', (e) => res({ code: -1, out: String((e && e.message) || e) }));
    child.on('close', (code) => res({ code: code === null ? -1 : code, out }));
  });
}

const PROBE = 'import sys, yaml; print("YAML_OK::" + sys.executable)';

/**
 * 找一个能 `import yaml` 的 Python，返回它**自报的绝对解释器路径**。
 *
 * 候选顺序即优先级：
 *   ① `DY_PY`（显式覆盖，最高优先）
 *   ② 受管 venv（`~/.workbuddy/binaries/python/envs/<v>/…`）—— 项目依赖按隔离纪律
 *      装在这里，故它**必须**先于裸解释器命中，否则会稳定地选到"能跑但缺 yaml"的那个
 *   ③ 受管裸解释器（`~/.workbuddy/binaries/python/versions/<v>/…`）
 *   ④ PATH 上的 `python3` / `python` / `py`
 *
 * 返回 `null` 表示**一个都没有**（调用方应报 MISCONFIGURED，而不是当成"检查通过"）。
 */
export async function resolvePython() {
  const candidates = [process.env.DY_PY].filter(Boolean);
  const home = process.env.USERPROFILE || process.env.HOME || '';
  const pyRoot = home ? join(home, '.workbuddy', 'binaries', 'python') : '';
  const envsDir = pyRoot ? join(pyRoot, 'envs') : '';
  if (envsDir && existsSync(envsDir)) {
    for (const v of readdirSync(envsDir).sort().reverse()) {
      candidates.push(join(envsDir, v, process.platform === 'win32' ? 'Scripts/python.exe' : 'bin/python'));
    }
  }
  const versionsDir = pyRoot ? join(pyRoot, 'versions') : '';
  if (versionsDir && existsSync(versionsDir)) {
    for (const v of readdirSync(versionsDir).sort().reverse()) {
      candidates.push(join(versionsDir, v, process.platform === 'win32' ? 'python.exe' : 'bin', 'python'));
    }
  }
  candidates.push('python3', 'python', 'py');
  for (const cand of candidates) {
    const r = await runProc(cand, ['-c', PROBE], HERE);
    if (r.code !== 0) continue;
    const m = /YAML_OK::(.+)/.exec(r.out);
    if (!m) continue;
    // 拿不到绝对路径时退回候选名 —— 宁可退化，也不因解析失败把整项判成"未验证"。
    return m[1].trim() || cand;
  }
  return null;
}

/** `resolvePython()` 失败时的统一措辞（各调用点口径一致，便于检索）。 */
export const NO_PYTHON_MESSAGE =
  'MISCONFIGURED: 找不到可执行的 Python（且需含 PyYAML）。\n'
  + '  · 安装：python -m pip install -r requirements.txt（见 skeleton/requirements.txt）\n'
  + '  · 或指定：环境变量 DY_PY=<绝对路径>（Windows 上注意 `py <脚本>` 与 `py -c` 可能落到不同解释器）\n';

/**
 * 用正确解释器跑一个 .py 脚本。返回 `{ code, out }`；找不到解释器时
 * 返回 `code: 2`（沿用 compliance 的退出码图例：2 = 配置缺失）。
 */
export async function runPythonScript(scriptPath, args, cwd) {
  const py = await resolvePython();
  if (!py) return { code: 2, out: NO_PYTHON_MESSAGE, python: null };
  const r = await runProc(py, [scriptPath, ...args], cwd);
  return { ...r, python: py };
}

export const TOOLS_DIR = HERE;
export const SKELETON_ROOT = resolve(HERE, '..', '..');
export const FRONTENDS_ROOT = resolve(HERE, '..');
