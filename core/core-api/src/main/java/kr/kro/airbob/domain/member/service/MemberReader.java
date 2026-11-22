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
}
