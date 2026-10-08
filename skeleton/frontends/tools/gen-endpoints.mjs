#!/usr/bin/env node
/**
 * `gen-endpoints.py` 的 Node 入口 —— 唯一目的是「用**正确的** Python 跑那个脚本」
 * ============================================================================
 *
 * 用法（与直接调 .py 完全一致，参数原样透传）
 *   node frontends/tools/gen-endpoints.mjs            # 写出四端端点层
 *   node frontends/tools/gen-endpoints.mjs --check    # 只校验、不写
 *
 * 🛑 为什么需要这一层（实测缺陷，2026-10-08）
 * ---------------------------------------------------------------------------
 * `package.json` 里原先是 `python ../tools/gen-endpoints.py [--check]`。这条写法
 * 有两个真问题，且都**只在特定机器/路径上显形**：
 *
 *   ① **裸 `python` 未必含 PyYAML** —— 生成器缺 PyYAML 时 `exit 2`
 *      （刻意如此：不退化成正则后照常打印 PASS）。实测本机
 *      `npm run check:contract` → `MISCONFIGURED: PyYAML is required (exit 2)`。
 *   ② **`py` 启动器会因 shebang 换解释器** —— 而 `gen-endpoints.py` 首行就是
 *      `#!/usr/bin/env python`（详见 `py.mjs` 的模块注释：`py -c` 与 `py <脚本>`
 *      会落到两个不同的解释器）。
 *
 * 🛑 影响面不是"本机不方便"，而是 **CI 会红**：`.github/workflows/build-and-test.yml`
 *    的 `frontend` job 里有一条 `npm run check:contract`（`check:contract` 即
 *    `gen-endpoints.py --check`）。所以这条链路必须走解析器，不能写裸 `python`。
 *
 * 退出码**原样透传**（0 通过 / 1 不通过 / 2 配置缺失），不做任何改写 ——
 * 上游按退出码判断的地方不应因为多了一层包装而改变行为。
 */

import { join } from 'node:path';
import { SKELETON_ROOT, TOOLS_DIR, runPythonScript } from './py.mjs';

const script = join(TOOLS_DIR, 'gen-endpoints.py');
const args = process.argv.slice(2);

const r = await runPythonScript(script, args, SKELETON_ROOT);
process.stdout.write(r.out);
if (r.python && process.env.DY_VERBOSE_PY) {
  process.stderr.write(`[gen-endpoints] interpreter = ${r.python}\n`);
}
process.exit(typeof r.code === 'number' ? r.code : 1);
