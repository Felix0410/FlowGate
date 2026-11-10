-- V12-A增量：仅新库显式安装，不改V11历史。所有期间按UTC半开区间。
SET time_zone = '+00:00';
CREATE TABLE IF NOT EXISTS fg_portal_user (
 principal_id BIGINT PRIMARY KEY, login_name_normalized VARCHAR(100) NOT NULL UNIQUE,
 display_name VARCHAR(100) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 FOREIGN KEY(principal_id) REFERENCES fg_principal(id),CHECK(status IN ('ACTIVE','SUSPENDED'))
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_developer_member (
 developer_id BIGINT NOT NULL, principal_id BIGINT NOT NULL, role VARCHAR(16) NOT NULL DEFAULT 'OWNER',status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 PRIMARY KEY(developer_id,principal_id),INDEX ix_v12_member_principal(principal_id,developer_id),
 FOREIGN KEY(developer_id) REFERENCES fg_developer(id), FOREIGN KEY(principal_id) REFERENCES fg_principal(id),CHECK(role='OWNER'),CHECK(status IN ('ACTIVE','SUSPENDED'))
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_api_category (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,code VARCHAR(100) NOT NULL UNIQUE,name VARCHAR(100) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',sort_order INT NOT NULL DEFAULT 0,
 CHECK(status IN ('ACTIVE','SUSPENDED'))
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_api_listing (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,api_id BIGINT NOT NULL UNIQUE,category_id BIGINT NOT NULL,title VARCHAR(200) NOT NULL,summary VARCHAR(1000) NOT NULL,doc_text TEXT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',revision BIGINT NOT NULL DEFAULT 1,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 FOREIGN KEY(api_id) REFERENCES fg_api(id),FOREIGN KEY(category_id) REFERENCES fg_api_category(id),CHECK(status IN ('DRAFT','LISTED','OFFLINE')),CHECK(revision>0),INDEX ix_v12_listing_status(status,id),INDEX ix_v12_listing_category(category_id,status,id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_api_sku (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,listing_id BIGINT NOT NULL,code VARCHAR(100) NOT NULL UNIQUE,kind VARCHAR(16) NOT NULL,quota_units BIGINT NOT NULL,price_cents BIGINT NOT NULL DEFAULT 0,
 period_policy VARCHAR(16) NOT NULL DEFAULT 'CURRENT_MONTH',status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',revision BIGINT NOT NULL DEFAULT 1,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 FOREIGN KEY(listing_id) REFERENCES fg_api_listing(id),CHECK(kind IN ('FREE_BASE','PAID_BASE')),CHECK(quota_units BETWEEN 1 AND 1000000),CHECK(price_cents>=0 AND (kind<>'FREE_BASE' OR price_cents=0)),CHECK(period_policy IN ('CURRENT_MONTH','NEXT_MONTH')),CHECK(status IN ('ACTIVE','SUSPENDED')),CHECK(revision>0),INDEX ix_v12_sku_listing(listing_id,id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_portal_free_claim (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,principal_id BIGINT NOT NULL,api_id BIGINT NOT NULL,application_id BIGINT NOT NULL,sku_id BIGINT NOT NULL,period_yyyymm CHAR(6) NOT NULL,
 UNIQUE(principal_id,api_id),FOREIGN KEY(principal_id) REFERENCES fg_principal(id),FOREIGN KEY(api_id) REFERENCES fg_api(id),FOREIGN KEY(application_id) REFERENCES fg_application(id),FOREIGN KEY(sku_id) REFERENCES fg_api_sku(id),CHECK(period_yyyymm REGEXP '^[0-9]{4}(0[1-9]|1[0-2])$')
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_api_entitlement_slot (
 application_id BIGINT NOT NULL,api_id BIGINT NOT NULL,period_yyyymm CHAR(6) NOT NULL,state VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',holder_type VARCHAR(16),holder_id BIGINT,version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(application_id,api_id,period_yyyymm),FOREIGN KEY(application_id) REFERENCES fg_application(id),FOREIGN KEY(api_id) REFERENCES fg_api(id),
 CHECK((state='AVAILABLE' AND holder_type IS NULL AND holder_id IS NULL) OR (state IN ('HELD','GRANTED') AND holder_type IS NOT NULL AND holder_type IN ('FREE_GRANT','ORDER','PROMO') AND holder_id IS NOT NULL AND holder_id>0)),CHECK(version>=0),CHECK(period_yyyymm REGEXP '^[0-9]{4}(0[1-9]|1[0-2])$')
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_entitlement_grant (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,source_type VARCHAR(16) NOT NULL,source_id BIGINT NOT NULL,application_id BIGINT NOT NULL,api_id BIGINT NOT NULL,period_yyyymm CHAR(6) NOT NULL,kind VARCHAR(16) NOT NULL DEFAULT 'BASE',units BIGINT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),UNIQUE(source_type,source_id),FOREIGN KEY(application_id) REFERENCES fg_application(id),FOREIGN KEY(api_id) REFERENCES fg_api(id),CHECK(source_type IN ('FREE_TRIAL','PAID_ORDER')),CHECK(kind='BASE'),CHECK(units BETWEEN 1 AND 1000000),INDEX ix_v12_grant_app(application_id,api_id,period_yyyymm,id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_api_subscription (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,application_id BIGINT NOT NULL,api_id BIGINT NOT NULL,period_yyyymm CHAR(6) NOT NULL,effective_at TIMESTAMP(3) NOT NULL,expires_at TIMESTAMP(3) NOT NULL,grant_id BIGINT NOT NULL UNIQUE,units_snapshot BIGINT NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 UNIQUE(application_id,api_id,period_yyyymm),FOREIGN KEY(application_id) REFERENCES fg_application(id),FOREIGN KEY(api_id) REFERENCES fg_api(id),FOREIGN KEY(grant_id) REFERENCES fg_entitlement_grant(id),CHECK(effective_at<expires_at),CHECK(units_snapshot BETWEEN 1 AND 1000000),CHECK(status IN ('ACTIVE','SUSPENDED','EXPIRED'))
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_api_quota_balance (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,application_id BIGINT NOT NULL,api_id BIGINT NOT NULL,period_yyyymm CHAR(6) NOT NULL,subscription_id BIGINT NOT NULL UNIQUE,total_units BIGINT NOT NULL,remaining_units BIGINT NOT NULL,version BIGINT NOT NULL DEFAULT 0,
 UNIQUE(application_id,api_id,period_yyyymm),FOREIGN KEY(application_id) REFERENCES fg_application(id),FOREIGN KEY(api_id) REFERENCES fg_api(id),FOREIGN KEY(subscription_id) REFERENCES fg_api_subscription(id),CHECK(total_units BETWEEN 1 AND 1000000 AND remaining_units>=0 AND remaining_units<=total_units),CHECK(version>=0)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_api_quota_ledger (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,charge_id CHAR(36) NOT NULL UNIQUE,request_fingerprint CHAR(64) NOT NULL,application_id BIGINT NOT NULL,principal_id BIGINT NOT NULL,credential_generation BIGINT NOT NULL,api_id BIGINT NOT NULL,route_id VARCHAR(200) NOT NULL,applied_version BIGINT NOT NULL,policy_id BIGINT NOT NULL,period_yyyymm CHAR(6) NOT NULL,units INT NOT NULL,remaining_units_after BIGINT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),FOREIGN KEY(application_id) REFERENCES fg_application(id),FOREIGN KEY(principal_id) REFERENCES fg_principal(id),FOREIGN KEY(api_id) REFERENCES fg_api(id),FOREIGN KEY(applied_version) REFERENCES fg_config_release(version),CHECK(units BETWEEN 1 AND 100),CHECK(remaining_units_after>=0),CHECK(credential_generation>0),INDEX ix_v12_ledger_app(application_id,api_id,period_yyyymm,id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_api_usage_outbox (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,event_id CHAR(36) NOT NULL UNIQUE,charge_id CHAR(36) NOT NULL UNIQUE,event_type VARCHAR(50) NOT NULL DEFAULT 'USAGE_GRANTED_V12',payload_json JSON NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'NEW',attempt_count INT NOT NULL DEFAULT 0,next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),lease_owner VARCHAR(100),lease_until TIMESTAMP(3) NULL,last_error_code VARCHAR(100),
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),FOREIGN KEY(charge_id) REFERENCES fg_api_quota_ledger(charge_id),CHECK(event_type='USAGE_GRANTED_V12'),CHECK(status IN ('NEW','IN_FLIGHT','SENT','FAILED')),CHECK(attempt_count>=0),INDEX ix_v12_outbox_claim(status,next_attempt_at,lease_until,id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_api_consumed_event (
 event_id CHAR(36) PRIMARY KEY,charge_id CHAR(36) NOT NULL UNIQUE,payload_sha256 CHAR(64) NOT NULL,consumed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),FOREIGN KEY(charge_id) REFERENCES fg_api_quota_ledger(charge_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_api_usage_daily (
 day_utc DATE NOT NULL,application_id BIGINT NOT NULL,api_id BIGINT NOT NULL,authorized_units BIGINT NOT NULL DEFAULT 0,grant_count BIGINT NOT NULL DEFAULT 0,updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),PRIMARY KEY(day_utc,application_id,api_id),INDEX ix_v12_daily_app(application_id,day_utc,api_id),FOREIGN KEY(application_id) REFERENCES fg_application(id),FOREIGN KEY(api_id) REFERENCES fg_api(id),CHECK(authorized_units>=0 AND grant_count>=0)
) ENGINE=InnoDB;
