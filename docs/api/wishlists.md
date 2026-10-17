# 위시리스트 API 사용 흐름

모든 위시리스트 API는 로그인이 필요하다. 로그인 응답의 HttpOnly `SESSION_ID` 쿠키를 브라우저가 자동으로 전송한다. 회원 ID나 세션 값을 요청 폼에 직접 넣지 않는다. 자세한 요청·응답 스키마와 오류 예시는 [client.yaml](openapi/client.yaml)의 `위시리스트` 태그에서 확인한다.

## 목록 ID와 저장된 숙소 연결 ID

| 값 | 의미 | 사용처 |
|---|---|---|
| `wishlistId` | 위시리스트 ID | 목록 이름 변경·삭제, 숙소 추가, 상세 조회 |
| `accommodationId` | 숙소 ID | 저장 여부 조회의 쿼리 매개변수 |
| `accommodation_id` | 추가할 숙소 ID | 숙소 추가 요청의 JSON 필드 |
| `wishlistAccommodationId` | 목록과 숙소를 연결한 항목의 ID | 메모 변경, 목록에서 숙소 삭제 |

목록 생성 응답의 `data.id`는 **위시리스트 ID**다. 숙소 추가 응답의 `data.id`는 **연결 ID**다. 연결 ID는 목록 조회의 `wishlist_accommodation_id` 또는 상세 조회의 각 항목에서도 확인할 수 있다.

## 생성·저장·메모·삭제

기준 경로는 `/api/v1/members/wishlists`다.

| 작업 | 메서드와 경로 | 성공 |
|---|---|---|
| 목록 생성 | `POST /api/v1/members/wishlists` | 201, `data.id`는 목록 ID |
| 이름 변경 | `PATCH /api/v1/members/wishlists/{wishlistId}` | 200, `data.id`는 목록 ID |
| 목록 삭제 | `DELETE /api/v1/members/wishlists/{wishlistId}` | 200, `{"success":true}` |
| 목록 조회 | `GET /api/v1/members/wishlists` | 200, `wishlists`·`page_info` |
| 숙소 저장 여부 | `GET /api/v1/members/wishlists/membership` | 200, 저장 상태 |
| 숙소 추가 | `POST /api/v1/members/wishlists/accommodations/{id}` | 200, `data.id`는 연결 ID |
| 메모 변경 | `PATCH /api/v1/members/wishlists/accommodations/{id}` | 200, `data.id`는 연결 ID |
| 저장한 숙소 삭제 | `DELETE /api/v1/members/wishlists/accommodations/{id}` | 200, `{"success":true}` |
| 상세 조회 | `GET /api/v1/members/wishlists/accommodations/{id}` | 200, `wishlist_accommodations`·`page_info`·`wishlist_name` |

`/accommodations/{id}`의 `id`는 **GET·POST에서는 목록 ID**, **PATCH·DELETE에서는 연결 ID**다. 실제 숫자를 넣는 요청 URL은 같으며, 각 API의 매개변수 설명에서 사용할 ID를 확인한다.

1. `{"name":"서울 여행"}`으로 빈 목록을 생성한다. 생성 시 활성 회원인지 확인한다. 이름은 필수이며 1~255자이고 공백만으로 구성할 수 없다. 같은 이름의 목록은 여러 개 만들 수 있다. 이름 변경에도 같은 검증을 적용한다.
2. 생성한 목록에 `{"accommodation_id":101}`로 공개된 숙소를 추가한다. 숙소 ID는 필수 양수다. 같은 목록의 중복 추가는 동시 요청도 `409/W004`로 거절한다. 다른 목록에는 같은 숙소를 저장할 수 있다.
3. 추가 응답의 연결 ID로 `{"memo":"역에서 가까운 숙소"}`를 보내 메모를 변경한다. 최초 메모는 `null`이다. 변경 시에는 1~1,024자의 내용이 필수이고 공백만 사용할 수 없다. 현재 API는 `null`이나 빈 문자열로 메모를 지우는 요청을 지원하지 않는다. 문자열 길이는 UTF-16 코드 단위로 검사하므로 일부 이모지는 2자로 센다.
4. 숙소 삭제는 해당 목록과 숙소의 연결 및 메모를 제거한다. 다른 목록의 저장 상태와 숙소 자체는 유지된다. 저장 후 숙소가 비공개가 되어도 메모 변경·삭제는 가능하다.
5. 목록 삭제는 목록을 삭제 상태로 바꾸고 그 안의 숙소 연결과 메모를 모두 제거한다. 이후 목록 조회에서 제외된다. 이미 삭제한 목록을 다시 삭제하면 `404/W001`, 이미 삭제한 숙소 연결을 다시 삭제하면 `403/W005`다.

## 목록 조회와 선택 화면

목록 조회는 **본인의 활성 목록 전체**를 생성 시각·목록 ID 내림차순으로 반환한다.

`GET /api/v1/members/wishlists?accommodationId=101`은 숙소 101을 포함한 목록으로 필터링하는 요청이 아니다. 각 목록이 그 숙소를 포함하는지 함께 확인하는 요청이다.

| 조건 | `is_contained` | `wishlist_accommodation_id` |
|---|---|---|
| `accommodationId` 생략 | `null` | `null` |
| 해당 숙소가 목록에 있음 | `true` | 연결 ID |
| 해당 숙소가 목록에 없음 | `false` | `null` |

`wishlist_item_count`는 저장된 전체 숙소 수이므로 비공개 숙소도 포함한다. `thumbnail_image_url`은 가장 최근에 추가한 대표 숙소의 현재 사진이며 공개 상태를 확인하지 않는다. 사진이나 대표가 없으면 `null`이다. 대표 숙소를 삭제하면 남은 항목의 추가 시각·연결 ID 내림차순으로 다음 대표를 선택한다.

## 저장 여부만 조회

`GET /api/v1/members/wishlists/membership?accommodationId=101&wishlistId=42`

- `accommodationId`는 필수이며 빠지면 `400/C001`이다. `wishlistId`는 선택이다.
- `is_in_any_wishlist`는 본인의 활성 목록 중 하나라도 해당 숙소를 포함하는지를 뜻한다.
- `wishlistId`가 본인의 활성 목록이면 `target_wishlist_found=true`이고 `target_wishlist_contains`에 실제 포함 여부를 반환한다.
- `wishlistId`를 생략했거나 없는 목록·삭제된 목록·타인 소유 목록이면 `target_wishlist_found=false`, `target_wishlist_contains=null`이다. 이 경우도 200이며, 전체 저장 여부는 별도로 계산한다.
- 숙소의 현재 공개 상태를 확인하지 않는다. 존재하지 않는 숙소 ID도 오류 대신 저장되지 않은 상태로 반환한다.

예를 들어 내 다른 목록에는 저장되어 있지만 목록 42에는 없다면 다음과 같다.

```json
{
  "success": true,
  "data": {
    "is_in_any_wishlist": true,
    "target_wishlist_contains": false,
    "target_wishlist_found": true
  }
}
```

## 상세 조회와 페이지 이동

상세 조회는 **현재 공개된 숙소만** 추가 시각·연결 ID 내림차순으로 반환한다. 숙소·주소·리뷰 요약은 저장 당시의 사본이 아니라 현재 정보다. 주소가 없는 과거 숙소는 상세 조회에서 제외된다. 리뷰가 없으면 `review_summary`는 0건·0점이며, 반환한 항목의 `is_in_wishlist`는 항상 `true`다. 모든 숙소가 비공개여도 빈 배열과 `wishlist_name`을 반환한다.

목록의 `wishlist_item_count`에는 비공개 숙소도 포함되므로 상세 조회의 항목 수와 다를 수 있다. `created_at`은 목록 생성 또는 숙소 추가 당시의 UTC 시각(`Z`)이며 이름·메모 변경으로 바뀌지 않는다. 과거 데이터에는 `null`이 남아 있을 수 있다.

목록과 상세 조회 모두 `size` 기본값은 20, 최대값은 50이다. 다음 페이지는 응답 `page_info.next_cursor`를 그대로 `cursor`로 보낸다. 목록이나 선택 숙소를 바꾸면 커서를 초기화한다. 마지막 페이지는 `has_next=false`, `next_cursor=null`이다.

## 주요 오류

| 상태·코드 | 상황 |
|---|---|
| 401/M004 | 세션이 없거나 만료됨 |
| 400/C001 | 필수 숙소 조회값 누락, 이름·메모·추가할 숙소 ID 검증 실패, 잘못된 JSON |
| 400/C004 | 경로 또는 쿼리 ID를 숫자로 해석할 수 없음 |
| 400/C006, C010 | 페이지 크기 또는 커서 오류 |
| 404/M001 | 목록 생성 시 활성 회원을 찾을 수 없음 |
| 404/W001 | 이름 변경·목록 삭제·숙소 추가·상세 조회의 목록이 없거나 삭제됨 |
| 403/W002 | 이름 변경·목록 삭제·숙소 추가·상세 조회에서 다른 회원의 목록에 접근 |
| 404/A001 | 추가할 숙소가 없거나 공개 상태가 아님 |
| 409/W004 | 같은 목록에 숙소를 중복 추가 |
| 415/C011 | 이름·숙소 추가·메모 요청에 지원하지 않는 Content-Type 사용 |
| 403/W005 | 메모 변경·숙소 삭제의 연결 항목이 없거나 삭제됐거나 타인 소유 |

저장 여부 조회는 없는 목록도 상태값으로 응답하므로 위 표의 목록 접근 오류를 적용하지 않는다.
