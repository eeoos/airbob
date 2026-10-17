package kr.kro.airbob.domain.auth.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.auth.dto.AuthRequest.Login;
import kr.kro.airbob.domain.auth.service.AuthService;
import kr.kro.airbob.domain.member.dto.MemberResponse;
import kr.kro.airbob.domain.member.service.MemberService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class AuthController implements AuthApi {

    private final AuthService authService;
    private final MemberService memberService;

    @Override
    public ResponseEntity<ApiResponse<Void>> login(Login request, HttpServletResponse response) {
        String sessionId = authService.login(request.getEmail(), request.getPassword());

        Cookie cookie = new Cookie("SESSION_ID", sessionId);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(3600); // 1시간
        response.addCookie(cookie);

        return ResponseEntity.ok(ApiResponse.success());
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> logout(String sessionId) {
        authService.logout(sessionId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Override
    public ResponseEntity<ApiResponse<MemberResponse.MeInfo>> getMyInfo(Long memberId) {
        MemberResponse.MeInfo response = memberService.getMemberInfo(memberId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
