# dy-crypto 对抗性验证 harness

由**未参与 dy-crypto 实现**的验证者编写，用于对字段级加密模块做对抗性验证。
本目录**不参与** `dy-crypto` 模块的 test 编译，因此不会影响该模块
`mvn -pl dy-crypto -am test` 的 `Tests run` 计数（应为 **29**）。

<!-- 上一行的「应为 **N**」是本文件唯一的机器锚点，由
     dy-crypto/src/test/java/com/diaoyuanyun/dy/crypto/gate/DocTestCountAnchorGateTest 解析，
     与「dy-crypto/src/test 下 @Test 方法数」比对，**不等即构建失败**。
     演进：23（原始）→ 26（任务 #68 新增 SubjectKeyStoreConcurrencyGateTest 3 例）
           → 28（新增 DocTestCountAnchorGateTest 2 例 = 守护测试自身，锚点包含它）
           → 29（2026-09-23 修复 FieldCryptoGateTest 的概率性假红，加装反向验证 ⑥ 1 例）。
     改测试计数时必须同步改该锚点；**把锚点改得读不出来（如删掉「应为 **N**」字样）
     同样会让守护测试变红**，因为"解析不到"按失败处理，不按通过处理。 -->

## 为什么放在这里，而不是 `dy-crypto/src/test/`

放在模块测试树下会被 `mvn -pl dy-crypto test` 编译进该模块自己的 `test-classes`，
使 surefire 的 `Tests run` 计数被探针抬高 —— **实现者的用例与验证者的探针混进同一个计数**，
等于让验证结论自证。本目录做成独立 runner，验证证据与实现证据彻底分开。

> ⚠️ **此处的「抬高」只讲方向，不给具体数字**：早先版本写的是某个固定值差
> （"由 28 抬到 29"），但模块计数会随用例增删而变 —— 一旦锚点升到 29，
> 那句举例就成了一个**指向错误**的陈述（它描述的是一次并未发生的实验）。
> 结论不依赖数字，故不写数字。

## 一条可复现的命令

```bash
cd deliverables/product-strategy/skeleton && bash verification/crypto/run_crypto_adversarial.sh
```

退出码：

| 码 | 含义 | 门禁应判 |
|---|---|---|
| 0 | 全部探针 PASS | 绿 |
| 1 | 存在 FAIL（发现缺陷） | 红 |
| 2 | 存在 N/A（未能验证） | 红（"未能验证"不等于通过） |
| 3 | runner 自身环境问题（无 javac / 编译失败 / 找不到 classes） | 红 |

runner 的行为：只读 `dy-crypto/target/classes` 作 classpath，把探针编译到**仓库外**
的 `mktemp -d` 临时目录，用 `-Dcrypto.repo.root` 显式喂模块路径，跑完自行清理。
**不触碰任何 Maven 生命周期，不写 `dy-crypto/target`**，故可与并行构建共存。

可选：`CRYPTO_ADV_KEEP=1` 保留临时编译目录（排障用）。

## 门禁形态（建议）

**不用** opt-in profile（如 `-Pgo-live`）—— 那种形状会被当成"可选检查"跳过。
建议同一个 job 内两个 step，任一非零即 job 红：

```yaml
- name: dy-crypto 单元/门禁用例（实现者）
  run: mvn -pl dy-crypto -am test          # 日常 29 例绿

- name: dy-crypto 对抗性验证（验证者，独立命名）
  run: bash verification/crypto/run_crypto_adversarial.sh
```

要点：第二 step **独立可命名、可单独复现**；`exit 2`（N/A）同样判红。
前提是它**先恒绿**才挂 —— 只红不绿的检查会被人直接关掉。

## 文件

| 文件 | 作用 |
|---|---|
| `AdversarialVerificationProbe.java` | 探针本体（39 条），唯一权威版本 |
| `run_crypto_adversarial.sh` | 独立 runner |
| `evidence/adversarial-report.txt` | 最近一次运行的逐条报告（自动生成） |
| `evidence/red-green/` | **P0/P2/P1 反向验证红→绿留档（永久位置）**，见其中 `INDEX.md` |
| `migrate_probe.py` | 一次性迁移工具，**已退役**（源文件已删，运行会以 exit 2 拒绝） |

## ★ 证据位置纪律：不可再生留档不得落在 `target/` 下

2026-09-22 发现并处置：a8 的 P0/P2/P1 红→绿留档原写在
`dy-crypto/target/a8-crypto-gate/red-green/` —— **`target/` 被 `mvn clean` 整体删除**，
本仓库又无 `.gitignore`、非 git 仓库。已迁至 `evidence/red-green/`。

**必须区分两类（否则会过度声称）：**

- **自动生成物**（如 `reverse-verification-evidence.txt`、`deletion-dag-evidence.txt`）
  由测试代码在 `mvn test` 时写出，`clean` 后**重跑即可再生** ⇒ 留在 `target/` 属正常 Maven 惯例。
- **不可再生留档**（a8 的两态 `*.txt`）是他**仓库外手工一次性实验**的留档，
  **没有任何代码会重新生成它** ⇒ `clean` 一次即永久丢失。

**纪律：不可再生的结论性留档一律落在 `verification/<domain>/evidence/`。
判据很朴素 —— 该文件在 `mvn clean package` 之后是否还存在？若它是唯一副本，就必须迁出。**

## 探针覆盖（39 条）

- **A1–A4** 绕过 `FieldCipher` 直接解密／明文密钥出口
- **B1–B7** AAD 归属绑定（跨主体、跨字段、跨租户、kekId 篡改、版本位篡改、NUL 键碰撞）
- **C1–C5** 算法位伪造／降级／未知串／注册表注入
- **D1** "无算法字面量"的独立复核（含注释剥离与隐藏耦合检查）
- **E1–E3** crypto-shredding 边界（墓碑承重性、事前抄走 wrappedDek、重启丢失墓碑）
- **F1–F2** 信封解析健壮性（31 组畸形输入 + 2MB 超长段）
- **G1–G3** 并发读写（**门控确定性复现**，非概率起跑）
- **H1–H9** 备份门、版本取回、轮换、替换算法、并发创建/轮换、销毁与创建交错
- **I1–I4** 删除 DAG、handler 异常、pom 声明核对、KEK 轮换正面用例
- **J1** **门禁有效性对照**：把旧节奏（P0 修复前）复刻成内联替身，用与 G1/H5
  相同的门控手法打靶，确认旧节奏必被抓住 —— 保证"探针在坏实现上会红"这件事
  在修复后仍可随时重放，而不是只留在历史日志里

> ⚠️ **J1 的 PASS 语义与其它条相反**：其它条 PASS = 攻击失败 = 实现正确；
> J1 PASS = **探针有牙齿**（旧节奏被抓住）。门禁脚本若做汇总展示，需单独标注。

## 已知局限（如实陈述）

1. **概率型以外的确定性**：G1/G2/H4/H5/H9 已改用门控 provider（卡住
   `currentKek()`）做确定性复现；但"12 线程一起起跑"这类写法**已废弃** ——
   实测它会在窗口小时侥幸全绿（连跑 6 次有 2 次 G1 假绿），门禁里
   "可能假绿"比"恒红"更危险。
2. **H9 的 SLA 观察**：修复后 `destroySubjectKey` 会因键锁阻塞等待并发创建者
   完成；若创建者卡在 KMS 上很久，销毁请求也会被一并挂住。这是**正确性之外**
   的 SLA 权衡，非缺陷，故未作为判据。
3. **I3 是静态核对**：核对 pom 声明与 test 树实际内容是否一致；它不验证
   "真库门禁"的运行时行为（那属于 dy-app 集成测试的范围）。
4. **子串判据必须过长度门（2026-09-23 新增，来自一次真实假红）**：
   `FieldCryptoGateTest#dod_required_three_fields_encrypt_and_roundtrip` 原先断言
   `envelope.contains(plaintext)`。信封的 nonce 段与密文段都是**随机字节的 Base64**，
   于是对 2 字符明文（`"72"` / `"98"`）而言，这条断言是**概率事件**：实测（20 万次采样）
   单字段假阳性 ≈ 0.94%，三字段合计 ≈ **1.9%** —— 约每 50 次构建无端红一次。
   更糟的是它的**检查面本身就是错的**：密文段是 Base64，`base64("98")=="OTg="`，
   明文在其中根本不以原样出现 ⇒ **一个把明文原样当密文回填的实现照样能通过**。
   即：它几乎只会假红、几乎不会真红。
   已修复为 `assertEnvelopeHides`：两条**与随机性无关**的确定性判据
   （解码后密文 ≠ 明文；密文长度 ≥ 明文 + 16B tag）+ 两条长度门控的子串判据，
   并加装**反向验证 ⑥**（注入"密文=明文"的实现，断言该判据必须报警）以自证判别力未削弱。
   **纪律**：对随机/编码产物做子串检查前，先算它的假阳性率；短明文一律不用子串判据。
   完整记录见 `2026-09-23-16-02-37/接手机自证/crypto-flaky-假红修复记录-2026-09-23.md`。