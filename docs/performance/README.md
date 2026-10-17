# 성능 개선과 측정 근거

아래 수치는 서로 다른 조건의 실험이다. 요청 분포·데이터·집계 방식이 다른 결과를 합쳐 하나의 개선율로 만들지 않는다.

## 대표 결과

| 주제 | 확인한 변화 | 범위 |
|---|---|---|
| [숙소 상세 캐시](cache.md) | 반복 통과한 부하 1,100 → 5,000 RPS | 앱 1대·숙소 20개·300초씩 3회 |
| [쿠폰 발급](coupon.md) | 조건부 UPDATE 125 RPS, 대리 키 RR 500 RPS 통과 | 앱 1대·120초씩 3회, 최대 처리량 4배라는 의미는 아님 |
| [최근 본 숙소 N+1](recently-viewed.md) | 주소 JOIN: SELECT 103 → 3회, p95 83.82 → 21.06ms | 숙소 100개·원본 리뷰 집계 유지·20 RPS, 3회 본 표본 통합 p95 |
| [벌크 쓰기](bulk-write.md) | 개별 삭제를 벌크 삭제로, 예약 이력은 JDBC batch로 저장 | 삭제 SQL·트랜잭션 범위·JDBC 계측을 구분 |
| [리뷰 요약](review-summary.md) | 위시리스트 p95 18.42 → 11.03ms | 숙소 50개·20 RPS, 회차 p95 3개의 중앙값 |
| [ALB·ASG 확장](asg.md) | 같은 피크 구간 p95 803 → 7ms | 6,000 RPS·15분, 각 구성 1회; 완료 RPS·누락도 함께 확인 |

현재 구현·회귀 검증과 과거 성능 측정은 구분한다. [측정 방법](../../load-test/README.md)의 스크립트는
보존용이며, 재현에는 각 결과에 기록된 앱·이미지·데이터셋·설정이 필요하다.

## 실험 이력과 추가 분석

| 기록 | 읽는 이유 |
|---|---|
| [쿠폰 로컬 검증 요약](history/coupon-local-results.md) | 후보 선택과 당시 비교 API·트랜잭션 계약 |
| [초기 쿠폰 비교 · 10월 9일](history/coupon-skip-locked-2026-10-09.md) | 초기 실행과 SQL 계측 근거 |
| [쿠폰 풀 크기 비교](history/coupon-pool-sizing-2026-10-10.md) | 풀 10·20·30의 조건과 한계 |
| [쿠폰 API·native query 비교](history/coupon-api-native-2026-10-10.md) | API 분리 후 비교와 바이너리 식별 |
| [쿠폰 급증·품절·중복 시나리오](history/coupon-scenarios-2026-10-10.md) | 지속 부하 외의 경합·정합성 검증 |
| [쿠폰 풀 격리 수준 일치](history/coupon-matched-isolation-2026-10-10.md) | 후보별 RR/RC 기본 풀을 맞춘 반복 검증 |
| [쿠폰 AWS 실행 조건](history/coupon-aws-method-2026-10-11.md) | 워밍업 변경·판정 기준·제외 실행 |
| [리뷰 요약 로컬 비교](history/review-summary-local.md) | AWS와 구분한 로컬 데이터·회차 결과 |
| [조회 API 실행계획 분석](history/read-api-local-2026-10-12.md) | 조기 종료·집계·쿠폰 조회의 DB 비용 |
| [최근 본 숙소 AWS 근거 발췌](history/recently-viewed-evidence.json) | 주소 JOIN과 리뷰 요약 단계를 구분한 원본 수치·해시 |

## 원본 보존과 재현

결과 문서의 **측정 당시 보관본**은 원본 계측·실행 설정·소스 스냅샷을 가리킨다.
과거 Git 경로는 당시 커밋의 구조이므로 현재 문서 경로로 바꾸지 않는다. 예를 들어 다음 명령은
아래 커밋에 보존된 결과를 현재 프로젝트 밖의 별도 디렉터리로 꺼낸다.

```bash
mkdir -p /tmp/airbob-measurement-evidence
git archive a10b89a1bfdc173f3f327ed4657a1df2db14d2cd docs/performance/results \
  | tar -x -C /tmp/airbob-measurement-evidence
```

캐시와 최근 본 숙소의 별도 보관본 위치·해시는 각각 [캐시 결과](cache.md#보존-버전과-원본),
[최근 본 숙소 결과](recently-viewed.md#원본과-재현)에 기록했다.

[전체 문서 목차](../README.md)
