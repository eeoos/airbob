package kr.kro.airbob.domain.member.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.member.entity.MemberHistory;

public interface MemberHistoryRepository extends JpaRepository<MemberHistory, Long> {

	// SCD2: 회원의 현재 유효 행(valid_to = 센티넬) 조회 — 변경 시 닫기 위함
	Optional<MemberHistory> findByMemberIdAndValidTo(Long memberId, LocalDateTime validTo);
}
