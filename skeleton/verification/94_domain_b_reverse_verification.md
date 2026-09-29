# S2-10 · 契约域 B 反向验证证据

三列证据：注入内容 | 预期失败点 | 实际失败断言原文

> 🛑 **阅读须知（编码）**：下文的「实际失败断言原文」是从 Maven 输出**原样落盘**的字节。
> 中文 Windows 上 Maven 前缀行（`[ERROR]` / `Tests run:`）是 **GBK**，而 surefire fork 进程打印的断言消息是 **UTF-8** ——
> **同一份输出里两种编码并存，不存在单一正确解码策略**（S2-9 留下的失效模式 **13-b**）。因此原文块里会出现乱码，
> 这是**刻意保留的证据原貌**（证明"看的就是构建真正吐出的东西"），**不是文档损坏**。
> ⚠️ **不要"顺手修一下乱码"** —— 修了就失去"逐字原样"的证据价值。
> 判定一律以**英文可读的部分**为准：测试方法名、`expected: <true> but was: <false>`、权限码、枚举字面
> （**反向验证的预期锚点一律只用 ASCII**，原因即此）。
> 🛑 **还原校验**：脚本退出前对**全部被触碰的源文件**做**逐字节**还原校验（以二进制读写，防文本模式把行尾翻译掉）。

## I1 · 缺口A 的最严重形态（码【彻底无主】）：把 B1 的码改成一个注册表里不存在的码

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/controller/CustomerController.java`
- 注入（old -> new）：`@RequirePermission("customer:archive")
    @PostMapping("/screening-records")` -> `@RequirePermission("customer:phantom")
    @PostMapping("/screening-records")`
- 捕捉者：码级门禁 `PermissionCodeRegistrationGateTest`
- 预期失败点：码级门禁第②条必须报出 orphans 非空（customer:phantom 被声明、却无人持有）——这证明【门禁本身有效】，能为『码无主』兜底
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   PermissionCodeRegistrationGateTest.every_require_permission_code_has_at_least_one_holder:188 ? ����Ȩ���뱻 @RequirePermission ������ȴ�� PermissionRegistry �û���κν�ɫ���С� ���� �������ǵĶ˵��ԡ�ȫ����ɫ��403��������Լ�����ɵ��Ľ�ɫ����[customer:phantom]��
�ѵǼǵ���: [*, customer:archive, customer:read, customer:write, refund:approve, refund:read, refund:write, report:region, store:read, verdict:read, verdict:write]��
����: {customer:phantom=[dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/controller/CustomerController.java]}��
? �����Ǳ��ֿ��Ѿ����������ε�ͬ��ȱ�ݣ���ɫ���Сд�ֲ� / refund:* ��δ�Ǽǣ����� ���ζ�ֻ���������±�¶�������ڴ˴����Ǽǣ���Ҫ�ſ������ԡ� ==> expected: <true> but was: <false>
[INFO] 
[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  3.725 s
[INFO] Finished at: 2026-09-26T04:29:34+08:00
[INFO] ------------------------------------------------------------------------
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test (default-test) on project dy-app: There are test failures.
[ERROR] 
[ERROR] Please refer to C:\Users\lenovo\WorkBuddy\2026-09-16-10-37-59\deliverables\product-strategy\skeleton\dy-app\target\surefire-reports for the individual test results.
[ERROR] Please refer to dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
[ERROR] -> [Help 1]
[ERROR] 
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR] 
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
```

## I1b · 缺口A 的【隐蔽】形态（码仍有主，但【契约点名的角色】丢了它）：单摘 therapist 的 customer:archive

- 目标文件：`dy-security/src/main/java/com/diaoyuanyun/dy/security/permission/PermissionRegistry.java`
- 注入（old -> new）：`rolePermissions.put("therapist", Set.of("customer:read", "customer:archive", "store:read"));` -> `rolePermissions.put("therapist", Set.of("customer:read", "store:read"));`
- 捕捉者：真请求 E2E `DomainBEndpointsE2ETest`
- 预期失败点：真请求 E2E 必须变红（therapist 调 B1 拿到 403）。🛑 关键：此时码级门禁【依然全绿】—— 因为 customer:archive 还剩 meridian 等 7 个持有者，而门禁只问『有没有【任何】角色持有』。这正是本仓库第七次同型缺口能长期潜伏的机制，故本脚本额外断言门禁在 I1b 下绿，把『盲区』变成可读事实而不是口头结论
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   DomainBEndpointsE2ETest$Consents.front_line_role_can_sign_consent_after_profiled:478 ����ʦ���ѽ����ͻ�ǩͬ������� 200����Լ B3 x-callable-roles: [therapist, meridian, admin]�����Ž� G1 �����㣩��ʵ��: 403 FORBIDDEN body={"code":2001,"message":"Ȩ�޲���: customer:archive","trace_id":"4ff9181bb6974474b29e3c1e06a75b0d"} ==> expected: <200 OK> but was: <403 FORBIDDEN>
[ERROR]   DomainBEndpointsE2ETest$Customers.contract_callable_frontline_roles_reach_b2:339 ��ɫ therapist �� B2 ���� 200����Լ B2 x-callable-roles: [therapist, meridian, admin]��admin չ�� = manager/area/hq����
? ���õ� 403 �� missing_items=["PROFILED"]�����ǡ��Ի��Ž�����B2 �Ĳ������ǡ����������ʵ�����ʱ�ͻ���Ȼ��δ PROFILED ������ assertProfiled ���� B2 ��ǰ�û��� B2 100% �����á�ʵ��: 403 FORBIDDEN body={"code":2001,"message":"Ȩ�޲���: customer:archive","trace_id":"6db0cc79ab19493e91a49ba5d8768185"} ==> expected: <200 OK> but was: <403 FORBIDDEN>
[ERROR]   DomainBEndpointsE2ETest$ScreeningRecords.contract_callable_frontline_roles_reach_b1:245 ��ɫ therapist �� B1 ���� 200����Լ B1 x-callable-roles: [therapist, meridian, admin]��admin չ�� = manager/area/hq����
? ���õ� 403������ĳ����� B1 ���� @RequirePermission �루customer:write��û������ therapist/meridian �������뼶�Ž����������ࡺ��ɫ��ȱʧ����ʵ��: 403 FORBIDDEN body={"code":2001,"message":"Ȩ�޲���: customer:archive","trace_id":"653a237c75a445aabf860842db9fa2e9"} ==> expected: <200 OK> but was: <403 FORBIDDEN>
[INFO] 
[ERROR] Tests run: 21, Failures: 3, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  14.764 s
[INFO] Finished at: 2026-09-26T04:30:18+08:00
[INFO] ------------------------------------------------------------------------
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test (default-test) on project dy-app: There are test failures.
[ERROR] 
[ERROR] Please refer to C:\Users\lenovo\WorkBuddy\2026-09-16-10-37-59\deliverables\product-strategy\skeleton\dy-app\target\surefire-reports for the individual test results.
[ERROR] Please refer to dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
[ERROR] -> [Help 1]
[ERROR] 
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR] 
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
```

## I2 · 缺口A 的另一形态（码有主但角色不匹配）：把 B1 的码改回 customer:write

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/controller/CustomerController.java`
- 注入（old -> new）：`@RequirePermission("customer:archive")
    @PostMapping("/screening-records")` -> `@RequirePermission("customer:write")
    @PostMapping("/screening-records")`
- 捕捉者：真请求 E2E `DomainBEndpointsE2ETest`
- 预期失败点：真请求 E2E 必须变红（一线角色调 B1 拿到 403）；而【码级门禁仍全绿】—— 故本脚本额外断言门禁在 I2 下绿
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   DomainBEndpointsE2ETest$Consents.declined_band_does_not_downgrade_service:525 ǰ��ʧ�ܣ�ɸ���ύӦ 200��ʵ��: 403 FORBIDDEN body={"code":2001,"message":"Ȩ�޲���: customer:write","trace_id":"bdff7803d8fc4e468fa658a011115c94"} ==> expected: <200 OK> but was: <403 FORBIDDEN>
[ERROR]   DomainBEndpointsE2ETest$Consents.front_line_role_can_sign_consent_after_profiled:470 ǰ��ʧ�ܣ�ɸ���ύӦ 200��ʵ��: 403 FORBIDDEN body={"code":2001,"message":"Ȩ�޲���: customer:write","trace_id":"123641d039074397bb0d1bfdbb060c74"} ==> expected: <200 OK> but was: <403 FORBIDDEN>
[ERROR]   DomainBEndpointsE2ETest$CustomerDetail.client_include_verdict_is_denied_with_contract_field_names:584 ǰ��ʧ�ܣ�ɸ���ύӦ 200��ʵ��: 403 FORBIDDEN body={"code":2001,"message":"Ȩ�޲���: customer:write","trace_id":"57ae56911184418abb54e698b05354a7"} ==> expected: <200 OK> but was: <403 FORBIDDEN>
[ERROR]   DomainBEndpointsE2ETest$CustomerDetail.client_sees_detail_but_without_the_two_store_fields_entirely:552 ǰ��ʧ�ܣ�ɸ���ύӦ 200��ʵ��: 403 FORBIDDEN body={"code":2001,"message":"Ȩ�޲���: customer:write","trace_id":"1bbff35ebd17456e9f509ad1eb710f5f"} ==> expected: <200 OK> but was: <403 FORBIDDEN>
[ERROR]   DomainBEndpointsE2ETest$CustomerDetail.staff_may_include_verdict:605 ǰ��ʧ�ܣ�ɸ���ύӦ 200��ʵ��: 403 FORBIDDEN body={"code":2001,"message":"Ȩ�޲���: customer:write","trace_id":"f9e3130816ca47d99c6d2a63d2b4cc52"} ==> expected: <200 OK> but was: <403 FORBIDDEN>
[ERROR]   DomainBEndpointsE2ETest$Customers.client_is_denied_on_b2:447 ǰ��ʧ�ܣ�ɸ���ύӦ 200��ʵ��: 403 FORBIDDEN body={"code":2001,"message":"Ȩ�޲���: customer:write","trace_id":"a861b774dabb4bd9863d19951d4c7618"} ==> expected: <200 OK> but was: <403 FORBIDDEN>
[ERROR]   DomainBEndpointsE2ETest$Customers.contract_callable_frontline_roles_reach_b2:332 ǰ��ʧ�ܣ�ɸ���ύӦ 200��ʵ��: 403 FORBIDDEN body={"code":2001,"message":"Ȩ�޲���: customer:write","trace_id":"b6bc54b5a5e949ac94a256a4d981366c"} ==> expected: <200 OK> but was: <403 FORBIDDEN>
[ERROR]   DomainBEndpointsE2ETest$Customers.rejected_customer_stays_blocked_on_b2:414 ǰ��ʧ�ܣ�ɸ���ύӦ 200��ʵ��: 403 FORBIDDEN body={"code":2001,"message":"Ȩ�޲���: customer:write","trace_id":"27ff4b9052b94e3aa6166d794f4cba09"} ==> expected: <200 OK> but was: <403 FORBIDDEN>
[ERROR]   DomainBEndpointsE2ETest$ScreeningRecords.b1_appends_rather_than_overwrites:307 �� 1 ���ύӦ 200 ==> expected: <200 OK> but was: <403 FORBIDDEN>
[ERROR]   DomainBEndpointsE2ETest$ScreeningRecords.b1_missing_items_is_400_not_403:293 ȱ items_json ���� 400����Լ B1 responses �� 400 ValidationFailed����ʵ��: 403 FORBIDDEN body={"code":2001,"message":"Ȩ�޲���: customer:write","trace_id":"9dee33ce247a409ebf63f26cf6ccc6fe"} ==> expected: <400 BAD_REQUEST> but was: <403 FORBIDDEN>
[ERROR]   DomainBEndpointsE2ETest$ScreeningRecords.contract_callable_frontline_roles_reach_b1:245 ��ɫ therapist �� B1 ���� 200����Լ B1 x-callable-roles: [therapist, meridian, admin]��admin չ�� = manager/area/hq����
? ���õ� 403������ĳ����� B1 ���� @RequirePermission �루customer:write��û������ therapist/meridian �������뼶�Ž����������ࡺ��ɫ��ȱʧ����ʵ��: 403 FORBIDDEN body={"code":2001,"message":"Ȩ�޲���: customer:write","trace_id":"c610b3979b6148c5ac8c02b08671c4d9"} ==> expected: <200 OK> but was: <403 FORBIDDEN>
[INFO] 
[ERROR] Tests run: 21, Failures: 11, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  13.096 s
[INFO] Finished at: 2026-09-26T04:31:09+08:00
[INFO] ------------------------------------------------------------------------
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test (default-test) on project dy-app: There are test failures.
[ERROR] 
[ERROR] Please refer to C:\Users\lenovo\WorkBuddy\2026-09-16-10-37-59\deliverables\product-strategy\skeleton\dy-app\target\surefire-reports for the individual test results.
[ERROR] Please refer to
```

## I3 · 缺口B 复发：给 E5 端点贴回一个（有主的）@RequirePermission

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/band/controller/BandAvailableDatesController.java`
- 注入（old -> new）：`    @PostMapping("/available-dates")` -> `    @com.diaoyuanyun.dy.security.permission.RequirePermission("customer:read")
    @PostMapping("/available-dates")`
- 捕捉者：真请求 E2E `DerivedVisibilityE2ETest`
- 预期失败点：E5 守护用例必须变红：合法 client 探测（无派生键）拿到 403 —— 因注册表刻意不登记 client
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   DerivedVisibilityE2ETest.legitimate_client_probe_without_derived_keys_must_reach_200:205 ? �Ϸ��ͻ���̽�⡾���� 200������Լ E5 x-callable-roles: [client]��responses ֻ�� 200/400/422��û�� 403�����õ� 403 �����ԭ���Ǳ��˵㱻����һ�� @RequirePermission �� ���� ��ע������ⲻ�Ǽ� client�����κ��붼������ͻ��˺� 403��E5 �ǿͻ�С����Ĺؼ�·������ȱ�ڻ��������豸̽�⹦�ܶԿͻ���ȫ�����á�ʵ��: 403 FORBIDDEN body={"code":2001,"message":"Ȩ�޲���: customer:read","trace_id":"6a7872415da541d2b64892551afbca0c"} ==> expected: <200 OK> but was: <403 FORBIDDEN>
[INFO] 
[ERROR] Tests run: 11, Failures: 1, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  12.183 s
[INFO] Finished at: 2026-09-26T04:31:53+08:00
[INFO] ------------------------------------------------------------------------
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test (default-test) on project dy-app: There are test failures.
[ERROR] 
[ERROR] Please refer to C:\Users\lenovo\WorkBuddy\2026-09-16-10-37-59\deliverables\product-strategy\skeleton\dy-app\target\surefire-reports for the individual test results.
[ERROR] Please refer to dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
[ERROR] -> [Help 1]
[ERROR] 
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR] 
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
```

## I4 · 自环门禁复发：B2 的门禁换回 assertAdmissionChain（内含 assertProfiled）

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/service/CustomerService.java`
- 注入（old -> new）：`CustomerGateGuard.assertScreeningResult("B2 POST /customers", hasPassing, current);` -> `CustomerGateGuard.assertAdmissionChain("B2 POST /customers", hasPassing, current);`
- 捕捉者：真请求 E2E `DomainBEndpointsE2ETest`
- 预期失败点：真请求 E2E 必须变红：B2 恒 403 GATE_MISSING(PROFILED) —— 守卫挂在了它自己的产出上
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   DomainBEndpointsE2ETest$Consents.declined_band_does_not_downgrade_service:526 ǰ��ʧ�ܣ�����Ӧ 200��ʵ��: 403 FORBIDDEN body={"code":2002,"message":"�Ž�ȱʧ: PROFILED ���� [B2 POST /customers] �ͻ���δ������data-dict ��2.25 �� ���� G1����δ PROFILED��δ�������� ����ǩ֪��ͬ���须��PRD v1.6 ׼��˳��Ϊ������ɸ�� �� ���� �� ǩ֪��ͬ���须������ǰ̬ = <δ֪/��δ����״̬����¼> ���� \uD83D\uDED1 ȱʧ��������ȡ G1 �� [PROFILED]����д�»��ߣ�������д�ɽ���������������","data":{"missing_items":["PROFILED"]},"trace_id":"ff5f146638634511a2fdc821cd2e88aa"} ==> expected: <200 OK> but was: <403 FORBIDDEN>
[ERROR]   DomainBEndpointsE2ETest$Consents.front_line_role_can_sign_consent_after_profiled:471 ǰ��ʧ�ܣ�����Ӧ 200��ʵ��: 403 FORBIDDEN body={"code":2002,"message":"�Ž�ȱʧ: PROFILED ���� [B2 POST /customers] �ͻ���δ������data-dict ��2.25 �� ���� G1����δ PROFILED��δ�������� ����ǩ֪��ͬ���须��PRD v1.6 ׼��˳��Ϊ������ɸ�� �� ���� �� ǩ֪��ͬ���须������ǰ̬ = <δ֪/��δ����״̬����¼> ���� \uD83D\uDED1 ȱʧ��������ȡ G1 �� [PROFILED]����д�»��ߣ�������д�ɽ���������������","data":{"missing_items":["PROFILED"]},"trace_id":"21ed2608d2cd4ab7bf0e1202e2a1daad"} ==> expected: <200 OK> but was: <403 FORBIDDEN>
[ERROR]   DomainBEndpointsE2ETest$CustomerDetail.client_include_verdict_is_denied_with_contract_field_names:585 ǰ��ʧ�ܣ�����Ӧ 200��ʵ��: 403 FORBIDDEN body={"code":2002,"message":"�Ž�ȱʧ: PROFILED ���� [B2 POST /customers] �ͻ���δ������data-dict ��2.25 �� ���� G1����δ PROFILED��δ�������� ����ǩ֪��ͬ���须��PRD v1.6 ׼��˳��Ϊ������ɸ�� �� ���� �� ǩ֪��ͬ���须������ǰ̬ = <δ֪/��δ����״̬����¼> ���� \uD83D\uDED1 ȱʧ��������ȡ G1 �� [PROFILED]����д�»��ߣ�������д�ɽ���������������","data":{"missing_items":["PROFILED"]},"trace_id":"e77b1a6fdb1a41e3a793be24ca87ce4e"} ==> expected: <200 OK> but was: <403 FORBIDDEN>
[ERROR]   DomainBEndpointsE2ETest$CustomerDetail.client_sees_detail_but_without_the_two_store_fields_entirely:553 ǰ��ʧ�ܣ�����Ӧ 200��ʵ��: 403 FORBIDDEN body={"code":2002,"message":"�Ž�ȱʧ: PROFILED ���� [B2 POST /customers] �ͻ���δ������data-dict ��2.25 �� ���� G1����δ PROFILED��δ�������� ����ǩ֪��ͬ���须��PRD v1.6 ׼��˳��Ϊ������ɸ�� �� ���� �� ǩ֪��ͬ���须������ǰ̬ = <δ֪/��δ����״̬����¼> ���� \uD83D\uDED1 ȱʧ��������ȡ G1 �� [PROFILED]����д�»��ߣ�������д�ɽ���������������","data":{"missing_items":["PROFILED"]},"trace_id":"d2c8a4b2f14e430a9bca5896fd53a4e6"} ==> expected: <200 OK> but was: <403 FORBIDDEN>
[ERROR]   DomainBEndpointsE2ETest$CustomerDetail.staff_may_include_verdict:606 ǰ��ʧ�ܣ�����Ӧ 200��ʵ��: 403 FORBIDDEN body={"code":2002,"message":"�Ž�ȱʧ: PROFILED ���� [B2 POST /customers] �ͻ���δ������data-dict ��2.25 �� ���� G1����δ PROFILED��δ�������� ����ǩ֪��ͬ���须��PRD v1.6 ׼��˳��Ϊ������ɸ�� �� ���� �� ǩ֪��ͬ���须������ǰ̬ = <δ֪/��δ����״̬����¼> ���� \uD83D\uDED1 ȱʧ��������ȡ G1 �� [PROFILED]����д�»��ߣ�������д�ɽ���������������","data":{"missing_items":["PROFILED"]},"trace_id":"59e2fa111a94456badab7158e09865d9"} ==> expected: <200 OK> but was: <403 FORBIDDEN>
[ERROR]   DomainBEndpointsE2ETest$Customers.contract_callable_frontline_roles_reach_b2:339 ��ɫ therapist �� B2 ���� 200����Լ B2 x-callable-roles: [therapist, meridian, admin]��admin չ�� = manager/area/hq����
? ���õ� 403 �� missing_items=["PROFILED"]�����ǡ��Ի��Ž�����B2 �Ĳ������ǡ����������ʵ�����ʱ�ͻ���Ȼ��δ PROFILED ������ assertProfiled ���� B2 ��ǰ�û��� B2 100% �����á�ʵ��: 403 FORBIDDEN body={"code":2002,"message":"�Ž�ȱʧ: PROFILED ���� [B2 POST /customers] �ͻ���δ������data-dict ��2.25 �� ���� G1����δ PROFILED��δ�������� ����ǩ֪��ͬ���须��PRD v1.6 ׼��˳��Ϊ������ɸ�� �� ���� �� ǩ֪��ͬ���须������ǰ̬ = <δ֪/��δ����״̬����¼> ���� \uD83D\uDED1 ȱʧ��������ȡ G1 �� [PROFILED]����д�»��ߣ�������д�ɽ���������������","data":{"missing_items":["PROFILED"]},"trace_id":"a44970446a4c4f8e86afc4294178d3e4"} ==> expected: <200 OK> but was: <403 FORBIDDEN>
[INFO] 
[ERROR] Tests run: 21, Failures: 6, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
[I
```

## I5 · SQL 类型缺陷复发：UPDATE_PROFILE_SQL 的 owner_store_id 去掉 ::uuid

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/repository/CustomerLedger.java`
- 注入（old -> new）：`+ "  owner_store_id = ?::uuid, serving_store_id = ?::uuid,"` -> `+ "  owner_store_id = ?, serving_store_id = ?::uuid,"`
- 捕捉者：真请求 E2E `DomainBEndpointsE2ETest`
- 预期失败点：真请求 E2E 必须变红：B2 恒 500 BadSqlGrammarException（uuid 列收到 varchar 参数）
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   DomainBEndpointsE2ETest$Consents.declined_band_does_not_downgrade_service:526 ǰ��ʧ�ܣ�����Ӧ 200��ʵ��: 500 INTERNAL_SERVER_ERROR body={"code":9001,"message":"ϵͳ�쳣: BadSqlGrammarException","trace_id":"aebcdb69a5014c549e4120ed7ea39fb2"} ==> expected: <200 OK> but was: <500 INTERNAL_SERVER_ERROR>
[ERROR]   DomainBEndpointsE2ETest$Consents.front_line_role_can_sign_consent_after_profiled:471 ǰ��ʧ�ܣ�����Ӧ 200��ʵ��: 500 INTERNAL_SERVER_ERROR body={"code":9001,"message":"ϵͳ�쳣: BadSqlGrammarException","trace_id":"865dac230ffb44b2af135bcc22c978b0"} ==> expected: <200 OK> but was: <500 INTERNAL_SERVER_ERROR>
[ERROR]   DomainBEndpointsE2ETest$CustomerDetail.client_include_verdict_is_denied_with_contract_field_names:585 ǰ��ʧ�ܣ�����Ӧ 200��ʵ��: 500 INTERNAL_SERVER_ERROR body={"code":9001,"message":"ϵͳ�쳣: BadSqlGrammarException","trace_id":"0eaf163a02ac45a988ad27c1533f851c"} ==> expected: <200 OK> but was: <500 INTERNAL_SERVER_ERROR>
[ERROR]   DomainBEndpointsE2ETest$CustomerDetail.client_sees_detail_but_without_the_two_store_fields_entirely:553 ǰ��ʧ�ܣ�����Ӧ 200��ʵ��: 500 INTERNAL_SERVER_ERROR body={"code":9001,"message":"ϵͳ�쳣: BadSqlGrammarException","trace_id":"9332f37adccf41949aad49de1cad040f"} ==> expected: <200 OK> but was: <500 INTERNAL_SERVER_ERROR>
[ERROR]   DomainBEndpointsE2ETest$CustomerDetail.staff_may_include_verdict:606 ǰ��ʧ�ܣ�����Ӧ 200��ʵ��: 500 INTERNAL_SERVER_ERROR body={"code":9001,"message":"ϵͳ�쳣: BadSqlGrammarException","trace_id":"0a7e9ebc608f4e58ac3a44810fb339ff"} ==> expected: <200 OK> but was: <500 INTERNAL_SERVER_ERROR>
[ERROR]   DomainBEndpointsE2ETest$Customers.contract_callable_frontline_roles_reach_b2:339 ��ɫ therapist �� B2 ���� 200����Լ B2 x-callable-roles: [therapist, meridian, admin]��admin չ�� = manager/area/hq����
? ���õ� 403 �� missing_items=["PROFILED"]�����ǡ��Ի��Ž�����B2 �Ĳ������ǡ����������ʵ�����ʱ�ͻ���Ȼ��δ PROFILED ������ assertProfiled ���� B2 ��ǰ�û��� B2 100% �����á�ʵ��: 500 INTERNAL_SERVER_ERROR body={"code":9001,"message":"ϵͳ�쳣: BadSqlGrammarException","trace_id":"66808d407ac241a2bb31c273b365f575"} ==> expected: <200 OK> but was: <500 INTERNAL_SERVER_ERROR>
[INFO] 
[ERROR] Tests run: 21, Failures: 6, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  12.775 s
[INFO] Finished at: 2026-09-26T04:33:10+08:00
[INFO] ------------------------------------------------------------------------
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test (default-test) on project dy-app: There are test failures.
[ERROR] 
[ERROR] Please refer to C:\Users\lenovo\WorkBuddy\2026-09-16-10-37-59\deliverables\product-strategy\skeleton\dy-app\target\surefire-reports for the individual test results.
[ERROR] Please refer to dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
[ERROR] -> [Help 1]
[ERROR] 
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR] 
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
```

## I6 · NPE 缺陷复发：修订快照换回 Map.copyOf（拒收 null 值）

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/domain/IntakeProfileRevisionRow.java`
- 注入（old -> new）：`snapshot = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(snapshot));` -> `snapshot = Map.copyOf(snapshot);`
- 捕捉者：真请求 E2E `DomainBEndpointsE2ETest`
- 预期失败点：真请求 E2E 必须变红：B6 首次写入恒 500 NullPointerException（快照含 null 值）
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   DomainBEndpointsE2ETest$IntakeProfiles.b6_first_write_creates_profile_with_revision_one:658 ? ��Լ B6 �� responses���� 200������ �ʶԡ�δ�������ͻ��״�д�����ɹ���revision_no=1�������ñ� 403������������δ�������룩��ʵ��: 500 INTERNAL_SERVER_ERROR body={"code":9001,"message":"ϵͳ�쳣: NullPointerException","trace_id":"1411476e9f224e2cb143a270ebac3e1d"} ==> expected: <200 OK> but was: <500 INTERNAL_SERVER_ERROR>
[ERROR]   DomainBEndpointsE2ETest$IntakeProfiles.b6_second_write_appends_revision_history:679 �ڶ����޶��� revision_no ������ 2��ʵ��:  ==> expected: <2> but was: <0>
[INFO] 
[ERROR] Tests run: 21, Failures: 2, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  12.840 s
[INFO] Finished at: 2026-09-26T04:33:49+08:00
[INFO] ------------------------------------------------------------------------
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test (default-test) on project dy-app: There are test failures.
[ERROR] 
[ERROR] Please refer to C:\Users\lenovo\WorkBuddy\2026-09-16-10-37-59\deliverables\product-strategy\skeleton\dy-app\target\surefire-reports for the individual test results.
[ERROR] Please refer to dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
[ERROR] -> [Help 1]
[ERROR] 
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR] 
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
```


被触碰源文件（逐字节还原校验）：

- `dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/controller/CustomerController.java`
- `dy-security/src/main/java/com/diaoyuanyun/dy/security/permission/PermissionRegistry.java`
- `dy-app/src/main/java/com/diaoyuanyun/dy/app/band/controller/BandAvailableDatesController.java`
- `dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/service/CustomerService.java`
- `dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/repository/CustomerLedger.java`
- `dy-app/src/main/java/com/diaoyuanyun/dy/app/customer/domain/IntakeProfileRevisionRow.java`

总体：全部被抓 且 已还原全绿
