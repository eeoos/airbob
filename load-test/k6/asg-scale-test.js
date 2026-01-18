import http from 'k6/http';
import { sleep, check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'https://example.com';

export const options = {
  scenarios: {
    asg_scale_test: {
      executor: 'ramping-vus',
      startVUs: 10,
      gracefulRampDown: '60s',
      stages: [
        // 워밍업 (캐시/커넥션 안정화)
        { duration: '3m', target: 30 },

        // 스케일-아웃 트리거 구간 (좀 더 강하게, 길게)
        { duration: '2m', target: 120 },
        { duration: '12m', target: 120 },

        // 추가 스파이크 (스케일-아웃이 늦거나 안 뜨면 여기서 확실히 밀어줌)
        { duration: '2m', target: 200 },
        { duration: '10m', target: 200 },

        // 안정화 관측 (인스턴스 늘어난 상태에서 지표가 어떻게 눌리는지)
        { duration: '8m', target: 120 },

        // 스케일-인 유도 (트래픽/CPU를 내려서 cooldown 이후 줄어드는지 관측)
        { duration: '12m', target: 10 },
      ],
    },
  },

  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<2000'],
  },
};

export default function () {
  const burnMs = __ENV.BURN_MS || '1200';

  const res = http.get(`${BASE_URL}/api/v1/test/cpu-burn?ms=${burnMs}`);

  check(res, {
    'status is 200': (r) => r.status === 200,
  });

  sleep(0.1);
}
