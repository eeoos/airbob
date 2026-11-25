package kr.kro.airbob.core.domain.member;

/** 가입할 회원. password는 평문이며 저장 전에 해시한다. */
public record NewMember(
	String email,
	String password,
	String nickname,
	String thumbnailImageUrl) {
}
