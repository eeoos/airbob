package kr.kro.airbob.domain.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.kro.airbob.domain.member.common.MemberRole;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberAdminResponse {

	@Schema(name = "MemberAdminResponseRoleChanged")
	public record RoleChanged(
		@Schema(description = "역할 변경 대상 회원 ID", example = "20", requiredMode = Schema.RequiredMode.REQUIRED)
		Long memberId,
		@Schema(description = "요청 처리 후 대상 회원의 역할", example = "ADMIN", requiredMode = Schema.RequiredMode.REQUIRED)
		MemberRole role
	) {
	}
}
