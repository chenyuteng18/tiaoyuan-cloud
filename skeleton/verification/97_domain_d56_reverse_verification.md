# S3-2b · 契约域 D（D5/D6）反向验证证据

三列证据：注入内容 | 预期失败点 | 实际失败断言原文

> 🛑 编码须知同 94_domain_b_reverse_verification.md：乱码为证据原貌，判定以英文方法名/码为准。

## I1 · 退回必填 reason 被删：PlanReviewRow 构造器放行退回无 reason

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/fulfillment/domain/PlanReviewRow.java`
- 注入：`if (RESULT_REJECTED.equals(result)
                && (reason == null || reason.isBlank())) {` -> `if (false
                && (reason == null || reason.isBlank())) {`
- 捕捉者：真请求 E2E `PlanAndDispatchE2ETest`
- 预期失败点：E2E 必须变红：退回缺 reason 不再 400 —— 无人能知驳回依据（PRD P0-06）
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   PlanAndDispatchE2ETest$Review.reject_without_reason_is_400:243 �˻ر��� reason Ӧ 400: {"code":9001,"message":"ϵͳ�쳣: DataIntegrityViolationException","trace_id":"f70170daeb8b4a20896aee1dedd2816b"} ==> expected: <400> but was: <500>
[INFO] 
```

## I2 · 同人自助二次确认被短路：reviewPlan 的 issuerId 判断删掉

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/fulfillment/service/PlanService.java`
- 注入：`if (approve && issuerId != null && issuerId.equals(reviewer)) {` -> `if (approve && issuerId != null && issuerId.equals(reviewer) && false) {`
- 捕捉者：真请求 E2E `PlanAndDispatchE2ETest`
- 预期失败点：E2E 必须变红：同人自助通过不再要求二次确认 —— US-2 无痕自助通过
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   PlanAndDispatchE2ETest$Review.self_approve_without_confirm_is_409:252 ͬ������ͨ��ȱ����ȷ��Ӧ 409: {"code":0,"message":"OK","data":{"review_id":"ca6fd7e5-757a-4f4c-b7c5-8faf4cd550cb","plan_id":"2d4ba833-6930-4f3a-bddc-61b592f6086a","plan_version":1,"result":"ͨ��","reviewed_at":"2026-09-25T21:54:22.683281300Z"},"trace_id":"087eace373554ad78dbcd16cab7ea7a1"} ==> expected: <409> but was: <200>
[INFO] 
```

## I3 · D6 方案审核前置被删：createDispatch 的 planApproved 判断删掉

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/fulfillment/service/PlanService.java`
- 注入：`if (!ledger.planApproved(tenantId, planId, planVersion)) {` -> `if (false && !ledger.planApproved(tenantId, planId, planVersion)) {`
- 捕捉者：真请求 E2E `PlanAndDispatchE2ETest`
- 预期失败点：E2E 必须变红：未审核方案可下发 —— PRD P0-16 审核通过才可下发被破坏
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   PlanAndDispatchE2ETest$Dispatch.dispatch_unapproved_plan_is_403:281 ����δ���Ӧ 403: {"code":0,"message":"OK","data":{"dispatch_id":"bd560fca-8247-43ea-85fe-ae30525c8ab7","plan_id":"d6f36bea-3454-4fdf-bcef-2272422a24e8","plan_version":1,"device_id":"e2e00000-0000-0000-0000-0000000000e5","result":"�ɹ�","dispatched_at":"2026-09-25T21:55:02.133683400Z"},"trace_id":"f404dab9243a435a878a2efa5bd8e2d8"} ==> expected: <403> but was: <200>
[INFO] 
```


被触碰源文件（逐字节还原校验）：

- `dy-app/src/main/java/com/diaoyuanyun/dy/app/fulfillment/domain/PlanReviewRow.java`
- `dy-app/src/main/java/com/diaoyuanyun/dy/app/fulfillment/service/PlanService.java`

总体：全部被抓 且 已还原全绿
