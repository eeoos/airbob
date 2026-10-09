package kr.kro.airbob.domain.coupon;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.kro.airbob.domain.coupon.common.CouponIssueStrategy;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Controlled SQL observation, separate from HTTP throughput measurements. */
@Testcontainers
class CouponIssueLockObservationTest {
	@Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11");

	@Test
	void recordsActualIndexLocksForBothKeysAndIsolationLevels() throws Exception {
		Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
			.locations("classpath:db/migration").load().migrate();
		List<Map<String, Object>> observations = new ArrayList<>();
		try (var observer = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
			var setup = observer.createStatement()) {
			setup.executeUpdate("""
				insert into coupon(id,name,discount_type,discount_value,is_active,total_quantity,issued_quantity,
				issue_start_at,issue_end_at,usable_from,usable_until,stock_prepared_at,updated_at)
				values(1,'locks','FIXED_AMOUNT',1000,true,2,0,
				'2026-01-01','2027-01-01','2026-01-01','2027-02-01',now(6),now(6))
				""");
			setup.executeUpdate("insert into member(id,email,nickname,role,status,updated_at) values(1,'locks@test.com','member','MEMBER','ACTIVE',now(6))");
			for (String table : List.of("coupon_issue_unit", "coupon_issue_unit_composite"))
				setup.executeUpdate("insert into " + table + "(coupon_id,unit_no) values(1,1),(1,2)");
			for (var strategy : CouponIssueStrategy.values()) {
				if (strategy == CouponIssueStrategy.DB) continue;
				try (var transaction = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
					var sql = transaction.createStatement()) {
					transaction.setTransactionIsolation(strategy.name().endsWith("_RC") ? Connection.TRANSACTION_READ_COMMITTED : Connection.TRANSACTION_REPEATABLE_READ);
					transaction.setAutoCommit(false);
					long connectionId;
					try (var result = sql.executeQuery("select connection_id()")) { result.next(); connectionId = result.getLong(1); }
					sql.executeQuery("select * from coupon where id=1 for share nowait").close();
					String columns = strategy.composite() ? "unit_no,unit_no" : "id,unit_no";
					long key;
					try (var result = sql.executeQuery("select " + columns + " from " + strategy.unitTable()
						+ " where coupon_id=1 order by unit_no limit 1 for update skip locked")) {
						assertThat(result.next()).isTrue(); key = result.getLong(1);
					}
					var selected = locks(observer, connectionId);
					var records = selected.stream().filter(row -> strategy.unitTable().equals(row.get("OBJECT_NAME"))
						&& "RECORD".equals(row.get("LOCK_TYPE"))).toList();
					assertThat(records).extracting(row -> row.get("INDEX_NAME")).contains("PRIMARY");
					if (strategy.composite()) assertThat(records).hasSize(1);
					else assertThat(records).hasSize(2).extracting(row -> row.get("INDEX_NAME"))
						.contains("uk_coupon_issue_unit_coupon_unit");
					if (strategy.name().endsWith("_RC")) assertThat(records)
						.allMatch(row -> row.get("LOCK_MODE").contains("REC_NOT_GAP"));
					else assertThat(records).anyMatch(row -> row.get("LOCK_MODE").equals("X"));
					sql.executeUpdate("delete from " + strategy.unitTable() + " where coupon_id=1 and "
						+ (strategy.composite() ? "unit_no" : "id") + "=" + key);
					var consumed = locks(observer, connectionId);
					sql.executeUpdate("insert into member_coupon(member_id,coupon_id,used,updated_at) values(1,1,false,now(6))");
					observations.add(Map.of("variant", strategy.variant(), "afterSelect", selected,
						"afterDelete", consumed, "afterInsert", locks(observer, connectionId)));
					transaction.rollback();
				}
			}
		}
		Path output = Path.of("build/reports/coupon/lock-observation.json");
		Files.createDirectories(output.getParent());
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),
			Map.of("mysql", "8.4.11", "scope", "one isolated transaction; logical record locks, not lock memory or throughput", "observations", observations));
	}

	private List<Map<String, String>> locks(Connection observer, long connectionId) throws Exception {
		List<Map<String, String>> rows = new ArrayList<>();
		try (var sql = observer.prepareStatement("""
			select OBJECT_NAME,INDEX_NAME,LOCK_TYPE,LOCK_MODE,LOCK_STATUS,LOCK_DATA
			from performance_schema.data_locks where THREAD_ID=(
			select THREAD_ID from performance_schema.threads where PROCESSLIST_ID=?)
			order by OBJECT_NAME,INDEX_NAME,LOCK_TYPE,LOCK_DATA
			""")) {
			sql.setLong(1, connectionId);
			try (var result = sql.executeQuery()) {
				while (result.next()) {
					Map<String, String> row = new LinkedHashMap<>();
					for (int index = 1; index <= 6; index++) row.put(result.getMetaData().getColumnLabel(index), result.getString(index));
					rows.add(row);
				}
			}
		}
		return rows;
	}
}
