package kr.kro.airbob.domain.auth.dto;

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
    public static class Login {
        @NotBlank
        private String email;
        @NotBlank
        private String password;
    }
}
