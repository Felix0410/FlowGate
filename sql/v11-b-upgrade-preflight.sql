-- V11-A → V11-B 启用计量前只读预检（2026-11-08）。
-- 0. 只改变本次MySQL客户端连接时区；不执行任何UPDATE/DELETE/ALTER/INSERT。
-- 1. 仅在有备份、明确业务库名和具有只读权限的连接上手动执行。
-- 2. 四项risk_count必须全部为0才能考虑开启 FLOWGATE_QUOTA_ACTIVATION_ENABLED。
-- 3. 如发现历史风险，停用计量并人工审核应用与历史Ledger；本脚本不自动修复或清零。
SET time_zone = '+00:00';

-- 同一应用在同一个UTC月份开始了两条订阅（A版本曾允许非重叠历史区间）。
SELECT 'SAME_MONTH_MULTIPLE_SUBSCRIPTIONS' AS risk, COUNT(*) AS risk_count
FROM fg_subscription s1
JOIN fg_subscription s2
  ON s1.application_id = s2.application_id
 AND s1.id < s2.id
 AND DATE_FORMAT(s1.effective_at,'%Y%m') = DATE_FORMAT(s2.effective_at,'%Y%m');

-- B0首版每条订阅只覆盖一个UTC自然月；历史跨月记录必须由负责人诊断。
SELECT 'CROSS_MONTH_SUBSCRIPTION' AS risk, COUNT(*) AS risk_count
FROM fg_subscription s
WHERE DATE_FORMAT(s.effective_at,'%Y%m') <> DATE_FORMAT(DATE_SUB(s.expires_at,INTERVAL 1 MICROSECOND),'%Y%m');

-- 已冻结的Balance字段与其外键指向的订阅/不可变套餐定义不相符。
SELECT 'FROZEN_BALANCE_DEFINITION_MISMATCH' AS risk, COUNT(*) AS risk_count
FROM fg_quota_balance b
JOIN fg_subscription s ON s.id=b.subscription_id
JOIN fg_api_plan p ON p.id=b.plan_id
WHERE s.application_id<>b.application_id
   OR s.plan_id<>b.plan_id
   OR b.plan_version<>s.plan_version_snapshot
   OR b.total_units<>p.monthly_units;

-- 已有Balance的计费月份内，又存在另一条同App订阅，可能导致继续扣错冻结套餐。
SELECT 'FROZEN_MONTH_WITH_ANOTHER_SUBSCRIPTION' AS risk, COUNT(*) AS risk_count
FROM fg_quota_balance b
JOIN fg_subscription s ON s.application_id=b.application_id
 AND DATE_FORMAT(s.effective_at,'%Y%m')=b.period_yyyymm
 AND s.id<>b.subscription_id;

-- 注：以上是默认只读风险计数，不输出用户名称、Token或明文业务调用数据。
-- 若历史订阅跨月，第二个查询已经报警；不要将其当作无风险而忽略。
