// prepare는 파일만 만들고, run만 준비된 AWS 앱에 HTTP 요청을 보낸다.
import { createHash, randomBytes } from 'node:crypto';
import { lookup } from 'node:dns/promises';
import { existsSync, mkdirSync, readFileSync, realpathSync, writeFileSync } from 'node:fs';
import https from 'node:https';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseConfig, TARGETS } from './lib.mjs';
import { runComparison } from './run-comparison.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../../..');
const files = ['aws.mjs', 'comparison.js', 'lib.mjs', 'run-comparison.mjs', 'README.md', 'AWS.md',
  'aws-experiment.example.json', 'aws-application.env.example']
  .map((name) => `load-test/k6/review-summary/${name}`).concat('infra/aws/toolchain.env');
const sha = (value) => createHash('sha256').update(value).digest('hex');
const need = (ok, message) => { if (!ok) throw new Error(message); };
const integer = (v, min, max) => Number.isSafeInteger(v) && v >= min && v <= max;
const slug = (v) => typeof v === 'string' && /^[a-z0-9][a-z0-9-]{2,63}$/.test(v);
const hash = (v) => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
const instance = (v) => typeof v === 'string' && /^i-(?:[a-f0-9]{8}|[a-f0-9]{17})$/.test(v);
function keys(value, required, optional = []) {
  need(value && typeof value === 'object' && !Array.isArray(value), '설정에는 객체가 필요합니다.');
  need(required.every((key) => Object.hasOwn(value, key))
    && Object.keys(value).every((key) => [...required, ...optional].includes(key)), '설정의 필수 항목 또는 허용된 항목을 확인하세요.');
}
function json(path) { return JSON.parse(readFileSync(path, 'utf8')); }
function write(path, value) {
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  writeFileSync(path, typeof value === 'string' ? value : `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600, flag: 'wx' });
}

export function validateConfig(input) {
  const c = structuredClone(input);
  keys(c, ['schemaVersion', 'example', 'experimentId', 'baseUrl', 'aws', 'app', 'dataset', 'load', 'cases']);
  need(c.schemaVersion === 1 && typeof c.example === 'boolean' && slug(c.experimentId), '설정 버전·예제 여부·실험 ID가 올바르지 않습니다.');
  need(c.baseUrl === 'https://api.airbob.cloud', 'Lab 인증서의 HTTPS origin을 사용하세요.');
  keys(c.aws, ['region', 'albDnsName', 'appInstanceId', 'loadGeneratorInstanceId']);
  need(c.aws.region === 'ap-northeast-2'
    && /^[a-z0-9-]+\.ap-northeast-2\.elb\.amazonaws\.com$/.test(c.aws.albDnsName), '서울 리전 실험 ALB DNS를 지정하세요.');
  need(instance(c.aws.appInstanceId) && instance(c.aws.loadGeneratorInstanceId)
    && c.aws.appInstanceId !== c.aws.loadGeneratorInstanceId, '서로 다른 앱·부하 발생기 인스턴스 ID가 필요합니다.');
  keys(c.app, ['imageDigest', 'sourceCommit', 'runId', 'resourceFencingTokenSha256']);
  need(/^sha256:[a-f0-9]{64}$/.test(c.app.imageDigest) && /^[a-f0-9]{40}$/.test(c.app.sourceCommit)
    && /^[a-z0-9][a-z0-9-]{2,31}$/.test(c.app.runId) && hash(c.app.resourceFencingTokenSha256), '앱 이미지·커밋·Lab 실행 식별자를 확인하세요.');
  keys(c.dataset, ['id', 'manifestSha256', 'flywayVersion']);
  need(slug(c.dataset.id) && hash(c.dataset.manifestSha256) && c.dataset.flywayVersion === 28, '이번 비교에는 확인된 V28 데이터와 manifest 해시가 필요합니다.');
  keys(c.load, ['rates', 'warmupSeconds', 'measureSeconds', 'rounds', 'preAllocatedVUs', 'maxVUs']);
  need(Array.isArray(c.load.rates) && c.load.rates.length > 0 && c.load.rates.length <= 10
    && new Set(c.load.rates).size === c.load.rates.length && c.load.rates.every((v) => integer(v, 1, 1000))
    && integer(c.load.warmupSeconds, 1, 300) && integer(c.load.measureSeconds, 1, 600)
    && integer(c.load.rounds, 1, 10) && integer(c.load.preAllocatedVUs, 1, 2000)
    && integer(c.load.maxVUs, c.load.preAllocatedVUs, 2000), '측정 시간·요청률·회차·VU 범위를 확인하세요.');
  need(Array.isArray(c.cases) && c.cases.length > 0 && c.cases.length <= 30, '측정할 데이터 사례가 필요합니다.');
  const ids = new Set();
  for (const item of c.cases) {
    need(TARGETS.includes(item.target), '지원하지 않는 API입니다.');
    const detail = item.target === 'accommodation-detail';
    const wishlist = item.target === 'wishlist-accommodations';
    keys(item, ['id', 'target', 'publishedReviewCount', ...(detail ? ['accommodationId']
      : ['expectedRows', 'authEnvPrefix', ...(wishlist ? ['wishlistId', 'pageSize'] : [])])], wishlist ? ['cursor'] : []);
    need(slug(item.id) && !ids.has(item.id) && integer(item.publishedReviewCount, 0, Number.MAX_SAFE_INTEGER), '사례 ID가 중복되거나 리뷰 합계가 올바르지 않습니다.');
    ids.add(item.id);
    if (detail) need(integer(item.accommodationId, 1, Number.MAX_SAFE_INTEGER), '실제 숙소 ID가 필요합니다.');
    else {
      need(/^[A-Z][A-Z0-9_]{2,63}$/.test(item.authEnvPrefix), '인증 환경변수의 접두어를 지정하세요.');
      need(integer(item.expectedRows, 1, wishlist ? 50 : 100), 'AWS 비교에는 비어 있지 않은 목록을 준비하세요.');
      if (wishlist) need(integer(item.wishlistId, 1, Number.MAX_SAFE_INTEGER) && integer(item.pageSize, item.expectedRows, 50)
        && (item.cursor === undefined || typeof item.cursor === 'string' && item.cursor.length <= 2048
          && !/[\r\n\0]/.test(item.cursor)), '위시리스트 ID·페이지·커서를 확인하세요.');
    }
  }
  if (!c.example) {
    need(![c.app.imageDigest.slice(7), c.app.sourceCommit, c.app.resourceFencingTokenSha256, c.dataset.manifestSha256]
      .some((v) => /^0+$/.test(v)) && !/example|replace/.test(c.aws.albDnsName)
      && !/^i-0+$/.test(c.aws.appInstanceId) && !/^i-0+$/.test(c.aws.loadGeneratorInstanceId), '예제 식별자를 실제 값으로 바꾼 뒤 example=false로 지정하세요.');
  }
  return c;
}

export function prepare(input, destination) {
  const c = validateConfig(input);
  const output = resolve(destination);
  // AWS CLI, Docker, k6, DNS 조회와 환경변수/자격증명 읽기를 하지 않는다.
  const sources = Object.fromEntries(files.map((name) => [name, readFileSync(join(root, name))]));
  mkdirSync(dirname(output), { recursive: true });
  mkdirSync(output, { mode: 0o700 });
  for (const [name, content] of Object.entries(sources)) write(join(output, name), content.toString('utf8'));
  write(join(output, 'config.json'), c);
  const plan = {
    schemaVersion: 1, state: 'offline-prepared', readyToExecute: false, example: c.example,
    awsCallsPerformed: 0, networkCallsPerformed: 0, infrastructureProvisioned: false,
    configSha256: sha(readFileSync(join(output, 'config.json'))),
    sources: Object.fromEntries(Object.entries(sources).map(([name, bytes]) => [name, sha(bytes)])),
    scheduledSecondsPerCaseAndRate: c.load.rounds * 2 * (c.load.warmupSeconds + 8 + c.load.measureSeconds),
    timingNote: '로그인·런타임 검증·종료 유예 시간은 별도. 한 번에 --case 하나와 --rate 하나만 실행.',
    commands: c.cases.flatMap((item) => c.load.rates.map((rate) => ({ case: item.id, rate }))),
    requiredBeforeRun: ['현재 코드의 digest 고정 앱 이미지와 V28 DB 확인', '고정 앱 1대와 별도 Linux 부하 발생기',
      'Lab 운영 절차의 측정 lease·수명·대상 자원 확인', '실험 ALB만 가리키는 DNS와 접근 권한',
      '같은 데이터셋의 실제 숙소·위시리스트·최근 본 기록과 리뷰 합계', '호스트 밖으로 내보내지 않는 벤치마크 토큰과 계정 인증',
      '기존 DB 모니터링과 UTC 시각 동기화'],
  };
  write(join(output, 'plan.json'), plan);
  write(join(output, 'SHA256SUMS'), [...files, 'config.json', 'plan.json']
    .map((name) => `${sha(readFileSync(join(output, name)))}  ${name}\n`).join(''));
  return plan;
}

export function readPrepared(path) {
  const directory = resolve(path);
  const plan = json(join(directory, 'plan.json'));
  need(plan.state === 'offline-prepared' && Object.keys(plan.sources).sort().join('\n') === [...files].sort().join('\n'), '준비 파일 목록이 올바르지 않습니다.');
  for (const name of files) need(sha(readFileSync(join(directory, name))) === plan.sources[name], '준비 후 스크립트가 변경됐습니다. 새로 prepare하세요.');
  need(sha(readFileSync(join(directory, 'config.json'))) === plan.configSha256, '준비 후 설정이 변경됐습니다. 새로 prepare하세요.');
  // 실행 중인 코드도 준비물과 같아야 한다. 저장소에서 변경된 실행기를 가져와 섞지 않는다.
  for (const name of files.filter((name) => /\.(?:mjs|js)$/.test(name))) {
    need(sha(readFileSync(join(root, name))) === plan.sources[name], '실행 코드와 준비된 번들이 다릅니다.');
  }
  return validateConfig(json(join(directory, 'config.json')));
}

export function caseEnvironment(c, item, rate, source, output, targetIp, smoke = false) {
  need(c.load.rates.includes(rate), '설정에 포함된 요청률을 선택하세요.');
  const env = Object.fromEntries(['PATH', 'HOME', 'TMPDIR', 'LANG', 'LC_ALL', 'TZ']
    .filter((key) => source[key] !== undefined).map((key) => [key, source[key]]));
  Object.assign(env, {
    BASE_URL: c.baseUrl, TARGET_IP: targetIp, BENCHMARK_READ_MODEL_TOKEN: source.BENCHMARK_READ_MODEL_TOKEN,
    TARGET: item.target, DATASET_LABEL: `${c.dataset.id}/${item.id}`, APP_REVISION: c.app.imageDigest,
    EXPECTED_REVIEW_COUNT: String(item.publishedReviewCount),
    RATE: String(smoke ? 2 : rate), WARMUP_DURATION: `${smoke ? 3 : c.load.warmupSeconds}s`,
    MEASURE_DURATION: `${smoke ? 5 : c.load.measureSeconds}s`, ROUNDS: String(smoke ? 1 : c.load.rounds),
    PRE_ALLOCATED_VUS: String(c.load.preAllocatedVUs), MAX_VUS: String(c.load.maxVUs),
    RESULT_DIR: output, K6_NO_USAGE_REPORT: 'true',
  });
  if (item.target === 'accommodation-detail') env.ACCOMMODATION_ID = String(item.accommodationId);
  else {
    env.EXPECTED_ROWS = String(item.expectedRows);
    for (const suffix of ['EMAIL', 'PASSWORD', 'SESSION_ID']) {
      const value = source[`${item.authEnvPrefix}_${suffix}`];
      if (value) env[`BENCHMARK_${suffix}`] = value;
    }
    if (item.target === 'wishlist-accommodations') {
      env.WISHLIST_ID = String(item.wishlistId); env.PAGE_SIZE = String(item.pageSize);
      if (item.cursor) env.CURSOR = item.cursor;
    }
  }
  parseConfig({ ...env, VARIANT: 'before', RESULT_PATH: 'validate.json' });
  return env;
}

export function verifyRuntime(c, value, challenge) {
  need(value.schema_version === 1 && value.run_id === c.app.runId
    && value.resource_fencing_token_sha256 === c.app.resourceFencingTokenSha256
    && value.challenge_sha256 === challenge && value.runtime_revision === c.app.imageDigest.slice(7)
    && value.app_instance_id === c.aws.appInstanceId, '응답한 앱의 실행·이미지·인스턴스 식별자가 다릅니다.');
  need(Array.isArray(value.active_profiles) && [...value.active_profiles].sort().join(',')
    === 'aws,performance-lab,read-model-benchmark,traffic-benchmark', '측정 앱의 프로필이 다릅니다.');
  need(['scheduler_enabled', 'kafka_listener_enabled', 'inventory_lifecycle_enabled', 'external_side_effects_enabled']
    .every((key) => value[key] === false), '백그라운드 작업 또는 외부 쓰기가 활성화되어 있습니다.');
  return value;
}

export function requestRuntime(c, ip, token, challenge) {
  const url = new URL('/api/v2/benchmark/read-model/runtime-assertion', c.baseUrl);
  const body = JSON.stringify({ run_id: c.app.runId, resource_fencing_token_sha256: c.app.resourceFencingTokenSha256, challenge_sha256: challenge });
  return new Promise((resolveRequest, reject) => {
    const req = https.request(url, {
      method: 'POST', agent: false, servername: url.hostname, rejectUnauthorized: true,
      lookup: (_host, options, callback) => options.all ? callback(null, [{ address: ip, family: 4 }]) : callback(null, ip, 4),
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body), 'X-Benchmark-Token': token },
    }, (res) => {
      let data = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => { data += chunk; if (data.length > 16384) req.destroy(new Error('런타임 응답이 너무 큽니다.')); });
      res.on('error', () => reject(new Error('런타임 응답 연결이 끊겼습니다.')));
      res.on('end', () => {
        try {
          need(res.statusCode === 200, '런타임 확인이 HTTP 200을 반환하지 않았습니다.');
          resolveRequest(verifyRuntime(c, JSON.parse(data), challenge));
        } catch { reject(new Error('런타임 신원·격리 검증 실패. 앱 설정과 토큰을 확인하세요.')); }
      });
    });
    const deadline = setTimeout(() => req.destroy(new Error('런타임 확인 시간 초과.')), 10000);
    req.on('close', () => clearTimeout(deadline));
    req.on('error', () => reject(new Error('실험 ALB의 TLS 연결 또는 런타임 요청이 실패했습니다.')));
    req.end(body);
  });
}

export async function runAws(prepared, destination, options, source = process.env, dependencies = {}) {
  const c = readPrepared(prepared);
  need(!c.example, '예제 준비물은 실행할 수 없습니다. 실제 설정으로 새로 prepare하세요.');
  need((dependencies.platform || process.platform) === 'linux', 'AWS의 별도 Linux 부하 발생기에서 실행하세요.');
  const item = c.cases.find((entry) => entry.id === options.case);
  need(item && c.load.rates.includes(options.rate), '--case와 --rate를 준비된 목록에서 선택하세요.');
  const output = resolve(destination);
  // 인증 오류는 DNS/HTTP 요청 전에 검출한다. 임시 IP는 설정 검증에만 쓰인다.
  caseEnvironment(c, item, options.rate, source, output, '192.0.2.1', options.smoke);
  mkdirSync(dirname(output), { recursive: true });
  mkdirSync(output, { mode: 0o700 });
  const record = { schemaVersion: 1, status: 'running', mode: options.smoke ? 'smoke' : 'measure',
    startedAt: new Date().toISOString(), config: c, caseId: item.id, rate: options.smoke ? 2 : options.rate };
  write(join(output, 'run-start.json'), record);
  try {
    const addresses = await (dependencies.lookup || lookup)(c.aws.albDnsName, { family: 4, all: true });
    need(addresses.length > 0 && addresses.every((entry) => entry.family === 4), 'ALB IPv4 주소를 찾을 수 없습니다.');
    const ip = addresses[0].address;
    const env = caseEnvironment(c, item, options.rate, source, output, ip, options.smoke);
    record.albAddresses = addresses.map((entry) => entry.address); record.targetIp = ip;
    const runtime = async ({ round, variant, directory }, phase) => {
      const challenge = randomBytes(32).toString('hex');
      const proof = await (dependencies.requestRuntime || requestRuntime)(c, ip, env.BENCHMARK_READ_MODEL_TOKEN, challenge);
      verifyRuntime(c, proof, challenge);
      write(join(directory, `${round}-${variant}-runtime-${phase}.json`), { at: new Date().toISOString(), ...proof });
    };
    const directory = await (dependencies.compare || runComparison)(env, {
      beforeRun: (context) => runtime(context, 'before'), afterRun: (context) => runtime(context, 'after'),
    });
    record.comparisonDirectory = directory; record.status = 'complete';
    return record;
  } catch (error) {
    record.status = 'failed';
    throw error;
  } finally {
    record.finishedAt = new Date().toISOString();
    write(join(output, 'run.json'), record);
  }
}

async function main() {
  const [mode, ...args] = process.argv.slice(2);
  const options = {};
  while (args.length) {
    const key = args.shift();
    need(/^--(?:config|output|prepared|case|rate|smoke)$/.test(key) && !Object.hasOwn(options, key), '명령 인자를 확인하세요.');
    options[key] = key === '--smoke' ? true : args.shift();
    need(options[key] && !String(options[key]).startsWith('--'), '명령 인자 값이 필요합니다.');
  }
  need(options['--output'], '--output에 새 디렉터리를 지정하세요.');
  if (mode === 'prepare') {
    need(Object.keys(options).every((key) => ['--config', '--output'].includes(key)), 'prepare 인자를 확인하세요.');
    const plan = prepare(json(options['--config'] || join(here, 'aws-experiment.example.json')), options['--output']);
    console.log(`오프라인 준비 완료: ${resolve(options['--output'])}\nAWS 호출 ${plan.awsCallsPerformed}회, 측정 실행 0회`);
  } else if (mode === 'run') {
    need(options['--prepared'] && !options['--config'], 'run에는 --prepared가 필요합니다.');
    const result = await runAws(options['--prepared'], options['--output'], {
      case: options['--case'], rate: Number(options['--rate']), smoke: Boolean(options['--smoke']),
    });
    console.log(`AWS ${result.mode} 완료: ${resolve(options['--output'])}`);
  } else throw new Error('prepare 또는 run을 지정하세요.');
}
if (process.argv[1] && existsSync(process.argv[1]) && realpathSync(process.argv[1]) === realpathSync(fileURLToPath(import.meta.url))) {
  main().catch((error) => { console.error(error.message); process.exitCode = 1; });
}
