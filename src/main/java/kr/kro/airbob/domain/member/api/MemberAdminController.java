package kr.kro.airbob.domain.member.api;

import jakarta.validation.Valid;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.member.dto.MemberAdminRequest;
import kr.kro.airbob.domain.member.dto.MemberAdminResponse;
import kr.kro.airbob.domain.member.service.MemberAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class MemberAdminController {

	private final MemberAdminService memberAdminService;

	@PatchMapping("/v1/admin/members/{memberId}/role")
	public ResponseEntity<ApiResponse<MemberAdminResponse.RoleChanged>> changeRole(
		@CurrentMemberId Long actorId,
		@PathVariable Long memberId,
		@RequestBody @Valid MemberAdminRequest.ChangeRole request
	) {
		return ResponseEntity.ok(ApiResponse.success(
			memberAdminService.changeRole(actorId, memberId, request)));
	}
}
