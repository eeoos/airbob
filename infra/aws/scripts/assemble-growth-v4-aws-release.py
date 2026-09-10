#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
import growth_v4_aws_contract as contract

p = argparse.ArgumentParser()
for name in ['release', 'publication-receipt', 'migration-dir', 'output']:
    p.add_argument('--' + name, type=Path, required=True)
p.add_argument('--revision', type=int, default=1)
p.add_argument('--search-directory', type=Path)
p.add_argument('--search-publication', type=Path)
a = p.parse_args()
m = contract.assemble(a.release, a.publication_receipt, a.migration_dir, a.output, a.revision, a.search_directory, a.search_publication)
print(json.dumps({'datasetRelease': m['datasetRelease'], 'manifestSha256': contract.sha(a.output / 'manifest.json'),
                  'filesToPublish': 1, 'sourceObjectsRemainUnchanged': True}))
