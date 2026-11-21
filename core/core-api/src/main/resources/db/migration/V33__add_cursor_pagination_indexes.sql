-- 게스트별 예약 커서 조회
CREATE INDEX idx_reservation_guest_created_id
    ON reservation (guest_id, created_at DESC, id DESC);

-- 호스트 소유 숙소의 예약 후보를 인덱스만으로 선별한 뒤, 선택된 예약의 표시 정보를 조인
-- 숙소별 커서 정렬 키와 상태·체크아웃 필터를 포함
-- 여러 숙소의 예약을 합치는 정렬은 유지
CREATE INDEX idx_reservation_accommodation_cursor_cover
    ON reservation (accommodation_id, created_at DESC, id DESC, status, check_out_at);

-- 호스트별 숙소 커서 조회
-- 전체 목록의 정렬 유지 및 상태 필터의 테이블 접근 감소
CREATE INDEX idx_accommodation_member_created_id_status
    ON accommodation (member_id, created_at DESC, id DESC, status);

-- 숙소별 공개 리뷰 최신순 커서 조회
CREATE INDEX idx_review_accommodation_status_created_id
    ON review (accommodation_id, status, created_at DESC, id DESC);

-- 숙소별 공개 리뷰 높은 평점순 커서 조회
CREATE INDEX idx_review_accommodation_status_rating_desc_created_id
    ON review (accommodation_id, status, rating DESC, created_at DESC, id DESC);

-- 숙소별 공개 리뷰 낮은 평점순 커서 조회
-- 평점은 오름차순, 같은 평점의 생성 시각과 ID는 내림차순
CREATE INDEX idx_review_accommodation_status_rating_asc_created_id
    ON review (accommodation_id, status, rating ASC, created_at DESC, id DESC);

-- 회원별 활성 위시리스트 커서 조회
CREATE INDEX idx_wishlist_member_status_created_id
    ON wishlist (member_id, status, created_at DESC, id DESC);

-- 위시리스트에 담긴 숙소 커서 조회
CREATE INDEX idx_wishlist_accommodation_wishlist_created_id
    ON wishlist_accommodation (wishlist_id, created_at DESC, id DESC);
