package kr.kro.airbob.domain.coupon.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import jakarta.servlet.http.Cookie;
import kr.kro.airbob.common.benchmark.BenchmarkAccessGuard;
import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.domain.auth.filter.SessionAuthFilter;
import kr.kro.airbob.domain.auth.interceptor.AdminAuthInterceptor;
import kr.kro.airbob.domain.coupon.dto.CouponBenchmarkFixture;
import kr.kro.airbob.domain.coupon.repository.CouponRepository;
import kr.kro.airbob.domain.coupon.service.CouponBenchmarkFixtureService;
import kr.kro.airbob.domain.coupon.service.CouponRedisStockManager;
import kr.kro.airbob.domain.coupon.service.CouponTimeProvider;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class CouponBenchmarkFixtureControllerTest {

	private static final String PATH = "/api/v1/admin/coupons/benchmark/fixtures";
	private static final String REQUEST = """
		{"run_id":"run-a","label":"db-round-1","variant":"db","stock":10,"lifetime_seconds":120}
		""";
	@Mock private CouponBenchmarkFixtureService service;
	@Mock private RedisTemplate<String, Object> redisTemplate;
	@Mock private ValueOperations<String, Object> values;
	@Mock private MemberRepository members;
	private MockMvc mvc;

	@BeforeEach
	void setup() {
		var mapper = new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		mvc = MockMvcBuilders.standaloneSetup(new CouponBenchmarkFixtureController(service, new BenchmarkAccessGuard("secret-token")))
			.setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
			.setControllerAdvice(new GlobalExceptionHandler())
			.addFilters(new SessionAuthFilter(redisTemplate, mapper))
			.addInterceptors(new AdminAuthInterceptor(members)).build();
	}

	@Test
	void requiresSession() throws Exception {
		mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(REQUEST)
			.header(BenchmarkAccessGuard.HEADER_NAME, "secret-token")).andExpect(status().isUnauthorized());
		verifyNoInteractions(service);
	}

	@Test
	void requiresActiveAdministrator() throws Exception {
		authenticate(false);
		mvc.perform(post(PATH).cookie(new Cookie("SESSION_ID", "session")).contentType(MediaType.APPLICATION_JSON)
			.content(REQUEST).header(BenchmarkAccessGuard.HEADER_NAME, "secret-token")).andExpect(status().isForbidden());
		verifyNoInteractions(service);
	}

	@Test
	void requiresTokenForEveryOperation() throws Exception {
		authenticate(true);
		mvc.perform(post(PATH).cookie(new Cookie("SESSION_ID", "session"))
			.contentType(MediaType.APPLICATION_JSON).content(REQUEST)).andExpect(status().isForbidden());
		mvc.perform(get(PATH + "/1?run_id=run-a").cookie(new Cookie("SESSION_ID", "session")))
			.andExpect(status().isForbidden());
		mvc.perform(delete(PATH + "/1?run_id=run-a").cookie(new Cookie("SESSION_ID", "session")))
			.andExpect(status().isForbidden());
		verifyNoInteractions(service);
	}

	@Test
	void returnsCreatedIdUsingApplicationJsonConvention() throws Exception {
		authenticate(true);
		given(service.create(any())).willReturn(new CouponBenchmarkFixture.Created(12L, "run-a", "db", 10, LocalDateTime.now()));
		mvc.perform(post(PATH).cookie(new Cookie("SESSION_ID", "session"))
			.header(BenchmarkAccessGuard.HEADER_NAME, "secret-token").contentType(MediaType.APPLICATION_JSON).content(REQUEST))
			.andExpect(status().isCreated()).andExpect(jsonPath("$.data.coupon_id").value(12));
		verify(service).create(new CouponBenchmarkFixture.Create("run-a", "db-round-1", "db", 10, 120));
	}

	@Test
	void rejectsInvalidStockBeforeCreatingCoupon() throws Exception {
		authenticate(true);
		mvc.perform(post(PATH).cookie(new Cookie("SESSION_ID", "session"))
			.header(BenchmarkAccessGuard.HEADER_NAME, "secret-token").contentType(MediaType.APPLICATION_JSON)
			.content(REQUEST.replace("\"stock\":10", "\"stock\":0"))).andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

	@Test
	void createsFixtureComponentsOnlyWithBothBenchmarkGates() {
		var runner = new ApplicationContextRunner()
			.withUserConfiguration(CouponBenchmarkFixtureController.class, CouponBenchmarkFixtureService.class, BenchmarkAccessGuard.class)
			.withBean(CouponRepository.class, () -> mock(CouponRepository.class))
			.withBean(CouponRedisStockManager.class, () -> mock(CouponRedisStockManager.class))
			.withBean(CouponTimeProvider.class, () -> mock(CouponTimeProvider.class))
			.withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class));
		runner.withPropertyValues("benchmark.read-model.enabled=true").run(context -> assertThat(context)
			.doesNotHaveBean(CouponBenchmarkFixtureController.class).doesNotHaveBean(CouponBenchmarkFixtureService.class));
		var profile = runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("coupon-benchmark"));
		profile.withPropertyValues("benchmark.read-model.enabled=false").run(context -> assertThat(context)
			.doesNotHaveBean(CouponBenchmarkFixtureController.class).doesNotHaveBean(CouponBenchmarkFixtureService.class));
		profile.withPropertyValues("benchmark.read-model.enabled=true").run(context -> assertThat(context)
			.hasSingleBean(CouponBenchmarkFixtureController.class).hasSingleBean(CouponBenchmarkFixtureService.class));
	}

	private void authenticate(boolean admin) {
		given(redisTemplate.hasKey("MEMBER_SESSION_ACTIVE:10")).willReturn(true);
		given(redisTemplate.opsForValue()).willReturn(values);
		given(values.get("SESSION:session")).willReturn(10L);
		given(members.existsByIdAndStatusAndRole(10L, MemberStatus.ACTIVE, MemberRole.ADMIN)).willReturn(admin);
	}
}
