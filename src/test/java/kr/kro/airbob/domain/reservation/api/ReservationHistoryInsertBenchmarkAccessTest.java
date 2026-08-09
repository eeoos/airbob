package kr.kro.airbob.domain.reservation.api;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import jakarta.servlet.http.Cookie;
import kr.kro.airbob.common.benchmark.bulkwrite.BulkWriteBenchmarkAccessGuard;
import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.domain.auth.filter.SessionAuthFilter;
import kr.kro.airbob.domain.auth.interceptor.AdminAuthInterceptor;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkRequest;
import kr.kro.airbob.domain.reservation.dto.ReservationHistoryInsertBenchmarkRequest.Variant;
import kr.kro.airbob.domain.reservation.service.ReservationHistoryInsertBenchmarkService;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationHistory INSERT 벌크 쓰기 API 전체 접근 경계 테스트")
class ReservationHistoryInsertBenchmarkAccessTest {

	private static final String TOKEN = "bulk-write-access-token-1234567890";
	private static final String PATH = "/api/v2/admin/benchmarks/bulk-write/reservation-history-insert";

	@Mock private ReservationHistoryInsertBenchmarkService benchmarkService;
	@Mock private RedisTemplate<String, Object> redisTemplate;
	@Mock private ValueOperations<String, Object> valueOperations;
	@Mock private MemberRepository memberRepository;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		ObjectMapper objectMapper = new ObjectMapper()
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		SessionAuthFilter sessionAuthFilter = new SessionAuthFilter(redisTemplate, objectMapper);
		AdminAuthInterceptor adminAuthInterceptor = new AdminAuthInterceptor(memberRepository);
		ReservationHistoryInsertBenchmarkController controller =
			new ReservationHistoryInsertBenchmarkController(
				benchmarkService,
				new BulkWriteBenchmarkAccessGuard(TOKEN)
			);
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
			.addFilters(sessionAuthFilter)
			.addInterceptors(adminAuthInterceptor)
			.build();
	}

	@Test
	@DisplayName("세션이 없으면 전용 token이 있어도 401이다")
	void rejectsTokenWithoutSession() throws Exception {
		mockMvc.perform(post(PATH)
				.contentType("application/json")
				.header(BulkWriteBenchmarkAccessGuard.HEADER_NAME, TOKEN)
				.content(beforeRequest(1)))
			.andExpect(status().isUnauthorized());

		then(benchmarkService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("일반 회원 세션은 ADMIN interceptor에서 403이다")
	void rejectsMemberSession() throws Exception {
		authenticate(10L, MemberRole.MEMBER);

		mockMvc.perform(post(PATH)
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.contentType("application/json")
				.header(BulkWriteBenchmarkAccessGuard.HEADER_NAME, TOKEN)
				.content(beforeRequest(1)))
			.andExpect(status().isForbidden());

		then(benchmarkService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("ADMIN 세션이어도 전용 token이 누락되거나 다르면 403이다")
	void rejectsMissingOrWrongToken() throws Exception {
		authenticate(10L, MemberRole.ADMIN);

		mockMvc.perform(post(PATH)
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.contentType("application/json")
				.content(beforeRequest(1)))
			.andExpect(status().isForbidden());
		mockMvc.perform(post(PATH)
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.contentType("application/json")
				.header(BulkWriteBenchmarkAccessGuard.HEADER_NAME, "wrong-token")
				.content(beforeRequest(1)))
			.andExpect(status().isForbidden());

		then(benchmarkService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("ADMIN 세션과 전용 token이 모두 맞아야 exact uppercase variant 요청을 허용한다")
	void allowsAdminWithDedicatedTokenForExactUppercaseVariants() throws Exception {
		authenticate(10L, MemberRole.ADMIN);
		ReservationHistoryInsertBenchmarkRequest before =
			new ReservationHistoryInsertBenchmarkRequest(Variant.BEFORE, 7);
		ReservationHistoryInsertBenchmarkRequest after =
			new ReservationHistoryInsertBenchmarkRequest(Variant.AFTER, 7);

		mockMvc.perform(post(PATH)
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.contentType("application/json")
				.header(BulkWriteBenchmarkAccessGuard.HEADER_NAME, TOKEN)
				.content(beforeRequest(7)))
			.andExpect(status().isOk());
		mockMvc.perform(post(PATH)
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.contentType("application/json")
				.header(BulkWriteBenchmarkAccessGuard.HEADER_NAME, TOKEN)
				.content(afterRequest(7)))
			.andExpect(status().isOk());

		then(benchmarkService).should().run(before);
		then(benchmarkService).should().run(after);
	}

	@Test
	@DisplayName("lowercase, UNKNOWN, 숫자, 누락, invalid dataset은 service 호출 전에 거부한다")
	void rejectsInvalidRequestBeforeService() throws Exception {
		authenticate(10L, MemberRole.ADMIN);

		assertRejected("{\"variant\":\"before\",\"dataset_size\":1}");
		assertRejected("{\"variant\":\"after\",\"dataset_size\":1}");
		assertRejected("{\"variant\":\"UNKNOWN\",\"dataset_size\":1}");
		assertRejected("{\"variant\":0,\"dataset_size\":1}");
		assertRejected("{\"dataset_size\":1}");
		assertRejected("{\"variant\":\"BEFORE\",\"dataset_size\":2001}");
		assertRejected("{\"variant\":\"BEFORE\",\"dataset_size\":-1}");
		assertRejected("{\"variant\":\"BEFORE\",\"dataset_size\":2147483648}");
		assertRejected("{\"variant\":\"BEFORE\",\"dataset_size\":1.5}");
		assertRejected("{\"variant\":\"BEFORE\",\"dataset_size\":\"0; DELETE FROM reservation\"}");

		then(benchmarkService).shouldHaveNoInteractions();
	}

	private void assertRejected(String content) throws Exception {
		mockMvc.perform(post(PATH)
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.contentType("application/json")
				.header(BulkWriteBenchmarkAccessGuard.HEADER_NAME, TOKEN)
				.content(content))
			.andExpect(status().is4xxClientError());
	}

	private void authenticate(long memberId, MemberRole role) {
		given(redisTemplate.hasKey("MEMBER_SESSION_ACTIVE:" + memberId)).willReturn(true);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.get("SESSION:valid-session")).willReturn(memberId);
		given(memberRepository.existsByIdAndStatusAndRole(
			memberId, MemberStatus.ACTIVE, MemberRole.ADMIN))
			.willReturn(role == MemberRole.ADMIN);
	}

	private String beforeRequest(int datasetSize) {
		return "{\"variant\":\"BEFORE\",\"dataset_size\":" + datasetSize + "}";
	}

	private String afterRequest(int datasetSize) {
		return "{\"variant\":\"AFTER\",\"dataset_size\":" + datasetSize + "}";
	}
}
