-- Debezium CDC 유저 생성 및 권한 부여
CREATE USER IF NOT EXISTS 'debezium'@'%' IDENTIFIED BY 'dbzpassword';

-- CDC에 필요한 권한
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'debezium'@'%';

-- airbobdb 데이터베이스에 대한 전체 권한
GRANT ALL PRIVILEGES ON airbobdb.* TO 'debezium'@'%';

FLUSH PRIVILEGES;
