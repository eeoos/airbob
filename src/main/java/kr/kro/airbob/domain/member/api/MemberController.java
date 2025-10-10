package kr.kro.airbob.domain.member.api;

import jakarta.validation.Valid;
import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.member.dto.MemberRequestDto;
import kr.kro.airbob.domain.member.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class MemberController {

    private final MemberService memberService;

    @PostMapping("/v1/members")
    public ResponseEntity<ApiResponse<Void>> signup(@RequestBody @Valid MemberRequestDto.SignupMemberRequestDto request) {
        memberService.createMember(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
    }
}
