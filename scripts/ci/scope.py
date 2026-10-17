"""Select checks from Git's complete diff; never use the truncated PR files API."""
import json
import os
from pathlib import Path
import re
import subprocess

DEPLOYMENT_SCRIPTS = {
    'scripts/deploy-oci.sh', 'scripts/sync-oci-deployment-assets.sh',
    'scripts/preflight-reservation-inventory-cutover.sh',
    'scripts/reindex-accommodations.sh', 'scripts/init-ssl.sh',
}
BUILD_FILES = {'build.gradle', 'settings.gradle', 'gradlew', 'gradlew.bat', 'gradle.properties'}
OCI_PREFIXES = ('src/main/', 'gradle/', 'docker/app/', 'docker/debezium/',
                'docker/elasticsearch/', 'docker/kafka/', 'docker/mysql/init/',
                'docker/logstash/', 'docker/nginx/', 'monitoring/')
OCI_FILES = BUILD_FILES | DEPLOYMENT_SCRIPTS | {'docker-compose.oci.yml', 'infra/aws/images/release.json'}


def classify(paths, force_app=False):
    selected = dict(app=force_app, infrastructure=False, data=False, oci=False)
    for path in paths:
        oci = path in OCI_FILES or path.startswith(OCI_PREFIXES)
        selected['oci'] |= oci
        if path.startswith(('docs/', 'load-test/', 'scripts/archive/')) or path in {
            'README.md', 'AGENTS.md', 'scripts/README.md', '.gitattributes', '.gitignore', 'LICENSE',
        }:
            continue
        if path.startswith(('.github/', 'scripts/ci/')) or path in BUILD_FILES or path.startswith('gradle/'):
            selected.update(app=True, infrastructure=True, data=True)
        elif path.startswith('infra/aws/'):
            selected.update(infrastructure=True, data=True)
        elif path.startswith('scripts/') and path.endswith('.py'):
            selected['data'] = True
        elif path.startswith(('docker/', 'monitoring/')) or path.startswith('docker-compose') or path in DEPLOYMENT_SCRIPTS:
            selected.update(app=True, infrastructure=True, data=True)
        elif path.startswith('src/main/resources/'):
            selected.update(app=True, infrastructure=True, data=True)
        elif path.startswith('src/'):
            selected['app'] = True
        else:
            # New code locations are checked until their dependencies are classified.
            selected.update(app=True, infrastructure=True, data=True)
        selected['app'] |= oci
    return selected


def git(*args):
    return subprocess.check_output(['git', *args], stderr=subprocess.PIPE, timeout=60).decode().strip()


def require_sha(value):
    if not isinstance(value, str) or not re.fullmatch(r'[0-9a-f]{40}', value) or value == '0' * 40:
        raise ValueError('missing or invalid commit')
    return value


def changed_paths(event_name, event, sha, read_git=git):
    require_sha(sha)
    if read_git('rev-parse', 'HEAD') != sha:
        raise ValueError('checkout does not match the tested commit')
    if event_name == 'pull_request':
        before = require_sha(event['pull_request']['base']['sha'])
        head = require_sha(event['pull_request']['head']['sha'])
        read_git('merge-base', '--is-ancestor', head, sha)
        comparison = before + '...' + sha
    elif event_name == 'push':
        if event['ref'] != 'refs/heads/main' or event['after'] != sha:
            raise ValueError('deployment checks require the exact main push')
        before = require_sha(event['before'])
        comparison = before + '..' + sha
    else:
        raise ValueError('unsupported CI event')
    read_git('cat-file', '-e', before + '^{commit}')
    return [p for p in read_git('diff', '--name-only', '--no-renames', '-z', comparison, '--').split('\0') if p]


def main():
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    name, sha = os.environ['GITHUB_EVENT_NAME'], os.environ['GITHUB_SHA']
    force = os.environ.get('FORCE_APP', 'false') == 'true'
    if name == 'workflow_dispatch':
        expected = require_sha(event['inputs']['expected_execution_commit'])
        if not force or os.environ['GITHUB_REF'] != 'refs/heads/main' or git('rev-parse', 'HEAD') != sha or expected != sha:
            raise ValueError('manual publication requires the reviewed main commit')
        selected = dict(app=True, infrastructure=True, data=True, oci=False)
    else:
        selected = classify(changed_paths(name, event, sha), force)
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        for key, value in selected.items():
            output.write(f'{key}={str(value).lower()}\n')
    print(json.dumps(selected, sort_keys=True))


if __name__ == '__main__':
    main()
