# 로컬 조회 API 실행계획 분석과 쿠폰 조회 비교

2026년 10월 12일 기존 로컬 MySQL 데이터에서 검색·예약·결제 API를 제외한 **조회 API 19개와 쿼리 형태 44개**를 분석했다. 호스트 숙소 목록의 정렬 인덱스와 정산 상세의 단일 조인·인덱스 순서 집계에서 결과를 보존하는 개선을 확인했다. 쿠폰 목록은 별도 일회성 DB와 실제 앱에서 `COUNT`와 재고 `EXISTS`를 비교했다.

## 추천 순서와 실험 범위

| 순위 | 대상 | 확인한 근거 | 이력서에 사용할 때의 범위 |
|---|---|---|---|
| 1 | 호스트 숙소 목록 | 복합 인덱스의 열 순서로 정렬과 조기 종료 지원, 선택적 상태 필터의 차이 검증 | 로컬 대규모 호스트의 SQL 실행계획 비교 |
| 2 | 호스트 정산 상세 | 두 번 반복한 조인을 한 번으로 줄이고 임시 테이블 집계를 순서 기반 집계로 변경 | 동일한 340개 숙소별 결과, SQL 실행계획 비교 |
| 3 | 쿠폰 캠페인 목록 | 발급 내역 집계를 잔여 재고 존재 확인으로 변경 | 합성 캠페인의 실제 HTTP와 DB 관측, 상태별 결과 구분 |
| 후속 | 관리자 월별 정산 | 약 375만 행 전체 스캔, 한 달 191,621건 반환 | 월 선두 인덱스와 페이지 계약을 함께 검토할 후보 |

호스트·정산 수치는 `EXPLAIN ANALYZE`의 **루트 실행시간**이다. HTTP 지연이나 p95가 아니다. 쿠폰 표의 HTTP p95만 실제 k6 요청에서 측정했다. 현재 V1 조회와 발급 동작은 그대로이며, 쿠폰 후보는 세션·벤치마크 토큰을 요구하는 비교 경로에만 연결했다.

예약 가용일 API는 예약 조회로 보아 제외했다. 정산 API는 포함했으며 그 내부의 예약·결제 원장 조인은 읽기 실행계획만 분석했다. 예약·결제 API 자체와 쓰기 경로는 대상이 아니다.

실제 적용 지점은 다음과 같다. 이번에는 후보 비교까지 수행했으며 영구 전환은 하지 않았다.

- 호스트 목록: [AccommodationRepositoryImpl](../../../../src/main/java/kr/kro/airbob/domain/accommodation/repository/querydsl/AccommodationRepositoryImpl.java)의 조회 조건과 정렬에 맞는 새 Flyway 인덱스 마이그레이션. 기존 SQL은 유지할 수 있다.
- 정산 상세: [SettlementRepository.findLineItems](../../../../src/main/java/kr/kro/airbob/domain/settlement/repository/SettlementRepository.java)의 UNION 집계를 단일 조인·조건부 합계로 교체하는 후보. 월 전체 정산 배치 쿼리와는 별개다.
- 쿠폰 목록: [CouponRepository](../../../../src/main/java/kr/kro/airbob/domain/coupon/repository/CouponRepository.java)의 `findCampaignsWithRemainingUnits`와 별도 가용성 projection. 현재는 보호된 비교 경로에만 연결했다.

## 기존 로컬 데이터와 진단 방법

- 기존 DB: MySQL 8.4.11, InnoDB 버퍼 풀 1 GiB, 기본 REPEATABLE READ.
- 숙소 730,529개, 호스트 370,817명은 호스트별 그룹핑에서 합산한 값이다. 최대 호스트는 숙소 2,591개를 보유한다. 호스트 288,695명은 숙소 1개이며, 1,001개 이상 보유자는 1명이다. 최대 호스트 결과를 일반 사용자의 평균으로 해석하지 않는다.
- 공개 리뷰가 가장 많은 숙소는 225개다. 가장 큰 활성 위시리스트의 저장 숙소는 200개, 보유 쿠폰이 가장 많은 회원은 57개다. 회원당 위시리스트 최댓값은 34개다.
- 매출 통계와 정산 데이터의 마지막 날짜는 각각 2026년 3월 31일과 3월 1일이다. 현재 시각에 조회 가능한 재고 준비 쿠폰은 0개였다.
- 저장소의 Repository·QueryDSL을 등가 SQL로 옮겨 `EXPLAIN FORMAT=JSON`과 `EXPLAIN ANALYZE FORMAT=TREE`를 각각 수집했다. 전체 44개 형태에서 ANALYZE를 3회 실행했다. 이는 전체 API의 실제 JDBC 추적이 아니며, 대상·정렬·커서 분기를 골라 본 진단이다.
- 실행계획용 연결은 읽기 전용이고 SELECT 시간 제한은 15초다. 업무 행은 수정하지 않았다. 최근 본 숙소는 실제 DB의 인기 리뷰 숙소 100개로 입력을 구성했으며 실제 Redis 이용 분포의 표본은 아니다.
- 첫 관측과 반복 관측을 모두 보존했다. 버퍼 풀을 비우거나 콜드 캐시를 강제하지 않았다. const 테이블은 최적화 단계에서 읽을 수 있어 루트의 매우 작은 시간을 SQL 전체 지연으로 사용하지 않는다.

환경·인덱스 정의는 [메타데이터](read-metadata.json), 입력 선정은 [대상](read-targets.json), 전체 SQL은 [쿼리 목록](read-catalogue.json), 원본 계획은 `read-plans.ndjson.gz`에 있다. `TABLE_ROWS`와 cardinality는 통계 추정값이다.

## 호스트 목록의 정렬과 조기 종료

`GET /api/v1/profile/host/accommodations?size=20`은 `member_id`로 필터링하고 `created_at DESC, id DESC`로 정렬해 21개를 읽는다. 기존 인덱스는 호스트 조건만 지원하므로 최대 호스트의 2,591개 숙소를 읽고 정렬한다. 주소 조인은 이미 21회만 수행하므로 이 사례의 핵심은 조인 지연이 아니라 **정렬 전 숙소 탐색량**이다.

다음 후보를 임시 INVISIBLE 인덱스로 만들었다.

```sql
(member_id, created_at DESC, id DESC, status)
```

같은 물리 인덱스와 데이터에서 `use_invisible_indexes=off/on`을 세션별로 바꿨다. 두 경로를 워밍업한 후 순서를 교대해 3회씩 측정하고, 전체 결과와 순서의 해시가 같은지 확인했다.

| 조회 | 기존 ms | 후보 ms |
|---|---:|---:|
| 상태 전체 | 3.040 | 0.178 |
| 공개 | 3.080 | 0.167 |
| 비공개 | 2.800 | 0.215 |
| 전체 목록 1,000번째 이후 커서 | 2.890 | 0.239 |

각 값은 3회 루트 실행시간의 중앙값이다. 후보는 정렬 연산 없이 인덱스 순서로 페이지를 충족하고 멈춘다. 상태는 뒤쪽 인덱스 열에서 ICP로 평가한다. 실행계획의 숙소 접근 연산이 반환하는 행은 21개지만, **ICP 내부에서 검사한 모든 리프 엔트리 수가 21개라는 뜻은 아니다.** 특히 드문 상태일수록 더 많은 인덱스 엔트리를 지나갈 수 있다.

상태를 앞에 둔 후보도 따로 비교했다.

```sql
(member_id, status, created_at DESC, id DESC)
```

| 조회 | 이 비교의 기존 ms | 상태 선두 후보 ms |
|---|---:|---:|
| 상태 전체 | 3.430 | 3.060 |
| 공개 | 3.080 | 0.181 |
| 비공개 | 2.760 | 0.107 |
| 전체 목록 1,000번째 이후 커서 | 2.890 | 2.910 |

상태가 등호로 고정되면 이 인덱스가 유리하다. 그러나 상태 전체 조회의 `status <> 'DELETED'`에서는 여러 상태 구간의 전역 시간순 정렬을 해결하지 못한다. 따라서 기본 목록까지 고려하면 첫 번째 후보가 더 넓게 맞는다. 비공개 필터의 빈도와 선택도가 충분히 크다면 두 번째 인덱스의 추가 가치를 검토할 수 있다.

두 인덱스는 각각 약 33.6 MiB였다. 영구 도입 시 쓰기 비용과 기존 `member_id` 인덱스의 중복 여부까지 평가해야 한다. 실험 후 두 인덱스를 모두 삭제했다.

최초 진단의 비공개 조회는 `index_merge` 교집합으로 호스트 인덱스 2,591행과 상태 인덱스 약 58,440행을 읽고 약 12.2ms를 기록했다. 인덱스 생성 뒤 통계·캐시 상태가 달라진 비교 기준선은 단일 호스트 인덱스를 선택했다. **12.2ms를 위 표의 개선율 기준으로 섞지 않는다.** 위 표는 각각 인덱스를 만든 뒤 수행한 OFF/ON 비교다. 인덱스 제거가 통계와 캐시까지 이전 상태로 돌리는 것은 아니다.

원본: [전체 정렬 후보](idx_readlab_host_order.json), [상태 선두 후보](idx_readlab_host_status_order.json).

## 정산 상세의 조인과 그룹핑

`GET /api/v1/profile/host/settlements/{id}`의 숙소별 내역은 확정과 취소를 `UNION ALL`의 두 분기에서 각각 읽는다. 최대 호스트의 2026년 3월 내역에서는 다음 작업이 반복됐다.

1. 호스트 숙소 2,591개를 찾는다.
2. 예약 인덱스를 2,591회 탐색한다.
3. 결제 원장 인덱스를 5,087회 탐색한다.
4. 같은 경로를 취소 분기에서 한 번 더 수행하고, 결과를 임시 테이블에 적재해 집계한다.

확정·취소 유형은 서로 배타적이므로 한 번의 조인에서 조건부 `SUM`으로 분리했다. `DATE(created_at)`는 같은 UTC 일자 구간을 표현하는 반개구간으로 바꾸고, 그룹 키는 숙소 PK로 두었다. 숙소명은 PK에 함수적으로 종속된다.

```sql
SELECT a.id, a.name,
       SUM(CASE WHEN pt.transaction_type = 'CONFIRM'
                THEN COALESCE(pt.amount, 0) ELSE 0 END) AS gross,
       SUM(CASE WHEN pt.transaction_type IN ('CANCEL', 'PARTIAL_CANCEL')
                THEN COALESCE(pt.cancel_amount, 0) ELSE 0 END) AS refund
FROM accommodation a
JOIN reservation r ON r.accommodation_id = a.id
JOIN payment_transaction pt ON pt.reservation_id = r.id
WHERE a.member_id = :hostId
  AND (확정의 created_at 구간 OR 취소의 COALESCE(canceled_at, created_at) 구간)
GROUP BY a.id
ORDER BY a.id;
```

위는 구조를 보여주는 축약 SQL이며 실행한 전체 SQL은 원본 JSON에 있다. `(member_id)` 보조 인덱스의 PK 순서와 nested loop가 그룹 순서를 유지해 `Group aggregate`로 실행됐다. `UNION` materialization, 임시 테이블 집계, 별도 정렬이 사라졌다. 원장 인덱스 탐색은 5,087회씩 두 번에서 한 번으로 줄었다. 예약의 숙소 FK 인덱스는 필요한 예약 ID까지 이미 커버하므로 별도 커버링 인덱스를 만들지 않았다.

워밍업 뒤 순서를 교대해 5회씩 비교한 중앙값은 **49.0ms → 23.2ms**였다. 340개 숙소의 전체 결과와 순서가 같았다. 조인 단일화·날짜 조건·그룹 키 변경을 합친 효과이며, 그룹핑 최적화만의 단독 효과로 표시하지 않는다. 날짜의 월 경계·취소 시각 NULL·다양한 호스트에 대한 추가 회귀 검증 후 실제 저장소 쿼리에 적용하는 것이 다음 단계다.

원본: [단일 조인 비교](settlement-detail-single-pass.json).

## 쿠폰 목록의 실제 HTTP 비교

현재 로컬 DB의 공개 캠페인이 비어 있어 별도 MySQL 8.4.11·Redis·실제 Spring 앱을 실행했다. 기본 서비스 `findCampaigns()`를 호출하는 COUNT 경로와 재고 존재 여부를 조회하는 EXISTS 경로를 동일한 세션·벤치마크 토큰으로 비교했다. 실제 V1 응답과 두 비교 경로의 바이트 해시도 일치했다.

- 캠페인 4개: 대리키 RR·RC, 복합키 RR·RC 각각 1개, 총수량은 각 10,000장.
- 발급 0장, 5,000장, 9,999장, 10,000장 상태를 순서대로 준비했다. 상태 변경은 측정 밖에서 재고 DELETE와 발급 내역 INSERT를 같은 트랜잭션으로 처리했다. 발급 API의 처리량 실험은 아니다.
- MySQL 버퍼 풀 256 MiB, CPU 제한 2개·메모리 제한 768 MiB. Java 21.0.12.1, 힙 512 MiB, Hikari 10개, RR 기본 풀. 앱·부하 발생기·기존 개발 서비스가 같은 로컬 호스트를 공유한다.
- 각 버전 50 RPS, 워밍업 5초, 본 측정 15초. 상태별 3회, 순서는 COUNT→EXISTS / EXISTS→COUNT / COUNT→EXISTS다.
- 본 측정 **18,014건**, 응답 불일치·HTTP 실패·요청 누락 0건. 각 측정의 실제 요청 수와 해당 목록 SQL의 실행 증가가 일치했다. 경계에서 751건인 구간도 실제 수를 그대로 보존했다.
- 아래 HTTP 값은 **세 회차 p95의 중앙값**이며 합산 p95가 아니다. SQL 값은 회차별 평균 실행시간의 중앙값이다.

| 캠페인당 상태 | COUNT HTTP p95 ms | EXISTS HTTP p95 ms | COUNT SQL ms | EXISTS SQL ms | 목록 SQL당 rows examined |
|---|---:|---:|---:|---:|---:|
| 발급 0장 | 9.58 | 10.14 | 0.237 | 0.570 | 8 → 12 |
| 5,000장 발급 | 8.18 | 7.44 | 1.612 | 0.234 | 20,008 → 12 |
| 9,999장 발급 | 9.00 | 6.92 | 2.892 | 0.123 | 40,004 → 12 |
| 10,000장 발급 및 품절 | 8.79 | 6.89 | 2.847 | 0.124 | 40,008 → 8 |

5,000장 상태의 EXISTS p95는 회차별 16.87 / 7.44 / 7.07ms로 변동이 있었다. 느린 회차도 그대로 포함했다. 짧은 로컬 비교이므로 작은 HTTP 차이를 안정적인 운영 개선율로 해석하지 않는다.

발급량이 쌓일수록 COUNT는 발급 내역을 반복해서 읽는다. EXISTS는 남은 재고를 찾으면 종료할 수 있고, 품절 상태도 재고 인덱스로 확인한다. 발급이 0장이면 COUNT 역시 읽을 발급 내역이 없어 원래 저렴하므로 모든 상태에서 EXISTS가 빠르다는 주장을 하지 않는다. 삭제된 레코드의 MVCC·purge 상태에 따라 존재 확인 비용도 달라질 수 있으며, 고정 시간이나 항상 한 번의 물리 읽기를 보장하지 않는다.

일반 캠페인은 양의 유한 수량과 올바른 unit 준비·소비 불변식을 전제로 한다. 후보는 시작 전 UPCOMING과 기존 노출 조건을 유지하고, 전략별 테이블을 고른다. 비교용 DB 전략은 정상적으로는 준비 조건에서 제외되며, 후보 SQL의 나머지 분기는 기존 COUNT 판정을 유지한다. 발급 수 카운터를 SKIP 경로에 추가하지 않았다.

별도 MySQL 기능 테스트 24개와 API 접근 제어 테스트 6개가 통과했다. 네 전략의 응답 일치, 시작·종료 경계, 비활성·미준비 제외, 마지막 unit의 미커밋 삭제·롤백, 같은 RR 스냅샷에서 동시 커밋을 보는 동작을 검증했다. SQL과 응답 변경 후보는 비교 경로에만 연결되어 있다.

초기 준비 실행의 오래된 Java 시간대 데이터 오류와 general log의 BLOB→문자열 변환 오류를 수정했다. 이후 한 구간에서 서버·클라이언트 쿼리 수가 맞지 않아 실행을 중단했다. 해당 구간은 전후 스냅샷을 보존하지 못해 차이의 원인을 확정하지 않았다. 관측 원본과 두 번의 안정된 카운터 표본을 보존하도록 보강하고, 최종 전체 비교를 새 DB에서 다시 실행했다. 중단 실행과 짧은 계측 진단은 위 표에 합산하지 않았다. `excluded-runs.ndjson.gz`에 남아 있는 원본과 종료 상태를 보존했다.

원본: [요약](coupon-summary.json), [회차별 결과](coupon-api-results.json), [실제 JDBC SQL](coupon-jdbc-sql.json), [환경](coupon-environment.json), [정리 확인](coupon-cleanup.json), `coupon-observations.ndjson.gz`.

## 조회 API별 판정

아래 경로는 공통 `/api` 접두사를 생략했다.

| 조회 API | 현재 계획과 규모 | 판단 |
|---|---|---|
| `/v1/auth/me` | 회원 PK 단건 | 인덱스 적절 |
| `/v1/accommodations/{id}` | 숙소·주소·호스트·정책·요약 PK 조회, 이미지·편의시설은 숙소 FK 범위 | 조인 수만으로 병목이라 볼 근거 없음 |
| `/v1/profile/host/accommodations` | 호스트 FK 필터 뒤 정렬, 최대 2,591개 후보 | 정렬 복합 인덱스 실측 개선 |
| `/v1/profile/host/accommodations/{id}` | 숙소 PK 및 소유자 검증 | 인덱스 적절 |
| `/v1/accommodations/{id}/reviews` | 최대 대상 242행 중 공개 225개를 읽어 정렬, 작성자 조인은 21회 | 정렬별 복합 인덱스 후보, 현재 데이터의 절대 비용은 작음 |
| `/v1/members/wishlists` | 회원 FK 조회 후 정렬, 진단 대상 2개 목록, 전체 최대 보유 목록 34개 | 회원별 규모가 작아 우선순위 낮음 |
| `/v1/members/wishlists/membership` | 이미 EXISTS와 PK·유니크 키 활용 | 조기 종료가 이미 적용됨 |
| `/v1/members/wishlists/accommodations/{id}` | 최대 200개 정렬, 숙소 23회·주소와 요약 21회 조인 | 저장 시각 인덱스 후보지만 불필요한 대량 화면 조인은 관측되지 않음 |
| `/v1/members/recently-viewed` | 최대 100개 PK 범위와 요약 조인 | 조회 범위가 이미 제한됨 |
| `/v1/coupons` | 로컬의 준비 캠페인 0개 | 실제 기존 DB에서 병목 실측 불가, 별도 캠페인 실험으로 검증 |
| `/v1/members/me/coupons` | 회원 복합 키로 최대 57개를 찾고 정렬 | 정렬 인덱스 추가의 우선순위 낮음 |
| `/v1/admin/stats/revenue` | 날짜 PK range와 Group aggregate, 한 달 621,785행→31행 | 그룹핑 인덱스가 이미 적절; 약 195ms는 집계량 문제 |
| `/v1/profile/host/settlements` | `(host_id, settlement_month)` 범위, 1년 12행 | 인덱스 적절 |
| `/v1/profile/host/settlements/summary` | 호스트 인덱스 119행→상태 2개, 작은 임시 집계 | 커버링 그룹 인덱스를 전역 추가할 우선순위 낮음 |
| `/v1/profile/host/settlements/{id}` | 두 분기의 조인·UNION·임시 집계 | 단일 조인과 순서 기반 집계 실측 개선 |
| `/v1/admin/settlements` | 월 선두 인덱스 부재, 약 375만 행 스캔 | 월·정렬 인덱스와 페이징을 함께 설계할 후속 후보 |
| `/v1/common-codes/{group}` | `(group_code,is_active,sort_order)` range, 30개 | 인덱스 적절, 캐시 미스 조회 |
| `/v1/admin/common-codes/{group}` | 그룹 내 30개 정렬 | 작은 정렬이므로 추가 인덱스 불필요 |
| `/v1/admin/common-code-groups` | 그룹 2개 | 테이블 규모가 작아 튜닝 대상 아님 |

리뷰 인덱스 후보는 최신순 `(accommodation_id,status,created_at DESC,id DESC)`, 높은 평점순 `(accommodation_id,status,rating DESC,created_at DESC,id DESC)`, 낮은 평점순 `(accommodation_id,status,rating ASC,created_at DESC,id DESC)`다. 높은 평점 인덱스를 역방향으로 읽으면 날짜 방향도 뒤집히므로 낮은 평점순과 같지 않다. 이 세 인덱스는 이번 실험에서 생성하지 않았으며 개선율을 제시하지 않는다. 사용 비중을 보고 필요한 정렬만 도입해야 한다.

관리자 정산은 월 전체 191,621개, PENDING 38,386개, PAID 153,235개를 반환했다. 상태 없는 조회에는 `(settlement_month,host_id)`, 상태 고정 조회에는 `(settlement_month,status,host_id)`가 후보지만 이번에 생성·실측하지 않았다. 인덱스만으로 큰 JSON 응답의 비용까지 해결할 수는 없으며 페이지 도입은 응답 계약 변경이다.

최근 본 숙소의 찜 판정을 EXISTS로 바꾼 단순 후보는, 선택한 100개 숙소와 저장 목록이 겹치지 않은 입력에서 중복 제거 materialization을 선택해 기존 약 0.048ms보다 느린 약 0.719ms였다. 이것을 모든 입력의 결과로 일반화하지 않으며, **EXISTS라는 문법 자체를 성과로 삼지 않는다.**

## 강의 기법과의 연결

| 기법 | 이번 코드에서 확인한 적용점 |
|---|---|
| 전체 스캔과 복합 인덱스 | 관리자 정산의 월 선두 키 부재, 호스트 목록의 필터와 정렬 열 순서 |
| filesort와 조기 종료 | 호스트 목록에서 실측, 리뷰·위시리스트는 추가 후보 |
| ICP | 호스트의 시간순 인덱스 뒤쪽 상태 필터에서 실제 사용, 조기 종료를 돕는 수단 |
| 커버링 | 예약 FK 인덱스와 위시리스트 소속 조회에 이미 적용; TEXT나 URL까지 큰 인덱스에 넣을 근거 없음 |
| 인덱스 머지 | 최초 비공개 목록의 교집합 과다 탐색 관측; 통계 변화 뒤 계획도 달라짐 |
| 조인 최적화 | 정산 상세의 중복 경로를 한 번으로 줄인 실측 |
| 인덱스 순서 그룹핑 | 정산 상세에서 임시 집계 제거, 매출 날짜별 집계는 이미 적용 |
| 스킵 스캔 MRR 히스토그램 | 이번 계획에서 도입 효과를 입증할 근거 없음; 먼저 명확한 접근 경로 문제를 해결 |

`SUM`·`COUNT`·`AVG`의 모든 대상 행을 읽는 집계를 그룹별 한두 행만 읽는 Loose Index Scan으로 설명하지 않는다. 정산 후보는 순서를 유지하는 Group aggregate이며, 실제 조회의 행 수·조인 횟수·임시 테이블 여부가 설명의 중심이다.

## 재현과 종료 상태

```bash
python3 load-test/mysql/analyze-read-apis-local.py \
  --output build/read-api-analysis-new --passes 3
python3 load-test/mysql/compare-read-api-candidates-local.py \
  --source build/read-api-analysis-new --output build/read-api-analysis-new/candidates
./gradlew bootJar -x test -x generateAdoc -x asciidoctor -x copyDocument
python3 load-test/k6/coupon/run-campaign-read-local.py \
  --output build/k6/coupon-campaign-read-new --java-home /path/to/current/java21 \
  --rounds 3 --rate 50 --seconds 15
```

첫 실행기는 기존 로컬 `mysql` 컨테이너와 그 내부의 비밀번호 파일을 이용해 읽기만 수행한다. 두 번째 실행기는 호스트 후보 인덱스를 하나씩 INVISIBLE로 생성하고 비교 후 삭제한다. 쿠폰 실행기는 별도 MySQL·Redis와 앱을 만들고 종료 시 자신이 만든 자원만 삭제한다. 기존 업무 행과 개발 서비스는 유지했다. 쿠폰 비교 경로는 `test,coupon-benchmark` 실험 프로필에서 사용하며, `coupon-performance`의 별도 경로 허용 목록을 확장하지 않았다.

Java 컴파일과 패키징, 기존 조회 계약 10개·새 MySQL 정합성 14개·접근 제어 6개 테스트가 통과했다. 초기 테스트 컴파일 오류를 수정한 뒤의 성공 실행 기준이며 실패한 컴파일을 테스트 통과 수에 포함하지 않았다. 영구 마이그레이션이나 V1 전환은 수행하지 않았다.

`experiment-sources.ndjson.gz`는 실험 코드와 후보 구현을, `SHA256SUMS`는 결과 파일의 해시를 보존한다. 이번 결과는 로컬 SQL·HTTP 실험이며 운영 최대 처리량, AWS 성능, 전체 트래픽의 평균 개선율로 사용하지 않는다.
