#!/usr/bin/env python3
"""Classify legacy image source changes without turning AWS config edits into OCI publishes."""
import os
from pathlib import Path
import re
import subprocess
import sys

IMAGE_DIRECTORIES = ('docker/aws-mirror/', 'docker/kafka/', 'docker/debezium/', 'docker/elasticsearch/')
# Only files read by the four Dockerfiles or Docker's context filter are image
# inputs. Topic/connector administration scripts in those directories run on the
# host; they are not copied into the images. A test checks COPY/ADD coverage so a
# future Dockerfile change cannot silently leave this inventory incomplete.
IMAGE_FILES = frozenset({'infra/aws/images/release.json', 'docker/debezium/connect-distributed.properties'}
                       | {directory + name for directory in IMAGE_DIRECTORIES
                          for name in ('Dockerfile', '.dockerignore', 'Dockerfile.dockerignore')})


def needs_images(paths):
    return any(path in IMAGE_FILES for path in paths)


def classify(event, before, after, github_sha, read_git):
    if event not in ('push', 'workflow_dispatch') or not re.fullmatch(r'[0-9a-f]{40}', after) \
            or after == '0' * 40 or after != github_sha:
        raise ValueError('invalid_publication_event')
    if read_git('rev-parse', '--verify', 'HEAD').decode().strip() != after:
        raise ValueError('publication_head_changed')
    if event == 'workflow_dispatch':
        return True
    if not re.fullmatch(r'[0-9a-f]{40}', before) or before == '0' * 40:
        raise ValueError('invalid_publication_before_commit')
    read_git('cat-file', '-e', before + '^{commit}')
    read_git('cat-file', '-e', after + '^{commit}')
    paths = read_git('diff', '--name-only', '-z', before, after, '--').decode().split('\x00')
    return needs_images(paths)


def main():
    try:
        if os.environ.get('GITHUB_REF') != 'refs/heads/main':
            raise ValueError('main_required')
        def read_git(*args):
            return subprocess.check_output(['git', *args], stderr=subprocess.DEVNULL, timeout=60)
        result = classify(os.environ.get('GITHUB_EVENT_NAME', ''), os.environ.get('BEFORE_SHA', ''),
                          os.environ.get('AFTER_SHA', ''), os.environ.get('GITHUB_SHA', ''), read_git)
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
            stream.write('images_required=' + str(result).lower() + '\n')
        return 0
    except (ValueError, KeyError, OSError, subprocess.SubprocessError):
        print('Image publication scope could not be verified', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
