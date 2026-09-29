# S3-1 · 契约域 C 反向验证证据

三列证据：注入内容 | 预期失败点 | 实际失败断言原文

> 🛑 编码须知同 94_domain_b_reverse_verification.md：Maven 前缀行 GBK 与 surefire 断言消息 UTF-8 并存，『实际失败断言原文』刻意保留乱码为证据原貌，判定以英文方法名/码为准。

## I1 · 码无主：把 C2 的 assessment:write 改成一个注册表里不存在的码

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/assessment/controller/AssessmentController.java`
- 注入（old -> new）：`@RequirePermission("assessment:write")
    @PostMapping("/customers/{id}/assessments/baseline")` -> `@RequirePermission("assessment:phantom")
    @PostMapping("/customers/{id}/assessments/baseline")`
- 捕捉者：码级门禁 `PermissionCodeRegistrationGateTest`
- 预期失败点：码级门禁第②条必须报出 orphans 非空（assessment:phantom 被声明、却无人持有）
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   PermissionCodeRegistrationGateTest.every_require_permission_code_has_at_least_one_holder:190 ? ����Ȩ���뱻 @RequirePermission ������ȴ�� PermissionRegistry �û���κν�ɫ���С� ���� �������ǵĶ˵��ԡ�ȫ����ɫ��403��������Լ�����ɵ��Ľ�ɫ����[assessment:phantom]��
�ѵǼǵ���: [*, assessment:write, customer:archive, customer:read, customer:write, refund:approve, refund:read, refund:write, report:region, store:read, verdict:read, verdict:write]��
����: {assessment:phantom=[dy-app/src/main/java/com/diaoyuanyun/dy/app/assessment/controller/AssessmentController.java]}��
? �����Ǳ��ֿ��Ѿ����������ε�ͬ��ȱ�ݣ���ɫ���Сд�ֲ� / refund:* ��δ�Ǽǣ����� ���ζ�ֻ���������±�¶�������ڴ˴����Ǽǣ���Ҫ�ſ������ԡ� ==> expected: <true> but was: <false>
[ERROR]   PermissionCodeRegistrationGateTest.scanner_actually_sees_the_annotations_and_their_codes:158 ɨ������������� assessment:write������Ȼ������Դ���У���ʵ���뼯: [assessment:phantom, customer:archive, customer:read, customer:write, refund:approve, refund:read, refund:write, store:read, verdict:read, verdict:write] ==> expected: <true> but was: <false>
[INFO] 
```

## I2 · migratable 四要素推导被改坏：把【题组ID为空 → false】删成恒 true

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/assessment/service/AssessmentService.java`
- 注入（old -> new）：`boolean migratable = itemGroupId != null && scaleVersion != null;` -> `boolean migratable = true;`
- 捕捉者：真请求 E2E `AssessmentDomainCEndpointsE2ETest`
- 预期失败点：真请求 E2E 必须变红：缺 item_group_id 时仍 migratable=true —— 四要素推导被静默破坏，effect_verdict=NULL 的防线失守
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   AssessmentDomainCEndpointsE2ETest$BaselineSubmit.missing_item_group_is_migratable_false:305 ȱ����IDӦ migratable=false����Ҫ���뱸�� true��: {"code":0,"message":"OK","data":{"assessment_id":"b4d3fb57-6901-411e-9408-ebf8e0f92a02","assessed_at":"2026-09-25T21:17:51.256391600Z","baseline_conclusion":{"haozhuan":null,"_note":"��ת�ж�����Լ C2 �����δ���ᣬ����˲����죨�� AssessmentService ��ע�ͣ�"},"migratable":true,"dimension_scores":[8,8,8,8,8,8,8]},"trace_id":"22dba5d82ea445b092f5816760d3a6d3"} ==> expected: <false> but was: <true>
[INFO] 
```

## I3 · 维度分/总分自洽校验被删：总分≠Σ维度分时不再阻断

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/assessment/service/AssessmentService.java`
- 注入（old -> new）：`if (sum != totalScore) {` -> `if (false) {`
- 捕捉者：真请求 E2E `AssessmentDomainCEndpointsE2ETest`
- 预期失败点：真请求 E2E 必须变红：总分 55 ≠ Σ维度分 56 时不再 422 —— 不自洽的数据会静默落库，让同源复评的改善率分母漂移
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   AssessmentDomainCEndpointsE2ETest$BaselineSubmit.inconsistent_scores_is_422:335 �ܷ֡٦�ά�ȷ�Ӧ 422: {"code":0,"message":"OK","data":{"assessment_id":"b02c9e50-ae5c-47c0-8028-389362cd5f48","assessed_at":"2026-09-25T21:18:31.280014800Z","baseline_conclusion":{"haozhuan":null,"_note":"��ת�ж�����Լ C2 �����δ���ᣬ����˲����죨�� AssessmentService ��ע�ͣ�"},"migratable":false,"dimension_scores":[8,8,8,8,8,8,8]},"trace_id":"4734b4671612437a9d889106ec59d91e"} ==> expected: <422> but was: <200>
[INFO] 
```

## I4 · 客户不下发 migratable 的裁剪被删：客户响应体出现 migratable 键

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/assessment/service/AssessmentService.java`
- 注入（old -> new）：`public static boolean migratableVisibleTo(VisibilityRole role) {
        return role != null && role != VisibilityRole.C` -> `public static boolean migratableVisibleTo(VisibilityRole role) {
        return true;
    }`
- 捕捉者：真请求 E2E `AssessmentDomainCEndpointsE2ETest`
- 预期失败点：真请求 E2E 必须变红：客户的 C3 响应体出现 migratable —— 契约 BaselineAssessmentData.migratable 的 x-visible-to 不含 client，无权限字段必须【不下发】（契约硬约束①）
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   AssessmentDomainCEndpointsE2ETest$AssessmentDetail.client_sees_no_migratable:373 �ͻ���Ӧ�岻�ó��� migratable��x-visible-to ���� client��: {"assessment_id":"43d85ee0-95c2-45bb-a6c5-cf4a430819f4","assessed_at":"2026-09-25T21:19:13.126445Z","baseline_conclusion":{"haozhuan":null,"_note":"��ת�ж�����Լ C2 �����δ���ᣬ����˲����죨�� AssessmentService ��ע�ͣ�"},"migratable":true,"dimension_scores":[8,8,8,8,8,8,8]} ==> expected: <false> but was: <true>
[INFO] 
```


被触碰源文件（逐字节还原校验）：

- `dy-app/src/main/java/com/diaoyuanyun/dy/app/assessment/controller/AssessmentController.java`
- `dy-app/src/main/java/com/diaoyuanyun/dy/app/assessment/service/AssessmentService.java`

总体：全部被抓 且 已还原全绿
