package kr.kro.airbob.domain.member.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import kr.kro.airbob.common.domain.BaseEntity;
import kr.kro.airbob.common.domain.UpdatableEntity;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.member.dto.MemberRequestDto.SignupMemberRequestDto;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends UpdatableEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String email;

	private String password;

	private String nickname;
	@Enumerated(EnumType.STRING)
	private MemberRole role;

	private String thumbnailImageUrl;

	public static Member createMember(SignupMemberRequestDto request, String hashedPassword) {
		return Member.builder()
				.nickname(request.getNickname())
				.email(request.getEmail())
				.password(hashedPassword)
				.thumbnailImageUrl(request.getThumbnailImageUrl())
				.role(MemberRole.MEMBER)
				.build();
	}
}
