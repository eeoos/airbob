# 쿠폰 발급 비교: 후보별 풀 기본 격리 수준 일치

원본 계측 파일·설정·검증 기록: [측정 당시 보관본](https://github.com/eeoos/airbob/tree/a10b89a1bfdc173f3f327ed4657a1df2db14d2cd/docs/performance/results/coupon-matched-isolation-2026-10-10).

실행 상태: `complete`. 본 측정 200회, 유효 168회, 전체 기준 통과 155회.

앱 1개·풀 10·동일 V30 JAR의 로컬 실험이다. RR 후보의 풀 기본값은 RR, RC 후보는 RC다. 각 후보마다 앱을 재시작하고 실제 연결 10개를 확인했다. 품절 재확인과 관리자 준비의 명시적 RR 트랜잭션은 유지했다.

200회 측정과 임시 자원 정리는 모두 끝났지만 상위 실행기와 후처리 프로세스가 사라져 자동 집계·개발 컨테이너 복구가 진행되지 않았다. 종료 원인은 확인되지 않았다. 원본 완료 결과·정리 기록·JAR 해시를 검증해 상태를 복원하고 기존 컨테이너 11개를 다시 시작했다. 5회차 프로세스 종료 코드는 미관측으로 남겼고, 복구 전 상태와 복구 기록을 원본 묶음에 보존했다.

JAR SHA-256: `612f6e5c694e9babcda302c3794064688a31df824fdc20f99bbe5f65adc5e10e`.

| 시나리오 | 방식 | 측정 | 유효 | 전체 통과 |
|---|---|---:|---:|---:|
| active-other-preparation | db | 5 | 3 | 3 |
| active-other-preparation | skip_composite_rc | 5 | 4 | 4 |
| active-other-preparation | skip_composite_rr | 5 | 5 | 5 |
| active-other-preparation | skip_surrogate_rc | 5 | 5 | 5 |
| active-other-preparation | skip_surrogate_rr | 5 | 5 | 5 |
| duplicate-bursts | db | 5 | 5 | 5 |
| duplicate-bursts | skip_composite_rc | 5 | 5 | 5 |
| duplicate-bursts | skip_composite_rr | 5 | 5 | 5 |
| duplicate-bursts | skip_surrogate_rc | 5 | 5 | 5 |
| duplicate-bursts | skip_surrogate_rr | 5 | 4 | 4 |
| last-unit | db | 5 | 5 | 5 |
| last-unit | skip_composite_rc | 5 | 5 | 5 |
| last-unit | skip_composite_rr | 5 | 5 | 5 |
| last-unit | skip_surrogate_rc | 5 | 5 | 5 |
| last-unit | skip_surrogate_rr | 5 | 5 | 5 |
| soldout-other-preparation | db | 5 | 5 | 5 |
| soldout-other-preparation | skip_composite_rc | 5 | 5 | 5 |
| soldout-other-preparation | skip_composite_rr | 5 | 5 | 5 |
| soldout-other-preparation | skip_surrogate_rc | 5 | 5 | 5 |
| soldout-other-preparation | skip_surrogate_rr | 5 | 5 | 5 |
| spike-recovery | db | 5 | 0 | 0 |
| spike-recovery | skip_composite_rc | 5 | 2 | 0 |
| spike-recovery | skip_composite_rr | 5 | 1 | 0 |
| spike-recovery | skip_surrogate_rc | 5 | 3 | 1 |
| spike-recovery | skip_surrogate_rr | 5 | 3 | 2 |
| stock-1000 | db | 5 | 5 | 5 |
| stock-1000 | skip_composite_rc | 5 | 5 | 5 |
| stock-1000 | skip_composite_rr | 5 | 5 | 5 |
| stock-1000 | skip_surrogate_rc | 5 | 5 | 4 |
| stock-1000 | skip_surrogate_rr | 5 | 5 | 5 |
| sustain-400 | db | 5 | 4 | 0 |
| sustain-400 | skip_composite_rc | 5 | 5 | 5 |
| sustain-400 | skip_composite_rr | 5 | 4 | 4 |
| sustain-400 | skip_surrogate_rc | 5 | 5 | 4 |
| sustain-400 | skip_surrogate_rr | 5 | 5 | 5 |
| sustain-650 | db | 5 | 0 | 0 |
| sustain-650 | skip_composite_rc | 5 | 4 | 4 |
| sustain-650 | skip_composite_rr | 5 | 5 | 4 |
| sustain-650 | skip_surrogate_rc | 5 | 3 | 3 |
| sustain-650 | skip_surrogate_rr | 5 | 3 | 3 |

실제 요청 2,756,721건, 예정 요청 2,812,500건. 관측된 교착 상태 0회.

호스트 압력·관측 누락·부하 누락으로 무효인 실행은 통과로 바꾸거나 제외해서 재집계하지 않았다. 유효하지만 지연/처리율 기준에 실패한 실행도 별도로 보존했다.

5회의 유효한 동일 시나리오 비교가 모두 있고, p95가 20% 및 5ms 이상 개선된 쌍이 4회 이상이며 p99 중앙값과 SLO 통과 수가 악화되지 않은 경우만 아래에 표시한다. 각 시나리오의 조건부 결과이며 단일 운영 후보를 자동 확정하는 표는 아니다.

| 시나리오 | 지표 | 기준 | 비교 후보 | 개선 쌍 |
|---|---|---|---|---:|
| 사전 기준을 충족한 비교 없음 | — | — | — | — |

요청 누락이나 호스트 포화를 DB 처리량의 상한으로 해석하지 않는다. 첫 400 RPS와 이후 650 RPS는 실행 시점과 예열 상태가 다르므로 둘만으로 부하 곡선을 만들지 않는다. 이력 누적과 공유 호스트의 영향이 남으며, 60초 결과를 장시간 최대 처리량으로 표현하지 않는다.

이전 공용 RR 풀에서의 중단된 측정 20회와 소규모 설정 검증은 별도 원본으로 보존했고 위 비교에는 합산하지 않았다. Hibernate SQL 수와 드라이버의 격리 수준 변경 명령 수도 별도다.

정합성·집계·설정·복구 검증 상태: `passed`.
[전체 집계](https://github.com/eeoos/airbob/blob/a10b89a1bfdc173f3f327ed4657a1df2db14d2cd/docs/performance/results/coupon-matched-isolation-2026-10-10/comparison.json), [검증 결과](https://github.com/eeoos/airbob/blob/a10b89a1bfdc173f3f327ed4657a1df2db14d2cd/docs/performance/results/coupon-matched-isolation-2026-10-10/validation.json), [원본](https://github.com/eeoos/airbob/blob/a10b89a1bfdc173f3f327ed4657a1df2db14d2cd/docs/performance/results/coupon-matched-isolation-2026-10-10/raw.ndjson.gz), [실행 소스](https://github.com/eeoos/airbob/blob/a10b89a1bfdc173f3f327ed4657a1df2db14d2cd/docs/performance/results/coupon-matched-isolation-2026-10-10/harness-sources.ndjson.gz).
