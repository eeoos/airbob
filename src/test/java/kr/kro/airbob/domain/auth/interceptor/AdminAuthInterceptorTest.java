package kr.kro.airbob.domain.auth.interceptor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.*;

import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import kr.kro.airbob.common.context.UserContext;
import kr.kro.airbob.common.context.UserInfo;
import kr.kro.airbob.domain.auth.exception.AdminAccessDeniedException;
import kr.kro.airbob.domain.member.common.MemberRole;
import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.repository.MemberRepository;

@DisplayName("관리자 인가 인터셉터 단위 테스트")
class AdminAuthInterceptorTest {

	private final MemberRepository memberRepository = mock(MemberRepository.class);
	private final AdminAuthInterceptor interceptor = new AdminAuthInterceptor(memberRepository);

	@AfterEach
	void tearDown() {
		UserContext.clear();
	}

	@Test
	@DisplayName("ADMIN 회원이면 통과한다")
	void adminPasses() {
		UserContext.set(new UserInfo(1L));
		Member admin = mock(Member.class);
		given(admin.getRole()).willReturn(MemberRole.ADMIN);
		given(memberRepository.findById(1L)).willReturn(Optional.of(admin));

		assertThat(interceptor.preHandle(null, null, new Object())).isTrue();
	}

	@Test
	@DisplayName("일반 회원이면 403(AdminAccessDeniedException)")
	void memberRejected() {
		UserContext.set(new UserInfo(1L));
		Member member = mock(Member.class);
		given(member.getRole()).willReturn(MemberRole.MEMBER);
		given(memberRepository.findById(1L)).willReturn(Optional.of(member));

		assertThatThrownBy(() -> interceptor.preHandle(null, null, new Object()))
			.isInstanceOf(AdminAccessDeniedException.class);
	}

	@Test
	@DisplayName("인증 컨텍스트가 없으면 거부한다")
	void noContextRejected() {
		assertThatThrownBy(() -> interceptor.preHandle(null, null, new Object()))
			.isInstanceOf(AdminAccessDeniedException.class);
	}

	@Test
	@DisplayName("회원을 찾을 수 없으면 거부한다")
	void memberNotFoundRejected() {
		UserContext.set(new UserInfo(99L));
		given(memberRepository.findById(99L)).willReturn(Optional.empty());

		assertThatThrownBy(() -> interceptor.preHandle(null, null, new Object()))
			.isInstanceOf(AdminAccessDeniedException.class);
	}
}
