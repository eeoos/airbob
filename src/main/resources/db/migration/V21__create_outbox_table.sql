-- V21__create_outbox_table.sql

CREATE TABLE outbox (
                        id BIGINT NOT NULL AUTO_INCREMENT,
                        aggregate_type VARCHAR(255) NOT NULL,
                        aggregate_id VARCHAR(255) NOT NULL,
                        event_type VARCHAR(255) NOT NULL,
                        payload TEXT NOT NULL,
                        created_at DATETIME(6) NOT NULL,
                        PRIMARY KEY (id)
) ENGINE=InnoDB;
