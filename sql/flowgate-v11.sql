-- V11-A独立增量：仅创建五张管理表；重复执行不修改V1表、数据或历史快照。
-- 时间列按UTC Instant解释；连接和迁移会话使用UTC，区间语义为[start,end)。
SET time_zone = '+00:00';
CREATE TABLE IF NOT EXISTS fg_developer (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 code VARCHAR(100) NOT NULL UNIQUE, name VARCHAR(200) NOT NULL,
 status VARCHAR(16) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 CONSTRAINT ck_developer_status CHECK (status IN ('ACTIVE','SUSPENDED')),
 CONSTRAINT ck_developer_code CHECK (REGEXP_LIKE(code,'^[A-Za-z0-9_-]{1,100}$','c'))
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_application (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, developer_id BIGINT NOT NULL,
 code VARCHAR(100) NOT NULL UNIQUE, name VARCHAR(200) NOT NULL, status VARCHAR(16) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_application_developer FOREIGN KEY (developer_id) REFERENCES fg_developer(id),
 CONSTRAINT ck_application_status CHECK (status IN ('ACTIVE','SUSPENDED')),
 CONSTRAINT ck_application_code CHECK (REGEXP_LIKE(code,'^[A-Za-z0-9_-]{1,100}$','c')),
 INDEX ix_application_developer_id (developer_id,id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_api_plan (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, code VARCHAR(100) NOT NULL UNIQUE, name VARCHAR(200) NOT NULL,
 monthly_units BIGINT NOT NULL, plan_version INT NOT NULL DEFAULT 1, status VARCHAR(16) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 CONSTRAINT ck_plan_units CHECK (monthly_units BETWEEN 1 AND 1000000),
 CONSTRAINT ck_plan_version CHECK (plan_version > 0),
 CONSTRAINT ck_plan_status CHECK (status IN ('ACTIVE','SUSPENDED')),
 CONSTRAINT ck_plan_code CHECK (REGEXP_LIKE(code,'^[A-Za-z0-9_-]{1,100}$','c'))
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_subscription (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, application_id BIGINT NOT NULL, plan_id BIGINT NOT NULL,
 effective_at TIMESTAMP(3) NOT NULL, expires_at TIMESTAMP(3) NOT NULL,
 status VARCHAR(16) NOT NULL, plan_version_snapshot INT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_subscription_application FOREIGN KEY (application_id) REFERENCES fg_application(id),
 CONSTRAINT fk_subscription_plan FOREIGN KEY (plan_id) REFERENCES fg_api_plan(id),
 CONSTRAINT ck_subscription_period CHECK (effective_at < expires_at),
 CONSTRAINT ck_subscription_status CHECK (status IN ('ACTIVE','SUSPENDED','EXPIRED')),
 CONSTRAINT ck_subscription_plan_version CHECK (plan_version_snapshot > 0),
 INDEX ix_subscription_app_time (application_id,effective_at,expires_at,id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_app_principal (
 principal_id BIGINT PRIMARY KEY, application_id BIGINT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_app_principal FOREIGN KEY (principal_id) REFERENCES fg_principal(id),
 CONSTRAINT fk_app_principal_application FOREIGN KEY (application_id) REFERENCES fg_application(id),
 INDEX ix_app_principal_application (application_id,principal_id)
) ENGINE=InnoDB;
