-- M仅增加传输控制，不修改旧账本。显式安装后保持旧路由，重复DDL不重置模式。
CREATE TABLE IF NOT EXISTS fg_legacy_mq_cutover (
 id TINYINT PRIMARY KEY, mode VARCHAR(16) NOT NULL, epoch BIGINT NOT NULL,
 cutover_max_outbox_id BIGINT NOT NULL DEFAULT 0,
 changed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), changed_by BIGINT NULL,
 CHECK(id=1), CHECK(mode IN ('KAFKA','FROZEN','RABBIT')), CHECK(epoch>0), CHECK(cutover_max_outbox_id>=0)
) ENGINE=InnoDB;
INSERT IGNORE INTO fg_legacy_mq_cutover(id,mode,epoch) VALUES(1,'KAFKA',1);
