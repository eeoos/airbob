# 호스트 예약 날짜·금액·정렬 범위 정리

대상은 호스트 예약 목록과 상세 화면이다. 백엔드
GET /api/v1/profile/host/reservations 및 /{reservationUid}의 응답과
프론트 codex/editable-booking-review 작업트리를 대조했다.

## 날짜와 박수

HostDetail의 check_in_date_time/check_out_date_time은 예약 당시 time_zone_id로
복원한 숙소 현지 시각이다. 프론트는 이를 브라우저 현지 Date로 해석한 뒤
경과 시간을 24시간으로 나눠 올림했다. 브라우저의 서머타임 전환이나 숙소의
체크인·체크아웃 시각 차이가 숙박 박수에 영향을 주는 문제가 있었다.

수정 전 테스트에서 뉴욕 브라우저 기준 2026-11-01 00:00 → 11-02 00:00의
1박이 2박이 됐다. 07-10 09:00 → 07-11 18:00도 1박을 2박으로 계산했다.
숙소 현지 날짜 부분을 추출하고 UTC의 날짜 차이로 계산하도록 수정했다.
기존의 같은 날·역순 입력에 대한 1박 대체 동작은 유지했다.

## 결제 금액

PaymentInfo.total_amount는 Payment.amount, 즉 최초 확정 결제 금액이다.
쿠폰 할인은 checkout에서 예약 총액에 이미 반영된다. Payment의 취소 처리도
amount를 바꾸지 않고 status와 balanceAmount를 갱신한다.

프론트는 floor(totalAmount / nights)를 단가로 재구성하고
“숙박 요금: N박 × 단가”와 “정산” 제목으로 표시했다. 할인 후 총액이 100,001원인
2박 예약에서는 2박 × 50,000원으로 표시돼 총액과도 맞지 않았다.

추정 단가 필드를 제거하고 “결제 정보”에 “숙박 기간”과 “최초 결제 금액”을
표시하도록 수정했다. 이 값은 할인 전 숙박 단가, 취소 후 잔액, 호스트 정산금이
아니다. 결제 정보가 없을 때의 대체 화면과 기존 결제 총액은 유지했다.

## 목록 정렬 범위

서버는 createdAt/id 내림차순 커서로 예약을 조회하고, 프론트는 불러온 페이지들을
합쳐 check_in_date로 정렬한다. 이번에는 기존 기능을 유지하고 화면에
“현재 불러온 예약 N건 안에서 체크인 날짜순으로 정렬합니다.”를 명시했다.

정렬 버튼의 접근성 설명도 실제 방향에 따라 “빠른 날짜순”/“늦은 날짜순”으로
맞췄다. 기존 내림차순을 “가까운 순”이라고 부르던 표현은 제거했다.
전체 결과의 체크인순 조회는 이번 구현 범위가 아니다. 그 기능을 도입할 때는
서버 ORDER BY·커서 경계·프론트 query key를 함께 변경해야 한다.

## 검증

- 백엔드: `./gradlew test --tests '*ReservationResponseTest' --tests '*ReservationRepositoryQueryTest'`
  — 20개 통과, 실패·건너뛰기 없음. MySQL 조회 회귀와 JSON 직렬화 계약을 포함한다.
- 프론트: `npm run test:ci -- src/features/reservations src/screens/reservation-detail src/screens/profile`
  — 27개 파일, 299개 테스트 통과.
- `TZ=America/New_York`로 호스트 상세 화면 모델 테스트 10개를 추가 실행해 통과.
  서머타임 시작·종료, 늦은 체크아웃, 윤년, 결제 없음, 취소 후 최초 금액 표시를 검증했다.
- 브라우저: `npm run test:e2e:characterization -- tests/e2e/specs/host-reservation-characterization.spec.ts`
  — 3개 통과. 서울·뉴욕에서 동일한 2박과 100,001원 표시, 목록 20건→21건 추가 조회,
  정렬 방향 변경과 새 항목 재정렬, 요청 커서 전달을 검증했다.
- 운영·테스트·E2E TypeScript, 변경 파일 ESLint·Stylelint·Prettier 검사 통과.

백엔드 contracts/host-reservation-stay-payment.json과 프론트의 같은 이름 fixture는
동일한 JSON 내용을 사용한다. 브라우저 검증은 모의 HTTP 응답 기반이며 실제
백엔드 서버와 연결한 검증은 아니다. 결제 키는 테스트 전용 가상 문자열이다.

이번 단계의 운영 코드 변경은 프론트 표시와 계산에 한정한다. 백엔드 DTO·SQL·잠금·
인덱스·결제 및 정산 로직은 변경하지 않았다. 전체 조회 개선 완료를 뜻하지 않으며,
다음 검토 대상은 위시리스트 이름·찜 상태 확인을 위한 목록 페이지 반복 조회다.
