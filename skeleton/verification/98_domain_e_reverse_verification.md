# S3-3 · 契约域 E 反向验证证据

三列证据：注入内容 | 预期失败点 | 实际失败断言原文

> 🛑 编码须知同 94_domain_b_reverse_verification.md：乱码为证据原貌，判定以英文方法名/码为准。

## I1 · E1 幂等被短路：幂等键命中判断删掉

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/band/service/BandService.java`
- 注入：`if (hit != null && hit.expiry() > System.currentTimeMillis()) {` -> `if (false && hit != null && hit.expiry() > System.currentTimeMillis()) {`
- 捕捉者：真请求 E2E `BandEndpointsE2ETest`
- 预期失败点：E2E 必须变红：重复 batch_no 不再 409 —— 幂等去重失效，同步批次会被重复受理
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   BandEndpointsE2ETest$SyncBatch.duplicate_batch_no_is_409:206 �ظ� batch_no Ӧ 409 �ݵ��ط� ==> expected: <409> but was: <200>
[INFO] 
```

## I2 · metric 13 值校验被删：TelemetryRow 构造器放行未登记 metric

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/band/domain/TelemetryRow.java`
- 注入：`if (metric == null || !METRICS.contains(metric)) {` -> `if (false) {`
- 捕捉者：真请求 E2E `BandRowTest`
- 预期失败点：单测必须变红：未登记 metric（heart_rate）不再被拒 —— 契约 §4.3 双分支幂等键的 metric 成员失效
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   BandRowTest.telemetry_metric_enum:32 δ�Ǽǵ� metric Ӧ�ܣ�heart_rate �� 13 ֵ���Ϸ��� hr�� ==> Expected com.diaoyuanyun.dy.common.exception.BizException to be thrown, but nothing was thrown.
[INFO] 
```

## I3 · OPTIONAL-SKIP：gap_reason 的 client 隔离由【全局】DerivedResponseBodyAdvice 承担（出口兜底），service 层的 gapReasonVisibleTo 只是第一层冗余；注入单层会被第二层兜住，证明不了牙齿，故本轮不注入。该两层分工的证据 = 注入 service 层后 DerivedResponseBodyAdvice 日志仍摘除 gap_reason（fields=[gap_reason]）。

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/band/service/BandService.java`
- 注入：`__SKIP__` -> `__SKIP__`
- 捕捉者：真请求 E2E `BandEndpointsE2ETest`
- 预期失败点：不注入。见 gap 说明。
- 判定：**OPTIONAL-SKIP**
- 实际失败断言原文：

```
(empty)
```

## I4 · 四态校验被删：SyncBatchRow 构造器放行非法 state（如 worn）

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/band/domain/SyncBatchRow.java`
- 注入：`if (state == null || !STATES.contains(state)) {` -> `if (false) {`
- 捕捉者：真请求 E2E `BandRowTest`
- 预期失败点：单测必须变红：非法四态（含『未佩戴』语义）不再被拒 —— 契约 BandSyncBatchRequest 四态被破坏
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   BandRowTest.sync_batch_state_enum:62 Expected com.diaoyuanyun.dy.common.exception.BizException to be thrown, but nothing was thrown.
[INFO] 
```


被触碰源文件（逐字节还原校验）：

- `dy-app/src/main/java/com/diaoyuanyun/dy/app/band/service/BandService.java`
- `dy-app/src/main/java/com/diaoyuanyun/dy/app/band/domain/TelemetryRow.java`
- `dy-app/src/main/java/com/diaoyuanyun/dy/app/band/domain/SyncBatchRow.java`

总体：全部被抓 且 已还原全绿
