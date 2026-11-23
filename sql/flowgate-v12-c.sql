-- C增量五表。订单CHECK/FK扩展由v12-c-migrate.py精确发现后执行；不改旧账本。
SET time_zone = '+00:00';
CREATE TABLE IF NOT EXISTS fg_promotion (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,name VARCHAR(200) NOT NULL,sku_id BIGINT NOT NULL,api_id BIGINT NOT NULL,sku_revision BIGINT NOT NULL,
 quota_units BIGINT NOT NULL,sku_price_cents BIGINT NOT NULL,promo_price_cents BIGINT NOT NULL,currency CHAR(3) NOT NULL DEFAULT 'CNY',
 period_yyyymm CHAR(6) NOT NULL,period_policy VARCHAR(16) NOT NULL,begins_at TIMESTAMP(3) NOT NULL,ends_at TIMESTAMP(3) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',epoch BIGINT NOT NULL DEFAULT 1,version BIGINT NOT NULL DEFAULT 1,
 order_creation_ttl_seconds INT NOT NULL DEFAULT 60,pay_ttl_seconds INT NOT NULL DEFAULT 900,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 FOREIGN KEY(sku_id) REFERENCES fg_api_sku(id),FOREIGN KEY(api_id) REFERENCES fg_api(id),
 CHECK(quota_units BETWEEN 1 AND 1000000),CHECK(promo_price_cents BETWEEN 1 AND sku_price_cents),CHECK(currency='CNY'),
 CHECK(period_policy IN ('CURRENT_MONTH','NEXT_MONTH')),CHECK(period_yyyymm REGEXP '^[0-9]{4}(0[1-9]|1[0-2])$'),
 CHECK(begins_at<ends_at),CHECK(status IN ('DRAFT','READY','RUNNING','PAUSED','REBUILDING','ENDED')),CHECK(epoch>0 AND version>0),
 CHECK(order_creation_ttl_seconds BETWEEN 1 AND 300),CHECK(pay_ttl_seconds BETWEEN 5 AND 900),INDEX ix_promotion_window(status,begins_at,ends_at,id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_promo_stock (
 promotion_id BIGINT PRIMARY KEY,total_stock BIGINT NOT NULL,reserved BIGINT NOT NULL DEFAULT 0,sold BIGINT NOT NULL DEFAULT 0,version BIGINT NOT NULL DEFAULT 1,
 FOREIGN KEY(promotion_id) REFERENCES fg_promotion(id),CHECK(total_stock BETWEEN 1 AND 100000),CHECK(reserved>=0 AND sold>=0 AND reserved+sold<=total_stock)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_promo_purchase_intent (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,promotion_id BIGINT NOT NULL,principal_id BIGINT NOT NULL,application_id BIGINT NOT NULL,api_id BIGINT NOT NULL,sku_id BIGINT NOT NULL,
 period_yyyymm CHAR(6) NOT NULL,client_request_id VARCHAR(64) NOT NULL,request_fingerprint CHAR(64) NOT NULL,activity_epoch BIGINT NOT NULL,
 sku_revision BIGINT NOT NULL,quota_units BIGINT NOT NULL,price_cents BIGINT NOT NULL,currency CHAR(3) NOT NULL DEFAULT 'CNY',
 effective_at TIMESTAMP(3) NOT NULL,expires_at TIMESTAMP(3) NOT NULL,creation_deadline_at TIMESTAMP(3) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'ACCEPTED',order_id BIGINT NULL UNIQUE,version BIGINT NOT NULL DEFAULT 1,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE(promotion_id,principal_id),UNIQUE(principal_id,client_request_id),
 FOREIGN KEY(promotion_id) REFERENCES fg_promotion(id),FOREIGN KEY(principal_id) REFERENCES fg_principal(id),FOREIGN KEY(application_id) REFERENCES fg_application(id),FOREIGN KEY(api_id) REFERENCES fg_api(id),FOREIGN KEY(sku_id) REFERENCES fg_api_sku(id),FOREIGN KEY(order_id) REFERENCES fg_order(id),
 CHECK(price_cents BETWEEN 1 AND 99999999),CHECK(quota_units BETWEEN 1 AND 1000000),CHECK(currency='CNY'),CHECK(activity_epoch>0 AND version>0),
 CHECK(effective_at<expires_at AND creation_deadline_at<expires_at),CHECK(status IN ('ACCEPTED','ORDER_READY','PAID','CLOSED','EXPIRED','REFUNDED')),INDEX ix_intent_expiry(status,creation_deadline_at,id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_promo_outbox (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,event_id CHAR(36) NOT NULL UNIQUE,intent_id BIGINT NOT NULL,event_type VARCHAR(40) NOT NULL DEFAULT 'PROMO_INTENT_ACCEPTED',event_version INT NOT NULL DEFAULT 1,
 payload_json JSON NOT NULL,status VARCHAR(24) NOT NULL DEFAULT 'NEW',attempt_count INT NOT NULL DEFAULT 0,lease_owner VARCHAR(100),lease_until TIMESTAMP(3) NULL,
 next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),last_error_code VARCHAR(100),created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(intent_id) REFERENCES fg_promo_purchase_intent(id),UNIQUE(event_type,intent_id,event_version),CHECK(event_type='PROMO_INTENT_ACCEPTED' AND event_version=1),
 CHECK(status IN ('NEW','IN_FLIGHT','SENT','FAILED','FAILED_PERMANENT')),CHECK(attempt_count>=0),INDEX ix_promo_outbox_claim(status,next_attempt_at,lease_until,id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_promo_event_consumed (
 event_id CHAR(36) PRIMARY KEY,intent_id BIGINT NOT NULL UNIQUE,payload_sha256 CHAR(64) NOT NULL,result VARCHAR(24) NOT NULL,consumed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 FOREIGN KEY(event_id) REFERENCES fg_promo_outbox(event_id),FOREIGN KEY(intent_id) REFERENCES fg_promo_purchase_intent(id),CHECK(result IN ('APPLIED','IGNORED_TERMINAL','ALREADY_APPLIED'))
) ENGINE=InnoDB;
