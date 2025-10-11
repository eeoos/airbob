package kr.kro.airbob.domain.reservation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.reservation.entity.ReservationStatusHistory;

public interface ReservationStatusHistoryRepository extends JpaRepository<ReservationStatusHistory, Long> {
}
