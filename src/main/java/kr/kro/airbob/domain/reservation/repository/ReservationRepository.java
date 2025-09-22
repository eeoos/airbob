package kr.kro.airbob.domain.reservation.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import kr.kro.airbob.domain.reservation.entity.Reservation;

public interface ReservationRepository extends JpaRepository<Reservation, Long>, ReservationRepositoryCustom{

}
