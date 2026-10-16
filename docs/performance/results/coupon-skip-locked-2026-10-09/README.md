# 로컬 쿠폰 실험 근거

원본 계측 파일·설정·검증 기록: [측정 당시 보관본](https://github.com/eeoos/airbob/tree/a10b89a1bfdc173f3f327ed4657a1df2db14d2cd/docs/performance/results/coupon-skip-locked-2026-10-09).

[보고서](../../coupon-skip-locked-local-results.md)의 근거다. AWS 결과가 아니다.

| 파일 | 범위 |
|---|---|
| `five-variants.json` | 다섯 방식 × 두 시나리오 × 5라운드, 500 RPS·20초·500 VU |
| `three-variants.json` | 선행 세 방식 × 두 시나리오 × 6라운드, 500 RPS·20초·500 VU |
| `key-precomparison.json` | 대리 키 RR·복합 키 RR × 4라운드, 200 RPS·20초·200 VU |
| `smoke.json` | 20 RPS·10초 smoke, 호스트 표본 누락으로 성능 비교에서는 전부 무효 |
| `lock-observation.json` | 별도 MySQL SQL 트랜잭션의 실행 계획·잠금 단계·빈 범위 경계 관찰 |
| `environment.json` | 실제 로컬 호스트·JVM·컨테이너 이미지 digest |
| `validation.json` | 테스트 실행 수와 로컬 로그 해시 |
| `full-regression.json` | 전체 테스트 최초 실행 및 수정 후 영향 클래스 재실행 결과 |

측정 JSON은 모든 본 측정과 워밍업을 포함하며 원본 `result.json`의 SHA-256과 저장소 기준 상대 경로를 기록한다.
HTTP 응답 통계, SQL 수, 재고 상태, 잠금·Hikari·GC·호스트·컨테이너 관측과 실패 사유를 보존한 요약 자료다.
앱 로그·이메일·인증 세션·비밀번호·토큰 파일은 포함하지 않는다.
원본 파일은 각 metadata의 `rawDirectory` 아래에 있으며 Git 추적 대상인 이 요약과 구분한다.

무효 회차를 제거해 중앙값이나 개선율을 계산하지 않았다. 그룹의 모든 회차가 유효할 때만 aggregate를 제공하고,
유효하지만 성능 기준을 넘은 회차도 포함한다. 다른 실행 묶음의 표본은 합치지 않는다.
전체 회귀 테스트와 영향 범위 재실행은 별개 실행이며 단일 전체 green run으로 해석하지 않는다.
