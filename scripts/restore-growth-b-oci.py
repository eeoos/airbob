#!/usr/bin/env python3
"""Run the B preflight/apply workflow on the OCI host itself; never connect over SSH."""
import importlib.util
import json
from pathlib import Path
import sys

path = Path(__file__).with_name('restore-growth-b-local.py')
spec = importlib.util.spec_from_file_location('growth_b_local_restore', path)
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)

if __name__ == '__main__':
    try:
        restore.main(mode='oci')
    except BaseException as error:
        print(json.dumps({'state': 'FAILED', 'errorType': type(error).__name__}), file=sys.stderr)
        sys.exit(1)
