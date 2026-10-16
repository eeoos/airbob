# 벌크 삭제 실험 보관 안내

위시리스트·편의시설 삭제의 Before/After 성능 비교를 마쳐 실행용 API, fixture,
로컬·AWS 실행기와 `bulk-delete-benchmark` 프로필을 제거했다.
현재 애플리케이션에는 운영 벌크 삭제 구현을 유지한다.

- 위시리스트: `WishlistService.deleteWishlist()`의 `deleteAllInBatch()`.
- 편의시설: `AccommodationCommandService.updateAccommodation()`에서
  `deleteByAccommodationIdInBulk()`를 호출한 뒤 새 편의시설을 저장한다.
- `amenityInfos=null`은 기존 편의시설 유지, 빈 목록은 전체 삭제다.
- 삭제 범위, 권한, 롤백, 이력과 캐시 무효화는 일반 MySQL 통합 테스트로 검증한다.

## 과거 소스와 측정 근거

- [삭제 실험 도구가 추가된 커밋](https://github.com/eeoos/airbob/commit/b6d1be8224d05bd6fc516eee837c1949d87bf7f8)
- [정리 직전 실행 안내와 도구](https://github.com/eeoos/airbob/tree/a10b89a1bfdc173f3f327ed4657a1df2db14d2cd/load-test/k6/bulk-write)

기존 측정 결과 JSON과 환경 기록은 이 정리에서 삭제하지 않는다. 결과를 설명하거나
재현할 때는 각 실행에 기록된 앱 커밋, 이미지 digest, DB 버전과 설정을 함께 확인한다.
과거 실행 안내는 해당 커밋의 소스를 대상으로 하며 현재 브랜치에서는 사용할 수 없다.

`FULL_REPLACEMENT`는 숙소 수정 트랜잭션 전체, `DELETE_ONLY`는 편의시설 삭제만
측정했다. 두 결과를 합치지 않고 기존 결과의 측정 범위를 유지한다.

## 유지하는 공통 도구

예약 이력 INSERT 비교는 [공통 실행 안내](../../README.md)를 따른다.
`bulk-write-benchmark` 프로필, `BulkOperationMonitor`, JDBC batch 계측과
예약 이력의 k6 실행·집계 도구는 유지한다. 일반 요청의 `StatementInspector` 및
Prometheus 쿼리 수 모니터링도 유지한다.
