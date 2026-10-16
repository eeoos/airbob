# 벌크 쓰기 실험 보관 안내

위시리스트·편의시설 삭제와 예약 이력 INSERT의 Before/After 성능 비교를 마쳐
애플리케이션의 비교용 API, fixture 생성 코드와 전용 프로필을 제거했다.
현재 애플리케이션에는 최적화된 운영 구현을 유지한다.
측정 방법을 설명하는 [부하 테스트 스크립트와 가이드](../../load-test/README.md)는 보존한다.

- 위시리스트: `WishlistService.deleteWishlist()`의 `deleteAllInBatch()`.
- 편의시설: `AccommodationCommandService.updateAccommodation()`에서
  `deleteByAccommodationIdInBulk()`를 호출한 뒤 새 편의시설을 저장한다.
  `amenityInfos=null`은 기존 편의시설 유지, 빈 목록은 전체 삭제다.
- 예약 만료: `ExpiredReservationCleanupService`가 만료 예약을 제한된 크기로 조회하고
  `ReservationHistoryBatchWriter`의 JDBC batch로 이력을 저장한다.
  예약 상태 변경, 소유한 재고 해제, 쿠폰 복원과 이력 저장은 같은 트랜잭션에서 처리한다.

## 과거 소스와 측정 근거

- [삭제 실험 도구가 추가된 커밋](https://github.com/eeoos/airbob/commit/b6d1be8224d05bd6fc516eee837c1949d87bf7f8)
- [삭제 실험 정리 직전 실행 안내와 도구](https://github.com/eeoos/airbob/tree/a10b89a1bfdc173f3f327ed4657a1df2db14d2cd/load-test/k6/bulk-write)
- [INSERT 실험 정리 직전 실행 안내](https://github.com/eeoos/airbob/blob/5ab7360edd01aa29355bb2fa79fcd6060c8659c4/load-test/README.md)
- [INSERT 실험 정리 직전 소스와 도구](https://github.com/eeoos/airbob/tree/5ab7360edd01aa29355bb2fa79fcd6060c8659c4/load-test/k6/bulk-write)

기존 측정 결과 JSON과 환경 기록은 이 정리에서 삭제하지 않는다. 결과를 설명하거나
재현할 때는 각 실행에 기록된 앱 커밋, 이미지 digest, DB 버전과 설정을 함께 확인한다.
과거 실행 안내는 해당 커밋의 소스를 대상으로 하며 현재 브랜치에서는 사용할 수 없다.
과거 AWS 릴리스의 지문과 구버전 manifest 거부를 검증하는 테스트 fixture에는
당시 실험 메타데이터를 보관한다.

편의시설의 `FULL_REPLACEMENT`는 숙소 수정 트랜잭션 전체, `DELETE_ONLY`는 삭제만
측정했다. 예약 이력 INSERT 비교는 만료 예약 정리 트랜잭션을 대상으로 했다.
각 결과의 측정 범위를 그대로 유지한다.

## 유지하는 검증과 모니터링

일반 MySQL 테스트에서 삭제 범위·권한·이력·캐시 무효화를 검증한다. 예약 이력은
배치 경계와 스냅샷 저장을 검증하고, 두 번째 배치가 실패하면 첫 배치, 예약 상태,
재고 해제와 쿠폰 복원이 모두 롤백되는지도 확인한다.

일반 요청의 `SqlQueryStatementInspector`와 Prometheus 쿼리 수 모니터링은 유지한다.
비교 실험에서만 사용한 `BulkOperationMonitor`와 JDBC batch 횟수·영향 행 계측은
제거했다. JDBC batch 결과의 실패 여부와 반환 개수 검증은 운영 writer에 유지한다.
