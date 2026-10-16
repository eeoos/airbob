# Airbob k6 벤치마크

이 디렉터리에는 서로 독립적인 성능 실험이 함께 있다. 먼저 측정 목적을 고른 뒤 해당 진입점과 README만 보면 된다.

| 측정 목적 | 직접 실행할 진입점 | 상세 가이드 |
|---|---|---|
| 위시리스트·매출 반정규화 before/after | `read-model/wishlist-comparison.js`, `read-model/revenue-stats-comparison.js` | [read-model/README.md](read-model/README.md) |
| 숙소 응답의 리뷰 요약 반정규화 (측정 완료) | [결과·도구 보관 안내](review-summary/README.md) | [비교 조건과 측정 기록](../../docs/performance/review-summary-denormalization.md) |
| 쿠폰 DB 조건부 UPDATE/SKIP LOCKED 발급 | `coupon/run_experiments.py`, `coupon/coupon-issuance-comparison.js` | [쿠폰 실험 가이드](coupon/README.md) |

숙소 상세 캐시 비교는 측정을 완료해 전용 실행기를 보관 버전으로 옮겼다.
[결과와 재현 버전](../../docs/performance/aws-cache-experiments.md)을 참고한다.

직접 실행하지 않는 파일도 있다.

- `lib/`: 로그인, fixture 준비, 응답 검증, 결과 요약처럼 여러 진입점이 호출하는 코드
- `test/`: `lib/`의 파싱과 응답 계약을 검증하는 짧은 k6 테스트. 실제 API 부하 테스트가 아니다.

read-model 스크립트는 모두 같은 순서로 동작한다.

1. `options`가 요청률, warm-up, 측정 시간과 실패 기준을 정한다.
2. `setup()`이 로그인하고 before/after 응답이 같은지 한 번 검증한다.
3. `warmup()`이 선택한 variant만 예열한다.
4. `measure()`가 같은 variant에 고정 RPS를 보낸다.
5. `handleSummary()`가 p50/p95/p99와 성공률을 JSON으로 저장한다.

## 측정을 마친 비교 코드

최근 본 숙소 N+1, 숙소 응답의 리뷰 요약 반정규화, 위시리스트·편의시설 개별 DELETE 비교용
API·fixture·실행 스크립트는 제거했다. 실제 서비스의 DTO 조회·요약 JOIN·벌크 삭제는 유지한다.
현재 앱에서는 과거 before/after 스크립트를 실행할 수 없다.

- [리뷰 요약 AWS 측정 결과](../../docs/performance/results/review-summary-aws-2026-10-12/README.md)
- [리뷰 요약 구현과 과거 측정 기록](../../docs/performance/review-summary-denormalization.md)
- [API 쿼리 모니터링](../../docs/query-count-monitoring.md)
- [예약 이력 벤치마크와 과거 삭제 결과 분석](bulk-write/BENCHMARK.md)

재현할 때는 측정 결과에 기록한 커밋·이미지·데이터를 함께 사용한다. 보관한 결과와 체크섬은
현재 코드에 맞춰 다시 계산하거나 변경하지 않는다. 향후 회귀 검증은 운영 API와 통합 테스트를 사용한다.
