\set ON_ERROR_STOP on
BEGIN;
-- 🛑 只改 checksum 一列。前提已由 117 证明：dev 库模式 == 当前迁移链的模式
--    （结构清单 + 约束指纹 + schema_migration 登记 + pg_dump 归一化逐字节，5/5 通过）。
--    这等价于 Flyway 官方 `repair` 的语义，但由我们自己做，因为：
--    ① 官方 repair 会把【所有】失败记录一并清理，而此处只有一个 checksum 失配，
--       范围越小越好；② 本仓用的是嵌入式 Flyway（Spring 启动时执行），
--       没有配置 maven flyway 插件，不存在现成的 repair 命令可调。
UPDATE flyway_schema_history SET checksum = 626314169 WHERE version = '16';
SELECT 'after_update' AS k, version, checksum FROM flyway_schema_history WHERE version = '16';
COMMIT;
