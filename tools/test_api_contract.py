"""Offline schema conformance checks; all fixtures are synthetic."""
import copy
import itertools
import json
import unittest
from jsonschema import ValidationError
from validate_api_contract import API_DIRECTORY, expand_refs, load_contracts, payload_validator, validate_examples


class ApiSchemaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.contracts = load_contracts()
        http = cls.contracts["openapi"]
        cls.capabilities = payload_validator(expand_refs(http["components"]["schemas"]["CapabilitiesResponse"], http))
        cls.metric = payload_validator(expand_refs(http["components"]["schemas"]["MetricEnvelope"], http))
        cls.auth_config = payload_validator(expand_refs(http["components"]["schemas"]["AuthConfig"], http))
        cls.registration = payload_validator(expand_refs(http["components"]["schemas"]["DeviceRegistrationResponse"], http))

    def test_examples_match_the_published_payload_schemas(self):
        validate_examples(self.contracts)

    def test_all_seven_capability_combinations_are_usable(self):
        fixture = json.loads((API_DIRECTORY / "examples" / "capabilities.json").read_text())
        for length in range(1, 4):
            for keys in itertools.combinations(fixture["capabilities"], length):
                value = copy.deepcopy(fixture)
                value["capabilities"] = {key: fixture["capabilities"][key] for key in keys}
                self.capabilities.validate(value)
        fixture["capabilities"] = {"future": {"schema_version": 1}}
        with self.assertRaises(ValidationError):
            self.capabilities.validate(fixture)

    def test_oidc_contract_fixes_audience_scope_and_device_token_shape(self):
        config = json.loads((API_DIRECTORY / "examples" / "auth-config.json").read_text())
        self.auth_config.validate(config)
        invalid = copy.deepcopy(config)
        invalid["audience"] = "another-api"
        with self.assertRaises(ValidationError):
            self.auth_config.validate(invalid)
        invalid = copy.deepcopy(config)
        invalid["scopes"] = ["openid"]
        with self.assertRaises(ValidationError):
            self.auth_config.validate(invalid)

        response = json.loads((API_DIRECTORY / "examples" / "device-registration-response.json").read_text())
        self.registration.validate(response)
        response["device_token"] = "not-a-device-token"
        with self.assertRaises(ValidationError):
            self.registration.validate(response)

    def test_lifecycle_requires_its_occurrence_fields(self):
        example = json.loads((API_DIRECTORY / "examples" / "metrics-snapshot.json").read_text())
        for kind, field, active in [("ride.started", "started_at", True), ("ride.ended", "ended_at", False)]:
            value = copy.deepcopy(example)
            value["kind"] = kind
            value["data"]["ride_active"] = active
            with self.assertRaises(ValidationError):
                self.metric.validate(value)
            value["data"][field] = value["occurred_at"]
            if not active:
                value["data"]["finalized_at"] = value["occurred_at"]
            self.metric.validate(value)

    def test_unknown_optional_fields_and_missing_measurements_are_allowed(self):
        value = json.loads((API_DIRECTORY / "examples" / "metrics-snapshot.json").read_text())
        value["data"] = {"connection": "live", "ride_active": True, "future_optional": 1}
        self.metric.validate(value)
        value["schema_version"] = 2
        with self.assertRaises(ValidationError):
            self.metric.validate(value)

    def test_invalid_timestamp_and_units_are_rejected(self):
        value = json.loads((API_DIRECTORY / "examples" / "metrics-snapshot.json").read_text())
        value["occurred_at"] = "not-a-timestamp"
        with self.assertRaises(ValidationError):
            self.metric.validate(value)
        value["occurred_at"] = "2030-01-01T00:00:00Z"
        value["data"]["trip_distance_km"] = -1
        with self.assertRaises(ValidationError):
            self.metric.validate(value)
