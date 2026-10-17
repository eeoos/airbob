# Airbob 문서

성능 개선은 아래 6개 문서부터 읽는다. 각 문서는 문제, 변경 내용, 측정 조건과 해석 범위를 담고 있다.
현재 앱 실행·장애 대응은 운영 안내를 사용한다. 측정 당시 앱·프로필은 현재 운영 설정과 구분한다.

## 성능 개선 사례

| 주제 | 읽을 내용 |
|---|---|
| [숙소 상세 캐시](performance/cache.md) | 캐시 도입, 동시 미스, Redis 장애 시 DB 부하 |
| [쿠폰 발급](performance/coupon.md) | 공유 카운터 경합과 SKIP LOCKED 재고, 대리 키 RR 선택 |
| [최근 본 숙소 N+1](performance/recently-viewed.md) | 주소 JOIN과 리뷰 요약 적용을 분리한 조회 비교 |
| [벌크 쓰기](performance/bulk-write.md) | 위시리스트·편의시설 삭제와 예약 이력 JDBC batch |
| [리뷰 요약](performance/review-summary.md) | 원본 리뷰 집계와 요약 테이블 조회의 AWS 비교 |
| [ALB·ASG 확장](performance/asg.md) | 같은 부하 단계의 지연·완료 처리량·확장 및 축소 |

[결과와 실험 이력 전체 목차](performance/README.md) · [부하 테스트 스크립트와 실행 가이드](../load-test/README.md)

## 운영과 개발 안내

| 목적 | 문서 |
|---|---|
| 로컬 SQL 확인·쿼리 수 관측 | [SQL 추적과 모니터링](operations/query-count-monitoring.md) |
| 예약 재고와 DB 전환 | [재고 전환](operations/reservation-inventory-cutover.md) |
| 결제 실패·복구·수동 해결 | [결제 운영](operations/payment-operation-runbook.md) |
| 쿠폰 발급·재고·스키마 전환 | [쿠폰 운영](operations/coupon-issuance-operations.md), [UTC 시간 계약](operations/coupon-time-contract.md) |
| 변경 전달·운영자 알림 | [Outbox·Debezium](operations/outbox-operations.md), [알림](operations/operator-alert-operations.md) |
| 검색 갱신·전체 재색인 | [색인 운영](operations/accommodation-indexing-operations.md), [Logstash 재색인](operations/logstash-reindex.md) |
| 운영 배포 | [OCI 배포](operations/oci-deployment-runbook.md), [실행 도구 안내](../scripts/README.md) |
| AWS 자원 기동·정지 | [빠른 실행 안내](operations/aws-lab-quickstart.md), [고정 이미지·리소스 계약](operations/aws-performance-lab.md) |
| B 데이터셋 복원·검증 | [복원 도구](operations/global-b-operations.md), [Mac → RDS 적재](operations/global-b-mac-import.md), [기록된 검증 결과](operations/global-b-verification.md) |

## 구성도와 근거 자료

- 서비스 구성도·AWS 배치안·ERD는 [메인 README](../README.md#시스템-아키텍처)에서 확인한다.
- [데이터 규모와 근거 해시](operations/global-b-evidence-summary.json)는 기록된 데이터셋 검증 결과다.
- 중간 실험은 [성능 목차의 실험 이력](performance/README.md#실험-이력과-추가-분석)에 보존한다.
  최종 선택에 쓰지 않은 후보, 무효 실행과 측정 한계도 함께 남긴다.
