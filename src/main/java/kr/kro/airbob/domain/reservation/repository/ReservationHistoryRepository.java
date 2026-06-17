package kr.kro.airbob.domain.reservation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.reservation.entity.ReservationHistory;

public interface ReservationHistoryRepository extends JpaRepository<ReservationHistory, Long> {
}
