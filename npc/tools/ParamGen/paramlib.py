#!/usr/bin/env python3
"""Shared helpers for the NPC parameter schema / profile prototype.

This module deliberately depends only on the Python standard library.  The
only third-party code used anywhere in ParamGen is kconfiglib, which is
vendored under ``vendor/`` for the schema snapshot generator.

Design notes
------------
* An *effective hash* is computed over a canonical projection of the
  ``npc_config_gen --typed-json`` output.  Build-only, boot-only, FPGA-only,
  path, timestamp, version and schema metadata are removed before hashing.
* Profile base/allow checks operate on the semantic *flat* parameter map.
  Only parameters that have a Kconfig prompt (primary parameters) are
  compared; derived parameters follow from those and are still part of the
  effective hash.
* Profiles never decide whether a configuration is legal.  The existing
  Kconfig contract remains the legality authority.  Profiles only classify
  support status.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[2]
DEFAULT_SCHEMA = REPO_ROOT / "npc/params/schema/schema.json"
DEFAULT_SCHEMA_SUMMARY = REPO_ROOT / "npc/params/schema/schema-summary.json"
DEFAULT_PROFILE_DIR = REPO_ROOT / "npc/params/profiles"
DEFAULT_MANIFEST_DIR = REPO_ROOT / "npc/support/qualified"

HASH_DOMAIN = "npc-effective-config-v1"
CANONICAL_SCHEMA_VERSION = 1

# Keys/sections that are explicitly outside the effective hardware/platform
# configuration.  Keep this list documented in docs/ParamSchemaAndProfiles.md.
TOP_LEVEL_EXCLUDED = {
    "schema",
    "config_hash",
    "effective_hash",
    "hash_domain",
    "build",
    "boot",
    "fpga",
    "metadata",
    "meta",
    "generated_at",
    "generated_by",
}

# Flat/build/debug knobs.  These are intentionally excluded from the
# effective hash and from profile base comparisons.
NONSEMANTIC_PREFIXES = (
    "CC_",
    "CXX_",
    "KCONFIG_",
    "SIM_",
    "VERILATOR_",
    "GSIM_",
    "TRACE_",
    "LOG_",
    "PERF_",
    "SYNTH_",
    "PGO_",
    "MARCH_",
    "LD_",
    "LIB_",
    "STDLIB_",
    "USER_CXX_",
    "BUILD_",
    "PROF_",
    "X_INITIAL_",
    "X_ASSIGN_",
    "PDK",
)
NONSEMANTIC_EXACT = {
    "ASSERT",
    "COVERAGE",
    "SDB",
    "DIFFTEST",
    "WATCHPOINT",
    "ITRACE",
    "MTRACE",
    "FTRACE",
    "IRINGBUF",
    "AUTOFLUSH",
    "SAVABLE",
    "OUTPUT_SPLIT",
}
NONSEMANTIC_KEY_RE = re.compile(
    r"(^|_)(PATH|DIR|DIRECTORY|FILE|TIMESTAMP|VERSION|DATE|TIME|SCHEMA|"
    r"CONFIG_HASH|EFFECTIVE_HASH|GENERATED_AT|GENERATED_BY)$",
    re.IGNORECASE,
)
NONSEMANTIC_VALUE_RE = re.compile(
    r"^(\d{4}-\d{2}-\d{2}([T ][0-9:.+-]+)?|"
    r"\d{2}:\d{2}(:\d{2})?|"
    r"(?:\d+\.){2,}\d+)$"
)

# A few Kconfig strings legitimately end in _PATH but carry an enum-like
# microarchitecture value rather than a filesystem path.
SEMANTIC_PATH_KEYS = {
    "MMU_PTW_PATH",
    "TIMER_PATH",
    "PTW_PATH",
}

PATH_EXTENSIONS = (
    ".vcd",
    ".fst",
    ".dts",
    ".dtb",
    ".bin",
    ".elf",
    ".img",
    ".cpio",
    ".json",
    ".log",
    ".txt",
)

MISSING = object()


class ParamError(Exception):
    """Error in an input document or tool invocation."""


class ProfileError(ParamError):
    """Invalid profile/manifest definition or predicate evaluation error."""


# ---------------------------------------------------------------------------
# JSON helpers
# ---------------------------------------------------------------------------

def read_json(path: os.PathLike | str) -> Any:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    except OSError as error:
        raise ParamError(f"cannot read {path}: {error}") from error
    except json.JSONDecodeError as error:
        raise ParamError(f"invalid JSON in {path}: {error}") from error


def write_json(path: os.PathLike | str, value: Any) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True)
    path.write_text(text + "\n", encoding="utf-8")


def dump_canonical_json(value: Any) -> str:
    """Stable byte representation used for hashing and diff-friendly files."""
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


# ---------------------------------------------------------------------------
# Schema loading / scalar normalization
# ---------------------------------------------------------------------------

def load_schema(path: Optional[os.PathLike | str] = None) -> Dict[str, Any]:
    schema_path = Path(path) if path else DEFAULT_SCHEMA
    if not schema_path.exists():
        return {}
    schema = read_json(schema_path)
    if not isinstance(schema, dict):
        raise ParamError(f"{schema_path}: schema root must be an object")
    return schema


def schema_parameter_map(schema: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    result: Dict[str, Dict[str, Any]] = {}
    for entry in schema.get("parameters", []):
        if isinstance(entry, dict) and isinstance(entry.get("name"), str):
            result[entry["name"]] = entry
    return result


def schema_flat_types(schema: Dict[str, Any]) -> Dict[str, str]:
    """Return the Kconfig type for each flat parameter.

    Choice options are treated as bool for value normalization, while their
    public schema ``type`` is ``choice``.
    """
    result: Dict[str, str] = {}
    for name, entry in schema_parameter_map(schema).items():
        kind = entry.get("kconfig_type") or entry.get("type") or "string"
        if kind == "tristate":
            kind = "bool"
        result[name] = kind
    return result


def schema_primary_names(schema: Dict[str, Any]) -> set[str]:
    """Parameters with a Kconfig prompt; only these are profile dimensions."""
    names: set[str] = set()
    for name, entry in schema_parameter_map(schema).items():
        if entry.get("prompt_exists") and not entry.get("derived", False):
            names.add(name)
    return names


def _parse_bool(value: Any) -> Optional[bool]:
    if isinstance(value, bool):
        return value
    if isinstance(value, int) and value in (0, 1):
        return bool(value)
    if isinstance(value, str):
        lowered = value.strip().lower()
        if lowered in ("y", "yes", "true", "1"):
            return True
        if lowered in ("n", "no", "false", "0", ""):
            return False
    return None


def _parse_number(value: Any) -> Optional[int]:
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    if isinstance(value, float) and value.is_integer():
        return int(value)
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return None
        try:
            return int(text, 0)
        except ValueError:
            return None
    return None


def normalize_scalar(value: Any, kconfig_type: Optional[str] = None) -> Any:
    """Normalize a scalar for canonical hashing/profile comparison.

    - bool/tristate (and choice options): y/n/1/0 -> bool
    - int/hex: decimal/0x values -> int
    - string: keep semantic text exactly as supplied
    - unknown: infer y/n and numeric forms, otherwise keep the string
    """
    if value is None:
        return None
    if isinstance(value, (dict, list)):
        return value
    kind = (kconfig_type or "").lower()
    if kind in ("bool", "tristate", "choice"):
        parsed = _parse_bool(value)
        return parsed if parsed is not None else value
    if kind in ("int", "hex"):
        parsed = _parse_number(value)
        return parsed if parsed is not None else value
    if kind == "string":
        return value

    if isinstance(value, str):
        parsed_bool = _parse_bool(value)
        if parsed_bool is not None and value.strip().lower() in ("y", "n"):
            return parsed_bool
        parsed_number = _parse_number(value)
        if parsed_number is not None:
            return parsed_number
    return value


def looks_like_path(value: Any) -> bool:
    if not isinstance(value, str) or not value:
        return False
    text = value.strip()
    if text.startswith(("/", "~", "./", "../")):
        return True
    if re.match(r"^[A-Za-z]:[\\/]", text):
        return True
    # Kernel command lines and other free-form text may contain '/'; only
    # treat a whitespace-free token as a path.
    if re.search(r"\s", text):
        return False
    if "/" in text or "\\" in text:
        return True
    return text.lower().endswith(PATH_EXTENSIONS)


def is_nonsemantic_key(key: str) -> bool:
    upper = key.upper()
    if upper in SEMANTIC_PATH_KEYS:
        return False
    if upper in NONSEMANTIC_EXACT:
        return True
    if upper.startswith(NONSEMANTIC_PREFIXES):
        return True
    if upper.endswith("_JOBS"):
        return True
    if NONSEMANTIC_KEY_RE.search(upper):
        return True
    return False


def is_nonsemantic_value(key: str, value: Any) -> bool:
    if is_nonsemantic_key(key):
        return True
    if not isinstance(value, str):
        return False
    if looks_like_path(value):
        return True
    if NONSEMANTIC_VALUE_RE.match(value.strip()):
        return True
    return False


# ---------------------------------------------------------------------------
# Canonical effective projection / hash
# ---------------------------------------------------------------------------

def _canonicalize_tree(
    value: Any,
    *,
    types: Dict[str, str],
    parent_key: Optional[str] = None,
) -> Any:
    if isinstance(value, dict):
        result: Dict[str, Any] = {}
        for key in sorted(value):
            if key in TOP_LEVEL_EXCLUDED and parent_key is None:
                continue
            if is_nonsemantic_key(key):
                continue
            child = value[key]
            if is_nonsemantic_value(key, child):
                continue
            result[key] = _canonicalize_tree(
                child, types=types, parent_key=key
            )
        return result
    if isinstance(value, list):
        return [
            _canonicalize_tree(item, types=types, parent_key=parent_key)
            for item in value
        ]
    kind = types.get(parent_key or "", None)
    return normalize_scalar(value, kind)


def canonical_from_typed(
    typed: Dict[str, Any],
    schema: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Build the canonical projection used by the effective hash."""
    if not isinstance(typed, dict):
        raise ParamError("typed config root must be a JSON object")
    schema = schema if schema is not None else load_schema()
    types = schema_flat_types(schema)

    flat_raw = typed.get("flat", {})
    if flat_raw is None:
        flat_raw = {}
    if not isinstance(flat_raw, dict):
        raise ParamError("typed config 'flat' must be an object")

    flat: Dict[str, Any] = {}
    for key in sorted(flat_raw):
        if is_nonsemantic_value(key, flat_raw[key]):
            continue
        flat[key] = normalize_scalar(flat_raw[key], types.get(key))

    values = _canonicalize_tree(
        {key: value for key, value in typed.items() if key != "flat"},
        types=types,
    )
    return {"values": values, "flat": flat}


def effective_hash(canonical: Dict[str, Any]) -> str:
    payload = HASH_DOMAIN + "\n" + dump_canonical_json(canonical)
    return sha256_text(payload)


def canonical_document(
    typed: Dict[str, Any],
    schema: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    canonical = canonical_from_typed(typed, schema)
    return {
        "effective_hash": effective_hash(canonical),
        "hash_domain": HASH_DOMAIN,
        "canonical_schema_version": CANONICAL_SCHEMA_VERSION,
        "canonical": canonical,
    }


def typed_flat(
    canonical_doc_or_typed: Dict[str, Any],
    schema: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Extract a normalized flat map from typed or canonical input."""
    if not isinstance(canonical_doc_or_typed, dict):
        raise ParamError("config root must be a JSON object")
    if "canonical" in canonical_doc_or_typed:
        canonical = canonical_doc_or_typed["canonical"]
        if isinstance(canonical, dict) and isinstance(canonical.get("flat"), dict):
            return canonical["flat"]
    flat = canonical_doc_or_typed.get("flat")
    if isinstance(flat, dict):
        types = schema_flat_types(schema) if schema else {}
        return {
            key: normalize_scalar(value, types.get(key))
            for key, value in flat.items()
            if not is_nonsemantic_value(key, value)
        }
    values = canonical_doc_or_typed.get("values")
    if isinstance(values, dict) and isinstance(values.get("flat"), dict):
        return values["flat"]
    raise ParamError("config has no typed 'flat' map")


def effective_hash_of_document(
    document: Dict[str, Any],
    schema: Optional[Dict[str, Any]] = None,
) -> str:
    if "canonical" in document:
        canonical = document["canonical"]
        if isinstance(canonical, dict) and "values" in canonical:
            computed = effective_hash(canonical)
            provided = document.get("effective_hash")
            if isinstance(provided, str) and provided != computed:
                raise ParamError(
                    "canonical document effective_hash does not match its "
                    "canonical payload"
                )
            return computed
    if isinstance(document.get("effective_hash"), str):
        return document["effective_hash"]
    # Accept raw typed JSON.
    return effective_hash(canonical_from_typed(document, schema))


# ---------------------------------------------------------------------------
# Profiles / manifests
# ---------------------------------------------------------------------------

PROFILE_REQUIRED = (
    "id",
    "version",
    "status",
    "description",
    "base",
    "allow",
    "predicate",
    "qualification",
)
ALLOW_KEYS = {"bool", "enum", "int_range", "list"}


def load_profile(path: os.PathLike | str) -> Dict[str, Any]:
    profile = read_json(path)
    validate_profile(profile)
    return profile


def validate_profile(profile: Dict[str, Any]) -> None:
    if not isinstance(profile, dict):
        raise ProfileError("profile root must be an object")
    missing = [key for key in PROFILE_REQUIRED if key not in profile]
    if missing:
        raise ProfileError(f"profile is missing required field(s): {', '.join(missing)}")
    if not isinstance(profile["id"], str) or not profile["id"]:
        raise ProfileError("profile.id must be a non-empty string")
    if not isinstance(profile["version"], int) or profile["version"] < 1:
        raise ProfileError("profile.version must be a positive integer")
    if not isinstance(profile["status"], str) or not profile["status"]:
        raise ProfileError("profile.status must be a non-empty string")
    if not isinstance(profile["description"], str):
        raise ProfileError("profile.description must be a string")

    base = profile["base"]
    if not isinstance(base, dict):
        raise ProfileError("profile.base must be an object")
    if not isinstance(base.get("source"), str) or not base["source"]:
        raise ProfileError("profile.base.source must be a non-empty string")
    if not isinstance(base.get("values"), dict):
        raise ProfileError("profile.base.values must be an object")
    for key, value in base["values"].items():
        if not isinstance(key, str):
            raise ProfileError("profile.base.values keys must be strings")
        _validate_scalar(value, f"base.values.{key}")

    allow = profile["allow"]
    if not isinstance(allow, dict):
        raise ProfileError("profile.allow must be an object")
    unknown = set(allow) - ALLOW_KEYS
    if unknown:
        raise ProfileError(
            "profile.allow has unknown dimension(s): " + ", ".join(sorted(unknown))
        )
    for key in ("bool", "enum", "int_range", "list"):
        if key in allow and not isinstance(allow[key], (list, dict)):
            raise ProfileError(f"profile.allow.{key} must be a list or object")

    for key in allow.get("bool", []):
        if not isinstance(key, str):
            raise ProfileError("profile.allow.bool entries must be strings")

    enum = allow.get("enum", {})
    if not isinstance(enum, dict):
        raise ProfileError("profile.allow.enum must be an object")
    for key, values in enum.items():
        if not isinstance(key, str) or not isinstance(values, list):
            raise ProfileError("profile.allow.enum must map names to value lists")
        for value in values:
            _validate_scalar(value, f"allow.enum.{key}")

    int_range = allow.get("int_range", {})
    if not isinstance(int_range, dict):
        raise ProfileError("profile.allow.int_range must be an object")
    for key, spec in int_range.items():
        if not isinstance(spec, dict):
            raise ProfileError(f"allow.int_range.{key} must be an object")
        for bound in ("min", "max"):
            if bound not in spec:
                raise ProfileError(f"allow.int_range.{key} is missing '{bound}'")
        if _parse_number(spec["min"]) is None or _parse_number(spec["max"]) is None:
            raise ProfileError(f"allow.int_range.{key} bounds must be numeric")
        if "step" in spec and _parse_number(spec["step"]) is None:
            raise ProfileError(f"allow.int_range.{key}.step must be numeric")

    lists = allow.get("list", {})
    if not isinstance(lists, dict):
        raise ProfileError("profile.allow.list must be an object")
    for key, spec in lists.items():
        if not isinstance(spec, dict):
            raise ProfileError(f"allow.list.{key} must be an object")
        if "allowed_items" in spec and not isinstance(spec["allowed_items"], list):
            raise ProfileError(f"allow.list.{key}.allowed_items must be a list")
        for bound in ("min_items", "max_items"):
            if bound in spec and not isinstance(spec[bound], int):
                raise ProfileError(f"allow.list.{key}.{bound} must be an integer")

    predicate = profile["predicate"]
    if not isinstance(predicate, list):
        raise ProfileError("profile.predicate must be a list")
    for clause in predicate:
        _validate_clause(clause)

    qualification = profile["qualification"]
    if not isinstance(qualification, dict):
        raise ProfileError("profile.qualification must be an object")
    suites = qualification.get("suites")
    if not isinstance(suites, list) or not all(isinstance(s, str) for s in suites):
        raise ProfileError("profile.qualification.suites must be a list of strings")
    if "manifest" in qualification and not isinstance(qualification["manifest"], str):
        raise ProfileError("profile.qualification.manifest must be a string")
    if "hashes" in qualification and not isinstance(qualification["hashes"], list):
        raise ProfileError("profile.qualification.hashes must be a list")


def _validate_scalar(value: Any, where: str) -> None:
    if isinstance(value, (str, int, float, bool)) or value is None:
        return
    raise ProfileError(f"{where}: expected a scalar value")


def _validate_clause(clause: Any) -> None:
    if isinstance(clause, dict) and "expr" in clause:
        if "id" in clause and not isinstance(clause["id"], str):
            raise ProfileError("predicate clause id must be a string")
        _validate_expr(clause["expr"])
        return
    _validate_expr(clause)


def _validate_expr(expr: Any) -> None:
    if not isinstance(expr, dict) or len(expr) != 1:
        raise ProfileError(f"predicate expression must be a single-key object: {expr!r}")
    op, arg = next(iter(expr.items()))
    if op in ("all", "any"):
        if not isinstance(arg, list) or not arg:
            raise ProfileError(f"'{op}' requires a non-empty list")
        for child in arg:
            _validate_expr(child)
        return
    if op == "not":
        _validate_expr(arg)
        return
    if op in ("eq", "ne", "in", "lt", "lte", "gt", "gte"):
        if isinstance(arg, list):
            if len(arg) != 2:
                raise ProfileError(f"'{op}' list form must be [path, value]")
            if not isinstance(arg[0], str):
                raise ProfileError(f"'{op}' path must be a string")
            return
        if not isinstance(arg, dict):
            raise ProfileError(f"'{op}' requires an object")
        if not isinstance(arg.get("path"), str):
            raise ProfileError(f"'{op}' requires string field 'path'")
        if op == "in":
            if not isinstance(arg.get("values"), list):
                raise ProfileError("'in' requires a list field 'values'")
        elif "value" not in arg:
            raise ProfileError(f"'{op}' requires field 'value'")
        return
    if op == "exists":
        if isinstance(arg, str):
            return
        if isinstance(arg, dict) and isinstance(arg.get("path"), str):
            return
        raise ProfileError("'exists' requires a path string")
    if op == "const":
        if not isinstance(arg, bool):
            raise ProfileError("'const' requires a boolean")
        return
    raise ProfileError(f"unknown predicate operator: {op}")


def load_manifest(path: Optional[os.PathLike | str]) -> Optional[Dict[str, Any]]:
    if not path:
        return None
    manifest = read_json(path)
    if not isinstance(manifest, dict):
        raise ProfileError("manifest root must be an object")
    if not isinstance(manifest.get("profile"), str):
        raise ProfileError("manifest.profile must be a string")
    if not isinstance(manifest.get("profile_version"), int):
        raise ProfileError("manifest.profile_version must be an integer")
    hashes = manifest.get("hashes")
    if not isinstance(hashes, list):
        raise ProfileError("manifest.hashes must be a list")
    for entry in hashes:
        if isinstance(entry, str) and entry:
            continue
        if isinstance(entry, dict) and isinstance(entry.get("effective_hash"), str):
            continue
        raise ProfileError("manifest.hashes entries must be hashes or objects")
    return manifest


def manifest_hashes(manifest: Optional[Dict[str, Any]]) -> set[str]:
    if not manifest:
        return set()
    result: set[str] = set()
    for entry in manifest.get("hashes", []):
        if isinstance(entry, str):
            result.add(entry)
        elif isinstance(entry, dict):
            result.add(entry["effective_hash"])
    return result


# ---------------------------------------------------------------------------
# Predicate evaluation
# ---------------------------------------------------------------------------

def resolve_path(root: Any, path: str) -> Any:
    if path == "":
        return root
    current = root
    for part in path.split("."):
        if isinstance(current, dict) and part in current:
            current = current[part]
        else:
            return MISSING
    return current


def _missing_matches(expected: Any) -> bool:
    """Absent Kconfig values behave like false/0 for equality predicates."""
    if expected is False:
        return True
    if isinstance(expected, (int, float)) and not isinstance(expected, bool) and expected == 0:
        return True
    if isinstance(expected, str) and expected.strip().lower() in ("n", "false", "0", ""):
        return True
    return False


def _compare_values(left: Any, right: Any) -> Tuple[Any, Any]:
    """Coerce bool/number/string pairs for profile predicates."""
    if isinstance(left, bool) and isinstance(right, (int, float)) and not isinstance(right, bool):
        return int(left), right
    if isinstance(right, bool) and isinstance(left, (int, float)) and not isinstance(left, bool):
        return left, int(right)
    if isinstance(left, str) and isinstance(right, bool):
        parsed = _parse_bool(left)
        if parsed is not None:
            return parsed, right
    if isinstance(right, str) and isinstance(left, bool):
        parsed = _parse_bool(right)
        if parsed is not None:
            return left, parsed
    if isinstance(left, str) and isinstance(right, (int, float)):
        parsed = _parse_number(left)
        if parsed is not None:
            return parsed, right
    if isinstance(right, str) and isinstance(left, (int, float)) and not isinstance(left, bool):
        parsed = _parse_number(right)
        if parsed is not None:
            return left, parsed
    return left, right


def eval_expr(expr: Dict[str, Any], root: Any) -> bool:
    if not isinstance(expr, dict) or len(expr) != 1:
        raise ProfileError(f"invalid predicate expression: {expr!r}")
    op, arg = next(iter(expr.items()))
    if op == "all":
        return all(eval_expr(child, root) for child in arg)
    if op == "any":
        return any(eval_expr(child, root) for child in arg)
    if op == "not":
        return not eval_expr(arg, root)
    if op == "const":
        return bool(arg)
    if op == "exists":
        path = arg if isinstance(arg, str) else arg["path"]
        return resolve_path(root, path) is not MISSING
    if op in ("eq", "ne", "in", "lt", "lte", "gt", "gte"):
        if isinstance(arg, list):
            path, expected = arg
        else:
            path = arg["path"]
            if op == "in":
                expected = arg["values"]
            else:
                expected = arg["value"]
        actual = resolve_path(root, path)
        if actual is MISSING:
            # Kconfig hides an option when its dependency is false (for
            # example LINUX_BOOT_ENABLE without SBI/Linux).  Treat an absent
            # value as false/zero for equality, so "feature is off" is
            # expressible; numeric ordering and exists still fail.
            if op == "in":
                return any(_missing_matches(item) for item in expected)
            if op in ("eq", "ne"):
                is_zero = _missing_matches(expected)
                return is_zero if op == "eq" else not is_zero
            return False
        if op == "in":
            for item in expected:
                left, right = _compare_values(actual, item)
                if left == right:
                    return True
            return False
        left, right = _compare_values(actual, expected)
        if op == "eq":
            return left == right
        if op == "ne":
            return left != right
        numeric_left = _parse_number(left)
        numeric_right = _parse_number(right)
        if numeric_left is None or numeric_right is None:
            raise ProfileError(f"numeric comparison on non-numeric value at {path}")
        if op == "lt":
            return numeric_left < numeric_right
        if op == "lte":
            return numeric_left <= numeric_right
        if op == "gt":
            return numeric_left > numeric_right
        if op == "gte":
            return numeric_left >= numeric_right
    raise ProfileError(f"unknown predicate operator: {op}")


def evaluate_predicates(profile: Dict[str, Any], root: Any) -> List[Dict[str, Any]]:
    failures: List[Dict[str, Any]] = []
    for index, clause in enumerate(profile["predicate"]):
        if isinstance(clause, dict) and "expr" in clause:
            clause_id = clause.get("id") or f"predicate[{index}]"
            description = clause.get("description", "")
            expr = clause["expr"]
        else:
            clause_id = f"predicate[{index}]"
            description = ""
            expr = clause
        try:
            ok = eval_expr(expr, root)
        except ProfileError as error:
            raise ProfileError(f"{clause_id}: {error}") from error
        if not ok:
            failures.append(
                {
                    "id": clause_id,
                    "description": description,
                    "expr": expr,
                }
            )
    return failures


# ---------------------------------------------------------------------------
# Allow / boundary checks
# ---------------------------------------------------------------------------

def _list_items(value: Any) -> List[Any]:
    if isinstance(value, list):
        return value
    if isinstance(value, str):
        if "," in value:
            return [item.strip() for item in value.split(",")]
        return [value]
    return [value]


def _items_equal(left: Any, right: Any) -> bool:
    return normalize_scalar(left) == normalize_scalar(right)


def check_boundary(
    profile: Dict[str, Any],
    flat: Dict[str, Any],
    *,
    primary_names: Optional[set[str]] = None,
) -> List[Dict[str, Any]]:
    """Return allow/base violations for the given effective flat map.

    When ``primary_names`` is supplied (from schema.json), derived parameters
    are ignored: they follow from primary parameters that are checked here.
    """
    base = profile["base"]["values"]
    allow = profile["allow"]
    bool_allow = set(allow.get("bool", []))
    enum_allow = allow.get("enum", {})
    range_allow = allow.get("int_range", {})
    list_allow = allow.get("list", {})

    violations: List[Dict[str, Any]] = []
    keys = set(base) | set(flat)
    for key in sorted(keys):
        if primary_names is not None and key not in primary_names:
            continue
        base_value = base.get(key, MISSING)
        actual = flat.get(key, MISSING)
        if base_value is MISSING and actual is MISSING:
            continue
        if base_value is not MISSING and actual is not MISSING:
            if normalize_scalar(actual) == normalize_scalar(base_value):
                continue
        # An explicitly allowed parameter may disappear when its Kconfig
        # dependency turns off (for example initramfs address when initramfs
        # is disabled).  This is still a supported deviation.
        if actual is MISSING and (
            key in bool_allow
            or key in enum_allow
            or key in range_allow
            or key in list_allow
        ):
            continue
        if key in bool_allow:
            parsed = _parse_bool(actual)
            if parsed is not None:
                continue
            violations.append(
                _violation(key, base_value, actual, "bool", "value is not boolean")
            )
            continue
        if key in enum_allow:
            allowed = enum_allow[key]
            if any(_items_equal(actual, item) for item in allowed):
                continue
            violations.append(
                _violation(key, base_value, actual, "enum", "value not in allow.enum")
            )
            continue
        if key in range_allow:
            spec = range_allow[key]
            number = _parse_number(actual)
            low = _parse_number(spec["min"])
            high = _parse_number(spec["max"])
            step = _parse_number(spec.get("step", 1)) or 1
            if number is None or low is None or high is None:
                violations.append(
                    _violation(key, base_value, actual, "int_range", "non-numeric value")
                )
            elif not (low <= number <= high):
                violations.append(
                    _violation(
                        key,
                        base_value,
                        actual,
                        "int_range",
                        f"value outside [{low}, {high}]",
                    )
                )
            elif step > 1 and (number - low) % step != 0:
                violations.append(
                    _violation(
                        key,
                        base_value,
                        actual,
                        "int_range",
                        f"value not on step {step} from {low}",
                    )
                )
            else:
                continue
            continue
        if key in list_allow:
            spec = list_allow[key]
            items = _list_items(actual)
            if "min_items" in spec and len(items) < spec["min_items"]:
                violations.append(
                    _violation(key, base_value, actual, "list", "too few items")
                )
                continue
            if "max_items" in spec and len(items) > spec["max_items"]:
                violations.append(
                    _violation(key, base_value, actual, "list", "too many items")
                )
                continue
            if "allowed_items" in spec:
                allowed = {dump_canonical_json(normalize_scalar(item)) for item in spec["allowed_items"]}
                bad = [
                    item
                    for item in items
                    if dump_canonical_json(normalize_scalar(item)) not in allowed
                ]
                if bad:
                    violations.append(
                        _violation(
                            key,
                            base_value,
                            actual,
                            "list",
                            "items not allowed: " + ",".join(str(item) for item in bad),
                        )
                    )
                    continue
            continue
        if base_value is MISSING:
            reason = "parameter not present in profile base"
        elif actual is MISSING:
            reason = "parameter missing from effective config"
        else:
            reason = "deviation outside allow dimensions"
        violations.append(_violation(key, base_value, actual, "base", reason))
    return violations


def _violation(
    key: str,
    base_value: Any,
    actual: Any,
    dimension: str,
    reason: str,
) -> Dict[str, Any]:
    return {
        "parameter": key,
        "base": None if base_value is MISSING else base_value,
        "effective": None if actual is MISSING else actual,
        "dimension": dimension,
        "reason": reason,
    }


# ---------------------------------------------------------------------------
# Classification
# ---------------------------------------------------------------------------

def evaluation_root(
    document: Dict[str, Any],
    schema: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Root used by predicates.

    Accepts typed JSON, canonical documents and plain nested objects.  A
    normalized ``flat`` map is always available at ``flat.*``.
    """
    if not isinstance(document, dict):
        raise ParamError("config root must be a JSON object")
    types = schema_flat_types(schema) if schema else {}
    if "canonical" in document and isinstance(document["canonical"], dict):
        canonical = document["canonical"]
        root = dict(canonical.get("values", {}))
        root["flat"] = dict(canonical.get("flat", {}))
        return root
    if "values" in document and isinstance(document["values"], dict):
        root = dict(document["values"])
        if isinstance(document.get("flat"), dict):
            root["flat"] = document["flat"]
        return root
    root = dict(document)
    flat = document.get("flat")
    if isinstance(flat, dict):
        root["flat"] = {
            key: normalize_scalar(value, types.get(key))
            for key, value in flat.items()
            if not is_nonsemantic_value(key, value)
        }
    return root


def classify(
    profile: Dict[str, Any],
    document: Dict[str, Any],
    *,
    manifest: Optional[Dict[str, Any]] = None,
    schema: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Classify one effective config against one profile.

    Status precedence:
      invalid            profile/manifest/predicate definition error, or a
                         manifest hash that contradicts profile checks
      qualified          checks pass and effective_hash is in the manifest
      profile-compatible checks pass but hash is not in the manifest
      experimental       checks fail (legal config outside profile boundary)
    """
    schema = schema if schema is not None else load_schema()
    try:
        validate_profile(profile)
        validate_manifest_if_needed(manifest, profile)
    except ProfileError as error:
        return _result(
            profile,
            document,
            "invalid",
            errors=[str(error)],
        )

    try:
        hash_value = effective_hash_of_document(document, schema)
    except ParamError as error:
        return _result(profile, document, "invalid", errors=[str(error)])

    try:
        flat = typed_flat(document, schema)
        root = evaluation_root(document, schema)
        predicate_failures = evaluate_predicates(profile, root)
    except ParamError as error:
        return _result(
            profile,
            document,
            "invalid",
            hash_value=hash_value,
            errors=[str(error)],
        )

    try:
        primary = schema_primary_names(schema) if schema else None
        violations = check_boundary(profile, flat, primary_names=primary)
    except ProfileError as error:
        return _result(
            profile,
            document,
            "invalid",
            hash_value=hash_value,
            predicate_failures=predicate_failures,
            errors=[str(error)],
        )

    manifest_set = manifest_hashes(manifest)
    in_manifest = hash_value in manifest_set
    passed = not violations and not predicate_failures

    errors: List[str] = []
    if in_manifest and not passed:
        errors.append(
            "effective hash is present in the qualified manifest but fails "
            "profile base/allow or predicate checks"
        )
    if manifest:
        if manifest.get("profile") != profile["id"]:
            errors.append(
                f"manifest profile '{manifest.get('profile')}' does not match "
                f"profile '{profile['id']}'"
            )
        if manifest.get("profile_version") != profile["version"]:
            errors.append(
                f"manifest profile_version {manifest.get('profile_version')} "
                f"does not match profile version {profile['version']}"
            )

    if errors:
        status = "invalid"
    elif passed:
        status = "qualified" if in_manifest else "profile-compatible"
    else:
        status = "experimental"

    return _result(
        profile,
        document,
        status,
        hash_value=hash_value,
        violations=violations,
        predicate_failures=predicate_failures,
        in_manifest=in_manifest,
        errors=errors,
    )


def validate_manifest_if_needed(
    manifest: Optional[Dict[str, Any]], profile: Dict[str, Any]
) -> None:
    if manifest is None:
        return
    if manifest.get("profile") != profile["id"]:
        # Kept as a classification error rather than a hard raise so callers
        # can see the status for the profile they asked about.
        return
    if manifest.get("profile_version") != profile["version"]:
        return


def _result(
    profile: Dict[str, Any],
    document: Dict[str, Any],
    status: str,
    *,
    hash_value: Optional[str] = None,
    violations: Optional[List[Dict[str, Any]]] = None,
    predicate_failures: Optional[List[Dict[str, Any]]] = None,
    in_manifest: bool = False,
    errors: Optional[List[str]] = None,
) -> Dict[str, Any]:
    violations = violations or []
    predicate_failures = predicate_failures or []
    errors = errors or []
    return {
        "profile": {
            "id": profile.get("id"),
            "version": profile.get("version"),
            "status": profile.get("status"),
        },
        "effective_hash": hash_value,
        "status": status,
        "profile_compatible": status in ("qualified", "profile-compatible"),
        "qualified": status == "qualified",
        "checks": {
            "base_allow": {
                "passed": not violations,
                "violations": violations,
            },
            "predicate": {
                "passed": not predicate_failures,
                "failures": predicate_failures,
            },
            "manifest": {
                "hash_present": in_manifest,
            },
        },
        "errors": errors,
    }


# ---------------------------------------------------------------------------
# Config generation helpers (used by the report driver)
# ---------------------------------------------------------------------------

def run_checked(command: Sequence[str], *, cwd: Optional[Path] = None) -> str:
    import subprocess

    try:
        completed = subprocess.run(
            list(command),
            cwd=str(cwd) if cwd else None,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
    except subprocess.CalledProcessError as error:
        raise ParamError(
            f"command failed ({' '.join(command)}):\n{error.stderr.strip()}"
        ) from error
    return completed.stdout


def prepare_kconfig_workdir(npc_dir: Path, workdir: Path) -> None:
    """Create a throw-away c_conf workdir without touching the repo."""
    import shutil

    if workdir.exists():
        shutil.rmtree(workdir)
    (workdir / "include/generated").mkdir(parents=True)
    (workdir / "include/config").mkdir(parents=True)
    (workdir / "Kconfig").symlink_to(npc_dir / "Kconfig")
    (workdir / "kconfig").symlink_to(npc_dir / "kconfig")


def generate_typed_config(
    npc_dir: Path,
    workdir: Path,
    defconfig: Path,
    *,
    conf_bin: Optional[Path] = None,
    config_gen_bin: Optional[Path] = None,
) -> Dict[str, Any]:
    """Generate typed JSON for one defconfig in an isolated workdir."""
    npc_dir = npc_dir.resolve()
    conf_bin = (conf_bin or npc_dir / "tools/build/c_conf").resolve()
    config_gen_bin = (
        config_gen_bin or npc_dir / "tools/build/npc_config_gen"
    ).resolve()
    for binary in (conf_bin, config_gen_bin):
        if not binary.exists():
            raise ParamError(f"required binary not found: {binary}")
    prepare_kconfig_workdir(npc_dir, workdir)
    run_checked(
        [str(conf_bin), "-s", f"--defconfig={defconfig.resolve()}", "Kconfig"],
        cwd=workdir,
    )
    run_checked([str(conf_bin), "-s", "--syncconfig", "Kconfig"], cwd=workdir)
    run_checked(
        [
            str(config_gen_bin),
            "--config-json",
            str(workdir / "include/generated/npc_config.json"),
            "--typed-json",
            str(workdir / "typed.json"),
        ],
        cwd=workdir,
    )
    return read_json(workdir / "typed.json")


# ---------------------------------------------------------------------------
# Misc
# ---------------------------------------------------------------------------

def profile_paths(directory: Optional[os.PathLike | str] = None) -> List[Path]:
    directory = Path(directory) if directory else DEFAULT_PROFILE_DIR
    return sorted(directory.glob("*.json"))


def relative_to_repo(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(REPO_ROOT))
    except ValueError:
        return str(path.resolve())
