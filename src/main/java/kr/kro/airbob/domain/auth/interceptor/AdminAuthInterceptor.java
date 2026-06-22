package kr.kro.airbob.domain.auth.interceptor;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.domain.auth.exception.AdminAccessDeniedException;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;

/**
 * 관리자 전용 경로(/api/v1/admin/**) 인가 가드.
 *
 * <p>인증(로그인 여부)은 SessionAuthFilter 가 담당하고, 여기서는 인가(ADMIN 권한)만 검사한다.
 * 필터가 비공개 경로의 인증을 보장하므로 이 시점엔 UserContext 에 회원 식별자가 채워져 있다.
 * 세션에는 memberId 만 담기므로 role 은 DB 에서 조회한다(관리자 요청은 드물어 비용 무시 가능).
 */
@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

	private final MemberRepository memberRepository;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		UserInfo userInfo = UserContext.get();
		if (userInfo == null || userInfo.id() == null) {
			throw new AdminAccessDeniedException();
		}

		Member member = memberRepository.findById(userInfo.id())
			.orElseThrow(AdminAccessDeniedException::new);

		if (member.getRole() != MemberRole.ADMIN) {
			throw new AdminAccessDeniedException();
		}
		return true;
	}
}
