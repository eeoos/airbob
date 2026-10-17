# Airbob API 공통 규칙

요청 경로는 `/api/v1`로 시작한다. 요청·응답 JSON 필드는 `snake_case`이며, URL의 쿼리와 경로 변수 이름은 OpenAPI에 표시된 이름 그대로 사용한다. 예를 들어 검색의 `minPrice`, 리뷰의 `sortType`, 예약 목록의 `filterType`은 JSON 필드 이름 규칙으로 바꾸지 않는다.

## 인증과 권한

| API | 요청에 직접 입력할 값 | 성공 응답 |
|---|---|---|
| `POST /api/v1/auth/login` | JSON 본문의 `email`, `password` | 200, `{"success":true}`와 `Set-Cookie` |
| `GET /api/v1/auth/me` | 없음. 브라우저의 세션 쿠키 사용 | 200, `data.id`, `data.email`, `data.nickname` |
| `POST /api/v1/auth/logout` | 없음. 브라우저의 세션 쿠키 사용 | 200, `{"success":true}` |

로그인 요청의 두 필드는 필수이며 빈 문자열·공백만 있는 값·null은 허용하지 않는다. 아래는 형식 예시이므로 실제 요청에서는 가입한 활성 회원의 계정으로 바꾼다.

```json
{"email":"docs@example.invalid","password":"example-password"}
```

성공 시 `Set-Cookie`로 `SESSION_ID`가 발급된다. 쿠키는 HttpOnly, Path=/, Max-Age=3600이며, 서버 세션도 발급 후 최대 1시간 유지된다. 응답 JSON에 세션 ID는 포함하지 않는다. 같은 서버의 Swagger UI에서는 브라우저가 쿠키를 자동 저장·전송한다. `GET /api/v1/auth/me`의 200 응답으로 로그인 상태를 확인한다. 회원 ID는 서버에서 결정하며, 과거 회원 데이터의 이메일·닉네임은 null일 수 있다.

로그아웃은 해당 쿠키가 가리키는 서버 세션 한 개만 무효화하고 다른 로그인 세션은 유지한다. 별도 매개변수나 요청 본문은 없으며, `SESSION_ID`를 복사하거나 Authorize에 입력할 필요가 없다. 현재 API는 브라우저의 쿠키를 삭제하는 `Set-Cookie`를 내려주지 않으므로 쿠키가 남아 있을 수 있다. 이 쿠키로 이후 인증 요청이나 로그아웃을 다시 실행하면 401/M004를 받는다. 쿠키 인증 요구 사항은 OpenAPI의 `sessionCookie` 보안 정의로 표현한다.

| 인증 오류 | 의미 |
|---|---|
| 400/C001 | 로그인 필수값 누락·빈 값·공백 또는 읽을 수 없는 요청 본문 |
| 401/M003 | 로그인 비밀번호 불일치 |
| 404/M001 | 로그인 또는 내 정보 조회에서 활성 회원을 찾을 수 없음 |
| 401/M004 | 내 정보 조회·로그아웃에 유효한 세션이 없음 |

회원가입·로그인과 숙소 상세·예약 가능 정보·리뷰 목록·검색·공통 코드의 공개 GET은 비로그인 호출을 지원한다. 쿠폰 캠페인 목록을 포함한 다른 API는 로그인이 필요하다. `/api/v1/admin/**`는 MySQL에서 활성 ADMIN 역할을 다시 확인한다. 호스트·예약·리뷰·위시리스트의 접근 권한은 대상 소유권과 업무 조건도 충족해야 한다.

서버가 세션에서 구하는 `memberId`, `hostId`, `actorMemberId`는 요청에 전달하지 않는다. 관리자 회원 역할 변경 경로의 `{memberId}`처럼 명세에 나온 대상 식별자는 별도로 전달한다.

## 회원가입과 회원 역할 관리

`POST /api/v1/members`는 로그인 없이 호출한다. `nickname`은 공백만 있는 값을 제외한 1~20자, `email`은 이메일 형식의 최대 255자, `password`는 공백만 있는 값을 제외한 8~20자로 모두 필수다. 선택 항목 `thumbnail_image_url`은 최대 255자이며 생략하거나 null을 보낼 수 있다.

```json
{"nickname":"여행자","email":"docs@example.invalid","password":"example-password","thumbnail_image_url":null}
```

가입 시 역할은 MEMBER, 상태는 ACTIVE로 정해진다. 성공 응답은 201과 `{"success":true}`이며 회원 ID나 세션 쿠키를 반환하지 않는다. 로그인 후 `GET /api/v1/auth/me`에서 ID를 확인한다. 탈퇴·휴면 회원을 포함해 등록된 이메일은 재사용할 수 없다. 동일 이메일로 가입이 경합해도 한 요청만 생성에 성공하고 중복 요청은 409/M002를 받는다.

`PATCH /api/v1/admin/members/{memberId}/role`은 활성 ADMIN 회원만 호출할 수 있다. Swagger에서는 client 그룹에서 관리자로 로그인한 뒤 admin 그룹의 회원 관리 API를 실행한다. 경로의 `memberId`에는 변경 대상의 ID를 넣고, 요청자의 ID나 세션 ID는 직접 입력하지 않는다.

```json
{"role":"ADMIN","reason":"운영 관리자 지정"}
```

`role`은 MEMBER 또는 ADMIN이며 `reason`은 앞뒤 공백 제거 후 1~255자로 필수다. 이미 같은 역할이어도 사유 검증은 수행하지만 변경 이력은 추가하지 않고 200을 반환한다. 성공 응답의 `data`는 `{"member_id":20,"role":"ADMIN"}`처럼 대상 ID와 처리 후 역할을 담는다. 기존 로그인 세션은 유지되며 권한 변경은 대상의 다음 관리자 API 요청부터 반영된다.

| 회원 API 오류 | 의미 |
|---|---|
| 400/C001 | 가입 필수값·형식·길이 오류, 역할 또는 변경 사유 오류, 읽을 수 없는 JSON |
| 400/C004 | 역할 변경 경로의 memberId를 숫자로 읽을 수 없음 |
| 401/M004 | 역할 변경 요청에 유효한 세션이 없음 |
| 403/M006 | 요청자가 활성 ADMIN이 아님 |
| 404/M001 | 역할 변경 대상이 없거나 탈퇴·휴면 상태임 |
| 409/M002 | 이미 등록된 이메일로 가입 요청 |
| 409/M007 | 요청자가 자신의 ADMIN 역할을 MEMBER로 변경하려고 함 |

## 숙소 작성과 게시

1. 로그인 후 `POST /api/v1/accommodations`를 본문 없이 호출한다. 응답 `data.id`가 숙소 ID이며 DRAFT 상태로 생성된다. 별도 호스트 역할은 없다.
2. `PATCH /api/v1/accommodations/{accommodationId}`로 숙소 정보를 채운다. 숙소 유형은 `GET /api/v1/common-codes/ACCOMMODATION_TYPE`, 편의시설은 `GET /api/v1/common-codes/AMENITY_TYPE`에서 받은 활성 `code`를 사용한다.
3. `POST /api/v1/accommodations/{accommodationId}/images`로 이미지를 추가한다.
4. `PATCH /api/v1/accommodations/{accommodationId}/publish`를 본문 없이 호출한다. 이름·설명·주소와 좌표·양수 요금·유형·시간대·인원 정책·체크인/체크아웃 시간과 이미지 1장 이상이 필요하다. 주소의 좌표와 시간대는 수정 API가 계산한다.

초안의 기본 체크인/체크아웃 시간은 15:00/11:00이다. 시간 입력은 숙소 현지 `HH:mm` 또는 `HH:mm:ss`이고, 응답은 `HH:mm:ss`이다. 체크아웃 시간이 체크인 시간보다 늦으면 400/C001이다. 미래 재고를 점유하는 예약이 있으면 체크인·체크아웃 시간과 시간대를 바꿀 수 없다. 주소가 달라도 계산된 시간대가 같다면 이 시간대 변경 제한에는 걸리지 않는다.

부분 수정 예시:

```json
{"name":"도심 속 작은 숙소","base_price":100000,"currency":"KRW","amenity_infos":[{"name":"WIFI","count":1}],"occupancy_policy_info":{"max_occupancy":4,"infant_occupancy":1,"pet_occupancy":0}}
```

| 수정 항목 | 처리 규칙 |
|---|---|
| 생략 또는 null | 기존 값 유지. null로 값을 삭제하지 않음 |
| name / description | 각각 1~50자 / 1~5000자. 공백만 있는 값은 거절 |
| base_price / currency | 양수 원 단위 요금 / KRW만 지원 |
| address_info | 주소 전체 교체. postal_code·country·city·street 필수. 우편번호 최대 12자, 나머지 항목 최대 255자 |
| occupancy_policy_info | 세 항목 모두 필수. max_occupancy ≥ 1, infant_occupancy·pet_occupancy ≥ 0 |
| amenity_infos | 전체 교체. []는 모두 삭제. null 항목 거절, 코드 대문자 정규화 후 개수 합산, 0개 항목 제외. 합계가 정수 범위를 넘으면 400/C001 |

편의시설 요청의 `name`은 표시명이 아닌 공통 코드이고, 조회 응답에서는 같은 값을 `amenities[].type`으로 돌려준다. 주소와 인원 정책을 아직 설정하지 않은 초안은 관리 상세의 `address`·`policy` 객체 내부 값이 null이며 이미지·편의시설은 빈 배열이다. 통화가 저장되지 않은 숙소의 조회 응답 `currency`는 null일 수 있고 예약 계산에서는 KRW를 사용한다.

수정·게시·이미지 변경은 삭제되지 않은 본인 소유 숙소에만 가능하다. 소유권이 없는 숙소도 403이 아닌 404/A001을 반환한다. `/unpublish`는 PUBLISHED → UNPUBLISHED이며 다른 상태에서 호출하면 400/A003이다. 삭제는 DELETED로 바꾸는 논리 삭제이며 게시 중단·삭제가 기존 예약을 자동 취소하지 않는다. 검색과 공개 상세 캐시에는 상태 변경이 비동기로 반영된다.

`GET /api/v1/profile/host/accommodations`는 생성 시각·ID 내림차순이며 삭제된 숙소를 항상 제외한다. status를 생략하면 삭제를 제외한 모든 상태를 조회하고, DELETED로 조회하면 빈 목록이다. 반면 `GET /api/v1/profile/host/accommodations/{accommodationId}`는 소유자라면 삭제된 숙소도 조회할 수 있다.

게시 필수 정보가 부족하면 400/A009의 `error.errors`에서 `field`와 `reason`을 확인한다. 예를 들어 `field: "addressInfo"`는 요청의 `address_info`에 해당한다. 이미지 부족은 400/A006이다. 이미 게시된 숙소에 게시를 다시 요청해도 조건을 재검사한 뒤 200을 반환한다.

## 숙소 예약 달력

`GET /api/v1/accommodations/{accommodationId}/availability`는 공개 API이며 날짜 입력을 받지 않는다. 숙소 현지 오늘부터 3개월 뒤까지 `[booking_window_start_inclusive, booking_window_end_exclusive)` 구간을 반환한다. 종료 경계 날짜의 밤은 예약할 수 없지만 체크아웃 날짜로는 사용할 수 있다.

```json
{"booking_window_start_inclusive":"2027-01-01","booking_window_end_exclusive":"2027-04-01","unavailable_ranges":[{"start_date":"2027-01-10","end_date_exclusive":"2027-01-12"}]}
```

예시의 예약 불가 구간은 1월 10일·11일 밤이며 12일 밤은 포함하지 않는다. 점유 중이거나 아직 만료되지 않은 임시 확보 구간을 반환하고, 만료된 임시 확보 구간은 제외한다. 조회는 재고 확보가 아니므로 실제 체크아웃에서 다시 검사한다.

## 성공과 오류 응답

데이터가 있는 성공 응답:

```json
{"success":true,"data":{"operation_id":"00000000-0000-0000-0000-000000000001","status":"PENDING","status_url":"/api/v1/payment-operations/00000000-0000-0000-0000-000000000001"}}
```

데이터가 없는 성공 응답은 `{"success":true}`이다. 최상위 `data`, `error`는 null이면 생략된다. DTO 내부의 필드는 실제 계약에 따라 null이 포함될 수 있다. 예약의 `hold_expires_at`, 쿠폰의 과거 `total_quantity`, 마지막 페이지의 `next_cursor`가 그 예다.

오류 응답:

```json
{"success":false,"error":{"code":"CP012","status":503,"message":"발급 요청이 몰리고 있습니다. 잠시 후 다시 시도해 주세요.","errors":[]}}
```

`error.code`로 업무 오류를 구분한다. `errors`에는 필드 검증 실패가 담길 수 있다. 비밀번호·토큰·결제 키 등 민감 필드의 거절 값은 마스킹된다. 상태 코드는 API마다 다르며 모든 생성 요청이 201인 것은 아니다. 결제 확인과 예약 취소의 202는 반드시 해당 사용 흐름과 함께 해석한다.

| 오류 | 클라이언트의 처리 |
|---|---|
| M004 / 401 | 로그인 상태를 확인한다. |
| M006 / 403 | 관리자 권한이 있는 계정인지 확인한다. |
| C006 / 400 | 커서 페이지 크기를 1~50 범위로 수정한다. |
| C010 / 400 | 커서를 초기화하거나 이전 응답의 값을 그대로 전달한다. |
| R002 / 409 | 요청한 날짜가 이미 점유되었다. 날짜 선택을 다시 진행한다. |
| R025 / 503 | 짧은 잠금 경합이다. 지연을 두고 같은 체크아웃 키·요청으로 재시도한다. |
| CP002 / 409 | 쿠폰이 소진되었다. 품절 상태를 표시한다. |
| CP012 / 503 | 발급 경합이다. 요청 간격을 두고 제한된 횟수로 재시도한다. |

동일 오류 상태에서도 코드는 여러 개일 수 있다. 각 API 명세의 오류 예시에서 주요 코드를 확인한다. 결제 요청의 네트워크 오류나 202를 결제 실패로 단정하지 않는다.

## 커서 조회

`@CursorParam`을 사용하는 목록은 `cursor`, `size`를 사용한다. 기본 size는 20, 허용 범위는 1~50이다. 첫 요청에서는 cursor를 생략하고 이후에는 응답의 `page_info.next_cursor`를 그대로 전달한다. 커서를 직접 만들거나 해석하지 않는다.

```json
{"has_next":false,"next_cursor":null,"current_size":1}
```

위 예시는 `page_info` 내용이다. 항목 배열의 필드명은 API마다 다르다. 필터·정렬·숙소·위시리스트 등 조회 대상을 바꾸면 cursor를 초기화한다.

검색은 별도의 `page`, `size` 방식을 사용한다. page는 0부터 시작하며 14보다 큰 값은 14로 보정된다. size는 서버에서 항상 18로 보정한다.

## 날짜와 금액

- 쿠폰 업무 시각과 `Instant` 응답은 UTC의 `Z` 형식이다. 예: `2027-01-01T00:00:00Z`. 프런트엔드는 필요한 표시 시간대로 변환한다.
- 예약의 숙박 날짜는 숙소 현지 날짜 `YYYY-MM-DD`이다. 체크아웃일의 밤은 포함하지 않는다.
- 예약 상세의 `check_in_date_time`, `check_out_date_time`은 오프셋 없는 숙소 현지 일시다. `time_zone_id`와 함께 해석한다. 서버의 `server_time`을 기준으로 보유 만료와 남은 시간을 표시한다.
- 정산의 월 매개변수는 `YYYY-MM`, 날짜 범위는 해당 API가 명시한 `YYYY-MM-DD` 형식을 사용한다.
- 결제 금액은 서버 응답의 정수 값과 통화를 그대로 사용한다. 프런트엔드에서 다시 계산한 값으로 덮어쓰지 않는다.

## 이미지 업로드

숙소·리뷰 이미지 업로드는 요청 Content-Type이 `multipart/form-data`가 아니면 415/C011로 거절한다.

숙소와 리뷰의 이미지 API는 `multipart/form-data`의 `images` 필드에 파일을 여러 개 전달한다. 파일 파트의 Content-Type은 `image/jpeg` 또는 `image/png`이다. 파일당 10MiB(10 × 1024 × 1024바이트), 요청 전체 20MiB 제한을 적용하며 전체 크기에는 multipart 구분자와 헤더도 포함된다. 파일 목록을 JSON이나 쿼리 문자열로 보내지 않는다.

`images`가 없으면 400/C001, 빈 파일은 400/I007, 형식 오류는 400/I003이다. 서버의 multipart 크기 제한에 걸리면 413/I009이며, 애플리케이션의 개별 파일 검사에서 초과를 발견하면 400/I008이다. 큰 요청은 웹서버가 오류 응답 전달 전에 연결을 종료할 수도 있으므로 클라이언트에서도 전송 전 크기를 확인한다.

숙소 업로드 응답의 `data.uploaded_images`는 이번에 추가한 이미지의 `id`와 `image_url`이다. 삭제 경로의 `imageId`에는 이 ID를 사용한다. 이미지가 다른 숙소에 속하면 400/C009, 이미지가 없으면 404/I004이다. 대표 사진은 가장 작은 ID의 이미지이며 이를 삭제하면 다음 이미지로 바뀐다. 마지막 이미지를 삭제하면 대표 사진은 null이지만 숙소 게시 상태가 자동으로 바뀌지는 않는다.
