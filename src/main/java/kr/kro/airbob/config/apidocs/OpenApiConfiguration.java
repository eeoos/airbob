package kr.kro.airbob.config.apidocs;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.cursor.annotation.CursorParam;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
public class OpenApiConfiguration {

	public static final String SESSION_AUTH = "sessionCookie";
	private static final String ERROR_SCHEMA = "#/components/schemas/ApiError";
	private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
	// Checked against SessionAuthFilter for every real API mapping by ApiDocumentationTest.
	private static final Set<String> PUBLIC_POSTS = Set.of("/api/v1/auth/login", "/api/v1/members");
	private static final List<String> PUBLIC_GETS = List.of(
		"/api/v1/accommodations/*", "/api/v1/accommodations/*/availability",
		"/api/v1/accommodations/*/reviews", "/api/v1/search/accommodations", "/api/v1/common-codes/*");

	public OpenApiConfiguration() {
		SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentMemberId.class, CursorParam.class);
	}

	@Bean
	OpenAPI airbobOpenApi() {
		return new OpenAPI()
			.info(new Info().title("Airbob API").version("v1")
				.description("예약·숙소·쿠폰·결제 API. JSON 필드는 snake_case이며 쿼리 매개변수는 명세의 이름을 사용합니다. "
					+ "client 그룹의 인증 → 로그인에 성공하면 브라우저가 SESSION_ID 쿠키를 자동 저장·전송합니다. Authorize에 쿠키를 입력하지 않습니다. "
					+ "202 응답의 처리 완료 여부는 각 API 설명을 따릅니다."))
			.servers(List.of(new Server().url("/")))
			.components(new Components()
				.addSecuritySchemes(SESSION_AUTH, new SecurityScheme().type(SecurityScheme.Type.APIKEY)
					.in(SecurityScheme.In.COOKIE).name("SESSION_ID")
					.description("같은 서버의 POST /api/v1/auth/login에 성공하면 브라우저가 자동 저장·전송하는 HttpOnly 세션 쿠키입니다. "
						+ "이 입력란에 쿠키를 붙여 넣지 않습니다. 자물쇠 표시는 실제 세션 상태를 나타내지 않으며, "
						+ "GET /api/v1/auth/me의 200 응답으로 로그인 상태를 확인합니다."))
				.addSchemas("ApiError", errorSchema()));
	}

	@Bean
	ModelResolver apiModelResolver(ObjectMapper objectMapper) {
		// Share the actual application naming strategy and Jackson modules with schema generation.
		return new ModelResolver(objectMapper).openapi31(true);
	}

	@Bean
	GroupedOpenApi clientApi() {
		return GroupedOpenApi.builder().group("client")
			.pathsToMatch("/api/v1/**").pathsToExclude("/api/v1/admin/**").build();
	}

	@Bean
	GroupedOpenApi adminApi() {
		return GroupedOpenApi.builder().group("admin").pathsToMatch("/api/v1/admin/**").build();
	}

	@Bean
	GlobalOperationCustomizer apiParametersAndErrors() {
		return (operation, handler) -> {
			Set<ErrorCode> errors = new LinkedHashSet<>();
			errors.add(ErrorCode.INTERNAL_SERVER_ERROR);
			ApiErrors declared = handler.getMethodAnnotation(ApiErrors.class);
			if (declared != null) {
				errors.addAll(Arrays.asList(declared.value()));
			}
			for (var parameter : handler.getMethodParameters()) {
				if (MultipartFile.class.equals(parameter.getParameterType())
					|| MultipartFile.class.equals(ResolvableType.forMethodParameter(parameter).getGeneric(0).resolve())) {
					RequestParam form = parameter.getParameterAnnotation(RequestParam.class);
					errors.add(ErrorCode.INVALID_INPUT_VALUE);
					errors.add(ErrorCode.UPLOAD_SIZE_EXCEEDED);
					String name = form != null && !form.value().isEmpty() ? form.value() : parameter.getParameterName();
					if (operation.getParameters() != null) {
						operation.getParameters().removeIf(p -> p.getName().equals(name));
					}
					operation.setRequestBody(new RequestBody().required(true).content(new Content()
						.addMediaType("multipart/form-data", new MediaType().schema(new ObjectSchema()
							.required(List.of(name)).addProperty(name, new ArraySchema().items(new StringSchema().format("binary")))))));
				}
				CursorParam cursor = parameter.getParameterAnnotation(CursorParam.class);
				if (cursor == null) {
					continue;
				}
				operation.addParametersItem(new Parameter().name(cursor.cursorParam()).in("query")
					.required(false).description("이전 응답 page_info의 next_cursor. 첫 페이지에서는 생략합니다. 필터·정렬을 바꾸면 초기화합니다.")
					.schema(new StringSchema()));
				operation.addParametersItem(new Parameter().name(cursor.sizeParam()).in("query")
					.required(false).description("한 페이지의 최대 항목 수")
					.schema(new IntegerSchema()._default(cursor.defaultSize())
						.minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(cursor.maxSize()))));
				errors.add(ErrorCode.CURSOR_DECODING_ERROR);
				errors.add(ErrorCode.CURSOR_PAGE_SIZE_INVALID);
			}
			// Explicit operation-level hides take precedence over flattened @ParameterObject fields.
			var description = handler.getMethodAnnotation(io.swagger.v3.oas.annotations.Operation.class);
			if (description != null && operation.getParameters() != null) {
				Arrays.stream(description.parameters()).filter(io.swagger.v3.oas.annotations.Parameter::hidden)
					.forEach(hidden -> operation.getParameters().removeIf(p -> p.getName().equals(hidden.name())));
			}
			errors.forEach(code -> addError(operation, code));
			return operation;
		};
	}

	@Bean
	GlobalOpenApiCustomizer apiContracts() {
		return api -> {
			api.getComponents().getSchemas().values().forEach(OpenApiConfiguration::normalizeNullableReferences);
			api.getComponents().getSchemas().forEach(OpenApiConfiguration::documentSuccessEnvelope);
			api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
				boolean anonymous = method.name().equals("POST") && PUBLIC_POSTS.contains(path)
					|| method.name().equals("GET") && PUBLIC_GETS.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
				operation.setSecurity(anonymous ? List.of() : List.of(new SecurityRequirement().addList(SESSION_AUTH)));
				if (!anonymous) {
					addError(operation, ErrorCode.UNAUTHORIZED_ACCESS);
				}
				if (path.startsWith("/api/v1/admin/")) {
					addError(operation, ErrorCode.ADMIN_ACCESS_DENIED);
					operation.setDescription(operation.getDescription() + " 관리자(ADMIN) 권한이 필요합니다.");
				}
			}));
		};
	}

	private static void normalizeNullableReferences(Schema<?> schema) {
		boolean nullable = Boolean.TRUE.equals(schema.getNullable()) || "null".equals(schema.getType())
			|| schema.getTypes() != null && schema.getTypes().contains("null");
		// An enum constraint must explicitly include null even when its type already allows it.
		if (nullable && schema.getEnum() != null && !schema.getEnum().contains(null)) {
			includeNullEnumValue(schema);
		}
		// swagger-core can emit {$ref: ..., type: null} for a nullable DTO reference in OAS 3.1.
		// JSON Schema combines those constraints; anyOf is needed to accept either the DTO or null.
		if (schema.get$ref() != null && nullable) {
			String reference = schema.get$ref();
			schema.set$ref(null);
			schema.setType(null);
			schema.setTypes(null);
			schema.setNullable(null);
			schema.setAnyOf(List.of(new Schema<>().$ref(reference), new Schema<>().types(Set.of("null"))));
		}
		if (schema.getProperties() != null) {
			schema.getProperties().values().forEach(OpenApiConfiguration::normalizeNullableReferences);
		}
		if (schema.getItems() != null) {
			normalizeNullableReferences(schema.getItems());
		}
	}

	private static <T> void includeNullEnumValue(Schema<T> schema) {
		List<T> values = new ArrayList<>(schema.getEnum());
		values.add(null);
		schema.setEnum(values);
	}

	private static void documentSuccessEnvelope(String name, Schema<?> schema) {
		if (!name.startsWith("ApiResponse") || schema.getProperties() == null) {
			return;
		}
		// ApiResponse omits null data/error. Error responses have their own ApiError schema.
		schema.getProperties().remove("error");
		schema.getProperties().put("success", new BooleanSchema()._enum(List.of(true)));
		if (name.equals("ApiResponseVoid")) {
			schema.getProperties().remove("data");
			schema.setRequired(List.of("success"));
		} else {
			schema.setRequired(List.of("success", "data"));
		}
	}

	private static void addError(Operation operation, ErrorCode code) {
		String status = Integer.toString(code.getStatus().value());
		ApiResponse response = operation.getResponses().computeIfAbsent(status,
			ignored -> new ApiResponse().description("오류 응답. examples에서 업무 오류 코드와 메시지를 확인합니다.")
				.content(new Content().addMediaType("application/json", new MediaType().schema(new Schema<>().$ref(ERROR_SCHEMA)))));
		response.getContent().get("application/json").addExamples(code.getCode(), new Example()
			.summary(code.getMessage())
			.value(Map.of("success", false, "error", Map.of("code", code.getCode(), "status", code.getStatus().value(),
				"message", code.getMessage(), "errors", List.of()))));
	}

	private static Schema<?> errorSchema() {
		return new ObjectSchema().required(List.of("success", "error"))
			.addProperty("success", new BooleanSchema()._enum(List.of(false)))
			.addProperty("error", new ObjectSchema().required(List.of("code", "status", "message", "errors"))
				.addProperty("code", new StringSchema().description("업무 오류 코드"))
				.addProperty("status", new IntegerSchema().description("HTTP 상태 코드"))
				.addProperty("message", new StringSchema().description("오류 설명"))
				.addProperty("errors", new ArraySchema().items(new ObjectSchema()
					.addProperty("field", new StringSchema()).addProperty("value", new StringSchema())
					.addProperty("reason", new StringSchema()))));
	}
}
