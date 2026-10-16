# 리뷰 요약 반정규화 측정 보관 안내

리뷰 요약 전후 측정은 완료했다. 현재 애플리케이션에서는 세 화면의 `review-summary-before` API와
원본 리뷰 집계 분기를 제거했으며, 전용 k6 실행기·입력 예제·도구 테스트도 정리했다.

- [AWS 측정 결과와 원본](../../../docs/performance/results/review-summary-aws-2026-10-12/README.md)
- [비교 조건과 로컬 측정 기록](../../../docs/performance/review-summary-denormalization.md)
- 제거 전 코드와 실행 도구를 보존한 커밋: `10893dfda93f38620751c30f10befef0709ddcb9`

이전 도구가 필요하면 위 커밋을 별도 작업트리에 체크아웃한다. 당시 실행 가이드는 같은 커밋의
`load-test/k6/review-summary/README.md`와 `AWS.md`에 있다.
정확한 AWS 측정 앱·설정·도구 버전은 결과 디렉터리의 환경 정보와 소스 기록을 기준으로 한다.
과거 입력을 현재 배포 앱에 그대로 실행하지 않는다.

리뷰 요약 테이블·생성/수정/삭제 시 갱신·요약 JOIN 조회·운영 지표는 유지한다.
N+1, 캐시, 위시리스트 개수 및 매출 집계 벤치마크는 별도 기능이다.
공용 read-model 도구의 과거 manifest·결과 검증 형식과, 실행을 차단하는 단독 리뷰 요약 안내 파일은 유지한다.
