package kr.kro.airbob.domain.member.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.common.exception.ErrorCode;
import kr.kro.airbob.config.apidocs.ApiErrors;
import kr.kro.airbob.domain.member.dto.MemberRequest;
import kr.kro.airbob.domain.member.service.MemberService;
import lombok.RequiredArgsConstructor;

@Tag(name = "회원")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class MemberController {

    private final MemberService memberService;

    @Operation(operationId = "Member.signup", summary = "회원 가입",
        description = """
            로그인 없이 활성 상태의 일반 회원(MEMBER)을 생성합니다. 닉네임·이메일·비밀번호는 필수이고 썸네일은 선택입니다.
            탈퇴·휴면 회원을 포함해 이미 등록된 이메일은 사용할 수 없습니다. 중복된 요청은 동시 가입 시에도 409/M002로 거절합니다.

            성공 응답은 201과 {"success":true}입니다. 회원 ID나 세션 쿠키를 반환하지 않으므로,
            가입 후 인증 → 로그인으로 세션을 발급받고 인증 → 로그인 회원 조회에서 회원 ID를 확인합니다.
            """)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 완료", useReturnTypeSchema = true)
    @ApiErrors({
        ErrorCode.INVALID_INPUT_VALUE,
        ErrorCode.EMAIL_DUPLICATION
    })
    @PostMapping("/v1/members")
    public ResponseEntity<ApiResponse<Void>> signup(@RequestBody @Valid MemberRequest.Signup request) {
        memberService.createMember(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
    }
}
