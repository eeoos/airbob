package kr.kro.airbob.common.monitoring.sqltrace;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceUnwrapper;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.p6spy.engine.spy.P6SpyDriver;
import com.zaxxer.hikari.HikariDataSource;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@Testcontainers
class LocalSqlTraceIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
		.withDatabaseName("airbob_sql_trace_test");

	private final Logger logger = (Logger)LoggerFactory.getLogger(LocalReadSqlTraceListener.LOGGER_NAME);
	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
	private Level previousLevel;

	@BeforeEach
	void captureTrace() {
		previousLevel = logger.getLevel();
		logger.setLevel(Level.INFO);
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach
	void cleanUp() {
		RequestContextHolder.resetRequestAttributes();
		logger.detachAppender(appender);
		logger.setLevel(previousLevel);
		appender.stop();
	}

	@Test
	void loggedSqlCanBeReplayedWithTheSameMicrosecondsBinaryAndEscapedValues() {
		context("dev,sql-trace").run(application -> {
			assertThat(application).hasNotFailed();
			DataSource dataSource = application.getBean(DataSource.class);
			assertThat(dataSource).isInstanceOf(HikariDataSource.class);
			assertThat(((HikariDataSource)dataSource).getDriverClassName()).isEqualTo(P6SpyDriver.class.getName());
			// 결제 execution fence가 사용하는 기존 Hikari 접근도 유지한다.
			assertThat(DataSourceUnwrapper.unwrap(dataSource, HikariDataSource.class)).isNotNull();
			JdbcTemplate jdbc = new JdbcTemplate(dataSource);
			bindRequest("GET", "/api/v1/profile/host/accommodations");
			Map<String, Object> original = jdbc.queryForMap("""
				SELECT ? AS member_id, ? AS status, CAST(? AS DATETIME(6)) AS cursor_at,
				       HEX(?) AS uid, ? AS text_value, ? AS null_value, ? AS flag,
				       'literal ?' AS literal_question_mark
				""", 6675L, "PUBLISHED", Timestamp.valueOf("2026-09-22 13:29:09.123456"),
				HexFormat.of().parseHex("c993390d0e7e3e54a011e2dad1525c9f"), "O'Reilly\\객실?", null, true);

			assertThat(appender.list).hasSize(1);
			String message = appender.list.getFirst().getFormattedMessage();
			assertThat(message).startsWith("SQL_TRACE GET /api/v1/profile/host/accommodations | JDBC execute ");
			String sql = message.substring(message.indexOf('\n') + 1);
			assertThat(sql).contains("123456").contains("PUBLISHED").endsWith(";");
			RequestContextHolder.resetRequestAttributes();
			assertThat(jdbc.queryForMap(sql)).isEqualTo(original);
			assertThat(appender.list).hasSize(1);
		});
	}

	@Test
	void startupBackgroundLoginAndActuatorQueriesDoNotProduceTrace() {
		context("dev,sql-trace").run(application -> {
			assertThat(application).hasNotFailed();
			JdbcTemplate jdbc = new JdbcTemplate(application.getBean(DataSource.class));
			jdbc.queryForObject("SELECT ?", Integer.class, 1000);
			bindRequest("POST", "/api/v1/auth/login");
			jdbc.queryForObject("SELECT ?", String.class, "not-a-real-credential");
			bindRequest("GET", "/actuator/health");
			jdbc.queryForObject("SELECT 1", Integer.class);
			assertThat(appender.list).isEmpty();
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"dev", "dev,sql-trace,aws", "dev,sql-trace,oci", "dev,sql-trace,performance-lab"})
	void ordinaryAndPerformanceEnvironmentsKeepTheOriginalDataSource(String profiles) {
		context(profiles).run(application -> {
			assertThat(application).hasNotFailed();
			assertThat(application.getBean(DataSource.class)).isInstanceOf(HikariDataSource.class);
			assertThat(application.getBean(HikariDataSource.class).getDriverClassName())
				.isEqualTo("com.mysql.cj.jdbc.Driver");
			assertThat(application).doesNotHaveBean("sqlTraceDriverPostProcessor");
		});
	}

	private ApplicationContextRunner context(String profiles) {
		String jdbcUrl = MYSQL.getJdbcUrl();
		return new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
			.withUserConfiguration(LocalSqlTraceConfiguration.class)
			.withPropertyValues("spring.profiles.active=" + profiles,
				"spring.datasource.url=" + jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?")
					+ "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
				"spring.datasource.username=" + MYSQL.getUsername(),
				"spring.datasource.password=" + MYSQL.getPassword(),
				"spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver");
	}

	private void bindRequest(String method, String path) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, path);
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
	}
}
