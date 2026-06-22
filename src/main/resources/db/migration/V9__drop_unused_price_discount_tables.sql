-- V9__drop_unused_price_discount_tables.sql
-- 미사용 테이블 정리. 두 테이블 모두 애플리케이션에서 참조하는 로직이 없는 죽은 코드였다.
--  * price_policy: 계절/기간별 기본요금 엔티티 골격만 있고 repository/service 없음 (참조 0건)
--  * accommodation_discount_policy: 숙소-할인정책 연결 테이블이나 어디서도 사용되지 않음 (참조 0건)
-- 쿠폰 도메인은 유저 귀속(member_coupon)으로 재설계하므로 숙소 조인 테이블은 불필요하다.

DROP TABLE IF EXISTS accommodation_discount_policy;
DROP TABLE IF EXISTS price_policy;
