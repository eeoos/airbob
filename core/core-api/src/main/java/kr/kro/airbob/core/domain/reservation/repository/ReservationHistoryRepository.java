package kr.kro.airbob.core.domain.reservation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.core.domain.reservation.ReservationHistory;

public interface ReservationHistoryRepository extends JpaRepository<ReservationHistory, Long> {}
