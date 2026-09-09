#!/usr/bin/env python3
"""Restore and fully verify the small v3 dataset on a qualification-only RDS host."""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import growth_aws_contract as contract


def execute(command, *, env=None, output=None, timeout=300):
    result = subprocess.run(command, env=env, stdout=output or subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=timeout)
    if result.returncode:
        if output is not None:
            output.write(result.stderr)
        # MySQL/Java errors can include connection coordinates or SQL. Keep the
        # public failure closed; the caller's RDS and run identity are sufficient.
        raise RuntimeError('Qualification subprocess failed: ' + Path(command[0]).name)
    return result.stdout


def aws(*args):
    return json.loads(execute(['aws', '--region', os.environ['AIRBOB_REGION'], '--no-cli-pager',
                              '--output', 'json', '--cli-connect-timeout', '5', '--cli-read-timeout', '30',
                              *args], timeout=45) or b'{}')


def mysql_literal(value):
    contract.require('\n' not in value and '\r' not in value and '\x00' not in value, 'Unsafe client option')
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'


def prepare_tls(directory):
    ca = directory / 'rds-ca.pem'
    with urllib.request.urlopen('https://truststore.pki.rds.amazonaws.com/ap-northeast-2/ap-northeast-2-bundle.pem', timeout=30) as response:
        data = response.read(1_000_001)
    contract.require(len(data) <= 1_000_000, 'Invalid RDS trust bundle size')
    certificates = re.findall(rb'-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----', data, re.S)
    contract.require(1 <= len(certificates) <= 10, 'Invalid RDS trust bundle')
    ca.write_bytes(data)
    truststore = directory / 'rds-trust.p12'
    for number, certificate in enumerate(certificates):
        part = directory / ('ca-' + str(number) + '.pem')
        part.write_bytes(certificate)
        # This password protects a public CA-only store; there are no private keys.
        execute(['keytool', '-importcert', '-noprompt', '-alias', 'rds-' + str(number), '-file', str(part),
                 '-keystore', str(truststore), '-storetype', 'PKCS12', '-storepass', 'public-ca-store'])
    return ca, truststore


def restore_and_verify(release, manifest, runtime, secret_dir, output, connection, mysql_client='mysql'):
    """Shared real-MySQL path; connection is passed in memory, never in argv."""
    contract.require((connection.get('ca') and connection.get('truststore')) or
                     connection['host'] in ['127.0.0.1', 'localhost'],
                     'Remote restore requires verified TLS before import')
    output.mkdir(mode=0o700)
    migrations = runtime / 'migrations'
    contract.consumer.validate(release, manifest['source']['datasetId'], '8.4.11', migrations)
    defaults = secret_dir / 'client.cnf'
    options = {'host': connection['host'], 'port': str(connection.get('port', 3306)),
               'user': connection['username'], 'password': connection['password']}
    if connection.get('ca'):
        options.update(ssl='true', **{'ssl-verify-server-cert': 'true', 'ssl-ca': str(connection['ca'])})
    fd = os.open(defaults, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as file:
        file.write('[client]\n' + ''.join(key + '=' + mysql_literal(value) + '\n' for key, value in options.items()))
    receipt = output / 'restore.json'
    with (output / 'restore.log').open('wb') as log:
        execute([sys.executable, str(Path(__file__).with_name('restore-growth-dataset.py')),
                 '--release', str(release), '--expected-id', manifest['source']['datasetId'],
                 '--migration-dir', str(migrations), '--defaults-file', str(defaults),
                 '--database', 'airbobdb', '--mysql-client', mysql_client, '--receipt', str(receipt)], output=log)
    properties = 'connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
    if connection.get('truststore'):
        properties += '&sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreType=PKCS12&trustCertificateKeyStorePassword=public-ca-store&trustCertificateKeyStoreUrl=file:' + str(connection['truststore'])
    else:
        properties += '&sslMode=DISABLED&allowPublicKeyRetrieval=true'
        contract.require(connection['host'] in ['127.0.0.1', 'localhost'], 'Unverified TLS is allowed only in the local test')
    backend = runtime / 'backend'
    migration_target = backend / 'src/main/resources/db/migration'
    migration_target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    migration_target.symlink_to(migrations, target_is_directory=True)
    env = dict(os.environ, AIRBOB_ETL_DB_URL='jdbc:mysql://' + connection['host'] + ':' + str(connection.get('port', 3306)) + '/airbobdb?' + properties,
               AIRBOB_ETL_DB_USER=connection['username'], AIRBOB_ETL_DB_PASSWORD=connection['password'],
               AIRBOB_ETL_BACKEND_ROOT=str(backend), JAVA_OPTS='-Xmx256m')
    actual_path = output / 'fingerprint.json'
    with (output / 'fingerprint.log').open('wb') as log:
        execute([str(runtime / 'runtime/bin/etl'), '--growth-profile=' + str(runtime / 'profile.json'),
                 '--growth-command=fingerprint', '--growth-output=' + str(actual_path), '--service-schema=airbobdb'],
                env=env, output=log)
    actual = contract.read(actual_path)
    expected = contract.read(release / 'before-fingerprint.json')
    contract.require(actual == expected, 'Restored full rows or DDL differ from the qualified source')
    return {'schemaVersion': 1, 'kind': 'growth-rds-full-verification', 'datasetId': manifest['source']['datasetId'],
            'mysqlVersion': actual['mysqlVersion'], 'fullRowAndDdlParity': True, 'tableCount': len(actual['tables']),
            'expectedFingerprintSha256': manifest['artifacts']['before-fingerprint.json']['sha256'],
            'fingerprint': actual}


def publish_evidence(path, bucket, key):
    result = aws('s3api', 'put-object', '--bucket', bucket, '--key', key, '--body', str(path), '--if-none-match', '*',
                 '--tagging', 'Retention=summary', '--content-type', 'application/json', '--server-side-encryption', 'AES256')
    version = result.get('VersionId')
    contract.require(version not in [None, '', 'null', 'None'], 'Evidence must have an S3 version')
    with tempfile.TemporaryDirectory(prefix='growth-evidence-') as directory:
        copy = Path(directory) / 'readback'
        downloaded = aws('s3api', 'get-object', '--bucket', bucket, '--key', key, '--version-id', version, str(copy))
        contract.require(downloaded.get('VersionId') == version and contract.sha(copy) == contract.sha(path), 'Evidence readback mismatch')
    return version


def validate_run_identity(run_id, resource_id):
    contract.require(re.fullmatch(r'[a-z0-9][a-z0-9-]{2,31}', run_id) and
                     re.fullmatch(r'db-[A-Z0-9]{26}', resource_id), 'Invalid run/RDS identity')


def main(manifest_path):
    env = os.environ
    contract.require(env.get('AIRBOB_QUALIFICATION_ONLY') == 'true' and env.get('AIRBOB_DATABASE_BOOTSTRAP') == 'dump',
                     'Growth AWS support is currently qualification-only dump restore')
    contract.require(env['AIRBOB_REGION'] == 'ap-northeast-2' and
                     env['AIRBOB_DATASET_BUCKET'] == 'airbob-performance-lab-dataset-942632789808' and
                     env['AIRBOB_EVIDENCE_BUCKET'] == 'airbob-performance-lab-evidence-942632789808' and
                     env['AIRBOB_RDS_ENGINE_VERSION'] == '8.4.11', 'AWS qualification boundary mismatch')
    validate_run_identity(env['AIRBOB_RUN_ID'], env['AIRBOB_RDS_RESOURCE_ID'])
    manifest = contract.validate_manifest(contract.read(manifest_path), env['AIRBOB_DATASET_RELEASE'])
    contract.require(contract.sha(manifest_path) == env['AIRBOB_DATASET_MANIFEST_SHA256'], 'Wrapper trust anchor mismatch')
    contract.require(re.fullmatch(r'airbob-lab-[a-z0-9-]+\.[a-z0-9]+\.ap-northeast-2\.rds\.amazonaws\.com', env['AIRBOB_RDS_ENDPOINT']), 'Unexpected RDS endpoint')
    work = Path('/var/lib/airbob/growth-qualification') / env['AIRBOB_RUN_ID']
    work.mkdir(parents=True, exist_ok=True, mode=0o700)
    release = work / 'release'
    release.mkdir(mode=0o700)
    shutil.copyfile(manifest_path, release / 'manifest.json')
    for name, item in manifest['artifacts'].items():
        path = release / name
        aws('s3api', 'get-object', '--bucket', env['AIRBOB_DATASET_BUCKET'], '--key',
            'datasets/' + manifest['datasetRelease'] + '/' + name, str(path))
        path.chmod(0o600)
        contract.require(path.stat().st_size == item['bytes'] and contract.sha(path) == item['sha256'], 'Payload digest mismatch')
    contract.validate_directory(release, manifest['datasetRelease'])
    runtime = work / 'verifier'
    runtime.mkdir(mode=0o700)
    contract.validate_runtime(release / 'verification-runtime.zip', contract.read(release / 'migration-files.json'), runtime)
    java_version = execute(['java', '--version']).decode() if shutil.which('java') else ''
    if not re.search(r'(?:openjdk|java) 21[. ]', java_version) or not shutil.which('keytool'):
        execute(['dnf', 'install', '-y', 'java-21-amazon-corretto-headless'], timeout=300)
    contract.require(re.search(r'(?:openjdk|java) 21[. ]', execute(['java', '--version']).decode()), 'Java 21 is required')
    with tempfile.TemporaryDirectory(prefix='credentials-', dir=work) as directory:
        secret_dir = Path(directory)
        ca, truststore = prepare_tls(secret_dir)
        secret = json.loads(aws('secretsmanager', 'get-secret-value', '--secret-id', env['AIRBOB_RDS_MASTER_SECRET_ARN'])['SecretString'])
        connection = {'host': env['AIRBOB_RDS_ENDPOINT'], 'username': secret['username'], 'password': secret['password'], 'ca': ca, 'truststore': truststore}
        verification = restore_and_verify(release, manifest, runtime, secret_dir, work / 'verification', connection)
    verification.update(runId=env['AIRBOB_RUN_ID'], rdsResourceId=env['AIRBOB_RDS_RESOURCE_ID'],
                        manifestSha256=env['AIRBOB_DATASET_MANIFEST_SHA256'])
    proof_path = work / 'growth-full-verification.json'
    proof_path.write_text(json.dumps(verification, indent=2) + '\n')
    publish_evidence(proof_path, env['AIRBOB_EVIDENCE_BUCKET'], 'data-bootstrap/' + env['AIRBOB_RUN_ID'] + '/growth-full-verification.json')
    qualification = {'schemaVersion': 1, 'kind': 'dataset-qualification', 'runId': env['AIRBOB_RUN_ID'],
                     'rdsResourceId': env['AIRBOB_RDS_RESOURCE_ID'], 'rdsEngineVersion': '8.4.11',
                     'dataset': {'release': manifest['datasetRelease'], 'runId': manifest['datasetRunId'],
                                 'manifestSha256': env['AIRBOB_DATASET_MANIFEST_SHA256'], 'mysql': manifest['mysql'],
                                 'releaseTuple': manifest['releaseTuple'], 'search': manifest['search']},
                     'verification': {'mode': 'full', 'semanticAttestationSha256': contract.sha(proof_path), 'search': 'disabled'},
                     'verifiedAt': dt.datetime.now(dt.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')}
    qualification_path = work / 'dataset-qualification.json'
    qualification_path.write_text(json.dumps(qualification, indent=2) + '\n')
    version = publish_evidence(qualification_path, env['AIRBOB_EVIDENCE_BUCKET'], 'data-bootstrap/' + env['AIRBOB_RUN_ID'] + '/dataset-qualification.json')
    print(json.dumps({'state': 'GROWTH_RDS_FULLY_QUALIFIED', 'tableCount': 32, 'qualificationVersionId': version}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    main(parser.parse_args().manifest)
