# C-5 · 可观测性「指标禁租户标签」反向验证证据

三列证据：注入内容 | 预期失败点 | 实际失败断言原文

> 🛑 编码须知同 94/98/99/101_*.md：乱码为证据原貌，判定以英文方法名/码为准。

> 说明：本文件补的是**产品代码侧**的注入。`TenantTagGuardTest` 自身只在测试内
> 构造注入（证明检测函数有区分力），它**不**证明「产品侧的禁令集合/公共标签装配
> 被改动时构建会失败」。两者合起来才是完整证据。

## M1 · 禁令集合被掏空：FORBIDDEN_TAG_KEYS 清成空集 ⇒ 所有断言平凡通过（常绿门禁）

- 目标文件：`dy-web/src/main/java/com/diaoyuanyun/dy/web/observability/TenantTagPolicy.java`
- 注入：`    public static final Set<String> FORBIDDEN_TAG_KEYS = Set.of("tenant_id", "tenantid", "tenant");` -> `    public static final Set<String> FORBIDDEN_TAG_KEYS = Set.of();`
- 捕捉者：`TenantTagGuardTest`
- 预期失败点：必须变红：禁令集合为空 ⇒ isForbidden 恒 false ⇒ 所有检查都判合规。一个『常绿』的门禁比没有门禁更危险，因为它给出虚假保证 —— 本用例的存在就是为了让「清空禁令」这件事在构建期就响。
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   TenantTagGuardTest.forbidden_key_set_is_not_empty:248 �����Ϊ�� -> ���ж���ƽ��ͨ�����Ž���ͬ�����ڣ� ==> expected: <false> but was: <true>
[ERROR]   TenantTagGuardTest.guard_scans_every_registered_meter_not_just_a_name_prefix:169 ����ǡ��ץ���� 1 ��Υ�� Meter, ʵ��: [] ==> expected: <1> but was: <0>
[ERROR]   TenantTagGuardTest.injected_tenant_tag_is_detected_red_then_green_after_removal:134 ����̬��ע���ǩ 'tenant_id' ���Ž������� ���� ������û��, ˵���Ž��� tenant_id ����д����������Ч�������ݵ��Ž�������ס������ը���⻧й©�� ==> expected: <false> but was: <true>
[ERROR]   TenantTagGuardTest.policy_matches_case_insensitively_and_ignores_unrelated_keys:181 tenant_id ���뱻�� ==> expected: <true> but was: <false>
[INFO] 
```

## M2 · 公共标签装配被污染：dyCommonTags 加上 tenant_id（最可能被顺手加的位置）

- 目标文件：`dy-web/src/main/java/com/diaoyuanyun/dy/web/observability/ObservabilityConfiguration.java`
- 注入：`        return registry -> registry.config().commonTags("application", applicationName, "instance_id", instanceId);` -> `        return registry -> registry.config().commonTags("application", applicationName,
                "instance_id", instanceId, "tenant_id",
                System.getProperty("dy.probe.tenant", "t`
- 捕捉者：`TenantTagGuardTest`
- 预期失败点：必须变红：公共标签会应用到【每一个】Meter，故这条注入会让全平台指标带上租户维度。基数会随租户数爆炸（监控后端过载），且 /actuator/prometheus 会泄漏全平台租户清单。这正是 ADR-09 §7.2 明令禁止的那条。
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   TenantTagGuardTest.common_tag_customizer_does_not_inject_tenant_dimension:207 ������ǩ���ú��⻧ά��, Υ��: [dy.probe -> ��ǩ 'tenant_id'] ==> expected: <true> but was: <false>
[ERROR]   TenantTagGuardTest.real_prometheus_registry_registers_no_tenant_tagged_meter:75 ָ�겻��Я���⻧ά�ȱ�ǩ��������ը + ���⻧��Ϣй©�棩��Υ����: [dy.http.requests -> ��ǩ 'tenant_id', dy.http.latency -> ��ǩ 'tenant_id', dy.band.connected -> ��ǩ 'tenant_id', dy.order.amount -> ��ǩ 'tenant_id'] ==> expected: <true> but was: <false>
[INFO] 
```


被触碰源文件（逐字节还原校验）：

- `dy-web/src/main/java/com/diaoyuanyun/dy/web/observability/TenantTagPolicy.java`
- `dy-web/src/main/java/com/diaoyuanyun/dy/web/observability/ObservabilityConfiguration.java`

总体：全部被抓 且 已还原全绿
