"""Bind and restore the already published v4 native S3 snapshot, read-only."""
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.request
from growth_v4_contract import read, require, sha

BUCKET = 'airbob-performance-lab-dataset-942632789808'
REGION = 'ap-northeast-2'
DATASET = 'korea-growth-v4-778895bd2bd73be4'
RELEASE = DATASET + '-search-r1'
PREFIX = 'elasticsearch/releases/' + RELEASE
NAMES = {'manifest.json', 'snapshot-reference.json', 'snapshot-producer-receipt.json',
    'snapshot-seal.json', 'source-proof.json', 'mysql-current-fingerprint.json',
    'historical-inventory.json', 'seal-publication.json'}
MANIFEST_SHA = '69a27409e161ceb9ab8cf996e4d8cb78636e9a212f9166479af096e293fb1066'
FINGERPRINT = {'documents': 14343,
    'mappingSha256': 'd16594b50719036f3d2a125e734842cbb86678421ff8616fc55491a7ccf218a3',
    'contentSha256': 'f6a430ba203cdb32d6b2d020cc9c89dc4ed6e433d4939c27b7b512f7d867f2d0',
    'identityPairsSha256': '542ec2aecdd0db33a7296a8204b44ae459e003b470b66d34d9e9e5e9219e507a'}
ES = 'http://elasticsearch.lab.airbob.internal:9200'


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()


def descriptor_valid(search, source_id):
    require(source_id == DATASET and set(search) == {'enabled', 'snapshotRelease', 'artifacts', 'seal'} and
        search['enabled'] is True and search['snapshotRelease'] == RELEASE and
        set(search['artifacts']) == NAMES, 'Unexpected search release binding')
    for name, item in dict(search['artifacts'], nativeSeal=search['seal']).items():
        key = 'elasticsearch/seals/' + RELEASE + '.json' if name == 'nativeSeal' else 'datasets/' + RELEASE + '/' + name
        require(set(item) == {'key', 'versionId', 'sha256', 'bytes'} and item['key'] == key and
            re.fullmatch(r'[A-Za-z0-9._~+/=-]{1,1024}', item['versionId']) and item['versionId'] not in ['null', 'None'] and
            re.fullmatch(r'[0-9a-f]{64}', item['sha256']) and type(item['bytes']) is int and 0 < item['bytes'] < 20000,
            'Invalid search object version or bound')
    require(search['artifacts']['manifest.json']['sha256'] == MANIFEST_SHA and
        search['seal']['sha256'] == search['artifacts']['snapshot-seal.json']['sha256'], 'Unreviewed search manifest or seal')


def validate_companion(directory, search, envelope):
    descriptor_valid(search, envelope['source']['datasetId'])
    require({p.name for p in directory.iterdir()} == NAMES, 'Companion file inventory differs')
    for name, item in search['artifacts'].items():
        require((directory / name).stat().st_size == item['bytes'] and sha(directory / name) == item['sha256'], 'Companion bytes differ')
    manifest = read(directory / 'manifest.json')
    require(manifest['datasetId'] == DATASET and manifest['snapshotRelease'] == RELEASE and
        manifest['sourceDataset'] == {'bucket': BUCKET, 'consumerManifest': envelope['artifacts']['consumer-manifest.json'],
                                     'dump': envelope['artifacts']['airbob-growth.sql.gz']}, 'Search and MySQL source versions differ')
    require(manifest['search']['fingerprint'] == FINGERPRINT and
        manifest['search']['elasticsearchVersion'] == '8.18.8' and
        manifest['search']['basePath'] == PREFIX and manifest['search']['logicalAlias'] == 'accommodations', 'Unexpected search identity')
    require({k: manifest['seal'][k] for k in ['key', 'versionId', 'sha256']} ==
        {k: search['seal'][k] for k in ['key', 'versionId', 'sha256']}, 'Native seal coordinate differs')
    for name, item in manifest['artifacts'].items():
        require(all(search['artifacts'][name][k] == item[k] for k in ['sha256', 'bytes']), 'Companion artifact binding differs')
    proof = read(directory / 'source-proof.json')
    require(proof['baseDumpSha256'] == envelope['mysql']['dumpSha256'] and proof['unchangedCompleteTables'] == 31 and
        proof['historicalInventoryAllRowsEqual'] is True and proof['historicalInventoryRows'] == 4392547, 'Snapshot MySQL lineage differs')
    reference = read(directory / 'snapshot-reference.json')
    require(reference['fingerprint'] == FINGERPRINT and reference['basePath'] == PREFIX and reference['bucket'] == BUCKET and
        reference['datasetId'] == DATASET and reference['snapshotRelease'] == RELEASE and
        all(reference[k] == manifest['search'][k] for k in ['snapshot', 'snapshotUuid', 'snapshotIndex', 'elasticsearchVersion', 'image']),
        'Native reference differs from companion')
    seal = read(directory / 'snapshot-seal.json')
    require(seal['snapshotReferenceSha256'] == search['artifacts']['snapshot-reference.json']['sha256'] and
        seal['snapshotReceiptSha256'] == search['artifacts']['snapshot-producer-receipt.json']['sha256'], 'Seal does not bind snapshot evidence')
    return manifest, reference


def assemble_search(directory, publication_path, envelope):
    publication = read(publication_path)
    require(publication['state'] == 'PUBLISHED_BYTES_AND_VERSIONS_VERIFIED' and publication['nativeWriterDisabled'] is True and
        publication['bucket'] == BUCKET and publication['snapshotRelease'] == RELEASE, 'Verified search publication required')
    manifest = read(directory / 'manifest.json')
    search = {'enabled': True, 'snapshotRelease': RELEASE, 'artifacts': publication['objects'],
        'seal': dict({k: manifest['seal'][k] for k in ['key', 'versionId', 'sha256']}, bytes=(directory / 'snapshot-seal.json').stat().st_size)}
    validate_companion(directory, search, envelope)
    return search


def fetch_companion(directory, envelope, aws, check_native=False):
    search = envelope['search']
    descriptor_valid(search, envelope['source']['datasetId'])
    directory.mkdir(mode=0o700)
    def fetch(item, target):
        response = aws('s3api', 'get-object', '--bucket', BUCKET, '--key', item['key'], '--version-id', item['versionId'], str(target))
        require(response['VersionId'] == item['versionId'] and target.stat().st_size == item['bytes'] and sha(target) == item['sha256'], 'Snapshot object version or bytes differ')
    for name, item in search['artifacts'].items():
        fetch(item, directory / name)
    native = directory.parent / 'native-seal-readback.json'
    try:
        fetch(search['seal'], native)
    finally:
        native.unlink(missing_ok=True)
    manifest, reference = validate_companion(directory, search, envelope)
    if check_native:
        # The operator can read current native object versions without granting
        # ListBucketVersions to any application host or changing its boundary.
        latest = {v['key']: v for v in reference['inventory'] if v['kind'] == 'version' and v['isLatest']}
        remote = aws('s3api', 'list-objects-v2', '--bucket', BUCKET, '--prefix', PREFIX + '/')
        require({v['Key'] for v in remote.get('Contents', [])} == set(latest), 'Native repository membership changed')
        for item in remote['Contents']:
            expected = latest[item['Key']]
            head = aws('s3api', 'head-object', '--bucket', BUCKET, '--key', item['Key'])
            require(head['VersionId'] == expected['versionId'] and item['Size'] == expected['bytes'] and
                item['ETag'] == expected['etag'], 'Native repository latest version changed')
    return manifest, reference


def api(method, path, value=None):
    request = urllib.request.Request(ES + path, method=method, data=None if value is None else canonical(value),
        headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=180) as response:
        return json.load(response)


def absent(path):
    try:
        api('GET', path)
    except urllib.error.HTTPError as error:
        require(error.code == 404, 'Cannot inspect existing search target')
    else:
        raise ValueError('Search target already exists; it is never deleted implicitly')


def fingerprint(index):
    mapping = api('GET', '/' + index + '/_mapping')[index]['mappings']
    documents, scroll = [], None
    try:
        result = api('POST', '/' + index + '/_search?scroll=1m', {'size': 1000, 'sort': ['_doc'], 'track_total_hits': True})
        require(result['hits']['total'] == {'value': FINGERPRINT['documents'], 'relation': 'eq'}, 'Search count differs')
        while True:
            scroll = result.get('_scroll_id', scroll)
            require(result['_shards']['failed'] == 0, 'Search shard failed')
            if not result['hits']['hits']: break
            documents.extend({'id': h['_id'], 'source': h['_source']} for h in result['hits']['hits'])
            require(len(documents) <= FINGERPRINT['documents'], 'Search inventory exceeds bound')
            result = api('POST', '/_search/scroll', {'scroll': '1m', 'scroll_id': scroll})
    finally:
        if scroll:
            from growth_v4_app_read import cleanup_all
            cleanup_all([lambda: api('DELETE', '/_search/scroll', {'scroll_id': [scroll]})])
    documents.sort(key=lambda d: d['id'].encode())
    require(len(documents) == len({d['id'] for d in documents}) == FINGERPRINT['documents'], 'Duplicate or missing IDs')
    pairs = ''.join(d['id'] + '\t' + str(d['source']['accommodationId']) + '\n' for d in documents).encode()
    return {'documents': len(documents), 'mappingSha256': hashlib.sha256(canonical(mapping)).hexdigest(),
        'contentSha256': hashlib.sha256(canonical(documents)).hexdigest(), 'identityPairsSha256': hashlib.sha256(pairs).hexdigest()}, documents


def qualify_search(directory, envelope, work, sql):
    manifest, reference = validate_companion(directory, envelope['search'], envelope)
    require(os.environ['AIRBOB_ELASTICSEARCH_IMAGE_DIGEST'] == reference['image'].split('@')[1] and
        api('GET', '/')['version']['number'] == '8.18.8', 'AWS Elasticsearch image/version differs')
    nodes = api('GET', '/_nodes/plugins')['nodes']
    require(nodes and all({'analysis-nori', 'repository-s3'} <= {p['name'] for p in n['plugins'] + n['modules']}
                         for n in nodes.values()), 'Required search plugins missing')
    repository = 'airbob-v4-aws-readonly'
    index = 'accommodations-v' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%d%H%M%S')
    absent('/_alias/accommodations')
    absent('/accommodations')
    absent('/' + index)
    require(repository not in api('GET', '/_snapshot/_all'), 'Restore repository already exists')
    registered = False
    try:
        registered = True
        require(api('PUT', '/_snapshot/' + repository, {'type': 's3', 'settings': {'bucket': BUCKET, 'base_path': PREFIX,
            'readonly': True, 'region': REGION, 'endpoint': 's3.' + REGION + '.amazonaws.com'}})['acknowledged'], 'Repository registration failed')
        snapshots = api('GET', '/_snapshot/' + repository + '/' + reference['snapshot'])['snapshots']
        require(len(snapshots) == 1 and snapshots[0]['state'] == 'SUCCESS' and snapshots[0]['uuid'] == reference['snapshotUuid'] and
            snapshots[0]['indices'] == [reference['snapshotIndex']] and snapshots[0]['include_global_state'] is False, 'Snapshot identity differs')
        result = api('POST', '/_snapshot/' + repository + '/' + reference['snapshot'] + '/_restore?wait_for_completion=true', {
            'indices': reference['snapshotIndex'], 'include_global_state': False, 'include_aliases': False, 'feature_states': ['none'],
            'rename_pattern': '^' + re.escape(reference['snapshotIndex']) + '$', 'rename_replacement': index,
            'index_settings': {'index.number_of_replicas': 0, 'index.blocks.write': True}})
        require(result['snapshot']['shards']['failed'] == 0, 'Native restore shard failed')
        api('POST', '/' + index + '/_refresh')
        actual, documents = fingerprint(index)
        require(actual == FINGERPRINT, 'Restored document fields, mapping, or identities differ')
        expected = {line.split('\t')[0]: int(line.split('\t')[1]) for line in sql(
            "SELECT LOWER(BIN_TO_UUID(accommodation_uid)),id FROM accommodation WHERE status='PUBLISHED' ORDER BY id").splitlines()}
        require(expected == {d['id']: d['source']['accommodationId'] for d in documents}, 'Restored RDS and ES membership differ')
        require(api('PUT', '/' + index + '/_settings', {'index.blocks.write': False})['acknowledged'], 'Cannot enable normal index writes')
        require(api('POST', '/_aliases', {'actions': [{'add': {'index': index, 'alias': 'accommodations', 'is_write_index': True}}]})['acknowledged'], 'Alias creation failed')
        require(api('GET', '/_alias/accommodations') == {index: {'aliases': {'accommodations': {'is_write_index': True}}}}, 'Write alias differs')
        return {'state': 'NATIVE_S3_RESTORE_FULLY_VERIFIED', 'datasetId': DATASET, 'snapshotRelease': RELEASE,
            'restoredIndex': index, 'fingerprint': actual, 'publishedRdsIdsAndUidsEqual': True,
            'allSourceFieldsAndMappingsEqual': True, 'credentials': 'EC2 instance role; no static S3 credentials installed'}
    finally:
        if registered:
            from growth_v4_app_read import cleanup_all
            cleanup_all([lambda: api('DELETE', '/_snapshot/' + repository)])


if __name__ == '__main__':
    import argparse
    import subprocess
    parser = argparse.ArgumentParser(description='Verify version-bound search companion and current native S3 inventory without changing AWS.')
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    from growth_v4_aws_contract import validate_manifest
    envelope = read(args.manifest)
    validate_manifest(envelope, envelope['datasetRelease'])
    def aws(*parts):
        result = subprocess.run(['aws', '--region', REGION, '--no-cli-pager', '--output', 'json',
            '--cli-connect-timeout', '5', '--cli-read-timeout', '45', *parts], capture_output=True, timeout=60)
        require(result.returncode == 0, 'Search preflight AWS read failed')
        return json.loads(result.stdout or b'{}')
    fetch_companion(args.output, envelope, aws, check_native=True)
    print('V4_SEARCH_S3_VERSIONS_VERIFIED')
