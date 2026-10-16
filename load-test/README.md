# 성능 측정 도구 정리

성능 측정은 종료했으며 비교 API, 실험 전용 프로필과 부하 발생·실행·집계 도구를 제거했다.
새로운 실험은 그 시점의 운영 코드와 데이터 계약을 기준으로 별도로 구성한다.

- [측정 결과와 해석](../docs/performance/results/README.md)
- [벌크 쓰기 정리 기록](../docs/performance/bulk-write-history.md)
- [정리 직전 측정 도구](https://github.com/eeoos/airbob/tree/d21e3fe1e2a4cbfdcdacb9e54afb5ee8e235c83c/load-test)
- [운영 쿼리 모니터링과 로컬 SQL 추적](../docs/query-count-monitoring.md)
- [보존한 AWS 리소스 관리 코드](../infra/aws/bundles/README.md)

운영 After 구현과 일반 회귀 테스트는 유지한다. 과거 결과를 재현할 때는 해당 결과에
기록된 앱 커밋·이미지·데이터셋을 함께 사용한다.
