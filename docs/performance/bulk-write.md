# 위시리스트·편의시설 삭제와 예약 이력 쓰기

대상마다 반복하던 쓰기를 묶어 실행하도록 바꿨다. 삭제는 Hibernate를 거치는 벌크 DELETE,
예약 이력 저장은 JDBC batch를 사용한다. 두 경로의 SQL 관측 방식은 다르다.

## 무엇을 바꿨는가

| 대상 | 개선 전 비교 경로 | 현재 구현 |
|---|---|---|
| 위시리스트 연결 숙소 | 연결 엔티티별 DELETE | `deleteAllInBatch()`로 한 번에 삭제 |
| 숙소 편의시설 | 기존 편의시설별 DELETE | 숙소 ID 조건의 JPQL DELETE |
| 만료 예약 이력 | JPA `save()`를 건별 호출 | 제한된 크기의 JDBC batch INSERT |

[위시리스트 삭제](../../src/main/java/kr/kro/airbob/domain/wishlist/service/WishlistService.java)는 존재·소유권과
삭제 대상을 조회한 뒤 연결 행을 지운다. 대상 조회 자체를 제거한 최적화는 아니다.
연결 숙소 N개를 개별 삭제하던 경로와 비교하면 DELETE는 N회에서 1회가 된다.
N=1,000의 `1,000 → 1회`는 DELETE 횟수이며 전체 요청의 SQL 수나 지연 감소율이 아니다.

[편의시설 저장소](../../src/main/java/kr/kro/airbob/domain/accommodation/repository/AccommodationAmenityRepository.java)는
`DELETE ... WHERE accommodation.id = :accommodationId`로 기존 항목을 지운다.
[숙소 수정 서비스](../../src/main/java/kr/kro/airbob/domain/accommodation/service/AccommodationCommandService.java)에서
`amenityInfos=null`은 기존 값 유지, 빈 목록은 전체 삭제, 값이 있으면 삭제 후 재등록한다.

[예약 이력 writer](../../src/main/java/kr/kro/airbob/domain/reservation/repository/ReservationHistoryBatchWriter.java)는
`JdbcTemplate.batchUpdate()`를 사용한다. 예약 상태·소유 재고 해제·쿠폰 복원과 이력 저장은
같은 만료 정리 트랜잭션에서 처리한다. JDBC batch 호출 수와 MySQL이 실행한 SQL 문장 수는 구분한다.
실제 드라이버 전송 방식은 측정 당시의 `rewriteBatchedStatements` 등 JDBC 설정과 함께 해석한다.

## 비교 범위와 측정 방법

- 위시리스트와 편의시설 `DELETE_ONLY`는 삭제 경로를 비교한다.
- 편의시설 `FULL_REPLACEMENT`는 같은 숙소 수정 흐름에서 삭제 방식만 바꿨다. 양쪽에
  부모 잠금·입력 검증·재등록·이력·outbox 기록·커밋을 포함하고 Redis 캐시 I/O는 끈 조건이다.
- 삭제 실행기는 순차 1 VU로 워밍업과 AB/BA 순서를 교차했다. 연산부터 트랜잭션 커밋까지의 시간을
  주 지표로 삼고, fixture 준비·검증·정리를 제외했다. HTTP 시간과 최대 처리량으로 확대하지 않는다.
- 예약 이력 INSERT 비교는 만료 정리 트랜잭션을 대상으로 했다. 이력 쓰기만의 시간과 구분한다.

[삭제 실행 가이드](../../load-test/k6/bulk-write/BENCHMARK.md)와
[INSERT 실행 가이드](../../load-test/MEASUREMENT.md)에 입력·계측·검증 절차를 보존했다.
현재 앱의 비교 API·fixture 생성 코드·전용 프로필은 제거했으며, 운영 구현과 일반 회귀 테스트를 유지한다.

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
