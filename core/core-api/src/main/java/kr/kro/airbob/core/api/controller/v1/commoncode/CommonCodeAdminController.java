package kr.kro.airbob.core.api.controller.v1.commoncode;

import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.core.api.controller.v1.commoncode.request.CommonCodeGroupRequest;
import kr.kro.airbob.core.api.controller.v1.commoncode.request.CommonCodeRequest;
import kr.kro.airbob.core.api.controller.v1.commoncode.response.CommonCodeAdminResponse;
import kr.kro.airbob.core.api.controller.v1.commoncode.response.CommonCodeGroupResponse;
import kr.kro.airbob.core.domain.commoncode.CommonCodeAdminService;
import kr.kro.airbob.core.support.response.ApiResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class CommonCodeAdminController implements CommonCodeAdminApi {

	private final CommonCodeAdminService commonCodeAdminService;

	@Override
	public ResponseEntity<ApiResponse<List<CommonCodeGroupResponse>>> getGroups() {
		List<CommonCodeGroupResponse> groups = commonCodeAdminService.getGroups().stream()
			.map(CommonCodeGroupResponse::from)
			.toList();
		return ResponseEntity.ok(ApiResponse.success(groups));
	}

	@Override
	public ResponseEntity<ApiResponse<CommonCodeGroupResponse>> createGroup(
		CommonCodeGroupRequest.Create request) {

		CommonCodeGroupResponse created = CommonCodeGroupResponse.from(
			commonCodeAdminService.createGroup(request.toNewCommonCodeGroup()));
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
	}

	@Override
	public ResponseEntity<ApiResponse<CommonCodeGroupResponse>> updateGroup(
		String groupCode,
		CommonCodeGroupRequest.Update request) {

		CommonCodeGroupResponse updated = CommonCodeGroupResponse.from(commonCodeAdminService.updateGroup(
			groupCode.toUpperCase(Locale.ROOT), request.toPatch()));
		return ResponseEntity.ok(ApiResponse.success(updated));
	}

	@Override
	public ResponseEntity<ApiResponse<List<CommonCodeAdminResponse>>> getAll(
		String group) {
		List<CommonCodeAdminResponse> codes = commonCodeAdminService.getAll(group.toUpperCase(Locale.ROOT)).stream()
			.map(CommonCodeAdminResponse::from)
			.toList();
		return ResponseEntity.ok(ApiResponse.success(codes));
	}

	@Override
	public ResponseEntity<ApiResponse<CommonCodeAdminResponse>> create(
		String group,
		CommonCodeRequest.Create request) {

		CommonCodeAdminResponse created = CommonCodeAdminResponse.from(
			commonCodeAdminService.create(group.toUpperCase(Locale.ROOT), request.toNewCommonCode()));
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(created));
	}

	@Override
	public ResponseEntity<ApiResponse<CommonCodeAdminResponse>> update(
		String group,
		String code,
		CommonCodeRequest.Update request) {

		CommonCodeAdminResponse updated = CommonCodeAdminResponse.from(
			commonCodeAdminService.update(group.toUpperCase(Locale.ROOT), code, request.toPatch()));
		return ResponseEntity.ok(ApiResponse.success(updated));
	}
}
