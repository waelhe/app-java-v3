#!/usr/bin/env python3
"""Guard declared Saleor GraphQL root signatures against the pinned upstream SDL.

This checks wire signatures of Saleor root fields already declared by this
backend. It does not claim that all 458 Saleor roots or their full nested type
closures are implemented; execution coverage is provided by the required Maven
unit/integration suite and tracked by the canonical inventory.
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

from graphql import Source, parse, print_ast
from graphql.language.ast import ObjectTypeDefinitionNode, ObjectTypeExtensionNode

ROOT_TYPES = {"Query", "Mutation", "Subscription"}
ROOT = Path(__file__).resolve().parents[1]
DEFAULT_PINS = ROOT / "docs/governance/plans/saleor-source-pins.json"


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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", required=True, type=Path, help="verified official Saleor SDL")
    parser.add_argument("--schema-dir", required=True, type=Path, help="backend GraphQL SDL directory")
    parser.add_argument("--pins", type=Path, default=DEFAULT_PINS, help="immutable Saleor pin manifest")
    parser.add_argument("--summary", type=Path, help="optional GitHub Actions step summary")
    args = parser.parse_args()

    pins = json.loads(args.pins.read_text(encoding="utf-8"))
    reference_pin = pins["referenceSchema"]
    upstream = read_root_fields([args.reference], "official Saleor")
    local_paths = sorted(args.schema_dir.rglob("*.graphqls"))
    if not local_paths:
        fail(f"no .graphqls files found under {args.schema_dir}")
    local = read_root_fields(local_paths, "backend")

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
    report = [
        "## Saleor root contract guard",
        "",
        f"- Official reference: {reference_pin['repository']}@{reference_pin['ref']}",
        f"- Verified Saleor root inventory: Query {actual_counts['Query']}, Mutation {actual_counts['Mutation']}, Subscription {actual_counts['Subscription']} = {len(upstream)}.",
        f"- Backend root fields compared to Saleor by name: {len(shared)}.",
        f"- Signature mismatches: {len(mismatches)}.",
        f"- Saleor roots not yet declared by the backend: {len(upstream_only)} (tracked by the canonical implementation inventory; absence is not reported as implemented).",
        f"- Backend-only root fields: {len(local_only)} (platform extensions; not treated as Saleor roots).",
        "- Scope: checks root field return types, argument names/types and argument defaults. It does not claim full nested type-closure parity or operation-level execution coverage.",
        "",
    ]
    if mismatches:
        report.extend(["### Root signature mismatches", ""])
        for type_name, name, expected, actual in mismatches[:100]:
            report.append(f"- {type_name}.{name}")
            report.append(f"  - Saleor: {expected}")
            report.append(f"  - Backend: {actual}")
        if len(mismatches) > 100:
            report.append(f"- ... and {len(mismatches) - 100} additional mismatches")
    output = "\n".join(report) + "\n"
    print(output, end="")
    if args.summary:
        args.summary.parent.mkdir(parents=True, exist_ok=True)
        with args.summary.open("a", encoding="utf-8") as handle:
            handle.write(output)

    if mismatches:
        fail(
            "the backend changes the wire signature of declared Saleor roots; "
            "preserve the pinned Saleor signature or record/implement an explicitly approved contract decision"
        )


if __name__ == "__main__":
    main()
