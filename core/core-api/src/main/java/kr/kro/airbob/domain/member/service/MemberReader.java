package kr.kro.airbob.domain.member.service;

import org.springframework.stereotype.Component;

import kr.kro.airbob.domain.member.entity.Member;
import kr.kro.airbob.domain.member.entity.MemberStatus;
import kr.kro.airbob.domain.member.exception.MemberNotFoundException;
import kr.kro.airbob.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;

/**
 * Implement component through which other domains read members, instead of using MemberRepository.
 */
@Component
@RequiredArgsConstructor
public class MemberReader {

	private final MemberRepository memberRepository;

	public Member readActive(Long memberId) {
		return memberRepository.findByIdAndStatus(memberId, MemberStatus.ACTIVE)
			.orElseThrow(MemberNotFoundException::new);
	}

	/** 조회 없이 회원 참조(프록시)를 만든다. 존재 확인이 필요 없는 연관관계 저장용이다. */
	public Member readReference(Long memberId) {
		return memberRepository.getReferenceById(memberId);
	}

	/** 로그인용: 이메일로 활성 회원을 찾는다. */
	public Member readActiveByEmail(String email) {
		return memberRepository.findByEmailAndStatus(email, MemberStatus.ACTIVE)
			.orElseThrow(MemberNotFoundException::new);
	}

	/** 활성 회원 행을 잠근다. 세션 발급처럼 회원 상태·비밀번호 변경과 직렬화해야 하는 작업이 쓴다. */
	public Member readActiveForUpdate(Long memberId) {
		return memberRepository.findByIdAndStatusForUpdate(memberId, MemberStatus.ACTIVE)
			.orElseThrow(MemberNotFoundException::new);
	}
}
