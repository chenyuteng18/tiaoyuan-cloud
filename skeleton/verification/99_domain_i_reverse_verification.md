# S3-4 · 契约域 I 反向验证证据

三列证据：注入内容 | 预期失败点 | 实际失败断言原文

> 🛑 编码须知同 94_domain_b_reverse_verification.md：乱码为证据原貌，判定以英文方法名/码为准。

## I1 · I8 规则③ phone 脱敏被短路：maskPhone 恒返回原值

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/doctpl/service/DocTemplateService.java`
- 注入：`return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);` -> `return phone;`
- 捕捉者：真请求 E2E `DocTemplateE2ETest`
- 预期失败点：E2E 必须变红：渲染结果出现完整手机号（13812345678）—— 契约 I8 规则③「原名不得出现在文书内」被破坏
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   DocTemplateE2ETest$Render.render_with_mask:235 phone Ӧ����Ϊ ǰ3��4��138****5678����ʵ��: �ͻ� ���� �绰 13812345678 ==> expected: <true> but was: <false>
[INFO] 
```

## I2 · I8 规则②白名单越界被删：未在白名单的占位符被静默放过

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/doctpl/service/DocTemplateService.java`
- 注入：`if (!whitelist.contains(key)) {` -> `if (false && !whitelist.contains(key)) {`
- 捕捉者：真请求 E2E `DocTemplateE2ETest`
- 预期失败点：E2E 必须变红：未声明占位符（not_declared）不再 2004 —— 契约 I8 规则②「不静默留空」被破坏
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   DocTemplateE2ETest$Render.render_out_of_whitelist:273 δ�ڰ�������ռλ��Ӧ 403��2004����Լ I8 ����� ����Ĭ���գ�: {"code":0,"message":"OK","data":{"rendered_snapshot":"�ͻ� ���� �绰 {{phone}}","rendered_hash":"2aaee94b2f9841312b97d8513e531e3ecc4650b1cb3057b1d9437801b8d27f41","template_id":"630ca6ba-00f7-478d-ac52-192fdaf29eb0","version":1,"masked_phone":true},"trace_id":"2fc4e486a3a347d4b262289a3c16114b"} ==> expected: <403> but was: <200>
[INFO] 
```

## I3 · OPTIONAL-SKIP：规则①（整体 fail-closed）与规则②（逐 key 拒绝）对『空白名单 + 携占位符』是纵深双层 —— 破单层规则①会被规则②的 !whitelist.contains(key) 兜住，prove 不了牙齿（与 S3-3 的 gap_reason 双层防线同款）。故不注入。

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/doctpl/service/DocTemplateService.java`
- 注入：`__SKIP__` -> `__SKIP__`
- 捕捉者：领域单测 `DocTemplateE2ETest`
- 预期失败点：不注入。见 gap 说明。
- 判定：**OPTIONAL-SKIP**
- 实际失败断言原文：

```
(empty)
```

## I4 · 版本不可覆盖被破坏：version < 1 校验删除

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/doctpl/domain/DocTemplateRow.java`
- 注入：`if (version < 1) {` -> `if (false && version < 1) {`
- 捕捉者：领域单测 `DocTemplateRowTest`
- 预期失败点：单测必须变红：version=0 不再被拒 —— 契约 I4「版本递增不可覆盖」的领域闸被破坏
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   DocTemplateRowTest.version_lt_1_fails:76 Expected com.diaoyuanyun.dy.common.exception.BizException to be thrown, but nothing was thrown.
[INFO] 
```


被触碰源文件（逐字节还原校验）：

- `dy-app/src/main/java/com/diaoyuanyun/dy/app/doctpl/service/DocTemplateService.java`
- `dy-app/src/main/java/com/diaoyuanyun/dy/app/doctpl/domain/DocTemplateRow.java`

总体：全部被抓 且 已还原全绿
