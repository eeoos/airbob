package kr.kro.airbob.domain.wishlist.api;

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
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.wishlist.dto.WishlistDeleteBenchmarkRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistDeleteBenchmarkRequest.Variant;
import kr.kro.airbob.domain.wishlist.service.WishlistDeleteBenchmarkService;

@ExtendWith(MockitoExtension.class)
@DisplayName("Wishlist 삭제 벌크 쓰기 API 전체 접근 경계 테스트")
class WishlistDeleteBenchmarkAccessTest {

	private static final String TOKEN = "bulk-write-access-token-1234567890";
	private static final String PATH = "/api/v2/admin/benchmarks/bulk-write/wishlist-delete";

	@Mock private WishlistDeleteBenchmarkService benchmarkService;
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
		WishlistDeleteBenchmarkController controller = new WishlistDeleteBenchmarkController(
			benchmarkService,
			new BulkWriteBenchmarkAccessGuard(TOKEN)
		);
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.setCustomArgumentResolvers(new CurrentMemberIdArgumentResolver())
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
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("M004"));

		then(benchmarkService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("일반 회원 세션은 ADMIN interceptor에서 403이다")
	void rejectsMemberSessionBeforeBenchmarkGuard() throws Exception {
		authenticate(10L, MemberRole.MEMBER);

		mockMvc.perform(post(PATH)
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.contentType("application/json")
				.header(BulkWriteBenchmarkAccessGuard.HEADER_NAME, TOKEN)
				.content(beforeRequest(1)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("M006"));

		then(benchmarkService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("ADMIN 세션이어도 전용 token이 누락되거나 다르면 403이다")
	void rejectsMissingOrWrongBulkToken() throws Exception {
		authenticate(10L, MemberRole.ADMIN);

		mockMvc.perform(post(PATH)
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.contentType("application/json")
				.content(beforeRequest(1)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("B001"));
		mockMvc.perform(post(PATH)
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.contentType("application/json")
				.header(BulkWriteBenchmarkAccessGuard.HEADER_NAME, "wrong-token")
				.content(beforeRequest(1)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("B001"));

		then(benchmarkService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("ADMIN 세션과 전용 token이 모두 맞아야 domain ID 없이 실행한다")
	void allowsAdminWithDedicatedToken() throws Exception {
		authenticate(10L, MemberRole.ADMIN);
		WishlistDeleteBenchmarkRequest request = new WishlistDeleteBenchmarkRequest(Variant.BEFORE, 7);

		mockMvc.perform(post(PATH)
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.contentType("application/json")
				.header(BulkWriteBenchmarkAccessGuard.HEADER_NAME, TOKEN)
				.content(beforeRequest(7)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		then(benchmarkService).should().run(10L, request);
	}

	@Test
	@DisplayName("정확한 대문자 AFTER variant를 요청 계약으로 허용한다")
	void allowsExactAfterVariant() throws Exception {
		authenticate(10L, MemberRole.ADMIN);
		WishlistDeleteBenchmarkRequest request = new WishlistDeleteBenchmarkRequest(Variant.AFTER, 7);

		mockMvc.perform(post(PATH)
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.contentType("application/json")
				.header(BulkWriteBenchmarkAccessGuard.HEADER_NAME, TOKEN)
				.content("{\"variant\":\"AFTER\",\"dataset_size\":7}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.success").value(true));

		then(benchmarkService).should().run(10L, request);
	}

	@Test
	@DisplayName("범위 밖 dataset, 정수 overflow, SQL 문자열은 service 호출 전에 거부한다")
	void rejectsInvalidDatasetBeforeService() throws Exception {
		authenticate(10L, MemberRole.ADMIN);

		assertRejectedBeforeService("{\"variant\":\"BEFORE\",\"dataset_size\":1001}");
		assertRejectedBeforeService("{\"variant\":\"BEFORE\",\"dataset_size\":-1}");
		assertRejectedBeforeService("{\"variant\":\"BEFORE\",\"dataset_size\":2147483648}");
		assertRejectedBeforeService("{\"variant\":\"BEFORE\",\"dataset_size\":1.5}");
		assertRejectedBeforeService("{\"variant\":\"BEFORE\",\"dataset_size\":\"0; DELETE FROM wishlist\"}");
		assertRejectedBeforeService("{\"variant\":\"UNKNOWN\",\"dataset_size\":1}");
		assertRejectedBeforeService("{\"variant\":\"after\",\"dataset_size\":1}");
		assertRejectedBeforeService("{\"variant\":0,\"dataset_size\":1}");

		then(benchmarkService).shouldHaveNoInteractions();
	}

	private void assertRejectedBeforeService(String content) throws Exception {
		mockMvc.perform(post(PATH)
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.contentType("application/json")
				.header(BulkWriteBenchmarkAccessGuard.HEADER_NAME, TOKEN)
				.content(content))
			.andExpect(status().is4xxClientError());
	}

	private void authenticate(long memberId, MemberRole role) {
		given(redisTemplate.hasKey("SESSION:valid-session")).willReturn(true);
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
}
