# Mac import: same-RDS downsize

`growth_b_mac_downsize.py` is an offline validator. It never calls AWS,
Terraform, SQL, starts a service, or replays an import. Root's existing
orchestration owns the Mac import lock, current lease/heartbeat, admin
credentials, same backend/native state lock, and actual API/Terraform execution.
It must wait for the Mac importer and postcheck to finish first.

The raw Terraform state, full plan JSON and RDS response stay in bounded RAM.
Pass parsed dictionaries to these APIs; no raw file is required:

```python
prepared = downsize.prepare_inputs(
    operator, state, sql_complete, postcheck,
    operator_sha256=original_operator_raw_sha,
    sql_import_sha256=sql_complete_raw_sha,
    postcheck_sha256=postcheck_raw_sha,
    expected_dump_sha256=already_selected_sealed_dump_sha,
    lease_owner=current_lease_owner,
)
tfvars, request = prepared['tfvars'], prepared['request']
modify = {'DBInstanceIdentifier': request['rds']['identifier'],
          'DBInstanceClass': 'db.t3.small', 'ApplyImmediately': True}
remove_tag = {'ResourceName': request['rds']['arn'], 'TagKeys': ['BDatabaseClass']}
approval = downsize.validate_api_requests(
    request, fresh_describe_db_instances, modify, remove_tag,
    observed_at_epoch=source_observation_epoch,
)
# Caller submits exactly these two requests with admin credentials, waits
# for the same small RDS to become available, then runs Terraform refresh-only
# with the reconstructed tfvars. The helper performs none of these actions.
receipt = downsize.complete_transition(
    request, approval, state_after, actual_describe_db_instances,
    actual_database_identity,
)
```

`state`/`state_after` are the parsed `terraform state pull` shape (version 4,
lineage, serial, outputs and resources). The pre-import creation tfvars are
not needed: the original operator plus state outputs supply the settings and
the exact old probe receipt key. The class becomes `db.t3.small`; the original
resource fence, expiry, dataset and image tuple remain unchanged. The current
lease owner is an explicit input; no Mac host consumes a bootstrap context.

For an early check before SQL finishes, use `project_operator(operator)`,
`check_topology(public_outputs, operator_projection, LARGE)`, and
`tfvars_for(operator_projection, topology, current_lease_owner)` in memory.
The actual public output artifact wraps its direct values under `outputs`.
This early reconstruction is not SQL completion or permission to apply.

The API path requires one fresh source RDS observation, at most 60 seconds
old and within the original expiry. Its resource ID, identifier, ARN,
endpoint, class `db.m6i.large`, available status, explicit empty pending
changes, engine/storage and original tags must match. The request dictionaries
above have exact key sets; another setting, identifier, ARN or tag is rejected.
The approval binds the source observation hash and exact two public requests.
Do not replay an uncertain API call automatically; observe and reconcile the
same instance. A now-small source cannot obtain a new large-source approval.

This path records
`planEvidenceKind="aws-requests-with-terraform-refresh-only"` in approval and
completion, with `planJsonSha256` and `savedPlanSha256` both null. It does not
claim to have checked a Terraform plan or to have executed an AWS request.
The caller owns submission and refresh-only evidence. Completion checks the
actual resulting state and RDS observations before issuing a transition.

The optional alternative `validate_plan(request, full_plan_dictionary)` records
`planEvidenceKind="terraform-full-plan"`. Its full plan must contain exactly one changed managed resource,
`module.rds[0].aws_db_instance.this`, with action `update`. Only its class and
removal of `BDatabaseClass` may change. Configurable unknowns, other RDS
settings, moved/replaced resources and deferred work are rejected. The caller
must inspect the actual apply plan before approving it and keep its reviewed
source/input/state version unchanged. A binary saved plan is optional; when
held in RAM, pass both `saved_plan_sha256` and `saved_plan_bytes`. Omitting
both makes no claim that a binary saved plan was checked. Do not add a RAM
disk or write raw plan/state/logs just for this helper.

`actual_database_identity` is the existing Mac importer's inexpensive
`database_identity()` result: `serverUuid`, `engineVersion`, `tlsCipher`.
Completion requires the same state lineage with an advanced serial, same
RDS resource/endpoint/UUID, small/available/no pending changes, preserved
configuration/tags/TTL, and unchanged NAT/no-writer outputs. It claims no full
dataset validation or service readiness. Lost completion output can be
recreated from the same request, approval and successful current observations;
this does not invoke another apply or import. Partial/uncertain apply remains
incomplete and retains the database for explicit reconciliation.

Only the closed tfvars/request/approval/completion dictionaries may be written
with `write_new(path, value)` (create-only, mode 0600). The CLI is equivalent:
`prepare|plan|api-requests|complete --output NEW_JSON`, with API keyword arguments encoded
as one stdin JSON object, bounded to 64MiB. CLI clock overrides are rejected.
External artifact SHA arguments are raw-byte SHA256; internal request/plan/
state/tfvars hashes use `digest()` (sorted compact JSON plus newline).

Publish the completed public receipt immutably at
`data-bootstrap/<run>/<dataset>-mac-rds-downsize.json`. Subsequent retained
operations explicitly pass `B_MAC_DOWNSIZE_RECEIPT_JSON` containing its exact
`key`, `versionId`, `sha256`, `bytes`. The existing class loader checks that
reference and the actual receipt against the unchanged original operator.
Without it, the class loader returns the original large class. Retained
service operations still require an exact live-class match. Scheduled `down`
does not call that live check: it loads the original operator, targets actual
state addresses, and permits only delete actions. Therefore missing transition
reference does not itself prevent cleanup of the small RDS. The scheduled
workflow sets neither class override nor transition reference; an explicit
small override without a transition reference would still be rejected.
The class-pin IAM denies concern `ModifyDBInstance`, not `DeleteDBInstance`;
the original ephemeral RunId/fence/expiry tags remain present. These are code
path checks, not evidence of an actual cleanup. Existing lease, expiry, state
identity, OCI authority and teardown gates still apply.

No generic class change or existing services admission is relaxed. In
particular, this does not create the missing Debezium host or replace
preparation/service receipts.
