package kr.kro.airbob.common.code;

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
import lombok.RequiredArgsConstructor;

/**
 * 공통 코드 관리 API (ADMIN 전용 — AdminAuthInterceptor 가 /api/v1/admin/** 보호).
 * 운영자가 라벨/정렬/노출여부를 바꾸거나 새 코드를 추가한다(배포 불필요).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/common-codes")
public class CommonCodeAdminController {

	private final CommonCodeAdminService commonCodeAdminService;

	// 비활성 포함 전체 조회(관리 화면용)
	@GetMapping("/{group}")
	public ResponseEntity<ApiResponse<List<CommonCodeAdminResponse>>> getAll(@PathVariable String group) {
		List<CommonCodeAdminResponse> codes = commonCodeAdminService.getAll(group.toUpperCase(Locale.ROOT));
		return ResponseEntity.ok(ApiResponse.success(codes));
	}

	@PostMapping("/{group}")
	public ResponseEntity<ApiResponse<CommonCodeAdminResponse>> create(
		@PathVariable String group,
		@RequestBody @Valid CommonCodeRequest.Create request) {

		CommonCodeAdminResponse created = commonCodeAdminService.create(group.toUpperCase(Locale.ROOT), request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
	}

	@PatchMapping("/{group}/{code}")
	public ResponseEntity<ApiResponse<CommonCodeAdminResponse>> update(
		@PathVariable String group,
		@PathVariable String code,
		@RequestBody @Valid CommonCodeRequest.Update request) {

		CommonCodeAdminResponse updated = commonCodeAdminService.update(group.toUpperCase(Locale.ROOT), code, request);
		return ResponseEntity.ok(ApiResponse.success(updated));
	}
}
