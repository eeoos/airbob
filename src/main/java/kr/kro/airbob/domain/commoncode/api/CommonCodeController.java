package kr.kro.airbob.domain.commoncode.api;

import java.util.List;
import java.util.Locale;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.config.apidocs.ApiErrors;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeResponse;
import kr.kro.airbob.domain.commoncode.service.CommonCodeService;
import lombok.RequiredArgsConstructor;

/**
 * 공통 코드 조회 API. 프론트 셀렉트 박스/목록 노출용
 */
@Tag(name = "공통 코드")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class CommonCodeController {

	private final CommonCodeService commonCodeService;

	@Operation(operationId = "CommonCode.getCommonCodes", summary = "활성 공통 코드 조회",
		description = """
			로그인 없이 그룹의 활성 코드 목록을 sort_order 오름차순으로 조회합니다. 그룹명은 대문자로 정규화하며 같은 sort_order 안의 순서는 보장하지 않습니다. 페이지 구분 없이 전체 목록을 반환합니다.

			비활성 그룹이거나 활성 코드가 없으면 200과 빈 배열입니다. 존재하지 않는 그룹은 404/CC001입니다. 결과와 그룹 없음 상태를 서버 인스턴스별로 1분간 캐시하므로 관리자 변경 직후에는 이전 목록이나 404가 잠시 유지될 수 있습니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors(ErrorCode.COMMON_CODE_GROUP_NOT_FOUND)
	@GetMapping("/v1/common-codes/{group}")
	public ResponseEntity<ApiResponse<List<CommonCodeResponse>>> getCommonCodes(
		@Parameter(description = "조회할 그룹 코드. AMENITY_TYPE(편의시설), ACCOMMODATION_TYPE(숙소 유형) 또는 관리자가 등록한 그룹", example = "AMENITY_TYPE") @PathVariable String group) {
		List<CommonCodeResponse> codes = commonCodeService.getCodes(group.toUpperCase(Locale.ROOT));
		return ResponseEntity.ok(ApiResponse.success(codes));
	}
}
