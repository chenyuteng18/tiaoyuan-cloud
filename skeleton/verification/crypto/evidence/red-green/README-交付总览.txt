==============================================================================
A8 Phase 2（P0/P2/P1）交付留档 —— 修改点 · 反向验证红→绿 · Tests run
生成时间：2026-09-22
==============================================================================

【一、三处修改（文件 + 方法）】

P0  并发原子性 —— dy-crypto/src/main/java/com/diaoyuanyun/dy/crypto/key/InMemorySubjectKeyStore.java
  · bySubject 字段      语义由"裸 ArrayList"改为"**恒为不可变 List，一律整体替换**"；
                        并发纪律写进 javadoc：读—判—写必须在同一把键锁（bySubject.compute）内完成。
  · getOrCreateDek()    整个"锁内重做墓碑检查 → 沿用已有版本 / 过备份门 → 生成 v1 →
                        以 List.of(...) 整体放回"收进 bySubject.compute(key, ...)。
                        锁外不再有任何 map 读或写。
  · rotateDek()         next = oldMax + 1 的计算与 append 收进 bySubject.compute(...)；
                        构造新 ArrayList → 追加 → List.copyOf 整体放回（**不就地 add**）。
  · createVersion()     **移除**末尾的 bySubject.computeIfAbsent(...).add(stored)；
                        改为纯计算、只返回 StoredDek，不再触碰 bySubject。
                        理由（写进 javadoc）：ConcurrentHashMap 每-bin 锁不可重入，
                        在 compute 的 lambda 内再对本 bin 做 computeIfAbsent 会**死锁**。
  · destroySubjectKey() "记墓碑 + 删材料"收进同一把键锁：bySubject.compute(...) 内先
                        tombstones.record(...)，再 return null（由 CHM 移除该 key）。
                        顺序为【先记墓碑、再删材料】—— 该顺序在并发下才是唯一安全的
                        （否则两步之间取得锁的创建线程会读到"无墓碑、无材料"→ 复活已删主体）。
  · wrappedOf(s, int)   补注释"[读者不见写入]"：value 恒为不可变快照，故遍历不会抛 CME。

P2  作用域键 NUL 碰撞 —— dy-crypto/src/main/java/com/diaoyuanyun/dy/crypto/envelope/SubjectRef.java
  · SubjectRef 紧凑构造器 → require(String, String)：新增对 '\u0000' 的拒绝
                        （IllegalArgumentException，异常消息说明：NUL 是作用域键拼接分隔符，
                        分量自带 NUL 会让 SubjectRef("T","b\0c","d") 与 SubjectRef("T","b","c\0d")
                        拼出同一个键 → 两主体【共用同一把 DEK】→ 既击穿 per-subject 粒度，
                        又让删除权误伤另一个主体）。

P1  pom 依赖声明不实 —— dy-crypto/pom.xml
  · 删除 <dependency> org.postgresql:postgresql (test scope)。
  · 改写注释：把原注释"只有真库 crypto-shredding 门禁用它"明确标注为【不实（2026-09-22 更正）】，
    并说明 src/test 下零 JDBC 代码（唯二测试类是 CryptoTestHarness 与两个门禁 Test，均不 import java.sql）。
  · 登记 backlog：真库 crypto-shredding 门禁应落在 dy-app 的集成测试（那里已有 RLS 真库门禁支撑）。

【二、反向验证：红 → 绿 两态】

方法：把缺陷**故意注入回去**，看同一批断言是否变红；恢复后转绿。
两态用**同一份探针源码**编译（verification/crypto/AdversarialVerificationProbe.java），保证可比。

关键纪律：注入**只在仓库外的副本**上进行 —— 共享树的 src/main、pom.xml、
target/classes 全程零改动（红绿循环前后，三份修复态文件 md5 与备份完全一致）。
P1 的注入走"假 repo-root"（在 /tmp 下造一个重新声明 postgresql 的 dy-crypto/pom.xml），
因此不动机器上任何共享 pom。

  ┌────────┬──────────────────────────────────────┬──────────────┬──────────────┐
  │ 项     │ 缺陷                                 │ 红态（注入）  │ 绿态（修复）  │
  ├────────┼──────────────────────────────────────┼──────────────┼──────────────┤
  │ P0     │ 并发原子性（键锁拆掉，改回就地 add）  │ FAIL=5       │ FAIL=0       │
  │        │                                      │ G1/G2/H4/H5/H9│ 全绿        │
  ├────────┼──────────────────────────────────────┼──────────────┼──────────────┤
  │ P2     │ NUL 拒绝撤掉（if(false)）             │ B7 红        │ B7 PASS      │
  ├────────┼──────────────────────────────────────┼──────────────┼──────────────┤
  │ P1     │ 假根重新声明 postgresql+真库门禁      │ I3 红        │ I3 PASS      │
  └────────┴──────────────────────────────────────┴──────────────┴──────────────┘

红态统计：
  P0          PASS=34 FAIL=5  N/A=0  RUNNER_EXIT=1
  P2(+P0叠加) PASS=33 FAIL=6  N/A=0  RUNNER_EXIT=1   （专属红项 B7；其余 5 项为同副本保留 P0 降级所致）
  P1(假根)    PASS=37 FAIL=1  N/A=1  RUNNER_EXIT=1   （红项 I3；N/A=1 为端口/进程外类探针本机不可用）
绿态统计：
               PASS=39 FAIL=0  N/A=0  RUNNER_EXIT=0

红态关键数字（与 team-lead 独立实测一致）：
  G1  全部 12 线程都进入创建路径=true；不同 DEK 数=12；wrappedOf(c,1) 还原不出所取那把；liveKeyCount=1
  G2  回读成功=1/12；回读失败(认证类)=11；取不到密钥版本类=0
  H4  两线程都进入创建路径=true；不同 DEK 数=2；wrappedOf(c,1) 还原出的那把不在其中
  H5  版本号集合=[1]（大小 1，应为 8）；不同 DEK 数=8；一一对应=false；v1 还原出的不是上报的那把；CME=0
  H9  期间墓碑已记=true；放行后"创建成功"；销毁后仍有活跃密钥材料=true（liveKeyCount=1）
      （最新探针还加了"销毁调用耗时"——红态 0ms，修复后为键锁等待、非 0）
  B7  共用同一 DEK=true；销毁前 y 可读=true（55）→ 销毁后 y 可读=false（SubjectKeyDestroyedException）
      ⇒ 可读性不变=false ⇒ 删除权误伤成立
  I3  pom 声称 postgresql 为『真库门禁用』=true；含 JDBC/真库代码的文件=[]（空 ⇒ 声明的门禁不存在）

留档文件（dy-crypto/target/a8-crypto-gate/red-green/）：
  P0-concurrency-red.txt        4457 B
  P0-concurrency-green.txt      4004 B
  P2-nul-collision-red.txt      2483 B
  P1-pom-declaration-red.txt    1811 B

【三、Tests run 与可引用证据】

⚠️⚠️ 【时点注记（2026-09-22 由 w-crypto-selftest 补，**未改动下列任何原始数字**）】⚠️⚠️
  本节粘贴的是**快照时点那次 `mvn` 的真实输出**，改它等于伪造证据，故一律保持原样。
  但下面有两处**结论**已被后续工作证伪/推进，引用时不得按原文理解：
    ① 计数：其中 dy-crypto 的总数是 **23**，那是时点值。**当前为 28** ——
       新增 SubjectKeyStoreConcurrencyGateTest（3 例，任务 #68）
       与计数锚点守护测试 DocTestCountAnchorGateTest（2 例）。
    ② 第 3 条自承局限「模块自测 src/test 下零并发代码 … 并发断言只存在于对抗探针」
       —— **已关闭**：并发断言现已同时存在于模块自测（门控确定性，反向验证
       变体 I 20/20 红、变体 M 15/15 红、绿态 15/15 全绿）。
  仍**未**关闭的：该测试的 CME 判据在"就地 add"坏实现上恒为 0、无法变红（已在测试内降级为附加观察项）；
  以及真库 crypto-shredding 门禁落在 dy-app 集成测试（backlog）。

模块自测（mvn -pl dy-crypto -am -o test，离线、单模块 + -am）：
  [INFO] Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0  -- DeletionDagGateTest
  [INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0  -- FieldCryptoGateTest
  [INFO] Tests run: 23, Failures: 0, Errors: 0, Skipped: 0
  [INFO] BUILD SUCCESS          （EXIT=0）
  ⇒ 守住 team-lead 要求的 Tests run: 23。

同链捕获的可引用证据（本次新采，检验校验器修复后的正向形态）：
  dy-crypto/target/a8-crypto-gate/p0p2p1-fix.log + p0p2p1-fix.exit
  校验器判定：PAIRED_OK（同名 + 间隔 0.46s + 计数 23/0/0）
  汇总：可引用=1  不可配对=0  无测试证据=0  矛盾=0   （EXIT_CODE=0）

对抗探针整体（以真实仓库为 repo-root、修复态）：
  PASS=39 FAIL=0 N/A=0  RUNNER_EXIT=0
  其中此前 7 个红项 B7/G1/G2/H4/H5/H9/I3 全部转绿。

【四、我【没能做到】的部分（不美化）】

1. 我**没有**用 `bash verification/crypto/run_crypto_adversarial.sh` 跑这次红→绿循环。
   原因：该脚本的探针源码在本次过程中正被其作者（crypto-verifier）实时编辑
   （110503 B → 124728 B，新增 J1 与 LegacyRhythmSubjectKeyStore 内嵌旧节奏替身），
   在 19:48~19:49 曾**编译不过**（缺 import key.SubjectKeyStore；18 个错误），
   且脚本会把产物写进**共享的** verification/crypto/evidence/。
   为不干扰正在进行的编辑、也不污染共享证据目录，我改用"冻结的探针类"+
   仓库外编译主源码来做两态。
   ⇒ 后果：我留档的两态**不是** run_crypto_adversarial.sh 的输出形态（无 RUNNER 抬头行）。
   我已请 team-lead 用该脚本复跑取绿态（他本来就计划这么做）。
   红态留档里的 RUNNER_EXIT=1 是我按探针 main() 的退出码语义（nFail>0→1）标注的，
   与脚本包装后的退出码同值。

2. P1 的"红态"不是把真实 pom 改坏再改回，而是用**假 repo-root**（/tmp 下另造一份
   重新声明 postgresql 的 pom + 零 JDBC 的 test 树）。理由是共享 pom 属共用文件，
   改坏再改回会与并行构建者抢文件。这对 I3 的判据是等价的（I3 只读 pom 文本 + 扫描
   src/test），但严格说：**我没有实测过"真实 pom 被改回原样"的那一态**。

3. 模块自测 `src/test` 下**零并发代码**（无 Thread/Executor/CountDownLatch，也无 rotateDek 调用）。
   因此 P0 的"版本号重复 / 按版本号取回失败"断言**只存在于对抗探针**，
   `Tests run: 23` 全绿**不能**等价于"并发正确"。把并发断言补进模块自测属 backlog，
   我不在本轮做（会动 src/test 且超出 team-lead 划定的三处修改范围）。

4. 我**没有**跑全量 `mvn clean package`（team-lead 明令禁止），也**没有**碰
   verification/crypto/ 与 dy-app/。

5. 观察 Y 之外，我在探针里看到的另一处**不属于我范围**的变化：探针新增了 J1
   （门禁有效性对照），其在本轮两态中**都 PASS**——因为它是拿内嵌"旧节奏替身"跑的，
   与主源码是否修复无关。这是正确的设计（证明断言有牙齿），我只是记录：
   它的 PASS **不能**用来推断实现正确。

【五、共享树零改动证明】
  md5(修复态) == md5(备份)：
    47aed80e756496630dfcd3716d0a48c2  dy-crypto/src/main/.../key/InMemorySubjectKeyStore.java
    9bd80a1f0bc80f41169ebaa10ce2b3dd  dy-crypto/src/main/.../envelope/SubjectRef.java
    e5133cee4d5e74ae29ac368aee4fb2df  dy-crypto/pom.xml
  grep "反向验证·降级" dy-crypto/src/main/ → 无命中（降级标记只存在于 /tmp 副本）
==============================================================================