-- V23__create_member_status_history_table.sql

ALTER TABLE member ADD COLUMN status VARCHAR(20) NOT NULL;
UPDATE member SET status = 'ACTIVE'; -- 기존 데이터는 모두 ACTIVE로 설정

CREATE TABLE member_status_history (
    id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    new_status VARCHAR(20) NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    reason VARCHAR(255),
    PRIMARY KEY (id),
    CONSTRAINT fk_member_status_history_to_member FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE=InnoDB;

CREATE INDEX idx_member_status_history_member_id ON member_status_history (member_id);

