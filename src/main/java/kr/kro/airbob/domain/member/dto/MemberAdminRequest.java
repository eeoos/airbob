package kr.kro.airbob.domain.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.kro.airbob.domain.member.common.MemberRole;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberAdminRequest {

	@Schema(name = "MemberAdminRequestChangeRole")
	public record ChangeRole(
		@Schema(description = "변경할 역할. MEMBER는 일반 회원, ADMIN은 관리자입니다.", example = "ADMIN")
		@NotNull MemberRole role,
		@Schema(description = "변경 사유. 앞뒤 공백 제거 후 1~255자이며, 같은 역할을 다시 요청할 때도 필수입니다.",
			example = "운영 관리자 지정")
		@NotBlank @Size(min = 1, max = 255) String reason
	) {
		public ChangeRole {
			if (reason != null) {
				reason = reason.strip();
			}
		}
	}
}
