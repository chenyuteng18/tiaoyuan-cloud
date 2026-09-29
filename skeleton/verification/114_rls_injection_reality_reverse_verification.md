# 反向验证 114 — RLS 注入机制「文档机制 vs 实际机制」门禁

**日期**：2026-09-27 · 批次十三 · 第六轮
**被测对象**：`dy-app/src/test/java/com/diaoyuanyun/dy/app/provisioning/RlsInjectionRealityGateTest.java`（6 例）
**脚本**：`verification/114_rls_injection_reality_reverse_verification.py`
**结论**：**6 组注入 / 8 项判定 · 8/8 PASS**（含"还原后复绿"与"逐字节还原 + 无残留"）

---

## 一、被测对象守的是什么

批次十三第六轮实测抓出一条**文档与实现的分歧**（不是安全漏洞，但会导致两类实际危害）。
README §三 ADR-02 把 **L2 记为 `dy-tenancy/RlsSessionAspect`（`SET LOCAL`）**，
但代码走的完全是另一条路。实测三条（口径：剥离注释与字符串字面量后扫 8 模块 `src/main`）：

| # | 事实 | 实测值 |
|---|---|---|
| ① | `@RlsScoped` 的**生产使用数** | **0** —— 唯一出现是它自己的定义文件（`@interface` 声明不是使用） |
| ② | 切点 `@Before("@annotation(...RlsScoped)")` | **零匹配 ⇒ 永不触发** |
| ③ | `applyTenantSession` 在**生产代码**中的调用 | **0**（仅定义行；既未触发、也未被手工调用） |
| ④ | **真实机制**：`<T> T inTenant(String tenantId, Supplier<T> body)` 定义类 | **18 个**（17 dy-app 域仓储 + 1 dy-config 支撑类） |
| ⑤ | `SET LOCAL app.tenant_id = ...` 语句 | **19 处**（18 载体各 1 + 切面内 4 行含告警消息；另有 12 处出现在注释里） |
| ⑥ | `inTenant(` 调用点 | **139 处** |

⇒ **两条机制是替代关系，不是覆盖关系**：覆盖面**无缺口**，但文档描述的是一条**不被走的**路。

### 🛑 为什么这不是安全漏洞（必须先说清楚）

18 个载体的**显式传参**（`inTenant(tenantId, ...)`）覆盖面完整，且比依赖 ThreadLocal 的切面
**更难被误用** —— 一个方法想访问租户数据，如果不接 `tenantId` 参数，它根本写不出查询。
故本门禁的定性是「**文档与实现的分歧**」，不是「静默的安全陷阱」。

### 真实危害（两条，这才是门禁存在的理由）

1. **会误导下一个人做错事**：照 README 给新仓储方法加 `@RlsScoped`，以为"上下文有人管了"，
   于是在事务之外、或不经 `inTenant` 直接查 —— RLS 策略读到空 `app.tenant_id`，
   查询**静默返回零行**（fail-closed 的形态是"看起来正常的空结果"，**不是报错**）。
   这正是 `JdbcConfigSupport` 注释里逐字警告过的误用形态。
2. **会静默漂移**：两侧单独变更都不会红 —— 把登记表里的任一 `inTenant` 载体删掉一个（改用切面），
   或在别处新增一处 `@RlsScoped`，构建都照样 `BUILD SUCCESS`。
   （⚠️ 本报告写于批次十三，当时载体数为 18；**2026-09-30 实测已为 25**（dy-app 24 + dy-config 1）。
   此处刻意**不再硬编码数字** —— 该数随边界移动，「把上一批的数字抄进本批」正是本仓反复复发的缺陷形态。）

---

## 二、门禁的 6 条判据

| # | 判据 | 形态 |
|---|---|---|
| ① | `@RlsScoped` 生产使用数恒为 0（**双粒度核对**） | 断言空集 == 空集 |
| ② | 每个定义 `inTenant` 的类必须在同文件出现合格的 `SET LOCAL app.tenant_id = ...` | 差异真在 |
| ③ | `inTenant` 载体集合与登记表**双向一致**（漏登记红 + 僵尸登记红） | 集合相等 |
| ④ | `applyTenantSession` 在自身定义之外的生产代码里零出现 | 断言空集 == 空集 |
| ⑤ | **两级剥离粒度判别力自证**（`STRIP_STRINGS` / `KEEP_STRINGS` 必须给出不同结果） | 元层自证 |
| ⑥ | 文档机制的两个类必须存在（删除死机制需显式动作） | 存在性 + 切点内容 |

🛑 **判据⑤是本门禁的关键设计**：本仓的 SQL 与切点表达式**都住在 Java 字符串字面量里**
（`jdbc.execute("SET LOCAL ...")`、`@Before("@annotation(...RlsScoped)")`），
于是"剥不剥字符串"会得出**相反**的结论：

- 判据① 必须 `STRIP_STRINGS`（字符串里写 `"@RlsScoped"` 只是文本，不是注解被使用）；
- 判据②⑥ 必须 `KEEP_STRINGS`（剥掉字符串会把**真实现**剥成空）。

**用单一口径去断言两个相反的事实，必然有一半是假的。**

---

## 三、注入表（6 组）

| 组 | 注入 | 期望变红 | 守的失效模式 |
|---|---|---|---|
| C1 | 在 `StoreRepository`（真实载体）加 `@com.diaoyuanyun.dy.tenancy.rls.RlsScoped` 标注 | `the_rls_scoped_annotation_is_never_used_in_production_code` | **L2 被真正启用** |
| C2 | 把 `StoreRepository` 的 `SET LOCAL app.tenant_id` 改成 `..._probe` | `every_class_defining_in_tenant_also_sets_the_session_variable` | **空壳入口**（定义 inTenant 却不设上下文） |
| C3 | **新建** `ProbeLedger.java`（定义 inTenant + SET LOCAL）但不登记 | `the_set_of_in_tenant_carriers_is_exactly_the_registered_set` | **漏登记**（新增载体不表态） |
| C4 | 把 `RlsScoped.java` 改名移走（删掉注解定义） | `the_documented_mechanism_classes_still_exist_so_deleting_them_must_be_explicit` | **死机制被静默删除** |
| C5 | 在 `CustomerLedger`（另一真实载体）加一处 `applyTenantSession` 调用 | `the_aspect_method_is_never_called_outside_its_own_definition` | **切面被手工调用**（"上下文有切面兜住"被当事实） |
| C6 | 破坏剥离器：字符串态分支改成 `stripStrings && false` | `the_comment_and_string_stripper_has_real_discriminating_power` | **判据①假绿**（"扫不到"≠"不存在"） |

**语义要点**：C1/C3/C5 注入的是**语法合法、可编译**的变更 ——
门禁变红**不是**因为变更写错了，而是因为**状态被改变了却未同步登记/文档**。
C2/C6 注入的则是**真实缺陷本身**（空壳入口 / 判别力丧失）。

---

## 四、逐字执行结果

```
[预检 OK ] C1 锚点出现 1 次 ::     <T> T inTenant(String tenantId, Supplier<T> body) {
[预检 OK ] C2 锚点出现 1 次 :: jdbc.execute("SET LOCAL app.tenant_id = '" + tenantId + "'")
[预检 OK ] C5 锚点出现 1 次 ::     <T> T inTenant(String tenantId, Supplier<T> body) {
[预检 OK ] C6 锚点出现 1 次 :: } else if (c == '"' && stripStrings) {

[基线 OK] 注入前全绿，且 6 个待查方法名在基线中【零出现】（锚点有判别力）

[C1] 门禁 exit=1 · 期望红 `the_rls_scoped_annotation_is_never_used_in_production_code` 被抓=True
[C2] 门禁 exit=1 · 期望红 `every_class_defining_in_tenant_also_sets_the_session_variable` 被抓=True
[C5] 门禁 exit=1 · 期望红 `the_aspect_method_is_never_called_outside_its_own_definition` 被抓=True
[C6] 门禁 exit=1 · 期望红 `the_comment_and_string_stripper_has_real_discriminating_power` 被抓=True
[C3] 门禁 exit=1 · 期望红 `the_set_of_in_tenant_carriers_is_exactly_the_registered_set` 被抓=True
[C4] 门禁 exit=1 · 期望红 `the_documented_mechanism_classes_still_exist_so_deleting_them_must_be_explicit` 被抓=True

[复绿] exit=0 · 复绿=True
[还原核验] 4 个文件逐字节一致，探测文件无残留

结论: 8/8 PASS
```

---

## 五、🛑 本轮反向验证的最重要产出：它当场抓出了**门禁自身的两个真实缺陷**

**这不是"脚本跑通了"，而是"脚本证明了被测量的东西原本是坏的"。**

### 缺口 1（C1 抓出）：注解正则只认短名

初版：`Pattern.compile("@RlsScoped\\b")`。

**漏掉**全限定名形态 `@com.diaoyuanyun.dy.tenancy.rls.RlsScoped`。
而这个形态恰恰是**最容易被顺手写下的一次真实启用** —— 两个林德类都不需要 `import`，
直接写全限定名最省事。若门禁只认短名，**那次真实启用会被静默放过**（判据①假绿）。

**修正**：`Pattern.compile("@(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)*RlsScoped\\b")` ——
允许"点分隔的标识符链"，但 `@interface RlsScoped` 的**声明**形态仍不匹配。

### 缺口 2（C2 抓出）：用 `contains` 做"有 X"的断言 = 前缀匹配假绿

初版：`sources.get(rel).contains("SET LOCAL app.tenant_id")`。

`"SET LOCAL app.tenant_id_probe = '...'"` **包含**子串 `"SET LOCAL app.tenant_id"` ⇒
把一个**真正坏掉的注入点**判成了合格。

**修正**：改为词法完整正则 `SET\s+LOCAL\s+app\.tenant_id\s*=` ——
变量名后必须直接跟 `=`，故任何 `app.tenant_id<后缀>` 都不会被误认。

### 两条教训（已固化为断言，不只是修一次）

门禁判据⑤ 现在包含**四条判别力断言**，把这两个缺口永久钉住：

```java
assertTrue (ANNOTATION_USE.matcher("    @RlsScoped").find());                              // 短名
assertTrue (ANNOTATION_USE.matcher("    @com.diaoyuanyun.dy.tenancy.rls.RlsScoped").find()); // 全限定名
assertFalse(ANNOTATION_USE.matcher("public @interface RlsScoped {").find());               // 声明不是使用
assertTrue (SET_LOCAL_STMT.matcher("SET LOCAL app.tenant_id='x'").find());                 // 无空格形态
assertFalse(SET_LOCAL_STMT.matcher("SET LOCAL app.tenant_id_probe = 'x'").find());         // 变量名后缀
```

⚠️ **与 112 同源**：112 抓出"`contains("@Aspect")` 把注释也算命中 ⇒ 注释掉注解后门禁仍绿"。
114 抓出的是同一族缺陷的**两个新变体**。
⇒ **反向验证的全部价值在于：它不只验"门禁有没有牙齿"，还验"牙齿咬的是不是真东西"。**
一个"6/6 全绿"的门禁，可能在真实变更面前一言不发 —— 只有注入能证明它不是。

---

## 六、纪律清单（与 100~113 同口径）

- ✅ **锚点预检**（存在 + 唯一）：4 个锚点各恰 1 次（含**同一锚点用于两个不同文件的 C1/C5**，不冲突）
- ✅ **元层判别力自证**：6 个待查方法名在基线（全绿）输出中**零出现**
- ✅ **逐字节还原**：4 个文件 `write_bytes` 快照还原，还原后逐一比对字节一致
- ✅ **还原后复绿**：`exit=0`
- ✅ **无残留**：C3 新建的 `ProbeLedger.java` 在 `unlink` 后确认不存在
- ✅ **锚点一律单行**（技能 8.7）：C1/C2/C5/C6 全用单行锚点
- ✅ **注入保持语法合法可编译**：C1/C5 是合法注解与合法方法调用；C3 是完整可编译类；C6 是合法布尔表达式
- ✅ **`exit != 0` 不足以证明牙齿**：判定式 `caught = (exit != 0) and (expect in got)`
- ✅ **must_see 锚点一律 ASCII**（方法名 / 注释符号 / 文件路径）
- ✅ **Windows**：`mvn.cmd` 绝对路径 + list 参数 + `shell=False`

### 与上一轮的差异（口径纠正）

| 项 | 上一轮（未剥注释的粗测） | 本轮（剥离后实测） |
|---|---|---|
| 载体类数 | "19 个 `*Ledger`" | **18 个**（含 `StoreRepository` / `ScaleItemBankRepository` / `DbShredTombstoneStore` / `JdbcConfigSupport`，**不含** `RunbookLedger` 式误读） |
| `inTenant` 调用 | "142 处" | **139 处** |
| `@RlsScoped` 生产标注 | "0" | **0**（本轮以**双粒度**确认，并证明字符串态也无） |

---

## 七、与 112 / 113 的呼应

| 脚本 | 抓出的东西 | 性质 |
|---|---|---|
| 112 | `AuditFillAspect` 从未接线 + **门禁自身假绿**（注释被当命中） | 死代码 + 门禁自缺陷 |
| 113 | 组织主数据开通边界（"能跑但空"的系统） | 未登记的真实边界 |
| **114** | **RLS L2 文档机制 ≠ 实际机制** + **门禁自身两个新缺口** | **文档/实现分歧 + 门禁自缺陷** |

三者共用同一个结论：**"代码里写着"与"系统真的这样做"是两件事；
"测试全绿"与"测试有效"也是两件事。**区分它们的唯一手段是**主动注入错误，看门禁是否真的会红**。