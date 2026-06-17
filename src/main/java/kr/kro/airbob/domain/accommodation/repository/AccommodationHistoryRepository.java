package kr.kro.airbob.domain.accommodation.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.accommodation.entity.AccommodationHistory;

public interface AccommodationHistoryRepository extends JpaRepository<AccommodationHistory, Long> {

	// SCD2: 숙소의 현재 유효 행(valid_to = 센티넬) 조회 — 변경 시 닫기 위함
	Optional<AccommodationHistory> findByAccommodationIdAndValidTo(Long accommodationId, LocalDateTime validTo);
}
