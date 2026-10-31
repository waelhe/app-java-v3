#!/usr/bin/env python3
"""Guard declared Saleor GraphQL root signatures against the pinned upstream SDL.

This checks wire signatures of Saleor root fields already declared by this
backend. It does not claim that all 458 Saleor roots or their full nested type
closures are implemented; execution coverage is provided by the required Maven
unit/integration suite and tracked by the canonical inventory.

Registered divergences (E-21 closure, 2026-10-27): the failure messages used
to promise "record an approved contract decision / divergence" while this
script read no registry at all — a message/behavior drift inside the
enforcement layer itself (defect-knowledge case E-21, gap G3). The guard now
reads the repository's registered-divergence CSV
(docs/governance/plans/saleor-contract-divergences.csv — the same registry the
canonical-plan gate validates). Semantics, fail-closed throughout:

- A signature mismatch, guarded input-contract change or PR-base regression on
  a surface whose name appears in an APPROVED row is honored as a registered
  divergence: reported in the step summary, never silent, and not fatal.
- Rows that are not APPROVED excuse nothing; when such a row mentions the
  failing surface the failure message says so, pointing at the real remedy.
- An APPROVED row whose decision_record is a PENDING placeholder fails the
  gate (registry integrity), mirroring the canonical-plan gate's rule.
- Matching is whole-identifier: the surface text must contain the exact root,
  field or input-type name (e.g. "User avatar mutations (userAvatarUpdate /
  userAvatarDelete)" matches userAvatarUpdate, not a lookalike prefix).
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from collections import Counter
from pathlib import Path

from graphql import Source, parse, print_ast
from graphql.language.ast import (
    InputObjectTypeDefinitionNode,
    ObjectTypeDefinitionNode,
    ObjectTypeExtensionNode,
)

ROOT_TYPES = {"Query", "Mutation", "Subscription"}
ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PINS = ROOT / "docs/governance/plans/saleor-source-pins.json"
DEFAULT_DIVERGENCES = ROOT / "docs/governance/plans/saleor-contract-divergences.csv"
ALLOWED_STATUSES = {"IMPLEMENTATION_REQUIRED", "OWNER_ADR_REQUIRED", "APPROVED"}
IDENTIFIER_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")

# These input objects are on active CMS mutation paths. Root-field signature
# checks alone cannot catch drift inside an input object, so pin their fields
# and nullability to Saleor 3.23.43 until type-closure parity is generalized.
INPUT_FIELD_CONTRACTS = {
    "MenuItemCreateInput": {
        "name": "String!",
        "menu": "ID!",
        "parent": "ID",
        "url": "String",
        "category": "ID",
        "collection": "ID",
        "page": "ID",
    },
    "MenuItemInput": {
        "name": "String",
        "url": "String",
        "category": "ID",
        "collection": "ID",
        "page": "ID",
    },
    "PageTypeUpdateInput": {
        "name": "String",
        "slug": "String",
        "addAttributes": "[ID!]",
        "removeAttributes": "[ID!]",
    },
}


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def field_signature(field) -> str:
    """Match the canonical inventory's root-level wire-signature definition."""
    args = []
    for arg in field.arguments or []:
        default = print_ast(arg.default_value) if arg.default_value is not None else ""
        args.append((arg.name.value, print_ast(arg.type), default))
    args.sort()
    return f"({args}):{print_ast(field.type)}"


def read_divergence_registry(path: Path) -> list[dict[str, str]]:
    """Reads the registered-divergence CSV the failure messages point at.

    The canonical-plan gate owns the registry's full schema validation; this
    reader validates only what this guard consumes, fail-closed: the three
    columns it reads must exist, rows must not be empty, approval statuses must
    be known, and an APPROVED row must carry a real decision record instead of
    a PENDING placeholder. A registry that cannot be read fails the guard —
    the registered-exception route must not silently vanish.
    """
    required = {"saleor_surface", "approval_status", "decision_record"}
    try:
        with path.open(encoding="utf-8", newline="") as handle:
            reader = csv.DictReader(handle)
            if not reader.fieldnames or not required.issubset(set(reader.fieldnames)):
                fail(
                    f"divergence registry {path} is missing required "
                    f"columns: {sorted(required - set(reader.fieldnames or []))}"
                )
            rows = list(reader)
    except OSError as exc:
        fail(f"divergence registry {path} cannot be read: {exc}")
    if not rows:
        fail(
            f"divergence registry {path} is empty; "
            "the guard's registered-exception route requires rows to exist"
        )
    for row in rows:
        surface = (row.get("saleor_surface") or "").strip()
        if not surface:
            fail("divergence registry has a row with an empty saleor_surface")
        if row.get("approval_status") not in ALLOWED_STATUSES:
            fail(f"divergence row '{surface}' has invalid approval_status {row.get('approval_status')!r}")
        if row.get("approval_status") == "APPROVED" and (row.get("decision_record") or "").startswith("PENDING"):
            fail(f"approved divergence '{surface}' has no decision record")
    return rows


def surface_identifiers(surface: str) -> set[str]:
    return set(IDENTIFIER_RE.findall(surface))


def registered_surface_names(
    rows: list[dict[str, str]],
) -> tuple[dict[str, str], dict[str, str]]:
    """Splits registry surfaces into (approved, pending) identifier maps.

    approved maps an identifier to the row surface that covers it; pending maps
    an identifier to the non-approved row status that mentions it (for failure
    hints only — pending rows never excuse a failure).
    """
    approved: dict[str, str] = {}
    pending: dict[str, str] = {}
    for row in rows:
        status = row["approval_status"]
        for identifier in surface_identifiers(row["saleor_surface"]):
            if status == "APPROVED":
                approved.setdefault(identifier, row["saleor_surface"])
            else:
                pending.setdefault(identifier, status)
    return approved, {k: v for k, v in pending.items() if k not in approved}


def pending_hint(names: set[str], pending: dict[str, str]) -> str:
    """One parenthetical hint naming registry rows that mention failing surfaces."""
    mentioned = sorted(names & set(pending))
    if not mentioned:
        return ""
    details = "; ".join(f"{name} appears in a registry row with approval_status {pending[name]}" for name in mentioned)
    return f" ({details})"


def read_root_fields(schema_files: list[Path], label: str) -> dict[tuple[str, str], object]:
    fields: dict[tuple[str, str], object] = {}
    for path in sorted(schema_files):
        try:
            document = parse(Source(path.read_text(encoding="utf-8"), name=str(path)))
        except Exception as exc:
            fail(f"could not parse {label} SDL {path}: {exc}")
        for definition in document.definitions:
            if not isinstance(definition, (ObjectTypeDefinitionNode, ObjectTypeExtensionNode)):
                continue
            type_name = definition.name.value
            if type_name not in ROOT_TYPES:
                continue
            for field in definition.fields or []:
                key = (type_name, field.name.value)
                if key in fields:
                    fail(
                        f"{label} SDL declares {type_name}.{field.name.value} more than once "
                        f"(latest declaration in {path}); root fields must have one unambiguous signature"
                    )
                fields[key] = field
    if not fields:
        fail(f"no Query/Mutation/Subscription root fields were found in {label} SDL")
    return fields


def read_input_contracts(schema_files: list[Path], label: str) -> dict[str, dict[str, str]]:
    found: dict[str, dict[str, str]] = {}
    wanted = set(INPUT_FIELD_CONTRACTS)
    for path in sorted(schema_files):
        try:
            document = parse(Source(path.read_text(encoding="utf-8"), name=str(path)))
        except Exception as exc:
            fail(f"could not parse {label} SDL {path}: {exc}")
        for definition in document.definitions:
            if not isinstance(definition, InputObjectTypeDefinitionNode):
                continue
            type_name = definition.name.value
            if type_name not in wanted:
                continue
            if type_name in found:
                fail(f"{label} SDL defines input object {type_name} more than once")
            found[type_name] = {
                field.name.value: print_ast(field.type)
                for field in (definition.fields or [])
            }
    missing_types = wanted - set(found)
    if missing_types:
        fail(f"{label} SDL is missing guarded input types: {sorted(missing_types)}")
    return found


def assert_input_contracts(reference_files: list[Path], local_files: list[Path]) -> list[str]:
    reference = read_input_contracts(reference_files, "official Saleor")
    local = read_input_contracts(local_files, "backend")
    failures = []
    for type_name, expected in INPUT_FIELD_CONTRACTS.items():
        if reference[type_name] != expected:
            fail(
                f"guard definition for {type_name} is stale versus official Saleor: "
                f"expected {expected}, actual {reference[type_name]}"
            )
        actual = local[type_name]
        if actual != expected:
            failures.append(f"{type_name}: expected {expected}, backend has {actual}")
    return failures


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", required=True, type=Path, help="verified official Saleor SDL")
    parser.add_argument("--schema-dir", required=True, type=Path, help="backend GraphQL SDL directory")
    parser.add_argument("--base-schema-dir", type=Path, help="PR base branch SDL directory; root removals/signature changes fail")
    parser.add_argument("--pins", type=Path, default=DEFAULT_PINS, help="immutable Saleor pin manifest")
    parser.add_argument(
        "--divergences",
        type=Path,
        default=DEFAULT_DIVERGENCES,
        help="registered-divergence CSV whose APPROVED rows excuse surfaces they name",
    )
    parser.add_argument("--summary", type=Path, help="optional GitHub Actions step summary")
    args = parser.parse_args()

    pins = json.loads(args.pins.read_text(encoding="utf-8"))
    reference_pin = pins["referenceSchema"]
    divergence_rows = read_divergence_registry(args.divergences)
    approved, pending = registered_surface_names(divergence_rows)
    approved_row_count = sum(1 for row in divergence_rows if row["approval_status"] == "APPROVED")

    upstream = read_root_fields([args.reference], "official Saleor")
    local_paths = sorted(args.schema_dir.rglob("*.graphqls"))
    if not local_paths:
        fail(f"no .graphqls files found under {args.schema_dir}")
    local = read_root_fields(local_paths, "backend")
    input_contract_failures = assert_input_contracts([args.reference], local_paths)

    upstream_counts = Counter(type_name for type_name, _ in upstream)
    expected_counts = {
        "Query": reference_pin["queryRoots"],
        "Mutation": reference_pin["mutationRoots"],
        "Subscription": reference_pin["subscriptionRoots"],
    }
    actual_counts = {name: upstream_counts[name] for name in sorted(ROOT_TYPES)}
    if actual_counts != expected_counts:
        fail(f"official Saleor root inventory differs from pinned manifest: expected {expected_counts}, got {actual_counts}")
    if len(upstream) != reference_pin["totalRoots"]:
        fail(
            f"official Saleor has {len(upstream)} root fields, expected "
            f"{reference_pin['totalRoots']} from the pinned manifest"
        )

    shared = sorted(set(local).intersection(upstream))
    if not shared:
        fail("backend declares no root names shared with the pinned Saleor schema; refusing a vacuous pass")

    mismatches = []
    for key in shared:
        expected = field_signature(upstream[key])
        actual = field_signature(local[key])
        if actual != expected:
            mismatches.append((key[0], key[1], expected, actual))

    upstream_only = sorted(set(upstream) - set(local))
    local_only = sorted(set(local) - set(upstream))

    base_regressions = []
    if args.base_schema_dir:
        base_paths = sorted(args.base_schema_dir.rglob("*.graphqls"))
        if not base_paths:
            fail(f"no .graphqls files found in PR base schema directory {args.base_schema_dir}")
        base_fields = read_root_fields(base_paths, "PR base")
        for key in sorted(base_fields):
            base_signature = field_signature(base_fields[key])
            current_field = local.get(key)
            if current_field is None:
                base_regressions.append((key[0], key[1], "REMOVED", base_signature, ""))
            else:
                current_signature = field_signature(current_field)
                if current_signature != base_signature:
                    base_regressions.append((key[0], key[1], "SIGNATURE_CHANGED", base_signature, current_signature))

    registered_mismatches = [m for m in mismatches if m[1] in approved]
    unregistered_mismatches = [m for m in mismatches if m[1] not in approved]
    registered_input_failures = [f for f in input_contract_failures if f.split(":", 1)[0] in approved]
    unregistered_input_failures = [f for f in input_contract_failures if f.split(":", 1)[0] not in approved]
    registered_base_regressions = [r for r in base_regressions if r[1] in approved]
    unregistered_base_regressions = [r for r in base_regressions if r[1] not in approved]

    report = [
        "## Saleor root contract guard",
        "",
        f"- Official reference: {reference_pin['repository']}@{reference_pin['ref']}",
        f"- Verified Saleor root inventory: Query {actual_counts['Query']}, Mutation {actual_counts['Mutation']}, Subscription {actual_counts['Subscription']} = {len(upstream)}.",
        f"- Backend root fields compared to Saleor by name: {len(shared)}.",
        f"- Signature mismatches against Saleor: {len(mismatches)} ({len(registered_mismatches)} honored as approved divergences).",
        f"- Guarded active input-object contract mismatches: {len(input_contract_failures)} ({len(registered_input_failures)} honored as approved divergences).",
        f"- PR-base root removals/signature changes: {len(base_regressions)} ({len(registered_base_regressions)} honored as approved divergences).",
        f"- Saleor roots not yet declared by the backend: {len(upstream_only)} (tracked by the canonical implementation inventory; absence is not reported as implemented).",
        f"- Backend-only root fields: {len(local_only)} (platform extensions; not treated as Saleor roots).",
        f"- Divergence registry: {len(divergence_rows)} rows ({approved_row_count} approved). Unapproved rows never excuse a mismatch.",
        "- Scope: checks root field return types, argument names/types and argument defaults. It does not claim full nested type-closure parity or operation-level execution coverage.",
        "",
    ]
    if input_contract_failures:
        report.extend(["### Active input-object contract mismatches", ""])
        report.extend(f"- {failure}" for failure in input_contract_failures)
        report.append("")
    if registered_mismatches or registered_input_failures or registered_base_regressions:
        report.extend(["### Registered divergences honored (approved rows)", ""])
        for type_name, name, expected, actual in registered_mismatches:
            report.append(f"- {type_name}.{name} — covered by registry row: {approved[name]}")
            report.append(f"  - Saleor: {expected}")
            report.append(f"  - Backend: {actual}")
        for failure in registered_input_failures:
            report.append(f"- {failure} — covered by registry row: {approved[failure.split(':', 1)[0]]}")
        for type_name, name, reason, previous, current in registered_base_regressions:
            report.append(f"- {type_name}.{name}: {reason} — covered by registry row: {approved[name]}")
            report.append(f"  - PR base: {previous}")
            report.append(f"  - Current: {current or '(removed)'}")
        report.append("")
    if unregistered_mismatches:
        report.extend(["### Root signature mismatches against Saleor", ""])
        for type_name, name, expected, actual in unregistered_mismatches[:100]:
            report.append(f"- {type_name}.{name}")
            report.append(f"  - Saleor: {expected}")
            report.append(f"  - Backend: {actual}")
        if len(unregistered_mismatches) > 100:
            report.append(f"- ... and {len(unregistered_mismatches) - 100} additional mismatches")
    if unregistered_base_regressions:
        report.extend(["", "### PR-base schema regressions", ""])
        for type_name, name, reason, previous, current in unregistered_base_regressions[:100]:
            report.append(f"- {type_name}.{name}: {reason}")
            report.append(f"  - PR base: {previous}")
            report.append(f"  - Current: {current or '(removed)'}")
        if len(unregistered_base_regressions) > 100:
            report.append(f"- ... and {len(unregistered_base_regressions) - 100} additional base regressions")
    output = "\n".join(report) + "\n"
    print(output, end="")
    if args.summary:
        args.summary.parent.mkdir(parents=True, exist_ok=True)
        with args.summary.open("a", encoding="utf-8") as handle:
            handle.write(output)

    if unregistered_mismatches:
        fail(
            "the backend changes the wire signature of declared Saleor roots without an approved "
            "divergence; restore the pinned Saleor signature, or record a divergence row naming "
            "the root in the divergences registry and get that row approved"
            + pending_hint({m[1] for m in unregistered_mismatches}, pending)
        )
    if unregistered_base_regressions:
        fail(
            "the backend removes or changes a GraphQL root declared on the PR base without an "
            "approved divergence; restore the existing wire contract, or record an approved "
            "divergence row naming the root in the divergences registry"
            + pending_hint({r[1] for r in unregistered_base_regressions}, pending)
        )
    if unregistered_input_failures:
        fail(
            "the backend changes a guarded active Saleor input-object contract without an approved "
            "divergence; restore the pinned field names and nullability, or record an approved "
            "divergence row naming the input type in the divergences registry"
            + pending_hint({f.split(':', 1)[0] for f in unregistered_input_failures}, pending)
        )


if __name__ == "__main__":
    main()
