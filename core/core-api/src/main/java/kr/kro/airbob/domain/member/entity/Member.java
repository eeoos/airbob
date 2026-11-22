package kr.kro.airbob.domain.member.entity;

import java.util.Objects;

import org.mindrot.jbcrypt.BCrypt;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import kr.kro.airbob.common.domain.BaseEntity;
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
public class Member extends BaseEntity {

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

	/** 입력한 비밀번호가 저장된 BCrypt 해시와 맞는지. 값이 없거나 해시 형식이 잘못되었으면 false다. */
	public boolean matchesPassword(String rawPassword) {
		if (rawPassword == null || password == null) {
			return false;
		}
		try {
			return BCrypt.checkpw(rawPassword, password);
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	/**
	 * 비밀번호 해시가 그대로인지. 비밀번호 확인과 세션 발급 사이에 비밀번호가 바뀌었는지 확인하는 데 쓴다.
	 * 해시는 비교만 하며 다시 검증하지 않는다.
	 */
	public boolean hasPasswordHash(String passwordHash) {
		return Objects.equals(password, passwordHash);
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

	public void changeRole(MemberRole role) {
		this.role = role;
	}
}
