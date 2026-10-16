# 성능 측정 결과

최종 결과와 해석은 아래 문서에 남기고, 원본 계측·실행 설정·소스 스냅샷은 측정 당시 Git 버전으로 연결했다.

- [숙소 상세 Redis 캐시](../aws-cache-experiments.md)
- [리뷰 요약 반정규화 · AWS](review-summary-aws-2026-10-12/README.md)
- [쿠폰 발급 · AWS](coupon-aws-2026-10-11/README.md)
- [쿠폰 발급 · 로컬 검증](../coupon-skip-locked-local-results.md)
- [ASG 확장 · AWS](asg-aws-2026-10-12/README.md)
- [조회 API 실행계획 · 로컬](read-api-local-2026-10-12/README.md)

원본은 각 문서의 **측정 당시 보관본** 링크에서 확인할 수 있다.
Git에서 복원할 때는 현재 작업 파일과 섞이지 않도록 별도 디렉터리에 꺼낸다.

```bash
mkdir -p /tmp/airbob-measurement-evidence
git archive a10b89a1bfdc173f3f327ed4657a1df2db14d2cd docs/performance/results \
  | tar -x -C /tmp/airbob-measurement-evidence
```

캐시 측정의 원본과 코드 버전은 [캐시 결과](../aws-cache-experiments.md#보존-버전과-원본)에 별도로 기록했다.
