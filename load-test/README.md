# 부하 테스트 방법과 측정 기록

이 디렉터리는 성능 개선을 **어떤 부하와 검증 절차로 측정했는지** 보여주는 스크립트와 실행 가이드를 보관한다.
현재 애플리케이션에는 최종 운영 구현을 유지하며, 비교용 Before API와 실험 전용 프로필은 제거한 상태다.

## 측정별 진입점

| 측정 대상 | 스크립트 | 당시 실행 가이드 |
| --- | --- | --- |
| Redis 상세 캐시 | [비교 요청](k6/cache/accommodation-detail-comparison.js), [로컬 반복](k6/cache/run-local-experiments.py), [AWS 반복](k6/cache/run-aws-experiments.py) | [캐시 측정](k6/cache/MEASUREMENT.md) |
| 위시리스트·편의시설 bulk DELETE | [위시리스트](k6/bulk-write/wishlist-delete-comparison.js), [편의시설](k6/bulk-write/accommodation-amenity-delete-comparison.js), [반복 실행](k6/bulk-write/run-experiments.py) | [벌크 삭제 측정](k6/bulk-write/BENCHMARK.md) |
| 예약 이력 bulk INSERT | [INSERT 비교](k6/bulk-write/reservation-history-insert-comparison.js), [실행기](k6/bulk-write/run-reservation-history-insert-observations.sh) | [벌크 쓰기 실행 절차](MEASUREMENT.md) |
| 쿠폰 발급 | [부하 시나리오](k6/coupon/coupon-issuance-comparison.js), [반복 실행](k6/coupon/run_experiments.py) | [발급 측정](k6/coupon/MEASUREMENT.md), [AWS 측정](k6/coupon/AWS-MEASUREMENT.md) |
| 최근 본 숙소 N+1 | [비교 요청](k6/recently-viewed-nplus1-performance.js), [AB/BA 실행기](k6/run-recently-viewed-comparison.sh) | [N+1 측정](k6/MEASUREMENT.md) |
| 리뷰 요약 반정규화 | [비교 요청](k6/review-summary/comparison.js), [반복 실행](k6/review-summary/run-comparison.mjs) | [리뷰 요약 측정](k6/review-summary/MEASUREMENT.md) |
| 위시리스트·매출 조회 | [위시리스트](k6/read-model/wishlist-comparison.js), [매출](k6/read-model/revenue-stats-comparison.js) | [조회 측정](k6/read-model/MEASUREMENT.md) |
| 실제 실행계획·DB 비용 | [EXPLAIN ANALYZE 수집](mysql/capture-explain-analyze.sh), [조회 분석](mysql/analyze-read-apis-local.py) | [DB 측정](mysql/MEASUREMENT.md) |

`k6/lib/`에는 로그인·fixture·응답 동등성 검증·요약 집계 코드가 있고,
`k6/test/`에는 당시 측정 도구의 계약을 검사한 테스트가 있다.
`http/`와 `fixtures/`에는 요청 예시와 입력 형식을 보관한다.

## 스크립트와 결과를 함께 읽는 방법

1. [측정 결과와 해석](../docs/performance/results/README.md)에서 환경, 앱 커밋, 이미지, 데이터셋, 유효 실행을 확인한다.
2. 해당 스크립트와 설정 파일에서 요청률·동시 사용자 수·워밍업·측정 시간·반복 순서를 확인한다.
3. 응답 검증, 실패율·누락 요청 처리, p95 집계 방식과 결과 파일을 함께 확인한다.

각 스크립트의 기본 설정과 실제 측정에 사용한 설정은 다를 수 있으므로, 발표·문서에 사용할 수치는
결과 보고서의 실행 조건과 원본 결과를 기준으로 설명한다.

## 보관 버전과 재현

원본 복원 기준은 `8b3cc82b923347a0c6d781e3f0f755eb177e8b14`다.
[원본 파일·해시 목록](archive-source.json)에 경로, Git blob, SHA-256을 기록했다.
스크립트·설정·fixture는 그 버전의 내용을 그대로 보존했고, 문서는 보관 안내와 링크를 보완했다.

현재 앱에는 이 스크립트가 호출하는 과거 비교 API와 실험 프로필이 없다.
실측을 재현하려면 각 결과에 기록된 **당시 앱 코드·이미지·데이터셋·실행 설정**을 함께 준비한다.
보관 기준 커밋과 개별 실측의 앱 커밋은 구분한다. 현재 운영 코드 기준의 새 측정은 별도로 구성한다.

- [당시 전체 소스](https://github.com/eeoos/airbob/tree/8b3cc82b923347a0c6d781e3f0f755eb177e8b14/load-test)
- [운영 구현의 벌크 쓰기 정리 기록](../docs/performance/bulk-write-history.md)
- [현재 쿼리 모니터링과 로컬 SQL 추적](../docs/query-count-monitoring.md)
