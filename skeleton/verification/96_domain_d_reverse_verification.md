# S3-2a · 契约域 D 反向验证证据

三列证据：注入内容 | 预期失败点 | 实际失败断言原文

> 🛑 编码须知同 94_domain_b_reverse_verification.md：乱码为证据原貌，判定以英文方法名/码为准。

## I1 · 码无主：把 D1 的 fulfillment:write 改成不存在的码

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/fulfillment/controller/FulfillmentController.java`
- 注入：`@RequirePermission("fulfillment:write")` -> `@RequirePermission("fulfillment:phantom")`
- 捕捉者：码级门禁 `PermissionCodeRegistrationGateTest`
- 预期失败点：码级门禁第②条必须报 orphans 非空（fulfillment:phantom 无人持有）
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   PermissionCodeRegistrationGateTest.every_require_permission_code_has_at_least_one_holder:192 ? ����Ȩ���뱻 @RequirePermission ������ȴ�� PermissionRegistry �û���κν�ɫ���С� ���� �������ǵĶ˵��ԡ�ȫ����ɫ��403��������Լ�����ɵ��Ľ�ɫ����[fulfillment:phantom]��
�ѵǼǵ���: [*, assessment:write, customer:archive, customer:read, customer:write, fulfillment:write, refund:approve, refund:read, refund:write, report:region, store:read, verdict:read, verdict:write]��
����: {fulfillment:phantom=[dy-app/src/main/java/com/diaoyuanyun/dy/app/fulfillment/controller/FulfillmentController.java]}��
? �����Ǳ��ֿ��Ѿ����������ε�ͬ��ȱ�ݣ���ɫ���Сд�ֲ� / refund:* ��δ�Ǽǣ����� ���ζ�ֻ���������±�¶�������ڴ˴����Ǽǣ���Ҫ�ſ������ԡ� ==> expected: <true> but was: <false>
[ERROR]   PermissionCodeRegistrationGateTest.scanner_actually_sees_the_annotations_and_their_codes:160 ɨ������������� fulfillment:write������Ȼ������Դ���У���ʵ���뼯: [assessment:write, customer:archive, customer:read, customer:write, fulfillment:phantom, refund:approve, refund:read, refund:write, store:read, verdict:read, verdict:write] ==> expected: <true> but was: <false>
[INFO] 
```

## I2 · 四道闸门被短路：assertAllGatesPassed 无条件放行

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/fulfillment/domain/FulfillmentGateGuard.java`
- 注入：`if (!missing.isEmpty()) {` -> `if (false && !missing.isEmpty()) {`
- 捕捉者：真请求 E2E `FulfillmentDomainDEndpointsE2ETest`
- 预期失败点：E2E 必须变红：缺同意书时 D1 不再 403 —— 四道闸门被整体短路，PRD P0-08 的执行前置失效
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   FulfillmentDomainDEndpointsE2ETest$GateMissing.missing_gate_is_403_with_names:279 ȱͬ����Ӧ 403: {"code":0,"message":"OK","data":{"visit_id":"b5a3631c-1827-42b2-b5fe-c90778593664","visit_no":2,"executed_at":"2026-09-25T21:41:10.349315200Z","serving_store_id":"e2e00000-0000-0000-0000-0000000000d2","customer_confirmed":true,"gate_check_json":{"screening_passed":true,"consent_signed":true,"agreement_signed":true,"plan_approved":true,"customer_confirmed":true}},"trace_id":"3a8fd46652964dc19808e76cdff7c57c"} ==> expected: <403> but was: <200>
[INFO] 
```

## I3 · 客户字段裁剪被删：visitInternalVisibleTo 恒 true

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/fulfillment/service/FulfillmentService.java`
- 注入：`public static boolean visitInternalVisibleTo(VisibilityRole role) {
        return role != null && role != VisibilityRol` -> `public static boolean visitInternalVisibleTo(VisibilityRole role) {
        return true;
    }`
- 捕捉者：真请求 E2E `FulfillmentDomainDEndpointsE2ETest`
- 预期失败点：E2E 必须变红：客户响应体出现 gate_check_json —— 契约 x-visible-to 不含 client
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   FulfillmentDomainDEndpointsE2ETest$VisitList.client_sees_no_gate_check:310 �ͻ���Ӧ�岻�ó��� gate_check_json��x-visible-to ���� client��: {"code":0,"message":"OK","data":{"items":[{"visit_id":"12bb1e4b-4666-4036-ab3b-ac94fef780a0","visit_no":1,"executed_at":"2026-09-25T21:41:49.272514Z","serving_store_id":"e2e00000-0000-0000-0000-0000000000d2","customer_confirmed":true,"gate_check_json":{"plan_approved":true,"consent_signed":true,"agreement_signed":true,"screening_passed":true,"customer_confirmed":true}}],"total":1,"page":1,"page_size":10},"trace_id":"c0942334e2674043a7c9ace1c3f7add2"} ==> expected: <false> but was: <true>
[INFO] 
```

## I4 · visit_no 账本被改坏：nextVisitNo 恒返回 1（不做 MAX+1）

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/fulfillment/repository/FulfillmentLedger.java`
- 注入：`Integer max = jdbc.queryForObject(
                    "SELECT coalesce(max(visit_no), 0) FROM visit WHERE customer_id =` -> `Integer max = 0;
            return 1;`
- 捕捉者：真请求 E2E `FulfillmentDomainDEndpointsE2ETest`
- 预期失败点：E2E 必须变红：第二次核销 visit_no 不递增 —— 客户维度全局唯一账本 U2 被破坏
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   FulfillmentDomainDEndpointsE2ETest$Visit.meridian_creates_visit:243 ��բ��ȫ��Ӧ 200: {"code":4001,"message":"�ͻ�ά�ȷ�����Ų�����ͻ��visit_no=1������ �����ԡ�U2�����������ȫ��Ψһ�˱�������ɷ����������ȡ�ţ������µ��ظ���Ψһ��������","trace_id":"2104c6ac46604709ba8513955b580d2d"} ==> expected: <200> but was: <409>
[INFO] 
```


被触碰源文件（逐字节还原校验）：

- `dy-app/src/main/java/com/diaoyuanyun/dy/app/fulfillment/controller/FulfillmentController.java`
- `dy-app/src/main/java/com/diaoyuanyun/dy/app/fulfillment/domain/FulfillmentGateGuard.java`
- `dy-app/src/main/java/com/diaoyuanyun/dy/app/fulfillment/service/FulfillmentService.java`
- `dy-app/src/main/java/com/diaoyuanyun/dy/app/fulfillment/repository/FulfillmentLedger.java`

总体：全部被抓 且 已还原全绿
