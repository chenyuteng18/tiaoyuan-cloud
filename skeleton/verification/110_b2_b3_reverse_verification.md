# 批次十二 · 反向验证证据（110）

> 脚本：`verification/110_b2_b3_reverse_verification.py`
> 结论：**5/5 PASS**（4 组注入全部被抓 + 还原后复绿）
> 日期：2026-09-27

## 一、本脚本要证明什么

批次十二收口两条**此前只写在注释 / 部署文档里、没有任何测试守护**的纪律。
它们的共同特征是：**失效时完全静默** —— 系统照常启动、请求照常 200、监控照常全绿。

| 编号 | 收口内容 | 新增/修改的守护 |
|---|---|---|
| **B-2** | 生产环境禁内存幂等后端 | `IdempotencyConfiguration.reject_memory_backend_in_production`（启动自检）＋ `application.yml` prod 段显式 redis 默认 ＋ `IdempotencyBackendFailureTest` **+5 例** ＋ 新建 `IdempotencyProdBackendConfigGateTest`（2 例） |
| **B-3** | 配置来源过渡态显式化 | 新建 `ConfigSourceTransitionGateTest`（**7 例**，构建期无 DB） |

## 二、注入表与结果

| 组 | 文件 | 注入内容 | 期望变红 | 结果 |
|---|---|---|---|---|
| C1 | `dy-app/src/main/resources/application.yml` | prod 段 `backend: ${DY_IDEMPOTENCY_BACKEND:redis}` → `backend: memory` | `prod_profile_declares_a_redis_default_for_the_idempotency_backend` | ✅ PASS |
| C2 | `dy-web/.../IdempotencyConfiguration.java` | 自检守卫 `if (!prodActive \|\| !MEMORY.equals(mode)) {` → `if (true) {`（恒 return ⇒ 永不抛） | `production_profile_refuses_to_start_with_the_in_memory_backend` | ✅ PASS（连带 `..._whitespace_and_case_variants` 也红） |
| C3 | `dy-app/.../ConfigSeedScaleProfileSource.java` | `describeSource()` 去掉「过渡」字样 | `every_transition_source_declares_itself_as_a_transition` | ✅ PASS |
| C4 | `dy-app/.../ConfigSeedRefundProfileSource.java` | `SEED_RESOURCE` 常量改名 | `seed_resource_constant_points_to_the_classpath_seed_file` | ✅ PASS |
| — | — | 全部还原后重跑两模块门禁 | 全绿 | ✅ PASS |

**结论：5/5 PASS。**

## 三、这次预检**当场抓到**的一个真问题（值得记下来）

C1 的首版锚点是**两行**：

```
  idempotency:
    backend: ${DY_IDEMPOTENCY_BACKEND:redis}
```

预检报 **锚点出现 0 次** —— 而文件里这两行**肉眼可见地在**（就在文件末尾）。
逐字节排查后确认：这是多行锚点在 Windows + heredoc 传递链路下的**不稳定性**
（`${...}` 与换行在多层转义中都可能被改写）。

🛑 **这正是"锚点预检"的价值所在**，而且它救的**不是"注入失效"、而是"注入生效但判定错"**：
- 若没有预检，脚本会把 `text.replace(anchor, repl)` 做成**空操作**（替换 0 次），
  于是注入**根本没发生**，门禁自然全绿 ⇒ 脚本会报"未被抓住" ⇒
  **一个把"门禁有牙齿"误判成"门禁没牙齿"的假阴性**（与批次十 99 脚本那次假阴性同源，但成因不同）。
- 修法：**锚点改为单行**（`    backend: ${DY_IDEMPOTENCY_BACKEND:redis}`，预检确证恰 1 次）。
  单行锚点在多层转义链路下不会遇到"换行被改写"的问题。

**纪律沉淀**：多行锚点在跨工具链路（heredoc / shell / python）中不可靠，
**优先用单行锚点**；若语义必须跨行，预检必须打印**逐字节长度**而不只是 `count`。

## 四、为什么 C2 的注入方式选"掏空守卫条件"而不是"删掉整个方法"

C2 只改一行（`if (...)` → `if (true)`），保持 Java 语法合法。
这种方式精确模拟了最可能的退化形态：**有人为了让测试变绿，把守卫条件改成恒真/恒假**。
它比"删掉整个方法"更隐蔽（编译仍通过、代码看起来还在），因此是更有价值的注入。

**红集比期望更宽**（额外红了 `..._whitespace_and_case_variants`）是合理的：
掏空守卫后，连"变体必须仍被判为 prod"这条也随之失效 ——
说明两条断言**守的是同一个守卫的两个侧面**（存在性 + 归一化正确性），互为交叉印证。

## 五、纪律（与 102~109 同口径）

- **锚点预检**（存在 + 唯一）· **元层判别力自证**（基线零出现）· **逐字节还原** · **还原后复绿**。
- 🛑 注入必须保持源文件语法合法（改字面量 / 改条件，不删结构）。
- 🛑 must_see 锚点一律用 **ASCII**（方法名）—— Windows GBK 下中文断言消息可能乱码。
- 🛑 Windows 下 `mvn.cmd` 绝对路径 + list 参数 + `shell=False`；**不加 `-q`**。
- 🛑 跨模块注入跑对模块：C2 改 dy-web 源 ⇒ 跑 dy-web；C1/C3/C4 改 dy-app ⇒ 跑 dy-app。
  两处均**无需 install**（同模块内 `test` 即可）。

## 六、与 108 / 109 的关系

本脚本与 108（配置槽消费反向验证）、109（跨源一致性 + 契约驱动角色矩阵）**无耦合**：
- 108 注入的是配置**槽消费**的接线语句；本轮未改动任何 `ConfigSeed*` 的**解析逻辑**，
  只改其 `describeSource()` 的返回值与新增门禁 ⇒ 108 的锚点不受影响（已复核）。
- 109 注入的是契约 / V6 / 字典三个文件；本轮未触碰这三者（已复核）。