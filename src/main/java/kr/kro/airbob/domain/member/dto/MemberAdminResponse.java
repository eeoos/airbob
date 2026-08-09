package kr.kro.airbob.domain.member.dto;

import kr.kro.airbob.domain.member.common.MemberRole;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberAdminResponse {

	public record RoleChanged(Long memberId, MemberRole role) {
	}
}
