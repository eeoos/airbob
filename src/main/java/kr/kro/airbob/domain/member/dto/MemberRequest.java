package kr.kro.airbob.domain.member.dto;

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
    public static class Signup {
        @NotBlank
        @Size(min = 1, max = 20)
        private String nickname;
        @NotBlank
        @Email
        private String email;
        @NotBlank
        @Size(min = 8, max = 20)
        private String password;
        private String thumbnailImageUrl;
    }
}
