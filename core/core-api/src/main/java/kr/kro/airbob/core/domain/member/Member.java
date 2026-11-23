package kr.kro.airbob.core.domain.member;

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
import kr.kro.airbob.core.support.persistence.BaseEntity;
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

	/** 가입할 회원. 비밀번호는 hashPassword로 만든 해시를 받는다. */
	public static Member create(NewMember newMember, String passwordHash) {
		return Member.builder()
				.nickname(newMember.nickname())
				.email(newMember.email())
				.password(passwordHash)
				.thumbnailImageUrl(newMember.thumbnailImageUrl())
				.role(MemberRole.MEMBER)
				.build();
	}

	/** 저장할 비밀번호 해시(BCrypt). 검증은 matchesPassword가 같은 방식으로 한다. */
	public static String hashPassword(String rawPassword) {
		return BCrypt.hashpw(rawPassword, BCrypt.gensalt());
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
