package kr.kro.airbob.common.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import kr.kro.airbob.config.WebMvcConfig;
import kr.kro.airbob.cursor.resolver.CursorParamArgumentResolver;
import kr.kro.airbob.domain.auth.filter.SessionAuthFilter;
import kr.kro.airbob.domain.auth.interceptor.AdminAuthInterceptor;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;

@Testcontainers
@SpringBootTest(
	classes = QueryCountMvcJpaPrometheusIntegrationTest.TestApplication.class,
	properties = {
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=create",
		"spring.jpa.open-in-view=false",
		"spring.cloud.aws.s3.enabled=false"
	}
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("MVC-JPA-Prometheus 쿼리 카운트 통합 테스트")
class QueryCountMvcJpaPrometheusIntegrationTest {

	private static final String ROUTE_TEMPLATE = "/monitoring-test/members/{memberId}";

	@Container
	private static final MySQLContainer<?> mySQLContainer = new MySQLContainer<>("mysql:8.0.33")
		.withDatabaseName("airbob_query_monitoring_test");

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", mySQLContainer::getJdbcUrl);
		registry.add("spring.datasource.username", mySQLContainer::getUsername);
		registry.add("spring.datasource.password", mySQLContainer::getPassword);
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PrometheusMeterRegistry prometheusMeterRegistry;

	@Autowired
	@Qualifier("queryCountStatementInspectorCustomizer")
	private HibernatePropertiesCustomizer statementInspectorCustomizer;

	@Autowired
	private SqlQueryStatementInspector sqlQueryStatementInspector;

	@MockitoBean
	private CursorParamArgumentResolver cursorParamArgumentResolver;

	@MockitoBean
	private SessionAuthFilter sessionAuthFilter;

	@MockitoBean
	private AdminAuthInterceptor adminAuthInterceptor;

	private long memberId;

	@BeforeEach
	void setUp() {
		prometheusMeterRegistry.clear();
		jdbcTemplate.update("DELETE FROM member");
		jdbcTemplate.update("""
			INSERT INTO member (email, nickname, role, status, updated_at)
			VALUES ('monitoring@airbob.test', 'monitoring-member', 'MEMBER', 'ACTIVE', NOW(6))
			""");
		memberId = jdbcTemplate.queryForObject("SELECT id FROM member", Long.class);
	}

	@Test
	@DisplayName("실제 MVC 요청의 Hibernate SELECT를 정규화 route tag의 Prometheus histogram으로 내보낸다")
	void exportsRepositoryQueryFromMappedMvcRequest() throws Exception {
		Map<String, Object> hibernateProperties = new HashMap<>();
		statementInspectorCustomizer.customize(hibernateProperties);
		assertThat(hibernateProperties).containsEntry(
			QueryMonitoringJpaConfig.STATEMENT_INSPECTOR_PROPERTY,
			sqlQueryStatementInspector
		);

		mockMvc.perform(get("/monitoring-test/members/{memberId}", memberId))
			.andExpect(status().isOk())
			.andExpect(content().string("monitoring-member"));

		String scrape = prometheusMeterRegistry.scrape();
		assertThat(scrape).contains("# TYPE app_query_per_request_queries histogram");
		assertThat(scrape.lines()
			.filter(line -> line.startsWith("app_query_per_request_queries_bucket"))
			.filter(line -> line.contains("path=\"" + ROUTE_TEMPLATE + "\""))
			.filter(line -> line.contains("http_method=\"GET\""))
			.filter(line -> line.contains("query_type=\"SELECT\""))
			.toList()
		).isNotEmpty();
		assertThat(scrape.lines()
			.filter(line -> line.startsWith("app_query_per_request_queries_sum"))
			.filter(line -> line.contains("path=\"" + ROUTE_TEMPLATE + "\""))
			.filter(line -> line.contains("http_method=\"GET\""))
			.filter(line -> line.contains("query_type=\"SELECT\""))
			.toList()
		).singleElement().satisfies(line -> assertThat(line).endsWith(" 1.0"));
		assertThat(scrape).doesNotContain("path=\"/monitoring-test/members/" + memberId + "\"");
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = Member.class)
	@EnableJpaRepositories(basePackageClasses = MemberRepository.class)
	@Import({
		WebMvcConfig.class,
		QueryCountInterceptor.class,
		MicrometerQueryCountMetricRecorder.class,
		QueryMonitoringJpaConfig.class,
		SqlQueryStatementInspector.class,
		RepositoryQueryController.class
	})
	static class TestApplication {

		@Bean
		PrometheusMeterRegistry prometheusMeterRegistry() {
			return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
		}
	}

	@RestController
	static class RepositoryQueryController {

		private final MemberRepository memberRepository;

		RepositoryQueryController(MemberRepository memberRepository) {
			this.memberRepository = memberRepository;
		}

		@GetMapping(ROUTE_TEMPLATE)
		String findMemberNickname(@PathVariable Long memberId) {
			return memberRepository.findById(memberId)
				.map(Member::getNickname)
				.orElseThrow();
		}
	}
}
