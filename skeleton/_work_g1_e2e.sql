\pset pager off
BEGIN;
INSERT INTO tenant (id, name, status) VALUES ('23000000-0000-0000-0000-00000000000a','G1 探针租户','active');
SELECT set_config('app.tenant_id','23000000-0000-0000-0000-00000000000a',true);
INSERT INTO customer (tenant_id, id, name, status) VALUES ('23000000-0000-0000-0000-00000000000a','23000000-0000-0000-0000-0000000000c1','G1 客户','active');
INSERT INTO plan (tenant_id, plan_id, customer_id, version, treatment_json, lifestyle_json, intent_params, status)
VALUES ('23000000-0000-0000-0000-00000000000a','23000000-0000-0000-0000-0000000000e1','23000000-0000-0000-0000-0000000000c1',1,
        '{"items":[]}'::jsonb,'{"items":[]}'::jsonb,'{}'::jsonb,'approved');
SELECT register_agreement(
  '23000000-0000-0000-0000-00000000000a'::uuid,
  '23000000-0000-0000-0000-0000000000d1'::uuid,
  '23000000-0000-0000-0000-0000000000c1'::uuid,
  '23000000-0000-0000-0000-0000000000e1'::uuid, 1,
  '{"refund":"协商一致可终止","termination":"提前7日告知"}'::jsonb,
  '2026-09-20 10:00:00+08'::timestamptz,
  '{"customer":"赵一","meridian_therapist":"钱二","therapist":"孙三","store_owner":"李四"}'::jsonb,
  '探针渲染稿','AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
  NULL, NULL, NULL, 'g1-probe') AS 首次登记返回;
WITH plan_out AS (
  SELECT p.tenant_id,p.customer_id,p.plan_id,p.version FROM plan p WHERE p.status IS DISTINCT FROM 'draft'),
signed AS (
  SELECT DISTINCT po.tenant_id,po.customer_id,po.plan_id,po.version FROM plan_out po
  JOIN agreement a ON a.tenant_id=po.tenant_id AND a.plan_id=po.plan_id AND a.plan_version=po.version)
SELECT count(po.*) AS 分母, count(s.*) AS 分子,
       round(100.0*count(s.*)/nullif(count(po.*),0),2) AS 合规率
  FROM plan_out po LEFT JOIN signed s
    ON s.tenant_id=po.tenant_id AND s.customer_id=po.customer_id AND s.plan_id=po.plan_id AND s.version=po.version;
SELECT latest_agreement_of('23000000-0000-0000-0000-00000000000a'::uuid,
                           '23000000-0000-0000-0000-0000000000c1'::uuid) AS 最新协议;
ROLLBACK;
