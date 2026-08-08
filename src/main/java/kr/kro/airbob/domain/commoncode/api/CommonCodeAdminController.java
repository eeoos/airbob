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

import jakarta.validation.Valid;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeAdminResponse;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeGroupRequest;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeGroupResponse;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeRequest;
import kr.kro.airbob.domain.commoncode.service.CommonCodeAdminService;
import lombok.RequiredArgsConstructor;

/**
 * 공통 코드 관리 API (ADMIN 전용 — AdminAuthInterceptor 가 /api/v1/admin/** 보호).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class CommonCodeAdminController {

	private final CommonCodeAdminService commonCodeAdminService;

	@GetMapping("/v1/admin/common-code-groups")
	public ResponseEntity<ApiResponse<List<CommonCodeGroupResponse>>> getGroups() {
		return ResponseEntity.ok(ApiResponse.success(commonCodeAdminService.getGroups()));
	}

	@PostMapping("/v1/admin/common-code-groups")
	public ResponseEntity<ApiResponse<CommonCodeGroupResponse>> createGroup(
		@RequestBody @Valid CommonCodeGroupRequest.Create request) {

		CommonCodeGroupResponse created = commonCodeAdminService.createGroup(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
	}

	@PatchMapping("/v1/admin/common-code-groups/{groupCode}")
	public ResponseEntity<ApiResponse<CommonCodeGroupResponse>> updateGroup(
		@PathVariable String groupCode,
		@RequestBody @Valid CommonCodeGroupRequest.Update request) {

		CommonCodeGroupResponse updated = commonCodeAdminService.updateGroup(
			groupCode.toUpperCase(Locale.ROOT), request);
		return ResponseEntity.ok(ApiResponse.success(updated));
	}

	// 비활성 포함 전체 조회(관리 화면용)
	@GetMapping("/v1/admin/common-codes/{group}")
	public ResponseEntity<ApiResponse<List<CommonCodeAdminResponse>>> getAll(@PathVariable String group) {
		List<CommonCodeAdminResponse> codes = commonCodeAdminService.getAll(group.toUpperCase(Locale.ROOT));
		return ResponseEntity.ok(ApiResponse.success(codes));
	}

	@PostMapping("/v1/admin/common-codes/{group}")
	public ResponseEntity<ApiResponse<CommonCodeAdminResponse>> create(
		@PathVariable String group,
		@RequestBody @Valid CommonCodeRequest.Create request) {

		CommonCodeAdminResponse created = commonCodeAdminService.create(group.toUpperCase(Locale.ROOT), request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
	}

	@PatchMapping("/v1/admin/common-codes/{group}/{code}")
	public ResponseEntity<ApiResponse<CommonCodeAdminResponse>> update(
		@PathVariable String group,
		@PathVariable String code,
		@RequestBody @Valid CommonCodeRequest.Update request) {

		CommonCodeAdminResponse updated = commonCodeAdminService.update(group.toUpperCase(Locale.ROOT), code, request);
		return ResponseEntity.ok(ApiResponse.success(updated));
	}
}
