# 리뷰 요약 반정규화 비교

리뷰 요약 단독 API 대신 화면에서 사용하는 전체 응답으로 비교한다.
Before는 `review`의 `PUBLISHED` 리뷰를 숙소별 `COUNT`, `ROUND(AVG(rating), 2)`로 집계하고,
After는 `accommodation_review_summary`를 조회한다. 요약 테이블은 삭제하지 않는다.

k6 실행은 [리뷰 요약 전후 측정 가이드](../../load-test/k6/review-summary/README.md)를 참고한다.
`review-summary/run-comparison.mjs`가 세 API 중 하나를 선택해 전후 순서를 바꿔 반복하고,
로그인·응답 검증·워밍업을 제외한 측정 결과와 회차별 개선율을 저장한다.

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

## 최근 본 숙소

| 구분 | GET 경로 |
| --- | --- |
| Before | `/api/v2/members/recently-viewed/review-summary-before` |
| After | `/api/v1/members/recently-viewed` |

`read-model-benchmark` 프로필과 `X-Benchmark-Token`, 같은 회원의 로그인 세션이 필요하다.
기존 `/api/v2/members/recently-viewed`는 주소 N+1 비교용이므로 이번 Before로 사용하지 않는다.

Redis에서 읽은 ID 중 공개 숙소의 리뷰만 한 번에 집계한다. 비어 있지 않은 유효 목록은
Before 3회/After 2회 SELECT이며, 주소 조회·찜 여부·최근 순서·비공개 기록 정리를 공유한다.

리뷰가 없는 경우에는 요약 행 유무에 따라 `null` 또는 0이 섞이던 응답을 **0건·0점**으로 통일했다.
운영 API와 기존 N+1 비교 API에도 같은 응답 규칙을 적용한다.

최근 본 기록은 측정 전에 준비한다. `read-model-benchmark`에서는 업무 쓰기를 차단하므로,
일반 개발 프로필에서 해당 회원으로 숙소 열람 기록을 쌓은 뒤 비교 프로필로 전환한다.
비공개·삭제된 숙소 기록은 GET 중 정리되므로, 양쪽 검증과 워밍업을 끝낸 뒤 안정된 목록으로 측정한다.
일반 Redis의 최근 본 기록과 세션은 비우지 않는다.

## 측정 조건

- 같은 DB 데이터와 인덱스, 같은 숙소·회원·요청률을 사용한다.
- 먼저 리뷰 수·평점뿐 아니라 전체 응답과 목록 순서가 같은지 확인한다.
- 리뷰 없음·적음·많음으로 나누고, 워밍업 후 Before/After 순서를 바꿔 반복한다.
- API p95/p99와 DB 실행 시간·읽은 행 수를 함께 기록한다. 쿼리 개수만으로 성능을 판단하지 않는다.
- 로그인·데이터 준비·응답 검증 시간은 측정에서 제외한다.
- 결과는 읽기 성능 비교다. 리뷰 생성·수정·삭제 때 요약을 유지하는 쓰기 비용은 포함하지 않는다.

기존 `review-summary-comparison.js`는 삭제된 API를 호출하지 않고 종료한다.
과거 AWS `REVIEW_SUMMARY_V1` manifest·SQL 증거를 새 API 결과에 그대로 재사용하면 안 된다.
