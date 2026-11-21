package kr.kro.airbob.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

public class AuthRequest {
    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @Schema(name = "AuthRequestLogin")
    public static class Login {
        @NotBlank
        @Schema(description = "가입된 활성 회원의 이메일. 빈 값이나 공백만 있는 값은 허용하지 않습니다.", example = "docs@example.invalid")
        private String email;
        @NotBlank
        @Schema(description = "회원 비밀번호. 빈 값이나 공백만 있는 값은 허용하지 않습니다.",
            example = "example-password", format = "password", accessMode = Schema.AccessMode.WRITE_ONLY)
        private String password;
    }
}
