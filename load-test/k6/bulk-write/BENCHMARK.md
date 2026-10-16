# 벌크 쓰기 도구

위시리스트·숙소 편의시설의 개별 DELETE 비교는 완료되어 비교 API, fixture API 및 실행기를 제거했다.
운영 서비스의 벌크 삭제와 MySQL 회귀 테스트는 유지한다. 기존 보고서·원본 결과·측정 소스는 보존한다.

현재 실행 가능한 비교는 예약 만료 이력 INSERT다.

- `run-bulk-write-benchmark-server.sh`: 별도 로컬 스키마에서 비교 앱 기동
- `run-reservation-history-insert.sh`: 예약 이력 비교 실행
- `run-reservation-history-insert-observations.sh`: 반복 표본 수집
- `aggregate-bulk-write-observations.mjs`: 저장된 표본의 통계 계산

집계기는 과거 위시리스트·편의시설 결과 형식도 읽는다. 저장된 JSON만 분석하며 삭제된 API를 호출하지 않는다.
공통 토큰·별도 스키마 검증·스케줄러 및 Kafka 격리 설정은 예약 이력 비교에서 계속 사용한다.
