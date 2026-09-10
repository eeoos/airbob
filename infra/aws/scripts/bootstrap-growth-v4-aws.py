#!/usr/bin/env python3
"""Restore existing V28 source bytes on the fenced private RDS data host."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import signal
import subprocess
import tempfile
import time
import urllib.request
import growth_v4_aws_contract as contract


def execute(args, *, env=None, output=None, timeout=300, input=None):
    process = subprocess.Popen(args, env=env, stdin=subprocess.PIPE if input is not None else subprocess.DEVNULL,
        stdout=output if output is not None else subprocess.PIPE,
        stderr=output if output is not None else subprocess.PIPE, start_new_session=True)
    try:
        stdout, _ = process.communicate(input=input, timeout=timeout)
        if process.returncode:
            raise RuntimeError('V4 subprocess failed: ' + Path(args[0]).name + ', exit ' + str(process.returncode))
        return stdout or b''
    except BaseException:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=10)
        raise
    finally:
        for stream in (process.stdin, process.stdout, process.stderr):
            if stream is not None:
                stream.close()


def aws(*args):
    return json.loads(execute(['aws', '--region', contract.REGION, '--no-cli-pager', '--output', 'json',
        '--cli-connect-timeout', '5', '--cli-read-timeout', '120', *args], timeout=900) or b'{}')


def prepare_tls(private):
    ca = private / 'rds-ca.pem'
    with urllib.request.urlopen('https://truststore.pki.rds.amazonaws.com/ap-northeast-2/ap-northeast-2-bundle.pem', timeout=30) as response:
        data = response.read(1_000_001)
    certificates = re.findall(rb'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----', data, re.S)
    contract.require(len(data) <= 1_000_000 and 1 <= len(certificates) <= 10, 'Invalid RDS CA bundle')
    ca.write_bytes(data)
    truststore = private / 'rds-trust.p12'
    for number, certificate in enumerate(certificates):
        part = private / ('ca-' + str(number) + '.pem')
        part.write_bytes(certificate)
        # Public CA certificates only; this store contains no private key.
        execute(['keytool', '-importcert', '-noprompt', '-alias', 'rds-' + str(number), '-file', str(part),
            '-keystore', str(truststore), '-storetype', 'PKCS12', '-storepass', 'public-ca-store'])
    return ca, truststore


def publish_evidence(path, bucket, key):
    result = aws('s3api', 'put-object', '--bucket', bucket, '--key', key, '--body', str(path), '--if-none-match', '*',
        '--tagging', 'Retention=summary', '--content-type', 'application/json', '--server-side-encryption', 'AES256')
    version = result.get('VersionId')
    contract.require(version not in [None, '', 'null', 'None'], 'Evidence must have an S3 version')
    with tempfile.TemporaryDirectory(prefix='growth-v4-evidence-') as directory:
        copy = Path(directory) / 'readback'
        result = aws('s3api', 'get-object', '--bucket', bucket, '--key', key, '--version-id', version, str(copy))
        contract.require(result.get('VersionId') == version and contract.sha(copy) == contract.sha(path), 'Evidence readback mismatch')
    return version


def publish_checkpoint(work, stage, proof):
    env = os.environ
    checkpoint = {'schemaVersion': 1, 'kind': 'growth-v4-preparation-checkpoint',
        'stage': stage, 'qualificationComplete': False, 'runId': env['AIRBOB_RUN_ID'],
        'rdsResourceId': env['AIRBOB_RDS_RESOURCE_ID'],
        'manifestSha256': env['AIRBOB_DATASET_MANIFEST_SHA256'], 'proof': proof}
    path = work / ('growth-v4-' + stage + '-checkpoint.json')
    path.write_text(json.dumps(checkpoint, indent=2) + '\n')
    publish_evidence(path, env['AIRBOB_EVIDENCE_BUCKET'], 'data-bootstrap/' + env['AIRBOB_RUN_ID'] + '/' + path.name)
    print(json.dumps({'stage': stage, 'qualificationComplete': False,
        'importSeconds': proof['importSeconds'], 'verificationSeconds': proof['verificationSeconds'],
        'tableCount': proof['tableCount'], 'totalRows': proof['totalRows']}), flush=True)


def write_client(private, connection):
    options = {'host': connection['host'], 'port': str(connection.get('port', 3306)),
        'user': connection['username'], 'password': connection['password'],
        'default-character-set': 'utf8mb4', 'max-allowed-packet': '256M'}
    if connection.get('ca'):
        options.update(ssl='true', **{'ssl-verify-server-cert': 'true', 'ssl-ca': str(connection['ca'])})
    else:
        contract.require(connection['host'] in ['127.0.0.1', 'localhost'], 'Remote RDS requires verified TLS')
    def quoted(value):
        contract.require(not any(c in value for c in ['\n', '\r', '\x00']), 'Unsafe client setting')
        return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'
    path = private / 'client.cnf'
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as target:
        target.write('[client]\n' + ''.join(k + '=' + quoted(v) + '\n' for k, v in options.items()))
    return path


def fingerprint(release, runtime, output, connection, timeout):
    url = 'jdbc:mysql://' + connection['host'] + ':' + str(connection.get('port', 3306)) + '/airbobdb?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
    if connection.get('truststore'):
        url += '&sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreType=PKCS12&trustCertificateKeyStorePassword=public-ca-store&trustCertificateKeyStoreUrl=file:' + str(connection['truststore'])
    else:
        contract.require(connection['host'] in ['127.0.0.1', 'localhost'], 'Remote fingerprint requires verified TLS')
        url += '&sslMode=DISABLED&allowPublicKeyRetrieval=true'
    env = dict(os.environ, AIRBOB_ETL_DB_URL=url, AIRBOB_ETL_DB_USER=connection['username'],
        AIRBOB_ETL_DB_PASSWORD=connection['password'], AIRBOB_ETL_BACKEND_ROOT=str(runtime / 'backend'),
        JAVA_OPTS='-Xmx512m -Duser.timezone=UTC')
    with output.with_suffix('.log').open('wb') as log:
        execute([str(runtime / 'runtime/bin/etl'), '--growth-profile=' + str(release / 'profile.json'),
            '--growth-command=fingerprint', '--growth-output=' + str(output), '--service-schema=airbobdb'],
            env=env, output=log, timeout=timeout)
    actual = contract.read(output)
    contract.require(actual == contract.read(release / 'before-fingerprint.json'), 'All-row or DDL parity failed')
    return actual


def restore_and_verify(release, manifest, runtime, private, output, connection, mysql_client='mysql'):
    contract.validate_directory(release, manifest, runtime / 'backend/src/main/resources/db/migration')
    output.mkdir(mode=0o700)
    defaults = write_client(private, connection)
    client = [mysql_client, '--defaults-extra-file=' + str(defaults), '--batch', '--raw', '--skip-column-names', 'airbobdb']
    def sql(query):
        return execute(client, input=query.encode(), timeout=30).decode().strip()
    contract.require(sql('SELECT VERSION()') == '8.4.11', 'Target must be MySQL 8.4.11')
    contract.require(sql('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()') == '0', 'Target must be empty; existing data is never dropped')
    uuid = sql('SELECT @@server_uuid')
    small = manifest['datasetScale'] == 'small-qualification'
    import_timeout, verify_timeout = (300, 300) if small else (7200, 3600)
    receipt = {'state': 'IMPORTING', 'datasetId': manifest['source']['datasetId'], 'mysqlServerUuid': uuid,
        'dumpSha256': manifest['mysql']['dumpSha256'], 'importDeadlineSeconds': import_timeout,
        'verificationDeadlineSeconds': verify_timeout}
    started = time.monotonic()
    try:
        # Streaming avoids a second 6 GB plain-text dump on the host. Killing
        # the process group terminates both gzip and mysql after any deadline.
        pipeline = 'gzip -dc -- ' + shlex.quote(str(release / 'airbob-growth.sql.gz')) + ' | ' + shlex.join(client)
        with (output / 'import.log').open('wb') as log:
            print(json.dumps({'stage': 'V28_DUMP_IMPORT_STARTED', 'deadlineSeconds': import_timeout}), flush=True)
            execute(['bash', '-o', 'pipefail', '-c', pipeline], output=log, timeout=import_timeout)
        receipt['importSeconds'] = round(time.monotonic() - started, 3)
        print(json.dumps({'stage': 'V28_DUMP_IMPORTED', 'importSeconds': receipt['importSeconds']}), flush=True)
        started = time.monotonic()
        actual = fingerprint(release, runtime, output / 'fingerprint.json', connection, verify_timeout)
        receipt.update(state='ALL_ROWS_AND_DDL_VERIFIED', verificationSeconds=round(time.monotonic()-started, 3),
            fullRowAndDdlParity=True, tableCount=len(actual['tables']), totalRows=sum(t['rows'] for t in actual['tables'].values()),
            historicalInventoryRows=actual['tables']['accommodation_inventory_day']['rows'], fingerprint=actual)
    except BaseException as error:
        receipt.update(state='FAILED', errorType=type(error).__name__)
        raise
    finally:
        (output / 'restore.json').write_text(json.dumps(receipt, indent=2) + '\n')
    return receipt


def main(manifest_path):
    env = os.environ
    contract.require(env.get('AIRBOB_QUALIFICATION_ONLY') == 'true' and env.get('AIRBOB_DATABASE_BOOTSTRAP') == 'dump', 'V4 requires fenced dump preparation')
    contract.require(env['AIRBOB_REGION'] == contract.REGION and env['AIRBOB_DATASET_BUCKET'] == contract.BUCKET and
        env['AIRBOB_EVIDENCE_BUCKET'] == 'airbob-performance-lab-evidence-942632789808' and env['AIRBOB_RDS_ENGINE_VERSION'] == '8.4.11', 'Unexpected AWS boundary')
    contract.require(re.fullmatch(r'lab-[a-z0-9][a-z0-9-]{0,27}', env['AIRBOB_RUN_ID']) and
        re.fullmatch(r'db-[A-Z0-9]{26}', env['AIRBOB_RDS_RESOURCE_ID']) and
        re.fullmatch(r'airbob-lab-[a-z0-9-]+\.[a-z0-9]+\.ap-northeast-2\.rds\.amazonaws\.com', env['AIRBOB_RDS_ENDPOINT']), 'Unexpected run/RDS identity')
    manifest = contract.validate_manifest(contract.read(manifest_path), env['AIRBOB_DATASET_RELEASE'])
    contract.require(contract.sha(manifest_path) == env['AIRBOB_DATASET_MANIFEST_SHA256'], 'Envelope trust anchor differs')
    work = Path('/var/lib/airbob/growth-v4-qualification') / env['AIRBOB_RUN_ID']
    work.mkdir(parents=True, mode=0o700)
    release = work / 'release'
    release.mkdir(mode=0o700)
    for name, a in manifest['artifacts'].items():
        output = release / name
        response = aws('s3api', 'get-object', '--bucket', contract.BUCKET, '--key', a['key'], '--version-id', a['versionId'], str(output))
        output.chmod(0o600)
        contract.require(response['VersionId'] == a['versionId'] and output.stat().st_size == a['bytes'] and contract.sha(output) == a['sha256'], 'Source object version or bytes differ')
    runtime = work / 'verifier'
    migrations = contract.extract_runtime(release, runtime)
    contract.validate_directory(release, manifest, migrations)
    if manifest['search']['enabled']:
        from growth_v4_search import fetch_companion
        fetch_companion(work / 'search-companion', manifest, aws)
    if not shutil.which('java') or not shutil.which('keytool') or not shutil.which('xargs'):
        execute(['dnf', 'install', '-y', 'java-21-amazon-corretto-headless', 'findutils'], timeout=300)
    contract.require(re.search(r'(?:openjdk|java) 21[. ]', execute(['java', '--version']).decode()), 'Java 21 required')
    with tempfile.TemporaryDirectory(prefix='credentials-', dir=work) as private_dir:
        private = Path(private_dir)
        ca, truststore = prepare_tls(private)
        secret = json.loads(aws('secretsmanager', 'get-secret-value', '--secret-id', env['AIRBOB_RDS_MASTER_SECRET_ARN'])['SecretString'])
        connection = {'host': env['AIRBOB_RDS_ENDPOINT'], 'username': secret['username'], 'password': secret['password'], 'ca': ca, 'truststore': truststore}
        proof = restore_and_verify(release, manifest, runtime, private, work / 'verification', connection)
        publish_checkpoint(work, 'db-verified', proof)
        if manifest['search']['enabled']:
            from growth_v4_search import qualify_search
            def sql(query):
                return execute(['mysql', '--defaults-extra-file=' + str(private / 'client.cnf'), '--batch', '--raw',
                    '--skip-column-names', 'airbobdb'], input=query.encode()).decode().strip()
            proof['search'] = qualify_search(work / 'search-companion', manifest, work, sql)
            publish_checkpoint(work, 'search-verified', proof)
        if env.get('AIRBOB_GROWTH_APP_READ_QUALIFICATION') == 'true':
            from growth_v4_app_read import qualify_application
            app_result = qualify_application(release, manifest, runtime, private, work, connection, execute, aws)
            fingerprint(release, runtime, work / 'after-app-fingerprint.json', connection,
                        300 if manifest['datasetScale'] == 'small-qualification' else 3600)
            app_result['fullRowsAndDdlAfterCredentialRestore'] = True
            app_path = work / 'growth-app-read-qualification.json'
            app_path.write_text(json.dumps(app_result, indent=2) + '\n')
            publish_evidence(app_path, env['AIRBOB_EVIDENCE_BUCKET'], 'data-bootstrap/' + env['AIRBOB_RUN_ID'] + '/' + app_path.name)
    proof.update(schemaVersion=1, kind='growth-v4-rds-full-verification', runId=env['AIRBOB_RUN_ID'],
        rdsResourceId=env['AIRBOB_RDS_RESOURCE_ID'], manifestSha256=env['AIRBOB_DATASET_MANIFEST_SHA256'])
    proof_path = work / 'growth-v4-full-verification.json'
    proof_path.write_text(json.dumps(proof, indent=2) + '\n')
    publish_evidence(proof_path, env['AIRBOB_EVIDENCE_BUCKET'], 'data-bootstrap/' + env['AIRBOB_RUN_ID'] + '/' + proof_path.name)
    qualification = {'schemaVersion': 1, 'kind': 'dataset-qualification', 'runId': env['AIRBOB_RUN_ID'],
        'rdsResourceId': env['AIRBOB_RDS_RESOURCE_ID'], 'rdsEngineVersion': '8.4.11',
        'dataset': {'release': manifest['datasetRelease'], 'runId': manifest['datasetRunId'],
            'manifestSha256': env['AIRBOB_DATASET_MANIFEST_SHA256'], 'mysql': manifest['mysql'],
            'releaseTuple': manifest['releaseTuple'], 'search': manifest['search']},
        'verification': {'mode': 'full', 'semanticAttestationSha256': contract.sha(proof_path),
                         'search': 'full' if manifest['search']['enabled'] else 'disabled'},
        'verifiedAt': dt.datetime.now(dt.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}
    path = work / 'dataset-qualification.json'
    path.write_text(json.dumps(qualification, indent=2) + '\n')
    version = publish_evidence(path, env['AIRBOB_EVIDENCE_BUCKET'], 'data-bootstrap/' + env['AIRBOB_RUN_ID'] + '/' + path.name)
    print(json.dumps({'state': 'GROWTH_V4_RDS_FULLY_QUALIFIED', 'tables': 32, 'qualificationVersionId': version}))


if __name__ == '__main__':
    def interrupted(signum, _frame):
        raise SystemExit(128 + signum)
    for sig in [signal.SIGTERM, signal.SIGHUP, signal.SIGINT]:
        signal.signal(sig, interrupted)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    main(parser.parse_args().manifest)
