package kr.kro.airbob.domain.member.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class MemberRequest {
    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @Schema(name = "MemberRequestSignup")
    public static class Signup {
        @NotBlank
        @Size(min = 1, max = 20)
        @Schema(description = "1~20자 회원 표시 이름. 공백만 있는 값은 허용하지 않습니다.", example = "여행자")
        private String nickname;
        @NotBlank
        @Email
        @Size(min = 1, max = 255)
        @Schema(description = "가입할 이메일. 최대 255자이며 기존 회원의 이메일은 사용할 수 없습니다.", example = "docs@example.invalid")
        private String email;
        @NotBlank
        @Size(min = 8, max = 20)
        @Schema(description = "8~20자 비밀번호. 공백만 있는 값은 허용하지 않습니다.", example = "example-password",
            format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
        private String password;
        @Size(max = 255)
        @Schema(description = "회원 썸네일 URL. 선택 항목으로 생략하거나 null을 보낼 수 있으며 최대 255자입니다.",
            example = "https://example.invalid/profile.jpg", nullable = true)
        private String thumbnailImageUrl;
    }
}
