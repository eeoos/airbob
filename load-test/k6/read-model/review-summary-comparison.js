// 리뷰 요약 비교 API가 제거되어 이전 실험을 실행하지 않는다.
// 기존 AWS runner에서도 HTTP 요청 전에 제거 사유를 확인할 수 있도록 진입점은 남긴다.
throw new Error(
  '리뷰 요약 전후 비교는 종료되었습니다. 측정 결과와 이전 도구 위치는 load-test/k6/review-summary/README.md를 확인하세요.',
);
