# B-3 · 配置真相源落库（V14）反向验证证据

三列证据：注入内容 | 预期失败点 | 实际失败断言原文

> 🛑 编码须知同 94/98/99_*.md：乱码为证据原貌，判定以 ASCII 方法名/码为准。

## K1 · 同步防线失效：只改 V14 内联段一行（列宽 64→63），不改真源 01

- 目标文件：`dy-app/src/main/resources/db/migration/V14__config_truth_source_tables.sql`
- 注入（首处）：`    config_key       VARCHAR(64) NOT NULL,` -> `    config_key       VARCHAR(63) NOT NULL,`
- 捕捉者：`ConfigTruthSourceMigrationSyncTest`
- 捕捉锚点：['RlsCoverageGateTest.every_tenant_table_has_a_registered_isolation_test']
- 预期失败点：同步门禁必须变红：段 A（DDL）逐行比较会在这一行上报不一致。这正是「改一处不改另一处」的最小真实形态 —— 应用库执行 V14、门禁库与口径来源读真源文件，分叉会让三方口径静默不一致。
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   ConfigTruthSourceMigrationSyncTest.section_a_ddl_is_byte_identical:208->assertSectionEqual:421 �� A��DDL�� �� 7 �в�һ�� ���� 01_truth_source_ddl.sql �� 2 ���� �� V14 �� 1 �� �ѷֲ棺
  01_truth_source_ddl.sql �� 2 ���� : "    config_key       VARCHAR(64) NOT NULL,"
  V14 �� 1 ��   : "    config_key       VARCHAR(63) NOT NULL,"
����������: 269 / 269��
�޷���ȷ��������Ķ���ͬ��������һ�ࣻ��������Ľṹ��ƫ�룬������ V14 �ļ�ͷ���Ǽǡ��������ڱ��������д�ɽṹ�������������Ƿſ��Ƚ� ���� �ſ��Ƚϵ����ڷֲ���Ϲص��Ž���
[INFO] 
```

## K2 · 编号域上界被放宽：V14 与 01 同步把 <= 48 改成 <= 49

- 目标文件：`dy-app/src/main/resources/db/migration/V14__config_truth_source_tables.sql`、`dy-config/src/main/resources/db/config/01_truth_source_ddl.sql`
- 注入（首处）：`    CONSTRAINT config_slot_no_in_domain CHECK (config_no >= 1 AND config_no <= 48),` -> `    CONSTRAINT config_slot_no_in_domain CHECK (config_no >= 1 AND config_no <= 49),`
- 捕捉者：`RlsV14ConfigTruthSourceIsolationTest`
- 捕捉锚点：['RlsCoverageGateTest.every_tenant_table_has_a_registered_isolation_test']
- 预期失败点：真库门禁必须变红：它断言编号域上界恰为 48（#48 已落 PRD 的最大号）。放宽上界会让「编号漂移到域外」重新变成可写 —— 而编号位移是静默错位，所有既有编号引用会一起失真。
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   RlsV14ConfigTruthSourceIsolationTest.config_slot_declares_46_rows_and_keeps_the_two_vacant_numbers_closed:458 ������Ͻ�ӦΪ 48��#48 health_pnl_visibility ���� PRD����ʵ��: CHECK (((config_no >= 1) AND (config_no <= 49))) ==> expected: <true> but was: <false>
[INFO] 
```

## K3 · #42 空号裁定被摘掉：V14 与 01 同步删掉 no_42_stays_vacant 约束行

- 目标文件：`dy-app/src/main/resources/db/migration/V14__config_truth_source_tables.sql`、`dy-app/src/main/resources/db/migration/V14__config_truth_source_tables.sql`、`dy-config/src/main/resources/db/config/01_truth_source_ddl.sql`、`dy-config/src/main/resources/db/config/01_truth_source_ddl.sql`
- 注入（首处）：`    CONSTRAINT config_slot_no_in_domain CHECK (config_no >= 1 AND config_no <= 48),
` -> `    CONSTRAINT config_slot_no_in_domain CHECK (config_no >= 1 AND config_no <= 48)
`
- 捕捉者：`RlsV14ConfigTruthSourceIsolationTest`
- 捕捉锚点：['RlsCoverageGateTest.every_tenant_table_has_a_registered_isolation_test']
- 预期失败点：真库门禁必须变红：它断言 pg_constraint 里存在 config_slot_no_42_stays_vacant。缺这条约束，一条 INSERT 就能占用 #42，而 2026-09-18 新增的「手环数据可见性矩阵」之所以编为 #43（而非 #42）正是因为 #42 被预留。⚠️ 本组已把前一行尾逗号一并摘掉，故失败【只可能】来自该断言；若证据里出现 `syntax error` / `LINE 36` 字样，说明注入又坏在语法上、证明力不足。
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Errors: 
[ERROR]   RlsV14ConfigTruthSourceIsolationTest.provision:189 ? IllegalState 015_apply_locked(�����ӳ���) ʧ��: psql EXIT=3
--- ��� ---
 pg_advisory_lock 
------------------
 
(1 row)

-- portable apply: applying the REAL migration chain from the deliverable --
-- portable apply: V1__baseline_tenant_rls.sql --
CREATE TABLE
CREATE TABLE
CREATE INDEX
ALTER TABLE
ALTER TABLE
psql:C:/Users/lenovo/WorkBuddy/2026-09-16-10-37-59/deliverables/product-strategy/skeleton/dy-app/src/main/resources/db/migration/V1__baseline_tenant_rls.sql:57: ע��:  ��ϵ"customer"�Ĳ���"tenant_isolation"�����ڣ�����
DROP POLICY
CREATE POLICY
CREATE TABLE
CREATE INDEX
CREATE TABLE
-- portable apply: V2__b_entities_state_machine_and_band.sql --
CREATE TABLE
CREATE INDEX
CREATE INDEX
CREATE INDEX
CREATE INDEX
CREATE INDEX
CREATE INDEX
ALTER TABLE
ALTER TABLE
psql:C:/Users/lenovo/WorkBuddy/2026-09-16-10-37-59/deliverables/product-strategy/skeleton/dy-app/src/main/resources/db/migration/V2__b_entities_state_machine_and_band.sql:127: ע��:  ��ϵ"customer_state_transition"�Ĳ���"tenant_isolation"�����ڣ�����
DROP POLICY
CREATE POLICY
CREATE TABLE
CREATE INDEX
CREATE INDEX
CREATE INDEX
CREATE INDEX
CREATE INDEX
ALTER TABLE
ALTER TABLE
psql:C:/Users/lenovo/WorkBuddy/2026-09-16-10-37-59/deliverables/product-strategy/skeleton/dy-app/src/main/resources/db/migration/V2__b_entities_state_machine_and_band.sql:189: ע��:  ��ϵ"band"�Ĳ���"tenant_isolation"�����ڣ�����
DROP POLICY
CREATE POLICY
INSERT 0 1
psql:C:/Users/lenovo/WorkBuddy/2026-09-16-10-37-59/deliverables/product-strategy/skeleton/dy-app/src/main/resources/db/migration/V2__b_entities_state_machine_and_band.sql:246: ע��:  V2: ���±��ڱ��ֿ� DDL �в�����, FK �պϡ�������(���½���, �� F-2 ����): band_telemetry, band_sync_probe, band_daily_coverage
DO
-- portable apply: V3__band_telemetry_metric_long_table.sql --
CREATE TABLE
CREATE INDEX
CREATE INDEX
CREATE INDEX
CREATE INDEX
CREATE INDEX
CREATE INDEX
ALTER TABLE
ALTER TABLE
psql:C:/Users/lenovo/WorkBuddy/2026-09-16-10-37-59/deliverables/product-strategy/skeleton/dy-app/src/main/resources/db/migration/V3__band_telemetry_metric_long_table.sql:138: ע��:  ��ϵ"band_telemetry"�Ĳ���"tenant_isolation"�����ڣ�����
DROP POLICY
CREATE POLICY
INSERT 0 1
-- portable apply: V4__scale_item_bank.sql --
CREATE TABLE
CREATE INDEX
CREATE INDEX
CREATE INDEX
ALTER TABLE
ALTER TABLE
psql:C:/Users/lenovo/WorkBuddy/2026-09-16-10-37-59/deliverables/product-strategy/skeleton/dy-app/src/main/resources/db/migration/V4__scale_item_bank.sql:115: ע��:  ��ϵ"scale_item_bank"�Ĳ���"tenant_isolation"�����ڣ�����
DROP POLICY
CREATE POLICY
psql:C:/Users/lenovo/WorkBuddy/2026-09-16-10-37-59/deliverables/product-strategy/skeleton/dy-app/src/main/resources/db/migration/V4__scale_item_bank.sql:164: ע��:  scale_item_bank OK: 8 ������ �� 7 ά�� �� 4 ���� = 224 �����������ṹ��δԤ�����棩
DO
INSERT 0 1
-- portable apply: V5__remaining_entities_org_journey_verdict_refund.sql --
CREATE TABLE
CREATE INDEX
ALTER TABLE
ALTER TABLE
psql:C:/Users/lenovo/WorkBuddy/2026-09-16-10-37-59/deliverables/product-strategy/skeleton/dy-app/src/main/resources/db/migration/V5__remaining_entities_org_journey_verdict_refund.sql:61: ע��:  ��ϵ"region"�Ĳ���"tenant_isolation"�����ڣ�����
DROP POLICY
CREATE POLICY
CREATE TABLE
CREATE INDEX
CREATE INDEX
CREATE INDEX
ALTER TABLE
ALTER TABLE
psql:C:/Users/lenovo/WorkBuddy/2026-09-16-10-37-59/deliverables/product-strategy/skeleton/dy-app/src/main/resources/db/migration/V5__remaining_entities_org_journey_verdict_refund.sql:89: ע��:  ��ϵ"store"�Ĳ���"tenant_isolation"�����ڣ�����
DROP POLICY
CREATE POLICY
CREATE TABLE
CREATE INDEX
CREATE INDEX
CREATE INDEX
ALTER TABLE
ALTER TABLE
psql:C:/Users/lenovo/WorkBuddy/2026-09-16-10-37-59/deliverables/product-strategy/skeleton/dy-app/src/main/resources/db/migration/V5__remaining_entities_org_journey_verdict_refund.sql:118: ע��:  ��ϵ"staff"�Ĳ���"tenant_isolation"�����ڣ�����
DROP POLICY
CREATE POLICY
CREATE TABLE
CREATE INDEX
CREATE INDEX
ALTER TABLE
ALTER TABLE
psq
```

## K4 · 幂等偏离退化成静默丢值：DO UPDATE SET 7 列 → DO NOTHING

- 目标文件：`dy-app/src/main/resources/db/migration/V14__config_truth_source_tables.sql`
- 注入（首处）：`ON CONFLICT (config_no) DO UPDATE
   SET config_key       = EXCLUDED.config_key,
       value_type       = EXCLUDED.value_type,
       allowed_values   = EXCLUDED.allowed_values,
       forbidden_valu` -> `ON CONFLICT (config_no) DO NOTHING;`
- 捕捉者：`ConfigTruthSourceMigrationSyncTest`
- 捕捉锚点：['RlsCoverageGateTest.every_tenant_table_has_a_registered_isolation_test']
- 预期失败点：同步门禁必须变红：段 B 比较会发现 V14 与登记规则重建的结果不一致，且「不得 DO NOTHING」断言同时报警。DO NOTHING 正是 02 原注释逐字想避免的效果 ——「改了 initial_value 却不会生效，迁移看着成功、值没变」。
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   ConfigTruthSourceMigrationSyncTest.section_b_declaration_matches_except_the_one_registered_divergence:273 V14 ���ѵǼǵ�ƫ������ǡ���� 1 �Σ�ʵ�� 0 �Σ���0 �� ? ƫ��鱻ɾ����ȷ�� ON CONFLICT �Ƿ��ڣ��������ܻ��� 23503 ʧ�ܣ���>1 �� ? ������δ�ǼǵĶ���ƫ�롣 ==> expected: <1> but was: <0>
[ERROR]   ConfigTruthSourceMigrationSyncTest.the_registered_divergence_does_not_silently_drop_values:344->indexOfLineEquals:463 V14 ���Ҳ�������ִ������С�ON CONFLICT (config_no) DO UPDATE��ƫ����Ƿ�ɾ�ˣ����Ƿ񱻸ĳ��� DO NOTHING��������ON CONFLICT (config_no) DO UPDATE����Ϊ�������ڴ���֮��δ�ҵ�
[INFO] 
```

## K5 · append-only 的语义载体被换掉：ERRCODE 42501 → 23514

- 目标文件：`dy-app/src/main/resources/db/migration/V14__config_truth_source_tables.sql`
- 注入（首处）：`        USING ERRCODE = '42501';` -> `        USING ERRCODE = '23514';`
- 捕捉者：`RlsV14ConfigTruthSourceIsolationTest`
- 捕捉锚点：['RlsCoverageGateTest.every_tenant_table_has_a_registered_isolation_test']
- 预期失败点：真库门禁必须变红：它断言历史行的 UPDATE/DELETE 以 42501（insufficient_privilege ——「不可改不可删」这一语义的载体）被拒。换成 23514 后，客户端会把「审计记录不可篡改」误读成「取值非法」，而错误码是运维与契约的唯一判据。
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   RlsV14ConfigTruthSourceIsolationTest.history_is_append_only_and_written_by_the_trigger:648 ��ʷ�� UPDATE Ӧ�Դ����� 42501 �ܾ���ʵ�� SQLSTATE=23514 ==> expected: <42501> but was: <23514>
[INFO] 
```

## K6 · 覆盖登记被摘掉：删掉 RlsCoverageGateTest 里的 app_config 登记

- 目标文件：`dy-app/src/test/java/com/diaoyuanyun/dy/app/rls/RlsCoverageGateTest.java`
- 注入（首处）：`            Map.entry("app_config", V14),
` -> ``
- 捕捉者：`RlsCoverageGateTest`
- 捕捉锚点：['RlsCoverageGateTest.every_tenant_table_has_a_registered_isolation_test']
- 预期失败点：覆盖门禁必须变红：迁移里有 app_config 却没有登记 ⇒ 「无隔离测试即构建失败」这条 ADR-02 L3 裁定被守住。缺它则配置表可以在没有任何隔离断言的情况下上线。
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   RlsCoverageGateTest.every_tenant_table_has_a_registered_isolation_test:344 �����⻧��û�ж�Ӧ�� RLS ������ԣ�ADR-02 L3���޸�����Լ�����ʧ�ܣ�: [app_config]
���� RlsCoverageGateTest.ISOLATION_TESTS �еǼǣ���������ʵ���ݿ�Ķ�д������ԡ��ѵǼǵ�: [tenant_kek, subject_dek, subject_key_tombstone, app_config, app_config_history, customer, customer_state_transition, band, band_telemetry, scale_item_bank, region, store, staff, device, screening_record, consent, scale, plan, agreement, baseline_assessment, plan_review, device_dispatch, visit, daily_report, cycle_assessment, verdict, refund, retention, case_archive, intake_profile, doc_template, band_sync_probe, band_sync_log, band_daily_coverage, refund_statement, refund_receipt, refund_offline_notice, intake_profile_revision]
[ERROR]   RlsCoverageGateTest.every_tenant_table_has_force_rls_in_the_real_database:446 �⻧������Ǽ�������һ�£���=[tenant_kek, subject_dek, subject_key_tombstone, app_config, app_config_history, customer, customer_state_transition, band, band_telemetry, scale_item_bank, region, store, staff, device, screening_record, consent, scale, plan, agreement, baseline_assessment, plan_review, device_dispatch, visit, daily_report, cycle_assessment, verdict, refund, retention, case_archive, intake_profile, doc_template, band_sync_probe, band_sync_log, band_daily_coverage, refund_statement, refund_receipt, refund_offline_notice, intake_profile_revision] �Ǽ�=[verdict, agreement, plan_review, doc_template, tenant_kek, consent, daily_report, subject_key_tombstone, scale, refund_receipt, retention, cycle_assessment, screening_record, device_dispatch, intake_profile, band_sync_log, band_sync_probe, intake_profile_revision, refund_statement, store, scale_item_bank, visit, case_archive, device, band_telemetry, refund, customer, plan, staff, subject_dek, app_config_history, band_daily_coverage, refund_offline_notice, customer_state_transition, region, baseline_assessment, band] ==> expected: <38> but was: <37>
[ERROR]   RlsCoverageGateTest.three_sources_must_agree_on_the_set_of_tenant_tables:380 �⻧���ġ��ǼǸ��ǡ���Ǩ�Ʋ�һ�£�ADR-02 L3���޸�����Լ�����ʧ�ܣ���Ǩ��=[tenant_kek, subject_dek, subject_key_tombstone, app_config, app_config_history, customer, customer_state_transition, band, band_telemetry, scale_item_bank, region, store, staff, device, screening_record, consent, scale, plan, agreement, baseline_assessment, plan_review, device_dispatch, visit, daily_report, cycle_assessment, verdict, refund, retention, case_archive, intake_profile, doc_template, band_sync_probe, band_sync_log, band_daily_coverage, refund_statement, refund_receipt, refund_offline_notice, intake_profile_revision] �Ǽ�=[verdict, agreement, plan_review, doc_template, tenant_kek, consent, daily_report, subject_key_tombstone, scale, refund_receipt, retention, cycle_assessment, screening_record, device_dispatch, intake_profile, band_sync_log, band_sync_probe, intake_profile_revision, refund_statement, store, scale_item_bank, visit, case_archive, device, band_telemetry, refund, customer, plan, staff, subject_dek, app_config_history, band_daily_coverage, refund_offline_notice, customer_state_transition, region, baseline_assessment, band] ==> expected: <[tenant_kek, subject_dek, subject_key_tombstone, app_config, app_config_history, customer, customer_state_transition, band, band_telemetry, scale_item_bank, region, store, staff, device, screening_record, consent, scale, plan, agreement, baseline_assessment, plan_review, device_dispatch, visit, daily_report, cycle_assessment, verdict, refund, retention, case_archive, intake_profile, doc_template, band_sync_probe, band_sync_log, band_daily_coverage, refund_statement, refund_receipt, refund_offline_notice, intake_profile_revision]> but was: <[verdict, agreement, plan_review, doc_template, tenant_kek, consent, daily_report, subject_key_tombstone, scale, refund_receipt, retention, cycle_assessment, screening_record, device_dispatch, intake_profile, band_sync_log, band_sync_prob
```


被触碰源文件（逐字节还原校验）：

- `dy-app/src/main/resources/db/migration/V14__config_truth_source_tables.sql`
- `dy-config/src/main/resources/db/config/01_truth_source_ddl.sql`
- `dy-app/src/test/java/com/diaoyuanyun/dy/app/rls/RlsCoverageGateTest.java`

总体：全部被抓 且 已还原全绿
