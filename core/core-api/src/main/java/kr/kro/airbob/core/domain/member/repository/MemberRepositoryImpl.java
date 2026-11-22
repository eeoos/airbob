package kr.kro.airbob.core.domain.member.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import kr.kro.airbob.core.domain.member.Member;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MemberRepositoryImpl implements MemberRepositoryCustom {

	private final EntityManager entityManager;

	@Override
	public void refreshWithWriteLock(Member member) {
		entityManager.refresh(member, LockModeType.PESSIMISTIC_WRITE);
	}
}
