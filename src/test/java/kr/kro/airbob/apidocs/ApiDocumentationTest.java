package kr.kro.airbob.apidocs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.*;
import org.springdoc.core.properties.*;
import org.springdoc.webmvc.core.configuration.*;
import org.springdoc.webmvc.ui.SwaggerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.data.web.SpringDataWebAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import jakarta.servlet.http.Cookie;
import kr.kro.airbob.common.exception.GlobalExceptionHandler;
import kr.kro.airbob.common.monitoring.QueryCountInterceptor;
import kr.kro.airbob.common.monitoring.QueryCountMetricRecorder;
import kr.kro.airbob.config.WebMvcConfig;
import kr.kro.airbob.config.apidocs.OpenApiConfiguration;
import kr.kro.airbob.cursor.resolver.*;
import kr.kro.airbob.cursor.util.CursorDecoder;
import kr.kro.airbob.domain.auth.filter.SessionAuthFilter;
import kr.kro.airbob.domain.auth.exception.InvalidPasswordException;
import kr.kro.airbob.domain.auth.interceptor.AdminAuthInterceptor;
import kr.kro.airbob.domain.auth.resolver.CurrentMemberIdArgumentResolver;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.member.dto.MemberAdminRequest;
import kr.kro.airbob.domain.member.dto.MemberAdminResponse;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.DuplicatedEmailException;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.exception.MemberRoleChangeNotAllowedException;
import kr.kro.airbob.domain.reservation.dto.ReservationResponse;
import kr.kro.airbob.domain.reservation.entity.Reservation;
import kr.kro.airbob.domain.reservation.exception.ReservationInventoryBusyException;
import kr.kro.airbob.domain.reservation.exception.ReservationNotFoundException;
import kr.kro.airbob.domain.reservation.exception.ReservationPaymentAttemptTooLateException;
import kr.kro.airbob.domain.payment.exception.PaymentAccessDeniedException;
import kr.kro.airbob.domain.accommodation.entity.Accommodation;
import kr.kro.airbob.domain.member.entity.Member;
import static kr.kro.airbob.domain.reservation.ReservationReadTestFixtures.guestDetail;
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse;
import kr.kro.airbob.domain.payment.dto.PaymentOperationAdminResponse;
import kr.kro.airbob.domain.payment.dto.PaymentResponse;
import kr.kro.airbob.domain.payment.entity.Payment;
import kr.kro.airbob.domain.payment.entity.PaymentMethod;
import kr.kro.airbob.domain.payment.entity.PaymentStatus;
import kr.kro.airbob.domain.payment.entity.PaymentTransaction;
import kr.kro.airbob.domain.payment.entity.PaymentOperation;
import kr.kro.airbob.domain.payment.entity.PaymentOperationStatus;
import kr.kro.airbob.domain.payment.entity.PaymentOperationType;
import kr.kro.airbob.domain.payment.exception.PaymentNotFoundException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationNotFoundException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationConflictException;
import kr.kro.airbob.domain.payment.exception.PaymentOperationExecutionFenceUnavailableException;
import kr.kro.airbob.domain.payment.repository.projection.PaymentOperationDetailRow;
import kr.kro.airbob.domain.payment.repository.projection.PaymentOperationManualReviewQueueItem;
import kr.kro.airbob.domain.payment.service.PaymentOperationManualReviewResult;
import kr.kro.airbob.domain.accommodation.dto.AccommodationResponse;
import kr.kro.airbob.domain.accommodation.dto.AddressResponse;
import kr.kro.airbob.domain.accommodation.entity.AccommodationStatus;
import kr.kro.airbob.domain.accommodation.exception.AccommodationNotFoundException;
import kr.kro.airbob.domain.accommodation.exception.PublishingFieldRequiredException;
import kr.kro.airbob.domain.image.dto.ImageResponse;
import kr.kro.airbob.domain.image.exception.InsufficientImageCountException;
import kr.kro.airbob.domain.coupon.dto.CouponResponse;
import kr.kro.airbob.domain.coupon.exception.*;
import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.common.CouponIssuanceStatus;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.accommodation.service.AccommodationCommandService;
import kr.kro.airbob.domain.accommodation.service.AccommodationImageService;
import kr.kro.airbob.domain.accommodation.service.AccommodationQueryService;
import kr.kro.airbob.domain.auth.service.AuthService;
import kr.kro.airbob.domain.commoncode.dto.*;
import kr.kro.airbob.domain.commoncode.exception.*;
import kr.kro.airbob.domain.commoncode.service.CommonCodeAdminService;
import kr.kro.airbob.domain.commoncode.service.CommonCodeService;
import kr.kro.airbob.domain.coupon.service.CouponQueryService;
import kr.kro.airbob.domain.coupon.service.CouponService;
import kr.kro.airbob.domain.coupon.service.CouponSkipLockedIssueService;
import kr.kro.airbob.domain.coupon.service.CouponStockPreparationService;
import kr.kro.airbob.domain.member.service.MemberAdminService;
import kr.kro.airbob.domain.member.service.MemberService;
import kr.kro.airbob.domain.payment.service.PaymentOperationCommandService;
import kr.kro.airbob.domain.payment.service.PaymentOperationManualReviewCommandService;
import kr.kro.airbob.domain.payment.service.PaymentOperationManualReviewQueryService;
import kr.kro.airbob.domain.payment.service.PaymentOperationQueryService;
import kr.kro.airbob.domain.payment.service.PaymentQueryService;
import kr.kro.airbob.domain.recentlyViewed.service.RecentlyViewedService;
import kr.kro.airbob.domain.reservation.service.ReservationHoldCommandService;
import kr.kro.airbob.domain.reservation.service.ReservationQuoteService;
import kr.kro.airbob.domain.reservation.service.ReservationService;
import kr.kro.airbob.domain.review.service.ReviewImageService;
import kr.kro.airbob.domain.review.service.ReviewService;
import kr.kro.airbob.common.exception.BaseException;
import kr.kro.airbob.common.exception.InvalidInputException;
import kr.kro.airbob.domain.review.dto.ReviewResponse;
import kr.kro.airbob.domain.review.entity.ReviewSortType;
import kr.kro.airbob.domain.review.exception.ReviewCreationForbiddenException;
import kr.kro.airbob.domain.review.exception.ReviewAlreadyExistsException;
import kr.kro.airbob.domain.review.exception.ReviewNotFoundException;
import kr.kro.airbob.domain.review.exception.ReviewUpdateForbiddenException;
import kr.kro.airbob.domain.review.exception.ReviewAccessDeniedException;
import kr.kro.airbob.domain.image.exception.ImageUploadException;
import kr.kro.airbob.domain.image.exception.InvalidImageFormatException;
import kr.kro.airbob.domain.image.exception.EmptyImageFileException;
import kr.kro.airbob.domain.image.exception.ImageFileSizeExceededException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import kr.kro.airbob.domain.settlement.service.SettlementService;
import kr.kro.airbob.domain.settlement.dto.SettlementResponse;
import kr.kro.airbob.domain.settlement.entity.Settlement;
import kr.kro.airbob.domain.settlement.entity.SettlementStatus;
import kr.kro.airbob.domain.settlement.exception.*;
import kr.kro.airbob.domain.statistics.service.RevenueStatsService;
import kr.kro.airbob.domain.statistics.dto.RevenueStatsResponse;
import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationRequest;
import kr.kro.airbob.domain.wishlist.dto.WishlistAccommodationResponse;
import kr.kro.airbob.domain.wishlist.dto.WishlistResponse;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccessDeniedException;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccommodationAccessDeniedException;
import kr.kro.airbob.domain.wishlist.exception.WishlistAccommodationDuplicateException;
import kr.kro.airbob.domain.wishlist.exception.WishlistNotFoundException;
import kr.kro.airbob.domain.wishlist.service.WishlistService;
import kr.kro.airbob.search.service.AccommodationSearchService;
import kr.kro.airbob.search.dto.AccommodationSearchResponse;
import kr.kro.airbob.search.document.AccommodationDocument;
import kr.kro.airbob.search.exception.SearchUnavailableException;

@SpringBootTest(classes = ApiDocumentationTest.MvcApplication.class, properties = {
	"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@MockitoBean(types = {
	AccommodationCommandService.class,
	AccommodationImageService.class,
	AccommodationQueryService.class,
	AccommodationSearchService.class,
	AuthService.class,
	CommonCodeAdminService.class,
	CommonCodeService.class,
	CouponQueryService.class,
	CouponService.class,
	CouponSkipLockedIssueService.class,
	CouponStockPreparationService.class,
	MemberAdminService.class,
	MemberService.class,
	PaymentOperationCommandService.class,
	PaymentOperationManualReviewCommandService.class,
	PaymentOperationManualReviewQueryService.class,
	PaymentOperationQueryService.class,
	PaymentQueryService.class,
	RecentlyViewedService.class,
	ReservationHoldCommandService.class,
	ReservationQuoteService.class,
	ReservationService.class,
	RevenueStatsService.class,
	ReviewImageService.class,
	ReviewService.class,
	SettlementService.class,
	WishlistService.class,
	MemberRepository.class, QueryCountMetricRecorder.class
})
class ApiDocumentationTest {
	@Autowired MockMvc mvc;
	@Autowired ObjectMapper mapper;
	@Autowired ApplicationContext context;
	@Autowired RequestMappingHandlerMapping mappings;
	@Autowired SessionAuthFilter authFilter;
	@MockitoBean RedisTemplate<String, Object> redis;

	private static final UUID DOCUMENTATION_OPERATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000040");
	private static final UUID DOCUMENTATION_ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
	private static final Cookie SESSION = new Cookie("SESSION_ID", "documentation-session");

	@BeforeEach
	void session() {
		ValueOperations<String, Object> values = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(values);
		when(values.get("SESSION:documentation-session")).thenReturn(10L);
		when(redis.hasKey("MEMBER_SESSION_ACTIVE:10")).thenReturn(true);
	}

	@Test
	void exportValidatedContractsWithEveryRealControllerMapping() throws Exception {
		Set<String> actual = new HashSet<>();
		mappings.getHandlerMethods().forEach((mapping, handler) -> mapping.getPatternValues().stream()
			.filter(path -> path.startsWith("/api/v1/"))
			.forEach(path -> mapping.getMethodsCondition().getMethods().forEach(method -> actual.add(method + " " + path))));
		Set<String> documented = new HashSet<>();
		Set<String> operationIds = new HashSet<>();
		for (String group : new String[] {"client", "admin"}) {
			JsonNode spec = specification(group);
			mvc.perform(get("/v3/api-docs.yaml/" + group)).andExpect(status().isOk());
			assertThat(spec.path("openapi").asText()).startsWith("3.1.");
			assertThat(spec.at("/components/securitySchemes/sessionCookie/name").asText()).isEqualTo("SESSION_ID");
			assertLocalReferences(spec, spec);
			assertSchemaExamples(spec, spec.path("components").path("schemas"));
			var paths = spec.path("paths").fields();
			while (paths.hasNext()) {
				var path = paths.next();
				assertThat(path.getKey().startsWith("/api/v1/admin/")).isEqualTo(group.equals("admin"));
				var methods = path.getValue().fields();
				while (methods.hasNext()) {
					var method = methods.next();
					JsonNode operation = method.getValue();
					assertThat(documented.add(method.getKey().toUpperCase() + " " + path.getKey())).isTrue();
					assertThat(operation.path("summary").asText()).isNotBlank();
					assertThat(operation.path("description").asText()).isNotBlank();
					assertThat(operationIds.add(operation.path("operationId").asText())).isTrue();
					assertSecurityMatchesRealFilter(path.getKey(), method.getKey(), operation);
					for (JsonNode parameter : operation.path("parameters")) {
						if (parameter.path("in").asText().equals("query")) {
							assertThat(parameter.path("name").asText()).as(path.getKey()).isNotIn("memberId", "hostId", "actorMemberId", "cursorData");
						}
					}
					for (JsonNode response : operation.path("responses")) {
						JsonNode media = response.path("content").path("application/json");
						for (JsonNode example : media.path("examples")) {
							validateInstance(spec, media.path("schema"), example.path("value"));
						}
					}
				}
			}
			JsonNode meta = new ObjectMapper().readTree(getClass().getResourceAsStream("/apidocs/openapi-3.1-schema.json"));
			assertThat(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(meta).validate(spec)).isEmpty();
			Path out = Path.of(System.getProperty("airbob.openapi.output", "build/openapi"));
			Files.createDirectories(out);
			ObjectMapper yaml = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).disable(YAMLGenerator.Feature.SPLIT_LINES));
			Files.writeString(out.resolve(group + ".yaml"), "# Generated by ./gradlew updateOpenApi. Edit Java sources, not this file.\n"
				+ yaml.writeValueAsString(sorted(spec)));
		}
		assertThat(documented).containsExactlyInAnyOrderElementsOf(actual);
	}

	@Test
	void documentsCursorAndAsyncPaymentContracts() throws Exception {
		JsonNode spec = specification("client");
		JsonNode coupons = spec.path("paths").path("/api/v1/coupons").path("get");
		Map<String, JsonNode> parameters = new TreeMap<>();
		coupons.path("parameters").forEach(p -> parameters.put(p.path("name").asText(), p));
		assertThat(parameters.keySet()).containsExactly("cursor", "size");
		assertThat(parameters.get("size").at("/schema/default").asInt()).isEqualTo(20);
		assertThat(parameters.get("size").at("/schema/maximum").asInt()).isEqualTo(50);
		JsonNode checkout = spec.path("paths").path("/api/v1/reservations").path("post");
		assertThat(checkout.path("parameters").findValuesAsText("name")).contains("Idempotency-Key");
		assertThat(spec.path("paths").path("/api/v1/payments/confirm").path("post").path("responses").has("202")).isTrue();
		assertThat(spec.path("paths").path("/api/v1/reservations/{reservationUid}").path("post")
			.at("/responses/202/headers/Location").isMissingNode()).isFalse();
		assertThat(spec.toString()).doesNotContain("quote_expires_at", "Bearer", "bearerAuth");
	}

	@Test
	void generatedSchemaMatchesRealMvcResponses() throws Exception {
		JsonNode spec = specification("client");
		when(context.getBean(PaymentOperationCommandService.class).requestConfirmation(any(), anyLong()))
			.thenReturn(new PaymentOperationResponse.Accepted(UUID.fromString("00000000-0000-0000-0000-000000000001"),
				PaymentOperationResponse.Status.PENDING, "/api/v1/payment-operations/00000000-0000-0000-0000-000000000001"));
		String body = mvc.perform(post("/api/v1/payments/confirm").cookie(SESSION).contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"payment_key":"example-payment-key","order_id":"00000000-0000-0000-0000-000000000002",
				 "amount":10000,"payment_attempt_id":"00000000-0000-0000-0000-000000000003"}
				""")).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, "/api/v1/payments/confirm", "post", "202", body);
		assertThat(mapper.readTree(body).path("data").has("operation_id")).isTrue();
		assertThat(mapper.readTree(body).has("error")).isFalse();

		String issued = mvc.perform(post("/api/v1/coupons/1/issue").cookie(SESSION))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, "/api/v1/coupons/{couponId}/issue", "post", "201", issued);
		assertThat(mapper.readTree(issued)).isEqualTo(mapper.readTree("{\"success\":true}"));

		String invalid = mvc.perform(get("/api/v1/coupons").cookie(SESSION).param("size", "51"))
			.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, "/api/v1/coupons", "get", "400", invalid);
		assertThat(mapper.readTree(invalid).at("/error/code").asText()).isEqualTo("C006");
	}

	@Test
	void optionalFieldsMatchHistoricalCouponsAndZeroPriceReservations() throws Exception {
		Instant now = Instant.parse("2027-01-01T00:00:00Z");
		when(context.getBean(CouponQueryService.class).findCouponCampaigns(any())).thenReturn(new CouponResponse.CouponInfos(
			List.of(new CouponResponse.CouponInfo(1L, "과거 쿠폰", null, DiscountType.FIXED_AMOUNT, 1000,
				null, null, now, now.plusSeconds(86400), now, now.plusSeconds(86400), null, CouponIssuanceStatus.OPEN)),
			new CursorResponse.PageInfo(false, null, 1)));
		String coupons = mvc.perform(get("/api/v1/coupons").cookie(SESSION)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		JsonNode spec = specification("client");
		validateResponse(spec, "/api/v1/coupons", "get", "200", coupons);
		assertThat(mapper.readTree(coupons).at("/data/infos/0/total_quantity").isNull()).isTrue();
		when(context.getBean(ReservationService.class).createPendingReservation(any(), anyLong(), any()))
			.thenReturn(new ReservationResponse.Ready("00000000-0000-0000-0000-000000000020", "숙소", LocalDate.parse("2027-01-10"),
				LocalDate.parse("2027-01-12"), 2, 10000L, 10000L, 0L, "KRW", ReservationStatus.CONFIRMED,
				false, false, null, now, "docs@example.invalid", "여행자"));
		String ready = mvc.perform(post("/api/v1/reservations").cookie(SESSION).header("Idempotency-Key", "checkout-00000001")
			.contentType(MediaType.APPLICATION_JSON).content("{\"quote_uid\":\"00000000-0000-0000-0000-000000000010\"}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, "/api/v1/reservations", "post", "200", ready);
		assertThat(mapper.readTree(ready).at("/data/payment_required").asBoolean()).isFalse();
	}

	@Test
	void reservationPaymentReferencesAcceptBothPendingAndPaidShapes() throws Exception {
		JsonNode spec = specification("client");
		JsonNode guestPayment = spec.at("/components/schemas/ReservationResponseGuestDetail/properties/payment");
		JsonNode hostPayment = spec.at("/components/schemas/ReservationResponseHostDetail/properties/payment");
		validateInstance(spec, guestPayment, mapper.readTree("null"));
		validateInstance(spec, hostPayment, mapper.readTree("null"));
		validateInstance(spec, guestPayment, mapper.readTree("{\"method\":\"카드\",\"total_amount\":10000,\"status\":\"DONE\",\"approved_at\":\"2027-01-01T00:00:00Z\"}"));
		validateInstance(spec, hostPayment, mapper.readTree("{\"total_amount\":10000}"));
	}

	@Test
	void draftAccommodationPreservesNullableAddressAndPolicyFields() throws Exception {
		AccommodationResponse.HostDetail draft = mapper.readValue(
			"{\"id\":101,\"address\":{},\"policy\":{},\"amenities\":[],\"images\":[]}", AccommodationResponse.HostDetail.class);
		when(context.getBean(AccommodationQueryService.class).findHostAccommodationDetail(101L, 10L)).thenReturn(draft);
		String body = mvc.perform(get("/api/v1/profile/host/accommodations/101").cookie(SESSION))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(specification("client"), "/api/v1/profile/host/accommodations/{accommodationId}", "get", "200", body);
		assertThat(mapper.readTree(body).at("/data/address/city").isNull()).isTrue();
	}

	@Test
	void uploadsAndSearchDescribeBrowserInputs() throws Exception {
		JsonNode spec = specification("client");
		for (String path : List.of("/api/v1/accommodations/{accommodationId}/images", "/api/v1/reviews/{reviewId}/images")) {
			JsonNode operation = spec.path("paths").path(path).path("post");
			assertThat(operation.path("parameters").findValuesAsText("name")).doesNotContain("images");
			assertThat(operation.path("requestBody").path("content").path("multipart/form-data")
				.at("/schema/properties/images/items/format").asText()).isEqualTo("binary");
		}
		JsonNode search = spec.path("paths").path("/api/v1/search/accommodations").path("get");
		assertThat(search.path("parameters").findValuesAsText("name"))
			.contains("destination", "topLeftLat", "minPrice", "checkIn", "page", "size")
			.doesNotContain("mapBounds", "searchRequest", "pageable", "valid", "validDateRange", "totalGuests", "sort");
	}

	@Test
	void loginAndAuthorizationUseRealCookiesAndGuards() throws Exception {
		AuthService authService = context.getBean(AuthService.class);
		when(authService.login("docs@example.invalid", "example-password"))
			.thenReturn("documentation-session");
		var login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"docs@example.invalid\",\"password\":\"example-password\"}"))
			.andExpect(status().isOk()).andReturn().getResponse();
		assertThat(login.getCookie("SESSION_ID").isHttpOnly()).isTrue();
		assertThat(login.getCookie("SESSION_ID").getMaxAge()).isEqualTo(3600);
		JsonNode client = specification("client");
		JsonNode logoutContract = client.path("paths").path("/api/v1/auth/logout").path("post");
		assertThat(logoutContract.path("parameters").isEmpty()).isTrue();
		assertThat(logoutContract.has("requestBody")).isFalse();
		assertThat(logoutContract.path("security").path(0).has("sessionCookie")).isTrue();
		mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
		String logout = mvc.perform(post("/api/v1/auth/logout").cookie(login.getCookie("SESSION_ID")))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(client, "/api/v1/auth/logout", "post", "200", logout);
		verify(authService).logout("documentation-session");
		String denied = mvc.perform(get("/api/v1/coupons")).andExpect(status().isUnauthorized())
			.andReturn().getResponse().getContentAsString();
		validateResponse(specification("client"), "/api/v1/coupons", "get", "401", denied);
		String forbidden = mvc.perform(get("/api/v1/admin/common-code-groups").cookie(SESSION))
			.andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
		validateResponse(specification("admin"), "/api/v1/admin/common-code-groups", "get", "403", forbidden);
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
	}

	@Test
	void authenticationErrorsMatchDocumentedCodesAndStatuses() throws Exception {
		JsonNode client = specification("client");
		AuthService authService = context.getBean(AuthService.class);
		when(authService.login("docs@example.invalid", "wrong-password")).thenThrow(new InvalidPasswordException());
		String wrongPassword = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"docs@example.invalid\",\"password\":\"wrong-password\"}"))
			.andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
		validateResponse(client, "/api/v1/auth/login", "post", "401", wrongPassword);
		assertThat(mapper.readTree(wrongPassword).at("/error/code").asText()).isEqualTo("M003");

		when(authService.login("missing@example.invalid", "example-password")).thenThrow(new MemberNotFoundException());
		String missingMember = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"missing@example.invalid\",\"password\":\"example-password\"}"))
			.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		validateResponse(client, "/api/v1/auth/login", "post", "404", missingMember);
		assertThat(mapper.readTree(missingMember).at("/error/code").asText()).isEqualTo("M001");

		when(context.getBean(MemberService.class).getMemberInfo(10L)).thenThrow(new MemberNotFoundException());
		String missingProfile = mvc.perform(get("/api/v1/auth/me").cookie(SESSION))
			.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		validateResponse(client, "/api/v1/auth/me", "get", "404", missingProfile);
		assertThat(mapper.readTree(missingProfile).at("/error/code").asText()).isEqualTo("M001");
	}

	@Test
	void authenticationProfileDocumentsNullableLegacyFields() throws Exception {
		when(context.getBean(MemberService.class).getMemberInfo(10L))
			.thenReturn(new MemberResponse.MeInfo(10L, null, null));
		String profile = mvc.perform(get("/api/v1/auth/me").cookie(SESSION))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(specification("client"), "/api/v1/auth/me", "get", "200", profile);
		assertThat(mapper.readTree(profile).at("/data/email").isNull()).isTrue();
		assertThat(mapper.readTree(profile).at("/data/nickname").isNull()).isTrue();
	}

	@Test
	void memberSignupDocumentsNullableThumbnailAndDoesNotCreateSession() throws Exception {
		JsonNode spec = specification("client");
		JsonNode operation = spec.path("paths").path("/api/v1/members").path("post");
		assertThat(operation.path("security").isEmpty()).isTrue();
		assertThat(spec.at("/components/schemas/MemberRequestSignup/properties/email/minLength").asInt()).isEqualTo(1);
		String request = """
			{"nickname":"여행자","email":"docs@example.invalid","password":"example-password","thumbnail_image_url":null}
			""";
		validateInstance(spec, spec.at("/components/schemas/MemberRequestSignup"), mapper.readTree(request));
		var response = mvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON).content(request))
			.andExpect(status().isCreated()).andReturn().getResponse();
		validateResponse(spec, "/api/v1/members", "post", "201", response.getContentAsString());
		assertThat(mapper.readTree(response.getContentAsString())).isEqualTo(mapper.readTree("{\"success\":true}"));
		assertThat(response.getHeader("Set-Cookie")).isNull();
		verify(context.getBean(MemberService.class)).createMember(argThat(signup ->
			signup.getEmail().equals("docs@example.invalid") && signup.getThumbnailImageUrl() == null));
	}

	@Test
	void memberSignupValidationAndDuplicateEmailMatchDocumentedErrors() throws Exception {
		JsonNode spec = specification("client");
		MemberService service = context.getBean(MemberService.class);
		ObjectNode valid = mapper.createObjectNode().put("nickname", "여행자")
			.put("email", "docs@example.invalid").put("password", "example-password");
		String longEmail = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(63);
		for (JsonNode invalid : List.of(mapper.createObjectNode(),
			valid.deepCopy().put("nickname", "a".repeat(21)), valid.deepCopy().put("nickname", "   "),
			valid.deepCopy().put("password", "short"), valid.deepCopy().put("password", "a".repeat(21)),
			valid.deepCopy().put("email", ""), valid.deepCopy().put("email", "invalid-email"), valid.deepCopy().put("email", longEmail),
			valid.deepCopy().put("thumbnail_image_url", "a".repeat(256)))) {
			var response = mvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON).content(invalid.toString()))
				.andExpect(status().isBadRequest()).andReturn().getResponse();
			assertDocumentedError(spec, "/api/v1/members", "post", "400", "C001", response.getContentAsString());
			for (JsonNode error : mapper.readTree(response.getContentAsString()).at("/error/errors")) {
				if (error.path("field").asText().equals("password")) {
					assertThat(error.path("value").asText()).isEqualTo("[REDACTED]");
				}
			}
		}
		verifyNoInteractions(service);
		doThrow(new DuplicatedEmailException()).when(service).createMember(any());
		var duplicate = mvc.perform(post("/api/v1/members").contentType(MediaType.APPLICATION_JSON).content(valid.toString()))
			.andExpect(status().isConflict()).andReturn().getResponse();
		assertDocumentedError(spec, "/api/v1/members", "post", "409", "M002", duplicate.getContentAsString());
	}

	@Test
	void memberRoleChangeUsesSessionGuardsAndDocumentsOnlyTargetId() throws Exception {
		JsonNode spec = specification("admin");
		String path = "/api/v1/admin/members/{memberId}/role";
		JsonNode operation = spec.path("paths").path(path).path("patch");
		assertThat(operation.path("parameters").findValuesAsText("name")).containsExactly("memberId");
		assertThat(operation.path("parameters").path(0).path("in").asText()).isEqualTo("path");
		assertThat(operation.path("security").path(0).has("sessionCookie")).isTrue();
		String request = "{\"role\":\"ADMIN\",\"reason\":\"  운영 관리자 지정  \"}";
		MemberAdminService service = context.getBean(MemberAdminService.class);
		var unauthenticated = mvc.perform(patch(path, 20).contentType(MediaType.APPLICATION_JSON).content(request))
			.andExpect(status().isUnauthorized()).andReturn().getResponse();
		assertDocumentedError(spec, path, "patch", "401", "M004", unauthenticated.getContentAsString());
		var forbidden = mvc.perform(patch(path, 20).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
			.andExpect(status().isForbidden()).andReturn().getResponse();
		assertDocumentedError(spec, path, "patch", "403", "M006", forbidden.getContentAsString());
		verifyNoInteractions(service);

		when(context.getBean(MemberRepository.class).existsByIdAndStatusAndRole(10L, MemberStatus.ACTIVE, MemberRole.ADMIN))
			.thenReturn(true);
		when(service.changeRole(10L, 20L, new MemberAdminRequest.ChangeRole(MemberRole.ADMIN, "운영 관리자 지정")))
			.thenReturn(new MemberAdminResponse.RoleChanged(20L, MemberRole.ADMIN));
		String body = mvc.perform(patch(path, 20).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "patch", "200", body);
		assertThat(mapper.readTree(body)).isEqualTo(mapper.readTree("{\"success\":true,\"data\":{\"member_id\":20,\"role\":\"ADMIN\"}}"));
		verify(service).changeRole(10L, 20L, new MemberAdminRequest.ChangeRole(MemberRole.ADMIN, "운영 관리자 지정"));
	}

	@Test
	void memberRoleChangeValidationAndBusinessErrorsMatchSpecification() throws Exception {
		JsonNode spec = specification("admin");
		String path = "/api/v1/admin/members/{memberId}/role";
		when(context.getBean(MemberRepository.class).existsByIdAndStatusAndRole(10L, MemberStatus.ACTIVE, MemberRole.ADMIN))
			.thenReturn(true);
		for (String invalid : List.of("{}", "{\"role\":\"UNKNOWN\",\"reason\":\"역할 변경\"}",
			"{\"role\":\"ADMIN\",\"reason\":\"   \"}", "{\"role\":\"ADMIN\",\"reason\":\"" + "a".repeat(256) + "\"}")) {
			var response = mvc.perform(patch(path, 20).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(invalid))
				.andExpect(status().isBadRequest()).andReturn().getResponse();
			assertDocumentedError(spec, path, "patch", "400", "C001", response.getContentAsString());
		}
		String request = "{\"role\":\"MEMBER\",\"reason\":\"관리자 권한 회수\"}";
		var invalidId = mvc.perform(patch(path, "invalid").cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
			.andExpect(status().isBadRequest()).andReturn().getResponse();
		assertDocumentedError(spec, path, "patch", "400", "C004", invalidId.getContentAsString());
		MemberAdminService service = context.getBean(MemberAdminService.class);
		verifyNoInteractions(service);
		doThrow(new MemberNotFoundException()).when(service).changeRole(anyLong(), anyLong(), any());
		var missing = mvc.perform(patch(path, 20).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
			.andExpect(status().isNotFound()).andReturn().getResponse();
		assertDocumentedError(spec, path, "patch", "404", "M001", missing.getContentAsString());
		doThrow(new MemberRoleChangeNotAllowedException()).when(service).changeRole(anyLong(), anyLong(), any());
		var selfDemotion = mvc.perform(patch(path, 10).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
			.andExpect(status().isConflict()).andReturn().getResponse();
		assertDocumentedError(spec, path, "patch", "409", "M007", selfDemotion.getContentAsString());
	}

	@Test
	void accommodationPatchContractAcceptsNullFieldsAndLocalTimes() throws Exception {
		JsonNode spec = specification("client");
		JsonNode schema = spec.at("/components/schemas/AccommodationRequestUpdate");
		ObjectNode omitted = mapper.createObjectNode();
		schema.path("properties").fieldNames().forEachRemaining(omitted::putNull);
		validateInstance(spec, schema, omitted);
		for (String invalid : List.of("{\"base_price\":0}", "{\"amenity_infos\":[{\"name\":\"WIFI\",\"count\":-1}]}",
			"{\"occupancy_policy_info\":{\"max_occupancy\":0,\"infant_occupancy\":0,\"pet_occupancy\":0}}",
			"{\"occupancy_policy_info\":{\"max_occupancy\":1,\"infant_occupancy\":-1,\"pet_occupancy\":0}}",
			"{\"occupancy_policy_info\":{\"max_occupancy\":1,\"infant_occupancy\":0,\"pet_occupancy\":-1}}")) {
			ObjectNode root = ((ObjectNode)schema).deepCopy();
			root.set("components", spec.path("components"));
			assertThat(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root)
				.validate(mapper.readTree(invalid))).as(invalid).isNotEmpty();
		}
		String path = "/api/v1/accommodations/{accommodationId}";
		String unchanged = mvc.perform(patch(path, 101).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(omitted.toString()))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "patch", "200", unchanged);
		String times = """
			{"name":"가상 숙소","description":"첫째 줄\\n둘째 줄","currency":"KRW","check_in_time":"15:00","check_out_time":"11:00:00"}
			""";
		validateInstance(spec, schema, mapper.readTree(times));
		mvc.perform(patch(path, 101).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(times)).andExpect(status().isOk());
		verify(context.getBean(AccommodationCommandService.class)).updateAccommodation(eq(101L), argThat(request ->
			java.time.LocalTime.of(15, 0).equals(request.checkInTime()) && java.time.LocalTime.of(11, 0).equals(request.checkOutTime())), eq(10L));
	}

	@Test
	void accommodationAvailabilityHasOnlyIdAndReturnsExclusiveDateBoundaries() throws Exception {
		JsonNode spec = specification("client");
		String path = "/api/v1/accommodations/{accommodationId}/availability";
		JsonNode operation = spec.path("paths").path(path).path("get");
		assertThat(operation.path("parameters").findValuesAsText("name")).containsExactly("accommodationId");
		assertThat(operation.has("requestBody")).isFalse();
		assertThat(operation.path("security").isEmpty()).isTrue();
		when(context.getBean(AccommodationQueryService.class).findAccommodationAvailability(101L))
			.thenReturn(new AccommodationResponse.Availability(LocalDate.parse("2027-01-01"), LocalDate.parse("2027-04-01"),
				List.of(new AccommodationResponse.UnavailableDateRange(LocalDate.parse("2027-01-10"), LocalDate.parse("2027-01-12")))));
		String body = mvc.perform(get(path, 101)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "get", "200", body);
		assertThat(mapper.readTree(body).at("/data/unavailable_ranges/0/end_date_exclusive").asText()).isEqualTo("2027-01-12");
		assertThat(mapper.readTree(body).at("/data/booking_window_end_exclusive").asText()).isEqualTo("2027-04-01");
	}

	@Test
	void accommodationDetailDocumentsUnsetCurrencyAndLegacyHostNickname() throws Exception {
		var detail = mapper.readValue("""
			{"id":101,"name":"가상 숙소","description":"숙소 설명","type":"APARTMENT","base_price":100000,
			 "currency":null,"check_in_time":"15:00:00","check_out_time":"11:00:00","time_zone_id":"Asia/Seoul",
			 "is_in_wishlist":false,"address_summary":{"country":"대한민국","city":"서울","state":null,"district":null},
			 "coordinate":{"latitude":37.5,"longitude":127.0},"host":{"id":10,"nickname":null,"thumbnail_image_url":null},
			 "policy":{"max_occupancy":4,"infant_occupancy":1,"pet_occupancy":0},"amenities":[],"images":[],
			 "review_summary":{"total_count":0,"average_rating":0}}
			""", AccommodationResponse.DetailInfo.class);
		when(context.getBean(AccommodationQueryService.class).findAccommodation(101L, null)).thenReturn(detail);
		String body = mvc.perform(get("/api/v1/accommodations/101")).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		validateResponse(specification("client"), "/api/v1/accommodations/{accommodationId}", "get", "200", body);
		assertThat(mapper.readTree(body).at("/data/currency").isNull()).isTrue();
		assertThat(mapper.readTree(body).at("/data/host/nickname").isNull()).isTrue();
		assertThat(mapper.readTree(body).at("/data/is_in_wishlist").asBoolean()).isFalse();
	}

	@Test
	void accommodationHostListDocumentsDraftNullsAndOnlyUserSuppliedFilters() throws Exception {
		var draft = new AccommodationResponse.HostAccommodationInfo(101L, null, null, AccommodationStatus.DRAFT, null,
			new AddressResponse.AddressSummaryInfo(null, null, null, null), null);
		when(context.getBean(AccommodationQueryService.class).findMyAccommodations(eq(10L), any(), eq(AccommodationStatus.DRAFT)))
			.thenReturn(new AccommodationResponse.HostAccommodationInfos(List.of(draft), new CursorResponse.PageInfo(false, null, 1)));
		String path = "/api/v1/profile/host/accommodations";
		String body = mvc.perform(get(path).cookie(SESSION).param("status", "DRAFT")).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		JsonNode spec = specification("client");
		validateResponse(spec, path, "get", "200", body);
		assertThat(spec.path("paths").path(path).path("get").path("parameters").findValuesAsText("name"))
			.containsExactlyInAnyOrder("status", "cursor", "size");
		String invalid = mvc.perform(get(path).cookie(SESSION).param("status", "UNKNOWN"))
			.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "get", "400", "C004", invalid);
	}

	@Test
	void accommodationCreationAndPublishingDocumentActualInputsAndErrors() throws Exception {
		JsonNode spec = specification("client");
		AccommodationCommandService service = context.getBean(AccommodationCommandService.class);
		when(service.createAccommodation(10L)).thenReturn(new AccommodationResponse.Create(101L));
		String created = mvc.perform(post("/api/v1/accommodations").cookie(SESSION)).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		validateResponse(spec, "/api/v1/accommodations", "post", "201", created);
		assertThat(spec.path("paths").path("/api/v1/accommodations").path("post").has("requestBody")).isFalse();
		String publishPath = "/api/v1/accommodations/{accommodationId}/publish";
		assertThat(spec.path("paths").path(publishPath).path("patch").has("requestBody")).isFalse();
		doThrow(new PublishingFieldRequiredException("addressInfo", "주소 정보가 누락되었습니다."))
			.when(service).publishAccommodation(101L, 10L);
		String missingFields = mvc.perform(patch(publishPath, 101).cookie(SESSION)).andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, publishPath, "patch", "400", "A009", missingFields);
		assertThat(mapper.readTree(missingFields).at("/error/errors/0/field").asText()).isEqualTo("addressInfo");
		assertThat(mapper.readTree(missingFields).at("/error/errors/0/reason").asText()).isEqualTo("주소 정보가 누락되었습니다.");
		doThrow(new InsufficientImageCountException("이미지가 최소 1장 필요합니다."))
			.when(service).publishAccommodation(101L, 10L);
		String noImages = mvc.perform(patch(publishPath, 101).cookie(SESSION)).andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, publishPath, "patch", "400", "A006", noImages);
		doThrow(new AccommodationNotFoundException()).when(service).updateAccommodation(eq(101L), any(), eq(10L));
		String inaccessible = mvc.perform(patch("/api/v1/accommodations/101").cookie(SESSION)
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, "/api/v1/accommodations/{accommodationId}", "patch", "404", "A001", inaccessible);
	}

	@Test
	void accommodationMultipartResponsesIncludeFileIdsAndParserErrors() throws Exception {
		JsonNode spec = specification("client");
		String path = "/api/v1/accommodations/{accommodationId}/images";
		AccommodationImageService service = context.getBean(AccommodationImageService.class);
		var missing = mvc.perform(multipart(path, 101).cookie(SESSION)).andExpect(status().isBadRequest()).andReturn().getResponse();
		assertDocumentedError(spec, path, "post", "400", "C001", missing.getContentAsString());
		var unsupported = mvc.perform(post(path, 101).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnsupportedMediaType()).andReturn().getResponse();
		assertDocumentedError(spec, path, "post", "415", "C011", unsupported.getContentAsString());
		verifyNoInteractions(service);
		when(service.uploadImages(eq(101L), anyList(), eq(10L)))
			.thenReturn(new ImageResponse.ImageUploadResult(List.of(new ImageResponse.ImageInfo(301L, "https://example.invalid/room.png"))));
		var file = new MockMultipartFile("images", "room.png", "image/png", new byte[] {1});
		String uploaded = mvc.perform(multipart(path, 101).file(file).cookie(SESSION)).andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "post", "201", uploaded);
		assertThat(mapper.readTree(uploaded).at("/data/uploaded_images/0/id").asLong()).isEqualTo(301L);
		doThrow(new org.springframework.web.multipart.MaxUploadSizeExceededException(10 * 1024 * 1024))
			.when(service).uploadImages(eq(101L), anyList(), eq(10L));
		String oversized = mvc.perform(multipart(path, 101).file(file).cookie(SESSION)).andExpect(status().isPayloadTooLarge())
			.andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "post", "413", "I009", oversized);
	}

	@Test
	void reservationQuoteSchemaAcceptsOptionalCouponAndRejectsNonPositiveInputs() throws Exception {
		JsonNode spec = specification("client");
		JsonNode request = spec.at("/components/schemas/ReservationRequestQuote");
		validateInstance(spec, request, mapper.readTree("""
			{"accommodation_id":101,"check_in_date":"2027-01-10","check_out_date":"2027-01-12","guest_count":2,"coupon_id":null}
			"""));
		for (String field : List.of("accommodation_id", "guest_count", "coupon_id")) {
			ObjectNode fieldSchema = ((ObjectNode)request.path("properties").path(field)).deepCopy();
			assertThat(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
				.getSchema(fieldSchema).validate(mapper.readTree("0"))).isNotEmpty();
		}
		Instant now = Instant.parse("2027-01-01T00:00:00Z");
		when(context.getBean(ReservationQuoteService.class).createQuote(any(), anyLong()))
			.thenReturn(new ReservationResponse.Quote(UUID.fromString("00000000-0000-0000-0000-000000000010"),
				101L, "숙소", LocalDate.parse("2027-01-10"), LocalDate.parse("2027-01-12"),
				2, 100000L, 2L, 200000L, 0L, 200000L, "KRW", true, false, now));
		String body = mvc.perform(post("/api/v1/reservation-quotes").cookie(SESSION)
			.contentType(MediaType.APPLICATION_JSON).content("""
				{"accommodation_id":101,"check_in_date":"2027-01-10","check_out_date":"2027-01-12","guest_count":2}
				""")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, "/api/v1/reservation-quotes", "post", "201", body);
		assertThat(mapper.readTree(body).at("/data/inventory_held").asBoolean()).isFalse();
		assertThat(body).doesNotContain("quote_expires_at");
	}

	@Test
	void checkoutDocumentsIdempotencyHeaderCurrentStateAndRetryAfter() throws Exception {
		JsonNode spec = specification("client");
		String path = "/api/v1/reservations";
		JsonNode operation = spec.path("paths").path(path).path("post");
		JsonNode key = operation.path("parameters").path(0);
		assertThat(key.path("name").asText()).isEqualTo("Idempotency-Key");
		assertThat(key.path("required").asBoolean()).isTrue();
		assertThat(key.at("/schema/minLength").asInt()).isEqualTo(8);
		assertThat(key.at("/schema/maxLength").asInt()).isEqualTo(128);
		String request = "{\"quote_uid\":\"00000000-0000-0000-0000-000000000010\",\"request_message\":null}";
		validateInstance(spec, spec.at("/components/schemas/ReservationRequestCheckout"), mapper.readTree(request));
		String missingHeader = mvc.perform(post(path).cookie(SESSION).contentType(MediaType.APPLICATION_JSON)
			.content(request)).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "post", "400", "C001", missingHeader);

		Instant now = Instant.parse("2027-01-01T00:00:00Z");
		Reservation expired = documentationPendingReservation(now.minusSeconds(1));
		when(context.getBean(ReservationService.class).createPendingReservation(any(), anyLong(), any()))
			.thenReturn(ReservationResponse.Ready.from(expired, now));
		String replay = mvc.perform(post(path).cookie(SESSION).header("Idempotency-Key", "checkout-00000001")
			.contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "post", "200", replay);
		assertThat(mapper.readTree(replay).at("/data/status").asText()).isEqualTo("EXPIRED");
		assertThat(mapper.readTree(replay).at("/data/payment_allowed").asBoolean()).isFalse();
		assertThat(mapper.readTree(replay).at("/data/hold_expires_at").asText()).isEqualTo("2026-12-31T23:59:59Z");
		assertThat(mapper.readTree(replay).at("/data/customer_email").isNull()).isTrue();

		when(context.getBean(ReservationService.class).createPendingReservation(any(), anyLong(), any()))
			.thenThrow(new ReservationInventoryBusyException(new IllegalStateException("test contention")));
		String busy = mvc.perform(post(path).cookie(SESSION).header("Idempotency-Key", "checkout-00000001")
			.contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isServiceUnavailable())
			.andExpect(header().string("Retry-After", "1")).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "post", "503", "R025", busy);
		assertThat(operation.at("/responses/503/headers/Retry-After").isMissingNode()).isFalse();
	}

	@Test
	void holdAndPaymentAttemptHaveNoBodyAndReturnUncachedReservationState() throws Exception {
		JsonNode spec = specification("client");
		String uid = "00000000-0000-0000-0000-000000000020";
		Instant now = Instant.parse("2027-01-01T00:00:00Z");
		String holdPath = "/api/v1/reservations/{reservationUid}/hold";
		String attemptPath = "/api/v1/reservations/{reservationUid}/payment-attempts";
		for (var entry : Map.of(holdPath, "delete", attemptPath, "post").entrySet()) {
			JsonNode operation = spec.path("paths").path(entry.getKey()).path(entry.getValue());
			assertThat(operation.has("requestBody")).isFalse();
			assertThat(operation.path("parameters").findValuesAsText("name")).containsExactly("reservationUid");
			assertThat(operation.path("responses").has("403")).isFalse();
		}
		when(context.getBean(ReservationHoldCommandService.class).releaseHold(uid, 10L))
			.thenReturn(new ReservationResponse.HoldRelease(uid, ReservationStatus.EXPIRED, false, now));
		String released = mvc.perform(delete(holdPath, uid).cookie(SESSION)).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
		validateResponse(spec, holdPath, "delete", "200", released);
		when(context.getBean(ReservationHoldCommandService.class).beginPaymentAttempt(uid, 10L))
			.thenReturn(new ReservationResponse.PaymentAttemptReady(UUID.fromString("00000000-0000-0000-0000-000000000030"),
				uid, 200000L, "KRW", now.plusSeconds(30), 30, now));
		String attempt = mvc.perform(post(attemptPath, uid).cookie(SESSION)).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
		validateResponse(spec, attemptPath, "post", "200", attempt);
		when(context.getBean(ReservationHoldCommandService.class).beginPaymentAttempt(uid, 10L))
			.thenThrow(new ReservationPaymentAttemptTooLateException());
		String tooLate = mvc.perform(post(attemptPath, uid).cookie(SESSION)).andExpect(status().isConflict())
			.andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, attemptPath, "post", "409", "R022", tooLate);
	}

	@Test
	void cancellationDocumentsNullableAmountLocationAndActualOwnershipError() throws Exception {
		JsonNode spec = specification("client");
		String path = "/api/v1/reservations/{reservationUid}";
		String uid = "00000000-0000-0000-0000-000000000020";
		String request = "{\"cancel_reason\":\"일정 변경\",\"cancel_amount\":null}";
		validateInstance(spec, spec.at("/components/schemas/PaymentRequestCancel"), mapper.readTree(request));
		assertThat(spec.at("/components/schemas/PaymentRequestCancel/example").has("cancel_amount")).isFalse();
		UUID operationId = UUID.fromString("00000000-0000-0000-0000-000000000040");
		String url = "/api/v1/payment-operations/" + operationId;
		ReservationService service = context.getBean(ReservationService.class);
		when(service.cancelReservation(eq(uid), any(), eq(10L)))
			.thenReturn(new PaymentOperationResponse.Cancellation(operationId, PaymentOperationResponse.Status.PENDING, url, false));
		String paid = mvc.perform(post(path, uid).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
			.andExpect(status().isAccepted()).andExpect(header().string("Location", url))
			.andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "post", "202", paid);
		assertThat(mapper.readTree(paid)).isEqualTo(mapper.readTree("{\"success\":true}"));
		when(service.cancelReservation(eq(uid), any(), eq(10L))).thenReturn(PaymentOperationResponse.Cancellation.completed());
		mvc.perform(post(path, uid).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
			.andExpect(status().isAccepted()).andExpect(header().doesNotExist("Location"));
		when(service.cancelReservation(eq(uid), any(), eq(10L))).thenThrow(new PaymentAccessDeniedException());
		String denied = mvc.perform(post(path, uid).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
			.andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "post", "403", "P004", denied);
	}

	@Test
	void reservationDetailsMatchLocalTimeNullableFieldsAndNotFoundErrors() throws Exception {
		JsonNode spec = specification("client");
		Instant now = Instant.parse("2027-01-01T00:00:00Z");
		Reservation pending = documentationPendingReservation(now.minusSeconds(1));
		String uid = pending.getReservationUid().toString();
		String guestPath = "/api/v1/profile/guest/reservations/{reservationUid}";
		String hostPath = "/api/v1/profile/host/reservations/{reservationUid}";
		JsonNode example = spec.at("/components/schemas/ReservationResponseGuestDetail/example");
		assertThat(example.path("status").asText()).isEqualTo("PAYMENT_PENDING");
		assertThat(example.path("payment").isNull()).isTrue();
		when(context.getBean(ReservationService.class).findMyReservationDetail(uid, 10L))
			.thenReturn(ReservationResponse.GuestDetail.from(guestDetail(pending), false, now));
		String detail = mvc.perform(get(guestPath, uid).cookie(SESSION)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		validateResponse(spec, guestPath, "get", "200", detail);
		assertThat(mapper.readTree(detail).at("/data/check_in_time").asText()).isEqualTo("15:00:00");
		assertThat(mapper.readTree(detail).at("/data/check_out_date_time").asText()).isEqualTo("2027-01-12T11:00:00");
		assertThat(mapper.readTree(detail).at("/data/status").asText()).isEqualTo("EXPIRED");
		assertThat(mapper.readTree(detail).at("/data/payment").isNull()).isTrue();
		var guest = ReservationResponse.GuestDetail.from(guestDetail(pending), false, now);
		when(context.getBean(ReservationService.class).findHostReservationDetail(uid, 10L))
			.thenReturn(ReservationResponse.HostDetail.builder().reservationUid(uid).reservationCode("AB12CD")
				.status(ReservationStatus.PAYMENT_PENDING).createdAt(guest.createdAt()).guestCount(2)
				.checkInDateTime(guest.checkInDateTime()).checkOutDateTime(guest.checkOutDateTime())
				.timeZoneId("Asia/Seoul").accommodation(guest.accommodation()).address(guest.address())
				.guest(guest.host()).build());
		String host = mvc.perform(get(hostPath, uid).cookie(SESSION)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		validateResponse(spec, hostPath, "get", "200", host);
		assertThat(mapper.readTree(host).at("/data/status").asText()).isEqualTo("PAYMENT_PENDING");
		when(context.getBean(ReservationService.class).findMyReservationDetail(uid, 10L)).thenThrow(new ReservationNotFoundException());
		when(context.getBean(ReservationService.class).findHostReservationDetail(uid, 10L)).thenThrow(new ReservationNotFoundException());
		for (String path : List.of(guestPath, hostPath)) {
			String missing = mvc.perform(get(path, uid).cookie(SESSION)).andExpect(status().isNotFound())
				.andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, "get", "404", "R001", missing);
			assertThat(spec.path("paths").path(path).path("get").path("responses").has("403")).isFalse();
		}
	}

	@Test
	void reservationListsExposeOnlyFiltersAndAcceptLegacyDisplayFields() throws Exception {
		JsonNode spec = specification("client");
		String uid = "00000000-0000-0000-0000-000000000020";
		var accommodation = new AccommodationResponse.AccommodationBasicInfo(101L, "숙소", null);
		var page = new CursorResponse.PageInfo(false, null, 1);
		LocalDate checkIn = LocalDate.parse("2027-01-10");
		LocalDate checkOut = LocalDate.parse("2027-01-12");
		when(context.getBean(ReservationService.class).findMyReservations(eq(10L), any(), any()))
			.thenReturn(new ReservationResponse.GuestReservationInfos(List.of(new ReservationResponse.GuestReservationInfo(
				201L, uid, checkIn, checkOut, "Asia/Seoul", ReservationStatus.EXPIRED, null, accommodation)), page));
		when(context.getBean(ReservationService.class).findHostReservations(eq(10L), any(), any()))
			.thenReturn(new ReservationResponse.HostReservationInfos(List.of(new ReservationResponse.HostReservationInfo(
				uid, "AB12CD", 190000L, "KRW", 2, checkIn, checkOut, "Asia/Seoul", ReservationStatus.CONFIRMED,
				null, new MemberResponse.MemberInfo(20L, null, null), accommodation)), page));
		for (String role : List.of("guest", "host")) {
			String path = "/api/v1/profile/" + role + "/reservations";
			assertThat(spec.path("paths").path(path).path("get").path("parameters").findValuesAsText("name"))
				.containsExactlyInAnyOrder("filterType", "cursor", "size");
			String body = mvc.perform(get(path).cookie(SESSION)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
			validateResponse(spec, path, "get", "200", body);
			String invalid = mvc.perform(get(path).cookie(SESSION).param("filterType", "unknown"))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, "get", "400", "C004", invalid);
		}
	}

	@Test
	void paymentConfirmationDocumentsValidationReplayAndActualAccessError() throws Exception {
		JsonNode spec = specification("client");
		String path = "/api/v1/payments/confirm";
		PaymentOperationCommandService service = context.getBean(PaymentOperationCommandService.class);
		ObjectNode valid = (ObjectNode)mapper.readTree("""
			{"payment_key":"example-payment-key","order_id":"00000000-0000-0000-0000-000000000020",
			 "amount":10000,"payment_attempt_id":"00000000-0000-0000-0000-000000000030"}
			""");
		JsonNode schema = spec.at("/components/schemas/PaymentRequestConfirm");
		assertThat(schema.path("required").toString()).contains("payment_key", "order_id", "amount", "payment_attempt_id");
		assertThat(schema.at("/properties/amount/minimum").asInt()).isEqualTo(1);
		assertThat(schema.at("/properties/payment_key/minLength").asInt()).isEqualTo(1);
		assertThat(schema.at("/properties/amount/maximum").asLong()).isEqualTo(Integer.MAX_VALUE);
		validateInstance(spec, schema, valid);
		String unauthenticated = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(valid.toString()))
			.andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "post", "401", "M004", unauthenticated);
		for (String field : List.of("payment_key", "order_id", "amount", "payment_attempt_id")) {
			ObjectNode missing = valid.deepCopy();
			missing.remove(field);
			String invalid = mvc.perform(post(path).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(missing.toString()))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, "post", "400", "C001", invalid);
		}
		for (ObjectNode invalid : List.of(valid.deepCopy().put("amount", 0), valid.deepCopy().put("amount", -1),
			valid.deepCopy().put("amount", 2147483648L), valid.deepCopy().put("payment_key", " "),
			valid.deepCopy().put("payment_key", "p".repeat(201)), valid.deepCopy().put("order_id", " "),
			valid.deepCopy().put("payment_attempt_id", "invalid"))) {
			String body = mvc.perform(post(path).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(invalid.toString()))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, "post", "400", "C001", body);
		}
		String unsupported = mvc.perform(post(path).cookie(SESSION).contentType(MediaType.TEXT_PLAIN).content(valid.toString()))
			.andExpect(status().isUnsupportedMediaType()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "post", "415", "C011", unsupported);
		verifyNoInteractions(service);

		for (PaymentOperationStatus state : List.of(PaymentOperationStatus.QUEUED, PaymentOperationStatus.APPLIED)) {
			PaymentOperation operation = PaymentOperation.builder().operationUid(DOCUMENTATION_OPERATION_ID).status(state).build();
			when(service.requestConfirmation(any(), eq(10L))).thenReturn(PaymentOperationResponse.Accepted.from(operation));
			String body = mvc.perform(post(path).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(valid.toString()))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
			validateResponse(spec, path, "post", "202", body);
			assertThat(mapper.readTree(body).at("/data/status").asText()).isEqualTo(state == PaymentOperationStatus.QUEUED ? "PENDING" : "SUCCEEDED");
			assertThat(mapper.readTree(body).at("/data/status_url").asText()).isEqualTo("/api/v1/payment-operations/" + DOCUMENTATION_OPERATION_ID);
		}
		for (kr.kro.airbob.common.exception.BaseException error : List.of(new PaymentAccessDeniedException(),
			new ReservationNotFoundException(), new kr.kro.airbob.domain.reservation.exception.InvalidReservationPaymentAttemptException(),
			new kr.kro.airbob.domain.reservation.exception.ExpiredReservationConfirmationException(),
			new PaymentOperationConflictException(), new kr.kro.airbob.common.exception.InvalidInputException("mismatch"))) {
			doThrow(error).when(service).requestConfirmation(any(), eq(10L));
			var response = mvc.perform(post(path).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(valid.toString()))
				.andExpect(status().is(error.getErrorCode().getStatus().value())).andReturn().getResponse();
			assertDocumentedError(spec, path, "post", Integer.toString(response.getStatus()), error.getErrorCode().getCode(), response.getContentAsString());
		}
		assertThat(spec.path("paths").path(path).path("post").at("/responses/403/content/application~1json/examples").has("R008")).isFalse();
	}

	@Test
	void paymentLookupSchemasAcceptStoredTimesAndNullableHistoricalCancellationFields() throws Exception {
		JsonNode spec = specification("client");
		var payment = Payment.builder().orderId(DOCUMENTATION_ORDER_ID.toString()).paymentKey("example-payment-key")
			.method(PaymentMethod.CARD).amount(10000L).balanceAmount(10000L).status(PaymentStatus.DONE)
			.createdAt(java.time.LocalDateTime.parse("2027-01-01T00:00:01"))
			.approvedAt(Instant.parse("2027-01-01T00:00:00Z")).build();
		var current = PaymentResponse.PaymentInfo.from(payment, List.of());
		var legacy = PaymentResponse.PaymentInfo.from(Payment.builder().orderId(DOCUMENTATION_ORDER_ID.toString())
			.paymentKey("example-payment-key").method(PaymentMethod.CARD).amount(10000L).balanceAmount(0L)
			.status(PaymentStatus.CANCELED).build(), List.of(PaymentTransaction.builder().build()));
		PaymentQueryService service = context.getBean(PaymentQueryService.class);
		when(service.findPaymentByPaymentKey("example-payment-key", 10L)).thenReturn(current);
		when(service.findPaymentByOrderId(DOCUMENTATION_ORDER_ID.toString(), 10L)).thenReturn(legacy);
		String keyPath = "/api/v1/payments/{paymentKey}";
		String orderPath = "/api/v1/payments/orders/{orderId}";
		String body = mvc.perform(get(keyPath, "example-payment-key").cookie(SESSION)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		validateResponse(spec, keyPath, "get", "200", body);
		assertThat(mapper.readTree(body).at("/data/requested_at").asText()).isEqualTo("2027-01-01T00:00:01Z");
		assertThat(mapper.readTree(body).at("/data/cancels").isEmpty()).isTrue();
		String historical = mvc.perform(get(orderPath, DOCUMENTATION_ORDER_ID).cookie(SESSION)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		validateResponse(spec, orderPath, "get", "200", historical);
		assertThat(mapper.readTree(historical).path("data").has("requested_at")).isFalse();
		assertThat(mapper.readTree(historical).path("data").has("approved_at")).isFalse();
		assertThat(mapper.readTree(historical).at("/data/cancels/0/canceled_at").isNull()).isTrue();
		assertThat(spec.at("/components/schemas/PaymentResponsePaymentInfo/example/status").asText()).isEqualTo("DONE");
		assertThat(spec.at("/components/schemas/PaymentResponsePaymentInfo/example/cancels").isEmpty()).isTrue();
	}

	@Test
	void paymentOperationSchemaAcceptsEveryPublicStateAndManualReviewDelay() throws Exception {
		JsonNode spec = specification("client");
		String path = "/api/v1/payment-operations/{operationId}";
		Instant now = Instant.parse("2027-01-01T00:00:00Z");
		for (PaymentOperationStatus state : PaymentOperationStatus.values()) {
			var row = new PaymentOperationDetailRow(DOCUMENTATION_OPERATION_ID, 10L, PaymentOperationType.CONFIRM,
				state, "PROVIDER_RESULT", java.time.LocalDateTime.ofInstant(now, java.time.ZoneOffset.UTC),
				now.plusSeconds(5).plusNanos(1), DOCUMENTATION_ORDER_ID, ReservationStatus.EXPIRED,
				now.plusSeconds(86400), now.minusSeconds(1));
			when(context.getBean(PaymentOperationQueryService.class).find(DOCUMENTATION_OPERATION_ID, 10L))
				.thenReturn(PaymentOperationResponse.Detail.from(row, now));
			String body = mvc.perform(get(path, DOCUMENTATION_OPERATION_ID).cookie(SESSION)).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
			validateResponse(spec, path, "get", "200", body);
			JsonNode data = mapper.readTree(body).path("data");
			assertThat(data.size()).isEqualTo(10);
			assertThat(data.has("payment_key")).isFalse();
			if (state == PaymentOperationStatus.MANUAL_REVIEW) {
				assertThat(data.path("status").asText()).isEqualTo("REQUIRES_REVIEW");
				assertThat(data.path("next_action").asText()).isEqualTo("CONTACT_SUPPORT");
				assertThat(data.path("retry_after_seconds").asInt()).isEqualTo(30);
			} else if (state == PaymentOperationStatus.WAITING_RETRY) {
				assertThat(data.path("retry_after_seconds").asInt()).isEqualTo(6);
				assertThat(data.path("failure_code").isNull()).isTrue();
				assertThat(data.path("user_failure_code").isNull()).isTrue();
			}
		}
		assertThat(spec.path("paths").path(path).path("get").path("parameters").findValuesAsText("name"))
			.containsExactly("operationId");
	}

	@Test
	void paymentReadErrorsDistinguishMissingDataOwnerAndMalformedOperationId() throws Exception {
		JsonNode spec = specification("client");
		String operationPath = "/api/v1/payment-operations/{operationId}";
		String invalid = mvc.perform(get(operationPath, "invalid").cookie(SESSION)).andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, operationPath, "get", "400", "C004", invalid);
		PaymentOperationQueryService operationService = context.getBean(PaymentOperationQueryService.class);
		PaymentQueryService paymentService = context.getBean(PaymentQueryService.class);
		for (String path : List.of(operationPath, "/api/v1/payments/{paymentKey}", "/api/v1/payments/orders/{orderId}")) {
			String noSession = mvc.perform(get(path, DOCUMENTATION_OPERATION_ID)).andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, "get", "401", "M004", noSession);
		}
		verifyNoInteractions(operationService, paymentService);
		for (boolean forbidden : List.of(false, true)) {
			doThrow(forbidden ? new PaymentAccessDeniedException() : new PaymentOperationNotFoundException())
				.when(operationService).find(any(), eq(10L));
			doThrow(forbidden ? new PaymentAccessDeniedException() : new PaymentNotFoundException())
				.when(paymentService).findPaymentByPaymentKey(any(), eq(10L));
			doThrow(forbidden ? new PaymentAccessDeniedException() : new PaymentNotFoundException())
				.when(paymentService).findPaymentByOrderId(any(), eq(10L));
			for (String path : List.of(operationPath, "/api/v1/payments/{paymentKey}", "/api/v1/payments/orders/{orderId}")) {
				var response = mvc.perform(get(path, DOCUMENTATION_OPERATION_ID).cookie(SESSION))
					.andExpect(status().is(forbidden ? 403 : 404)).andReturn().getResponse();
				assertDocumentedError(spec, path, "get", Integer.toString(response.getStatus()), forbidden ? "P004" : path.equals(operationPath) ? "P005" : "P001", response.getContentAsString());
			}
		}
	}

	@Test
	void paymentAdminQueueDocumentsBoundsSafeFieldsAndRealGuards() throws Exception {
		JsonNode spec = specification("admin");
		String path = "/api/v1/admin/payment-operations/manual-review";
		JsonNode limit = spec.path("paths").path(path).path("get").path("parameters").path(0);
		assertThat(limit.path("name").asText()).isEqualTo("limit");
		assertThat(limit.at("/schema/type").asText()).isEqualTo("integer");
		assertThat(limit.at("/schema/minimum").asInt()).isEqualTo(1);
		assertThat(limit.at("/schema/maximum").asInt()).isEqualTo(100);
		assertThat(limit.at("/schema/default").asInt()).isEqualTo(50);
		String noSession = mvc.perform(get(path)).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "get", "401", "M004", noSession);
		String forbidden = mvc.perform(get(path).cookie(SESSION)).andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "get", "403", "M006", forbidden);
		PaymentOperationManualReviewQueryService service = context.getBean(PaymentOperationManualReviewQueryService.class);
		verifyNoInteractions(service);
		allowDocumentationAdmin();
		var item = PaymentOperationAdminResponse.ManualReviewItem.from(new PaymentOperationManualReviewQueueItem(
			DOCUMENTATION_OPERATION_ID, PaymentOperationType.CONFIRM, 5, 1, Instant.parse("2027-01-01T00:00:00Z"), 7L, true));
		when(service.findManualReviewQueue(50)).thenReturn(new PaymentOperationAdminResponse.ManualReviewQueue(List.of(item), false));
		String body = mvc.perform(get(path).cookie(SESSION)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "get", "200", body);
		assertThat(mapper.readTree(body).at("/data/items/0").size()).isEqualTo(7);
		assertThat(body).doesNotContain("payment_key", "evidence_reference", "failure_code");
		verify(service).findManualReviewQueue(50);
		for (int invalidLimit : List.of(0, 101)) {
			doThrow(new kr.kro.airbob.common.exception.InvalidInputException("limit")).when(service).findManualReviewQueue(invalidLimit);
			String invalid = mvc.perform(get(path).cookie(SESSION).param("limit", Integer.toString(invalidLimit)))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, "get", "400", "C001", invalid);
		}
		String wrongType = mvc.perform(get(path).cookie(SESSION).param("limit", "invalid"))
			.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "get", "400", "C004", wrongType);
	}

	@Test
	void paymentAdminActionsDocumentValidatedRequestsAndDifferentCompletionStates() throws Exception {
		JsonNode spec = specification("admin");
		allowDocumentationAdmin();
		PaymentOperationManualReviewCommandService service = context.getBean(PaymentOperationManualReviewCommandService.class);
		for (String action : List.of("reconciliation", "mark-not-paid")) {
			String path = "/api/v1/admin/payment-operations/{operationId}/" + action;
			String valid = action.equals("reconciliation") ? "{\"expected_version\":7}" : """
				{"expected_version":7,"reason_code":"PROVIDER_PAYMENT_NOT_FOUND","evidence_reference":"incident/ABC-123"}
				""";
			for (String invalid : List.of("{}", valid.replace("\"expected_version\":7", "\"expected_version\":-1"))) {
				String body = mvc.perform(post(path, DOCUMENTATION_OPERATION_ID).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(invalid))
					.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
				assertDocumentedError(spec, path, "post", "400", "C001", body);
			}
			String wrongType = mvc.perform(post(path, "invalid").cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(valid))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, "post", "400", "C004", wrongType);
			String unsupported = mvc.perform(post(path, DOCUMENTATION_OPERATION_ID).cookie(SESSION).contentType(MediaType.TEXT_PLAIN).content(valid))
				.andExpect(status().isUnsupportedMediaType()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, "post", "415", "C011", unsupported);
			if (action.equals("mark-not-paid")) {
				for (String invalid : List.of(valid.replace("PROVIDER_PAYMENT_NOT_FOUND", "UNKNOWN"),
					valid.replace("incident/ABC-123", "secret?query=value"), valid.replace("incident/ABC-123", "x".repeat(257)))) {
					String body = mvc.perform(post(path, DOCUMENTATION_OPERATION_ID).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(invalid))
						.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
					assertDocumentedError(spec, path, "post", "400", "C001", body);
					assertThat(body).doesNotContain("secret?query=value");
				}
			}
		}
		verifyNoInteractions(service);
		when(service.requestReconciliation(DOCUMENTATION_OPERATION_ID, 10L, 7L)).thenReturn(
			new PaymentOperationManualReviewResult(DOCUMENTATION_OPERATION_ID, PaymentOperationStatus.QUEUED, 8));
		when(service.markNotPaid(eq(DOCUMENTATION_OPERATION_ID), eq(10L), eq(7L), any(), any())).thenReturn(
			new PaymentOperationManualReviewResult(DOCUMENTATION_OPERATION_ID, PaymentOperationStatus.DECLINED, 8));
		for (String action : List.of("reconciliation", "mark-not-paid")) {
			String path = "/api/v1/admin/payment-operations/{operationId}/" + action;
			String request = action.equals("reconciliation") ? "{\"expected_version\":7}" : """
				{"expected_version":7,"reason_code":"PROVIDER_PAYMENT_NOT_FOUND","evidence_reference":"incident/ABC-123"}
				""";
			String body = mvc.perform(post(path, DOCUMENTATION_OPERATION_ID).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
			validateResponse(spec, path, "post", "202", body);
			assertThat(mapper.readTree(body).at("/data/status").asText()).isEqualTo(action.equals("reconciliation") ? "QUEUED" : "DECLINED");
			JsonNode example = spec.path("paths").path(path).path("post").at("/responses/202/content/application~1json/example");
			assertThat(example).isEqualTo(mapper.readTree(body));
		}
	}

	@Test
	void paymentAdminActionErrorsMatchConflictNotFoundAndFenceOutcomes() throws Exception {
		JsonNode spec = specification("admin");
		allowDocumentationAdmin();
		PaymentOperationManualReviewCommandService service = context.getBean(PaymentOperationManualReviewCommandService.class);
		String request = "{\"expected_version\":7,\"reason_code\":\"PROVIDER_PAYMENT_NOT_FOUND\",\"evidence_reference\":\"incident/ABC-123\"}";
		for (String action : List.of("reconciliation", "mark-not-paid")) {
			String path = "/api/v1/admin/payment-operations/{operationId}/" + action;
			for (kr.kro.airbob.common.exception.BaseException error : List.of(new PaymentOperationNotFoundException(), new PaymentOperationConflictException())) {
				if (action.equals("reconciliation")) {
					doThrow(error).when(service).requestReconciliation(any(), eq(10L), anyLong());
				} else {
					doThrow(error).when(service).markNotPaid(any(), eq(10L), anyLong(), any(), any());
				}
				var response = mvc.perform(post(path, DOCUMENTATION_OPERATION_ID).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
					.andExpect(status().is(error.getErrorCode().getStatus().value())).andReturn().getResponse();
				assertDocumentedError(spec, path, "post", Integer.toString(response.getStatus()), error.getErrorCode().getCode(), response.getContentAsString());
			}
		}
		doThrow(new PaymentOperationExecutionFenceUnavailableException()).when(service).markNotPaid(any(), eq(10L), anyLong(), any(), any());
		String path = "/api/v1/admin/payment-operations/{operationId}/mark-not-paid";
		var response = mvc.perform(post(path, DOCUMENTATION_OPERATION_ID).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
			.andExpect(status().isServiceUnavailable()).andReturn().getResponse();
		assertDocumentedError(spec, path, "post", "503", "P007", response.getContentAsString());
		assertThat(response.getHeader("Retry-After")).isNull();
	}

	@Test
	void couponListsRequireSessionsAndExposeOnlyValidatedCursors() throws Exception {
		JsonNode spec = specification("client");
		CouponQueryService service = context.getBean(CouponQueryService.class);
		var emptyPage = new CursorResponse.PageInfo(false, null, 0);
		when(service.findCouponCampaigns(any())).thenReturn(new CouponResponse.CouponInfos(List.of(), emptyPage));
		when(service.findMyCoupons(eq(10L), any())).thenReturn(new CouponResponse.MemberCouponInfos(List.of(), emptyPage));
		for (String path : List.of("/api/v1/coupons", "/api/v1/members/me/coupons")) {
			JsonNode operation = spec.path("paths").path(path).path("get");
			assertThat(operation.path("parameters").findValuesAsText("name")).containsExactly("cursor", "size");
			JsonNode size = operation.path("parameters").path(1).path("schema");
			assertThat(size.path("type").asText()).isEqualTo("integer");
			assertThat(size.path("default").asInt()).isEqualTo(20);
			assertThat(size.path("minimum").asInt()).isEqualTo(1);
			assertThat(size.path("maximum").asInt()).isEqualTo(50);
			String unauthenticated = mvc.perform(get(path)).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, "get", "401", "M004", unauthenticated);
			String body = mvc.perform(get(path).cookie(SESSION)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			validateResponse(spec, path, "get", "200", body);
			assertThat(mapper.readTree(body).at("/data/infos").isEmpty()).isTrue();
			assertThat(mapper.readTree(body).at("/data/page_info/next_cursor").isNull()).isTrue();
			for (String invalidSize : List.of("0", "51", "invalid", "", "2147483648")) {
				String invalid = mvc.perform(get(path).cookie(SESSION).param("size", invalidSize)).andExpect(status().isBadRequest())
					.andReturn().getResponse().getContentAsString();
				assertDocumentedError(spec, path, "get", "400", "C006", invalid);
			}
			String invalidCursor = mvc.perform(get(path).cookie(SESSION).param("cursor", "not-base64!"))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, "get", "400", "C010", invalidCursor);
			Object wrongCursor = path.equals("/api/v1/coupons")
				? new kr.kro.airbob.cursor.dto.CursorData(101L, java.time.LocalDateTime.parse("2027-01-01T00:00:00"))
				: new kr.kro.airbob.cursor.dto.CouponCampaignCursorData(101L, java.time.LocalDateTime.parse("2027-01-01T00:00:00"));
			String encoded = java.util.Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(wrongCursor));
			String mismatched = mvc.perform(get(path).cookie(SESSION).param("cursor", encoded)).andExpect(status().isBadRequest())
				.andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, "get", "400", "C010", mismatched);
		}
		verify(service).findCouponCampaigns(argThat(request -> request.size() == 20 && request.lastId() == null));
		verify(service).findMyCoupons(eq(10L), argThat(request -> request.size() == 20 && request.lastId() == null));
	}

	@Test
	void couponListSchemasMatchAllIssuanceAndOwnershipStates() throws Exception {
		JsonNode spec = specification("client");
		java.time.LocalDateTime now = java.time.LocalDateTime.parse("2027-01-01T00:00:00.123456");
		var campaignInfos = new java.util.ArrayList<CouponResponse.CouponInfo>();
		for (int index = 0; index < 4; index++) {
			var row = new kr.kro.airbob.domain.coupon.repository.projection.CouponCampaignProjection(101L + index,
				"쿠폰", null, DiscountType.FIXED_AMOUNT, 1000, null, null,
				index == 0 ? now.plusHours(1) : now, now.plusDays(1), now, now.plusDays(30),
				index == 3 ? null : 100, index == 2);
			campaignInfos.add(CouponResponse.CouponInfo.of(row, now));
		}
		CouponQueryService service = context.getBean(CouponQueryService.class);
		when(service.findCouponCampaigns(any())).thenReturn(new CouponResponse.CouponInfos(campaignInfos,
			new CursorResponse.PageInfo(false, null, 4)));
		String campaigns = mvc.perform(get("/api/v1/coupons").cookie(SESSION)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		validateResponse(spec, "/api/v1/coupons", "get", "200", campaigns);
		assertThat(mapper.readTree(campaigns).at("/data/infos").findValuesAsText("issuance_status"))
			.containsExactly("UPCOMING", "SOLD_OUT", "OPEN", "OPEN");
		assertThat(mapper.readTree(campaigns).at("/data/infos/3/total_quantity").isNull()).isTrue();
		assertThat(mapper.readTree(campaigns).at("/data/infos/1/issue_start_at").asText()).isEqualTo("2027-01-01T00:00:00.123456Z");
		assertThat(campaigns).doesNotContain("has_remaining_stock", "issued_quantity", "stock_prepared_at", "issue_strategy");

		var ownedInfos = new java.util.ArrayList<CouponResponse.MemberCouponInfo>();
		for (int index = 0; index < 5; index++) {
			var row = new kr.kro.airbob.domain.coupon.repository.projection.MemberCouponProjection(201L + index, now,
				101L + index, "쿠폰", null, DiscountType.FIXED_AMOUNT, 1000, null, null,
				index == 2 || index == 3 ? now.plusHours(1) : now.minusHours(1), index < 2 ? now : now.plusDays(1),
				index == 0, index >= 3);
			ownedInfos.add(CouponResponse.MemberCouponInfo.of(row, now));
		}
		when(service.findMyCoupons(eq(10L), any())).thenReturn(new CouponResponse.MemberCouponInfos(ownedInfos,
			new CursorResponse.PageInfo(false, null, 5)));
		String owned = mvc.perform(get("/api/v1/members/me/coupons").cookie(SESSION)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		validateResponse(spec, "/api/v1/members/me/coupons", "get", "200", owned);
		assertThat(mapper.readTree(owned).at("/data/infos").findValuesAsText("status"))
			.containsExactly("USED", "EXPIRED", "UNAVAILABLE", "UPCOMING", "AVAILABLE");
		assertThat(mapper.readTree(owned).at("/data/infos/0/coupon_id").asLong()).isEqualTo(101);
		assertThat(owned).doesNotContain("member_coupon_id", "created_at", "issuance_status");
	}

	@Test
	void couponIssuanceDocumentsEmptyBodyAndDistinctSoldOutAndContentionErrors() throws Exception {
		JsonNode spec = specification("client");
		String path = "/api/v1/coupons/{couponId}/issue";
		JsonNode operation = spec.path("paths").path(path).path("post");
		assertThat(operation.has("requestBody")).isFalse();
		assertThat(operation.path("parameters").findValuesAsText("name")).containsExactly("couponId");
		String noSession = mvc.perform(post(path, 101)).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "post", "401", "M004", noSession);
		String invalidId = mvc.perform(post(path, "invalid").cookie(SESSION)).andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "post", "400", "C004", invalidId);
		CouponSkipLockedIssueService service = context.getBean(CouponSkipLockedIssueService.class);
		verifyNoInteractions(service);
		String success = mvc.perform(post(path, 101).cookie(SESSION)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "post", "201", success);
		assertThat(mapper.readTree(success)).isEqualTo(mapper.readTree("{\"success\":true}"));
		verify(service).issue(101L, 10L);
		for (kr.kro.airbob.common.exception.BaseException error : List.of(new CouponNotFoundException(), new CouponSoldOutException(),
			new CouponAlreadyIssuedException(), new CouponNotIssuableException(), new CouponStockNotPreparedException(), new CouponIssueContendedException())) {
			doThrow(error).when(service).issue(101L, 10L);
			var response = mvc.perform(post(path, 101).cookie(SESSION)).andExpect(status().is(error.getErrorCode().getStatus().value()))
				.andReturn().getResponse();
			assertDocumentedError(spec, path, "post", Integer.toString(response.getStatus()), error.getErrorCode().getCode(), response.getContentAsString());
			assertThat(response.getHeader("Retry-After")).isNull();
		}
	}

	@Test
	void couponCreationDocumentsIdentityOptionalFieldsAndConfigurationErrors() throws Exception {
		JsonNode spec = specification("admin");
		String path = "/api/v1/admin/coupons";
		ObjectNode request = (ObjectNode)mapper.readTree("""
			{"name":"쿠폰","description":null,"discount_type":"FIXED_AMOUNT","discount_value":1000,
			 "min_payment_price":null,"max_discount_amount":null,"issue_start_at":"2027-01-01T00:30:00+09:00",
			 "issue_end_at":"2027-01-31T00:00:00Z","usable_from":"2027-01-01T00:30:00+09:00",
			 "usable_until":"2027-02-28T00:00:00Z","is_active":true,"total_quantity":100}
			""");
		JsonNode schema = spec.at("/components/schemas/CouponRequestCreate");
		validateInstance(spec, schema, request);
		assertThat(schema.at("/properties/name/maxLength").asInt()).isEqualTo(255);
		assertThat(schema.at("/properties/description/maxLength").asInt()).isEqualTo(255);
		assertThat(schema.path("required").size()).isEqualTo(9);
		CouponService service = context.getBean(CouponService.class);
		String noSession = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(request.toString()))
			.andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "post", "401", "M004", noSession);
		String forbidden = mvc.perform(post(path).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request.toString()))
			.andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "post", "403", "M006", forbidden);
		verifyNoInteractions(service);
		allowDocumentationAdmin();
		for (ObjectNode invalid : List.of(request.deepCopy().put("issue_start_at", "2027-01-01T00:00:00"),
			request.deepCopy().put("discount_type", "UNKNOWN"), request.deepCopy().put("total_quantity", 2147483648L))) {
			String body = mvc.perform(post(path).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(invalid.toString()))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, "post", "400", "C001", body);
		}
		when(service.createCoupon(any())).thenReturn(101L);
		String success = mvc.perform(post(path).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request.toString()))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "post", "201", success);
		assertThat(mapper.readTree(success).at("/data/id").asLong()).isEqualTo(101);
		verify(service).createCoupon(argThat(dto -> dto.issueStartAt().equals(Instant.parse("2026-12-31T15:30:00Z")) && dto.description() == null));
		doThrow(new CouponInvalidConfigurationException()).when(service).createCoupon(any());
		String invalidConfiguration = mvc.perform(post(path).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "post", "400", "CP013", invalidConfiguration);
		String unsupported = mvc.perform(post(path).cookie(SESSION).contentType(MediaType.TEXT_PLAIN).content(request.toString()))
			.andExpect(status().isUnsupportedMediaType()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "post", "415", "C011", unsupported);
	}

	@Test
	void couponPatchSchemaAcceptsExplicitNullsAndDocumentsLockedConfiguration() throws Exception {
		JsonNode spec = specification("admin");
		allowDocumentationAdmin();
		String path = "/api/v1/admin/coupons/{couponId}";
		String request = """
			{"name":null,"description":"","discount_type":null,"discount_value":null,"min_payment_price":null,
			 "max_discount_amount":null,"issue_start_at":null,"issue_end_at":null,"usable_from":null,
			 "usable_until":null,"is_active":null,"total_quantity":null}
			""";
		JsonNode schema = spec.at("/components/schemas/CouponRequestUpdate");
		assertThat(schema.path("properties").has("issue_start_at")).isTrue();
		assertThat(schema.path("properties").has("issue_end_at")).isTrue();
		assertThat(schema.path("properties").has("issueStartAt")).isFalse();
		assertThat(schema.path("properties").has("issueEndAt")).isFalse();
		validateInstance(spec, schema, mapper.readTree(request));
		validateInstance(spec, schema, mapper.readTree("{}"));
		String success = mvc.perform(patch(path, 101).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "patch", "200", success);
		CouponService service = context.getBean(CouponService.class);
		verify(service).updateCoupon(argThat(dto -> dto.name() == null && dto.description().isEmpty() && dto.discountType() == null
			&& dto.isActive() == null && dto.totalQuantity() == null), eq(101L));
		for (kr.kro.airbob.common.exception.BaseException error : List.of(new CouponNotFoundException(),
			new CouponConfigurationLockedException(), new CouponInvalidConfigurationException())) {
			doThrow(error).when(service).updateCoupon(any(), eq(101L));
			var response = mvc.perform(patch(path, 101).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content("{\"total_quantity\":200}"))
				.andExpect(status().is(error.getErrorCode().getStatus().value())).andReturn().getResponse();
			assertDocumentedError(spec, path, "patch", Integer.toString(response.getStatus()), error.getErrorCode().getCode(), response.getContentAsString());
		}
	}

	@Test
	void couponQuantityRejectsMissingAndNonpositiveValuesAndReturnsEmptySuccess() throws Exception {
		JsonNode spec = specification("admin");
		allowDocumentationAdmin();
		String path = "/api/v1/admin/coupons/{couponId}/quantity";
		assertThat(spec.at("/components/schemas/CouponRequestQuantity/properties/total_quantity/minimum").asInt()).isEqualTo(1);
		for (String request : List.of("{}", "{\"total_quantity\":null}", "{\"total_quantity\":0}", "{\"total_quantity\":-1}")) {
			String invalid = mvc.perform(patch(path, 101).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, "patch", "400", "C001", invalid);
		}
		String invalidId = mvc.perform(patch(path, "invalid").cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content("{\"total_quantity\":100}"))
			.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "patch", "400", "C004", invalidId);
		verifyNoInteractions(context.getBean(CouponService.class));
		String success = mvc.perform(patch(path, 101).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content("{\"total_quantity\":100}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "patch", "200", success);
		assertThat(mapper.readTree(success)).isEqualTo(mapper.readTree("{\"success\":true}"));
	}

	@Test
	void couponPreparationAndDeletionHaveNoBodyAndUseRealAdminGuards() throws Exception {
		JsonNode spec = specification("admin");
		CouponService couponService = context.getBean(CouponService.class);
		CouponStockPreparationService preparationService = context.getBean(CouponStockPreparationService.class);
		String preparePath = "/api/v1/admin/coupons/{couponId}/stock/prepare";
		String deletePath = "/api/v1/admin/coupons/{couponId}";
		for (boolean prepare : List.of(true, false)) {
			String path = prepare ? preparePath : deletePath;
			String method = prepare ? "post" : "delete";
			assertThat(spec.path("paths").path(path).path(method).has("requestBody")).isFalse();
			assertThat(spec.path("paths").path(path).path(method).path("parameters").findValuesAsText("name")).containsExactly("couponId");
			String noSession = mvc.perform(prepare ? post(path, 101) : delete(path, 101)).andExpect(status().isUnauthorized())
				.andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, method, "401", "M004", noSession);
			String forbidden = mvc.perform((prepare ? post(path, 101) : delete(path, 101)).cookie(SESSION)).andExpect(status().isForbidden())
				.andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, method, "403", "M006", forbidden);
		}
		verifyNoInteractions(couponService, preparationService);
		allowDocumentationAdmin();
		for (boolean prepare : List.of(true, false)) {
			String path = prepare ? preparePath : deletePath;
			String method = prepare ? "post" : "delete";
			String success = mvc.perform((prepare ? post(path, 101) : delete(path, 101)).cookie(SESSION)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
			validateResponse(spec, path, method, "200", success);
			assertThat(mapper.readTree(success)).isEqualTo(mapper.readTree("{\"success\":true}"));
		}
		for (kr.kro.airbob.common.exception.BaseException error : List.of(new CouponNotFoundException(),
			new CouponAlreadyPreparedException(), new CouponStockPreparationNotAllowedException(), new CouponInvalidConfigurationException())) {
			doThrow(error).when(preparationService).prepare(101L);
			var response = mvc.perform(post(preparePath, 101).cookie(SESSION)).andExpect(status().is(error.getErrorCode().getStatus().value()))
				.andReturn().getResponse();
			assertDocumentedError(spec, preparePath, "post", Integer.toString(response.getStatus()), error.getErrorCode().getCode(), response.getContentAsString());
		}
		for (kr.kro.airbob.common.exception.BaseException error : List.of(new CouponNotFoundException(), new CouponConfigurationLockedException())) {
			doThrow(error).when(couponService).deleteCoupon(101L);
			var response = mvc.perform(delete(deletePath, 101).cookie(SESSION)).andExpect(status().is(error.getErrorCode().getStatus().value()))
				.andReturn().getResponse();
			assertDocumentedError(spec, deletePath, "delete", Integer.toString(response.getStatus()), error.getErrorCode().getCode(), response.getContentAsString());
		}
	}

	@Test
	void reviewListIsPublicAndMatchesSortCursorAndNullableDisplayFields() throws Exception {
		JsonNode spec = specification("client");
		String path = "/api/v1/accommodations/{accommodationId}/reviews";
		JsonNode operation = spec.path("paths").path(path).path("get");
		assertThat(operation.path("security").isEmpty()).isTrue();
		assertThat(operation.path("responses").has("401")).isFalse();
		assertThat(operation.path("parameters").findValuesAsText("name"))
			.containsExactlyInAnyOrder("accommodationId", "sortType", "cursor", "size");
		JsonNode sort = java.util.stream.StreamSupport.stream(operation.path("parameters").spliterator(), false)
			.filter(parameter -> parameter.path("name").asText().equals("sortType")).findFirst().orElseThrow().path("schema");
		assertThat(sort.path("default").asText()).isEqualTo("LATEST");
		assertThat(sort.path("enum").toString()).contains("LATEST", "HIGHEST_RATING", "LOWEST_RATING");
		ReviewService service = context.getBean(ReviewService.class);
		var page = new ReviewResponse.ReviewInfos(List.of(
			new ReviewResponse.ReviewInfo(201, 5, "좋은 숙소", Instant.parse("2027-01-10T02:00:00.123456Z"),
				new MemberResponse.MemberInfo(10, null, null), List.of(new ImageResponse.ImageInfo(301L, "https://example.invalid/review.png"))),
			new ReviewResponse.ReviewInfo(202, 3, null, (Instant)null,
				new MemberResponse.MemberInfo(20, "여행자", null), List.of())), new CursorResponse.PageInfo(false, null, 2));
		when(service.findReviews(eq(101L), any(), any())).thenReturn(page);
		String body = mvc.perform(get(path, 101)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "get", "200", body);
		assertThat(mapper.readTree(body).at("/data/reviews/0/reviewed_at").asText()).isEqualTo("2027-01-10T02:00:00.123456Z");
		assertThat(mapper.readTree(body).at("/data/reviews/1/images").isEmpty()).isTrue();
		verify(service).findReviews(eq(101L), argThat(request -> request.size() == 20 && request.lastId() == null),
			eq(ReviewSortType.LATEST));
		for (String invalidSize : List.of("0", "51", "invalid")) {
			String invalid = mvc.perform(get(path, 101).param("size", invalidSize)).andExpect(status().isBadRequest())
				.andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, "get", "400", "C006", invalid);
		}
		String invalidSort = mvc.perform(get(path, 101).param("sortType", "latest")).andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "get", "400", "C004", invalidSort);
		String invalidCursor = mvc.perform(get(path, 101).param("cursor", "not-a-cursor!")).andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "get", "400", "C010", invalidCursor);
		when(service.findReviews(eq(999L), any(), any())).thenReturn(new ReviewResponse.ReviewInfos(List.of(), new CursorResponse.PageInfo(false, null, 0)));
		String empty = mvc.perform(get(path, 999)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "get", "200", empty);
		assertThat(mapper.readTree(empty).at("/data/reviews").isEmpty()).isTrue();
	}

	@Test
	void reviewCreationValidatesRequiredBodyAndDocumentsEligibilityAndDuplicateErrors() throws Exception {
		JsonNode spec = specification("client");
		String path = "/api/v1/accommodations/{accommodationId}/reviews";
		JsonNode schema = spec.at("/components/schemas/ReviewRequestCreate");
		validateInstance(spec, schema, mapper.readTree("{\"rating\":5,\"content\":\"숙소가 좋았어요\"}"));
		assertThat(schema.path("required").toString()).contains("content", "rating");
		assertThat(schema.at("/properties/content/maxLength").asInt()).isEqualTo(1024);
		assertThat(schema.at("/properties/content/minLength").asInt()).isEqualTo(1);
		ReviewService service = context.getBean(ReviewService.class);
		for (String invalid : List.of("{}", "{\"content\":\"본문\"}", "{\"rating\":5}", "{\"rating\":null,\"content\":\"본문\"}",
			"{\"rating\":0,\"content\":\"본문\"}", "{\"rating\":6,\"content\":\"본문\"}", "{\"rating\":5,\"content\":\"  \"}",
			mapper.createObjectNode().put("rating", 5).put("content", "가".repeat(1025)).toString())) {
			String body = mvc.perform(post(path, 101).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(invalid))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, "post", "400", "C001", body);
		}
		verifyNoInteractions(service);
		when(service.createReview(eq(101L), any(), eq(10L))).thenReturn(new ReviewResponse.Create(201));
		String request = mapper.createObjectNode().put("rating", 5).put("content", "가".repeat(1024)).toString();
		String success = mvc.perform(post(path, 101).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "post", "201", success);
		assertThat(mapper.readTree(success).at("/data/id").asLong()).isEqualTo(201);
		for (BaseException error : List.of(new MemberNotFoundException(), new AccommodationNotFoundException(),
			new ReviewCreationForbiddenException(), new ReviewAlreadyExistsException())) {
			doThrow(error).when(service).createReview(eq(101L), any(), eq(10L));
			var response = mvc.perform(post(path, 101).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().is(error.getErrorCode().getStatus().value())).andReturn().getResponse();
			assertDocumentedError(spec, path, "post", Integer.toString(response.getStatus()), error.getErrorCode().getCode(), response.getContentAsString());
		}
		String unsupported = mvc.perform(post(path, 101).cookie(SESSION).contentType(MediaType.TEXT_PLAIN).content(request))
			.andExpect(status().isUnsupportedMediaType()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "post", "415", "C011", unsupported);
	}

	@Test
	void reviewUpdateRequiresContentButPreservesAnOmittedOrNullRating() throws Exception {
		JsonNode spec = specification("client");
		String path = "/api/v1/reviews/{reviewId}";
		JsonNode schema = spec.at("/components/schemas/ReviewRequestUpdate");
		assertThat(schema.path("required").toString()).isEqualTo("[\"content\"]");
		assertThat(schema.at("/properties/content/minLength").asInt()).isEqualTo(1);
		ReviewService service = context.getBean(ReviewService.class);
		for (String invalid : List.of("{}", "{\"rating\":3}", "{\"content\":null}", "{\"content\":\"\"}", "{\"content\":\" \"}",
			"{\"content\":\"본문\",\"rating\":6}")) {
			String body = mvc.perform(patch(path, 201).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(invalid))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, "patch", "400", "C001", body);
		}
		verifyNoInteractions(service);
		when(service.updateReviewContent(eq(201L), any(), eq(10L))).thenReturn(new ReviewResponse.Update(201));
		for (String request : List.of("{\"content\":\"수정 본문\"}", "{\"content\":\"수정 본문\",\"rating\":null}", "{\"content\":\"수정 본문\",\"rating\":1}")) {
			validateInstance(spec, schema, mapper.readTree(request));
			String success = mvc.perform(patch(path, 201).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			validateResponse(spec, path, "patch", "200", success);
			assertThat(mapper.readTree(success).at("/data/id").asLong()).isEqualTo(201);
		}
		for (BaseException error : List.of(new ReviewNotFoundException(),
			new ReviewUpdateForbiddenException())) {
			doThrow(error).when(service).updateReviewContent(eq(201L), any(), eq(10L));
			var response = mvc.perform(patch(path, 201).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"수정 본문\"}"))
				.andExpect(status().is(error.getErrorCode().getStatus().value())).andReturn().getResponse();
			assertDocumentedError(spec, path, "patch", Integer.toString(response.getStatus()), error.getErrorCode().getCode(), response.getContentAsString());
		}
	}

	@Test
	void everyReviewMutationRequiresTheSessionWithoutMemberIdInputs() throws Exception {
		JsonNode spec = specification("client");
		var requests = List.of(post("/api/v1/accommodations/{accommodationId}/reviews", 101).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":5,\"content\":\"본문\"}"),
			patch("/api/v1/reviews/{reviewId}", 201).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"본문\"}"),
			delete("/api/v1/reviews/{reviewId}", 201),
			multipart("/api/v1/reviews/{reviewId}/images", 201).file(new MockMultipartFile("images", "review.png", "image/png", new byte[] {1})),
			delete("/api/v1/reviews/{reviewId}/images/{imageId}", 201, 301));
		String[] paths = {"/api/v1/accommodations/{accommodationId}/reviews", "/api/v1/reviews/{reviewId}", "/api/v1/reviews/{reviewId}",
			"/api/v1/reviews/{reviewId}/images", "/api/v1/reviews/{reviewId}/images/{imageId}"};
		String[] methods = {"post", "patch", "delete", "post", "delete"};
		for (int index = 0; index < requests.size(); index++) {
			String body = mvc.perform(requests.get(index)).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, paths[index], methods[index], "401", "M004", body);
			assertThat(spec.path("paths").path(paths[index]).path(methods[index]).path("parameters").findValuesAsText("name"))
				.doesNotContain("memberId", "member_id", "SESSION_ID");
		}
		verifyNoInteractions(context.getBean(ReviewService.class), context.getBean(ReviewImageService.class));
	}

	@Test
	void reviewDeletionAndImageDeletionHaveDifferentMissingResourceErrors() throws Exception {
		JsonNode spec = specification("client");
		ReviewService service = context.getBean(ReviewService.class);
		ReviewImageService imageService = context.getBean(ReviewImageService.class);
		String review = "/api/v1/reviews/{reviewId}", image = "/api/v1/reviews/{reviewId}/images/{imageId}";
		for (String path : List.of(review, image)) {
			assertThat(spec.path("paths").path(path).path("delete").has("requestBody")).isFalse();
			String success = mvc.perform(delete(path, 201, 301).cookie(SESSION)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			validateResponse(spec, path, "delete", "200", success);
			assertThat(mapper.readTree(success)).isEqualTo(mapper.readTree("{\"success\":true}"));
		}
		doThrow(new ReviewNotFoundException()).when(service).deleteReview(201L, 10L);
		String missing = mvc.perform(delete(review, 201).cookie(SESSION)).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, review, "delete", "404", "V001", missing);
		assertThat(spec.path("paths").path(image).path("delete").path("responses").has("404")).isFalse();
		for (BaseException error : List.of(new ReviewAccessDeniedException(),
			new InvalidInputException())) {
			doThrow(error).when(imageService).deleteReviewImage(201L, 301L, 10L);
			var response = mvc.perform(delete(image, 201, 301).cookie(SESSION))
				.andExpect(status().is(error.getErrorCode().getStatus().value())).andReturn().getResponse();
			assertDocumentedError(spec, image, "delete", Integer.toString(response.getStatus()), error.getErrorCode().getCode(), response.getContentAsString());
		}
	}

	@Test
	void reviewUploadDocumentsMultipartFilesAndRealErrorsWithoutAnAuthorizationFormField() throws Exception {
		JsonNode spec = specification("client");
		String path = "/api/v1/reviews/{reviewId}/images";
		JsonNode operation = spec.path("paths").path(path).path("post");
		assertThat(operation.path("parameters").findValuesAsText("name")).containsExactly("reviewId");
		assertThat(operation.at("/requestBody/content/multipart~1form-data/schema/properties/images/items/format").asText()).isEqualTo("binary");
		String missing = mvc.perform(multipart(path, 201).cookie(SESSION)).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "post", "400", "C001", missing);
		String unsupported = mvc.perform(post(path, 201).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnsupportedMediaType()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "post", "415", "C011", unsupported);
		ReviewImageService service = context.getBean(ReviewImageService.class);
		verifyNoInteractions(service);
		when(service.uploadReviewImages(eq(201L), anyList(), eq(10L)))
			.thenReturn(new ImageResponse.ImageUploadResult(List.of(new ImageResponse.ImageInfo(301L, "https://example.invalid/review.png"))));
		var file = new MockMultipartFile("images", "review.png", "image/png", new byte[] {1});
		String success = mvc.perform(multipart(path, 201).file(file).cookie(SESSION)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "post", "201", success);
		assertThat(mapper.readTree(success).at("/data/uploaded_images/0/id").asLong()).isEqualTo(301);
		for (BaseException error : List.of(new ReviewNotFoundException(),
			new ImageUploadException("review.png"), new InvalidImageFormatException(),
			new EmptyImageFileException(), new ImageFileSizeExceededException())) {
			doThrow(error).when(service).uploadReviewImages(eq(201L), anyList(), eq(10L));
			var response = mvc.perform(multipart(path, 201).file(file).cookie(SESSION))
				.andExpect(status().is(error.getErrorCode().getStatus().value())).andReturn().getResponse();
			assertDocumentedError(spec, path, "post", Integer.toString(response.getStatus()), error.getErrorCode().getCode(), response.getContentAsString());
		}
		doThrow(new MaxUploadSizeExceededException(10 * 1024 * 1024)).when(service).uploadReviewImages(eq(201L), anyList(), eq(10L));
		String oversized = mvc.perform(multipart(path, 201).file(file).cookie(SESSION)).andExpect(status().isPayloadTooLarge()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "post", "413", "I009", oversized);
	}

	@Test
	void everyWishlistEndpointRequiresTheSessionWithoutManualIdentityInputs() throws Exception {
		JsonNode spec = specification("client");
		String lists = "/api/v1/members/wishlists", list = lists + "/{wishlistId}", membership = lists + "/membership";
		String items = lists + "/accommodations/{id}", item = lists + "/accommodations/{id}";
		var requests = List.of(post(lists), patch(list, 42), delete(list, 42), get(lists), get(membership).param("accommodationId", "101"),
			post(items, 42), patch(item, 501), delete(item, 501), get(items, 42));
		String[] paths = {lists, list, list, lists, membership, items, item, item, items};
		String[] methods = {"post", "patch", "delete", "get", "get", "post", "patch", "delete", "get"};
		for (int index = 0; index < requests.size(); index++) {
			String body = mvc.perform(requests.get(index)).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, paths[index], methods[index], "401", "M004", body);
			assertThat(spec.path("paths").path(paths[index]).path(methods[index]).path("parameters").findValuesAsText("name"))
				.doesNotContain("memberId", "member_id", "SESSION_ID");
		}
		verifyNoInteractions(context.getBean(WishlistService.class));
	}

	@Test
	void wishlistNamesHaveMatchingCreateAndRenameConstraintsAndIds() throws Exception {
		JsonNode spec = specification("client");
		String lists = "/api/v1/members/wishlists", list = lists + "/{wishlistId}";
		WishlistService service = context.getBean(WishlistService.class);
		for (String schemaName : List.of("WishlistRequestCreate", "WishlistRequestUpdate")) {
			JsonNode schema = spec.path("components").path("schemas").path(schemaName);
			assertThat(schema.path("required").toString()).isEqualTo("[\"name\"]");
			assertThat(schema.at("/properties/name/minLength").asInt()).isEqualTo(1);
			assertThat(schema.at("/properties/name/maxLength").asInt()).isEqualTo(255);
		}
		for (String invalid : List.of("{}", "{\"name\":null}", "{\"name\":\"\"}", "{\"name\":\"  \"}",
			mapper.createObjectNode().put("name", "가".repeat(256)).toString())) {
			String created = mvc.perform(post(lists).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(invalid))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, lists, "post", "400", "C001", created);
			String renamed = mvc.perform(patch(list, 42).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(invalid))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, list, "patch", "400", "C001", renamed);
		}
		verifyNoInteractions(service);
		when(service.createWishlist(any(), eq(10L))).thenReturn(new WishlistResponse.Create(42));
		when(service.updateWishlist(eq(42L), any(), eq(10L))).thenReturn(new WishlistResponse.Update(42));
		for (int length : List.of(1, 255)) {
			String request = mapper.createObjectNode().put("name", "가".repeat(length)).toString();
			String created = mvc.perform(post(lists).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
			validateResponse(spec, lists, "post", "201", created);
			assertThat(mapper.readTree(created).at("/data/id").asLong()).isEqualTo(42);
			String renamed = mvc.perform(patch(list, 42).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			validateResponse(spec, list, "patch", "200", renamed);
		}
	}

	@Test
	void wishlistAccommodationInputsValidateLengthsAndReturnTheConnectionId() throws Exception {
		JsonNode spec = specification("client");
		String items = "/api/v1/members/wishlists/accommodations/{id}";
		String item = "/api/v1/members/wishlists/accommodations/{id}";
		WishlistService service = context.getBean(WishlistService.class);
		for (String invalid : List.of("{}", "{\"accommodation_id\":null}", "{\"accommodation_id\":0}", "{\"accommodation_id\":-1}")) {
			String response = mvc.perform(post(items, 42).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(invalid))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, items, "post", "400", "C001", response);
		}
		for (String invalid : List.of("{}", "{\"memo\":null}", "{\"memo\":\"\"}", "{\"memo\":\"  \"}",
			mapper.createObjectNode().put("memo", "나".repeat(1025)).toString())) {
			String response = mvc.perform(patch(item, 501).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(invalid))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, item, "patch", "400", "C001", response);
		}
		verifyNoInteractions(service);
		JsonNode schema = spec.at("/components/schemas/WishlistAccommodationRequestUpdate");
		assertThat(schema.path("required").toString()).isEqualTo("[\"memo\"]");
		assertThat(schema.at("/properties/memo/minLength").asInt()).isEqualTo(1);
		assertThat(schema.at("/properties/memo/maxLength").asInt()).isEqualTo(1024);
		when(service.createWishlistAccommodation(eq(42L), any(), eq(10L))).thenReturn(new WishlistAccommodationResponse.Create(501));
		when(service.updateWishlistAccommodation(eq(501L), any(), eq(10L))).thenReturn(new WishlistAccommodationResponse.Update(501));
		String created = mvc.perform(post(items, 42).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content("{\"accommodation_id\":101}"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, items, "post", "200", created);
		assertThat(mapper.readTree(created).at("/data/id").asLong()).isEqualTo(501);
		verify(service).createWishlistAccommodation(42L, new WishlistAccommodationRequest.Create(101L), 10L);
		for (int length : List.of(1, 1024)) {
			String request = mapper.createObjectNode().put("memo", "나".repeat(length)).toString();
			String updated = mvc.perform(patch(item, 501).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			validateResponse(spec, item, "patch", "200", updated);
			assertThat(mapper.readTree(updated).at("/data/id").asLong()).isEqualTo(501);
		}
	}

	@Test
	void wishlistMembershipRequiresAnAccommodationAndDistinguishesUnknownTargetFromFalse() throws Exception {
		JsonNode spec = specification("client");
		String path = "/api/v1/members/wishlists/membership";
		JsonNode operation = spec.path("paths").path(path).path("get");
		assertThat(operation.path("parameters").findValuesAsText("name")).containsExactlyInAnyOrder("accommodationId", "wishlistId");
		operation.path("parameters").forEach(parameter -> assertThat(parameter.path("required").asBoolean())
			.isEqualTo(parameter.path("name").asText().equals("accommodationId")));
		String missing = mvc.perform(get(path).cookie(SESSION)).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "get", "400", "C001", missing);
		for (String parameter : List.of("accommodationId", "wishlistId")) {
			var request = get(path).cookie(SESSION).param(parameter, "invalid");
			if (parameter.equals("wishlistId")) request.param("accommodationId", "101");
			String invalid = mvc.perform(request).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, "get", "400", "C004", invalid);
		}
		WishlistService service = context.getBean(WishlistService.class);
		verifyNoInteractions(service);
		for (WishlistResponse.Membership membership : List.of(new WishlistResponse.Membership(true, true, true),
			new WishlistResponse.Membership(true, false, true), new WishlistResponse.Membership(false, null, false))) {
			when(service.findMembership(10L, 101L, 42L)).thenReturn(membership);
			String response = mvc.perform(get(path).cookie(SESSION).param("accommodationId", "101").param("wishlistId", "42").param("memberId", "999"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			validateResponse(spec, path, "get", "200", response);
			assertThat(mapper.readTree(response).path("data")).isEqualTo(mapper.valueToTree(membership));
		}
		when(service.findMembership(10L, 101L, null)).thenReturn(new WishlistResponse.Membership(true, null, false));
		String omitted = mvc.perform(get(path).cookie(SESSION).param("accommodationId", "101"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "get", "200", omitted);
		assertThat(mapper.readTree(omitted).at("/data/target_wishlist_contains").isNull()).isTrue();
		assertThat(operation.path("responses").has("404")).isFalse();
	}

	@Test
	void wishlistListDocumentsOptionalSelectionNullableFieldsAndCursorLimits() throws Exception {
		JsonNode spec = specification("client");
		String path = "/api/v1/members/wishlists";
		WishlistService service = context.getBean(WishlistService.class);
		when(service.findWishlists(any(), eq(10L), isNull())).thenReturn(new WishlistResponse.WishlistInfos(List.of(
			new WishlistResponse.WishlistInfo(42, "서울 여행", Instant.parse("2027-01-10T02:00:00.123456Z"), 2L, null, null, null),
			new WishlistResponse.WishlistInfo(43, "옛 목록", null, 0L, null, null, null)), new CursorResponse.PageInfo(false, null, 2)));
		String response = mvc.perform(get(path).cookie(SESSION)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "get", "200", response);
		assertThat(mapper.readTree(response).at("/data/wishlists/0/created_at").asText()).isEqualTo("2027-01-10T02:00:00.123456Z");
		assertThat(mapper.readTree(response).at("/data/wishlists/0/is_contained").isNull()).isTrue();
		verify(service).findWishlists(argThat(request -> request.size() == 20 && request.lastId() == null), eq(10L), isNull());
		when(service.findWishlists(any(), eq(10L), eq(101L))).thenReturn(new WishlistResponse.WishlistInfos(List.of(
			new WishlistResponse.WishlistInfo(42, "서울 여행", Instant.parse("2027-01-10T02:00:00Z"), 2L, null, true, 501L),
			new WishlistResponse.WishlistInfo(43, "다음 여행", Instant.parse("2027-01-09T02:00:00Z"), 0L, null, false, null)), new CursorResponse.PageInfo(false, null, 2)));
		String selection = mvc.perform(get(path).cookie(SESSION).param("accommodationId", "101").param("size", "50"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "get", "200", selection);
		assertThat(mapper.readTree(selection).at("/data/wishlists/0/wishlist_accommodation_id").asLong()).isEqualTo(501);
		assertThat(mapper.readTree(selection).at("/data/wishlists/1/is_contained").asBoolean()).isFalse();
		for (String paginated : List.of(path, path + "/accommodations/{id}")) {
			for (String size : List.of("0", "51", "invalid")) {
				String invalid = mvc.perform(get(paginated, 42).cookie(SESSION).param("size", size)).andExpect(status().isBadRequest())
					.andReturn().getResponse().getContentAsString();
				assertDocumentedError(spec, paginated, "get", "400", "C006", invalid);
			}
			String invalid = mvc.perform(get(paginated, 42).cookie(SESSION).param("cursor", "not-a-cursor!"))
				.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, paginated, "get", "400", "C010", invalid);
		}
	}

	@Test
	void wishlistDetailIncludesNameEmptyPagesCurrentCardsAndZeroReviewSummary() throws Exception {
		JsonNode spec = specification("client");
		String path = "/api/v1/members/wishlists/accommodations/{id}";
		WishlistService service = context.getBean(WishlistService.class);
		var info = new WishlistAccommodationResponse.WishlistAccommodationInfo(501, null,
			Instant.parse("2027-01-10T02:00:00.123456Z"), new AccommodationResponse.AccommodationBasicInfo(101, "서울 하우스", null),
			new AddressResponse.AddressSummaryInfo("대한민국", null, "서울", null), ReviewResponse.ReviewSummary.of(null, null), true);
		when(service.findWishlistAccommodations(eq(42L), any(), eq(10L)))
			.thenReturn(new WishlistAccommodationResponse.WishlistAccommodationInfos(List.of(info), new CursorResponse.PageInfo(false, null, 1), "서울 여행"));
		String response = mvc.perform(get(path, 42).cookie(SESSION)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "get", "200", response);
		assertThat(mapper.readTree(response).at("/data/wishlist_accommodations/0/review_summary/total_count").asInt()).isZero();
		assertThat(mapper.readTree(response).at("/data/wishlist_accommodations/0/is_in_wishlist").asBoolean()).isTrue();
		assertThat(mapper.readTree(response).at("/data/wishlist_accommodations/0/memo").isNull()).isTrue();
		when(service.findWishlistAccommodations(eq(42L), any(), eq(10L)))
			.thenReturn(new WishlistAccommodationResponse.WishlistAccommodationInfos(List.of(), new CursorResponse.PageInfo(false, null, 0), "서울 여행"));
		String empty = mvc.perform(get(path, 42).cookie(SESSION)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "get", "200", empty);
		assertThat(mapper.readTree(empty).at("/data/wishlist_name").asText()).isEqualTo("서울 여행");
		assertThat(mapper.readTree(empty).at("/data/wishlist_accommodations").isEmpty()).isTrue();
		for (BaseException error : List.of(new WishlistNotFoundException(), new WishlistAccessDeniedException())) {
			doThrow(error).when(service).findWishlistAccommodations(eq(42L), any(), eq(10L));
			var failed = mvc.perform(get(path, 42).cookie(SESSION)).andExpect(status().is(error.getErrorCode().getStatus().value())).andReturn().getResponse();
			assertDocumentedError(spec, path, "get", Integer.toString(failed.getStatus()), error.getErrorCode().getCode(), failed.getContentAsString());
		}
	}

	@Test
	void wishlistMutationErrorsAndDeleteResponsesMatchTheServicePolicy() throws Exception {
		JsonNode spec = specification("client");
		String lists = "/api/v1/members/wishlists", list = lists + "/{wishlistId}";
		String items = lists + "/accommodations/{id}", item = lists + "/accommodations/{id}";
		WishlistService service = context.getBean(WishlistService.class);
		for (String path : List.of(list, item)) {
			String response = mvc.perform(delete(path, 42).cookie(SESSION)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			validateResponse(spec, path, "delete", "200", response);
			assertThat(mapper.readTree(response)).isEqualTo(mapper.readTree("{\"success\":true}"));
		}
		for (BaseException error : List.of(new WishlistNotFoundException(), new WishlistAccessDeniedException())) {
			doThrow(error).when(service).deleteWishlist(42L, 10L);
			doThrow(error).when(service).updateWishlist(eq(42L), any(), eq(10L));
			var removed = mvc.perform(delete(list, 42).cookie(SESSION)).andExpect(status().is(error.getErrorCode().getStatus().value())).andReturn().getResponse();
			assertDocumentedError(spec, list, "delete", Integer.toString(removed.getStatus()), error.getErrorCode().getCode(), removed.getContentAsString());
			var renamed = mvc.perform(patch(list, 42).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"여행\"}"))
				.andExpect(status().is(error.getErrorCode().getStatus().value())).andReturn().getResponse();
			assertDocumentedError(spec, list, "patch", Integer.toString(renamed.getStatus()), error.getErrorCode().getCode(), renamed.getContentAsString());
		}
		for (BaseException error : List.of(new WishlistNotFoundException(), new WishlistAccessDeniedException(),
			new WishlistAccommodationDuplicateException(), new AccommodationNotFoundException())) {
			doThrow(error).when(service).createWishlistAccommodation(eq(42L), any(), eq(10L));
			var failed = mvc.perform(post(items, 42).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content("{\"accommodation_id\":101}"))
				.andExpect(status().is(error.getErrorCode().getStatus().value())).andReturn().getResponse();
			assertDocumentedError(spec, items, "post", Integer.toString(failed.getStatus()), error.getErrorCode().getCode(), failed.getContentAsString());
		}
		doThrow(new WishlistAccommodationAccessDeniedException()).when(service).deleteWishlistAccommodation(501L, 10L);
		doThrow(new WishlistAccommodationAccessDeniedException()).when(service).updateWishlistAccommodation(eq(501L), any(), eq(10L));
		String removed = mvc.perform(delete(item, 501).cookie(SESSION)).andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, item, "delete", "403", "W005", removed);
		String memo = mvc.perform(patch(item, 501).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content("{\"memo\":\"메모\"}"))
			.andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, item, "patch", "403", "W005", memo);
		assertThat(spec.path("paths").path(item).path("delete").path("responses").has("404")).isFalse();
		doThrow(new MemberNotFoundException()).when(service).createWishlist(any(), eq(10L));
		String inactive = mvc.perform(post(lists).cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"여행\"}"))
			.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, lists, "post", "404", "M001", inactive);
	}

	@Test
	void wishlistAccommodationOperationsShareOneTemplateWithDistinctIdMeanings() throws Exception {
		JsonNode spec = specification("client");
		JsonNode path = spec.path("paths").path("/api/v1/members/wishlists/accommodations/{id}");
		assertThat(path.size()).isEqualTo(4);
		for (String method : List.of("get", "post", "patch", "delete")) {
			JsonNode parameter = java.util.stream.StreamSupport.stream(path.path(method).path("parameters").spliterator(), false)
				.filter(value -> value.path("in").asText().equals("path")).findFirst().orElseThrow();
			assertThat(parameter.path("name").asText()).isEqualTo("id");
			assertThat(parameter.path("description").asText()).contains(
				List.of("get", "post").contains(method) ? "위시리스트 ID" : "연결 ID");
		}
		Set<String> shapes = new HashSet<>();
		spec.path("paths").fieldNames().forEachRemaining(value ->
			assertThat(shapes.add(value.replaceAll("\\{[^}]+}", "{}"))).as(value).isTrue());
	}

	@Test
	void wishlistJsonRequestsRejectUnsupportedContentTypes() throws Exception {
		JsonNode spec = specification("client");
		String lists = "/api/v1/members/wishlists", list = lists + "/{wishlistId}", items = lists + "/accommodations/{id}";
		var requests = List.of(post(lists), patch(list, 42), post(items, 42), patch(items, 501));
		String[] paths = {lists, list, items, items};
		String[] methods = {"post", "patch", "post", "patch"};
		for (int index = 0; index < requests.size(); index++) {
			String body = mvc.perform(requests.get(index).cookie(SESSION).contentType(MediaType.TEXT_PLAIN).content("{}"))
				.andExpect(status().isUnsupportedMediaType()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, paths[index], methods[index], "415", "C011", body);
		}
		verifyNoInteractions(context.getBean(WishlistService.class));
	}

	@Test
	void everyRecentlyViewedOperationRequiresTheSessionWithoutManualIdentityInputs() throws Exception {
		JsonNode spec = specification("client");
		String list = "/api/v1/members/recently-viewed", item = list + "/{accommodationId}";
		var requests = List.of(get(list), post(item, 101), delete(item, 101));
		String[] paths = {list, item, item};
		String[] methods = {"get", "post", "delete"};
		for (int index = 0; index < requests.size(); index++) {
			String body = mvc.perform(requests.get(index)).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, paths[index], methods[index], "401", "M004", body);
			JsonNode operation = spec.path("paths").path(paths[index]).path(methods[index]);
			assertThat(operation.has("requestBody")).isFalse();
			assertThat(operation.path("parameters").findValuesAsText("name"))
				.doesNotContain("memberId", "member_id", "SESSION_ID", "page", "size", "cursor");
		}
		verifyNoInteractions(context.getBean(RecentlyViewedService.class));
	}

	@Test
	void recentlyViewedRecordAndDeleteUseAccommodationIdAndReturnBodylessSuccessData() throws Exception {
		JsonNode spec = specification("client");
		String path = "/api/v1/members/recently-viewed/{accommodationId}";
		RecentlyViewedService service = context.getBean(RecentlyViewedService.class);
		for (String method : List.of("post", "delete")) {
			JsonNode operation = spec.path("paths").path(path).path(method);
			assertThat(operation.path("parameters").findValuesAsText("name")).containsExactly("accommodationId");
			assertThat(operation.path("responses").has("404")).isFalse();
			var request = method.equals("post") ? post(path, 999) : delete(path, 999);
			String body = mvc.perform(request.cookie(SESSION).param("memberId", "777"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			validateResponse(spec, path, method, "200", body);
			assertThat(mapper.readTree(body)).isEqualTo(mapper.readTree("{\"success\":true}"));
			for (String id : List.of("invalid", "9223372036854775808")) {
				var invalid = method.equals("post") ? post(path, id) : delete(path, id);
				String failed = mvc.perform(invalid.cookie(SESSION)).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
				assertDocumentedError(spec, path, method, "400", "C004", failed);
			}
		}
		verify(service).addRecentlyViewed(999L, 10L);
		verify(service).removeRecentlyViewed(999L, 10L);
	}

	@Test
	void recentlyViewedCardsMatchNullableCurrentFieldsUtcTimesAndEmptyHistory() throws Exception {
		JsonNode spec = specification("client");
		String path = "/api/v1/members/recently-viewed";
		RecentlyViewedService service = context.getBean(RecentlyViewedService.class);
		var cards = List.of(
			new AccommodationResponse.RecentlyViewedAccommodationInfo(Instant.parse("2027-01-10T02:00:00.123Z"),
				101L, "서울 하우스", "https://example.invalid/stay.jpg", new AddressResponse.AddressSummaryInfo("대한민국", null, "서울", null),
				ReviewResponse.ReviewSummary.of(2, new BigDecimal("4.50")), true),
			new AccommodationResponse.RecentlyViewedAccommodationInfo(Instant.parse("2027-01-09T00:00:00Z"),
				102L, null, null, new AddressResponse.AddressSummaryInfo(null, null, null, null), null, false));
		when(service.getRecentlyViewed(10L)).thenReturn(AccommodationResponse.RecentlyViewedAccommodationInfos.from(cards));
		String response = mvc.perform(get(path).cookie(SESSION)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "get", "200", response);
		JsonNode data = mapper.readTree(response).path("data");
		assertThat(data.path("total_count").asInt()).isEqualTo(2);
		assertThat(data.at("/accommodations/0/viewed_at").asText()).isEqualTo("2027-01-10T02:00:00.123Z");
		assertThat(data.at("/accommodations/1/accommodation_name").isNull()).isTrue();
		assertThat(data.at("/accommodations/1/thumbnail_url").isNull()).isTrue();
		assertThat(data.at("/accommodations/1/address_summary/country").isNull()).isTrue();
		assertThat(data.at("/accommodations/1/review_summary/total_count").asInt()).isZero();
		assertThat(data.at("/accommodations/1/review_summary/average_rating").decimalValue()).isZero();
		assertThat(data.at("/accommodations/1/is_in_wishlist").asBoolean()).isFalse();
		assertThat(spec.path("paths").path(path).path("get").path("parameters").isEmpty()).isTrue();
		JsonNode fields = spec.at("/components/schemas/AccommodationResponseRecentlyViewedAccommodationInfo/required");
		assertThat(fields.toString()).contains("viewed_at", "accommodation_id", "accommodation_name", "thumbnail_url",
			"address_summary", "review_summary", "is_in_wishlist");
		when(service.getRecentlyViewed(10L)).thenReturn(AccommodationResponse.RecentlyViewedAccommodationInfos.from(List.of()));
		String empty = mvc.perform(get(path).cookie(SESSION)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "get", "200", empty);
		assertThat(mapper.readTree(empty)).isEqualTo(mapper.readTree("{\"success\":true,\"data\":{\"accommodations\":[],\"total_count\":0}}"));
	}


	@Test
	void searchBindsCamelCaseFiltersIsoDatesAndOptionalSessionIdentity() throws Exception {
		var service = context.getBean(AccommodationSearchService.class);
		String path = "/api/v1/search/accommodations";
		mvc.perform(get(path).param("destination", "서울").param("checkIn", "2027-01-10").param("checkOut", "2027-01-12")
			.param("minPrice", "0").param("maxPrice", "200000").param("adultOccupancy", "2").param("childOccupancy", "1")
			.param("infantOccupancy", "1").param("petOccupancy", "1").param("amenityTypes", "WIFI", "PARKING")
			.param("accommodationTypes", "APT").param("topLeftLat", "38").param("topLeftLng", "126")
			.param("bottomRightLat", "37").param("bottomRightLng", "128").param("memberId", "999"))
			.andExpect(status().isOk());
		verify(service).searchAccommodations(argThat(request -> request.getDestination().equals("서울")
			&& request.getCheckIn().equals(LocalDate.parse("2027-01-10")) && request.getCheckOut().equals(LocalDate.parse("2027-01-12"))
			&& request.getMinPrice() == 0 && request.getMaxPrice() == 200000 && request.getAdultOccupancy() == 2
			&& request.getChildOccupancy() == 1 && request.getInfantOccupancy() == 1 && request.getPetOccupancy() == 1
			&& request.getAmenityTypes().equals(List.of("WIFI", "PARKING")) && request.getAccommodationTypes().equals(List.of("APT"))),
			argThat(bounds -> bounds.getTopLeftLat() == 38 && bounds.getTopLeftLng() == 126
				&& bounds.getBottomRightLat() == 37 && bounds.getBottomRightLng() == 128),
			argThat(page -> page.getPageNumber() == 0 && page.getPageSize() == 18), isNull());
		mvc.perform(get(path).cookie(SESSION).param("memberId", "999")).andExpect(status().isOk());
		verify(service).searchAccommodations(any(), any(), any(), eq(10L));
		mvc.perform(get(path).cookie(new Cookie("SESSION_ID", "expired"))).andExpect(status().isOk());
		verify(service, org.mockito.Mockito.times(2)).searchAccommodations(any(), any(), any(), isNull());
		assertThat(specification("client").path("paths").path(path).path("get").path("security").isEmpty()).isTrue();
	}

	@Test
	void searchNormalizesPageAndSizeWithoutAcceptingUserSort() throws Exception {
		var service = context.getBean(AccommodationSearchService.class);
		String path = "/api/v1/search/accommodations";
		String[][] values = {{"-1", "0", "0"}, {"invalid", "invalid", "0"}, {"15", "100", "14"},
			{"2147483647", "1", "14"}, {"2", "18", "2"}};
		for (String[] value : values) {
			mvc.perform(get(path).param("page", value[0]).param("size", value[1]).param("sort", "basePrice,asc"))
				.andExpect(status().isOk());
		}
		var pages = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
		verify(service, org.mockito.Mockito.times(values.length)).searchAccommodations(any(), any(), pages.capture(), isNull());
		assertThat(pages.getAllValues()).extracting(page -> page.getPageNumber()).containsExactly(0, 0, 14, 14, 2);
		assertThat(pages.getAllValues()).allSatisfy(page -> assertThat(page.getPageSize()).isEqualTo(18));
	}

	@Test
	void searchRejectsMalformedFiltersBeforeCallingTheSearchEngine() throws Exception {
		String path = "/api/v1/search/accommodations";
		JsonNode spec = specification("client");
		var requests = List.of(
			get(path).param("minPrice", "-1"), get(path).param("minPrice", "not-a-number"),
			get(path).param("minPrice", "2147483648"), get(path).param("minPrice", "100").param("maxPrice", "99"),
			get(path).param("adultOccupancy", "-1"), get(path).param("childOccupancy", "-1"),
			get(path).param("infantOccupancy", "-1"), get(path).param("petOccupancy", "-1"),
			get(path).param("checkIn", "2027-01-10"), get(path).param("checkOut", "2027-01-12"),
			get(path).param("checkIn", "2027-01-12").param("checkOut", "2027-01-12"),
			get(path).param("checkIn", "2027-02-30").param("checkOut", "2027-03-01"),
			get(path).param("topLeftLat", "38"), get(path).param("topLeftLat", "not-a-number"),
			get(path).param("topLeftLat", "91").param("topLeftLng", "126").param("bottomRightLat", "37").param("bottomRightLng", "128"),
			get(path).param("topLeftLat", "38").param("topLeftLng", "NaN").param("bottomRightLat", "37").param("bottomRightLng", "128"),
			get(path).param("topLeftLat", "36").param("topLeftLng", "126").param("bottomRightLat", "37").param("bottomRightLng", "128"));
		for (var request : requests) {
			String response = mvc.perform(request).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, path, "get", "400", "C001", response);
		}
		verifyNoInteractions(context.getBean(AccommodationSearchService.class));
	}


	@Test
	void searchDocumentsEveryFilterWithoutInternalOrRequiredCookieInputs() throws Exception {
		JsonNode operation = specification("client").path("paths").path("/api/v1/search/accommodations").path("get");
		assertThat(operation.has("requestBody")).isFalse();
		assertThat(operation.path("parameters").findValuesAsText("name")).containsExactlyInAnyOrder(
			"destination", "minPrice", "maxPrice", "checkIn", "checkOut", "adultOccupancy", "childOccupancy", "infantOccupancy", "petOccupancy",
			"amenityTypes", "accommodationTypes", "topLeftLat", "topLeftLng", "bottomRightLat", "bottomRightLng", "page", "size");
		for (JsonNode parameter : operation.path("parameters")) {
			assertThat(parameter.path("description").asText()).isNotBlank();
			assertThat(parameter.path("required").asBoolean()).isFalse();
			if (Set.of("amenityTypes", "accommodationTypes").contains(parameter.path("name").asText())) {
				assertThat(parameter.path("description").asText()).contains("미지원", "반영되지");
			}
			if (Set.of("checkIn", "checkOut").contains(parameter.path("name").asText())) {
				assertThat(parameter.at("/schema/format").asText()).isEqualTo("date");
			}
		}
		assertThat(operation.path("responses").properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("200", "400", "503", "500");
	}

	@Test
	void searchResponsesMatchNullableIndexedFieldsEmptyPagesAndUnavailableErrors() throws Exception {
		var service = context.getBean(AccommodationSearchService.class);
		JsonNode spec = specification("client");
		String path = "/api/v1/search/accommodations";
		var card = AccommodationSearchResponse.AccommodationSearchInfo.from(AccommodationDocument.builder().accommodationId(101L).build(), false);
		var page = new AccommodationSearchResponse.PageInfo(18, 0, 1, 1, true, true, false, false);
		when(service.searchAccommodations(any(), any(), any(), isNull()))
			.thenReturn(new AccommodationSearchResponse.AccommodationSearchInfos(List.of(card), page));
		String response = mvc.perform(get(path).param("destination", "서울")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "get", "200", response);
		JsonNode item = mapper.readTree(response).at("/data/stay_search_result_listing/0");
		for (String nullable : List.of("name", "accommodation_thumbnail_url", "type", "base_price", "currency")) {
			assertThat(item.path(nullable).isNull()).isTrue();
		}
		assertThat(item.at("/coordinate/latitude").isNull()).isTrue();
		assertThat(item.at("/address_summary/country").isNull()).isTrue();
		assertThat(item.at("/review_summary/total_count").asInt()).isZero();
		assertThat(item.at("/review_summary/average_rating").decimalValue()).isZero();
		assertThat(item.path("is_in_wishlist").asBoolean()).isFalse();
		assertThat(mapper.readTree(response).at("/data/page_info/is_first").asBoolean()).isTrue();
		assertThat(mapper.readTree(response).at("/data/page_info/is_last").asBoolean()).isTrue();
		when(service.searchAccommodations(any(), any(), any(), isNull()))
			.thenReturn(new AccommodationSearchResponse.AccommodationSearchInfos(List.of(), AccommodationSearchResponse.PageInfo.fail(18, 0)));
		String empty = mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "get", "200", empty);
		assertThat(mapper.readTree(empty).at("/data/stay_search_result_listing").isEmpty()).isTrue();
		assertThat(mapper.readTree(empty).at("/data/page_info/total_elements").asLong()).isZero();
		when(service.searchAccommodations(any(), any(), any(), isNull())).thenThrow(new SearchUnavailableException(new java.io.IOException("synthetic unavailable")));
		var unavailable = mvc.perform(get(path).param("destination", "서울")).andExpect(status().isServiceUnavailable()).andReturn().getResponse();
		assertDocumentedError(spec, path, "get", "503", "SE001", unavailable.getContentAsString());
		assertThat(unavailable.getHeader("Retry-After")).isNull();
	}

	@Test
	void commonCodePublicListsAreAnonymousAndMissingGroupsAreDocumented() throws Exception {
		String path = "/api/v1/common-codes/{group}";
		JsonNode spec = specification("client");
		var service = context.getBean(CommonCodeService.class);
		when(service.getCodes("AMENITY_TYPE")).thenReturn(List.of(new CommonCodeResponse("WIFI", "무선 인터넷", -1)));
		String response = mvc.perform(get(path, "amenity_type")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "get", "200", response);
		assertThat(mapper.readTree(response).at("/data/0/code").asText()).isEqualTo("WIFI");
		assertThat(mapper.readTree(response).at("/data/0/sort_order").asInt()).isEqualTo(-1);
		assertThat(mapper.readTree(response).at("/data/0").has("active")).isFalse();
		assertThat(spec.path("paths").path(path).path("get").path("security").isEmpty()).isTrue();
		when(service.getCodes("INACTIVE_GROUP")).thenReturn(List.of());
		String empty = mvc.perform(get(path, "inactive_group").cookie(new Cookie("SESSION_ID", "expired")))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "get", "200", empty);
		assertThat(mapper.readTree(empty)).isEqualTo(mapper.readTree("{\"success\":true,\"data\":[]}"));
		when(service.getCodes("MISSING_GROUP")).thenThrow(new CommonCodeGroupNotFoundException());
		String missing = mvc.perform(get(path, "missing_group")).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, path, "get", "404", "CC001", missing);
	}

	@Test
	void everyCommonCodeAdminOperationRequiresActiveAdminWithoutManualSessionInput() throws Exception {
		JsonNode spec = specification("admin");
		String groups = "/api/v1/admin/common-code-groups", group = groups + "/{groupCode}";
		String codes = "/api/v1/admin/common-codes/{group}", code = codes + "/{code}";
		var paths = List.of(groups, groups, group, codes, codes, code);
		var methods = List.of("get", "post", "patch", "get", "post", "patch");
		var requests = List.of(get(groups), post(groups), patch(group, "AMENITY_TYPE"),
			get(codes, "AMENITY_TYPE"), post(codes, "AMENITY_TYPE"), patch(code, "AMENITY_TYPE", "WIFI"));
		for (int i = 0; i < requests.size(); i++) {
			var request = requests.get(i);
			String absent = mvc.perform(request).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, paths.get(i), methods.get(i), "401", "M004", absent);
			String forbidden = mvc.perform(request.cookie(SESSION)).andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, paths.get(i), methods.get(i), "403", "M006", forbidden);
			JsonNode operation = spec.path("paths").path(paths.get(i)).path(methods.get(i));
			assertThat(operation.at("/security/0/sessionCookie").isArray()).isTrue();
			assertThat(operation.path("parameters").findValuesAsText("name")).doesNotContain("SESSION_ID", "memberId", "member_id", "page", "size", "cursor");
		}
		verifyNoInteractions(context.getBean(CommonCodeAdminService.class));
	}

	@Test
	void commonCodeAdminListsIncludeInactiveEntriesAndNullableDescriptions() throws Exception {
		allowDocumentationAdmin();
		JsonNode spec = specification("admin");
		var service = context.getBean(CommonCodeAdminService.class);
		String groups = "/api/v1/admin/common-code-groups", codes = "/api/v1/admin/common-codes/{group}";
		when(service.getGroups()).thenReturn(List.of(new CommonCodeGroupResponse("AMENITY_TYPE", "편의시설", null, false)));
		when(service.getAll("AMENITY_TYPE")).thenReturn(List.of(new CommonCodeAdminResponse("WIFI", "무선 인터넷", null, 0, false)));
		String groupBody = mvc.perform(get(groups).cookie(SESSION)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String codeBody = mvc.perform(get(codes, "amenity_type").cookie(SESSION)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, groups, "get", "200", groupBody);
		validateResponse(spec, codes, "get", "200", codeBody);
		for (String body : List.of(groupBody, codeBody)) {
			assertThat(mapper.readTree(body).at("/data/0/description").isNull()).isTrue();
			assertThat(mapper.readTree(body).at("/data/0/active").asBoolean()).isFalse();
			assertThat(mapper.readTree(body).at("/data/0").has("is_active")).isFalse();
		}
		when(service.getAll("EMPTY_GROUP")).thenReturn(List.of());
		String empty = mvc.perform(get(codes, "empty_group").cookie(SESSION)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, codes, "get", "200", empty);
		when(service.getAll("MISSING_GROUP")).thenThrow(new CommonCodeGroupNotFoundException());
		String missing = mvc.perform(get(codes, "missing_group").cookie(SESSION)).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, codes, "get", "404", "CC001", missing);
	}

	@Test
	void commonCodeGroupWritesBindIsActiveAndReturnGroupIdentity() throws Exception {
		allowDocumentationAdmin();
		JsonNode spec = specification("admin");
		var service = context.getBean(CommonCodeAdminService.class);
		String groups = "/api/v1/admin/common-code-groups", group = groups + "/{groupCode}";
		when(service.createGroup(any())).thenReturn(new CommonCodeGroupResponse("EXAMPLE_GROUP", "예시 그룹", null, false));
		var created = mvc.perform(post(groups).cookie(SESSION).contentType(MediaType.APPLICATION_JSON)
			.content("{\"group_code\":\" example_group \",\"group_name\":\"예시 그룹\",\"is_active\":false}"))
			.andExpect(status().isCreated()).andReturn().getResponse();
		validateResponse(spec, groups, "post", "201", created.getContentAsString());
		assertThat(created.getHeader("Location")).isNull();
		verify(service).createGroup(new CommonCodeGroupRequest.Create("example_group", "예시 그룹", null, false));
		when(service.updateGroup(eq("EXAMPLE_GROUP"), any())).thenReturn(new CommonCodeGroupResponse("EXAMPLE_GROUP", "예시 그룹", "", false));
		for (String request : List.of("{}", "{\"group_name\":null,\"description\":\"\",\"is_active\":false}")) {
			String updated = mvc.perform(patch(group, "example_group").cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			validateResponse(spec, group, "patch", "200", updated);
		}
		verify(service).updateGroup("EXAMPLE_GROUP", new CommonCodeGroupRequest.Update(null, "", false));
		validateInstance(spec, spec.at("/components/schemas/CommonCodeGroupRequestUpdate"), mapper.readTree("{\"group_name\":null,\"description\":null,\"is_active\":null}"));
		when(service.createGroup(any())).thenThrow(new CommonCodeGroupDuplicateException());
		String duplicate = mvc.perform(post(groups).cookie(SESSION).contentType(MediaType.APPLICATION_JSON)
			.content("{\"group_code\":\"EXAMPLE_GROUP\",\"group_name\":\"예시 그룹\"}"))
			.andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, groups, "post", "409", "CC004", duplicate);
		when(service.updateGroup(eq("EXAMPLE_GROUP"), any())).thenThrow(new CommonCodeGroupNotFoundException());
		String missing = mvc.perform(patch(group, "example_group").cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, group, "patch", "404", "CC001", missing);
	}

	@Test
	void commonCodeWritesBindSnakeCasePatchFieldsAndDocumentActualNotFoundCode() throws Exception {
		allowDocumentationAdmin();
		JsonNode spec = specification("admin");
		var service = context.getBean(CommonCodeAdminService.class);
		String codes = "/api/v1/admin/common-codes/{group}", code = codes + "/{code}";
		when(service.create(eq("AMENITY_TYPE"), any())).thenReturn(new CommonCodeAdminResponse("WIFI", "무선 인터넷", null, -1, false));
		String request = "{\"code\":\"　wifi　\",\"name\":\"무선 인터넷\",\"sort_order\":-1,\"is_active\":false}";
		var created = mvc.perform(post(codes, "amenity_type").cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
			.andExpect(status().isCreated()).andReturn().getResponse();
		validateResponse(spec, codes, "post", "201", created.getContentAsString());
		assertThat(created.getHeader("Location")).isNull();
		verify(service).create("AMENITY_TYPE", new CommonCodeRequest.Create("wifi", "무선 인터넷", null, -1, false));
		when(service.update(eq("AMENITY_TYPE"), eq("wifi"), any())).thenReturn(new CommonCodeAdminResponse("WIFI", "무선 인터넷", "", 0, false));
		for (String patchBody : List.of("{}", "{\"name\":null,\"description\":\"\",\"sort_order\":0,\"is_active\":false}")) {
			String updated = mvc.perform(patch(code, "amenity_type", "wifi").cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(patchBody))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			validateResponse(spec, code, "patch", "200", updated);
		}
		verify(service).update("AMENITY_TYPE", "wifi", new CommonCodeRequest.Update(null, "", 0, false));
		validateInstance(spec, spec.at("/components/schemas/CommonCodeRequestUpdate"), mapper.readTree("{\"name\":null,\"description\":null,\"sort_order\":null,\"is_active\":null}"));
		when(service.create(eq("AMENITY_TYPE"), any())).thenThrow(new CommonCodeDuplicateException());
		String duplicate = mvc.perform(post(codes, "amenity_type").cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
			.andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, codes, "post", "409", "CC003", duplicate);
		when(service.create(eq("MISSING_GROUP"), any())).thenThrow(new CommonCodeGroupNotFoundException());
		String missingGroup = mvc.perform(post(codes, "missing_group").cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content(request))
			.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, codes, "post", "404", "CC001", missingGroup);
		when(service.update(eq("MISSING_GROUP"), eq("wifi"), any())).thenThrow(new CommonCodeNotFoundException());
		String missingCode = mvc.perform(patch(code, "missing_group", "wifi").cookie(SESSION).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
		assertDocumentedError(spec, code, "patch", "404", "CC002", missingCode);
		assertThat(spec.path("paths").path(code).path("patch").at("/responses/404/content/application~1json/examples").properties())
			.extracting(Map.Entry::getKey).containsExactly("CC002");
	}

	@Test
	void commonCodeWriteValidationAndMediaTypesMatchTheContract() throws Exception {
		allowDocumentationAdmin();
		JsonNode spec = specification("admin");
		String groups = "/api/v1/admin/common-code-groups", group = groups + "/{groupCode}";
		String codes = "/api/v1/admin/common-codes/{group}", code = codes + "/{code}";
		var paths = List.of(groups, group, codes, code);
		var methods = List.of("post", "patch", "post", "patch");
		var requests = List.of(post(groups), patch(group, "EXAMPLE_GROUP"), post(codes, "AMENITY_TYPE"), patch(code, "AMENITY_TYPE", "WIFI"));
		var bodies = List.of(
			List.of("{}", "{\"group_code\":\"BAD-CODE\",\"group_name\":\"이름\"}", "{\"group_code\":\"EXAMPLE_GROUP\",\"group_name\":\"　\"}",
				mapper.writeValueAsString(Map.of("group_code", "A".repeat(51), "group_name", "이름")),
				mapper.writeValueAsString(Map.of("group_code", "EXAMPLE_GROUP", "group_name", "이".repeat(101))),
				mapper.writeValueAsString(Map.of("group_code", "EXAMPLE_GROUP", "group_name", "이름", "description", "설".repeat(256)))),
			List.of("{\"group_name\":\"\"}", "{\"group_name\":\"　\"}", mapper.writeValueAsString(Map.of("group_name", "이".repeat(101))),
				mapper.writeValueAsString(Map.of("description", "설".repeat(256)))),
			List.of("{}", "{\"code\":\"BAD-CODE\",\"name\":\"이름\"}", "{\"code\":\"WIFI\",\"name\":\"　\"}",
				mapper.writeValueAsString(Map.of("code", "A".repeat(51), "name", "이름")),
				mapper.writeValueAsString(Map.of("code", "WIFI", "name", "이".repeat(101))),
				mapper.writeValueAsString(Map.of("code", "WIFI", "name", "이름", "description", "설".repeat(256)))),
			List.of("{\"name\":\"\"}", "{\"name\":\"　\"}", mapper.writeValueAsString(Map.of("name", "이".repeat(101))),
				mapper.writeValueAsString(Map.of("description", "설".repeat(256))), "{\"sort_order\":2147483648}"));
		for (int i = 0; i < requests.size(); i++) {
			var request = requests.get(i).cookie(SESSION);
			for (String body : java.util.stream.Stream.concat(bodies.get(i).stream(), java.util.stream.Stream.of("", "null", "{" , "{\"is_active\":\"invalid\"}")).toList()) {
				String invalid = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body))
					.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
				assertDocumentedError(spec, paths.get(i), methods.get(i), "400", "C001", invalid);
			}
			String unsupported = mvc.perform(request.contentType(MediaType.TEXT_PLAIN).content("{}"))
				.andExpect(status().isUnsupportedMediaType()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, paths.get(i), methods.get(i), "415", "C011", unsupported);
		}
		verifyNoInteractions(context.getBean(CommonCodeAdminService.class));
	}


	@Test
	void settlementRejectsMalformedMonthsAndReversedBackfillBeforeInvokingServices() throws Exception {
		allowDocumentationAdmin();
		for (String month : List.of("2027-13", "2027-1", "2027-01-01", "", " 2027-01 ", "0000-01", "0999-12", "+10000-01")) {
			mvc.perform(get("/api/v1/admin/settlements").cookie(SESSION).param("month", month)).andExpect(status().isBadRequest());
			mvc.perform(post("/api/v1/admin/settlements/generate").cookie(SESSION).param("month", month)).andExpect(status().isBadRequest());
			mvc.perform(post("/api/v1/admin/settlements/backfill").cookie(SESSION).param("from", month).param("to", "2027-02"))
				.andExpect(status().isBadRequest());
		}
		mvc.perform(post("/api/v1/admin/settlements/backfill").cookie(SESSION).param("from", "2027-02").param("to", "2027-01"))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(context.getBean(SettlementService.class));
	}

	@Test
	void settlementRejectsReversedOrUnsupportedHostDateRanges() throws Exception {
		String path = "/api/v1/profile/host/settlements";
		mvc.perform(get(path).cookie(SESSION).param("from", "2027-02-01").param("to", "2027-01-01"))
			.andExpect(status().isBadRequest());
		mvc.perform(get(path).cookie(SESSION).param("from", "0999-12-31").param("to", "2027-01-01"))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(context.getBean(SettlementService.class));
	}

	@Test
	void everySettlementOperationUsesSessionIdentityAndAdminActionsRequireAdmin() throws Exception {
		var paths = List.of("/api/v1/profile/host/settlements", "/api/v1/profile/host/settlements/summary", "/api/v1/profile/host/settlements/{settlementId}",
			"/api/v1/admin/settlements", "/api/v1/admin/settlements/generate", "/api/v1/admin/settlements/backfill", "/api/v1/admin/settlements/{settlementId}/pay");
		var methods = List.of("get", "get", "get", "get", "post", "post", "post");
		var requests = List.of(get(paths.get(0)), get(paths.get(1)), get(paths.get(2), 101), get(paths.get(3)),
			post(paths.get(4)), post(paths.get(5)), post(paths.get(6), 101));
		JsonNode client = specification("client"), admin = specification("admin");
		for (int i = 0; i < requests.size(); i++) {
			JsonNode spec = i < 3 ? client : admin;
			String absent = mvc.perform(requests.get(i)).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, paths.get(i), methods.get(i), "401", "M004", absent);
			JsonNode operation = spec.path("paths").path(paths.get(i)).path(methods.get(i));
			assertThat(operation.path("parameters").findValuesAsText("name")).doesNotContain("hostId", "memberId", "SESSION_ID", "page", "size", "cursor");
			assertThat(operation.has("requestBody")).isFalse();
			if (i >= 3) {
				String forbidden = mvc.perform(requests.get(i).cookie(SESSION)).andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
				assertDocumentedError(spec, paths.get(i), methods.get(i), "403", "M006", forbidden);
			}
		}
		verifyNoInteractions(context.getBean(SettlementService.class));
	}

	@Test
	void settlementHostReadsMatchDatesNullableNamesAmountsAndSessionOwnership() throws Exception {
		JsonNode spec = specification("client");
		var service = context.getBean(SettlementService.class);
		String list = "/api/v1/profile/host/settlements", summary = list + "/summary", detail = list + "/{settlementId}";
		var settlement = documentationSettlement();
		when(service.getHostSettlements(eq(10L), any(), any())).thenReturn(List.of(SettlementResponse.HostSettlement.from(settlement)));
		String body = mvc.perform(get(list).cookie(SESSION).param("from", "2027-01-01").param("to", "2027-03-31").param("hostId", "999"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, list, "get", "200", body);
		assertThat(mapper.readTree(body).at("/data/0/settlement_month").asText()).isEqualTo("2027-01-01");
		assertThat(mapper.readTree(body).at("/data/0/settled_at").isNull()).isTrue();
		verify(service).getHostSettlements(10L, LocalDate.parse("2027-01-01"), LocalDate.parse("2027-03-31"));
		when(service.getHostSummary(10L)).thenReturn(new SettlementResponse.HostSummary(0, 0, 0, 0));
		String zero = mvc.perform(get(summary).cookie(SESSION)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, summary, "get", "200", zero);
		assertThat(mapper.readTree(zero).path("data")).isEqualTo(mapper.readTree("{\"pending_payout_total\":0,\"pending_count\":0,\"paid_payout_total\":0,\"paid_count\":0}"));
		settlement.markPaid(Instant.parse("2027-02-01T04:00:00Z"));
		when(service.getSettlementDetail(101L, 10L)).thenReturn(SettlementResponse.SettlementDetail.of(settlement,
			List.of(new SettlementResponse.LineItem(201L, null, 150000, 20000, 130000))));
		String paid = mvc.perform(get(detail, 101).cookie(SESSION).param("hostId", "999")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, detail, "get", "200", paid);
		assertThat(mapper.readTree(paid).at("/data/settled_at").asText()).isEqualTo("2027-02-01T04:00:00Z");
		assertThat(mapper.readTree(paid).at("/data/items/0/accommodation_name").isNull()).isTrue();
		when(service.getHostSettlements(eq(10L), any(), any())).thenReturn(List.of());
		String empty = mvc.perform(get(list).cookie(SESSION).param("from", "2027-01-01").param("to", "2027-01-01"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, list, "get", "200", empty);
		assertThat(mapper.readTree(empty).path("data").isEmpty()).isTrue();
	}

	@Test
	void settlementAdminReadsAndActionsUseQueryMonthsAndBodylessSuccess() throws Exception {
		allowDocumentationAdmin();
		var service = context.getBean(SettlementService.class);
		JsonNode spec = specification("admin");
		String list = "/api/v1/admin/settlements", generate = list + "/generate", backfill = list + "/backfill", pay = list + "/{settlementId}/pay";
		var negative = Settlement.createPending(10L, LocalDate.parse("2027-01-01"), 0, 50, new BigDecimal("0.03"));
		org.springframework.test.util.ReflectionTestUtils.setField(negative, "id", 102L);
		when(service.getSettlements(any(), any())).thenReturn(List.of(SettlementResponse.AdminSettlement.from(negative)));
		String body = mvc.perform(get(list).cookie(SESSION).param("month", "2027-01").param("status", "PENDING"))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, list, "get", "200", body);
		assertThat(mapper.readTree(body).at("/data/0/payout_amount").asLong()).isEqualTo(-48);
		verify(service).getSettlements(java.time.YearMonth.of(2027, 1), SettlementStatus.PENDING);
		mvc.perform(get(list).cookie(SESSION).param("month", "2027-01").param("status", "")).andExpect(status().isOk());
		verify(service).getSettlements(java.time.YearMonth.of(2027, 1), null);
		var requests = List.of(post(generate).param("month", "2027-01"), post(backfill).param("from", "2027-01").param("to", "2027-03"), post(pay, 101));
		var paths = List.of(generate, backfill, pay);
		for (int i = 0; i < requests.size(); i++) {
			String success = mvc.perform(requests.get(i).cookie(SESSION)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			validateResponse(spec, paths.get(i), "post", "200", success);
			assertThat(mapper.readTree(success)).isEqualTo(mapper.readTree("{\"success\":true}"));
		}
		verify(service).generateMonth(java.time.YearMonth.of(2027, 1));
		verify(service).backfill(java.time.YearMonth.of(2027, 1), java.time.YearMonth.of(2027, 3));
		verify(service).markPaid(101L);
	}

	@Test
	void settlementMissingInputsMalformedTypesAndBusinessErrorsAreDocumented() throws Exception {
		allowDocumentationAdmin();
		var service = context.getBean(SettlementService.class);
		JsonNode client = specification("client"), admin = specification("admin");
		String host = "/api/v1/profile/host/settlements", detail = host + "/{settlementId}";
		String list = "/api/v1/admin/settlements", generate = list + "/generate", backfill = list + "/backfill", pay = list + "/{settlementId}/pay";
		var missingRequests = List.of(get(host).param("to", "2027-02-01"), get(list), post(generate), post(backfill).param("from", "2027-01"));
		var missingPaths = List.of(host, list, generate, backfill);
		for (int i = 0; i < missingRequests.size(); i++) {
			String body = mvc.perform(missingRequests.get(i).cookie(SESSION)).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(i == 0 ? client : admin, missingPaths.get(i), i < 2 ? "get" : "post", "400", "C001", body);
		}
		var invalidRequests = List.of(get(host).param("from", "2027-02-30").param("to", "2027-03-01"), get(list).param("month", "2027-01").param("status", "pending"),
			get(detail, "invalid"), post(pay, "invalid"));
		var invalidPaths = List.of(host, list, detail, pay);
		for (int i = 0; i < invalidRequests.size(); i++) {
			String body = mvc.perform(invalidRequests.get(i).cookie(SESSION)).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(i == 0 || i == 2 ? client : admin, invalidPaths.get(i), i == 3 ? "post" : "get", "400", "C004", body);
		}
		for (var exception : List.of(new SettlementNotFoundException(), new SettlementAccessDeniedException())) {
			doThrow(exception).when(service).getSettlementDetail(101L, 10L);
			String body = mvc.perform(get(detail, 101).cookie(SESSION)).andExpect(status().is(exception.getErrorCode().getStatus().value()))
				.andReturn().getResponse().getContentAsString();
			assertDocumentedError(client, detail, "get", Integer.toString(exception.getErrorCode().getStatus().value()), exception.getErrorCode().getCode(), body);
		}
		for (var exception : List.of(new SettlementNotFoundException(), new SettlementAlreadyPaidException(), new SettlementMonthNotClosedException())) {
			doThrow(exception).when(service).markPaid(101L);
			String body = mvc.perform(post(pay, 101).cookie(SESSION)).andExpect(status().is(exception.getErrorCode().getStatus().value()))
				.andReturn().getResponse().getContentAsString();
			assertDocumentedError(admin, pay, "post", Integer.toString(exception.getErrorCode().getStatus().value()), exception.getErrorCode().getCode(), body);
		}
	}


	@Test
	void revenueStatsRejectsReversedAndUnsupportedDateRangesBeforeCallingServices() throws Exception {
		allowDocumentationAdmin();
		for (var range : List.of(List.of("2027-02-01", "2027-01-01"), List.of("0999-12-31", "2027-01-01"),
			List.of("2027-01-01", "+10000-01-01"))) {
			mvc.perform(get("/api/v1/admin/stats/revenue").cookie(SESSION).param("from", range.get(0)).param("to", range.get(1)))
				.andExpect(status().isBadRequest());
			mvc.perform(post("/api/v1/admin/stats/revenue/recompute").cookie(SESSION).param("from", range.get(0)).param("to", range.get(1)))
				.andExpect(status().isBadRequest());
		}
		verifyNoInteractions(context.getBean(RevenueStatsService.class));
	}

	@Test
	void revenueStatsOperationsRequireActiveAdminAndOnlyDateQueryInputs() throws Exception {
		JsonNode spec = specification("admin");
		var paths = List.of("/api/v1/admin/stats/revenue", "/api/v1/admin/stats/revenue/recompute");
		var methods = List.of("get", "post");
		var requests = List.of(get(paths.get(0)), post(paths.get(1)));
		for (int i = 0; i < requests.size(); i++) {
			var request = requests.get(i).param("from", "2027-01-01").param("to", "2027-01-31");
			String absent = mvc.perform(request).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, paths.get(i), methods.get(i), "401", "M004", absent);
			String forbidden = mvc.perform(request.cookie(SESSION)).andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
			assertDocumentedError(spec, paths.get(i), methods.get(i), "403", "M006", forbidden);
			JsonNode operation = spec.path("paths").path(paths.get(i)).path(methods.get(i));
			assertThat(operation.has("requestBody")).isFalse();
			assertThat(operation.path("parameters").findValuesAsText("name")).containsExactly("from", "to");
			for (JsonNode parameter : operation.path("parameters")) {
				assertThat(parameter.path("in").asText()).isEqualTo("query");
				assertThat(parameter.path("required").asBoolean()).isTrue();
				assertThat(parameter.at("/schema/format").asText()).isEqualTo("date");
				assertThat(parameter.path("description").asText()).contains("UTC");
			}
		}
		verifyNoInteractions(context.getBean(RevenueStatsService.class));
	}

	@Test
	void revenueStatsReadAndRecomputeMatchSparseNegativeAmountsAndBodylessSuccess() throws Exception {
		allowDocumentationAdmin();
		var service = context.getBean(RevenueStatsService.class);
		JsonNode spec = specification("admin");
		String path = "/api/v1/admin/stats/revenue";
		LocalDate from = LocalDate.of(2027, 1, 1), to = LocalDate.of(2027, 1, 31);
		when(service.getDailyRevenue(from, to)).thenReturn(new RevenueStatsResponse.DailyRevenues(from, to, "stats", List.of(
			new RevenueStatsResponse.DailyRevenue(from, 150000, 20000, 130000, 2, 1),
			new RevenueStatsResponse.DailyRevenue(from.plusDays(2), 0, 50, -50, 0, 2))));
		String body = mvc.perform(get(path).cookie(SESSION).param("from", from.toString()).param("to", to.toString()))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "get", "200", body);
		assertThat(mapper.readTree(body).path("data")).isEqualTo(mapper.readTree("""
			{"from":"2027-01-01","to":"2027-01-31","source":"stats","items":[
			  {"date":"2027-01-01","gross_amount":150000,"refund_amount":20000,"net_amount":130000,"payment_count":2,"refund_count":1},
			  {"date":"2027-01-03","gross_amount":0,"refund_amount":50,"net_amount":-50,"payment_count":0,"refund_count":2}]}
			"""));
		verify(service).getDailyRevenue(from, to);
		when(service.getDailyRevenue(from, from)).thenReturn(new RevenueStatsResponse.DailyRevenues(from, from, "stats", List.of()));
		String empty = mvc.perform(get(path).cookie(SESSION).param("from", from.toString()).param("to", from.toString()))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		validateResponse(spec, path, "get", "200", empty);
		assertThat(mapper.readTree(empty).at("/data/items").isEmpty()).isTrue();
		for (String date : List.of("1000-01-01", "9999-12-31")) {
			String success = mvc.perform(post(path + "/recompute").cookie(SESSION).param("from", date).param("to", date))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			validateResponse(spec, path + "/recompute", "post", "200", success);
			assertThat(mapper.readTree(success)).isEqualTo(mapper.readTree("{\"success\":true}"));
			verify(service).backfill(LocalDate.parse(date), LocalDate.parse(date));
		}
	}

	@Test
	void revenueStatsMissingMalformedAndReversedDatesMatchDocumentedErrors() throws Exception {
		allowDocumentationAdmin();
		JsonNode spec = specification("admin");
		String path = "/api/v1/admin/stats/revenue";
		for (String method : List.of("get", "post")) {
			String endpoint = method.equals("get") ? path : path + "/recompute";
			for (var values : List.of(List.of("2027-02-01", "2027-01-01", "C001"),
				List.of("0999-12-31", "2027-01-01", "C001"), List.of("2027-01-01", "+10000-01-01", "C001"),
				List.of("2027-02-30", "2027-03-01", "C004"), List.of("2027-1-1", "2027-03-01", "C004"),
				List.of("", "2027-03-01", "C001"))) {
				var request = method.equals("get") ? get(endpoint) : post(endpoint);
				String body = mvc.perform(request.cookie(SESSION).param("from", values.get(0)).param("to", values.get(1)))
					.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
				assertDocumentedError(spec, endpoint, method, "400", values.get(2), body);
			}
			for (String present : List.of("from", "to")) {
				var request = method.equals("get") ? get(endpoint) : post(endpoint);
				String body = mvc.perform(request.cookie(SESSION).param(present, "2027-01-01"))
					.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
				assertDocumentedError(spec, endpoint, method, "400", "C001", body);
			}
		}
		verifyNoInteractions(context.getBean(RevenueStatsService.class));
	}

	@Test
	void everyJsonBodyOperationDocumentsMalformedMissingAndUnsupportedBodies() throws Exception {
		allowDocumentationAdmin();
		for (String group : List.of("client", "admin")) {
			JsonNode spec = specification(group);
			var paths = spec.path("paths").fields();
			while (paths.hasNext()) {
				var path = paths.next();
				var operations = path.getValue().fields();
				while (operations.hasNext()) {
					var operation = operations.next();
					if (!operation.getValue().at("/requestBody/content").has("application/json")) {
						continue;
					}
					String uri = documentationUri(path.getKey(), operation.getValue());
					for (String json : List.of("", "{")) {
						String body = mvc.perform(request(org.springframework.http.HttpMethod.valueOf(operation.getKey().toUpperCase()), uri)
							.cookie(SESSION).header("Idempotency-Key", "documentation-checkout")
							.contentType(MediaType.APPLICATION_JSON).content(json))
							.andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
						assertDocumentedError(spec, path.getKey(), operation.getKey(), "400", "C001", body);
					}
					String unsupported = mvc.perform(request(org.springframework.http.HttpMethod.valueOf(operation.getKey().toUpperCase()), uri)
						.cookie(SESSION).header("Idempotency-Key", "documentation-checkout")
						.contentType(MediaType.TEXT_PLAIN).content("{}"))
						.andExpect(status().isUnsupportedMediaType()).andReturn().getResponse().getContentAsString();
					assertDocumentedError(spec, path.getKey(), operation.getKey(), "415", "C011", unsupported);
				}
			}
		}
	}

	@Test
	void everyProtectedOperationEnforcesTheDocumentedSessionAndAdminGuards() throws Exception {
		for (String group : List.of("client", "admin")) {
			JsonNode spec = specification(group);
			var paths = spec.path("paths").fields();
			while (paths.hasNext()) {
				var path = paths.next();
				var operations = path.getValue().fields();
				while (operations.hasNext()) {
					var operation = operations.next();
					if (operation.getValue().path("security").isEmpty()) {
						continue;
					}
					String uri = documentationUri(path.getKey(), operation.getValue());
					var method = org.springframework.http.HttpMethod.valueOf(operation.getKey().toUpperCase());
					String absent = mvc.perform(request(method, uri)).andExpect(status().isUnauthorized())
						.andReturn().getResponse().getContentAsString();
					assertDocumentedError(spec, path.getKey(), operation.getKey(), "401", "M004", absent);
					String expired = mvc.perform(request(method, uri).cookie(new Cookie("SESSION_ID", "expired")))
						.andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
					assertDocumentedError(spec, path.getKey(), operation.getKey(), "401", "M004", expired);
					if (group.equals("admin")) {
						String forbidden = mvc.perform(request(method, uri).cookie(SESSION))
							.andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
						assertDocumentedError(spec, path.getKey(), operation.getKey(), "403", "M006", forbidden);
					}
				}
			}
		}
	}

	@Test
	void allowedFrontendOriginsCanReadTheDocumentedRetryAndLocationHeaders() throws Exception {
		when(context.getBean(ReservationService.class).createPendingReservation(any(), anyLong(), any()))
			.thenThrow(new ReservationInventoryBusyException(new IllegalStateException("test contention")));
		var response = mvc.perform(post("/api/v1/reservations").cookie(SESSION)
			.header("Origin", "https://www.airbob.cloud").header("Idempotency-Key", "checkout-00000001")
			.contentType(MediaType.APPLICATION_JSON).content("{\"quote_uid\":\"00000000-0000-0000-0000-000000000010\"}"))
			.andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "1"))
			.andExpect(header().string("Access-Control-Allow-Origin", "https://www.airbob.cloud"))
			.andExpect(header().string("Access-Control-Allow-Credentials", "true"))
			.andReturn().getResponse();
		assertThat(response.getHeader("Access-Control-Expose-Headers")).contains("Location", "Retry-After");
	}

	private String documentationUri(String path, JsonNode operation) {
		String uri = path;
		for (JsonNode parameter : operation.path("parameters")) {
			if (!parameter.path("in").asText().equals("path")) {
				continue;
			}
			String value = parameter.path("example").asText("101");
			if (parameter.at("/schema/format").asText().equals("uuid")) {
				value = DOCUMENTATION_OPERATION_ID.toString();
			}
			uri = uri.replace("{" + parameter.path("name").asText() + "}", value);
		}
		return uri;
	}

	private Settlement documentationSettlement() {
		return Settlement.builder().id(101L).hostId(10L).settlementMonth(LocalDate.parse("2027-01-01"))
			.grossAmount(150000L).refundAmount(20000L).netAmount(130000L).commissionRate(new BigDecimal("0.03"))
			.commissionAmount(3900L).payoutAmount(126100L).status(SettlementStatus.PENDING).build();
	}

	private void allowDocumentationAdmin() {
		when(context.getBean(MemberRepository.class).existsByIdAndStatusAndRole(10L, MemberStatus.ACTIVE, MemberRole.ADMIN)).thenReturn(true);
	}

	private Reservation documentationPendingReservation(Instant expiresAt) {
		Member member = Member.builder().id(10L).build();
		Accommodation accommodation = Accommodation.builder().id(101L).name("숙소").member(member)
			.status(AccommodationStatus.PUBLISHED).checkInTime(LocalTime.of(15, 0)).checkOutTime(LocalTime.of(11, 0)).build();
		return Reservation.builder().reservationUid(UUID.fromString("00000000-0000-0000-0000-000000000020"))
			.reservationCode("AB12CD").guest(member).accommodation(accommodation)
			.checkInDate(LocalDate.parse("2027-01-10")).checkOutDate(LocalDate.parse("2027-01-12"))
			.checkInAt(Instant.parse("2027-01-10T06:00:00Z")).checkOutAt(Instant.parse("2027-01-12T02:00:00Z"))
			.timeZoneId("Asia/Seoul").guestCount(2).totalPrice(200000L).currency("KRW")
			.status(ReservationStatus.PAYMENT_PENDING).expiresAt(expiresAt).build();
	}

	private JsonNode specification(String group) throws Exception {
		return new ObjectMapper().readTree(mvc.perform(get("/v3/api-docs/" + group))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private void assertDocumentedError(JsonNode spec, String path, String method, String status, String code, String body) throws Exception {
		validateResponse(spec, path, method, status, body);
		assertThat(mapper.readTree(body).at("/error/code").asText()).isEqualTo(code);
		assertThat(spec.path("paths").path(path).path(method).path("responses").path(status)
			.path("content").path("application/json").path("examples").has(code)).isTrue();
	}

	private void assertSecurityMatchesRealFilter(String path, String method, JsonNode operation) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest(method.toUpperCase(), path.replaceAll("\\{[^}]+}", "1"));
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicBoolean allowed = new AtomicBoolean();
		authFilter.doFilter(request, response, (req, res) -> allowed.set(true));
		assertThat(operation.path("security").isEmpty()).as(method + " " + path).isEqualTo(allowed.get());
	}

	private void validateResponse(JsonNode spec, String path, String method, String status, String body) throws Exception {
		JsonNode schema = spec.path("paths").path(path).path(method).path("responses").path(status)
			.path("content").path("application/json").path("schema");
		assertThat(schema.isMissingNode()).as(path + " " + status).isFalse();
		validateInstance(spec, schema, mapper.readTree(body));
	}

	private void validateInstance(JsonNode spec, JsonNode schema, JsonNode body) {
		ObjectNode root = ((ObjectNode)schema).deepCopy();
		root.set("components", spec.path("components"));
		assertThat(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root).validate(body)).isEmpty();
	}

	private void assertSchemaExamples(JsonNode spec, JsonNode node) {
		if (node.isObject() && node.has("example")) {
			validateInstance(spec, node, node.get("example"));
		}
		node.forEach(child -> assertSchemaExamples(spec, child));
	}

	private void assertLocalReferences(JsonNode root, JsonNode node) {
		if (node.isObject() && node.has("$ref")) {
			String ref = node.path("$ref").asText();
			assertThat(ref).startsWith("#/");
			assertThat(root.at(ref.substring(1)).isMissingNode()).as(ref).isFalse();
		}
		node.forEach(child -> assertLocalReferences(root, child));
	}

	private JsonNode sorted(JsonNode node) {
		if (node.isObject()) {
			ObjectNode result = new ObjectMapper().createObjectNode();
			TreeMap<String, JsonNode> fields = new TreeMap<>();
			node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), sorted(entry.getValue())));
			fields.forEach(result::set);
			return result;
		}
		if (node.isArray()) {
			var result = new ObjectMapper().createArrayNode();
			node.forEach(child -> result.add(sorted(child)));
			return result;
		}
		return node;
	}

	@Configuration(proxyBeanMethods = false)
	@ComponentScan(basePackages = {"kr.kro.airbob.domain", "kr.kro.airbob.search"}, useDefaultFilters = false,
		includeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = RestController.class),
		excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\$.*"))
	@ImportAutoConfiguration({PropertyPlaceholderAutoConfiguration.class, WebMvcAutoConfiguration.class,
		JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, ValidationAutoConfiguration.class,
		SpringDataWebAutoConfiguration.class, SpringDocConfiguration.class, SpringDocConfigProperties.class,
		SpringDocSpecPropertiesConfiguration.class, SpringDocWebMvcConfiguration.class, MultipleOpenApiSupportConfiguration.class,
		SpringDocPageableConfiguration.class, SpringDocSortConfiguration.class, SwaggerConfig.class,
		SwaggerUiConfigProperties.class, SwaggerUiOAuthProperties.class})
	@Import({OpenApiConfiguration.class, WebMvcConfig.class, GlobalExceptionHandler.class, SessionAuthFilter.class,
		AdminAuthInterceptor.class, QueryCountInterceptor.class, CurrentMemberIdArgumentResolver.class,
		CursorDecoder.class, CursorParamArgumentResolver.class, CouponCampaignCursorParamArgumentResolver.class,
		ReviewCursorParamArgumentResolver.class})
	static class MvcApplication { }
}
