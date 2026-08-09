package kr.kro.airbob.domain.member.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.kro.airbob.domain.member.common.MemberRole;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberAdminRequest {

	public record ChangeRole(
		@NotNull MemberRole role,
		@NotBlank @Size(max = 255) String reason
	) {
		public ChangeRole {
			if (reason != null) {
				reason = reason.strip();
			}
		}
	}
}
