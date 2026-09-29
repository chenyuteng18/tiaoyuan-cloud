# B-2 · 手环域幂等（并发/多实例）反向验证证据

三列证据：注入内容 | 预期失败点 | 实际失败断言原文

> 🛑 编码须知同 94/98_*.md：乱码为证据原貌，判定以英文方法名/码为准。

## J1 · 库层原子轴被抽掉：删掉 INSERT 的 ON CONFLICT DO NOTHING

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/band/repository/BandLedger.java`
- 注入：`                    + " ON CONFLICT DO NOTHING";` -> `                    + "";`
- 捕捉者：真库并发门禁 `BandIdempotencyConcurrencyTest$ConcurrentSameKey`
- 预期失败点：并发门禁必须变红：同键并发写入时，非首位的线程会撞 uq_bt_daily_idempotent（23505 / DuplicateKeyException）。真 HTTP 链路上那是一个 500 —— 正是 B-2 修复前让客户端收 500 的成因。
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   BandIdempotencyConcurrencyTest$ConcurrentSameKey.concurrent_writes_on_same_key_yield_exactly_one_row_and_no_failure:239 ? ����д������� 30 ���쳣 ���� �ݵ��ڲ����¡�δ��ԭ�ӳе�����B-2 �޸�ǰ�˴���Ȼ�ǿգ�Ψһ������ͻð�����÷�������· = 500�����׸��쳣: org.springframework.dao.DuplicateKeyException: PreparedStatementCallback; SQL [INSERT INTO band_telemetry ( telemetry_id, tenant_id, customer_id, device_id, metric, date, hour, minute, value_enc, sleep_json, coverage_flag, data_source, gap_reason, sync_state, synced_at, is_wear, local_tz_offset, sport_id, created_by) VALUES (?::uuid, NULLIF(current_setting('app.tenant_id', true), '')::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)]; ����: �ظ���Υ��ΨһԼ��"uq_bt_daily_idempotent" ==> expected: <true> but was: <false>
[INFO] 
```

## J2 · 内存防线复发：给 BandService 加回一个 Map 实例字段

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/band/service/BandService.java`
- 注入：`    private final BandLedger ledger;` -> `    private final java.util.Map<String,String> probeCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final BandLedger led`
- 捕捉者：真库并发门禁 `BandIdempotencyConcurrencyTest$NoInProcessMap`
- 预期失败点：结构性断言必须变红：BandService 一旦长出进程内 Map 字段，B-2 的缺陷形态即复发（幂等退化成单实例快路径）。本条不依赖并发时序，是最可靠的确定性防线。
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   BandIdempotencyConcurrencyTest$NoInProcessMap.band_service_has_no_in_process_map_field:586 ? BandService �����˽����� Map �ֶ� [probeCache : java.util.Map] ���� B-2 ��ȱ����̬���ڸ������ݵȵ�Ȩ�������롿�ڿ��Ψһ�������κν����� Map �����˻��ɵ�ʵ����·����������'��������Դ + �����Ư��'���� BandService ��ͷ���� ==> expected: <true> but was: <false>
[INFO] 
```

## J3 · 日型谓词被削弱：删掉 findTelemetryId 日型分支的 AND sport_id IS NULL

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/band/repository/BandLedger.java`
- 注入：`                                + " AND COALESCE(hour,-1) = ? AND COALESCE(minute,-1) = ?"
                                + " AND sport_id ` -> `                                + " AND COALESCE(hour,-1) = ? AND COALESCE(minute,-1) = ?",`
- 捕捉者：真库并发门禁 `BandIdempotencyConcurrencyTest$BranchIsolation`
- 预期失败点：分支隔离断言必须变红：日型键会命中一条 sport_id 非空的行，两个分支的幂等键被混同（契约 §4.3 明令运动数据不得复用按日型幂等键）。
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   BandIdempotencyConcurrencyTest$BranchIsolation.sport_cursor_row_is_not_falsely_matched_by_the_daily_key:428 ? ���ͼ���һ���α���������Ϊ���У�hit=e00049da-00ee-41ed-89b6-7699cec38770������ findTelemetryId �����ͷ�֧©�� `sport_id IS NULL` ν�ʣ�������֧���ݵȼ��ѻ�ͬ����Լ ��4.3 �����˶����ݲ��ø��ð������ݵȼ��� ==> expected: <true> but was: <false>
[INFO] 
```


被触碰源文件（逐字节还原校验）：

- `dy-app/src/main/java/com/diaoyuanyun/dy/app/band/repository/BandLedger.java`
- `dy-app/src/main/java/com/diaoyuanyun/dy/app/band/service/BandService.java`

总体：全部被抓 且 已还原全绿
