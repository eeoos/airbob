package kr.kro.airbob.domain.member.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.config.apidocs.ApiErrors;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.member.dto.MemberAdminRequest;
import kr.kro.airbob.domain.member.dto.MemberAdminResponse;

@Tag(name = "회원 관리")
@RequestMapping("/api")
public interface MemberAdminApi {

	@Operation(operationId = "MemberAdmin.changeRole", summary = "회원 역할 변경",
		description = """
			활성 ADMIN 회원이 대상 활성 회원의 역할을 MEMBER 또는 ADMIN으로 변경합니다.
			client 그룹에서 관리자로 로그인한 뒤 실행합니다. 경로의 memberId는 변경 대상이며, 요청자의 ID는 세션에서 결정합니다.

			role과 reason은 필수입니다. 사유의 앞뒤 공백을 제거한 뒤 1~255자인지 확인합니다.
			이미 같은 역할이면 200으로 현재 역할을 반환하며 변경 이력을 추가하지 않습니다.
			자신을 MEMBER로 변경하면 409/M007, 대상이 없거나 탈퇴·휴면 상태이면 404/M001입니다.

			기존 로그인 세션은 유지되며, 변경된 권한은 대상 회원의 다음 관리자 API 요청부터 반영됩니다. 다시 로그인할 필요는 없습니다.
			""")
	@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
	@ApiErrors({
		ErrorCode.INVALID_INPUT_VALUE,
		ErrorCode.MEMBER_NOT_FOUND,
		ErrorCode.MEMBER_ROLE_CHANGE_NOT_ALLOWED,
		ErrorCode.INVALID_TYPE_VALUE
	})
	@PatchMapping("/v1/admin/members/{memberId}/role")
	ResponseEntity<ApiResponse<MemberAdminResponse.RoleChanged>> changeRole(
		@CurrentMemberId Long actorId,
		@Parameter(description = "역할을 변경할 대상 회원 ID", example = "20") @PathVariable Long memberId,
		@RequestBody @Valid MemberAdminRequest.ChangeRole request
	);
}
