import importlib.util
import io
import os
import sys
import types
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).parents[1] / "lambda" / "expiry_observer.py"


def load_observer_module():
    spec = importlib.util.spec_from_file_location("expiry_observer", MODULE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FakePaginator:
    def __init__(self, pages):
        self.pages = pages
        self.calls = []

    def paginate(self, **kwargs):
        self.calls.append(kwargs)
        tag_filters = kwargs.get("TagFilters", [])
        filtered_pages = []
        for page in self.pages:
            resources = []
            for candidate in page.get("ResourceTagMappingList", []):
                tags = {
                    tag.get("Key"): tag.get("Value")
                    for tag in candidate.get("Tags", [])
                }
                if all(
                    tags.get(tag_filter["Key"]) in tag_filter["Values"]
                    for tag_filter in tag_filters
                ):
                    resources.append(candidate)
            filtered_pages.append({"ResourceTagMappingList": resources})
        return iter(filtered_pages)


class FakeTaggingClient:
    def __init__(self, pages):
        self.paginator = FakePaginator(pages)

    def get_paginator(self, operation_name):
        if operation_name != "get_resources":
            raise AssertionError(f"unexpected operation: {operation_name}")
        return self.paginator


class FakeCloudWatchClient:
    def __init__(self):
        self.calls = []

    def put_metric_data(self, **kwargs):
        self.calls.append(kwargs)


def resource(arn, expires_at=None, omit=(), **overrides):
    tags = {
        "Project": "airbob",
        "Environment": "performance-lab",
        "Stack": "lab",
        "ManagedBy": "terraform",
        "Persistence": "ephemeral",
    }
    tags.update(overrides)
    for key in omit:
        tags.pop(key, None)
    if expires_at is not None:
        tags["ExpiresAt"] = expires_at
    return {
        "ResourceARN": arn,
        "Tags": [{"Key": key, "Value": value} for key, value in tags.items()],
    }


class ExpiryObserverTest(unittest.TestCase):
    def setUp(self):
        self.observer = load_observer_module()

    def test_reports_expired_and_invalid_resources_without_mutating_them(self):
        tagging = FakeTaggingClient(
            [
                {
                    "ResourceTagMappingList": [
                        resource("arn:aws:ec2:ap-northeast-2:942632789808:instance/i-expired", "200"),
                        resource("arn:aws:ec2:ap-northeast-2:942632789808:instance/i-active", "201"),
                    ]
                },
                {
                    "ResourceTagMappingList": [
                        resource("arn:aws:rds:ap-northeast-2:942632789808:db:missing-expiry"),
                        resource(
                            "arn:aws:rds:ap-northeast-2:942632789808:db:missing-persistence",
                            "199",
                            omit=("Persistence",),
                        ),
                        resource("arn:aws:rds:ap-northeast-2:942632789808:db:invalid-expiry", "tomorrow"),
                        resource(
                            "arn:aws:ec2:ap-northeast-2:942632789808:instance/i-wrong-persistence",
                            "199",
                            Persistence="persistent",
                        ),
                        resource(
                            "arn:aws:ec2:ap-northeast-2:942632789808:instance/i-wrong-manager",
                            "199",
                            ManagedBy="manual",
                        ),
                        resource(
                            "arn:aws:s3:::airbob-performance-lab-foundation",
                            None,
                            Stack="foundation",
                            Persistence="persistent",
                        ),
                    ]
                },
            ]
        )
        cloudwatch = FakeCloudWatchClient()

        output = io.StringIO()
        with redirect_stdout(output):
            result = self.observer.observe_expiry(tagging, cloudwatch, now_epoch=200)

        self.assertEqual(
            result,
            {
                "observed": 7,
                "expired": 1,
                "invalid": 5,
                "action_required": 6,
                "cleanup_enabled": False,
            },
        )
        self.assertEqual(len(tagging.paginator.calls), 1)
        self.assertEqual(
            tagging.paginator.calls[0]["TagFilters"],
            [
                {"Key": "Project", "Values": ["airbob"]},
                {"Key": "Environment", "Values": ["performance-lab"]},
                {"Key": "Stack", "Values": ["lab"]},
            ],
        )
        self.assertEqual(len(cloudwatch.calls), 1)
        self.assertEqual(cloudwatch.calls[0]["Namespace"], "Airbob/PerformanceLab")
        self.assertTrue(
            all(
                metric["Unit"] == "Count"
                for metric in cloudwatch.calls[0]["MetricData"]
            )
        )
        metrics = {
            metric["MetricName"]: metric["Value"]
            for metric in cloudwatch.calls[0]["MetricData"]
        }
        self.assertEqual(
            metrics,
            {
                "ObserverHeartbeat": 1,
                "ObservedResourceCount": 7,
                "ExpiredResourceCount": 1,
                "InvalidResourceCount": 5,
                "ActionRequiredCount": 6,
            },
        )
        self.assertNotIn("arn:aws", output.getvalue())
        self.assertEqual(
            output.getvalue().strip(),
            '{"action_required":6,"cleanup_enabled":false,"expired":1,"invalid":5,"observed":7}',
        )

    def test_requires_canonical_positive_ascii_expiry(self):
        for value in (None, 200, "", "0", "0200", "-1", "200.0", "２００"):
            with self.subTest(value=value):
                self.assertIsNone(self.observer._parse_expiry(value))

        self.assertEqual(self.observer._parse_expiry("200"), 200)

    def test_reports_zero_for_an_empty_lab(self):
        tagging = FakeTaggingClient([{"ResourceTagMappingList": []}])
        cloudwatch = FakeCloudWatchClient()

        with redirect_stdout(io.StringIO()):
            result = self.observer.observe_expiry(tagging, cloudwatch, now_epoch=200)

        self.assertEqual(result["action_required"], 0)
        metrics = {
            metric["MetricName"]: metric["Value"]
            for metric in cloudwatch.calls[0]["MetricData"]
        }
        for name in (
            "ObservedResourceCount",
            "ExpiredResourceCount",
            "InvalidResourceCount",
            "ActionRequiredCount",
        ):
            with self.subTest(metric=name):
                self.assertEqual(metrics[name], 0)

    def test_lambda_handler_refuses_any_cleanup_enablement(self):
        with patch.dict(os.environ, {"CLEANUP_ENABLED": "true"}, clear=True):
            with self.assertRaisesRegex(RuntimeError, "observer-only"):
                self.observer.lambda_handler({}, None)

    def test_lambda_handler_requires_the_fixed_metric_namespace(self):
        with patch.dict(os.environ, {"CLEANUP_ENABLED": "false"}, clear=True):
            with self.assertRaisesRegex(RuntimeError, "namespace mismatch"):
                self.observer.lambda_handler({}, None)

    def test_lambda_handler_uses_only_tagging_and_cloudwatch_clients(self):
        tagging = FakeTaggingClient([{"ResourceTagMappingList": []}])
        cloudwatch = FakeCloudWatchClient()
        requested_services = []

        def client(service_name):
            requested_services.append(service_name)
            return {"resourcegroupstaggingapi": tagging, "cloudwatch": cloudwatch}[service_name]

        fake_boto3 = types.SimpleNamespace(client=client)
        with patch.dict(
            os.environ,
            {"CLEANUP_ENABLED": "false", "METRIC_NAMESPACE": "Airbob/PerformanceLab"},
            clear=True,
        ), patch.dict(sys.modules, {"boto3": fake_boto3}), redirect_stdout(
            io.StringIO()
        ):
            result = self.observer.lambda_handler({}, None)

        self.assertEqual(requested_services, ["resourcegroupstaggingapi", "cloudwatch"])
        self.assertFalse(result["cleanup_enabled"])


if __name__ == "__main__":
    unittest.main()
