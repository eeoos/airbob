package kr.kro.airbob.core.domain.member;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.member.repository.MemberRepository;
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

	/** 상태와 무관하게 회원 행을 잠근다(탈퇴 처리용). */
	public Member readForUpdate(Long memberId) {
		return memberRepository.findByIdForUpdate(memberId)
			.orElseThrow(MemberNotFoundException::new);
	}

	/**
	 * 회원 행들을 ID 오름차순으로 잠그고, 잠금 이후의 현재 행으로 다시 읽는다(역할 변경용).
	 * 없는 ID는 결과에 빠진다.
	 */
	public Map<Long, Member> readAllForUpdate(Collection<Long> memberIds) {
		List<Long> ids = memberIds.stream().distinct().sorted().toList();
		List<Member> lockedMembers = memberRepository.findAllByIdForUpdate(ids).stream()
			.sorted(Comparator.comparing(Member::getId))
			.toList();
		lockedMembers.forEach(memberRepository::refreshWithWriteLock);
		return lockedMembers.stream().collect(toMap(Member::getId, identity()));
	}

	public MemberProfile readActiveProfile(Long memberId) {
		return memberRepository.findProfileByIdAndStatus(memberId, MemberStatus.ACTIVE)
			.orElseThrow(MemberNotFoundException::new);
	}

	/** 활성 상태의 ADMIN 회원인지. */
	public boolean isActiveAdmin(Long memberId) {
		return memberRepository.existsByIdAndStatusAndRole(memberId, MemberStatus.ACTIVE, MemberRole.ADMIN);
	}
}
