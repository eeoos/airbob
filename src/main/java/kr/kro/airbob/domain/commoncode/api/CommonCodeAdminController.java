package kr.kro.airbob.domain.commoncode.api;

import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeAdminResponse;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeGroupRequest;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeGroupResponse;
import kr.kro.airbob.domain.commoncode.dto.CommonCodeRequest;
import kr.kro.airbob.domain.commoncode.service.CommonCodeAdminService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class CommonCodeAdminController implements CommonCodeAdminApi {

	private final CommonCodeAdminService commonCodeAdminService;

	@Override
	public ResponseEntity<ApiResponse<List<CommonCodeGroupResponse>>> getGroups() {
		return ResponseEntity.ok(ApiResponse.success(commonCodeAdminService.getGroups()));
	}

	@Override
	public ResponseEntity<ApiResponse<CommonCodeGroupResponse>> createGroup(
		CommonCodeGroupRequest.Create request) {

		CommonCodeGroupResponse created = commonCodeAdminService.createGroup(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
	}

	@Override
	public ResponseEntity<ApiResponse<CommonCodeGroupResponse>> updateGroup(
		String groupCode,
		CommonCodeGroupRequest.Update request) {

		CommonCodeGroupResponse updated = commonCodeAdminService.updateGroup(
			groupCode.toUpperCase(Locale.ROOT), request);
		return ResponseEntity.ok(ApiResponse.success(updated));
	}

	@Override
	public ResponseEntity<ApiResponse<List<CommonCodeAdminResponse>>> getAll(
		String group) {
		List<CommonCodeAdminResponse> codes = commonCodeAdminService.getAll(group.toUpperCase(Locale.ROOT));
		return ResponseEntity.ok(ApiResponse.success(codes));
	}

	@Override
	public ResponseEntity<ApiResponse<CommonCodeAdminResponse>> create(
		String group,
		CommonCodeRequest.Create request) {

		CommonCodeAdminResponse created = commonCodeAdminService.create(group.toUpperCase(Locale.ROOT), request);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
	}

	@Override
	public ResponseEntity<ApiResponse<CommonCodeAdminResponse>> update(
		String group,
		String code,
		CommonCodeRequest.Update request) {

		CommonCodeAdminResponse updated = commonCodeAdminService.update(group.toUpperCase(Locale.ROOT), code, request);
		return ResponseEntity.ok(ApiResponse.success(updated));
	}
}
