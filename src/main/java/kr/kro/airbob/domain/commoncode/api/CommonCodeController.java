package kr.kro.airbob.domain.commoncode.api;

import java.util.List;
import java.util.Locale;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeResponse;
import kr.kro.airbob.domain.commoncode.service.CommonCodeService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class CommonCodeController implements CommonCodeApi {

	private final CommonCodeService commonCodeService;

	@Override
	public ResponseEntity<ApiResponse<List<CommonCodeResponse>>> getCommonCodes(
		String group) {
		List<CommonCodeResponse> codes = commonCodeService.getCodes(group.toUpperCase(Locale.ROOT));
		return ResponseEntity.ok(ApiResponse.success(codes));
	}
}
