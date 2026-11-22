package kr.kro.airbob.core.domain.member.repository;

import kr.kro.airbob.core.domain.member.Member;

public interface MemberRepositoryCustom {

	/**
	 * 잠근 회원을 쓰기 잠금과 함께 다시 읽는다. 잠금 전에 영속성 컨텍스트에 올라온 이전 상태를 버리고 현재 행으로 판단하기 위해서다.
	 */
	void refreshWithWriteLock(Member member);
}
