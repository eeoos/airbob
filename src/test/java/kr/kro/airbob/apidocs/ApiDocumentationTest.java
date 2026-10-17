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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
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
import kr.kro.airbob.domain.payment.dto.PaymentOperationResponse;
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
