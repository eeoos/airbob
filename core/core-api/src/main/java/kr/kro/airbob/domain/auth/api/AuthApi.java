package kr.kro.airbob.domain.auth.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.config.apidocs.ApiErrors;
import kr.kro.airbob.domain.auth.annotation.CurrentMemberId;
import kr.kro.airbob.domain.auth.dto.AuthRequest.Login;
import kr.kro.airbob.domain.member.dto.MemberResponse;

@Tag(name = "인증")
@RequestMapping("/api")
public interface AuthApi {

    @Operation(operationId = "Auth.login", summary = "로그인",
        description = """
            가입된 활성 회원의 이메일과 비밀번호로 로그인합니다. 두 필드는 필수이며 빈 값이나 공백만 있는 값은 허용하지 않습니다.
            예시 값은 요청 형식을 보여 주며, 실행할 때는 실제 가입한 계정으로 바꿉니다.

            성공하면 Set-Cookie로 HttpOnly SESSION_ID 쿠키를 발급합니다. 쿠키와 서버 세션의 유효 시간은 발급 후 최대 1시간입니다.
            응답 본문은 {"success":true}이며 세션 ID는 포함하지 않습니다.

            같은 서버의 Swagger UI에서는 브라우저가 쿠키를 자동 저장·전송하므로 Authorize에 값을 입력하지 않습니다.
            브라우저 정책상 Swagger의 Response headers에는 Set-Cookie가 표시되지 않습니다.
            로그인 성공 후 GET /api/v1/auth/me의 200 응답으로 세션을 확인합니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true,
        headers = @Header(name = "Set-Cookie", description = "SESSION_ID 세션 쿠키; HttpOnly; Path=/; Max-Age=3600", schema = @Schema(type = "string")))
    @ApiErrors({
        ErrorCode.INVALID_INPUT_VALUE,
        ErrorCode.MEMBER_NOT_FOUND,
        ErrorCode.INVALID_PASSWORD
    })
    @PostMapping("/v1/auth/login")
    ResponseEntity<ApiResponse<Void>> login(@Valid @RequestBody Login request, HttpServletResponse response);

    @Operation(operationId = "Auth.logout", summary = "로그아웃",
        description = """
            브라우저가 자동 전송하는 SESSION_ID에 해당하는 서버 세션 한 개를 무효화합니다. 다른 로그인 세션은 유지됩니다.
            같은 서버에서 로그인한 뒤 별도 매개변수나 본문 입력 없이 실행합니다. 성공 응답은 {"success":true}입니다.

            브라우저의 쿠키를 지우는 Set-Cookie는 내려주지 않습니다. 쿠키가 남아 있어도 서버 세션은 무효화되어 이후 인증 요청은 401입니다.
            쿠키가 없거나 이미 만료·무효화된 세션으로 로그아웃을 요청해도 401/M004를 반환합니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
    @PostMapping("/v1/auth/logout")
    ResponseEntity<ApiResponse<Void>> logout(@Parameter(hidden = true) @CookieValue("SESSION_ID") String sessionId);

    @Operation(operationId = "Auth.getMyInfo", summary = "로그인 회원 조회",
        description = "현재 세션의 활성 회원 ID, 이메일, 닉네임을 조회합니다. memberId를 직접 입력하지 않습니다. "
            + "쿠키가 없거나 세션이 유효하지 않으면 401/M004, 세션 확인 후 활성 회원을 찾지 못하면 404/M001을 반환합니다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공", useReturnTypeSchema = true)
    @ApiErrors(ErrorCode.MEMBER_NOT_FOUND)
    @GetMapping("/v1/auth/me")
    ResponseEntity<ApiResponse<MemberResponse.MeInfo>> getMyInfo(@CurrentMemberId Long memberId);
}
