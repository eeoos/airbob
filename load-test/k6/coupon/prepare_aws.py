#!/usr/bin/env python3
"""Offline only: package coupon tooling, or bind an existing account list to a dataset."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tarfile

from coupon_experiment import fingerprint, plan, require, validate

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SOURCES = [
    'load-test/k6/coupon/run_experiments.py', 'load-test/k6/coupon/coupon_experiment.py',
    'load-test/k6/coupon/coupon_monitoring.py', 'load-test/k6/coupon/coupon-issuance-comparison.js',
    'load-test/k6/coupon/coupon-benchmark-fixture.js', 'load-test/k6/coupon/prepare-coupon-sessions.js',
    'load-test/k6/coupon/benchmark-dataset-manifest-validator.js', 'load-test/k6/coupon/coupon-account-manifest.js',
    'load-test/k6/lib/benchmark-dataset-manifest.js', 'infra/aws/toolchain.env',
    'load-test/k6/coupon/README.md', 'load-test/k6/coupon/MANUAL.md', 'load-test/k6/coupon/AWS.md',
]
APP_SOURCES = [
    'src/main/resources/application.yaml',
    'src/main/java/kr/kro/airbob/config/SchedulingConfig.java',
    'src/main/java/kr/kro/airbob/domain/reservation/inventory/AccommodationInventoryProductionProfileGuard.java',
    'src/main/resources/application-coupon-performance.yaml', 'src/main/resources/application-coupon-benchmark.yaml',
    'src/main/java/kr/kro/airbob/common/benchmark/CouponPerformanceConfiguration.java',
    'src/main/java/kr/kro/airbob/common/benchmark/CouponPerformanceIsolationFilter.java',
]
APP_SOURCES += sorted(str(path.relative_to(ROOT)) for path in
                      (ROOT / 'src/main/java/kr/kro/airbob/domain/coupon').rglob('*.java'))
APP_SOURCES += sorted(str(path.relative_to(ROOT)) for path in
                      (ROOT / 'src/main/resources/lua').glob('coupon_*.lua'))


def write(path, value):
    with path.open('x') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')


def source_hashes(paths):
    return {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in paths}


def prepare(config, output):
    # Intentionally no subprocess, sockets, credentials, AWS SDK, or environment lookup.
    c = validate(config)
    paths = SOURCES + APP_SOURCES + ['load-test/k6/coupon/prepare_aws.py',
                                   'load-test/k6/coupon/aws-application.env.example']
    hashes = source_hashes(paths)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    with tarfile.open(output / 'coupon-tools.tar.gz', 'w:gz') as archive:
        for name in SOURCES:
            archive.add(ROOT / name, arcname=name, recursive=False)
    write(output / 'config.json', c)
    bundle_hash = hashlib.sha256((output / 'coupon-tools.tar.gz').read_bytes()).hexdigest()
    schedule = plan(c)
    measured = schedule['maximumMeasuredRuns']
    # CPU polling and login preparation are variable; show these separately from the fixed workload.
    schedule.update(
        state='offline-prepared', awsCallsPerformed=0, networkCallsPerformed=0,
        readyToExecute=False, configSha256=fingerprint(c), bundleSha256=bundle_hash, sources=hashes,
        fixedWorkloadSecondsUpperBound=measured * (c['durationSeconds'] + c['warmupSeconds'] + 2 * c['cooldownSeconds']),
        cloudwatchWaitSecondsUpperBound=measured * c['cpu']['waitSeconds'],
        loginPreparationIncludedInEstimate=False,
        appProfiles=['aws', 'coupon-performance', 'coupon-benchmark'],
        requiredInputs=['deployed image containing the recorded app sources', 'already restored and verified V28 dataset',
                        'matching real coupon account manifest and password/session fixture',
                        'lab-only HTTPS origin; direct private app metrics URLs; matching RDS identifier',
                        'shared benchmark token and ACTIVE ADMIN session', 'dedicated Linux load generator with pinned k6',
                        'fixed app count and lab lifetime covering login, measurements and cleanup'],
    )
    write(output / 'plan.json', schedule)
    write(output / 'cloudwatch-read-policy.json', {'Version': '2012-10-17', 'Statement': [{
        'Effect': 'Allow', 'Action': ['cloudwatch:GetMetricStatistics'], 'Resource': '*',
        'Condition': {'StringEquals': {'aws:RequestedRegion': c['cpu']['region']}},
    }]})
    (output / 'app.env.example').write_bytes((HERE / 'aws-application.env.example').read_bytes())
    (output / 'AWS.md').write_bytes((HERE / 'AWS.md').read_bytes())
    (output / 'SHA256SUMS').write_text(''.join(
        hashlib.sha256((output / name).read_bytes()).hexdigest() + '  ' + name + '\n'
        for name in ('coupon-tools.tar.gz', 'config.json', 'plan.json', 'cloudwatch-read-policy.json', 'app.env.example', 'AWS.md')))
    return schedule


def accounts(dataset_id, source_manifest, emails_path, output):
    require(re.fullmatch(r'[a-z0-9][a-z0-9-]{0,127}', dataset_id) is not None, 'Invalid dataset id')
    emails = json.loads(Path(emails_path).read_text())
    require(isinstance(emails, list) and 0 < len(emails) <= 20000000, 'Expected a nonempty JSON email array')
    require(all(isinstance(email, str) and len(email) <= 254
                and re.fullmatch(r'[a-z0-9][a-z0-9._+-]*@[a-z0-9][a-z0-9.-]*\.[a-z]{2,}', email)
                for email in emails) and len(set(emails)) == len(emails), 'Invalid or duplicate account emails')
    raw = Path(source_manifest).read_bytes()
    source = json.loads(raw)
    require(isinstance(source, dict), 'Expected the source dataset JSON manifest/envelope')
    versions = [source.get('flywayVersion'), source.get('mysql', {}).get('flywayVersion'),
                source.get('world', {}).get('flywayVersion')]
    versions = [str(version) for version in versions if version is not None]
    require(versions and set(versions) == {'28'}, 'Source dataset must declare Flyway V28')
    result = {'schemaVersion': 1, 'datasetVersion': 'coupon-accounts-v1',
              'sourceDataset': {'id': dataset_id, 'manifestSha256': hashlib.sha256(raw).hexdigest(), 'flywayVersion': 28},
              'accountPool': {'capacity': len(emails), 'emails': emails}}
    write(Path(output), result)
    return {'sourceDataset': result['sourceDataset'], 'capacity': len(emails), 'createdAccounts': 0,
            'networkCallsPerformed': 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    bundle = sub.add_parser('bundle')
    bundle.add_argument('--config', default=str(HERE / 'experiment.example.json'))
    bundle.add_argument('--output', required=True)
    account_list = sub.add_parser('accounts')
    account_list.add_argument('--dataset-id', required=True)
    account_list.add_argument('--source-manifest', required=True)
    account_list.add_argument('--emails', required=True, help='JSON array of existing, active benchmark account emails')
    account_list.add_argument('--output', required=True)
    args = parser.parse_args()
    os.umask(0o077)
    if args.mode == 'bundle':
        result = prepare(json.loads(Path(args.config).read_text()), args.output)
        print(json.dumps({k: result[k] for k in ('state', 'awsCallsPerformed', 'readyToExecute', 'requiredUniqueSessions')}, indent=2))
        print('Prepared files: ' + str(Path(args.output).resolve()))
    else:
        print(json.dumps(accounts(args.dataset_id, args.source_manifest, args.emails, args.output), indent=2))


if __name__ == '__main__':
    main()
