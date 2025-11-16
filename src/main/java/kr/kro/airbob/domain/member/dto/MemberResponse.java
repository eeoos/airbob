package kr.kro.airbob.domain.member.dto;

import java.time.LocalDateTime;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberResponse {

	public record ReviewerInfo(
		long id,
		String nickname,
		String thumbnailImageUrl
	) {
	}

	public record MeInfo(
		Long id,
		String email,
		String nickname,
		String thumbnailImageUrl
	) {
	}
}
