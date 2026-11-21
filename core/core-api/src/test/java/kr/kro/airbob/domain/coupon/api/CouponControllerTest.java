package kr.kro.airbob.domain.coupon.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.cursor.dto.CouponCampaignCursorData;
import kr.kro.airbob.cursor.dto.CursorData;
import kr.kro.airbob.cursor.dto.CursorRequest;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.cursor.resolver.CouponCampaignCursorParamArgumentResolver;
import kr.kro.airbob.cursor.resolver.CursorParamArgumentResolver;
import kr.kro.airbob.cursor.util.CursorDecoder;
import kr.kro.airbob.cursor.util.CursorEncoder;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.domain.coupon.common.CouponIssuanceStatus;
import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.common.MemberCouponStatus;
import kr.kro.airbob.domain.coupon.dto.CouponResponse;
import kr.kro.airbob.domain.coupon.service.CouponSkipLockedIssueService;
import kr.kro.airbob.domain.coupon.service.CouponQueryService;

@ExtendWith(MockitoExtension.class)
class CouponControllerTest {

	@Mock
	private CouponQueryService couponQueryService;
	@Mock
	private CouponSkipLockedIssueService issueService;

	private MockMvc mockMvc;
	private CursorEncoder cursorEncoder;

	@BeforeEach
	void setUp() {
		UserContext.set(new UserInfo(10L));
		ObjectMapper objectMapper = new ObjectMapper()
			.findAndRegisterModules()
			.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		CursorDecoder cursorDecoder = new CursorDecoder(objectMapper);
		cursorEncoder = new CursorEncoder(objectMapper);
		mockMvc = MockMvcBuilders.standaloneSetup(
			new CouponController(couponQueryService, issueService))
			.setCustomArgumentResolvers(new CurrentMemberIdArgumentResolver(),
				new CursorParamArgumentResolver(cursorDecoder), new CouponCampaignCursorParamArgumentResolver(cursorDecoder))
			.setControllerAdvice(new GlobalExceptionHandler())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
			.build();
	}

	@AfterEach
	void tearDown() {
		UserContext.clear();
	}

	@Test
	@DisplayName("운영 발급 URL은 SKIP LOCKED 서비스로 발급하고 201을 반환한다")
	void issuesCouponWithSkipLocked() throws Exception {
		mockMvc.perform(post("/api/v1/coupons/1/issue"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.success").value(true));

		verify(issueService).issue(1L, 10L);
	}

	@Test
	@DisplayName("쿠폰 캠페인 목록에 현재 발급 상태를 포함한다")
	void findsCouponCampaigns() throws Exception {
		Instant issueStartAt = Instant.parse("2026-08-20T01:00:00Z");
		CouponResponse.CouponInfo info = new CouponResponse.CouponInfo(
			1L,
			"오전 10시 선착순 쿠폰",
			null,
			DiscountType.FIXED_AMOUNT,
			10_000,
			50_000,
			null,
			issueStartAt,
			issueStartAt.plus(10, ChronoUnit.MINUTES),
			issueStartAt,
			issueStartAt.plus(30, ChronoUnit.DAYS),
			100,
			CouponIssuanceStatus.UPCOMING);
		var request = new CursorRequest.CouponCampaignCursorPageRequest(20, null, null);
		when(couponQueryService.findCouponCampaigns(request))
			.thenReturn(new CouponResponse.CouponInfos(List.of(info), new CursorResponse.PageInfo(false, null, 1)));

		mockMvc.perform(get("/api/v1/coupons"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.infos[0].id").value(1L))
			.andExpect(jsonPath("$.data.infos[0].issue_start_at").value("2026-08-20T01:00:00Z"))
			.andExpect(jsonPath("$.data.infos[0].issuance_status").value("UPCOMING"))
			.andExpect(jsonPath("$.data.page_info.has_next").value(false))
			.andExpect(jsonPath("$.data.page_info.next_cursor").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.data.page_info.current_size").value(1));

		verify(couponQueryService).findCouponCampaigns(request);
	}

	@Test
	@DisplayName("로그인 회원이 발급받은 쿠폰 목록을 조회한다")
	void findsMyCoupons() throws Exception {
		Instant usableFrom = Instant.parse("2026-08-20T01:00:00Z");
		CouponResponse.MemberCouponInfo info = new CouponResponse.MemberCouponInfo(
			1L,
			"오전 10시 선착순 쿠폰",
			null,
			DiscountType.FIXED_AMOUNT,
			10_000,
			50_000,
			null,
			usableFrom,
			usableFrom.plus(30, ChronoUnit.DAYS),
			MemberCouponStatus.AVAILABLE);
		var request = new CursorRequest.CursorPageRequest(20, null, null);
		when(couponQueryService.findMyCoupons(10L, request))
			.thenReturn(new CouponResponse.MemberCouponInfos(List.of(info), new CursorResponse.PageInfo(false, null, 1)));

		mockMvc.perform(get("/api/v1/members/me/coupons"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.infos[0].coupon_id").value(1L))
			.andExpect(jsonPath("$.data.infos[0].usable_from").value("2026-08-20T01:00:00Z"))
			.andExpect(jsonPath("$.data.infos[0].status").value("AVAILABLE"))
			.andExpect(jsonPath("$.data.infos[0].member_coupon_id").doesNotExist())
			.andExpect(jsonPath("$.data.infos[0].created_at").doesNotExist())
			.andExpect(jsonPath("$.data.page_info.has_next").value(false))
			.andExpect(jsonPath("$.data.page_info.next_cursor").value(org.hamcrest.Matchers.nullValue()))
			.andExpect(jsonPath("$.data.page_info.current_size").value(1));

		verify(couponQueryService).findMyCoupons(10L, request);
	}

	@Test
	@DisplayName("캠페인 다음 페이지는 발급 시작 시각 커서와 요청 크기를 전달한다")
	void forwardsCampaignCursor() throws Exception {
		LocalDateTime issueStartAt = LocalDateTime.of(2026, 10, 17, 1, 0, 0, 123456000);
		var request = new CursorRequest.CouponCampaignCursorPageRequest(5, 17L, issueStartAt);
		when(couponQueryService.findCouponCampaigns(request))
			.thenReturn(new CouponResponse.CouponInfos(List.of(), new CursorResponse.PageInfo(false, null, 0)));

		mockMvc.perform(get("/api/v1/coupons").param("size", "5")
				.param("cursor", cursorEncoder.encode(new CouponCampaignCursorData(17L, issueStartAt))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.infos").isEmpty())
			.andExpect(jsonPath("$.data.page_info.current_size").value(0));

		verify(couponQueryService).findCouponCampaigns(request);
	}

	@Test
	@DisplayName("내 쿠폰 다음 페이지는 로그인 회원과 보유 쿠폰 커서를 전달한다")
	void forwardsMemberCouponCursor() throws Exception {
		LocalDateTime createdAt = LocalDateTime.of(2026, 10, 17, 1, 0, 0, 654321000);
		var request = new CursorRequest.CursorPageRequest(50, 201L, createdAt);
		when(couponQueryService.findMyCoupons(10L, request))
			.thenReturn(new CouponResponse.MemberCouponInfos(List.of(), new CursorResponse.PageInfo(false, null, 0)));

		mockMvc.perform(get("/api/v1/members/me/coupons").param("size", "50")
				.param("cursor", cursorEncoder.encode(new CursorData(201L, createdAt))))
			.andExpect(status().isOk());

		verify(couponQueryService).findMyCoupons(10L, request);
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/v1/coupons", "/api/v1/members/me/coupons"})
	@DisplayName("두 쿠폰 API는 잘못된 크기와 손상된 커서를 400으로 거부한다")
	void rejectsInvalidPagingParameters(String endpoint) throws Exception {
		for (String size : List.of("0", "-1", "51", "abc")) {
			mockMvc.perform(get(endpoint).param("size", size)).andExpect(status().isBadRequest());
		}
		mockMvc.perform(get(endpoint).param("cursor", "not-a-cursor")).andExpect(status().isBadRequest());
		verifyNoInteractions(couponQueryService, issueService);
	}

	@Test
	@DisplayName("캠페인 커서와 내 쿠폰 커서를 다른 API에 사용할 수 없다")
	void rejectsCrossEndpointCursors() throws Exception {
		LocalDateTime boundary = LocalDateTime.of(2026, 10, 17, 1, 0);
		mockMvc.perform(get("/api/v1/coupons")
				.param("cursor", cursorEncoder.encode(new CursorData(1L, boundary))))
			.andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/members/me/coupons")
				.param("cursor", cursorEncoder.encode(new CouponCampaignCursorData(1L, boundary))))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(couponQueryService, issueService);
	}

	@Test
	@DisplayName("운영 API는 동시성 전략 suffix를 노출하지 않는다")
	void strategySuffixEndpointsAreNotMapped() throws Exception {
		mockMvc.perform(post("/api/v1/coupons/1/issue/lua"))
			.andExpect(status().isNotFound());
		mockMvc.perform(post("/api/v1/coupons/1/issue/lock"))
			.andExpect(status().isNotFound());
		for (String retired : List.of("db", "skip-surrogate-rr", "skip-surrogate-rc", "skip-composite-rr", "skip-composite-rc")) {
			mockMvc.perform(post("/api/v2/coupons/1/issue/" + retired)).andExpect(status().isNotFound());
		}
	}
}
