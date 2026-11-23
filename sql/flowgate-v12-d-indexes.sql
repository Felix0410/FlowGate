-- D隔离10万行EXPLAIN测得的建议索引；仅人工维护窗口评估后执行，运行器不自动改用户库。
-- 已有API使用id单列稳定游标仍适用；此索引为按时间+id有界导出/日范围诊断准备。
ALTER TABLE fg_quota_ledger ADD INDEX ix_d_ledger_app_period_created(application_id,period_yyyymm,created_at,id);
ALTER TABLE fg_api_quota_ledger ADD INDEX ix_d_ledger_app_period_created(application_id,period_yyyymm,created_at,id);
-- 重复安装前查information_schema.STATISTICS；仅确认由此维护引入时才按同名DROP INDEX回退。
