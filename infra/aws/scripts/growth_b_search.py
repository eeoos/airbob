"""B native search snapshots: sealed source lineage, bounded equality, fs/S3 restores.

Commands run on the selected local/OCI/AWS host. No credentials are serialized in
receipts. Production entry points construct their own MySQL/application proofs;
pre-written source-proof assertions are never accepted as source evidence.
"""
from __future__ import annotations
import argparse
import contextlib
import datetime as dt
import hashlib
import importlib.util
import itertools
import json
import math
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

from growth_b_contract import read, require, sha, validate, extract_runtime, validate_fingerprint
from growth_b_runtime import qualified_environment, qualification_binding, qualify_runtime

ALIAS = 'accommodations'
ALGORITHM = 'airbob-es-accommodation-id-asc-length-prefixed-json-v1'
NAMES = {'manifest.json', 'source-proof.json', 'snapshot-reference.json',
         'snapshot-producer-receipt.json', 'snapshot-seal.json', 'native-inventory.json',
         'mysql-baseline-fingerprint.json', 'mysql-prepared-fingerprint.json'}
INDEX_RE = r'accommodations-v[a-z0-9][a-z0-9._-]{0,120}'
HEX_RE = r'[0-9a-f]{64}'
# Actual native metadata emitted by the pinned 8.18.8 image. This describes a
# compatibility range, separately from the root API's exact product version.
SNAPSHOT_METADATA_VERSION = '8.18.0-8.18.8'
SNAPSHOT_METADATA_VERSION_ID = 8525000
BUILD_FIELDS = ('number', 'build_flavor', 'build_hash', 'lucene_version',
                'minimum_wire_compatibility_version', 'minimum_index_compatibility_version')
REQUIRED_PLUGINS = {'analysis-nori', 'repository-s3'}


def canonical(value):
    def normalize(item):
        if isinstance(item, float):
            require(math.isfinite(item), 'Non-finite search value')
            return int(item) if item.is_integer() else item
        if isinstance(item, dict): return {k: normalize(v) for k, v in item.items()}
        if isinstance(item, list): return [normalize(v) for v in item]
        return item
    return json.dumps(normalize(value), ensure_ascii=False, sort_keys=True,
                      separators=(',', ':'), allow_nan=False).encode()


def digest(value): return hashlib.sha256(canonical(value)).hexdigest()


def write(path, value):
    path = Path(path)
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w', encoding='utf-8') as stream:
        stream.write(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n')
    path.chmod(0o600)


def run(arguments, *, data=None, environment=None, timeout=120, stdout=None, cwd=None):
    """Never echo commands, stderr, credentials, or remote response bodies on failure."""
    result = subprocess.run(arguments, input=data, env=environment, stdout=stdout or subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=timeout, cwd=cwd)
    require(result.returncode == 0, 'Subprocess failed: ' + Path(arguments[0]).name)
    return result.stdout


def file_binding(path): return {'sha256': sha(path), 'bytes': Path(path).stat().st_size}


def bounded_json_line(stream):
    line = stream.readline(8 * 1024 * 1024 + 1)
    require(len(line) <= 8 * 1024 * 1024, 'Search document exceeds bounded line size')
    return line


def document_stream(path):
    previous = 0
    with Path(path).open('rb') as source:
        while line := bounded_json_line(source):
            document = json.loads(line)
            current = document.get('accommodationId')
            require(type(current) is int and current > previous, 'MySQL documents must have strictly increasing numeric accommodationId')
            require(str(uuid.UUID(document['id'])) == document['id'], 'Document identity must be a canonical UUID')
            previous = current
            yield {'id': document['id'], 'source': document}


class DocumentDigest:
    def __init__(self):
        self.content = hashlib.sha256(); self.pairs = hashlib.sha256(); self.count = 0; self.previous = 0
    def add(self, document):
        source = document['source']; current = source.get('accommodationId')
        require(type(current) is int and current > self.previous, 'ES numeric accommodation IDs are missing, duplicate, or unsorted')
        require(document['id'] == source.get('id') == str(uuid.UUID(document['id'])), 'ES ID differs from source UUID')
        require(source.get('status') == 'PUBLISHED', 'Search source contains an unpublished document')
        self.previous = current; self.count += 1
        for target, value in [(self.content, document), (self.pairs, [document['id'], current])]:
            encoded = canonical(value); target.update(struct.pack('>I', len(encoded))); target.update(encoded)
    def result(self):
        return {'algorithm': ALGORITHM, 'documents': self.count,
                'contentSha256': self.content.hexdigest(), 'identityPairsSha256': self.pairs.hexdigest()}


def file_fingerprint(path):
    result = DocumentDigest()
    for item in document_stream(path): result.add(item)
    return result.result()


def document_fingerprint(value):
    return {key: value[key] for key in ('algorithm', 'documents', 'contentSha256', 'identityPairsSha256')}


def index_semantics(settings):
    """Analysis and query/index behavior; omit UUID, creation time and write fence."""
    return {'analysis': settings.get('analysis', {}), 'similarity': settings.get('similarity', {}),
            'numberOfShards': int(settings.get('number_of_shards', 1)),
            'routingPartitionSize': int(settings.get('routing_partition_size', 1)),
            'sort': settings.get('sort', {}), 'defaultPipeline': settings.get('default_pipeline', '_none'),
            'finalPipeline': settings.get('final_pipeline', '_none')}


def validate_runtime_identity(identity):
    require(identity['version'] == '8.18.8' and set(identity['productBuild']) == set(BUILD_FIELDS)
            and identity['productBuild']['number'] == '8.18.8'
            and identity['productBuild']['build_flavor'] == 'default'
            and re.fullmatch(r'[0-9a-f]{40}', identity['productBuild']['build_hash']), 'ES exact build identity is missing')
    require(identity['requiredPluginVersions'] == {name: '8.18.8' for name in REQUIRED_PLUGINS}, 'ES required plugin/module versions differ')
    require(re.fullmatch(r'sha256:' + HEX_RE, identity['imageId'])
            and re.fullmatch(r'(?:[a-zA-Z0-9./:_-]+@)?sha256:' + HEX_RE, identity['image'])
            and identity['platform']['os'] == 'linux' and identity['platform']['architecture'] in {'arm64', 'amd64'},
            'ES immutable image pin and supported platform provenance are required')


def runtime_compatibility(source, target):
    """Native format compatibility depends on ES/Lucene/plugins, not Docker CPU layers."""
    validate_runtime_identity(source); validate_runtime_identity(target)
    require(source['productBuild'] == target['productBuild']
            and source['requiredPluginVersions'] == target['requiredPluginVersions'],
            'Source and target ES build, Lucene or required plugin versions are incompatible')
    return {'productBuildEqual': True, 'requiredPluginVersionsEqual': True,
            'sourceImageId': source['imageId'], 'targetImageId': target['imageId'],
            'sourceImmutablePin': source['image'], 'targetImmutablePin': target['image'],
            'sourcePlatform': source['platform'], 'targetPlatform': target['platform'],
            'sameImageBytes': source['imageId'] == target['imageId']}


class Elasticsearch:
    def __init__(self, config):
        self.config = config
        parsed = urllib.parse.urlsplit(config['url'])
        require(parsed.scheme in {'http', 'https'} and parsed.hostname and not parsed.username and not parsed.password
                and not parsed.query and not parsed.fragment and parsed.path in {'', '/'}, 'Invalid ES endpoint')
        self.url = config['url'].rstrip('/')
        self.timeout = min(int(config.get('requestTimeoutSeconds', 180)), 3600)
        require(1 <= self.timeout <= 3600, 'Invalid ES request timeout')
    def api(self, method, path, body=None, *, content_type='application/json'):
        data = body if isinstance(body, bytes) else None if body is None else canonical(body)
        headers = {'Content-Type': content_type}
        auth_env = self.config.get('authorizationEnvironment')
        if auth_env:
            require(re.fullmatch(r'AIRBOB_[A-Z0-9_]+', auth_env), 'Unsafe authorization environment name')
            headers['Authorization'] = os.environ[auth_env]
        request = urllib.request.Request(self.url + path, data=data, method=method, headers=headers)
        with urllib.request.urlopen(request, timeout=self.timeout) as response:
            raw = response.read(64 * 1024 * 1024 + 1)
        require(len(raw) <= 64 * 1024 * 1024, 'ES response exceeds page bound')
        return json.loads(raw)
    def optional(self, path):
        try: return self.api('GET', path)
        except urllib.error.HTTPError as error:
            require(error.code == 404, 'ES object inspection failed')
            return None
    def identity(self):
        info = self.api('GET', '/')
        require(info['version']['number'] == self.config['version'] == '8.18.8', 'ES product version differs from pinned runtime')
        container = self.config['container']
        require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*', container), 'Invalid ES container name')
        observed = json.loads(run(['docker', 'inspect', container]))[0]
        endpoint = urllib.parse.urlsplit(self.url)
        published = observed.get('NetworkSettings', {}).get('Ports', {}).get('9200/tcp') or []
        network_mode = observed.get('HostConfig', {}).get('NetworkMode')
        require(endpoint.hostname in {'127.0.0.1', 'localhost', '::1'} and
                (network_mode == 'host' and endpoint.port == 9200 or
                 any(int(item['HostPort']) == endpoint.port for item in published)),
                'ES endpoint must resolve to the selected local host container port')
        image = json.loads(run(['docker', 'image', 'inspect', observed['Image']]))[0]
        pin = self.config['image']
        require(re.fullmatch(r'(?:[a-zA-Z0-9./:_-]+@)?sha256:[0-9a-f]{64}', pin), 'Exact ES image digest required')
        require(pin == image['Id'] or pin in image.get('RepoDigests', []), 'ES container image differs from digest pin')
        nodes = self.api('GET', '/_nodes/plugins')['nodes']
        require(len(nodes) == 1 and all(REQUIRED_PLUGINS <=
                {plugin['name'] for plugin in node.get('plugins', []) + node.get('modules', [])} for node in nodes.values()),
                'Required ES analysis/snapshot modules missing')
        node = next(iter(nodes.values()))
        versions = {plugin['name']: plugin['version'] for plugin in node.get('plugins', []) + node.get('modules', [])
                    if plugin['name'] in REQUIRED_PLUGINS}
        identity = {'version': info['version']['number'], 'clusterUuid': info['cluster_uuid'], 'image': pin, 'imageId': image['Id'],
                    'platform': {'os': image['Os'], 'architecture': image['Architecture']},
                    'productBuild': {key: info['version'][key] for key in BUILD_FIELDS}, 'requiredPluginVersions': versions}
        validate_runtime_identity(identity)
        return identity
    def alias(self, optional=False):
        result = self.optional('/_alias/' + ALIAS)
        if result is None and optional: return None
        require(isinstance(result, dict) and len(result) == 1, 'Alias must have exactly one target')
        index, value = next(iter(result.items()))
        require(re.fullmatch(INDEX_RE, index) and value.get('aliases') == {ALIAS: {'is_write_index': True}}, 'Alias must have one explicit versioned write index')
        return index
    def stream(self, index, page_size=500):
        require(re.fullmatch(INDEX_RE, index), 'Invalid versioned index')
        require(1 <= page_size <= 2000, 'Invalid search page size')
        pit = self.api('POST', '/' + index + '/_pit?keep_alive=5m')['id']
        after = None; seen = 0; expected_count = None
        try:
            while True:
                query = {'pit': {'id': pit, 'keep_alive': '5m'}, 'size': page_size,
                         'sort': [{'accommodationId': 'asc'}, {'_shard_doc': 'asc'}], 'track_total_hits': True}
                if after is not None: query['search_after'] = after
                result = self.api('POST', '/_search', query); pit = result.get('pit_id', pit)
                require(result.get('_shards', {}).get('failed') == 0 and not result.get('timed_out'), 'Search page was incomplete')
                total = result['hits']['total']
                require(total['relation'] == 'eq', 'Exact ES document count required')
                if expected_count is None: expected_count = total['value']
                require(expected_count == total['value'], 'PIT total changed')
                hits = result['hits']['hits']
                if not hits: break
                for hit in hits:
                    require(hit['_index'] == index, 'PIT returned another index')
                    seen += 1; yield {'id': hit['_id'], 'source': hit['_source']}
                after = hits[-1]['sort']
            require(seen == expected_count, 'PIT omitted documents')
        finally:
            require(self.api('DELETE', '/_pit', {'id': pit}).get('succeeded') is True, 'PIT cleanup failed')
    def fingerprint(self, index, expected_path=None):
        mapping = self.api('GET', '/' + index + '/_mapping')[index]['mappings']
        accumulator = DocumentDigest()
        with contextlib.ExitStack() as stack:
            expected = iter(document_stream(expected_path)) if expected_path else None
            if expected is not None: stack.callback(expected.close)
            stream = self.stream(index); stack.callback(stream.close)
            for item in stream:
                if expected is not None:
                    row = next(expected, None)
                    require(row is not None and canonical(item) == canonical(row), 'ES full source differs from the current pinned MySQL reader')
                accumulator.add(item)
            require(expected is None or next(expected, None) is None, 'ES omitted current MySQL documents')
        result = accumulator.result(); result['mappingSha256'] = digest(mapping)
        settings = self.api('GET', '/' + index + '/_settings')[index]['settings']['index']
        result['indexSemanticsSha256'] = digest(index_semantics(settings))
        return result
    def create_from_source(self, index, documents, mapping):
        require(self.optional('/' + index) is None and self.alias(optional=True) is None,
                'Fresh producer index requires an absent accommodations alias and index')
        require(self.api('PUT', '/' + index, mapping).get('acknowledged'), 'Index creation failed')
        for batch in itertools.batched(document_stream(documents), 200):
            payload = b''.join(canonical({'index': {'_index': index, '_id': item['id']}}) + b'\n' + canonical(item['source']) + b'\n' for item in batch)
            result = self.api('POST', '/_bulk', payload, content_type='application/x-ndjson')
            require(result.get('errors') is False and len(result.get('items', [])) == len(batch), 'Source document bulk indexing failed')
        self.api('POST', '/' + index + '/_refresh')
        require(self.api('POST', '/_aliases', {'actions': [{'add': {'index': index, 'alias': ALIAS, 'is_write_index': True}}]}).get('acknowledged'), 'Producer alias creation failed')
        require(self.alias() == index, 'Producer alias changed')


class Repository:
    def __init__(self, config, aws=None):
        self.config = config; self.aws = aws
        require(config['type'] in {'fs', 's3'} and re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,100}', config['name']), 'Invalid snapshot repository')
        self.name = config['name']; self.settings = dict(config['settings'])
        allowed = {'location', 'compress'} if config['type'] == 'fs' else {'bucket', 'base_path', 'region', 'endpoint', 'client', 'compress', 'server_side_encryption'}
        require(set(self.settings) <= allowed, 'Unsupported or credential-bearing repository setting')
        if config['type'] == 'fs':
            require(Path(config['inventoryRoot']).is_absolute() and Path(self.settings['location']).is_absolute(), 'Absolute fs repository paths required')
        else:
            require(self.aws is not None and re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,62}', self.settings['bucket']), 'S3 command adapter and bucket required')
            require(re.fullmatch(r'elasticsearch/releases/global-growth-b-[0-9a-f]{16}-search-[a-z0-9._-]+', self.settings['base_path']), 'Release-scoped S3 base path required')
    def binding(self):
        if self.config['type'] == 's3': return {'type': 's3', 'bucket': self.settings['bucket'], 'basePath': self.settings['base_path']}
        return {'type': 'fs', 'layout': 'native-elasticsearch-repository', 'locationAtProduction': self.settings['location']}
    def register(self, es, readonly):
        require(es.optional('/_snapshot/' + self.name) is None, 'Repository name already exists; never replace an external registration')
        require(es.api('PUT', '/_snapshot/' + self.name, {'type': self.config['type'], 'settings': self.settings | {'readonly': readonly}}).get('acknowledged'), 'Repository registration failed')
        observed = es.api('GET', '/_snapshot/' + self.name)[self.name]
        require(str(observed['settings']['readonly']).lower() == str(readonly).lower(), 'Repository read-only state differs')
    def unregister(self, es): require(es.api('DELETE', '/_snapshot/' + self.name).get('acknowledged'), 'Repository registration cleanup failed')
    def inventory(self):
        if self.config['type'] == 'fs':
            root = Path(self.config['inventoryRoot'])
            require(root.is_dir() and not root.is_symlink(), 'Native fs repository is unavailable')
            entries = []
            for item in root.rglob('*'):
                require(not item.is_symlink(), 'Snapshot repository cannot contain symlinks')
                if item.is_file(): entries.append({'key': item.relative_to(root).as_posix(), **file_binding(item)})
                require(len(entries) <= 100000, 'Native inventory exceeds bounded metadata limit')
            entries.sort(key=lambda item: item['key'])
            return {'type': 'fs', 'entries': entries}
        settings = self.settings; entries = []; markers = []
        while True:
            page = self.aws('s3api', 'list-object-versions', '--no-paginate', '--max-keys', '1000',
                            '--bucket', settings['bucket'], '--prefix', settings['base_path'] + '/', *markers)
            for group, kind in [('Versions', 'version'), ('DeleteMarkers', 'delete-marker')]:
                for item in page.get(group, []):
                    entry = {'kind': kind, 'key': item['Key'], 'versionId': item['VersionId'], 'isLatest': item['IsLatest']}
                    require(item['Key'].startswith(settings['base_path'] + '/'), 'Native S3 object escaped the release prefix')
                    require(item['VersionId'] not in {'null', 'None', ''}, 'Versioned native S3 repository required')
                    if kind == 'version': entry.update(bytes=item['Size'], etag=item['ETag'])
                    entries.append(entry)
            require(len(entries) <= 100000, 'Native inventory exceeds bounded metadata limit')
            if not page.get('IsTruncated'): break
            next_markers = ['--key-marker', page['NextKeyMarker'], '--version-id-marker', page['NextVersionIdMarker']]
            require(next_markers != markers, 'S3 inventory pagination made no progress')
            markers = next_markers
        entries.sort(key=lambda item: (item['key'], item['versionId'], item['kind']))
        return {'type': 's3', 'bucket': settings['bucket'], 'basePath': settings['base_path'], 'entries': entries}


def aws_cli(config):
    def call(*parts):
        prefix = ['aws', '--no-cli-pager', '--output', 'json', '--region', config['region'],
                  '--cli-connect-timeout', '5', '--cli-read-timeout', '45']
        if config.get('profile'): prefix += ['--profile', config['profile']]
        return json.loads(run(prefix + list(parts), timeout=60) or b'{}')
    return call


def verify_prepared(base, current, owned):
    require(current['algorithm'] == base['algorithm'] == 'sha256-pk-order-length-prefixed-jdbc-bytes-v1'
            and current['mysqlVersion'] == base['mysqlVersion'] and set(current['tables']) == set(base['tables']), 'MySQL fingerprint contract changed')
    require(base['domainHashExcludedColumns']['member'] == current['domainHashExcludedColumns']['member'] == ['password'], 'Member comparison must exclude only the password')
    unchanged = []
    for name, expected in sorted(base['tables'].items()):
        actual = current['tables'][name]
        require(actual['ddlSha256'] == expected['ddlSha256'], 'Prepared source DDL changed: ' + name)
        if name == 'member':
            require(actual['rows'] == expected['rows'] and actual['domainRowsSha256'] == expected['domainRowsSha256'], 'Preparation changed member fields beyond password')
        elif name == 'accommodation_inventory_day':
            require(actual['rows'] >= expected['rows'] and owned['rows'] == expected['rows'] and
                    owned['rowsSha256'] == expected['rowsSha256'] and owned['invalidFreeRows'] == 0,
                    'Preparation changed historical owners or added invalid FREE rows')
        else:
            require(actual['rows'] == expected['rows'] and actual['rowsSha256'] == expected['rowsSha256'], 'Preparation changed immutable complete rows: ' + name)
            unchanged.append(name)
    return {'unchangedCompleteTables': unchanged, 'onlyAllowedChanges': ['member.password', 'additional valid FREE inventory rows'],
            'historicalInventoryAllRowsEqual': True, 'historicalInventoryRows': owned['rows'], 'historicalInventorySha256': owned['rowsSha256']}


# This wrapper invokes the sealed ETL fingerprint byte algorithm, without changing
# that binary or weakening its original local preparation commands.
MYSQL_PROBE = r'''
package org.example.growth;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
public final class GrowthBSourceProbe {
    static final ObjectMapper JSON=new ObjectMapper();
    static Map<String,Object> identity(Connection c) throws Exception {
        try(var s=c.createStatement();var r=s.executeQuery("SELECT VERSION(),@@server_uuid,DATABASE(),(SELECT COUNT(*) FROM accommodation WHERE status='PUBLISHED')")) {
            r.next();return Map.of("version",r.getString(1),"serverUuid",r.getString(2),"schema",r.getString(3),"publishedDocuments",r.getLong(4));
        }
    }
    public static void main(String[] args) throws Exception {
        var p=new Properties();try(var r=Files.newBufferedReader(Path.of(args[1]))) {p.load(r);}
        var ds=new DriverManagerDataSource(p.getProperty("url"),p.getProperty("username"),p.getProperty("password"));
        Path out=Path.of(args[2]);
        if(args[0].equals("fingerprint")) {GrowthFingerprint.write(ds,out);return;}
        try(var c=ds.getConnection()) {
            if(args[0].equals("fence")) {
                var network=Executors.newSingleThreadExecutor(r -> {var t=new Thread(r,"fence-network-timeout");t.setDaemon(true);return t;});
                c.setNetworkTimeout(network,10000);
                long connectionId;
                try(var s=c.createStatement();var r=s.executeQuery("SELECT CONNECTION_ID()")) {r.next();connectionId=r.getLong(1);}
                var tables=new ArrayList<String>();
                try(var s=c.createStatement();var r=s.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name")) {
                    while(r.next()) {String t=r.getString(1);if(!t.matches("[a-z0-9_]+"))throw new IllegalStateException("Unsafe table");tables.add("`"+t+"` READ");}
                }
                if(tables.isEmpty())throw new IllegalStateException("No source tables");
                try(var s=c.createStatement()) {s.execute("SET SESSION lock_wait_timeout=10");s.execute("LOCK TABLES "+String.join(",",tables));}
                JSON.writeValue(out.toFile(),identity(c));
                // EOF releases the fence. A live JVM alone does not prove the
                // JDBC connection is alive: heartbeat this exact connection and
                // reject reconnects, idle disconnects and network loss.
                var stop=new AtomicBoolean();var requests=new ArrayBlockingQueue<String>(1);
                var input=new Thread(() -> {
                    try(var lines=new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
                        String line;while((line=lines.readLine())!=null) {
                            if(!line.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("Invalid fence heartbeat");
                            requests.put(line);
                        }
                    }catch(Exception ignored){}finally {stop.set(true);requests.offer("");}
                },"fence-parent-input");
                input.setDaemon(true);input.start();
                while(!stop.get()) {
                    String request=requests.poll(15,TimeUnit.SECONDS);
                    if(stop.get())break;
                    try(var s=c.createStatement()) {
                        s.setQueryTimeout(5);
                        try(var r=s.executeQuery("SELECT CONNECTION_ID()")) {
                            if(!r.next() || r.getLong(1)!=connectionId)throw new IllegalStateException("Read fence connection changed");
                        }
                    }
                    if(request!=null)Files.writeString(out.resolveSibling(out.getFileName()+".heartbeat"),request);
                }
                try(var s=c.createStatement()) {s.execute("UNLOCK TABLES");}
                network.shutdownNow();
                return;
            }
            if(args[0].equals("identity")) {JSON.writeValue(out.toFile(),identity(c));return;}
            if(!args[0].equals("owned"))throw new IllegalArgumentException("Unknown probe mode");
            var d=MessageDigest.getInstance("SHA-256");byte[] length=new byte[4];long rows=0,invalid=0;
            try(var s=c.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)) {
                s.setFetchSize(Integer.MIN_VALUE);
                try(var r=s.executeQuery("SELECT accommodation_id,stay_date,state,reservation_id,hold_expires_at FROM accommodation_inventory_day WHERE state<>'FREE' ORDER BY accommodation_id,stay_date")) {
                    while(r.next()) {rows++;for(int n=1;n<=5;n++) {byte[] b=r.getBytes(n);int z=b==null?-1:b.length;
                        length[0]=(byte)(z>>>24);length[1]=(byte)(z>>>16);length[2]=(byte)(z>>>8);length[3]=(byte)z;
                        d.update(length);if(b!=null)d.update(b);}}
                }
            }
            try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM accommodation_inventory_day i LEFT JOIN accommodation a ON a.id=i.accommodation_id WHERE i.state='FREE' AND (i.reservation_id IS NOT NULL OR i.hold_expires_at IS NOT NULL OR a.id IS NULL)")) {r.next();invalid=r.getLong(1);}
            JSON.writeValue(out.toFile(),Map.of("rows",rows,"rowsSha256",HexFormat.of().formatHex(d.digest()),"invalidFreeRows",invalid));
        }
    }
}
'''

BACKEND_PROBE = r'''
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import kr.kro.airbob.AirbobApplication;
import kr.kro.airbob.search.service.AccommodationSearchSnapshotReader;
public final class GrowthBCurrentSearchProbe {
    public static void main(String[] args) throws Exception {
        Path output=Path.of(System.getenv("AIRBOB_SEARCH_OUTPUT"));
        // Instantiate only the actual reader, repositories and converter needed
        // below. Full eager/web startup would require an unrelated live Redisson
        // connection even though this projection never reads or writes Redis.
        var application=new SpringApplication(AirbobApplication.class);
        application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        application.setLazyInitialization(true);
        try(var context=application.run(args);
            var writer=Files.newBufferedWriter(output,StandardOpenOption.CREATE_NEW)) {
            var jdbc=context.getBean(JdbcTemplate.class);
            var reader=context.getBean(AccommodationSearchSnapshotReader.class);
            var converter=context.getBean(org.springframework.data.elasticsearch.core.convert.ElasticsearchConverter.class);
            long cursor=0;
            while(true) {
                var rows=jdbc.queryForList("SELECT id,BIN_TO_UUID(accommodation_uid) uid FROM accommodation WHERE status='PUBLISHED' AND id>? ORDER BY id LIMIT 200",cursor);
                if(rows.isEmpty())break;
                for(var row:rows) {
                    cursor=((Number)row.get("id")).longValue();
                    var document=reader.readPublished(UUID.fromString(row.get("uid").toString())).orElseThrow();
                    var serialized=org.springframework.data.elasticsearch.core.document.Document.create();
                    converter.write(document,serialized);writer.write(serialized.toJson());writer.newLine();
                }
            }
        }
    }
}
'''


def properties(path, values):
    def escape(value):
        return ''.join('\\u%04x' % ord(c) if c in '\\:=#!\n\r\t ' else c for c in str(value))
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w', encoding='utf-8') as output:
        for key, value in values.items(): output.write(escape(key) + '=' + escape(value) + '\n')
    Path(path).chmod(0o600)


def clean_java_environment():
    # Application overrides and JVM injection must not revive background writers.
    return {key: value for key, value in os.environ.items() if key in
            {'PATH', 'JAVA_HOME', 'HOME', 'LANG', 'LC_ALL', 'TMPDIR', 'SystemRoot'}} | {'TZ': 'UTC'}


def release_identity(config):
    release = Path(config['releaseDirectory'])
    for key, name in [('consumerManifestSha256', 'consumer-manifest.json'), ('checksSha256', 'SHA256SUMS.json')]:
        require(re.fullmatch(HEX_RE, config[key]) and sha(release / name) == config[key], 'Reviewed base release anchor differs: ' + name)
    manifest, checks, baseline = validate(release, config['datasetId'], Path(config['migrationDirectory']),
        allow_small=config.get('allowSmallQualification') is True, expected_app_sha=sha(Path(config['appJar'])))
    return manifest, checks, baseline


class SourceAdapter:
    """One pinned application reader and streaming fingerprint; no database writes."""
    def __init__(self, config, work, *, runtime_output=None):
        self.config = config; self.work = Path(work); self.work.mkdir(mode=0o700)
        self.manifest, self.checks, self.base = release_identity(config)
        self.runtime = extract_runtime(Path(config['releaseDirectory']), self.work / 'runtime')
        self.host_runtime_path = Path(runtime_output) if runtime_output is not None else self.work / 'host-runtime-qualification.json'
        self.host_runtime = qualify_runtime(Path(config['releaseDirectory']), self.runtime, self.host_runtime_path,
                                            expected_checks=self.checks)
        self.java_env = qualified_environment(self.host_runtime, clean_java_environment())
        self.libs = self.runtime / 'runtime/lib'
        if config.get('etlLibDirectory'):
            libs = Path(config['etlLibDirectory'])
            require({p.name: sha(p) for p in libs.glob('*.jar')} == read(Path(config['releaseDirectory']) / 'etl-binaries.json'), 'Explicit verifier library bytes differ')
            self.libs = libs
        settings_path = self.runtime / 'tools/growth_settings.py'
        spec = importlib.util.spec_from_file_location('_growth_b_verified_settings', settings_path)
        settings = importlib.util.module_from_spec(spec); spec.loader.exec_module(settings)
        self.settings = dict(settings.BASE_SETTINGS)
        mysql = config['mysql']; raw_url = mysql['jdbcUrl']
        require(raw_url.startswith('jdbc:mysql://'), 'Single MySQL JDBC endpoint required')
        parsed = urllib.parse.urlsplit(raw_url[5:])
        query = urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)
        require(parsed.hostname and not parsed.username and not parsed.password and not parsed.fragment
                and re.fullmatch(r'/[a-z][a-z0-9_]*', parsed.path) and len(dict(query)) == len(query), 'Unsafe MySQL JDBC endpoint')
        options = dict(query)
        require(not any('password' in key.lower() or key.lower() in {'user', 'username'} for key in options), 'JDBC credentials must use private properties')
        require(parsed.hostname in {'127.0.0.1', 'localhost', '::1'} or options.get('sslMode') == 'VERIFY_IDENTITY', 'Remote MySQL requires TLS identity verification')
        self.url = settings.utc_jdbc_url(raw_url)
        base_url, _, query_string = self.url.partition('?'); values = dict(urllib.parse.parse_qsl(query_string))
        for key in ('autoReconnect', 'autoReconnectForPools', 'reconnectAtTxEnd'):
            require(values.get(key, 'false').lower() == 'false', 'Read fence connections cannot reconnect')
            values[key] = 'false'
        self.url = base_url + '?' + urllib.parse.urlencode(values)
        trust = mysql.get('tlsTrustStore')
        if trust:
            store_path = Path(trust['path'])
            require(store_path.is_absolute() and sha(store_path) == trust['sha256'] and
                    re.fullmatch(r'AIRBOB_[A-Z0-9_]+', trust['passwordEnvironment']), 'Pinned private TLS trust store required')
            base, _, query_string = self.url.partition('?'); values = dict(urllib.parse.parse_qsl(query_string))
            values.update(trustCertificateKeyStoreUrl=store_path.as_uri(), trustCertificateKeyStoreType='PKCS12',
                          trustCertificateKeyStorePassword=os.environ[trust['passwordEnvironment']], fallbackToSystemTrustStore='false')
            self.url = base + '?' + urllib.parse.urlencode(values)
        secret = mysql['passwordEnvironment']
        require(re.fullmatch(r'AIRBOB_[A-Z0-9_]+', secret) and os.environ.get(secret), 'MySQL password environment is missing')
        require(str(uuid.UUID(mysql['expectedServerUuid'])) == mysql['expectedServerUuid'], 'Reviewed MySQL server UUID is required')
        self.schema = parsed.path[1:]
        self.connection = {'url': self.url, 'username': mysql['username'], 'password': os.environ[secret]}
        self.connection_path = self.work / 'connection.properties'; properties(self.connection_path, self.connection)
        self.timeout = int(config.get('sourceTimeoutSeconds', 7200))
        require(60 <= self.timeout <= 28800, 'Source timeout must be 60..28800 seconds')
        self.cp = str(self.work) + os.pathsep + str(self.libs / '*')
        source = self.work / 'GrowthBSourceProbe.java'; source.write_text(MYSQL_PROBE)
        run([self.host_runtime['hostJavaTools']['javac']['path'], '-cp', str(self.libs / '*'), '-d', str(self.work), str(source)], environment=self.java_env)
        self.backend_cp = None; self._fence = None
        self.heartbeat_path = None
    def check_fence(self):
        require(self._fence is not None and self._fence.poll() is None, 'MySQL read fence was lost')
        nonce = uuid.uuid4().hex
        self._fence.stdin.write((nonce + '\n').encode()); self._fence.stdin.flush()
        deadline = time.monotonic() + 15
        while True:
            require(self._fence.poll() is None and time.monotonic() < deadline, 'MySQL read fence connection heartbeat failed')
            if self.heartbeat_path.is_file() and self.heartbeat_path.read_text() == nonce: return
            time.sleep(.05)
    def command(self, mode, output):
        self.java_env = qualified_environment(self.host_runtime, self.java_env)
        return [self.host_runtime['hostJavaTools']['java']['path'], '-Duser.timezone=UTC', '-Xmx512m', '-cp', self.cp,
                'org.example.growth.GrowthBSourceProbe', mode, str(self.connection_path), str(output)]
    def probe(self, mode):
        if self._fence is not None: self.check_fence()
        output = self.work / (mode + '-' + uuid.uuid4().hex + '.json')
        run(self.command(mode, output), environment=self.java_env, timeout=self.timeout)
        if self._fence is not None: self.check_fence()
        result = read(output); output.unlink(); return result
    def identity(self):
        result = self.probe('identity')
        require(result['version'] == '8.4.11' and result['serverUuid'] == self.config['mysql']['expectedServerUuid']
                and result['schema'] == self.schema, 'Live MySQL identity differs from reviewed source')
        return result
    @contextlib.contextmanager
    def fence(self):
        require(self._fence is None, 'Read fence is already held')
        ready = self.work / ('fence-' + uuid.uuid4().hex + '.json')
        self.heartbeat_path = ready.with_name(ready.name + '.heartbeat')
        process = subprocess.Popen(self.command('fence', ready), stdin=subprocess.PIPE,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=self.java_env)
        self._fence = process
        try:
            deadline = time.monotonic() + 60
            while not ready.exists():
                require(process.poll() is None and time.monotonic() < deadline, 'Unable to acquire complete MySQL read fence')
                time.sleep(.1)
            identity = self.identity()
            require(read(ready) == identity, 'Read fence source differs from verification connection')
            yield identity
            self.check_fence()
        finally:
            process.stdin.close()
            try: process.wait(timeout=15)
            except subprocess.TimeoutExpired: process.terminate(); process.wait(timeout=15)
            self._fence = None
    def prepare_backend(self):
        if self.backend_cp is not None: return
        extracted = self.work / 'backend'; extracted.mkdir(mode=0o700)
        with zipfile.ZipFile(self.config['appJar']) as archive:
            selected = [entry for entry in archive.infolist() if entry.filename.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/'))]
            require(sum(entry.file_size for entry in selected) <= 1024**3 and len(selected) <= 50000, 'Application archive exceeds bounds')
            seen = set()
            for entry in selected:
                name = Path(entry.filename)
                require(not name.is_absolute() and '..' not in name.parts and '\\' not in entry.filename
                        and entry.filename not in seen and (entry.external_attr >> 16) & 0o170000 != 0o120000, 'Unsafe application archive member')
                seen.add(entry.filename); archive.extract(entry, extracted)
        self.backend_cp = os.pathsep.join([str(extracted), str(extracted / 'BOOT-INF/classes'), str(extracted / 'BOOT-INF/lib/*')])
        source = self.work / 'GrowthBCurrentSearchProbe.java'; source.write_text(BACKEND_PROBE)
        self.java_env = qualified_environment(self.host_runtime, self.java_env)
        run([self.host_runtime['hostJavaTools']['javac']['path'], '-cp', self.backend_cp, '-d', str(extracted), str(source)], environment=self.java_env)
        self.mapping = read(extracted / 'BOOT-INF/classes/elasticsearch/accommodations-index.json')
        settings = self.settings | {'spring.flyway.enabled': 'false', 'spring.datasource.url': self.connection['url'],
            'spring.datasource.username': self.connection['username'], 'spring.datasource.password': self.connection['password'],
            'spring.datasource.hikari.maximum-pool-size': '4', 'spring.datasource.hikari.read-only': 'true',
            'spring.data.redis.host': '127.0.0.1', 'spring.data.redis.port': '1',
            'spring.data.redis.repositories.enabled': 'false', 'spring.profiles.active': 'test',
            'spring.main.web-application-type': 'none', 'spring.main.lazy-initialization': 'true',
            'server.address': '127.0.0.1', 'server.port': '0', 'logging.level.root': 'ERROR'}
        self.application_settings = self.work / 'application.properties'; properties(self.application_settings, settings)
    def documents(self, path):
        require(self._fence is not None and self._fence.poll() is None, 'Application export requires the MySQL read fence')
        self.prepare_backend()
        self.java_env = qualified_environment(self.host_runtime, self.java_env)
        run([self.host_runtime['hostJavaTools']['java']['path'], '-Duser.timezone=UTC', '-Xmx512m', '-cp', self.backend_cp, 'GrowthBCurrentSearchProbe',
             '--spring.profiles.active=test', '--spring.config.additional-location=file:' + str(self.application_settings)],
            environment=self.java_env | {'AIRBOB_SEARCH_OUTPUT': str(path)}, timeout=self.timeout, stdout=subprocess.DEVNULL, cwd=self.work)
        Path(path).chmod(0o600)
        result = file_fingerprint(path)
        require(result['documents'] == self.identity()['publishedDocuments'], 'Pinned reader omitted published MySQL documents')
        return result


def anchors(source):
    return {'datasetId': source.config['datasetId'], 'consumerManifestSha256': source.config['consumerManifestSha256'],
            'checksSha256': source.config['checksSha256'], 'appJarSha256': source.manifest['appJarSha256']}


def capture_baseline(config, output):
    """Call before credentials/FREE preparation; writes an actual full-row proof."""
    output = Path(output); output.mkdir(mode=0o700)
    with tempfile.TemporaryDirectory(prefix='growth-b-source-') as work:
        source = SourceAdapter(config, Path(work) / 'source', runtime_output=Path(str(output) + '.host-runtime.json'))
        with source.fence() as identity:
            observed = source.probe('fingerprint')
            require(observed == source.base, 'Baseline differs from sealed complete rows/DDL before preparation')
            owned = source.probe('owned'); verify_prepared(source.base, observed, owned)
            write(output / 'mysql-baseline-fingerprint.json', observed)
            receipt = {'schemaVersion': 1, 'state': 'BASELINE_VERIFIED_BEFORE_PREPARATION', **anchors(source),
                'hostRuntimeQualification': qualification_binding(source.host_runtime_path),
                'mysql': identity, 'fingerprint': file_binding(output / 'mysql-baseline-fingerprint.json'),
                'verifiedAllColumnsAndDdl': True, 'sourceReadFence': 'all existing base tables READ',
                'capturedAt': dt.datetime.now(dt.timezone.utc).isoformat()}
            write(output / 'baseline-receipt.json', receipt)
    return receipt


def verify_baseline(source, baseline, identity):
    baseline = Path(baseline); receipt = read(baseline / 'baseline-receipt.json')
    require(receipt['schemaVersion'] == 1 and receipt['state'] == 'BASELINE_VERIFIED_BEFORE_PREPARATION'
            and receipt['verifiedAllColumnsAndDdl'] is True and all(receipt[k] == v for k, v in anchors(source).items()), 'Baseline receipt release binding differs')
    require(receipt['mysql'] == identity and receipt['fingerprint'] == file_binding(baseline / 'mysql-baseline-fingerprint.json')
            and read(baseline / 'mysql-baseline-fingerprint.json') == source.base, 'Baseline source UUID or complete bytes differ')
    return receipt


def prepared_source(source, baseline, identity):
    receipt = verify_baseline(source, baseline, identity)
    current = source.probe('fingerprint'); owned = source.probe('owned')
    proof = verify_prepared(source.base, current, owned)
    return current, {'baselineReceiptSha256': sha(Path(baseline) / 'baseline-receipt.json'),
                     'baselineVerifiedBeforePreparation': receipt['verifiedAllColumnsAndDdl'],
                     'preparedAllowedChanges': proof, 'ownedInventory': owned}


def validate_snapshot(info, snapshot, index, expected_uuid=None):
    require(info['snapshot'] == snapshot and info['state'] == 'SUCCESS' and info['indices'] == [index]
            and not info.get('failures') and info['shards']['failed'] == 0
            and info['shards']['successful'] == info['shards']['total'] > 0
            and not info.get('include_global_state') and not info.get('feature_states'), 'Native snapshot is not a complete isolated index snapshot')
    require(str(info['uuid']) and (expected_uuid is None or info['uuid'] == expected_uuid), 'Native snapshot UUID differs')
    require(info['version'] == SNAPSHOT_METADATA_VERSION and info['version_id'] == SNAPSHOT_METADATA_VERSION_ID,
            'Snapshot native format version differs from the pinned producer')
    return info


def snapshot_info(es, repository, snapshot):
    result = es.api('GET', '/_snapshot/' + repository.name + '/' + snapshot)['snapshots']
    require(len(result) == 1, 'Exactly one native snapshot is required')
    return result[0]


def create_snapshot(es, repository, snapshot, index, timeout):
    require(es.api('PUT', '/_snapshot/' + repository.name + '/' + snapshot,
        {'indices': index, 'include_global_state': False, 'feature_states': ['none'], 'partial': False}).get('accepted') is True,
        'Native snapshot was not accepted')
    end = time.monotonic() + timeout
    while True:
        info = snapshot_info(es, repository, snapshot)
        if info['state'] != 'IN_PROGRESS': return validate_snapshot(info, snapshot, index)
        require(time.monotonic() < end, 'Native snapshot completion timed out')
        time.sleep(2)


def restore_index(es, repository, reference, target, timeout):
    require(re.fullmatch(INDEX_RE, target) and es.optional('/' + target) is None, 'Restore target must be a new versioned index')
    # Never restore aliases or global state, and quarantine writes until equality.
    result = es.api('POST', '/_snapshot/' + repository.name + '/' + reference['snapshot'] + '/_restore',
        {'indices': reference['snapshotIndex'], 'rename_pattern': '^' + re.escape(reference['snapshotIndex']) + '$',
         'rename_replacement': target, 'include_aliases': False, 'include_global_state': False,
         'feature_states': ['none'], 'partial': False,
         'index_settings': {'index.number_of_replicas': 0, 'index.blocks.write': True}})
    require(result.get('accepted') is True, 'Native restore was not accepted')
    end = time.monotonic() + timeout
    while True:
        require(time.monotonic() < end, 'Native restore recovery timed out')
        try:
            health = es.api('GET', '/_cluster/health/' + target + '?wait_for_status=green&wait_for_active_shards=all&wait_for_no_initializing_shards=true&timeout=2s')
        except urllib.error.HTTPError as error:
            if error.code != 408: raise
            # ES 8.18.8 ClusterHealthResponse.status() returns 408 for a timed-out
            # health wait. This exception is limited to this GET, never other APIs.
            with error: raw = error.read(64 * 1024 + 1)
            require(len(raw) <= 64 * 1024, 'Restore health timeout response exceeds bound')
            try: health = json.loads(raw)
            except (ValueError, UnicodeError): raise ValueError('Malformed restore health timeout response') from None
            require(isinstance(health, dict) and health.get('timed_out') is True, 'HTTP 408 is not an explicit restore health wait timeout')
        require(isinstance(health, dict) and 'error' not in health
                and isinstance(health.get('cluster_name'), str) and health['cluster_name']
                and type(health.get('timed_out')) is bool and health.get('status') in {'red', 'yellow', 'green'}
                and all(type(health.get(key)) is int and health[key] >= 0 for key in (
                    'number_of_nodes', 'number_of_data_nodes', 'active_primary_shards', 'active_shards',
                    'relocating_shards', 'initializing_shards', 'unassigned_shards')),
                'Restore health response is incomplete or invalid')
        remaining = end - time.monotonic()
        require(remaining > 0, 'Native restore recovery timed out')
        if (health['timed_out'] is False and health['status'] == 'green'
                and health['active_primary_shards'] > 0
                and health['unassigned_shards'] == 0 and health['initializing_shards'] == 0):
            break
        time.sleep(min(2, remaining))
    observed = es.api('GET', '/' + target + '/_settings')[target]['settings']['index']
    require(str(observed.get('blocks', {}).get('write')).lower() == 'true', 'Restored index must remain write blocked during verification')
    require(not es.api('GET', '/' + target + '/_alias')[target]['aliases'], 'Native restore unexpectedly installed aliases')


def snapshot_timeout(config):
    seconds = int(config.get('snapshotTimeoutSeconds', 7200))
    require(60 <= seconds <= 28800, 'Snapshot timeout must be 60..28800 seconds')
    return seconds


def new_index(label): return 'accommodations-v' + label + '-' + uuid.uuid4().hex[:20]


def repository_for(config):
    settings = config['repository']
    return Repository(settings, aws_cli(settings['aws']) if settings['type'] == 's3' else None)


def disk_gate(es, expected_additional_bytes):
    safety = int(es.config.get('diskSafetyBytes', 1024**3))
    require(safety >= 1024**3 and expected_additional_bytes >= 0, 'ES requires at least one GiB retained safety margin')
    nodes = es.api('GET', '/_nodes/stats/fs')['nodes']
    require(len(nodes) == 1, 'B native snapshot qualification requires one pinned ES node')
    total = next(iter(nodes.values()))['fs']['total']
    available = total['available_in_bytes']; needed = math.ceil(expected_additional_bytes * 1.5) + safety
    require(type(available) is int and available >= needed, 'ES node lacks measured space for the additional index and retained safety margin')
    return {'availableBytes': available, 'expectedAdditionalStoreBytes': expected_additional_bytes,
            'requiredAvailableBytes': needed, 'retainedSafetyBytes': safety, 'allocationMultiplier': 1.5,
            'existingIndicesRetained': True}


def same_repository(repository, reference):
    expected = reference['repository']; actual = repository.binding()
    require(expected['type'] == actual['type'], 'Native repository type differs')
    if actual['type'] == 's3': require(actual == expected, 'S3 bucket/base path differs from sealed native repository')
    else: require(expected.get('layout') == actual['layout'], 'Native filesystem layout differs')


def source_drift_check(source, identity, before, projection, work):
    require(source.identity() == identity, 'MySQL identity or publication count drifted')
    path = Path(work) / 'documents-after.jsonl'
    first = Path(work) / 'current-documents.jsonl'
    require(shutil.disk_usage(work).free >= first.stat().st_size + 1024**3,
            'Source host lacks space for a bounded second projection file and safety margin')
    after = source.documents(path)
    require(after == projection, 'Current application search projection drifted during snapshot operation')
    path.unlink()
    require(source.probe('fingerprint') == before, 'MySQL complete rows/DDL drifted during snapshot operation')
    return {'mysqlUuidUnchanged': True, 'completeRowsAndDdlUnchanged': True,
            'allCurrentDocumentFieldsUnchanged': True}


def descriptor_for(directory, manifest):
    return {'schemaVersion': 1, 'datasetId': manifest['datasetId'], 'snapshotRelease': manifest['snapshotRelease'],
            'consumerManifestSha256': manifest['consumerManifestSha256'], 'appJarSha256': manifest['appJarSha256'],
            'objects': {name: file_binding(Path(directory) / name) for name in sorted(NAMES)}}


def produce(config, baseline, output, *, build_index=False):
    """Create a new native companion, including a real round-trip from pinned MySQL."""
    output = Path(output); output.mkdir(mode=0o700)
    snapshot = config['snapshotRelease']
    require(re.fullmatch(re.escape(config['datasetId']) + r'-search-[a-z0-9][a-z0-9._-]{0,60}', snapshot), 'Snapshot release must be scoped to the exact B dataset')
    registered = False; blocked = False; previous_block = None; source_index = None; temporary_index = None
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix='growth-b-search-') as work:
        source = SourceAdapter(config, Path(work) / 'source', runtime_output=Path(str(output) + '.host-runtime.json'))
        es = Elasticsearch(config['elasticsearch']); identity = es.identity(); repository = repository_for(config)
        if repository.config['type'] == 's3':
            require(repository.settings['base_path'] == 'elasticsearch/releases/' + snapshot, 'Writer S3 prefix must exactly equal the selected new companion release')
        require(repository.inventory()['entries'] == [], 'Producer requires a completely empty new native repository')
        try:
            with source.fence() as mysql:
                prepared, preparation = prepared_source(source, baseline, mysql)
                documents = Path(work) / 'current-documents.jsonl'; projection = source.documents(documents)
                if build_index:
                    disk_gate(es, documents.stat().st_size * 3)
                    source_index = config.get('sourceIndex', new_index('source'))
                    require(re.fullmatch(INDEX_RE, source_index), 'Invalid producer source index')
                    es.create_from_source(source_index, documents, source.mapping)
                else: source_index = es.alias()
                index_settings = es.api('GET', '/' + source_index + '/_settings')[source_index]['settings']['index']
                previous_block = index_settings.get('blocks', {}).get('write')
                require(es.api('PUT', '/' + source_index + '/_settings', {'index.blocks.write': True}).get('acknowledged'), 'Source index write fence failed')
                blocked = True
                es.api('POST', '/' + source_index + '/_refresh')
                original = es.fingerprint(source_index, documents)
                require(original['mappingSha256'] == digest(source.mapping['mappings'])
                        and original['indexSemanticsSha256'] == digest(index_semantics(source.mapping['settings'])),
                        'Source mapping/analyzers differ from pinned application')
                source_bytes = es.api('GET', '/' + source_index + '/_stats/store')['_all']['total']['store']['size_in_bytes']
                space = disk_gate(es, source_bytes * (2 if repository.config['type'] == 'fs' else 1))
                repository.register(es, False); registered = True
                info = create_snapshot(es, repository, snapshot, source_index, snapshot_timeout(config))
                repository.unregister(es); registered = False
                repository.register(es, True); registered = True
                inventory = repository.inventory()
                reference = {'schemaVersion': 1, **anchors(source), 'snapshotRelease': snapshot,
                    'logicalAlias': ALIAS, 'elasticsearch': identity, 'repository': repository.binding(),
                    'snapshot': snapshot, 'snapshotUuid': info['uuid'], 'snapshotIndex': source_index,
                    'snapshotMetadataVersion': info['version'], 'snapshotMetadataVersionId': info['version_id'],
                    'fingerprint': original, 'nativeInventorySha256': digest(inventory)}
                validate_snapshot(snapshot_info(es, repository, snapshot), snapshot, source_index, info['uuid'])
                temporary_index = new_index('verify')
                restore_index(es, repository, reference, temporary_index, snapshot_timeout(config))
                restored = es.fingerprint(temporary_index, documents)
                require(restored == original and es.alias() == source_index, 'Native round-trip or producer alias changed')
                drift = source_drift_check(source, mysql, prepared, projection, work)
                require(es.fingerprint(source_index, documents) == original and es.alias() == source_index
                        and es.identity() == identity and repository.inventory() == inventory,
                        'Producer ES identity, source, alias or immutable native inventory drifted')
                stores = es.api('GET', '/' + source_index + ',' + temporary_index + '/_stats/store')['_all']['total']['store']['size_in_bytes']
                require(es.api('DELETE', '/' + temporary_index).get('acknowledged'), 'Owned temporary restore cleanup failed')
                temporary_index = None
                repository.unregister(es); registered = False
                require(es.api('PUT', '/' + source_index + '/_settings', {'index.blocks.write': previous_block}).get('acknowledged'), 'Producer write-fence cleanup failed')
                blocked = False
                proof = {'schemaVersion': 1, 'state': 'PINNED_MYSQL_SOURCE_VERIFIED', **anchors(source),
                    'hostRuntimeQualification': qualification_binding(source.host_runtime_path),
                    'baseDumpSha256': source.checks['airbob-growth.sql.gz'], 'mysql': mysql, **preparation,
                    'reader': 'pinned Airbob AccommodationSearchSnapshotReader + ElasticsearchConverter',
                    'projection': projection, 'everyElasticsearchSourceFieldCompared': True, 'sourceDrift': drift,
                    'fence': 'all existing MySQL base tables READ plus source index.blocks.write',
                    'baselineFingerprintSha256': digest(source.base), 'preparedFingerprintSha256': digest(prepared)}
                receipt = {'schemaVersion': 1, 'state': 'NATIVE_SNAPSHOT_PRODUCED_AND_RESTORED', **anchors(source),
                    'hostRuntimeQualification': qualification_binding(source.host_runtime_path),
                    'snapshotRelease': snapshot, 'snapshotUuid': info['uuid'], 'sourceFingerprint': original,
                    'restoredFingerprint': restored, 'temporaryRestoreDeleted': True, 'repositoryRegistrationRemoved': True,
                    'repositoryUnchangedAfterReadOnlyRestore': True, 'durationSeconds': round(time.monotonic() - started, 3),
                    'storage': {'measuredSourcePlusTemporaryRestoreStoreBytes': stores, 'diskGate': space,
                                'sourceJsonlBytes': documents.stat().st_size,
                                'nativeRepositoryBytes': sum(item.get('bytes', 0) for item in inventory['entries'])}}
                payloads = {'source-proof.json': proof, 'snapshot-reference.json': reference,
                    'snapshot-producer-receipt.json': receipt, 'native-inventory.json': inventory,
                    'mysql-baseline-fingerprint.json': source.base, 'mysql-prepared-fingerprint.json': prepared}
                for name, value in payloads.items(): write(output / name, value)
                write(output / 'snapshot-seal.json', {'schemaVersion': 1, 'state': 'SEALED_AFTER_NATIVE_ROUND_TRIP',
                    **anchors(source), 'snapshotRelease': snapshot,
                    'artifacts': {name: file_binding(output / name) for name in sorted(payloads)}})
                manifest = {'schemaVersion': 1, 'kind': 'airbob-global-growth-b-native-search-companion', **anchors(source),
                    'snapshotRelease': snapshot, 'snapshotUuid': info['uuid'], 'fullDocumentFingerprint': original,
                    'artifacts': {name: file_binding(output / name) for name in sorted(NAMES - {'manifest.json'})}}
                write(output / 'manifest.json', manifest)
                descriptor = descriptor_for(output, manifest)
                validate_companion(output, descriptor)
                return descriptor
        finally:
            # Only this run's unique temporary index may be deleted. Old service
            # indices and native repository data are deliberately never deleted.
            if temporary_index is not None: es.api('DELETE', '/' + temporary_index)
            if registered: repository.unregister(es)
            if blocked: es.api('PUT', '/' + source_index + '/_settings', {'index.blocks.write': previous_block})


def validate_descriptor(descriptor, *, remote=False):
    require(descriptor['schemaVersion'] == 1 and re.fullmatch(r'global-growth-b-[0-9a-f]{16}', descriptor['datasetId'])
            and re.fullmatch(re.escape(descriptor['datasetId']) + r'-search-[a-z0-9][a-z0-9._-]{0,60}', descriptor['snapshotRelease']), 'Invalid B companion identity')
    require(all(re.fullmatch(HEX_RE, descriptor[key]) for key in ('consumerManifestSha256', 'appJarSha256'))
            and set(descriptor['objects']) == NAMES, 'Exact companion artifact identity is required')
    for name, item in descriptor['objects'].items():
        require(re.fullmatch(HEX_RE, item['sha256']) and type(item['bytes']) is int and 0 < item['bytes'] <= 64 * 1024**2, 'Invalid bounded companion artifact binding')
        coordinates = {'bucket', 'key', 'versionId'}
        if remote or coordinates & set(item):
            require(coordinates <= set(item) and re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,62}', item['bucket'])
                    and item['key'].endswith('/' + name) and not item['key'].startswith('/') and '..' not in item['key'].split('/')
                    and isinstance(item['versionId'], str) and item['versionId'] not in {'', 'null', 'None'}, 'Exact versioned companion object coordinates required')


def validate_companion(directory, descriptor):
    """Verify all descriptor-pinned files and cross-bind generated evidence."""
    directory = Path(directory); validate_descriptor(descriptor)
    require(directory.is_dir() and not directory.is_symlink() and {p.name for p in directory.iterdir()} == NAMES, 'Exact companion file inventory required')
    for name, expected in descriptor['objects'].items():
        require(file_binding(directory / name) == {key: expected[key] for key in ('sha256', 'bytes')}, 'Companion bytes differ: ' + name)
    manifest = read(directory / 'manifest.json'); reference = read(directory / 'snapshot-reference.json')
    proof = read(directory / 'source-proof.json'); receipt = read(directory / 'snapshot-producer-receipt.json')
    seal = read(directory / 'snapshot-seal.json'); inventory = read(directory / 'native-inventory.json')
    require(manifest['schemaVersion'] == 1 and manifest['kind'] == 'airbob-global-growth-b-native-search-companion', 'Unsupported companion schema')
    for value in (manifest, reference, proof, receipt, seal):
        require(value['schemaVersion'] == 1 and all(value[key] == descriptor[key] for key in
            ('datasetId', 'consumerManifestSha256', 'appJarSha256')) and value['checksSha256'] == manifest['checksSha256'], 'Companion base release binding differs')
    for value in (manifest, reference, receipt, seal):
        require(value['snapshotRelease'] == descriptor['snapshotRelease'], 'Companion snapshot release differs')
    require(manifest['artifacts'] == {name: file_binding(directory / name) for name in sorted(NAMES - {'manifest.json'})}
            and seal['state'] == 'SEALED_AFTER_NATIVE_ROUND_TRIP' and seal['artifacts'] == {
                name: file_binding(directory / name) for name in sorted(NAMES - {'manifest.json', 'snapshot-seal.json'})}, 'Companion seal differs')
    fingerprint = reference['fingerprint']
    require(fingerprint['algorithm'] == ALGORITHM and type(fingerprint['documents']) is int and fingerprint['documents'] > 0
            and all(re.fullmatch(HEX_RE, fingerprint[key]) for key in ('contentSha256', 'identityPairsSha256', 'mappingSha256', 'indexSemanticsSha256'))
            and manifest['fullDocumentFingerprint'] == receipt['sourceFingerprint'] == receipt['restoredFingerprint'] == fingerprint,
            'Native full-document equality proof differs')
    require(reference['logicalAlias'] == ALIAS and re.fullmatch(INDEX_RE, reference['snapshotIndex'])
            and reference['snapshot'] == descriptor['snapshotRelease']
            and reference['snapshotUuid'] == manifest['snapshotUuid'] == receipt['snapshotUuid']
            and reference['snapshotMetadataVersion'] == SNAPSHOT_METADATA_VERSION
            and reference['snapshotMetadataVersionId'] == SNAPSHOT_METADATA_VERSION_ID
            and reference['elasticsearch']['version'] == '8.18.8'
            and re.fullmatch(r'sha256:' + HEX_RE, reference['elasticsearch']['imageId']), 'Pinned snapshot identity differs')
    validate_runtime_identity(reference['elasticsearch'])
    require(proof['state'] == 'PINNED_MYSQL_SOURCE_VERIFIED' and proof['baselineVerifiedBeforePreparation'] is True
            and re.fullmatch(HEX_RE, proof['baseDumpSha256'])
            and 'global-growth-b-' + proof['baseDumpSha256'][:16] == descriptor['datasetId']
            and proof['everyElasticsearchSourceFieldCompared'] is True
            and proof['sourceDrift'] == {'mysqlUuidUnchanged': True, 'completeRowsAndDdlUnchanged': True, 'allCurrentDocumentFieldsUnchanged': True}
            and proof['projection'] == document_fingerprint(fingerprint)
            and proof['mysql']['publishedDocuments'] == fingerprint['documents'] and proof['mysql']['version'] == '8.4.11', 'Actual source reader/drift evidence is missing')
    require(str(uuid.UUID(proof['mysql']['serverUuid'])) == proof['mysql']['serverUuid'], 'Source MySQL UUID is invalid')
    require(receipt['state'] == 'NATIVE_SNAPSHOT_PRODUCED_AND_RESTORED' and receipt['temporaryRestoreDeleted'] is True
            and receipt['repositoryRegistrationRemoved'] is True and receipt['repositoryUnchangedAfterReadOnlyRestore'] is True, 'Native restore/cleanup evidence missing')
    base = read(directory / 'mysql-baseline-fingerprint.json'); prepared = read(directory / 'mysql-prepared-fingerprint.json')
    validate_fingerprint(base); validate_fingerprint(prepared)
    require(proof['baselineFingerprintSha256'] == digest(base) and proof['preparedFingerprintSha256'] == digest(prepared)
            and proof['preparedAllowedChanges'] == verify_prepared(base, prepared, proof['ownedInventory']), 'Prepared source lineage differs')
    require(reference['nativeInventorySha256'] == digest(inventory) and inventory['entries']
            and inventory['type'] == reference['repository']['type'], 'Native inventory binding differs')
    if inventory['type'] == 's3':
        require(inventory['bucket'] == reference['repository']['bucket'] and inventory['basePath'] == reference['repository']['basePath'], 'Native S3 coordinates differ')
        require(all(entry['versionId'] not in {'', 'null', 'None'} and entry['key'].startswith(inventory['basePath'] + '/') for entry in inventory['entries']), 'Native object versions are missing')
    else: require(inventory['type'] == 'fs' and all(not Path(entry['key']).is_absolute() and '..' not in Path(entry['key']).parts
                and re.fullmatch(HEX_RE, entry['sha256']) for entry in inventory['entries']), 'Unsafe filesystem native inventory')
    return manifest, reference


def fetch_companion(descriptor, output, aws_get_object):
    """Download exact versions. Callback(item, target) returns AWS get-object metadata."""
    validate_descriptor(descriptor, remote=True)
    output = Path(output); output.mkdir(mode=0o700)
    for name, item in sorted(descriptor['objects'].items()):
        result = aws_get_object(item, output / name)
        require(result.get('VersionId') == item['versionId'] and result.get('ContentLength') == item['bytes'], 'Downloaded companion version/length differs')
        (output / name).chmod(0o600)
    validate_companion(output, descriptor)
    return output


def restore(config, companion, descriptor, baseline, output, *, activate_alias=False):
    """Restore only to a fresh index; activation follows all source and drift checks."""
    manifest, reference = validate_companion(companion, descriptor)
    output = Path(output); output.mkdir(mode=0o700)
    target = config.get('targetIndex', new_index('restore')); registered = False
    with tempfile.TemporaryDirectory(prefix='growth-b-search-restore-') as work:
        source = SourceAdapter(config, Path(work) / 'source', runtime_output=Path(str(output) + '.host-runtime.json'))
        es = Elasticsearch(config['elasticsearch']); identity = es.identity(); previous = es.alias(optional=True)
        compatibility = runtime_compatibility(reference['elasticsearch'], identity)
        repository = repository_for(config); same_repository(repository, reference)
        native = read(Path(companion) / 'native-inventory.json')
        require(repository.inventory() == native, 'Native repository versions/files differ before restore')
        producer = read(Path(companion) / 'snapshot-producer-receipt.json')
        # Credit no space from existing indices; retain the measured two-index bound.
        space = disk_gate(es, producer['storage']['measuredSourcePlusTemporaryRestoreStoreBytes'])
        require(all(manifest[k] == v for k, v in anchors(source).items())
                and read(Path(companion) / 'mysql-baseline-fingerprint.json') == source.base
                and read(Path(companion) / 'source-proof.json')['baseDumpSha256'] == source.checks['airbob-growth.sql.gz'],
                'Companion does not belong to the selected sealed base release')
        try:
            with source.fence() as mysql:
                prepared, preparation = prepared_source(source, baseline, mysql)
                documents = Path(work) / 'current-documents.jsonl'; projection = source.documents(documents)
                require(projection == document_fingerprint(reference['fingerprint']), 'Current MySQL projection differs from this native snapshot; produce a fresh companion')
                require(reference['fingerprint']['mappingSha256'] == digest(source.mapping['mappings'])
                        and reference['fingerprint']['indexSemanticsSha256'] == digest(index_semantics(source.mapping['settings'])),
                        'Companion mapping/analyzers differ from pinned application')
                repository.register(es, True); registered = True
                validate_snapshot(snapshot_info(es, repository, reference['snapshot']), reference['snapshot'], reference['snapshotIndex'], reference['snapshotUuid'])
                restore_index(es, repository, reference, target, snapshot_timeout(config))
                actual = es.fingerprint(target, documents)
                require(actual == reference['fingerprint'], 'Restored full ES source differs from sealed snapshot')
                drift = source_drift_check(source, mysql, prepared, projection, work)
                require(es.identity() == identity and es.alias(optional=True) == previous and repository.inventory() == native,
                        'Restore identity, alias or native inventory drifted')
                repository.unregister(es); registered = False
                if activate_alias:
                    require(es.api('PUT', '/' + target + '/_settings', {'index.blocks.write': None}).get('acknowledged'), 'Verified index write enable failed')
                    actions = ([] if previous is None else [{'remove': {'index': previous, 'alias': ALIAS, 'must_exist': True}}])
                    actions.append({'add': {'index': target, 'alias': ALIAS, 'is_write_index': True}})
                    require(es.api('POST', '/_aliases', {'actions': actions}).get('acknowledged') and es.alias() == target, 'Atomic alias activation failed')
                receipt = {'schemaVersion': 1, 'state': 'SEARCH_RESTORED_AND_ACTIVATED' if activate_alias else 'SEARCH_VERIFIED_NOT_ACTIVATED',
                    'hostRuntimeQualification': qualification_binding(source.host_runtime_path),
                    **anchors(source), 'snapshotRelease': reference['snapshotRelease'], 'snapshotUuid': reference['snapshotUuid'],
                    'companionManifestSha256': sha(Path(companion) / 'manifest.json'), 'mysql': mysql,
                    'baselineReceiptSha256': preparation['baselineReceiptSha256'], 'preparedAllowedChanges': preparation['preparedAllowedChanges'],
                    'fullDocumentFingerprint': actual, 'allDocumentSourceFieldsEqual': True, 'sourceDrift': drift,
                    'elasticsearch': identity, 'runtimeCompatibility': compatibility,
                    'restoredIndex': target, 'previousIndexRetained': previous,
                    'activeAlias': target if activate_alias else previous, 'repositoryReadOnly': True,
                    'nativeInventoryUnchanged': True, 'repositoryRegistrationRemoved': True, 'diskGate': space}
                write(output / 'search-restore-receipt.json', receipt)
                return receipt
        finally:
            if registered: repository.unregister(es)
            # A failed restored index remains quarantined for diagnosis; an old
            # index is never deleted and no alias is switched before equality.


def cli_producer(argv=None):
    parser = argparse.ArgumentParser(description='Create a native B search companion using pinned MySQL/application evidence.')
    commands = parser.add_subparsers(dest='command', required=True)
    before = commands.add_parser('capture-baseline'); before.add_argument('--config', type=Path, required=True); before.add_argument('--output', type=Path, required=True)
    create = commands.add_parser('produce'); create.add_argument('--config', type=Path, required=True); create.add_argument('--baseline', type=Path, required=True)
    create.add_argument('--output', type=Path, required=True); create.add_argument('--descriptor-output', type=Path, required=True)
    create.add_argument('--build-index', action='store_true', help='Create a source index only when both index and accommodations alias are absent.')
    args = parser.parse_args(argv); config = read(args.config)
    if args.command == 'capture-baseline': result = capture_baseline(config, args.output)
    else:
        result = produce(config, args.baseline, args.output, build_index=args.build_index)
        write(args.descriptor_output, result)
    print(json.dumps({'state': result.get('state', 'NATIVE_COMPANION_CREATED'), 'datasetId': result['datasetId']}))


def cli_restore(argv=None):
    parser = argparse.ArgumentParser(description='Verify/restore a sealed native B search companion into a fresh versioned index.')
    for key in ('config', 'companion', 'descriptor', 'baseline', 'output'): parser.add_argument('--' + key, type=Path, required=True)
    parser.add_argument('--activate-alias', action='store_true')
    args = parser.parse_args(argv)
    receipt = restore(read(args.config), args.companion, read(args.descriptor), args.baseline, args.output, activate_alias=args.activate_alias)
    print(json.dumps({'state': receipt['state'], 'datasetId': receipt['datasetId'], 'restoredIndex': receipt['restoredIndex']}))
