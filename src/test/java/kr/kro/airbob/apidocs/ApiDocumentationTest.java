package kr.kro.airbob.apidocs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

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
import kr.kro.airbob.domain.coupon.common.DiscountType;
import kr.kro.airbob.domain.coupon.common.CouponIssuanceStatus;
import kr.kro.airbob.cursor.dto.CursorResponse;
import kr.kro.airbob.domain.reservation.entity.ReservationStatus;
import kr.kro.airbob.domain.accommodation.service.AccommodationCommandService;
import kr.kro.airbob.domain.accommodation.service.AccommodationImageService;
import kr.kro.airbob.domain.accommodation.service.AccommodationQueryService;
import kr.kro.airbob.domain.auth.service.AuthService;
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
import kr.kro.airbob.domain.review.service.ReviewService;
import kr.kro.airbob.domain.settlement.service.SettlementService;
import kr.kro.airbob.domain.statistics.service.RevenueStatsService;
import kr.kro.airbob.domain.wishlist.service.WishlistService;
import kr.kro.airbob.search.service.AccommodationSearchService;

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
		Set<String> explicitlyDocumented = new HashSet<>();
		mappings.getHandlerMethods().forEach((mapping, handler) -> {
			if (handler.hasMethodAnnotation(io.swagger.v3.oas.annotations.Operation.class)) {
				mapping.getPatternValues().forEach(path -> mapping.getMethodsCondition().getMethods()
					.forEach(method -> explicitlyDocumented.add(method + " " + path)));
			}
		});
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
					if (explicitlyDocumented.contains(method.getKey().toUpperCase() + " " + path.getKey())) {
						assertThat(operation.path("summary").asText()).isNotBlank();
						assertThat(operation.path("description").asText()).isNotBlank();
					}
					assertThat(operationIds.add(operation.path("operationId").asText())).isTrue();
					assertSecurityMatchesRealFilter(path.getKey(), method.getKey(), operation);
					for (JsonNode parameter : operation.path("parameters")) {
						if (explicitlyDocumented.contains(method.getKey().toUpperCase() + " " + path.getKey())
							&& parameter.path("in").asText().equals("query")) {
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
