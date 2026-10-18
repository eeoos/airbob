package kr.kro.airbob.domain.member.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.common.dto.ApiResponse;
import kr.kro.airbob.domain.member.dto.MemberRequest;
import kr.kro.airbob.domain.member.service.MemberService;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class MemberController implements MemberApi {

    private final MemberService memberService;

    @Override
    public ResponseEntity<ApiResponse<Void>> signup(MemberRequest.Signup request) {
        memberService.createMember(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
    }
}
