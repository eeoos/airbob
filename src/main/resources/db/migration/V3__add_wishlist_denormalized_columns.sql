-- V3__add_wishlist_denormalized_columns.sql
-- 위시리스트 목록 조회 핫패스(COUNT GROUP BY + ROW_NUMBER 썸네일)를 제거하기 위한 반정규화 컬럼.
--  * accommodation_count            : 저장한 숙소 개수(멤버십 수, 게시 여부 무관)
--  * representative_accommodation_id: 대표(가장 최근에 추가된) 숙소 id. 썸네일은 PK 배치 조인으로 조회.
-- 추가/삭제 시 WishlistService 가 원자적 UPDATE 로 유지한다.

ALTER TABLE wishlist
  ADD COLUMN accommodation_count int NOT NULL DEFAULT 0,
  ADD COLUMN representative_accommodation_id bigint DEFAULT NULL;

-- 기존 데이터 백필
UPDATE wishlist w
SET accommodation_count = (
  SELECT COUNT(*) FROM wishlist_accommodation wa WHERE wa.wishlist_id = w.id
);

UPDATE wishlist w
SET representative_accommodation_id = (
  SELECT wa.accommodation_id
  FROM wishlist_accommodation wa
  WHERE wa.wishlist_id = w.id
  ORDER BY wa.created_at DESC, wa.id DESC
  LIMIT 1
);
