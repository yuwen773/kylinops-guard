-- Fix-01 RCA 推理链结构化
-- 在 kylin_audit_log 和 kylin_report 表分别增加 root_cause_chain_json 字段
-- 使用 ${lob_type} 占位符，与 V1/V2 保持一致（H2=CLOB, PG=TEXT）

ALTER TABLE kylin_audit_log ADD COLUMN root_cause_chain_json ${lob_type};
ALTER TABLE kylin_report ADD COLUMN root_cause_chain_json ${lob_type};