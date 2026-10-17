package kr.kro.airbob.domain.commoncode.api;

import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.config.apidocs.ApiErrors;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeAdminResponse;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeGroupRequest;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeGroupResponse;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeRequest;
import kr.kro.airbob.domain.commoncode.service.CommonCodeAdminService;
import lombok.RequiredArgsConstructor;

/**
 * 공통 코드 관리 API (ADMIN 전용 — AdminAuthInterceptor 가 /api/v1/admin/** 보호).
 */
@Tag(name = "공통 코드 관리")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class CommonCodeAdminController {

	private final CommonCodeAdminService commonCodeAdminService;

	@Operation(operationId = "CommonCodeAdmin.getGroups", summary = "공통 코드 그룹 조회",
		description = "활성·비활성 그룹 전체를 group_code 오름차순으로 조회합니다. 페이지 구분과 조회 캐시가 없으며 데이터가 없으면 빈 배열입니다. 각 응답의 active는 그룹 자체의 활성 여부입니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@GetMapping("/v1/admin/common-code-groups")
	public ResponseEntity<ApiResponse<List<CommonCodeGroupResponse>>> getGroups() {
		return ResponseEntity.ok(ApiResponse.success(commonCodeAdminService.getGroups()));
	}

	@Operation(operationId = "CommonCodeAdmin.createGroup", summary = "공통 코드 그룹 생성",
		description = "group_code의 앞뒤 공백을 제거하고 대문자로 저장합니다. group_name은 필수, description은 선택이며 is_active를 생략하거나 null이면 true입니다. 같은 그룹은 동시 요청에서도 409/CC004로 거절합니다. 201 응답의 group_code를 이후 관리 경로에 사용하며 Location 헤더는 없습니다. 공개 조회에 캐시된 그룹 없음 상태는 최대 1분간 유지될 수 있습니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 완료", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.UNSUPPORTED_MEDIA_TYPE,
		ErrorCode.COMMON_CODE_GROUP_DUPLICATE
	})
	@PostMapping("/v1/admin/common-code-groups")
	public ResponseEntity<ApiResponse<CommonCodeGroupResponse>> createGroup(
		@RequestBody @Valid CommonCodeGroupRequest.Create request) {

		CommonCodeGroupResponse created = commonCodeAdminService.createGroup(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
	}

	@Operation(operationId = "CommonCodeAdmin.updateGroup", summary = "공통 코드 그룹 수정",
		description = "그룹 이름·설명·활성 상태를 부분 수정하며 group_code는 변경하지 않습니다. 생략하거나 null인 필드는 유지하고 description을 빈 문자열로 보내면 내용을 비웁니다. 빈 객체도 허용합니다. is_active=false로 바꿔도 하위 코드 자체의 active는 변경하지 않으며 공개 목록에서 그룹 전체를 숨깁니다. 공개 조회 캐시는 즉시 무효화하지 않아 최대 1분 뒤 반영됩니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.UNSUPPORTED_MEDIA_TYPE,
		ErrorCode.COMMON_CODE_GROUP_NOT_FOUND
	})
	@PatchMapping("/v1/admin/common-code-groups/{groupCode}")
	public ResponseEntity<ApiResponse<CommonCodeGroupResponse>> updateGroup(
		@Parameter(description = "수정할 그룹 코드. 대문자로 정규화합니다.", example = "EXAMPLE_GROUP") @PathVariable String groupCode,
		@RequestBody @Valid CommonCodeGroupRequest.Update request) {

		CommonCodeGroupResponse updated = commonCodeAdminService.updateGroup(
			groupCode.toUpperCase(Locale.ROOT), request);
		return ResponseEntity.ok(ApiResponse.success(updated));
	}

	// 비활성 포함 전체 조회(관리 화면용)
	@Operation(operationId = "CommonCodeAdmin.getAll", summary = "공통 코드 전체 조회",
		description = "그룹 자체의 활성 여부와 관계없이 활성·비활성 코드 전체를 sort_order 오름차순으로 조회합니다. 같은 순서 값 안의 순서는 보장하지 않습니다. 페이지 구분과 조회 캐시는 없습니다. 그룹은 있지만 코드가 없으면 빈 배열, 그룹이 없으면 404/CC001입니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.COMMON_CODE_GROUP_NOT_FOUND
	})
	@GetMapping("/v1/admin/common-codes/{group}")
	public ResponseEntity<ApiResponse<List<CommonCodeAdminResponse>>> getAll(
		@Parameter(description = "조회할 그룹 코드. 비활성 그룹도 허용합니다.", example = "AMENITY_TYPE") @PathVariable String group) {
		List<CommonCodeAdminResponse> codes = commonCodeAdminService.getAll(group.toUpperCase(Locale.ROOT));
		return ResponseEntity.ok(ApiResponse.success(codes));
	}

	@Operation(operationId = "CommonCodeAdmin.create", summary = "공통 코드 생성",
		description = "기존 그룹에 코드를 생성합니다. 비활성 그룹에도 생성할 수 있습니다. code의 앞뒤 공백을 제거하고 대문자로 저장하며 같은 그룹 내에서 고유합니다. name은 필수, sort_order 생략·null은 0, is_active 생략·null은 true입니다. 201로 생성된 코드를 반환하며 Location 헤더는 없습니다. 공개 조회 반영에는 최대 1분이 걸릴 수 있습니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 완료", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.UNSUPPORTED_MEDIA_TYPE,
		ErrorCode.COMMON_CODE_GROUP_NOT_FOUND,
		ErrorCode.COMMON_CODE_DUPLICATE
	})
	@PostMapping("/v1/admin/common-codes/{group}")
	public ResponseEntity<ApiResponse<CommonCodeAdminResponse>> create(
		@Parameter(description = "기존 그룹 코드. 대문자로 정규화하며 비활성 그룹도 허용합니다.", example = "AMENITY_TYPE") @PathVariable String group,
		@RequestBody @Valid CommonCodeRequest.Create request) {

		CommonCodeAdminResponse created = commonCodeAdminService.create(group.toUpperCase(Locale.ROOT), request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
	}

	@Operation(operationId = "CommonCodeAdmin.update", summary = "공통 코드 수정",
		description = "이름·설명·정렬 순서·활성 상태를 부분 수정하며 그룹과 code 식별자는 변경하지 않습니다. 생략·null 필드는 유지하고 description의 빈 문자열은 내용을 비웁니다. 빈 객체도 허용합니다. 그룹이나 코드가 없어 해당 조합을 찾지 못하면 404/CC002입니다. 공개 조회 반영에는 최대 1분이 걸릴 수 있습니다.")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.UNSUPPORTED_MEDIA_TYPE,
		ErrorCode.COMMON_CODE_NOT_FOUND
	})
	@PatchMapping("/v1/admin/common-codes/{group}/{code}")
	public ResponseEntity<ApiResponse<CommonCodeAdminResponse>> update(
		@Parameter(description = "기존 그룹 코드. 대문자로 정규화하며 비활성 그룹도 허용합니다.", example = "AMENITY_TYPE") @PathVariable String group,
		@Parameter(description = "그룹 내 수정할 코드. 대문자로 정규화합니다.", example = "EXAMPLE_AMENITY") @PathVariable String code,
		@RequestBody @Valid CommonCodeRequest.Update request) {

		CommonCodeAdminResponse updated = commonCodeAdminService.update(group.toUpperCase(Locale.ROOT), code, request);
		return ResponseEntity.ok(ApiResponse.success(updated));
	}
}
