# JWT 鉴权补全 · 反向验证报告

**日期**：2026-09-16
**对象**：`skeleton/dy-tenancy/src/main/java/com/diaoyuanyun/dy/tenancy/jwt/JwtVerifier.java`
**方法**：注入错误 → 断言测试失败 → 还原 → 断言恢复全绿
**结论**：**PASS —— 15 个 JWT 测试确有牙齿（能抓住退化，不是"永远通过"）**

---

## 1. 为什么做这件事

骨架上一轮报告"BUILD SUCCESS + 25 测试全绿"，但独立复核发现 7 处缺陷、4 处严重 —— **测试之所以全绿，是因为断言的是实现者自己的实现，而非外部契约**。所以本轮所有安全逻辑，验收不只看"测试绿"，必须证明"注入错误后测试会红"。

---

## 2. 基线

| 项 | 值 |
|---|---|
| 命令 | `mvn -pl dy-tenancy -am test`（reactor 内构建，避免 dy-common 解析失败） |
| 结果 | **BUILD SUCCESS** |
| `JwtVerifierTest` | **15 tests, 0 failures, 0 errors** |

---

## 3. 三次注入

### S1 — 去掉 `alg` 等值判断（模拟 `alg:none` 绕过）

**注入**：`if (alg == null || !"HS256".equalsIgnoreCase(alg)) {` → `if (false) {`

| | |
|---|---|
| 注入后结果 | **BUILD FAILURE** ✅ 被抓住 |
| 抓它的测试 | `alg_confusion_rs256_header_must_be_rejected` |
| 断言原文 | `Expected JwtValidationException to be thrown, but nothing was thrown.` |

> **意义**：这是 JWT 最经典的绕过（攻击者把 header 改成 `alg:none` 并去掉签名）。注入后测试立即报"本该抛异常却没抛"—— 说明测试真的在验证"拒绝"这件事，而不是在验证返回值。

### S2 — 去掉签名比较（模拟完全不验签）

**注入**：`if (!MessageDigest.isEqual(expected, actual)) {` → `if (false) {`

| | |
|---|---|
| 注入后结果 | **BUILD FAILURE** ✅ 被抓住（2 个测试同时报错） |
| 抓它的测试 | `tampered_payload_must_be_rejected:89` · `token_signed_with_wrong_secret_must_be_rejected:99` |
| 断言原文 | `Expected JwtValidationException to be thrown, but nothing was thrown.` |

> **意义**：篡改载荷、用别人密钥签发 —— 两种最直接的伪造手段都被覆盖。

### S3 — 去掉 `exp` 过期校验（模拟永久有效 token）

**注入**：`if (payload.has("exp") && payload.get("exp").asLong() + skew < now) {` → `if (false) {`

| | |
|---|---|
| 注入后结果 | **BUILD FAILURE** ✅ 被抓住 |
| 抓它的测试 | `expired_token_must_be_rejected:120` |
| 断言原文 | `Expected JwtValidationException to be thrown, but nothing was thrown.` |

> **意义**：过期校验是最容易被"为了调试方便"临时关掉的一项，测试守住了它。

---

## 4. 还原

| 项 | 结果 |
|---|---|
| 还原后构建 | **BUILD SUCCESS** |
| `JwtVerifierTest` | 15 tests, 0 failures |
| 文件字节数 | 9272（与注入前一致） |
| 三处防护在位 | `alg` 等值判断 ✅ · `MessageDigest.isEqual` ✅ · `exp` 校验 ✅ |
| 残留 `if (false)` | 0 处 |
| 临时备份文件 | 已删除 |

---

## 5. 复现步骤

```powershell
# 1) 备份
Copy-Item JwtVerifier.java JwtVerifier.java.bak

# 2) 注入（任选上述一条）
#    把目标 if 条件改为 if (false)

# 3) 断言必须失败
& $mvn -f skeleton\pom.xml -B -ntp -pl dy-tenancy -am test
# 期望：BUILD FAILURE，且报 "Expected JwtValidationException to be thrown, but nothing was thrown."

# 4) 还原
Copy-Item JwtVerifier.java.bak JwtVerifier.java -Force
Remove-Item JwtVerifier.java.bak

# 5) 断言恢复全绿
& $mvn -f skeleton\pom.xml -B -ntp clean package
# 期望：BUILD SUCCESS
```

> **注意**：必须用 `-pl dy-tenancy -am`。直接 `-f dy-tenancy/pom.xml` 会因 `dy-common` 未安装到本地仓库而解析失败（报 `Could not find artifact com.diaoyuanyun:dy-common`），此时 exit≠0 是**依赖解析失败**，不是测试失败 —— 两者必须区分，否则反向验证会得出"全部被抓住"的假结论。

---

## 6. 本轮顺带修掉的真实缺陷（由新测试抓出）

新增的过滤器级测试在首次运行时**报红**，抓出一处**此前从未被发现**的契约违约：

| 项 | 内容 |
|---|---|
| 症状 | 401 响应体只有 `{"code":1002,"message":"..."}`，**`trace_id` 整个键消失** |
| 违约依据 | 契约 §2.0：信封四字段"不得增删" |
| 根因① | `Result` 类级 `@JsonInclude(NON_NULL)` 把 null 的 `trace_id` 吞掉 |
| 根因② | `TraceIdFilter` 未固定顺序，可能晚于 `TenantContextFilter` 执行，致 MDC 无 trace_id |
| 修法① | `traceId` 字段与 getter 加 `@JsonInclude(ALWAYS)` 覆盖类级策略 |
| 修法② | `TraceIdFilter` → `@Order(HIGHEST_PRECEDENCE + 10)`；`TenantContextFilter` → `+100`；并在过滤器内做 trace_id 兜底生成 |
| 回归守护 | `ResultEnvelopeTest.trace_id_key_must_survive_even_when_null` + `TenantContextFilterTest.invalid_token_must_return_401_with_standard_envelope` |

> **这条比 JWT 本身更值得记住**：它证明"过滤器级测试"不是冗余 —— 旧测试只调静态方法，永远碰不到这个洞。

---

## 7. 测试总数变化

| 阶段 | 测试数 | 说明 |
|---|---|---|
| 上一轮交付 | 36 | 骨架基线 |
| + `JwtVerifierTest` | 15 | 攻击面回归（alg:none / 篡改 / 错误密钥 / 过期 / nbf / iss / 缺租户 / 缺 exp / 格式 / 密钥缺失 / 弱密钥 / 吊销）|
| + `TenantContextFilterTest` | 6 | 过滤器级行为（401 信封 / 403 信封 / 匿名不建上下文 / 正常建立与清理）|
| + `ResultEnvelopeTest` 回归 | 1 | `trace_id` 为 null 时也必须在 JSON 里 |
| **本轮** | **58** | **BUILD SUCCESS, 0 failure, 0 error** |

---

> 本报告由产品战略团队 AI 协作生成，重要决策请由产品负责人审定。