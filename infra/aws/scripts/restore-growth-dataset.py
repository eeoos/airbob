#!/usr/bin/env python3
"""Restore a verified v3 small dataset into an existing, empty MySQL 8.4 database.

Uses a caller-provided MySQL defaults file. Creates no AWS resources and never drops tables.
"""
import argparse
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

spec=importlib.util.spec_from_file_location('growth_validator',Path(__file__).with_name('validate-growth-dataset-v3.py'))
validator=importlib.util.module_from_spec(spec);spec.loader.exec_module(validator)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release',type=Path,required=True);parser.add_argument('--expected-id',required=True)
    parser.add_argument('--mysql-version',default='8.4.11');parser.add_argument('--migration-dir',type=Path,required=True)
    parser.add_argument('--mysql-client',default='mysql');parser.add_argument('--defaults-file',required=True)
    parser.add_argument('--database',required=True);parser.add_argument('--receipt',type=Path,required=True)
    args=parser.parse_args()
    validator.require(re.fullmatch(r'[a-z][a-z0-9_]{0,63}',args.database),'Unsafe schema name')
    validator.require(not args.receipt.exists(),'Receipt already exists')
    manifest,fingerprint,targets=validator.validate(args.release,args.expected_id,args.mysql_version,args.migration_dir)
    command=[args.mysql_client,'--defaults-extra-file='+args.defaults_file,'--batch','--raw','--skip-column-names',args.database]
    def sql(query):return subprocess.check_output(command+['-e',query],text=True)
    version=sql('SELECT VERSION()').strip()
    validator.require(version==args.mysql_version,'Target engine does not match the qualified MySQL version')
    count=int(sql('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()').strip())
    validator.require(count==0,'Target database is not empty')
    receipt={'state':'RESTORING','datasetId':manifest['datasetId'],'mysqlVersion':version,
             'dumpSha256':manifest['artifacts']['dump']['sha256'],'schema':args.database}
    try:
        with gzip.open(args.release/'airbob-growth.sql.gz','rb') as source,tempfile.TemporaryFile() as raw:
            shutil.copyfileobj(source,raw);raw.seek(0);subprocess.run(command,stdin=raw,check=True)
        actual_tables=sql("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() ORDER BY table_name").splitlines()
        validator.require(actual_tables==sorted(fingerprint['tables']),'Restored table inventory mismatch')
        for table,expected in fingerprint['tables'].items():
            validator.require(int(sql(f'SELECT COUNT(*) FROM `{table}`').strip())==expected['rows'],'Row count mismatch: '+table)
            ddl=sql(f'SHOW CREATE TABLE `{table}`').split('\t',1)[1].removesuffix('\n')
            validator.require(hashlib.sha256(ddl.encode()).hexdigest()==expected['ddlSha256'],'Restored DDL mismatch: '+table)
        receipt.update(state='RESTORED_SCHEMA_AND_COUNTS_VERIFIED',tableCount=len(actual_tables),readTargets=len(targets['targets']),
                       scope='exact dump digest, engine, migration-source, restored DDL and row counts; full row parity belongs to the separate qualification receipt')
    except BaseException as error:
        receipt.update(state='FAILED',errorType=type(error).__name__);raise
    finally:
        args.receipt.write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(receipt))


if __name__=='__main__':main()
