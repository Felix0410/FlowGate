-- C读模型：非破坏性增量，权威B余额/流水/Outbox均不改。
CREATE TABLE IF NOT EXISTS fg_consumed_event (
 event_id CHAR(36) PRIMARY KEY,
 charge_id CHAR(36) NOT NULL UNIQUE,
 payload_sha256 CHAR(64) NOT NULL,
 consumed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 INDEX ix_consumed_at(consumed_at)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS fg_usage_daily (
 day_utc DATE NOT NULL,
 application_id BIGINT NOT NULL,
 api_id BIGINT NOT NULL,
 authorized_units BIGINT NOT NULL DEFAULT 0,
 grant_count BIGINT NOT NULL DEFAULT 0,
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 PRIMARY KEY(day_utc,application_id,api_id),
 INDEX ix_usage_app_day(application_id,day_utc,api_id),
 CONSTRAINT ck_usage_units CHECK(authorized_units>=0 AND grant_count>=0)
) ENGINE=InnoDB;
