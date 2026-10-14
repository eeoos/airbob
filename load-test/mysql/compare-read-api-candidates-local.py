#!/usr/bin/env python3
"""Compare bounded local candidates; remove only the invisible index we created."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess

spec = importlib.util.spec_from_file_location('inventory', Path(__file__).with_name('analyze-read-apis-local.py'))
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


def ddl(sql):
    result = subprocess.run(['docker', 'exec', '-i', 'mysql', 'sh', '-c',
        'MYSQL_PWD="$(cat "$MYSQL_ROOT_PASSWORD_FILE")" exec mysql -uroot --default-character-set=utf8mb4 --batch --raw --skip-column-names airbobdb'],
        input='SET SESSION lock_wait_timeout=5;\n' + sql, text=True, capture_output=True, timeout=180)
    if result.returncode:
        raise RuntimeError(result.stderr.strip())
    return result.stdout.strip()


def analyze(query, enabled=False):
    setting = "SET SESSION optimizer_switch='use_invisible_indexes=" + ('on' if enabled else 'off') + "';\n"
    tree = inventory.mysql(setting + 'EXPLAIN ANALYZE FORMAT=TREE ' + query)
    match = re.search(r'actual time=([\d.eE+-]+)\.\.([\d.eE+-]+) rows=([\d.eE+-]+) loops=(\d+)', tree)
    return {'tree': tree, 'rootTotalMs': float(match[2]) if match else None}


def fingerprint(query, enabled=False):
    setting = "SET SESSION optimizer_switch='use_invisible_indexes=" + ('on' if enabled else 'off') + "';\n"
    body = inventory.mysql(setting + query)
    return hashlib.sha256(body.encode()).hexdigest()


def host_indexes(output, cases):
    keys = ['host-list-all', 'host-list-published', 'host-list-unpublished', 'host-list-cursor']
    indexes = {
        'idx_readlab_host_order': '(member_id, created_at DESC, id DESC, status)',
        'idx_readlab_host_status_order': '(member_id, status, created_at DESC, id DESC)',
    }
    existing = inventory.mysql("SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS "
                               "WHERE TABLE_SCHEMA='airbobdb' AND TABLE_NAME='accommodation' AND IS_VISIBLE='NO'")
    if existing:
        raise ValueError('An existing invisible accommodation index prevents an isolated comparison')
    for name, columns in indexes.items():
        result = {'index': name, 'columns': columns, 'table': 'accommodation', 'runs': [],
                  'scope': 'temporary invisible index on existing local data; session-only optimizer switch'}
        created = False
        try:
            ddl(f'ALTER TABLE accommodation ADD INDEX {name} {columns} INVISIBLE, ALGORITHM=INPLACE, LOCK=NONE')
            created = True
            result['indexSizePages'] = inventory.mysql("SELECT stat_value FROM mysql.innodb_index_stats "
                f"WHERE database_name='airbobdb' AND table_name='accommodation' AND index_name='{name}' AND stat_name='size'")
            for key in keys:
                sql = cases[key]['sql']
                before, after = fingerprint(sql), fingerprint(sql, True)
                if before != after:
                    raise ValueError('Result parity failed: ' + key)
                row = {'id': key, 'sql': sql, 'responseHash': before, 'parity': True, 'samples': []}
                # Warm both routes before timing; alternate execution order in each pair.
                analyze(sql)
                analyze(sql, True)
                for number in range(1, 4):
                    for enabled in ([False, True] if number % 2 else [True, False]):
                        sample = analyze(sql, enabled)
                        sample.update(round=number, candidate=enabled)
                        row['samples'].append(sample)
                result['runs'].append(row)
                print(json.dumps({'index': name, 'query': key,
                    'baselineMs': [s['rootTotalMs'] for s in row['samples'] if not s['candidate']],
                    'candidateMs': [s['rootTotalMs'] for s in row['samples'] if s['candidate']]}), flush=True)
                inventory.save(output / (name + '.json'), result)
        finally:
            if created:
                ddl(f'ALTER TABLE accommodation DROP INDEX {name}, ALGORITHM=INPLACE, LOCK=NONE')
            result['cleanupConfirmed'] = not inventory.mysql("SELECT INDEX_NAME FROM information_schema.STATISTICS "
                f"WHERE TABLE_SCHEMA='airbobdb' AND TABLE_NAME='accommodation' AND INDEX_NAME='{name}'")
            inventory.save(output / (name + '.json'), result)


def settlement_join(output, cases):
    original = cases['settlement-detail-lines']['sql']
    host = re.search(r'a.member_id=(\d+)', original)[1]
    begin, end = re.search(r"DATE\(pt.created_at\) BETWEEN '([^']+)' AND '([^']+)'", original).groups()
    import datetime as dt
    exclusive = (dt.date.fromisoformat(end)+dt.timedelta(days=1)).isoformat()
    gross = "SUM(CASE WHEN pt.transaction_type='CONFIRM' THEN COALESCE(pt.amount,0) ELSE 0 END)"
    refund = "SUM(CASE WHEN pt.transaction_type IN ('CANCEL','PARTIAL_CANCEL') THEN COALESCE(pt.cancel_amount,0) ELSE 0 END)"
    candidate = f"SELECT a.id,a.name,{gross},{refund},{gross}-{refund} FROM accommodation a " \
        "JOIN reservation r ON r.accommodation_id=a.id JOIN payment_transaction pt ON pt.reservation_id=r.id " \
        f"WHERE a.member_id={host} AND ((pt.transaction_type='CONFIRM' AND pt.created_at>='{begin}' AND pt.created_at<'{exclusive}') " \
        f"OR (pt.transaction_type IN ('CANCEL','PARTIAL_CANCEL') AND COALESCE(pt.canceled_at,pt.created_at)>='{begin}' " \
        f"AND COALESCE(pt.canceled_at,pt.created_at)<'{exclusive}')) GROUP BY a.id ORDER BY a.id"
    first, second = fingerprint(original), fingerprint(candidate)
    result = {'id': 'settlement-detail-single-pass', 'baselineSql': original, 'candidateSql': candidate,
              'parity': first == second, 'responseHash': first, 'samples': [],
              'changes': ['disjoint UNION branches become one traversal with conditional SUM',
                          'date intervals become half-open UTC timestamp intervals',
                          'group by accommodation PK; name is functionally dependent']}
    if first != second:
        raise ValueError('Settlement response parity failed')
    analyze(original)
    analyze(candidate)
    for number in range(1, 6):
        for variant in (['baseline', 'candidate'] if number % 2 else ['candidate', 'baseline']):
            sample = analyze(original if variant == 'baseline' else candidate)
            sample.update(round=number, variant=variant)
            result['samples'].append(sample)
    inventory.save(output / 'settlement-detail-single-pass.json', result)
    print(json.dumps({'query': result['id'], 'parity': result['parity'],
        'baselineMs': [s['rootTotalMs'] for s in result['samples'] if s['variant']=='baseline'],
        'candidateMs': [s['rootTotalMs'] for s in result['samples'] if s['variant']=='candidate']}), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    cases = {c['id']: c for c in json.loads((args.source/'catalogue.json').read_text())}
    settlement_join(args.output, cases)
    host_indexes(args.output, cases)


if __name__ == '__main__':
    main()
