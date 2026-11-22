package kr.kro.airbob.core.api.controller.v1.commoncode;

import java.util.List;
import java.util.Locale;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.core.api.controller.v1.commoncode.response.CommonCodeResponse;
import kr.kro.airbob.core.domain.commoncode.CommonCodeService;
import kr.kro.airbob.core.support.response.ApiResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class CommonCodeController implements CommonCodeApi {

	private final CommonCodeService commonCodeService;

	@Override
	public ResponseEntity<ApiResponse<List<CommonCodeResponse>>> getCommonCodes(
		String group) {
		List<CommonCodeResponse> codes = commonCodeService.getCodes(group.toUpperCase(Locale.ROOT)).stream()
			.map(CommonCodeResponse::from)
			.toList();
		return ResponseEntity.ok(ApiResponse.success(codes));
	}
}
