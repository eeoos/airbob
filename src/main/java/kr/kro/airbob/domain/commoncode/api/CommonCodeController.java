package kr.kro.airbob.domain.commoncode.api;

import java.util.List;
import java.util.Locale;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeResponse;
import kr.kro.airbob.domain.commoncode.service.CommonCodeService;
import lombok.RequiredArgsConstructor;

/**
 * 공통 코드 조회 API. 프론트 셀렉트 박스/목록 노출용
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class CommonCodeController {

	private final CommonCodeService commonCodeService;

	@GetMapping("/v1/common-codes/{group}")
	public ResponseEntity<ApiResponse<List<CommonCodeResponse>>> getCommonCodes(@PathVariable String group) {
		List<CommonCodeResponse> codes = commonCodeService.getCodes(group.toUpperCase(Locale.ROOT));
		return ResponseEntity.ok(ApiResponse.success(codes));
	}
}
