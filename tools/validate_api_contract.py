#!/usr/bin/env python3
"""Validate published API schemas/examples; --specifications needs network access."""
import argparse
import json
import math
import re
from datetime import datetime
from pathlib import Path
from urllib.request import urlopen
import jsonschema
import yaml

API_DIRECTORY = Path(__file__).resolve().parents[1] / "docs" / "api"


def load_contracts():
    return {name: yaml.safe_load((API_DIRECTORY / f"{name}.yaml").read_text())
            for name in ("openapi", "asyncapi")}


def expand_refs(value, document):
    if isinstance(value, dict):
        if "$ref" in value:
            ref = value["$ref"]
            if not ref.startswith("#/"):
                raise ValueError("Published payload schemas must use local references")
            target = document
            for key in ref[2:].split("/"):
                target = target[key.replace("~1", "/").replace("~0", "~")]
            return expand_refs(target, document)
        return {key: expand_refs(item, document) for key, item in value.items()}
    if isinstance(value, list):
        return [expand_refs(item, document) for item in value]
    return value


def payload_validator(schema):
    jsonschema.Draft202012Validator.check_schema(schema)
    formats = jsonschema.FormatChecker()

    @formats.checks("date-time", raises=ValueError)
    def timestamp(value):
        if not isinstance(value, str):
            return True
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}(\.\d+)?([Zz]|[+-]\d{2}:\d{2})", value):
            return False
        datetime.fromisoformat(value.upper().replace("Z", "+00:00"))
        return True

    base = jsonschema.Draft202012Validator
    types = base.TYPE_CHECKER.redefine("number", lambda checker, value:
        base.TYPE_CHECKER.is_type(value, "number") and
        (not isinstance(value, float) or math.isfinite(value)))
    return jsonschema.validators.extend(base, type_checker=types)(schema, format_checker=formats)


def validate_examples(contracts):
    http, realtime = contracts["openapi"], contracts["asyncapi"]
    for filename, schema, document in [
        ("motion-batch.json", http["components"]["schemas"]["MotionBatch"], http),
        ("auth-config.json", http["components"]["schemas"]["AuthConfig"], http),
        ("device-registration.json", http["components"]["schemas"]["DeviceRegistration"], http),
        ("device-registration-response.json", http["components"]["schemas"]["DeviceRegistrationResponse"], http),
        ("capabilities.json", http["components"]["schemas"]["CapabilitiesResponse"], http),
        ("metrics-snapshot.json", http["components"]["schemas"]["MetricEnvelope"], http),
        ("realtime-telemetry.json", realtime["components"]["messages"]["Telemetry"]["payload"], realtime),
    ]:
        example = json.loads((API_DIRECTORY / "examples" / filename).read_text())
        json.dumps(example, allow_nan=False)
        payload_validator(expand_refs(schema, document)).validate(example)


def validate_specifications(contracts):
    urls = {
        "openapi": "https://spec.openapis.org/oas/3.1/schema/2022-10-07",
        "asyncapi": "https://raw.githubusercontent.com/asyncapi/spec-json-schemas/master/schemas/3.0.0.json",
    }
    for name, url in urls.items():
        with urlopen(url, timeout=30) as response:
            schema = json.load(response)
        validator = jsonschema.validators.validator_for(schema)(schema)
        errors = list(validator.iter_errors(contracts[name]))
        if errors:
            error = errors[0]
            raise ValueError(f"{name}: {list(error.absolute_path)}: {error.message[:400]}")
        print(f"{name}: upstream specification schema passed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--specifications", action="store_true")
    args = parser.parse_args()
    contracts = load_contracts()
    validate_examples(contracts)
    if args.specifications:
        validate_specifications(contracts)
    print("API examples passed schema validation")
