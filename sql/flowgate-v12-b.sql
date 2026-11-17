-- V12-B仅全新隔离库显式安装；先安装V12-A。不修改旧账本。
SET time_zone = '+00:00';
CREATE TABLE IF NOT EXISTS fg_order (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,order_no VARCHAR(48) NOT NULL UNIQUE,
 principal_id BIGINT NOT NULL,developer_id BIGINT NOT NULL,application_id BIGINT NOT NULL,api_id BIGINT NOT NULL,sku_id BIGINT NOT NULL,
 period_yyyymm CHAR(6) NOT NULL,sku_revision BIGINT NOT NULL,sku_kind VARCHAR(16) NOT NULL DEFAULT 'PAID_BASE',price_cents BIGINT NOT NULL,units_snapshot BIGINT NOT NULL,currency CHAR(3) NOT NULL DEFAULT 'CNY',
 source VARCHAR(16) NOT NULL DEFAULT 'NORMAL',promo_intent_id BIGINT NULL UNIQUE,client_request_id VARCHAR(64) NOT NULL,request_fingerprint CHAR(64) NOT NULL,
 status VARCHAR(40) NOT NULL DEFAULT 'PENDING_PAYMENT',version BIGINT NOT NULL DEFAULT 0,pay_deadline_at TIMESTAMP(3) NOT NULL,
 effective_at TIMESTAMP(3) NOT NULL,expires_at TIMESTAMP(3) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE(principal_id,client_request_id),INDEX ix_order_owner(principal_id,id),INDEX ix_order_app_period(application_id,api_id,period_yyyymm),INDEX ix_order_timeout(status,pay_deadline_at,id),
 FOREIGN KEY(principal_id) REFERENCES fg_principal(id),FOREIGN KEY(application_id) REFERENCES fg_application(id),FOREIGN KEY(developer_id) REFERENCES fg_developer(id),FOREIGN KEY(api_id) REFERENCES fg_api(id),FOREIGN KEY(sku_id) REFERENCES fg_api_sku(id),
 CHECK(price_cents BETWEEN 1 AND 99999999 AND units_snapshot BETWEEN 1 AND 1000000),CHECK(currency='CNY'),CHECK(source='NORMAL' AND promo_intent_id IS NULL AND sku_kind='PAID_BASE'),
 CHECK(status IN ('PENDING_PAYMENT','PAID_PENDING_FULFILLMENT','FULFILLMENT_RETRY','FULFILLMENT_REVIEW','FULFILLED','CANCELLED','CLOSED_TIMEOUT','REFUNDED')),
 CHECK(effective_at<expires_at AND pay_deadline_at<expires_at),CHECK(version>=0),CHECK(period_yyyymm REGEXP '^[0-9]{4}(0[1-9]|1[0-2])$')
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_payment_record (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,order_id BIGINT NOT NULL UNIQUE,provider VARCHAR(8) NOT NULL DEFAULT 'SIM',provider_ref VARCHAR(80) NOT NULL UNIQUE,amount_cents BIGINT NOT NULL,currency CHAR(3) NOT NULL DEFAULT 'CNY',
 confirmed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),FOREIGN KEY(order_id) REFERENCES fg_order(id),CHECK(provider='SIM' AND currency='CNY' AND amount_cents BETWEEN 1 AND 99999999)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_business_outbox (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,event_id CHAR(36) NOT NULL UNIQUE,event_type VARCHAR(40) NOT NULL,aggregate_type VARCHAR(16) NOT NULL DEFAULT 'ORDER',aggregate_id BIGINT NOT NULL,aggregate_revision BIGINT NOT NULL,payload_json JSON NOT NULL,event_version INT NOT NULL DEFAULT 1,
 status VARCHAR(16) NOT NULL DEFAULT 'NEW',attempt_count INT NOT NULL DEFAULT 0,next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),lease_owner VARCHAR(100),lease_until TIMESTAMP(3) NULL,last_error_code VARCHAR(100),
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),FOREIGN KEY(aggregate_id) REFERENCES fg_order(id),UNIQUE(event_type,aggregate_type,aggregate_id,aggregate_revision),CHECK(event_type='PAYMENT_CONFIRMED' AND aggregate_type='ORDER' AND aggregate_revision>0),CHECK(event_version=1),CHECK(status IN ('NEW','IN_FLIGHT','SENT','FAILED','FAILED_PERMANENT')),CHECK(attempt_count>=0),INDEX ix_business_claim(status,next_attempt_at,lease_until,id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_business_event_consumed (
 event_id CHAR(36) PRIMARY KEY,event_type VARCHAR(40) NOT NULL DEFAULT 'PAYMENT_CONFIRMED',result VARCHAR(24) NOT NULL,aggregate_type VARCHAR(16) NOT NULL DEFAULT 'ORDER',aggregate_id BIGINT NOT NULL,payload_sha256 CHAR(64) NOT NULL,consumed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(event_id) REFERENCES fg_business_outbox(event_id),FOREIGN KEY(aggregate_id) REFERENCES fg_order(id),CHECK(aggregate_type='ORDER' AND event_type='PAYMENT_CONFIRMED'),CHECK(result IN ('APPLIED','ALREADY_APPLIED','IGNORED_TERMINAL'))
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_order_transition (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,order_id BIGINT NOT NULL,from_status VARCHAR(40),to_status VARCHAR(40) NOT NULL,actor_type VARCHAR(16) NOT NULL,actor_id BIGINT,reason_code VARCHAR(100) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),FOREIGN KEY(order_id) REFERENCES fg_order(id),INDEX ix_transition_order(order_id,id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_simulated_refund (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,order_id BIGINT NOT NULL UNIQUE,refund_ref VARCHAR(80) NOT NULL UNIQUE,amount_cents BIGINT NOT NULL,reason_code VARCHAR(100) NOT NULL,status VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED',confirmed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(order_id) REFERENCES fg_order(id),CHECK(amount_cents BETWEEN 1 AND 99999999 AND status='CONFIRMED')
) ENGINE=InnoDB;
