package kr.kro.airbob.domain.coupon.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import kr.kro.airbob.domain.coupon.entity.Coupon;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {

	List<Coupon> findByIsActiveTrue();

	/**
	 * 발급 수를 DB 레벨에서 원자적으로 증가시킨다.
	 * 원자적 카운터(Redis) 방식에서 재고는 Redis 가 통제하므로, DB issuedQuantity 는
	 * read-modify-write 대신 이 단일 UPDATE 로 정확히 누적한다.
	 */
	@Modifying(clearAutomatically = true)
	@Query("update Coupon c set c.issuedQuantity = c.issuedQuantity + 1 where c.id = :id")
	int incrementIssuedQuantity(@Param("id") Long id);
}
