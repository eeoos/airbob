# 최근 본 숙소 N+1 개선

최근 본 숙소 목록에서 주소를 지연 로딩하면 숙소 수만큼 SELECT가 늘어난다.
숙소와 주소를 DTO projection으로 함께 조회해 이 반복 조회를 제거했다.
현재 운영 API는 리뷰 요약까지 JOIN하므로, 유효한 숙소 1~100개에서 숙소·주소·리뷰 요약 1회와 찜 조회 1회로 응답한다.

## 주소 N+1만 분리한 AWS 비교

2026년 10월 12일 `20261012-raw-review` 실행은 양쪽에서 **같은 원본 리뷰 집계**를 유지했다.
숙소 100개·주소 100개·공개 리뷰 합계 671개를 고정하고 20 RPS로 측정했다.
각 버전은 60초 워밍업 뒤 180초씩 3회 측정했고, 아래 p95는 워밍업을 제외한 3회 요청 표본을 합쳐 다시 계산했다.

| 구현 | SELECT/요청 | p95 | 본 측정 요청 |
|---|---:|---:|---:|
| 주소 지연 로딩 + 원본 리뷰 집계 | 103 | 83.82ms | 10,803 |
| 숙소·주소 DTO JOIN + 원본 리뷰 집계 | 3 | 21.06ms | 10,801 |

Before는 숙소 1 + 주소 100 + 원본 리뷰 집계 1 + 찜 1이다.
After는 숙소·주소 1 + 원본 리뷰 집계 1 + 찜 1이다. 오류·미시작 요청은 양쪽 모두 0이었다.
SELECT 횟수는 같은 fixture의 검증·워밍업·본 요청을 포함한 해당 경로의 서버 지표 증분으로 확인했다.
p95는 본 측정 표본만 사용하므로 두 지표의 집계 구간은 구분한다.

앱 c6i.large 1대(CPU 2개·메모리 3GiB·힙 1,536MiB), Hikari 풀 10개,
RDS db.t3.small(MySQL 8.4.11·버퍼 풀 512MiB), 별도 c6i.xlarge 부하 발생기를 사용했다.
주소 N+1을 재현하기 위해 측정 앱의 batch fetch를 0으로 설정했고 SQL 출력은 껐다.
고정 데이터·고정 요청률의 읽기 비교이며 최대 처리량이나 모든 사용자 분포에 대한 결과는 아니다.

## 리뷰 요약까지 적용한 단계별 비교

별도 `20261012-three-stages` 실행에서는 공개 리뷰 15,384개를 가진 숙소 100개를 대상으로
10 RPS·워밍업 60초·본 측정 180초·3회 순서 교차 비교를 수행했다. 각 단계의 본 표본을 합친 p95다.

| 단계 | SELECT/요청 | p95 |
|---|---:|---:|
| 주소 지연 로딩 + 원본 리뷰 집계 | 103 | 203.76ms |
| 숙소·주소 DTO JOIN + 원본 리뷰 집계 | 3 | 99.90ms |
| 숙소·주소·리뷰 요약 DTO JOIN | 2 | 16.51ms |

주소 JOIN의 효과는 첫째→둘째 단계, 리뷰 요약의 효과는 둘째→셋째 단계로 구분한다.
이 데이터의 p95와 앞선 671개 리뷰·20 RPS 실험의 p95를 섞지 않는다.
위시리스트·상세까지 포함한 별도 결과는 [리뷰 요약 AWS 비교](review-summary.md)에 있다.

## 현재 구현과 회귀 검증

- [RecentlyViewedService](../../src/main/java/kr/kro/airbob/domain/recentlyViewed/service/RecentlyViewedService.java)는 Redis의 최근 순서를 유지하면서 필요한 필드를 한 번에 조회한다.
  공개되지 않거나 삭제된 숙소의 기록을 정리하고, 찜 여부는 해당 회원 기준으로 한 번에 조회한다.
- [실제 MySQL·Redis 통합 테스트](../../src/test/java/kr/kro/airbob/domain/recentlyViewed/service/RecentlyViewedQueryIntegrationTest.java)는 유효 숙소 1·20·50·100개에서 SELECT 2회와 응답·정렬·소유권 계약을 검증한다.
  빈 기록이나 유효 숙소가 없는 요청까지 항상 2회를 실행한다는 뜻은 아니다.
- [쿼리 수 모니터링](../operations/query-count-monitoring.md)은 로그 출력과 독립적으로 동작한다.
  정확한 횟수는 요청별 SQL 또는 Prometheus의 `_sum / _count` 증가량으로 확인한다.

## 원본과 재현

[공개 근거 발췌](history/recently-viewed-evidence.json)에 두 실행의 조건·회차·지표·앱 소스 해시와
원본 보고서·압축본 SHA-256을 기록했다. 원본은 프로젝트 밖의 `airbob-artifacts/nplus1/` 아래
`20261012-raw-review/`, `20261012-three-stages/`에 보존돼 있다.

측정 이미지는 기준 커밋에 패치를 적용해 만들었으므로 커밋 하나만으로 같은 바이너리라고 표현하지 않는다.
원본의 source patch·JAR 해시·이미지와 V30 데이터셋을 함께 사용한다.

[보관된 k6 가이드](../../load-test/k6/MEASUREMENT.md#recently-viewed-n1-beforeafter-comparison)의 초기 통합 비교는
주소 N+1 제거와 리뷰 요약 JOIN을 함께 적용한 `N+3 → 2` 조건이다. 주소만 분리한 위 비교의 `103 → 3`과 구분한다.
현재 앱에는 비교용 V2 API와 실험 프로필이 없다.
