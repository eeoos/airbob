#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
import growth_aws_contract as contract

parser = argparse.ArgumentParser(description='Validate the explicit v3 AWS qualification release')
parser.add_argument('path', type=Path)
parser.add_argument('expected_release')
parser.add_argument('--manifest-only', action='store_true')
parser.add_argument('--migration-dir', type=Path)
args = parser.parse_args()
if args.manifest_only:
    manifest = contract.validate_manifest(contract.read(args.path), args.expected_release)
else:
    manifest, _, _, _ = contract.validate_directory(args.path, args.expected_release, args.migration_dir)
print(json.dumps({'valid': True, 'datasetRelease': manifest['datasetRelease'], 'scope': 'RDS qualification only'}))
