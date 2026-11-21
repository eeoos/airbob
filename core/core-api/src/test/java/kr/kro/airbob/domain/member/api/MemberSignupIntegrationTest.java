package kr.kro.airbob.domain.member.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.ObjectMapper;

import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.common.history.ChangeType;
import kr.kro.airbob.config.ClockConfig;
import kr.kro.airbob.config.JpaAuditingConfig;
import kr.kro.airbob.config.QueryDslConfig;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.port.SessionInvalidator;
import kr.kro.airbob.domain.member.repository.MemberHistoryRepository;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.member.service.MemberService;

@DataJpaTest(showSql = false)
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({ClockConfig.class, JpaAuditingConfig.class, QueryDslConfig.class, MemberService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MemberSignupIntegrationTest {

	private static final String EMAIL = "signup@example.invalid";
	private static final String PASSWORD = "example-password";

	@Container
	private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
		.withDatabaseName("airbobdb_member_signup");

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.flyway.url", MYSQL::getJdbcUrl);
		registry.add("spring.flyway.user", MYSQL::getUsername);
		registry.add("spring.flyway.password", MYSQL::getPassword);
	}

	@Autowired private MemberService memberService;
	@Autowired private MemberHistoryRepository historyRepository;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper mapper;
	@MockitoSpyBean private MemberRepository memberRepository;
	@MockitoBean private SessionInvalidator sessionInvalidator;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		historyRepository.deleteAllInBatch();
		memberRepository.deleteAllInBatch();
		mvc = MockMvcBuilders.standaloneSetup(new MemberController(memberService))
			.setControllerAdvice(new GlobalExceptionHandler())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
			.build();
	}

	@Test
	void signupCommitsActiveOrdinaryMemberAndHistoryWithoutLoggingIn() throws Exception {
		var request = signupRequest();
		request.put("role", "ADMIN");
		request.put("status", "DELETED");
		request.putNull("thumbnail_image_url");
		var response = signup(request.toString());

		assertThat(response.getStatus()).isEqualTo(201);
		assertThat(mapper.readTree(response.getContentAsString())).isEqualTo(mapper.readTree("{\"success\":true}"));
		assertThat(response.getHeader("Set-Cookie")).isNull();
		Member member = memberRepository.findByEmailAndStatus(EMAIL, MemberStatus.ACTIVE).orElseThrow();
		assertThat(member.getRole()).isEqualTo(MemberRole.MEMBER);
		assertThat(member.getThumbnailImageUrl()).isNull();
		assertThat(BCrypt.checkpw(PASSWORD, member.getPassword())).isTrue();
		assertThat(historyRepository.findAll()).singleElement().satisfies(history -> {
			assertThat(history.getMemberId()).isEqualTo(member.getId());
			assertThat(history.getStatus()).isEqualTo(MemberStatus.ACTIVE);
			assertThat(history.getChangeType()).isEqualTo(ChangeType.CREATE);
		});
	}

	@ParameterizedTest
	@EnumSource(MemberStatus.class)
	void duplicateEmailReturnsConflictForEveryExistingMemberStatus(MemberStatus memberStatus) throws Exception {
		Member original = memberRepository.saveAndFlush(Member.builder()
			.email(EMAIL).nickname("기존 회원").password("synthetic-password-hash").build());
		jdbc.update("UPDATE member SET status = ? WHERE id = ?", memberStatus.name(), original.getId());

		var response = signup(signupRequest().toString());

		assertThat(response.getStatus()).isEqualTo(409);
		assertThat(mapper.readTree(response.getContentAsString()).at("/error/code").asText()).isEqualTo("M002");
		assertThat(memberRepository.count()).isEqualTo(1);
		assertThat(historyRepository.count()).isZero();
		assertThat(jdbc.queryForObject("SELECT status FROM member WHERE id = ?", String.class, original.getId()))
			.isEqualTo(memberStatus.name());
	}

	@Test
	void concurrentSignupReturnsOneCreatedAndOneConflictWithoutPartialHistory() throws Exception {
		CyclicBarrier beforeInsert = new CyclicBarrier(2);
		var repositoryDelegate = mockingDetails(memberRepository).getMockCreationSettings().getDefaultAnswer();
		doAnswer(call -> {
			beforeInsert.await(10, TimeUnit.SECONDS);
			// Spring Data's interface spy delegates to the real repository proxy.
			return repositoryDelegate.answer(call);
		}).when(memberRepository).save(any(Member.class));

		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> signup(signupRequest().toString()));
			var second = executor.submit(() -> signup(signupRequest().toString()));
			var responses = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
			assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 409);
			for (var response : responses) {
				if (response.getStatus() == 409) {
					assertThat(mapper.readTree(response.getContentAsString()).at("/error/code").asText()).isEqualTo("M002");
				}
			}
		}
		assertThat(memberRepository.count()).isEqualTo(1);
		assertThat(historyRepository.count()).isEqualTo(1);
	}

	@Test
	void thumbnailBeyondStorageLimitReturnsBadRequestWithoutCreatingMember() throws Exception {
		var request = signupRequest().put("thumbnail_image_url", "https://example.invalid/" + "a".repeat(256));
		mvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON).content(request.toString()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("C001"));
		assertThat(memberRepository.count()).isZero();
		assertThat(historyRepository.count()).isZero();
	}

	private com.fasterxml.jackson.databind.node.ObjectNode signupRequest() {
		return mapper.createObjectNode().put("email", EMAIL).put("password", PASSWORD).put("nickname", "여행자");
	}

	private MockHttpServletResponse signup(String body) throws Exception {
		return mvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON).content(body))
			.andReturn().getResponse();
	}
}
