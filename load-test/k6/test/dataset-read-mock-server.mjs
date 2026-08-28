import { createServer } from 'node:http';
import { writeFileSync } from 'node:fs';

const [, , portFile, failingResourceId = ''] = process.argv;
if (!portFile) {
  throw new Error('port file is required');
}

const server = createServer((request, response) => {
  const match = /^\/api\/v1\/accommodations\/(\d+)$/.exec(request.url || '');
  if (request.method !== 'GET' || !match) {
    response.writeHead(404).end();
    return;
  }
  const resourceId = Number(match[1]);
  const responseId = String(resourceId) === failingResourceId ? resourceId + 1 : resourceId;
  response.writeHead(200, { 'content-type': 'application/json' });
  response.end(JSON.stringify({ success: true, data: { id: responseId } }));
});

server.listen(0, '127.0.0.1', () => {
  const address = server.address();
  writeFileSync(portFile, `${address.port}\n`, { mode: 0o600 });
});

process.on('SIGTERM', () => server.close());
