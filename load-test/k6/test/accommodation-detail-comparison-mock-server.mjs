import { createServer } from 'node:http';
import { readFileSync, writeFileSync } from 'node:fs';

const [, , portFile, fixtureFile, observationsFile, mode = 'success'] = process.argv;
const fixture = JSON.parse(readFileSync(fixtureFile, 'utf8'));
const accommodations = new Map(fixture.accommodations.map((entry) => [entry.id, entry.data]));
const observations = [];
const token = process.env.BENCHMARK_READ_MODEL_TOKEN;
if (!portFile || !observationsFile || !token) {
  throw new Error('port, observations file, and benchmark token are required');
}
const server = createServer((request, response) => {
  const tokenMatches = request.headers['x-benchmark-token'] === token;
  const hasSession = Boolean(request.headers.cookie || request.headers.authorization);
  observations.push({ method: request.method, path: request.url, tokenMatches, hasSession });
  writeFileSync(observationsFile, JSON.stringify(observations), { mode: 0o600 });
  const match = /^\/api\/v[12]\/accommodations\/(\d+)$/.exec(request.url || '');
  const data = match ? accommodations.get(Number(match[1])) : undefined;
  if (request.method !== 'GET' || !data || !tokenMatches || hasSession) {
    response.writeHead(404).end();
    return;
  }
  const respond = () => {
    if (mode === 'redirect') {
      response.writeHead(302, { location: request.url }).end();
      return;
    }
    response.writeHead(mode === 'status' ? 500 : 200, {
      'content-type': 'application/json',
      // The next iteration must still be anonymous, even if a server sets a cookie.
      'set-cookie': 'SESSION=should-not-be-reused; Path=/',
    });
    if (mode === 'json') {
      response.end('not JSON');
      return;
    }
    const result = { ...data, amenities: [...data.amenities].reverse() };
    if (mode === 'payload') {
      result.review_summary = { ...data.review_summary, review_count: data.review_summary.review_count + 1 };
    }
    response.end(JSON.stringify({ success: true, data: result }));
  };
  if (mode === 'slow') {
    setTimeout(respond, 200);
  } else {
    respond();
  }
});
server.listen(0, '127.0.0.1', () => {
  writeFileSync(portFile, `${server.address().port}\n`, { mode: 0o600 });
});
process.on('SIGTERM', () => server.close());
