package kr.kro.airbob.domain.member.dto;

import kr.kro.airbob.domain.member.entity.Member;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberResponse {

	@Builder
	public record MemberInfo(
		long id,
		String nickname,
		String thumbnailImageUrl
	) {
		public static MemberInfo from(Member member) {
			if(member == null) return MemberInfo.builder().build();
			return MemberInfo.builder()
				.id(member.getId())
				.nickname(member.getNickname())
				.thumbnailImageUrl(member.getThumbnailImageUrl())
				.build();
		}
	}

	public record MeInfo(
		Long id,
		String email,
		String nickname,
		String thumbnailImageUrl
	) {
	}
}
