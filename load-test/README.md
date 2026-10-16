# 부하 테스트

- 종료한 쿠폰 발급 비교 실험: [k6/coupon/README.md](k6/coupon/README.md)
- k6 도구 및 과거 실험 안내: [k6/README.md](k6/README.md)
- 종료한 위시리스트·편의시설 삭제 및 예약 이력 INSERT 비교: [벌크 쓰기 실험 보관 안내](k6/bulk-write/BENCHMARK.md)

벌크 쓰기 성능 비교용 API, 전용 프로필과 실행·집계 도구는 제거했다.
최적화된 운영 삭제와 예약 이력 JDBC batch 저장은 유지한다. 과거 실행 방법과
측정 근거는 보관 안내의 커밋별 소스에서 확인한다.

운영 기능 검증은 Docker와 현재 TZDB를 지원하는 JDK 21 환경에서 실행한다.

```bash
./gradlew test --tests '*WishlistDeleteIntegrationTest' \
  --tests '*AccommodationAmenityDeleteIntegrationTest' \
  --tests '*ReservationHistoryBatchWriter*' --tests '*ExpiredReservationCleanup*'
```
