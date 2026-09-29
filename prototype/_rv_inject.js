/* 反向验证注入器（临时件，验证完毕即删）
   用法：node _rv_inject.js <mode> —— 读正式 index.html，注入一处缺陷，落到 /tmp/rv/index.html */
const fs = require('fs');
const path = require('path');
const SRC = 'C:/Users/lenovo/WorkBuddy/2026-09-16-10-37-59/deliverables/product-strategy/prototype/index.html';
const DST = path.join(__dirname, 'index.html');
const mode = process.argv[2];
let h = fs.readFileSync(SRC, 'utf8');
const before = h;
const D = "${'$'}";         // 源码里占位符写作 ${'$'}{name}（转义写法）

if (mode === 'qc6') {           // ① 删除 QC-6 不可省验收项
  h = h.replace('签署前全文可读 + 可回看（QC-6 附条件验收项）', '签署前全文可读');
} else if (mode === 'chap8') {  // ② 删除第 8 章「附则」
  h = h.replace('        8. 附则\r\n', '');
} else if (mode === 'upload') { // ③ 超管判定被旁路 → 上传位对所有角色可见
  h = h.replace('function isSuperAdmin(){ return curRole === \'all\' || SUPER_ADMIN_ROLES.indexOf(curRole) >= 0; }',
    'function isSuperAdmin(){ return true; }');
} else if (mode === 'ph5') {    // ④ 占位符表格少一行（6 → 5）
  h = h.replace('        <tr><td><code>' + D + '{plan.version}</code></td><td>V1.0</td></tr>\r\n', '');
} else if (mode === 'banph') {  // ⑤ 越界注册健康类占位符（合法表格行，仅越界一项）
  h = h.replace("<tr><td><code>" + D + "{plan.version}</code></td><td>V1.0</td></tr>",
    "<tr><td><code>" + D + "{plan.version}</code></td><td>V1.0</td></tr>\r\n" +
    "        <tr><td><code>" + D + "{screening_result}</code></td><td>禁忌结论</td></tr>");
} else if (mode === 'role') {   // ⑥ 把 06 屏 role 改成 hq（绕过条件渲染换权限）
  h = h.replace("d:'含终止/退出条款（对外禁用承诺性表述）', role:'therapist', gate:'门禁'}",
    "d:'含终止/退出条款（对外禁用承诺性表述）', role:'hq', gate:'门禁'}");
} else if (mode === 'mask') {   // ⑦ 删掉门禁横幅的「无效退款」合规约束
  h = h.replace('<b>合规约束：协议对外不得出现"无效退款"等承诺性表述</b>', '');
}

if (h === before) { console.log('!! 注入未生效: ' + mode); process.exit(9); }
fs.writeFileSync(DST, h);
console.log('已注入 ' + mode);