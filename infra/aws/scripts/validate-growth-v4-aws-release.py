#!/usr/bin/env python3
import argparse
from pathlib import Path
import growth_v4_aws_contract as contract

p = argparse.ArgumentParser()
p.add_argument('manifest', type=Path)
p.add_argument('release_id')
p.add_argument('--source', type=Path)
p.add_argument('--migration-dir', type=Path)
a = p.parse_args()
m = contract.validate_manifest(contract.read(a.manifest), a.release_id)
if a.source:
    contract.require(a.migration_dir is not None, 'Migration directory required')
    contract.validate_directory(a.source, m, a.migration_dir)
print('V4_AWS_CONTRACT_VERIFIED')
