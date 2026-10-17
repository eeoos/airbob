# 리뷰 요약 반정규화 AWS 측정 결과

[로컬 비교와 구현 설명](history/review-summary-local.md) · [N+1과 리뷰 요약의 단계별 비교](recently-viewed.md) · [측정 스크립트](../../load-test/k6/review-summary/MEASUREMENT.md)

원본 계측 파일·설정·검증 기록: [측정 당시 보관본](https://github.com/eeoos/airbob/tree/a10b89a1bfdc173f3f327ed4657a1df2db14d2cd/docs/performance/results/review-summary-aws-2026-10-12).

2026년 10월 12일 기존 AWS 실험 자원에서 실제 화면 조회 API의 원본 리뷰 집계(Before)와 요약 테이블 조회(After)를 비교했다. 위시리스트와 최근 본 숙소는 세 회차 모두 빨라졌다. 상세 조회는 중앙값이 줄었지만 한 회차에서는 더 느렸다.

## 최종 결과

각 값은 **버전별 세 회차 p95의 중앙값**이다. 모든 요청을 합친 p95가 아니다. 단축률은 `(Before 중앙값 − After 중앙값) / Before 중앙값 × 100`으로 계산했다.

| 조회 | 요청률 | Before p95 | After p95 | 단축률 |
|---|---:|---:|---:|---:|
| 최근 본 숙소 100개 | 10 RPS | 104.23ms | 14.09ms | 86.5% |
| 위시리스트 숙소 50개 | 20 RPS | 18.42ms | 11.03ms | 40.1% |
| 숙소 상세 · 캐시 OFF | 20 RPS | 10.26ms | 8.61ms | 16.1% |

**최종 비교 18개 측정 구간의 요청 18,009건이 모두 성공했고 오류·요청 누락은 0건이었다.** 모든 요청에서 사전 검증한 응답 해시와 같은지 확인했다. 최근 본 20 RPS 실패 실행은 이 통계에서 제외하고 아래에 별도로 기록했다.

## 데이터와 환경

- 상세: 숙소 `324693`, 공개 리뷰 225개, 익명 조회. Before/After 모두 V2의 캐시 우회 경로다.
- 위시리스트: 기존 활성 위시리스트 `236192`. 저장 숙소 199개 중 첫 페이지 50개를 반환하며, 그 50개의 공개 리뷰 합계는 1,045개다. 저장 숙소 수가 큰 기존 목록 200개를 조사해 첫 페이지 리뷰 합계가 가장 큰 대상을 골랐다. 전체 데이터에서 리뷰가 가장 많은 위시리스트라고 주장하지 않는다.
- 최근 본: 공개 숙소 중 리뷰가 많은 100개, 리뷰 합계 15,384개. 일반 사용자의 조회 분포를 대표하는 무작위 표본은 아니다.
- 테스트용 Redis 세션을 사전에 준비했다. 측정 요청은 양쪽 모두 기존 세션 필터와 위시리스트 소유자 검증을 거쳤다. 기존 위시리스트와 업무 DB 데이터는 변경하지 않았다. 최근 본 기록은 기존 실험 전용 계정에만 준비했다.
- 서울 리전: 앱 c6i.large 1대, 별도 부하 발생기 c6i.xlarge, RDS db.t3.small · MySQL 8.4.11.
- 앱 컨테이너: CPU 2개·메모리 3 GiB, JVM 힙 1,536 MiB. Hikari 최대/최소 연결 10개, 기본 격리 수준 REPEATABLE_READ. InnoDB 버퍼 풀 512 MiB.
- 같은 digest 고정 이미지와 V30 DB를 사용했다. 상세 캐시·SQL 출력 로그·스케줄러·앱 Kafka 리스너·예약 재고 작업·외부 쓰기 연동을 끄고 매 구간 전후 런타임을 확인했다.
- k6 1.5.0, Node.js 22.23.2. VU 사전 할당/상한은 각각 80이며 고정 동시 사용자 80명 실험이 아니다. 인증서 검증을 유지한 HTTPS로 실험 ALB IP에만 연결했다. 공개 DNS는 변경하지 않았다.
- 버전별 워밍업 30초·본 측정 60초. 순서는 1회 Before→After, 2회 After→Before, 3회 Before→After다. 로그인·사전 검증·워밍업은 결과에서 제외했다.

## 회차별 p95

| 조회 | 회차 | Before ms | After ms | 단축률 |
|---|---:|---:|---:|---:|
| 최근 본 숙소 100개 | 1 | 118.41 | 14.68 | 87.6% |
| 최근 본 숙소 100개 | 2 | 104.23 | 14.09 | 86.5% |
| 최근 본 숙소 100개 | 3 | 100.06 | 13.85 | 86.2% |
| 위시리스트 숙소 50개 | 1 | 19.77 | 11.03 | 44.2% |
| 위시리스트 숙소 50개 | 2 | 17.86 | 11.01 | 38.4% |
| 위시리스트 숙소 50개 | 3 | 18.42 | 11.09 | 39.8% |
| 숙소 상세 · 캐시 OFF | 1 | 11.27 | 8.61 | 23.6% |
| 숙소 상세 · 캐시 OFF | 2 | 9.94 | 12.74 | -28.2% |
| 숙소 상세 · 캐시 OFF | 3 | 10.26 | 7.70 | 25.0% |

상세 2회차는 After가 약 28.2% 느렸다. 따라서 상세의 16.1%를 일관된 개선으로 단정하거나 가장 좋은 회차만 대표값으로 쓰지 않는다.

## 최근 본 20 RPS 실패와 부하 조정

처음에는 세 API 모두 20 RPS로 시작했다. 최근 본 Before 첫 측정에서는 1,201건 중 2건이 5초 요청 타임아웃으로 실패했고, p95는 약 3,437ms였다. 오류 기준 위반으로 실행기가 중단했으며 해당 부하에서 After와의 개선율은 계산하지 않았다.

실패 구간과 겹친 CloudWatch 1분 버킷의 RDS 평균 CPU는 약 91.6%였다. CPU 크레딧은 남아 있었고, 원본 리뷰 집계는 숙소 ID 인덱스를 사용하는 range 접근이었다. 관측 구간에서 InnoDB 물리 읽기와 행 잠금 대기는 증가하지 않았다. 반복 집계의 CPU 비용과 동시 실행 증가가 지연에 영향을 준 것으로 해석했다. 다른 병목 가능성까지 모두 배제한 결과는 아니다.

**최근 본 조회만 10 RPS로 낮춰 Before/After를 모두 새로 3회 비교했다.** 위시리스트·상세의 20 RPS 결과와 최근 본 10 RPS 결과는 서로 다른 조건이며, API 간 지연이나 최대 처리량을 직접 비교하지 않는다. 실패한 20 RPS 결과를 삭제하거나 성공 통계에 섞지 않았다.

## DB 관측 근거

MySQL statement digest를 약 5초 간격으로 수집했다. 각 HTTP 본 측정 안의 첫/마지막 표본 차이를 사용했으므로 워밍업과 측정 경계 일부는 포함되지 않는다. 다음은 Before의 **원본 리뷰 집계 쿼리 한 번**의 비용이며 전체 API SQL 비용은 아니다.

| 조회 | 집계 쿼리당 rows examined | 회차별 평균 SQL 실행 시간 ms |
|---|---:|---|
| 최근 본 숙소 100개 | 16,985 | 68.22 / 68.56 / 69.89 |
| 위시리스트 숙소 50개 | 1,176 | 5.76 / 5.86 / 5.94 |
| 숙소 상세 · 캐시 OFF | 242 | 1.23 / 1.27 / 1.25 |

After 측정 구간에서는 이 원본 리뷰 집계 쿼리의 실행 증가가 0이었다. 요약 테이블 JOIN 등 다른 조회 비용은 남아 있으므로 API 전체 DB 비용이 0이 됐다는 의미는 아니다. 공개 리뷰 수와 rows examined는 서로 다른 지표다.

CloudWatch 파일은 실험 환경 확인용이다. 1분 버킷이 워밍업·Before·After와 겹치므로 이를 전후 전용 CPU 감소율로 사용하지 않는다. 요약 테이블 갱신의 쓰기 비용, 일반 트래픽 분포, 최대 지속 처리량은 이번 실험에서 측정하지 않았다.

## 원본과 종료 상태

- 앱 소스: `43edf992227af2ae628ac0d83815c56722cf34b8`.
- 앱 이미지: `sha256:bd5a786c730a8a86109d359df4928a68c56e7967c32811d566e55e8b681bea4d`.
- 측정 도구 V30 보정 커밋: `82c1ac12`.
- [summary.json](https://github.com/eeoos/airbob/blob/a10b89a1bfdc173f3f327ed4657a1df2db14d2cd/docs/performance/results/review-summary-aws-2026-10-12/summary.json): 최종 중앙값, 18개 정상 측정과 실패 실행.
- [sql-analysis.json](https://github.com/eeoos/airbob/blob/a10b89a1bfdc173f3f327ed4657a1df2db14d2cd/docs/performance/results/review-summary-aws-2026-10-12/sql-analysis.json): 측정 내부 구간의 SQL 증분과 계산값.
- [dataset-manifest.json](https://github.com/eeoos/airbob/blob/a10b89a1bfdc173f3f327ed4657a1df2db14d2cd/docs/performance/results/review-summary-aws-2026-10-12/dataset-manifest.json), [environment.json](https://github.com/eeoos/airbob/blob/a10b89a1bfdc173f3f327ed4657a1df2db14d2cd/docs/performance/results/review-summary-aws-2026-10-12/environment.json): 실제 입력과 환경.
- `raw-results.json.gz`: k6 결과·회차 비교·런타임 검증·실패 기록을 경로별 JSON 객체로 보관.
- `db-observations.ndjson.gz`, `harness-sources.ndjson.gz`: DB 표본과 실행·분석에 사용한 소스. Python `gzip.decompress`로 해제할 수 있다.
- [restore-proof.json](https://github.com/eeoos/airbob/blob/a10b89a1bfdc173f3f327ed4657a1df2db14d2cd/docs/performance/results/review-summary-aws-2026-10-12/restore-proof.json), [fixture-cleanup.json](https://github.com/eeoos/airbob/blob/a10b89a1bfdc173f3f327ed4657a1df2db14d2cd/docs/performance/results/review-summary-aws-2026-10-12/fixture-cleanup.json), [post-run-verification.json](https://github.com/eeoos/airbob/blob/a10b89a1bfdc173f3f327ed4657a1df2db14d2cd/docs/performance/results/review-summary-aws-2026-10-12/post-run-verification.json): 복구와 종료 검증.

측정 종료 후 동일 이미지·동일 환경변수의 `aws,coupon-performance` 앱으로 복구했다. 임시 세션 2개와 이번에 만든 최근 본 기록을 정리했고, 부하 프로세스와 DB 관측기도 종료했다. ALB 대상 healthy와 앱 1대를 확인했으며 AWS 자원은 보존했다.

기존 자원의 만료 태그가 지난 상태여서 사용자가 추가 2시간의 측정을 명시적으로 허용했다. 해당 허용 기한 안에서 별도 측정 잠금을 유지했다. **원래 자원의 만료 태그·불변 manifest·IAM은 변경하지 않았다.** 이번 실행 허용과 인프라의 만료 태그 갱신은 구분한다. 앱 재시작 전 ASG 보호 설정 변경은 IAM에서 거부돼 적용되지 않았고, 기존 건강 검사 정책을 유지한 채 재시작·복구와 최종 정상 상태를 확인했다.
