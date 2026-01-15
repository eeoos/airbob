import http from 'k6/http';
import { sleep, check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'https://example.com';

export const options = {
    scenarios: {
        asg_scale_test: {
            executor: 'ramping-vus',
            startVUs: 10,
            stages: [
                { duration: '2m', target: 30 }, // 워밍업
                { duration: '5m', target: 30 }, // CPU 부하 유지
                { duration: '3m', target: 80 }, // scale-out 트리거
                { duration: '5m', target: 80 }, // 스케일 이후 안정화 관측
            ],
        },
    },
};

export default function () {
    const res = http.get(`${BASE_URL}/api/v1/test/cpu-burn`);

    check(res, {
        'status is 200': (r) => r.status === 200,
    });

    sleep(1);
}
