package kr.kro.airbob.core.api.controller.v1.member;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.core.api.controller.v1.member.request.MemberAdminRequest;
import kr.kro.airbob.core.api.controller.v1.member.response.MemberAdminResponse;
import kr.kro.airbob.core.domain.member.MemberAdminService;
import kr.kro.airbob.core.domain.member.MemberRole;
import kr.kro.airbob.core.support.response.ApiResponse;
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
		MemberRole role = memberAdminService.changeRole(actorId, memberId, request.role(), request.reason());
		return ResponseEntity.ok(ApiResponse.success(new MemberAdminResponse.RoleChanged(memberId, role)));
	}
}
