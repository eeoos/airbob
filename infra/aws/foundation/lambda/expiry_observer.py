import json
import os
import time


METRIC_NAMESPACE = "Airbob/PerformanceLab"
IDENTITY_TAGS = {
    "Project": "airbob",
    "Environment": "performance-lab",
    "Stack": "lab",
}
REQUIRED_TAGS = {
    **IDENTITY_TAGS,
    "ManagedBy": "terraform",
    "Persistence": "ephemeral",
}
EXPIRES_TAG_KEY = "ExpiresAt"


def _parse_expiry(value):
    if not isinstance(value, str) or not value.isascii() or not value.isdecimal():
        return None
    parsed = int(value)
    if parsed <= 0 or str(parsed) != value:
        return None
    return parsed


def _tags(resource):
    return {tag.get("Key"): tag.get("Value") for tag in resource.get("Tags", [])}


def observe_expiry(tagging_client, cloudwatch_client, now_epoch=None):
    now_epoch = int(time.time()) if now_epoch is None else now_epoch
    observed = 0
    expired = 0
    invalid = 0

    paginator = tagging_client.get_paginator("get_resources")
    pages = paginator.paginate(
        TagFilters=[
            {"Key": key, "Values": [value]} for key, value in IDENTITY_TAGS.items()
        ]
    )
    for page in pages:
        for resource in page.get("ResourceTagMappingList", []):
            observed += 1
            tags = _tags(resource)
            expiry = _parse_expiry(tags.get(EXPIRES_TAG_KEY))
            if any(tags.get(key) != value for key, value in REQUIRED_TAGS.items()) or expiry is None:
                invalid += 1
            elif expiry <= now_epoch:
                expired += 1

    action_required = expired + invalid
    values = {
        "ObserverHeartbeat": 1,
        "ObservedResourceCount": observed,
        "ExpiredResourceCount": expired,
        "InvalidResourceCount": invalid,
        "ActionRequiredCount": action_required,
    }
    cloudwatch_client.put_metric_data(
        Namespace=METRIC_NAMESPACE,
        MetricData=[
            {"MetricName": name, "Value": value, "Unit": "Count"}
            for name, value in values.items()
        ],
    )

    result = {
        "observed": observed,
        "expired": expired,
        "invalid": invalid,
        "action_required": action_required,
        "cleanup_enabled": False,
    }
    print(json.dumps(result, sort_keys=True, separators=(",", ":")))
    return result


def lambda_handler(_event, _context):
    if os.environ.get("CLEANUP_ENABLED") != "false":
        raise RuntimeError("expiry observer must remain observer-only")
    if os.environ.get("METRIC_NAMESPACE") != METRIC_NAMESPACE:
        raise RuntimeError("expiry observer metric namespace mismatch")

    import boto3

    return observe_expiry(
        boto3.client("resourcegroupstaggingapi"),
        boto3.client("cloudwatch"),
    )
