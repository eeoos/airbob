package kr.kro.airbob.core.api.controller.v1.member;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import kr.kro.airbob.core.api.controller.v1.member.request.MemberRequest;
import kr.kro.airbob.core.domain.member.MemberService;
import kr.kro.airbob.core.support.response.ApiResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class MemberController implements MemberApi {

	private final MemberService memberService;

	@Override
	public ResponseEntity<ApiResponse<Void>> signup(MemberRequest.Signup request) {
		memberService.createMember(request.toNewMember());
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success());
	}
}
