package kr.kro.airbob.domain.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.kro.airbob.domain.member.entity.Member;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MemberResponse {

	@Builder
	@Schema(name = "MemberResponseMemberInfo")
	public record MemberInfo(
		long id,
		@Schema(description = "회원 닉네임. 과거 데이터는 null일 수 있습니다.", example = "여행자", nullable = true)
		String nickname,
		@Schema(nullable = true) String thumbnailImageUrl
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

	@Schema(name = "MemberResponseMeInfo")
	public record MeInfo(
		@Schema(description = "현재 로그인한 회원의 ID", example = "10", requiredMode = Schema.RequiredMode.REQUIRED)
		Long id,
		@Schema(description = "회원 이메일. 과거 데이터는 null일 수 있습니다.", example = "docs@example.invalid",
			nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		String email,
		@Schema(description = "회원 닉네임. 과거 데이터는 null일 수 있습니다.", example = "여행자",
			nullable = true, requiredMode = Schema.RequiredMode.REQUIRED)
		String nickname
	) {
	}
}
