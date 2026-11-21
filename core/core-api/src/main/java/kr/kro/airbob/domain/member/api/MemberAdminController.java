package kr.kro.airbob.domain.member.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.member.dto.MemberAdminRequest;
import kr.kro.airbob.domain.member.dto.MemberAdminResponse;
import kr.kro.airbob.domain.member.service.MemberAdminService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class MemberAdminController implements MemberAdminApi {

	private final MemberAdminService memberAdminService;

	@Override
	public ResponseEntity<ApiResponse<MemberAdminResponse.RoleChanged>> changeRole(
		Long actorId,
		Long memberId,
		MemberAdminRequest.ChangeRole request
	) {
		return ResponseEntity.ok(ApiResponse.success(
			memberAdminService.changeRole(actorId, memberId, request)));
	}
}
