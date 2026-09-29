# a8-crypto-gate 证据快照

> 由 team-lead 于 2026-09-22 从 `dy-crypto/target/a8-crypto-gate/` 复制的**快照**。

## 性质：这些是自动生成物，不是唯一副本

| 文件 | 生成方式 | `mvn clean` 后 |
|---|---|---|
| `reverse-verification-evidence.txt` | `FieldCryptoGateTest` 调 `CryptoTestHarness.dumpEvidence(...)` 写出 | 重跑 `mvn -pl dy-crypto test` 即再生 |
| `deletion-dag-evidence.txt` | `DeletionDagGateTest` 同上 | 同上 |
| `p0p2p1-fix.log` / `p0p2p1-fix.exit` | `mvn -pl dy-crypto -am test -o` 的输出重定向 | 重跑即可再生 |

**故本目录仅为"某一时点的快照"，保留它是为了便于复核，而非因为它不可再生。**
真正不可再生、必须迁出 `target/` 的留档在 `../red-green/`（见其 `INDEX.md` §0）。

## 快照时点的校验结论（team-lead 复核）

> ⚠️ **时点注记（2026-09-22 由 w-crypto-selftest 补，未改动下列任何原始数字）**：
> 下面是**快照时点**的校验结论，那时 dy-crypto 的计数是 **23/0/0**。
> **当前计数为 28/0/0** —— 任务 #68 新增 `SubjectKeyStoreConcurrencyGateTest`（3 例），
> 以及计数锚点守护测试 `DocTestCountAnchorGateTest`（2 例）。
> 本次迁移/校验的结论（`PAIRED_OK`、"迁移未破坏可配对性"）**不依赖具体计数**，故仍然成立；
> 但**不得**把下面的 `23/0/0` 当作"当前模块计数"引用（当前值见 §6.1 机器锚点）。

```
python dy-crypto/tools/verify-exit-evidence.py --dir verification/crypto/evidence/a8-gate
→ p0p2p1-fix.exit ↔ p0p2p1-fix.log：PAIRED_OK（间隔 0.01s）
  日志含 BUILD SUCCESS 且计数 23/0/0
  汇总：可引用=1  不可配对=0  无测试证据=0  矛盾=0   （EXIT_CODE=0）
```

迁移后在新路径下复跑校验器仍判 `PAIRED_OK` ⇒ 迁移未破坏证据的可配对性。