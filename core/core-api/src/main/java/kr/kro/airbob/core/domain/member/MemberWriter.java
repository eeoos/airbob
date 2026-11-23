package kr.kro.airbob.core.domain.member;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import kr.kro.airbob.core.domain.member.repository.MemberHistoryRepository;
import kr.kro.airbob.core.domain.member.repository.MemberRepository;
import kr.kro.airbob.core.support.persistence.history.ChangeType;
import kr.kro.airbob.core.support.persistence.history.HistoryConstants;
import lombok.RequiredArgsConstructor;

/**
 * 회원 변경 구현 컴포넌트. 변경마다 회원 이력(SCD2)을 함께 남긴다: 직전 현재 행을 닫고 새 스냅샷을 연다.
 */
@Component
@RequiredArgsConstructor
public class MemberWriter {

	private final MemberRepository memberRepository;
	private final MemberHistoryRepository historyRepository;
	private final Clock clock;

	public Member create(NewMember newMember) {
		if (memberRepository.existsByEmail(newMember.email())) {
			throw new DuplicatedEmailException();
		}

		Member member = Member.create(newMember, Member.hashPassword(newMember.password()));
		try {
			// IDENTITY inserts immediately; the unique index also guards concurrent signups.
			memberRepository.save(member);
		} catch (DataIntegrityViolationException exception) {
			if (isDuplicateEmail(exception)) {
				throw new DuplicatedEmailException(exception);
			}
			throw exception;
		}

		// 첫 데이터(CREATE)부터 이력 기록 — 가입은 비인증 컨텍스트라 source_system 명시
		historyRepository.save(MemberHistory.openSystem(
			member, ChangeType.CREATE, "신규 회원가입", "API", utcNow()));
		return member;
	}

	/** 잠근 회원을 탈퇴 상태로 바꾼다. */
	public void withdraw(Member member, String reason) {
		member.delete();
		memberRepository.save(member);
		recordChange(member, ChangeType.DELETE, reason);
	}

	/** 잠근 회원의 역할을 바꾼다. */
	public void changeRole(Member member, MemberRole role, String reason) {
		member.changeRole(role);
		recordChange(member, ChangeType.ROLE_CHANGE, reason);
	}

	private void recordChange(Member member, ChangeType changeType, String reason) {
		LocalDateTime changedAt = utcNow();
		historyRepository.findByMemberIdAndValidTo(member.getId(), HistoryConstants.FOREVER)
			.ifPresent(current -> current.close(changedAt));
		historyRepository.save(MemberHistory.open(member, changeType, reason, changedAt));
	}

	private LocalDateTime utcNow() {
		return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
	}

	private boolean isDuplicateEmail(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation && violation.getErrorCode() == 1062) {
				String constraint = violation.getConstraintName();
				return "uk_member_email".equals(constraint) || "member.uk_member_email".equals(constraint);
			}
		}
		return false;
	}
}
