package kr.kro.airbob.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("V33 커서 페이지네이션 인덱스 마이그레이션")
class CursorPaginationIndexesMigrationIntegrationTest {

	private static final Map<String, List<String>> NEW_INDEXES = Map.of(
		"reservation.idx_reservation_guest_created_id", List.of(
			"guest_id:A:BTREE:YES:1", "created_at:D:BTREE:YES:1", "id:D:BTREE:YES:1"),
		"reservation.idx_reservation_accommodation_cursor_cover", List.of(
			"accommodation_id:A:BTREE:YES:1", "created_at:D:BTREE:YES:1", "id:D:BTREE:YES:1",
			"status:A:BTREE:YES:1", "check_out_at:A:BTREE:YES:1"),
		"accommodation.idx_accommodation_member_created_id_status", List.of(
			"member_id:A:BTREE:YES:1", "created_at:D:BTREE:YES:1", "id:D:BTREE:YES:1", "status:A:BTREE:YES:1"),
		"review.idx_review_accommodation_status_created_id", List.of(
			"accommodation_id:A:BTREE:YES:1", "status:A:BTREE:YES:1",
			"created_at:D:BTREE:YES:1", "id:D:BTREE:YES:1"),
		"review.idx_review_accommodation_status_rating_desc_created_id", List.of(
			"accommodation_id:A:BTREE:YES:1", "status:A:BTREE:YES:1", "rating:D:BTREE:YES:1",
			"created_at:D:BTREE:YES:1", "id:D:BTREE:YES:1"),
		"review.idx_review_accommodation_status_rating_asc_created_id", List.of(
			"accommodation_id:A:BTREE:YES:1", "status:A:BTREE:YES:1", "rating:A:BTREE:YES:1",
			"created_at:D:BTREE:YES:1", "id:D:BTREE:YES:1"),
		"wishlist.idx_wishlist_member_status_created_id", List.of(
			"member_id:A:BTREE:YES:1", "status:A:BTREE:YES:1",
			"created_at:D:BTREE:YES:1", "id:D:BTREE:YES:1"),
		"wishlist_accommodation.idx_wishlist_accommodation_wishlist_created_id", List.of(
			"wishlist_id:A:BTREE:YES:1", "created_at:D:BTREE:YES:1", "id:D:BTREE:YES:1"));

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11");

	@Test
	@DisplayName("기존 데이터·날짜 소유권·제약조건과 V32 인덱스를 보존하고 커서 인덱스 여덟 개만 추가")
	void preservesExistingDataAndAddsEightCursorIndexes() throws SQLException {
		flyway("32").migrate();
		try (Connection connection = DriverManager.getConnection(
			MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
			 Statement statement = connection.createStatement()) {
			insertExistingRows(statement);
			Map<String, List<Map<String, String>>> dataBefore = businessRows(statement);
			var indexesBefore = indexes(statement);
			var constraintsBefore = constraints(statement);

			assertThat(indexesBefore).doesNotContainKeys(NEW_INDEXES.keySet().toArray(String[]::new));
			assertThat(flyway("33").migrate().migrationsExecuted).isEqualTo(1);
			assertThat(flyway("33").migrate().migrationsExecuted).isZero();

			assertThat(businessRows(statement)).isEqualTo(dataBefore);
			assertThat(constraints(statement)).isEqualTo(constraintsBefore);
			Map<String, List<String>> expectedIndexes = new LinkedHashMap<>(indexesBefore);
			expectedIndexes.putAll(NEW_INDEXES);
			assertThat(indexes(statement)).containsExactlyInAnyOrderEntriesOf(expectedIndexes);
		}
	}

	private void insertExistingRows(Statement statement) throws SQLException {
		statement.executeUpdate("""
			INSERT INTO member (id, nickname, role, status, updated_at)
			VALUES (1, 'host', 'MEMBER', 'ACTIVE', '2026-10-16 01:00:00.123456'),
			       (2, 'other host', 'MEMBER', 'ACTIVE', '2026-10-16 01:00:00.123456'),
			       (3, 'guest', 'MEMBER', 'ACTIVE', '2026-10-16 01:00:00.123456')
			""");
		statement.executeUpdate("""
			INSERT INTO accommodation (id, member_id, name, accommodation_uid, status,
			    check_in_time, check_out_time, time_zone_id, created_at, updated_at)
			VALUES (10, 1, 'published', UUID_TO_BIN(UUID()), 'PUBLISHED',
			        '15:00:00', '11:00:00', 'Asia/Seoul', '2026-10-16 01:00:00.123456', '2026-10-16 01:00:00.123456'),
			       (11, 1, 'unpublished', UUID_TO_BIN(UUID()), 'UNPUBLISHED',
			        '15:00:00', '11:00:00', 'America/New_York', '2026-10-16 01:00:00.123456', '2026-10-16 01:00:00.123456'),
			       (12, 2, 'other host', UUID_TO_BIN(UUID()), 'PUBLISHED',
			        '15:00:00', '11:00:00', 'Asia/Seoul', NULL, '2026-10-16 01:00:00.123456')
			""");
		statement.executeUpdate("""
			INSERT INTO reservation (id, reservation_uid, reservation_code, accommodation_id, guest_id,
			    check_in_date, check_out_date, check_in_at, check_out_at, time_zone_id,
			    guest_count, total_price, discount_amount, status, message, expires_at, created_at, updated_at, currency)
			VALUES (100, UUID_TO_BIN(UUID()), 'CURSOR-100', 10, 3,
			        '2026-10-20', '2026-10-21', '2026-10-20 06:00:00', '2026-10-21 02:00:00', 'Asia/Seoul',
			        2, 120000, 10000, 'PAYMENT_PENDING', NULL, '2026-10-17 01:15:00.123456',
			        '2026-10-17 01:00:00.123456', '2026-10-17 01:00:00.123456', 'KRW'),
			       (101, UUID_TO_BIN(UUID()), 'CURSOR-101', 11, 3,
			        '2026-10-20', '2026-10-21', '2026-10-20 19:00:00', '2026-10-21 15:00:00', 'America/New_York',
			        1, 100000, 0, 'CONFIRMED', 'preserve me', '2026-10-17 01:15:00.123456',
			        '2026-10-17 01:00:00.123456', '2026-10-17 01:02:00.654321', 'KRW'),
			       (102, UUID_TO_BIN(UUID()), 'CURSOR-102', 10, 3,
			        '2026-10-22', '2026-10-23', '2026-10-22 06:00:00', '2026-10-23 02:00:00', 'Asia/Seoul',
			        2, 150000, 0, 'CANCELLED', NULL, '2026-10-17 01:15:00.123455',
			        '2026-10-17 01:00:00.123455', '2026-10-17 01:02:00.654321', 'KRW'),
			       (103, UUID_TO_BIN(UUID()), 'CURSOR-103', 12, 3,
			        '2026-10-20', '2026-10-21', '2026-10-20 06:00:00', '2026-10-21 02:00:00', 'Asia/Seoul',
			        3, 180000, 0, 'CANCELLATION_FAILED', NULL, '2026-10-17 01:15:00.123457',
			        '2026-10-17 01:00:00.123457', '2026-10-17 01:02:00.654321', 'KRW')
			""");
		statement.executeUpdate("""
			INSERT INTO accommodation_inventory_day
			    (accommodation_id, stay_date, state, reservation_id, hold_expires_at)
			VALUES (10, '2026-10-20', 'HOLD', 100, '2026-10-17 01:15:00.123456'),
			       (10, '2026-10-22', 'FREE', NULL, NULL),
			       (11, '2026-10-20', 'OCCUPIED', 101, NULL),
			       (12, '2026-10-20', 'OCCUPIED', 103, NULL)
			""");
		statement.executeUpdate("""
			INSERT INTO review (id, accommodation_id, member_id, rating, content, status, created_at, updated_at)
			VALUES (200, 10, 3, 5, '첫 번째 리뷰', 'PUBLISHED', '2026-10-16 01:00:00.123456', '2026-10-16 01:00:00.123456'),
			       (201, 10, 3, 5, '같은 평점과 시각', 'PUBLISHED', '2026-10-16 01:00:00.123456', '2026-10-16 01:00:00.123456'),
			       (202, 10, 3, 1, '낮은 평점', 'PUBLISHED', '2026-10-16 01:00:00.123457', '2026-10-16 01:00:00.123457'),
			       (203, 11, 3, NULL, NULL, 'DELETED', NULL, '2026-10-16 01:00:00.123456')
			""");
		statement.executeUpdate("""
			INSERT INTO wishlist
			    (id, name, member_id, status, accommodation_count, representative_accommodation_id, created_at, updated_at)
			VALUES (300, '여름 여행', 1, 'ACTIVE', 2, 11, '2026-10-16 01:00:00.123456', '2026-10-16 01:00:00.123456'),
			       (301, '겨울 여행', 1, 'ACTIVE', 1, 12, '2026-10-16 01:00:00.123456', '2026-10-16 01:00:00.123456'),
			       (302, '삭제한 여행', 2, 'DELETED', 1, 10, NULL, '2026-10-16 01:00:00.123456')
			""");
		statement.executeUpdate("""
			INSERT INTO wishlist_accommodation
			    (id, wishlist_id, accommodation_id, memo, created_at, updated_at)
			VALUES (400, 300, 10, '보존할 메모', '2026-10-16 01:00:00.123456', '2026-10-16 01:00:00.123456'),
			       (401, 300, 11, NULL, '2026-10-16 01:00:00.123456', '2026-10-16 01:00:00.123456'),
			       (402, 301, 12, NULL, '2026-10-16 01:00:00.123457', '2026-10-16 01:00:00.123457'),
			       (403, 302, 10, NULL, NULL, '2026-10-16 01:00:00.123456')
			""");
	}

	private Map<String, List<Map<String, String>>> businessRows(Statement statement) throws SQLException {
		Map<String, List<Map<String, String>>> snapshot = new LinkedHashMap<>();
		for (String table : List.of("member", "accommodation", "reservation", "review", "wishlist", "wishlist_accommodation")) {
			snapshot.put(table, rows(statement, "SELECT * FROM " + table + " ORDER BY id"));
		}
		snapshot.put("accommodation_inventory_day", rows(statement, """
			SELECT * FROM accommodation_inventory_day ORDER BY accommodation_id, stay_date
			"""));
		return snapshot;
	}

	private Map<String, List<String>> indexes(Statement statement) throws SQLException {
		Map<String, List<String>> definitions = new LinkedHashMap<>();
		for (Map<String, String> row : rows(statement, """
			SELECT CONCAT(table_name, '.', index_name) AS index_key,
			       CONCAT(column_name, ':', collation, ':', index_type, ':', is_visible, ':', non_unique) AS definition
			FROM information_schema.statistics
			WHERE table_schema = DATABASE()
			ORDER BY table_name, index_name, seq_in_index
			""")) {
			definitions.computeIfAbsent(row.get("index_key"), ignored -> new ArrayList<>()).add(row.get("definition"));
		}
		return definitions;
	}

	private List<Map<String, String>> constraints(Statement statement) throws SQLException {
		return rows(statement, """
			SELECT table_name, constraint_name, constraint_type, enforced
			FROM information_schema.table_constraints
			WHERE table_schema = DATABASE()
			ORDER BY table_name, constraint_name
			""");
	}

	private List<Map<String, String>> rows(Statement statement, String sql) throws SQLException {
		List<Map<String, String>> rows = new ArrayList<>();
		try (ResultSet result = statement.executeQuery(sql)) {
			while (result.next()) {
				Map<String, String> row = new LinkedHashMap<>();
				for (int index = 1; index <= result.getMetaData().getColumnCount(); index++) {
					row.put(result.getMetaData().getColumnLabel(index), result.getString(index));
				}
				rows.add(row);
			}
		}
		return rows;
	}

	private Flyway flyway(String target) {
		return Flyway.configure()
			.dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration")
			.target(target)
			.load();
	}
}
