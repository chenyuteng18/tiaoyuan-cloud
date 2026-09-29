\set ON_ERROR_STOP on
-- ============================================================================
-- 探针：V16 自证判据 (b) / (d) 用【字符串 LIKE】判定，是否脆弱？
--
-- 判据现状：
--   (b)  pg_get_constraintdef(con.oid) LIKE 'FOREIGN KEY (tenant_id, %'
--   (d)  pg_get_constraintdef(c2.oid) LIKE '%(tenant_id, %'
--
-- 疑问：若唯一的列顺序被交换（UNIQUE (id, tenant_id) 而非 (tenant_id, id)），
--       外键引用 (tenant_id, id) 还能否成立？若 PG 接受，则：
--         · 数据库其实【受保护】（外键有效）
--         · 但判据 (d) 会因为字符串不匹配而误报 —— 这是【假阳性】
--       这就是"判据脆弱"的具体形态。
-- ============================================================================
BEGIN;

CREATE TEMP TABLE p_tgt (
    tenant_id uuid NOT NULL,
    id        uuid NOT NULL,
    CONSTRAINT p_tgt_pk PRIMARY KEY (id)
) ON COMMIT DROP;

-- 载体唯一约束，列顺序【交换】：(id, tenant_id)
ALTER TABLE p_tgt ADD CONSTRAINT p_tgt_uq UNIQUE (id, tenant_id);

CREATE TEMP TABLE p_src (
    tenant_id uuid NOT NULL,
    tgt_id    uuid NOT NULL,
    CONSTRAINT p_src_fk FOREIGN KEY (tenant_id, tgt_id) REFERENCES p_tgt (tenant_id, id)
) ON COMMIT DROP;

SELECT
    'A. 交换列顺序后 FK 仍能建立' AS 检查项,
    con.conname,
    pg_get_constraintdef(con.oid) AS 约束定义,
    (pg_get_constraintdef(con.oid) LIKE 'FOREIGN KEY (tenant_id, %') AS 判据b_是否认得它,
    EXISTS (
        SELECT 1 FROM pg_constraint c2
        WHERE c2.conrelid = 'p_tgt'::regclass
          AND c2.contype IN ('u','p')
          AND pg_get_constraintdef(c2.oid) LIKE '%(tenant_id, %'
    ) AS 判据d_是否认得载体
FROM pg_constraint con
WHERE con.conname = 'p_src_fk';

-- 行为验证：这个"列序交换"的载体 + 复合外键，到底挡不挡跨租户？
INSERT INTO p_tgt (tenant_id, id) VALUES ('00000000-0000-0000-0000-00000000000a','00000000-0000-0000-0000-0000000000a1');
DO $$
DECLARE v_rejected boolean := false;
BEGIN
    BEGIN
        INSERT INTO p_src (tenant_id, tgt_id)
        VALUES ('00000000-0000-0000-0000-00000000000b','00000000-0000-0000-0000-0000000000a1');
    EXCEPTION WHEN foreign_key_violation THEN v_rejected := true;
    END;
    RAISE NOTICE 'B. 列序交换形态下，跨租户引用被拒 = %', v_rejected;
END $$;

ROLLBACK;