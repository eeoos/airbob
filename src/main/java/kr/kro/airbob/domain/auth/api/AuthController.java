package kr.kro.airbob.domain.auth.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.auth.service.AuthService;
import kr.kro.airbob.domain.auth.dto.AuthRequest.Login;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/v1/auth/login")
    public ResponseEntity<ApiResponse<Void>> login(@RequestBody Login request, HttpServletResponse response) {
        String sessionId = authService.login(request.getEmail(), request.getPassword());

        Cookie cookie = new Cookie("SESSION_ID", sessionId);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(3600); // 1시간
        response.addCookie(cookie);

        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/v1/auth/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@CookieValue("SESSION_ID") String sessionId) {
        authService.logout(sessionId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @GetMapping("/v1/auth/me")
    public ResponseEntity<ApiResponse<MemberResponse.MeInfo>> getMyInfo() {
        Long memberId = UserContext.get().id();
        MemberResponse.MeInfo response = authService.getMemberInfo(memberId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
