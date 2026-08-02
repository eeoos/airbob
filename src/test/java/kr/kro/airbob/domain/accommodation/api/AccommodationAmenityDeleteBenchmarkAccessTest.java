package kr.kro.airbob.domain.accommodation.api;

import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Optional;

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
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest.Measurement;
import kr.kro.airbob.domain.accommodation.dto.AccommodationAmenityDeleteBenchmarkRequest.Variant;
import kr.kro.airbob.domain.accommodation.service.AccommodationAmenityDeleteBenchmarkService;
import kr.kro.airbob.domain.auth.filter.SessionAuthFilter;
import kr.kro.airbob.domain.auth.interceptor.AdminAuthInterceptor;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.repository.MemberRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("AccommodationAmenity 삭제 벤치마크 전체 접근 경계 테스트")
class AccommodationAmenityDeleteBenchmarkAccessTest {

	private static final String TOKEN = "amenity-benchmark-token-1234567890";
	private static final String PATH =
		"/api/v2/admin/benchmarks/bulk-write/accommodation-amenity-delete";

	@Mock private AccommodationAmenityDeleteBenchmarkService benchmarkService;
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
		AccommodationAmenityDeleteBenchmarkController controller =
			new AccommodationAmenityDeleteBenchmarkController(
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
	@DisplayName("세션이 없거나 MEMBER이면 전용 token이 있어도 거부한다")
	void rejectsMissingOrNonAdminSession() throws Exception {
		mockMvc.perform(post(PATH)
				.contentType("application/json")
				.header(BulkWriteBenchmarkAccessGuard.HEADER_NAME, TOKEN)
				.content(request("FULL_REPLACEMENT", "1")))
			.andExpect(status().isUnauthorized());

		authenticate(10L, MemberRole.MEMBER);
		mockMvc.perform(post(PATH)
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.contentType("application/json")
				.header(BulkWriteBenchmarkAccessGuard.HEADER_NAME, TOKEN)
				.content(request("FULL_REPLACEMENT", "1")))
			.andExpect(status().isForbidden());

		then(benchmarkService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("ADMIN이어도 전용 token이 누락되거나 다르면 거부한다")
	void rejectsMissingOrWrongToken() throws Exception {
		authenticate(10L, MemberRole.ADMIN);

		mockMvc.perform(post(PATH)
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.contentType("application/json")
				.content(request("DELETE_ONLY", "1")))
			.andExpect(status().isForbidden());
		mockMvc.perform(post(PATH)
				.cookie(new Cookie("SESSION_ID", "valid-session"))
				.contentType("application/json")
				.header(BulkWriteBenchmarkAccessGuard.HEADER_NAME, "wrong-token")
				.content(request("DELETE_ONLY", "1")))
			.andExpect(status().isForbidden());

		then(benchmarkService).shouldHaveNoInteractions();
	}

	@Test
	@DisplayName("ADMIN 세션과 token이 맞으면 domain ID 없이 Before와 After의 두 measurement를 허용한다")
	void allowsAdminForBothVariantsAndMeasurements() throws Exception {
		authenticate(10L, MemberRole.ADMIN);

		for (Variant variant : Variant.values()) {
			for (Measurement measurement : Measurement.values()) {
			mockMvc.perform(post(PATH)
					.cookie(new Cookie("SESSION_ID", "valid-session"))
					.contentType("application/json")
					.header(BulkWriteBenchmarkAccessGuard.HEADER_NAME, TOKEN)
					.content(request(variant.name(), measurement.name(), "30")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true));
			then(benchmarkService).should().run(
				10L,
				new AccommodationAmenityDeleteBenchmarkRequest(variant, measurement, 30)
			);
			}
		}
	}

	@Test
	@DisplayName("enum casing과 null/음수/초과/비정수 dataset은 service 전에 거부한다")
	void rejectsInvalidRequestBeforeService() throws Exception {
		authenticate(10L, MemberRole.ADMIN);

		String[] invalidBodies = {
			request("FULL_REPLACEMENT", "null"),
			request("FULL_REPLACEMENT", "-1"),
			request("FULL_REPLACEMENT", "101"),
			request("FULL_REPLACEMENT", "1.5"),
			request("FULL_REPLACEMENT", "2147483648"),
			request("FULL_REPLACEMENT", "\"1\""),
			request("full_replacement", "1"),
			request("UNKNOWN", "1"),
			"{\"variant\":\"before\",\"measurement\":\"DELETE_ONLY\",\"dataset_size\":1}",
			"{\"variant\":\"after\",\"measurement\":\"DELETE_ONLY\",\"dataset_size\":1}",
			"{\"variant\":null,\"measurement\":\"DELETE_ONLY\",\"dataset_size\":1}",
			"{\"variant\":\"BEFORE\",\"measurement\":null,\"dataset_size\":1}"
		};
		for (String body : invalidBodies) {
			mockMvc.perform(post(PATH)
					.cookie(new Cookie("SESSION_ID", "valid-session"))
					.contentType("application/json")
					.header(BulkWriteBenchmarkAccessGuard.HEADER_NAME, TOKEN)
					.content(body))
				.andExpect(status().is4xxClientError());
		}

		then(benchmarkService).shouldHaveNoInteractions();
	}

	private void authenticate(long memberId, MemberRole role) {
		given(redisTemplate.hasKey("SESSION:valid-session")).willReturn(true);
		given(redisTemplate.opsForValue()).willReturn(valueOperations);
		given(valueOperations.get("SESSION:valid-session")).willReturn(memberId);
		Member member = Member.builder()
			.id(memberId)
			.role(role)
			.status(MemberStatus.ACTIVE)
			.build();
		given(memberRepository.findById(memberId)).willReturn(Optional.of(member));
	}

	private String request(String measurement, String datasetSize) {
		return request("BEFORE", measurement, datasetSize);
	}

	private String request(String variant, String measurement, String datasetSize) {
		return "{\"variant\":\"" + variant + "\",\"measurement\":\"" + measurement
			+ "\",\"dataset_size\":" + datasetSize + "}";
	}
}
