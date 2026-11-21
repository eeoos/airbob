package kr.kro.airbob.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.config.ClockConfig;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.port.SessionInvalidator;
import kr.kro.airbob.domain.member.repository.MemberHistoryRepository;
import kr.kro.airbob.domain.member.repository.MemberRepository;

@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({ClockConfig.class, JpaAuditingConfig.class, QueryDslConfig.class,
	MemberProfileReadQueryIntegrationTest.ReadTestConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MemberProfileReadQueryIntegrationTest {

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
		.withDatabaseName("airbobdb_member_profile_read");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired private JdbcTemplate jdbc;
	@Autowired private EntityManager entityManager;
	@Autowired private EntityManagerFactory entityManagerFactory;
	@Autowired private SqlCapture sqlCapture;
	@Autowired private MemberRepository memberRepository;
	@Autowired private ObjectMapper objectMapper;
	private MemberHistoryRepository historyRepository;
	private SessionInvalidator sessionInvalidator;
	private MemberService service;

	@BeforeEach
	void fixture() {
		jdbc.update("""
			INSERT INTO member (id, email, password, nickname, role, status, thumbnail_image_url, updated_at)
			VALUES (41, 'profile@test.invalid', 'synthetic-password-hash', '예약 회원', 'MEMBER', 'ACTIVE',
				'/images/profile.jpg', NOW(6)),
			       (42, 'another@test.invalid', 'synthetic-other-hash', '다른 회원', 'MEMBER', 'ACTIVE', null, NOW(6))
			""");
		historyRepository = mock(MemberHistoryRepository.class);
		sessionInvalidator = mock(SessionInvalidator.class);
		service = new MemberService(memberRepository, historyRepository, sessionInvalidator, Clock.systemUTC());
	}

	@ParameterizedTest
	@EnumSource(MemberRole.class)
	void readsActiveProfileContractForEachRole(MemberRole role) throws Exception {
		jdbc.update("UPDATE member SET role = ? WHERE id = 41", role.name());
		Statistics statistics = prepareMeasurement();

		var result = service.getMemberInfo(41L);

		try (var fixture = new ClassPathResource("contracts/auth-me-profile.json").getInputStream()) {
			assertThat(objectMapper.readTree(objectMapper.writeValueAsString(result)))
				.isEqualTo(objectMapper.readTree(fixture));
		}
		assertReadQuery(statistics);
		assertThat(jdbc.queryForObject("SELECT thumbnail_image_url FROM member WHERE id = 41", String.class))
			.isEqualTo("/images/profile.jpg");
	}

	@ParameterizedTest
	@EnumSource(value = MemberStatus.class, names = {"DELETED", "DORMANT"})
	void rejectsInactiveMember(MemberStatus status) {
		jdbc.update("UPDATE member SET status = ? WHERE id = 41", status.name());
		Statistics statistics = prepareMeasurement();

		assertThatThrownBy(() -> service.getMemberInfo(41L))
			.isInstanceOfSatisfying(MemberNotFoundException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND));
		assertReadQuery(statistics);
	}

	@Test
	void rejectsUnknownMemberInsteadOfReturningAnotherActiveProfile() {
		Statistics statistics = prepareMeasurement();
		assertThatThrownBy(() -> service.getMemberInfo(999L)).isInstanceOf(MemberNotFoundException.class);
		assertReadQuery(statistics);
	}

	@Test
	void readsCurrentValuesAndPreservesAuthenticationAndImageData() {
		service.getMemberInfo(41L);
		jdbc.update("UPDATE member SET nickname = '변경된 회원', email = 'updated@test.invalid' WHERE id = 41");
		Statistics statistics = prepareMeasurement();

		var result = service.getMemberInfo(41L);

		assertThat(result.id()).isEqualTo(41L);
		assertThat(result.nickname()).isEqualTo("변경된 회원");
		assertThat(result.email()).isEqualTo("updated@test.invalid");
		assertReadQuery(statistics);
		assertThat(jdbc.queryForObject("SELECT password FROM member WHERE id = 41", String.class))
			.isEqualTo("synthetic-password-hash");
		assertThat(jdbc.queryForObject("SELECT thumbnail_image_url FROM member WHERE id = 41", String.class))
			.isEqualTo("/images/profile.jpg");
	}

	private Statistics prepareMeasurement() {
		entityManager.flush();
		entityManager.clear();
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
		sqlCapture.statements.clear();
		return statistics;
	}

	private void assertReadQuery(Statistics statistics) {
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		assertThat(sqlCapture.statements).hasSize(1);
		String sql = sqlCapture.statements.getFirst();
		String select = sql.substring("select ".length(), sql.indexOf(" from "));
		assertThat(select.split(",")).hasSize(3);
		assertThat(select).doesNotContain(".password", ".role", ".status", ".thumbnail_image_url", ".updated_at");
		assertThat(sql).contains(".status=?").doesNotContain(" join ");
		assertThat(statistics.getEntityLoadCount()).isZero();
		verifyNoInteractions(historyRepository, sessionInvalidator);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ReadTestConfig {
		@Bean
		SqlCapture sqlCapture() {
			return new SqlCapture();
		}

		@Bean
		HibernatePropertiesCustomizer statementInspector(SqlCapture capture) {
			return properties -> properties.put("hibernate.session_factory.statement_inspector", capture);
		}
	}

	static class SqlCapture implements StatementInspector {
		private final List<String> statements = new ArrayList<>();

		@Override
		public String inspect(String sql) {
			statements.add(sql.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT).trim());
			return sql;
		}
	}
}
