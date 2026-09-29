# 批次十三 · 反向验证证据 —— B-2b prod + redis 启动期探活纪律

> 脚本：`verification/111_b2b_redis_startup_probe_reverse_verification.py`
> 门禁：`dy-web` · `IdempotencyBackendFailureTest`（11 → **14** 例）
> 结论：**3/3 PASS**（2 组注入全被抓 + 还原后复绿）

## 一、这条纪律要守什么（以及为什么批次十二没守住）

批次十二的 **B-2** 立论是："配置错误应在**启动阶段**暴露，而不是运行期静默降级。"
但当时的两道防线只覆盖：

| 防线 | 覆盖 | 未覆盖 |
|------|------|--------|
| ① prod 段默认 redis（`IdempotencyProdBackendConfigGateTest`） | prod 不会是 memory | — |
| ② prod + memory ⇒ 拒启动（`reject_memory_backend_in_production`） | prod 不会是 memory | **prod + redis 但连不上** |

**漏掉的那一半是更隐蔽的失效**：

```
prod 配 redis + Redis 主机写错 / 未启动 / 网络不通
  → Lettuce 惰性建连（本仓 dy-web/pom.xml 注释逐字确证）
  → 应用【照常启动成功】，健康检查也只是 DOWN、不阻断
  → 直到【第一个带 Idempotency-Key 的写请求】才全线 5xx
```

即：**配置错误被推迟到了"业务已经在跑"的时刻**。这与 B-2 自身立论（要启动期暴露）**自相矛盾** —— 是真正的门禁牙齿不全，不是"锦上添花的增强"。

## 二、注入表

| 组 | 文件 | 锚点（单行） | 注入后 | 守的失效模式 |
|---|---|---|---|---|
| C1 | `dy-web/.../IdempotencyConfiguration.java` | `        if (!prodActive) {` | `        if (true) {` | **自检根本没跑**（守卫条件被掏空 ⇒ 恒 return） |
| C2 | 同上 | `        } catch (RuntimeException e) {` | catch 行后插入 `if (true) { log.warn(...); return; }` | **自检跑了但异常被吞**（fail-closed 退化成 fail-open） |

🛑 **为什么要两组**：C1 与 C2 是同一条纪律的**两个不同失效模式**。只注入 C1 会让"守卫还在、但 catch 被改成静默返回"这类改动**静默通过**；反之亦然。两者都注入，才能证明"拒启动"这个行为在**两个可能被掏空的点上**都有牙齿。

## 三、实测输出（关键行）

```
[快照] dy-web/.../IdempotencyConfiguration.java bytes=12062
[预检 OK ] C1 锚点出现 1 次
[预检 OK ] C2 锚点出现 1 次

[基线 OK] 注入前全绿，且待查方法名在基线中【零出现】（锚点有判别力）

[C1] 门禁 exit=1 · 期望红 `production_with_unreachable_redis_refuses_to_start` 被抓=True
     红集=['production_with_unreachable_redis_refuses_to_start']
[C2] 门禁 exit=1 · 期望红 `production_with_unreachable_redis_refuses_to_start` 被抓=True
     红集=['production_with_unreachable_redis_refuses_to_start']

[复绿] web exit=0 · 复绿=True

结论: 3/3 PASS
```

## 四、纪律遵守情况（与 100~110 同口径）

- **锚点预检**（存在 + 唯一）：C1/C2 各恰 1 次 —— 若为 0 次，`replace` 会退化成**空操作**
  ⇒ 注入根本没发生 ⇒ 门禁全绿 ⇒ 把"有牙齿"误报成"没牙齿"（假阴性）。
- **锚点用单行**（技能 8.7）：初版 C1/C2 曾写多行锚点，实测告知在多行/转义链路下不可靠 ⇒ 全部改单行。
  实测三处候选锚点各恰 1 次：
  `        if (!prodActive) {` → 1 · `        } catch (RuntimeException e) {` → 1 · `            redis.hasKey(PROBE_KEY);` → 1
- **元层判别力自证**：基线（全绿）输出里待查方法名**零出现** ⇒ `must_see` 锚点不是恒真。
- **逐字节还原**：`OK ... bytes=12062/12062`；还原后**复绿**（exit=0）。
- **语法合法性**：C1 只改守卫条件；C2 插入一条合法 `if (true) { ...; return; }`。两者编译通过
  （门禁 exit=1 是**断言失败**而非编译错误）。
- **跑对模块**：两处注入都在 `dy-web` 源 ⇒ 只跑 `dy-web`，不需 install。
- **输出判定锚点 = ASCII 方法名**（Windows GBK 下中文会乱码）。

## 五、新增门禁用例（`IdempotencyBackendFailureTest` 11 → 14）

| 用例 | 断言 |
|---|---|
| `production_with_unreachable_redis_refuses_to_start` | prod + 探活抛连接异常 ⇒ `IllegalStateException`，且消息含 `redis` 与"启动"语义 |
| `production_with_reachable_redis_starts_normally` | prod + 探活成功 ⇒ 不抛（拦了就等于"生产没法启动"） |
| `non_production_does_not_probe_redis_at_startup` | dev/test/local + 探活必抛 ⇒ **不抛**（本地无 Redis 属正常，不得误伤） |

探活替身用**真实 `StringRedisTemplate` 匿名子类**覆写 `hasKey(...)`（不是 Mockito stub，本仓无 Mockito 依赖），
使"可达 / 不可达"由被覆写方法的**真实调用路径**决定，避免替身与真实行为不一致而给出假结论。