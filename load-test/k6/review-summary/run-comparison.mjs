import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { parseConfig, comparePair } from './lib.mjs';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
export async function runComparison(env = process.env, hooks = {}) {
  const rounds = Number(env.ROUNDS || 3);
  if (!Number.isInteger(rounds) || rounds < 1 || rounds > 20) throw new Error('ROUNDS는 1~20의 정수입니다.');
  parseConfig({ ...env, VARIANT: 'before', RESULT_PATH: 'validate.json' });
  const root = resolve(env.RESULT_DIR || join(scriptDirectory, '../../../build/k6/review-summary'));
  mkdirSync(root, { recursive: true });
  const directory = mkdtempSync(join(root, `${env.TARGET}-`));
  const pairs = [];
  let expectedHash;
  for (let round = 1; round <= rounds; round++) {
    const order = round % 2 === 1 ? ['before', 'after'] : ['after', 'before'];
    const results = {};
    for (const variant of order) {
      const path = join(directory, `${round}-${variant}.json`);
      await hooks.beforeRun?.({ round, variant, path, directory });
      console.log(`${round}/${rounds}회차: ${variant}`);
      const child = spawnSync(env.K6_BIN || 'k6', ['run', '--address', '', '--quiet', join(scriptDirectory, 'comparison.js')], {
        stdio: 'inherit', env: { ...env, K6_NO_USAGE_REPORT: 'true', VARIANT: variant, RESULT_PATH: path },
      });
      if (child.error || child.status !== 0) throw new Error(`k6 실행 실패: ${round}회차 ${variant}. 결과 위치: ${directory}`);
      results[variant] = JSON.parse(readFileSync(path, 'utf8'));
      if (!results[variant].valid) throw new Error(`유효하지 않은 측정입니다. 결과 위치: ${directory}`);
      await hooks.afterRun?.({ round, variant, path, directory });
      expectedHash ??= results[variant].responseHash;
      if (expectedHash !== results[variant].responseHash) throw new Error('회차 사이에 응답 데이터가 달라졌습니다.');
    }
    const comparison = comparePair(results.before, results.after);
    pairs.push({ round, order, ...comparison });
    console.log(`p95 지연 감소율: ${comparison.latencyReductionPercent.p95?.toFixed(2) ?? '계산 불가'}%`);
  }
  // 회차별 백분위수를 합치거나 평균내어 전체 p95라고 표시하지 않는다.
  writeFileSync(join(directory, 'comparison.json'), `${JSON.stringify({ schemaVersion: 1, target: env.TARGET, responseHash: expectedHash, pairs }, null, 2)}\n`, { mode: 0o600 });
  console.log(`전후 비교 완료: ${directory}`);
  return directory;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  runComparison().catch((error) => { console.error(error.message); process.exitCode = 1; });
}
