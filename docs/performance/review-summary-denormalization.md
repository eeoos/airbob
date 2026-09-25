# 리뷰 요약 반정규화 비교

리뷰 요약 단독 API 대신 화면에서 사용하는 전체 응답으로 비교한다.
Before는 `review`의 `PUBLISHED` 리뷰를 숙소별 `COUNT`, `ROUND(AVG(rating), 2)`로 집계하고,
After는 `accommodation_review_summary`를 조회한다. 요약 테이블은 삭제하지 않는다.

## 숙소 상세

| 구분 | GET 경로 |
| --- | --- |
| Before | `/api/v2/accommodations/{id}/review-summary-before` |
| After | `/api/v2/accommodations/{id}` |

두 경로 모두 상세 캐시를 사용하지 않는다. 기존 V2 경로의 `Before`라는 이름은 **캐시 적용 전**을 뜻한다.
리뷰 반정규화 실험에서는 이 경로가 After다. V1 상세 API와 비교하면 캐시 효과가 섞인다.

`dev,read-model-benchmark` 또는 `dev,cache-benchmark` 프로필에서 실행하고
`BENCHMARK_READ_MODEL_TOKEN`을 설정한다. 요청에는 `X-Benchmark-Token`을 전달한다.
로그인 여부와 회원을 양쪽에서 같게 유지한다.

Before는 상세·이미지·편의시설 조회에 원본 집계 1회를 더한다. After는 상세 쿼리에서 요약을 JOIN한다.
익명 요청은 각각 4회/3회, 로그인 요청은 찜 확인이 추가되어 5회/4회 SELECT를 실행한다.
이는 원본 배치 집계와 사전 집계 JOIN 방식의 전체 API 비용 비교다.

## 위시리스트 안의 숙소 목록

| 구분 | GET 경로 |
| --- | --- |
| Before | `/api/v2/members/wishlists/accommodations/{wishlistId}/review-summary-before?size=20` |
| After | `/api/v1/members/wishlists/accommodations/{wishlistId}?size=20` |

`read-model-benchmark` 프로필과 `X-Benchmark-Token`, 해당 위시리스트 소유자의 로그인 세션이 필요하다.
`size`와 `cursor`를 양쪽에서 같게 전달한다. 위시리스트 목록 자체의 개수·대표 이미지 비교와는 별개다.

숙소 페이지를 먼저 조회하고 그 페이지의 숙소 ID에 대해서만 원본 리뷰를 묶어서 집계한다.
목록이 늘어도 숙소마다 추가 쿼리를 실행하지 않는다. 비어 있지 않은 페이지는 Before 3회,
After 2회 SELECT이며, 빈 페이지는 양쪽 모두 2회다. 권한·공개 상태·메모·커서 순서는 동일하다.

## 측정 조건

- 같은 DB 데이터와 인덱스, 같은 숙소·회원·요청률을 사용한다.
- 먼저 리뷰 수·평점뿐 아니라 전체 응답과 목록 순서가 같은지 확인한다.
- 리뷰 없음·적음·많음으로 나누고, 워밍업 후 Before/After 순서를 바꿔 반복한다.
- API p95/p99와 DB 실행 시간·읽은 행 수를 함께 기록한다. 쿼리 개수만으로 성능을 판단하지 않는다.
- 로그인·데이터 준비·응답 검증 시간은 측정에서 제외한다.
- 결과는 읽기 성능 비교다. 리뷰 생성·수정·삭제 때 요약을 유지하는 쓰기 비용은 포함하지 않는다.

기존 `review-summary-comparison.js`는 삭제된 API를 호출하지 않고 종료한다.
과거 AWS `REVIEW_SUMMARY_V1` manifest·SQL 증거를 새 API 결과에 그대로 재사용하면 안 된다.
