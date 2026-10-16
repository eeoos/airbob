"""Shared local/AWS bulk-delete experiment plan, runtime checks and report calculation."""
import hashlib
import json
import math
import re
from urllib.parse import urlsplit

CONFIG_KEYS = {
    'schemaVersion', 'example', 'environment', 'baseUrl', 'runId', 'appCommit', 'imageDigest',
    'schemaLabel', 'poolSize', 'rewriteBatchedStatements', 'rounds', 'samples', 'warmupSamples',
    'wishlistSizes', 'amenitySizes', 'credentials',
}
RUNTIME_KEYS = {
    'schema_version', 'runtime_id', 'environment', 'app_commit', 'image_digest', 'schema_label',
    'flyway_version', 'database_id', 'jvm_version', 'mysql_version', 'rewrite_batched_statements', 'pool_size',
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def integer(value, low, high):
    return type(value) is int and low <= value <= high


def validate(config):
    require(isinstance(config, dict) and set(config) == CONFIG_KEYS, 'Unexpected experiment configuration fields')
    c = json.loads(json.dumps(config))
    require(c['schemaVersion'] == 1 and type(c['example']) is bool, 'Invalid experiment schema/example flag')
    require(c['environment'] in ('local', 'aws'), 'Environment must be local or aws')
    require(isinstance(c['baseUrl'], str), 'An HTTP origin is required')
    url = urlsplit(c['baseUrl'])
    require(url.scheme in ('http', 'https') and url.hostname and not url.username and not url.password
            and not url.path and not url.query and not url.fragment, 'Use an origin without credentials or a path')
    require(url.port is None or 1 <= url.port <= 65535, 'Invalid origin port')
    if c['environment'] == 'local':
        require(url.hostname in ('localhost', '127.0.0.1', '::1'), 'Local experiments require a loopback origin')
    else:
        require(url.scheme == 'https' and url.hostname not in ('api.airbob.cloud', 'airbob.cloud',
                'localhost', '127.0.0.1', '::1'), 'AWS requires a dedicated lab HTTPS origin')
    require(isinstance(c['runId'], str) and re.fullmatch(r'[a-z0-9][a-z0-9-]{0,47}', c['runId']),
            'Run id must be a public filename-safe label of at most 48 characters')
    require(isinstance(c['appCommit'], str) and re.fullmatch(r'[0-9a-f]{40}', c['appCommit']),
            'Use the deployed full app commit')
    require(c['imageDigest'] == 'local' if c['environment'] == 'local' else
            isinstance(c['imageDigest'], str) and re.fullmatch(r'sha256:[0-9a-f]{64}', c['imageDigest']),
            'AWS requires a pinned image digest; local uses local')
    require(isinstance(c['schemaLabel'], str) and len(c['schemaLabel']) <= 64 and
            re.fullmatch(r'[a-z][a-z0-9_]*_bulk_write_benchmark', c['schemaLabel']),
            'A disposable *_bulk_write_benchmark schema is required')
    require(integer(c['poolSize'], 4, 100), 'Pool size must be between 4 and 100')
    require(type(c['rewriteBatchedStatements']) is bool, 'rewriteBatchedStatements must be boolean')
    require(integer(c['rounds'], 2, 20) and c['rounds'] % 2 == 0,
            'Use an even number of paired rounds between 2 and 20')
    require(integer(c['samples'], 1, 100) and integer(c['warmupSamples'], 1, 100),
            'Samples and warmup samples must be between 1 and 100')
    for key, maximum in [('wishlistSizes', 1000), ('amenitySizes', 100)]:
        values = c[key]
        require(isinstance(values, list) and 1 <= len(values) <= 10
                and all(integer(n, 0, maximum) for n in values) and len(set(values)) == len(values),
                'Invalid or duplicate dataset sizes')
    require(isinstance(c['credentials'], dict) and
            set(c['credentials']) == {'emailFile', 'passwordFile', 'tokenFile'}, 'Use credential file paths')
    require(all(isinstance(p, str) and p.startswith('/') for p in c['credentials'].values()),
            'Credential paths must be absolute')
    return c


def cases(c):
    return ([dict(key=f'wishlist-n{n}', candidate='WISHLIST_DELETE', datasetSize=n,
                  runner='run-wishlist-delete', measurement=None) for n in c['wishlistSizes']]
            + [dict(key=f'amenity-{mode.lower().replace("_", "-")}-n{n}',
                    candidate='ACCOMMODATION_AMENITY_DELETE', datasetSize=n,
                    runner='run-accommodation-amenity-delete', measurement=mode)
               for mode in ('DELETE_ONLY', 'FULL_REPLACEMENT') for n in c['amenitySizes']])


def plan(c):
    blocks = []
    for case in cases(c):
        for round_number in range(1, c['rounds'] + 1):
            order = ('BEFORE', 'AFTER') if round_number % 2 else ('AFTER', 'BEFORE')
            for index, variant in enumerate(order, 1):
                label = f'{c["runId"]}-{case["key"]}-r{round_number}-{variant.lower()}'
                blocks.append(dict(**case, round=round_number, runOrder=index, variant=variant, label=label))
    return dict(schemaVersion=1, environment=c['environment'], runId=c['runId'],
                appInstanceCount=1, blocks=blocks,
                measuredRequests=len(blocks) * c['samples'],
                warmupRequests=len(blocks) * c['warmupSamples'],
                percentileAlgorithm='nearest-rank',
                scope='DB operation through commit; fixture creation/verification/cleanup excluded; cache Redis I/O disabled')


def validate_runtime(c, runtime):
    require(isinstance(runtime, dict) and set(runtime) == RUNTIME_KEYS, 'Unexpected server runtime response')
    expected = dict(schema_version='bulk-delete-runtime-v1', environment=c['environment'],
                    app_commit=c['appCommit'], image_digest=c['imageDigest'], schema_label=c['schemaLabel'],
                    flyway_version='28', rewrite_batched_statements=c['rewriteBatchedStatements'], pool_size=c['poolSize'])
    require(all(runtime[k] == v and type(runtime[k]) is type(v) for k, v in expected.items()),
            'Server profile, image/commit, schema or JDBC settings do not match the experiment')
    for key in ('runtime_id', 'database_id'):
        require(isinstance(runtime[key], str) and re.fullmatch(r'[0-9a-f-]{36}', runtime[key]),
                'Server must report its process and MySQL identity')
    for key in ('jvm_version', 'mysql_version'):
        require(isinstance(runtime[key], str) and re.fullmatch(r'[a-zA-Z0-9._+-]{1,80}', runtime[key]),
                'Invalid runtime version label')
    return dict(runtime)


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def stats(values):
    require(values and all(type(n) in (int, float) and math.isfinite(n) and n >= 0 for n in values),
            'Missing or invalid measured samples')
    ordered = sorted(values)
    return dict(count=len(ordered), min=ordered[0], p50=ordered[max(0, math.ceil(len(ordered) * .5) - 1)],
                p95=ordered[math.ceil(len(ordered) * .95) - 1], max=ordered[-1])


def comparison(c, completed):
    results = []
    for case in cases(c):
        matched = [b for b in completed if b['block']['key'] == case['key']]
        require(len(matched) == c['rounds'] * 2, 'Incomplete paired rounds')
        variants = {}
        for variant in ('BEFORE', 'AFTER'):
            blocks = [b for b in matched if b['block']['variant'] == variant]
            observations = [o for b in blocks for o in b['artifact']['observations']]
            require(len(observations) == c['rounds'] * c['samples'], 'Incomplete raw sample count')
            sql = [o['hibernate_statements_by_type'] for o in observations]
            require(all(s == sql[0] for s in sql), 'SQL counts changed within a comparison')
            variants[variant] = dict(serverOperationMs=stats([o['server_operation_ms'] for o in observations]),
                                     hibernateStatements=sql[0],
                                     roundP50=[dict(round=b['block']['round'],
                                                    runOrder=b['block']['runOrder'],
                                                    value=stats([o['server_operation_ms']
                                                                 for o in b['artifact']['observations']])['p50'])
                                               for b in blocks])
        before = variants['BEFORE']['serverOperationMs']['p50']
        after = variants['AFTER']['serverOperationMs']['p50']
        metadata = matched[0]['artifact']['metadata']
        results.append(dict(case=case['key'], candidate=case['candidate'], datasetSize=case['datasetSize'],
                            measurement=case['measurement'], variants=variants,
                            workloadClass=metadata.get('workload_class'),
                            activeAmenityCodeCount=metadata.get('active_amenity_code_count'),
                            p50ReductionPercent=None if before == 0 else (before - after) / before * 100))
    return results
