package kr.kro.airbob.domain.auth.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import kr.kro.airbob.core.api.controller.v1.member.response.MemberResponse;
import kr.kro.airbob.core.api.security.SessionCookie;
import kr.kro.airbob.core.domain.member.MemberService;
import kr.kro.airbob.core.support.response.ApiResponse;
import kr.kro.airbob.domain.auth.dto.AuthRequest.Login;
import kr.kro.airbob.domain.auth.service.AuthService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class AuthController implements AuthApi {

    private final AuthService authService;
    private final MemberService memberService;

    @Override
    public ResponseEntity<ApiResponse<Void>> login(Login request, HttpServletResponse response) {
        String sessionId = authService.login(request.getEmail(), request.getPassword());

        response.addCookie(SessionCookie.create(sessionId));

        return ResponseEntity.ok(ApiResponse.success());
    }

    @Override
    public ResponseEntity<ApiResponse<Void>> logout(String sessionId) {
        authService.logout(sessionId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    @Override
    public ResponseEntity<ApiResponse<MemberResponse.MeInfo>> getMyInfo(Long memberId) {
        MemberResponse.MeInfo response = MemberResponse.MeInfo.from(memberService.getMemberInfo(memberId));
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
