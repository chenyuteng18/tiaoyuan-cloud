# C-1 · 审计链校验端点反向验证证据

四列证据：注入内容 | 预期失败点 | 实际失败断言原文 | 判定

> 🛑 编码须知同 94/98/99_*.md：乱码为证据原貌，判定以英文方法名/码为准。

## K1 · 计数防线被掏空：控制器把 checked 恒写 0（= 把表读成 0 行却报 valid 的假通过形态）

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/audit/controller/AuditChainController.java`
- 注入：`        data.put("checked", v.checked());` -> `        data.put("checked", 0);`
- 捕捉者：真请求 E2E `AuditChainEndpointE2ETest$Callability`
- 预期失败点：穿透保真断言必须变红：控制器把 checked 写死为 0，与 verifyChain() 的权威值不符。checked 是『记录条数』而非『通过条数』，它的唯一用途就是让『链有效』与『链是空的』无法被混淆。
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   AuditChainEndpointE2ETest$Callability.hq_reaches_the_endpoint_with_exact_passthrough_fidelity:145 HTTP �� checked ������ verifyChain() һ�� ==> expected: <191> but was: <0>
[INFO] 
```

## K2 · 权限防线被撤：摘掉 @RequirePermission("audit:read")

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/audit/controller/AuditChainController.java`
- 注入：`    @RequirePermission("audit:read")
    @GetMapping("/audit/log-chain")` -> `    @GetMapping("/audit/log-chain")`
- 捕捉者：真请求 E2E `AuditChainEndpointE2ETest$Callability`
- 预期失败点：hq only 断言必须变红：失去 audit:read 这一层后，area / manager / therapist / meridian 都仍是 staff，会被 @StaffOnly 放行 ⇒ 端点变成 staff 全可调。而 audit_log 是跨租户串联的全局单链，契约 F3 的 x-row-scope 预设了『对象属于某租户』⇒ 那会开出一条语义裂缝。
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   AuditChainEndpointE2ETest$Callability.non_hq_roles_are_denied_by_the_permission_layer:169 ? area ���õ��������У�� ���� audit_log ��ȫ�ֵ��������⻧���������������κε�һ�⻧������Լ F3 �� x-row-scope���ŵ������ / �����Ͻ����Ԥ���ˡ���������ĳ�⻧�����Ž����Ļ������������ scope �����ݶ�ȫ�ֶ��󡻵������ѷ졣ʵ��: 200 OK {"code":0,"message":"OK","data":{"valid":true,"reason":null,"checked":193,"capability_envelope":["��֤�������м���һ�еĸ�д / ɾ���ر����ֲ���λ������ ? ���Լ� hash ʧ�䣻ɾ�м��� ? ���� prev_hash ʧ�䣩","����֤���٣����ݴ�δ���۸� ���� ��ȡ�� UPDATE Ȩ���߿��Զϵ����������㲢��д����ʱ���˵㷵�� valid=true������Կ��ϣ���Ĺ������ʣ�","����֤���ڣ���βδ���ض� ���� ɾ�����������������¼�����κκ�̿��飬���˵�ͬ������ valid=true��Ψһ�ɹ۲⼣���� checked �������� checked ֻ��˵���������ж�������������˵����Ӧ���ж�������","��ê����ÿ�� HMAC / ���ڰ� (count, tail_hash) ê������������ʱ��� / WORM �洢��������������Ψһ����·������ ADR-11 �������δʵ��"],"not_proven":"�����۲����ɡ���������ɴ۸ġ������õı����ǡ�������Ȼ�۸ġ����ҡ���Ȼ���ı߽�������������Χ�޶����м�۸Ļᱻ���֣�β���ض��뼶�����㲻�ᣩ","scope_note":"У�����Ϊ audit_log ȫ�ֵ��������⻧���������Ǳ��⻧�Ӽ�","caller_tenant":"e2e00000-0000-0000-0000-00000000c1c1"},"trace_id":"c4b6a852b5614c8990fcb9dad0188a39"} ==> expected: <403> but was: <200>
[ERROR]   AuditChainEndpointE2ETest$Callability.unknown_role_is_denied_not_treated_as_hq:224 δ�Ǽǽ�ɫ����ͨ����fail-closed��: {"code":0,"message":"OK","data":{"valid":true,"reason":null,"checked":192,"capability_envelope":["��֤�������м���һ�еĸ�д / ɾ���ر����ֲ���λ������ ? ���Լ� hash ʧ�䣻ɾ�м��� ? ���� prev_hash ʧ�䣩","����֤���٣����ݴ�δ���۸� ���� ��ȡ�� UPDATE Ȩ���߿��Զϵ����������㲢��д����ʱ���˵㷵�� valid=true������Կ��ϣ���Ĺ������ʣ�","����֤���ڣ���βδ���ض� ���� ɾ�����������������¼�����κκ�̿��飬���˵�ͬ������ valid=true��Ψһ�ɹ۲⼣���� checked �������� checked ֻ��˵���������ж�������������˵����Ӧ���ж�������","��ê����ÿ�� HMAC / ���ڰ� (count, tail_hash) ê������������ʱ��� / WORM �洢��������������Ψһ����·������ ADR-11 �������δʵ��"],"not_proven":"�����۲����ɡ���������ɴ۸ġ������õı����ǡ�������Ȼ�۸ġ����ҡ���Ȼ���ı߽�������������Χ�޶����м�۸Ļᱻ���֣�β���ض��뼶�����㲻�ᣩ","scope_note":"У�����Ϊ audit_log ȫ�ֵ��������⻧���������Ǳ��⻧�Ӽ�","caller_tenant":"e2e00000-0000-0000-0000-00000000c1c1"},"trace_id":"17f19539e9cb4d3099f5226e77aad184"} ==> expected: <403> but was: <200>
[INFO] 
```

## K3 · 能力边界被削：从 capability_envelope 里删掉「链尾截断」那一条

- 目标文件：`dy-app/src/main/java/com/diaoyuanyun/dy/app/audit/controller/AuditChainController.java`
- 注入：`                "不能证明②：链尾未被截断 —— 删除【最后若干条】记录不留任何后继可验，"
                        + "本端点同样返回 valid=true。唯一可观测迹象是 checked 条数，"
                        + "而 checked 只能说明『现在有多少条』、不能说明『应该有多少条』",
            ` -> `                "外锚定（每条 HMAC`
- 捕捉者：真请求 E2E `AuditChainEndpointE2ETest$Envelope`
- 预期失败点：四条齐全断言必须变红：边界被摊平为三条。尾部截断是 C-1 真请求实测新挖出的盲区（既有破坏性门禁只覆盖『删中间行』），而擦掉最近的操作痕迹恰是攻击者的首选动作 —— 删掉这句话等于对读端点的人隐瞒它。
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   AuditChainEndpointE2ETest$Envelope.capability_envelope_covers_all_four_items:243 ̯ƽΪ��������֤�� / ����֤���ټ������� / ����֤����β���ض� / ��ê��δʵ�֡�ʵ��: ["��֤�������м���һ�еĸ�д / ɾ���ر����ֲ���λ������ ? ���Լ� hash ʧ�䣻ɾ�м��� ? ���� prev_hash ʧ�䣩","����֤���٣����ݴ�δ���۸� ���� ��ȡ�� UPDATE Ȩ���߿��Զϵ����������㲢��д����ʱ���˵㷵�� valid=true������Կ��ϣ���Ĺ������ʣ�","��ê����ÿ�� HMAC / ���ڰ� (count, tail_hash) ê������������ʱ��� / WORM �洢��������������Ψһ����·������ ADR-11 �������δʵ��"] ==> expected: <4> but was: <3>
[INFO] 
```

## K4 · 权限登记防线被绕：给 area 补上 audit:read（只改 dy-security 的登记表，控制器一行不动）

- 目标文件：`dy-security/src/main/java/com/diaoyuanyun/dy/security/permission/PermissionRegistry.java`
- 注入：`        rolePermissions.put("area", Set.of("customer:read", "customer:write", "customer:archive",
                "report:region", "store:read",
                "refund:read", "refund:write", "refund:` -> `        rolePermissions.put("area", Set.of("customer:read", "customer:write", "customer:archive",
                "report:region", "store:read",
                "refund:read", "refund:write", "refund:`
- 捕捉者：真请求 E2E `AuditChainEndpointE2ETest$Callability`
- 预期失败点：hq only 断言必须变红：控制器一字未改，但登记表多一个持有者 ⇒ area 立刻可调。本条证明『可调角色』这件事的真正真相源是登记表，任何只看控制器的评审都会漏掉这条路径。
- 判定：**CAUGHT**
- 实际失败断言原文：

```
[ERROR] Failures: 
[ERROR]   AuditChainEndpointE2ETest$Callability.non_hq_roles_are_denied_by_the_permission_layer:169 ? area ���õ��������У�� ���� audit_log ��ȫ�ֵ��������⻧���������������κε�һ�⻧������Լ F3 �� x-row-scope���ŵ������ / �����Ͻ����Ԥ���ˡ���������ĳ�⻧�����Ž����Ļ������������ scope �����ݶ�ȫ�ֶ��󡻵������ѷ졣ʵ��: 200 OK {"code":0,"message":"OK","data":{"valid":true,"reason":null,"checked":196,"capability_envelope":["��֤�������м���һ�еĸ�д / ɾ���ر����ֲ���λ������ ? ���Լ� hash ʧ�䣻ɾ�м��� ? ���� prev_hash ʧ�䣩","����֤���٣����ݴ�δ���۸� ���� ��ȡ�� UPDATE Ȩ���߿��Զϵ����������㲢��д����ʱ���˵㷵�� valid=true������Կ��ϣ���Ĺ������ʣ�","����֤���ڣ���βδ���ض� ���� ɾ�����������������¼�����κκ�̿��飬���˵�ͬ������ valid=true��Ψһ�ɹ۲⼣���� checked �������� checked ֻ��˵���������ж�������������˵����Ӧ���ж�������","��ê����ÿ�� HMAC / ���ڰ� (count, tail_hash) ê������������ʱ��� / WORM �洢��������������Ψһ����·������ ADR-11 �������δʵ��"],"not_proven":"�����۲����ɡ���������ɴ۸ġ������õı����ǡ�������Ȼ�۸ġ����ҡ���Ȼ���ı߽�������������Χ�޶����м�۸Ļᱻ���֣�β���ض��뼶�����㲻�ᣩ","scope_note":"У�����Ϊ audit_log ȫ�ֵ��������⻧���������Ǳ��⻧�Ӽ�","caller_tenant":"e2e00000-0000-0000-0000-00000000c1c1"},"trace_id":"317cb7b3676f4435868475328dc63d11"} ==> expected: <403> but was: <200>
[INFO] 
```


被触碰源文件（逐字节还原校验）：

- `dy-app/src/main/java/com/diaoyuanyun/dy/app/audit/controller/AuditChainController.java`
- `dy-security/src/main/java/com/diaoyuanyun/dy/security/permission/PermissionRegistry.java`

总体：全部被抓 且 已还原全绿
