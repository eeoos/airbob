#!/usr/bin/env python3
"""Check sealed v3 read responses against one explicitly selected application."""
import argparse
import hashlib
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request


def canonical(value):
    if isinstance(value, float) and value.is_integer():
        return int(value)
    if isinstance(value, list):
        return [canonical(v) for v in value]
    if isinstance(value, dict):
        return {k: canonical(v) for k, v in value.items()}
    return value


def digest(value):
    encoded = json.dumps(canonical(value), ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
    return hashlib.sha256(encoded).hexdigest()


def probe(release, dataset, base, password):
    spec = importlib.util.spec_from_file_location('growth_validator', Path(__file__).with_name('validate-growth-dataset-v3.py'))
    validator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(validator)
    validator.validate(release, dataset, '8.4.11')
    url = urllib.parse.urlsplit(base)
    if url.scheme not in ('http', 'https') or not url.hostname or url.username or url.password or url.query or url.fragment or url.path not in ('', '/'):
        raise ValueError('Expected an explicit HTTP application origin')
    if len(password) < 16:
        raise ValueError('A fresh run password is required')
    targets = json.loads((release / 'read-scenarios.json').read_text())['targets']
    sessions = {}
    observations = []

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    def request(client, path, body=None):
        if not path.startswith('/api/') or path.startswith('//') or '\\' in path or '#' in path:
            raise ValueError('Invalid sealed request path')
        req = urllib.request.Request(base.rstrip('/') + path,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        try:
            response = client.open(req, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status, raw = response.code, response.read(2_000_001)
        if len(raw) > 2_000_000:
            raise ValueError('Response exceeds the small qualification bound')
        return status, json.loads(raw)

    for target in targets:
        member = target['memberId']
        if member not in sessions:
            client = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
            if member is not None:
                account = target['account']
                if account['memberId'] != member or not re.fullmatch(r'growth-[a-z0-9-]+@example\.test', account['email']):
                    raise ValueError('Unexpected synthetic account identity')
                status, _ = request(client, '/api/v1/auth/login', {'email': account['email'], 'password': password})
                if status != 200:
                    raise RuntimeError('Synthetic account login failed')
            sessions[member] = client
        status, body = request(sessions[member], target['path'])
        actual_digest = digest(body)
        ids_match = target['arrayField'] is None or [v[target['idField']] for v in body['data'][target['arrayField']]] == target['expectedIds']
        observations.append({'id': target['id'], 'status': status, 'expectedStatus': target['expectedStatus'],
            'expectedResponseSha256': target['expectedResponseSha256'], 'actualResponseSha256': actual_digest,
            'orderedIdsMatch': ids_match,
            'passed': status == target['expectedStatus'] and actual_digest == target['expectedResponseSha256'] and ids_match})
    return {'schemaVersion': 1, 'datasetId': dataset, 'targetCount': len(targets),
        'passed': all(row['passed'] for row in observations), 'observations': observations,
        'scope': 'sealed HTTP compatibility; not a throughput or autoscaling measurement'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', type=Path, required=True)
    parser.add_argument('--expected-id', required=True)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError('Output must be new')
    result = probe(args.release, args.expected_id, args.base_url, os.environ.get('AIRBOB_ETL_BENCHMARK_PASSWORD', ''))
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    if not result['passed']:
        raise SystemExit('Sealed read response mismatch; inspect the bounded result file')


if __name__ == '__main__':
    main()
