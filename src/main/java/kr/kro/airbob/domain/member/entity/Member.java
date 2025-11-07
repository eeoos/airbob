package kr.kro.airbob.domain.member.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import kr.kro.airbob.common.domain.UpdatableEntity;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.member.dto.MemberRequest.Signup;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Entity
@Getter
@SuperBuilder
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

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private MemberStatus status;

	private String thumbnailImageUrl;

	@PrePersist
	protected void onPrePersist() {
		this.role = MemberRole.MEMBER;
		this.status = MemberStatus.ACTIVE; // 기본 상태 ACTIVE
	}

	public static Member createMember(Signup request, String hashedPassword) {
		return Member.builder()
				.nickname(request.getNickname())
				.email(request.getEmail())
				.password(hashedPassword)
				.thumbnailImageUrl(request.getThumbnailImageUrl())
				.role(MemberRole.MEMBER)
				.build();
	}

	public void delete() {
		this.status = MemberStatus.DELETED;
	}

	public void dormant() {
		this.status = MemberStatus.DORMANT;
	}

	public void activate() {
		this.status = MemberStatus.ACTIVE;
	}
}
