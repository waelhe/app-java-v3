#!/usr/bin/env python3
"""Validate canonical Saleor governance and produce a per-root coverage artifact."""
from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import Counter
from pathlib import Path

from graphql import parse, print_ast
from graphql.language.ast import ObjectTypeDefinitionNode, StringValueNode

ROOT = Path(__file__).resolve().parents[1]
PINS = ROOT / "docs/governance/plans/saleor-source-pins.json"
MATRIX = ROOT / "docs/governance/plans/saleor-commerce-domain-matrix.csv"
DIVERGENCES = ROOT / "docs/governance/plans/saleor-contract-divergences.csv"
CANONICAL = ROOT / "docs/governance/plans/saleor-dashboard-full-commerce-canonical-plan.md"
SUPERSEDED = [
    ROOT / "docs/governance/plans/saleor-dashboard-marketplace-adaptation-plan.md",
    ROOT / "docs/research/saleor-schema-parity-plan.md",
]
EXPECTED = {
    # Values are (Query roots, Mutation roots, Subscription roots, phase).
    # The independent total_roots column is validated as Query + Mutation + Subscription.
    "Products": (15, 63, 0, "P2"),
    "Users": (10, 43, 0, "P1"),
    "Orders": (7, 45, 0, "P4"),
    "Discounts": (6, 27, 0, "P3"),
    "Checkout": (3, 20, 0, "P4"),
    "Apps": (5, 16, 0, "P5"),
    "Gift cards": (5, 16, 0, "P3"),
    "Payments": (4, 17, 0, "P4"),
    "Attributes": (2, 15, 0, "P2"),
    "Pages": (4, 14, 0, "P5"),
    "Menu": (4, 11, 0, "P5"),
    "Shipping": (2, 13, 0, "P4"),
    "Taxes": (7, 7, 0, "P3"),
    "Channels": (2, 6, 0, "P3"),
    "Authentication": (0, 9, 0, "P1"),
    "Shop": (2, 7, 0, "P3"),
    "Webhooks": (3, 6, 0, "P5"),
    "Uncategorized": (9, 7, 0, "P5"),
    "Subscriptions": (0, 0, 26, "P6"),
}
PHASE_TOTALS = {"P1": 62, "P2": 95, "P3": 85, "P4": 111, "P5": 79, "P6": 26}


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def root_types(document):
    return {
        definition.name.value: definition
        for definition in document.definitions
        if isinstance(definition, ObjectTypeDefinitionNode)
        and definition.name.value in {"Query", "Mutation", "Subscription"}
    }


def root_counts(document):
    types = root_types(document)
    return {
        name: len(types[name].fields or []) if name in types else 0
        for name in ("Query", "Mutation", "Subscription")
    }


def category_of(field) -> str:
    for directive in field.directives or []:
        if directive.name.value != "doc":
            continue
        for argument in directive.arguments or []:
            if argument.name.value == "category" and isinstance(argument.value, StringValueNode):
                return " ".join(argument.value.value.split())
    return "Uncategorized"


def signature(field) -> str:
    # Root-level signature only. This is not full nested type-closure parity.
    args = []
    for arg in field.arguments or []:
        default = print_ast(arg.default_value) if arg.default_value is not None else ""
        args.append((arg.name.value, print_ast(arg.type), default))
    args.sort()
    return f"({args}):{print_ast(field.type)}"


def fields_for(document, type_name):
    definition = root_types(document).get(type_name)
    return {field.name.value: field for field in (definition.fields or [])} if definition else {}


def read_matrix():
    with MATRIX.open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    if not rows:
        fail("domain matrix is empty")
    names = [row["saleor_category"] for row in rows]
    if len(names) != len(set(names)):
        fail("duplicate category row in domain matrix")
    if set(names) != set(EXPECTED):
        fail(f"matrix category set mismatch: expected {sorted(EXPECTED)}, got {sorted(names)}")
    phase_counts = Counter()
    total = 0
    for row in rows:
        name = row["saleor_category"]
        q, m, s, phase = EXPECTED[name]
        expected = (q, m, s, q + m + s, phase)
        actual = (
            int(row["query_roots"]),
            int(row["mutation_roots"]),
            int(row["subscription_roots"]),
            int(row["total_roots"]),
            row["phase"],
        )
        if actual != expected:
            fail(f"matrix row {name}: expected {expected}, got {actual}")
        phase_counts[phase] += q + m + s
        total += q + m + s
        for required in ("owner_modules", "target_policy", "automatic_acceptance"):
            if not row[required].strip():
                fail(f"matrix row {name} has empty {required}")
    if total != 458:
        fail(f"matrix totals to {total}, expected 458")
    if dict(phase_counts) != PHASE_TOTALS:
        fail(f"phase totals differ: expected {PHASE_TOTALS}, got {dict(phase_counts)}")
    return rows


def read_divergences():
    required_fields = {
        "saleor_surface", "disposition", "public_wire_requirement",
        "internal_mapping", "owner_modules", "affected_dashboard_surfaces",
        "required_acceptance", "approval_status", "decision_record",
        "decision_basis", "review_trigger",
    }
    allowed_dispositions = {"ADOPT", "ADAPT", "DEFER", "N_A_WITH_ADR", "KEEP_EXTENSION"}
    allowed_statuses = {"IMPLEMENTATION_REQUIRED", "OWNER_ADR_REQUIRED", "APPROVED"}
    with DIVERGENCES.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if not reader.fieldnames or not required_fields.issubset(set(reader.fieldnames)):
            fail(f"divergence registry missing required columns: {sorted(required_fields - set(reader.fieldnames or []))}")
        rows = list(reader)
    if not rows:
        fail("divergence registry is empty; all wire-level exceptions must be recorded")
    surfaces = [row["saleor_surface"] for row in rows]
    if len(surfaces) != len(set(surfaces)):
        fail("duplicate saleor_surface in divergence registry")
    for row in rows:
        for key in required_fields:
            if not (row.get(key) or "").strip():
                fail(f"divergence row {row.get('saleor_surface', '<unknown>')} has empty {key}")
        if row["disposition"] not in allowed_dispositions:
            fail(f"divergence row {row['saleor_surface']} has invalid disposition {row['disposition']}")
        if row["approval_status"] not in allowed_statuses:
            fail(f"divergence row {row['saleor_surface']} has invalid approval_status {row['approval_status']}")
        if row["approval_status"] == "APPROVED" and row["decision_record"].startswith("PENDING"):
            fail(f"approved divergence {row['saleor_surface']} has no decision record")
        if row["approval_status"] == "OWNER_ADR_REQUIRED" and not row["decision_record"].startswith("PENDING"):
            fail(f"unapproved divergence {row['saleor_surface']} must identify its pending ADR")
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--schema", required=True, type=Path)
    parser.add_argument("--published", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--summary", type=Path)
    parser.add_argument("--require-exact-root-signatures", "--require-full-coverage", dest="require_exact_root_signatures", action="store_true", help="Diagnostic only: fail unless all 458 root names/signatures match literally; this is not semantic or end-to-end coverage.")
    parser.add_argument("--require-approved-divergences", action="store_true", help="Fail unless every registered divergence has an approved decision record.")
    args = parser.parse_args()

    pins = json.loads(PINS.read_text(encoding="utf-8"))
    reference = pins["referenceSchema"]
    if reference["queryRoots"] + reference["mutationRoots"] + reference["subscriptionRoots"] != reference["totalRoots"]:
        fail("source pins manifest root counts do not add up")
    if not CANONICAL.exists():
        fail("canonical plan missing")
    canonical_text = CANONICAL.read_text(encoding="utf-8")
    if "D-SALEOR-FULL-001" not in canonical_text:
        fail("canonical decision ID missing from plan")
    for old_path in SUPERSEDED:
        if not old_path.exists():
            fail(f"historical plan missing; preserve the record and marker: {old_path}")
        old_text = old_path.read_text(encoding="utf-8")[:2500]
        if "SUPERSEDED — HISTORICAL EVIDENCE ONLY" not in old_text:
            fail(f"superseded marker was removed from {old_path.relative_to(ROOT)}")
        if "saleor-dashboard-full-commerce-canonical-plan.md" not in old_text:
            fail(f"canonical pointer missing from {old_path.relative_to(ROOT)}")

    matrix_rows = read_matrix()
    divergence_rows = read_divergences()
    source_doc = parse(args.schema.read_text(encoding="utf-8"))
    published_doc = parse(args.published.read_text(encoding="utf-8"))
    source_roots = root_types(source_doc)
    counts = root_counts(source_doc)
    measured_total = sum(counts.values())
    expected_counts = {
        "Query": reference["queryRoots"],
        "Mutation": reference["mutationRoots"],
        "Subscription": reference["subscriptionRoots"],
    }
    if counts != expected_counts or measured_total != reference["totalRoots"]:
        fail(f"pinned schema root-count drift: expected {expected_counts}, got {counts}")

    source_category_counts = Counter()
    for typename in ("Query", "Mutation"):
        for field in source_roots[typename].fields or []:
            source_category_counts[category_of(field)] += 1
    matrix_counts = {
        row["saleor_category"]: int(row["query_roots"]) + int(row["mutation_roots"])
        for row in matrix_rows if row["saleor_category"] != "Subscriptions"
    }
    if dict(source_category_counts) != dict(Counter(matrix_counts)):
        fail(
            "source @doc(category) counts differ from matrix: "
            f"source={dict(source_category_counts)}, matrix={dict(Counter(matrix_counts))}"
        )

    matrix_by_category = {row["saleor_category"]: row for row in matrix_rows}
    totals = Counter()
    category_status = Counter()
    category_roots = Counter()
    records = []
    for typename in ("Query", "Mutation", "Subscription"):
        source_fields = fields_for(source_doc, typename)
        target_fields = fields_for(published_doc, typename)
        for name, field in sorted(source_fields.items()):
            category = "Subscriptions" if typename == "Subscription" else category_of(field)
            target = target_fields.get(name)
            if target is None:
                status = "MISSING_ROOT_NAME"
            elif signature(target) != signature(field):
                status = "ROOT_SIGNATURE_DIFFERS"
            else:
                status = "ROOT_SIGNATURE_MATCHES_TYPE_CLOSURE_UNVERIFIED"
            totals[status] += 1
            category_status[(category, status)] += 1
            category_roots[category] += 1
            domain_row = matrix_by_category.get(category)
            if domain_row is None:
                fail(f"root {typename}.{name} has no reviewed matrix owner row for category {category}")
            records.append({
                "root_type": typename,
                "root_name": name,
                "category": category,
                "phase": domain_row["phase"],
                "owner_modules": domain_row["owner_modules"],
                "target_policy": domain_row["target_policy"],
                "automatic_acceptance": domain_row["automatic_acceptance"],
                "reference_signature": signature(field),
                "published_signature": signature(target) if target else "",
                "status": status,
            })

    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=[
            "root_type", "root_name", "category", "phase", "owner_modules",
            "target_policy", "automatic_acceptance", "reference_signature",
            "published_signature", "status"
        ])
        writer.writeheader()
        writer.writerows(records)

    exact = totals["ROOT_SIGNATURE_MATCHES_TYPE_CLOSURE_UNVERIFIED"]
    missing = totals["MISSING_ROOT_NAME"]
    diff = totals["ROOT_SIGNATURE_DIFFERS"]
    report = [
        "## Saleor canonical schema inventory",
        "",
        f"- Reference pin: {reference['repository']}@{reference['ref']}",
        f"- Reference roots: Query {counts['Query']}, Mutation {counts['Mutation']}, Subscription {counts['Subscription']} = **{measured_total}**",
        f"- Published backend roots with the same root name and signature: **{exact}/{measured_total} ({exact / measured_total * 100:.2f}%)**",
        f"- Missing root names: **{missing}**; same-name signature differences: **{diff}**",
        "- This is root-signature inventory only; it is not implementation, nested type-closure, authorization or Dashboard E2E coverage.",
        f"- Divergence registry rows: {len(divergence_rows)}; awaiting implementation/ADR approval: {sum(row['approval_status'] != 'APPROVED' for row in divergence_rows)}.",
        "",
        "| Status | Count |",
        "|---|---:|",
        f"| ROOT_SIGNATURE_MATCHES_TYPE_CLOSURE_UNVERIFIED | {exact} |",
        f"| MISSING_ROOT_NAME | {missing} |",
        f"| ROOT_SIGNATURE_DIFFERS | {diff} |",
        "",
        "| Domain | Phase | Roots | Exact root signatures | Missing roots | Signature differences |",
        "|---|---|---:|---:|---:|---:|",
    ]
    for category in sorted(matrix_by_category):
        exact_in_category = category_status[(category, "ROOT_SIGNATURE_MATCHES_TYPE_CLOSURE_UNVERIFIED")]
        missing_in_category = category_status[(category, "MISSING_ROOT_NAME")]
        diff_in_category = category_status[(category, "ROOT_SIGNATURE_DIFFERS")]
        report.append(
            f"| {category} | {matrix_by_category[category]['phase']} | {category_roots[category]} | "
            f"{exact_in_category} | {missing_in_category} | {diff_in_category} |"
        )
    report.extend([
        "",
        "Owner, phase, policy and acceptance fields in the CSV are inherited from the reviewed domain matrix.",
        f"Full per-root report: {args.report.name} (upload as workflow artifact).",
    ])
    output = "\n".join(report) + "\n"
    print(output)
    if args.summary:
        args.summary.parent.mkdir(parents=True, exist_ok=True)
        with args.summary.open("a", encoding="utf-8") as handle:
            handle.write(output)
    if args.require_exact_root_signatures and (exact != measured_total or missing or diff):
        fail("exact-root-signature diagnostic requires all 458 root names/signatures to match literally; this does not measure semantic or end-to-end coverage")
    if args.require_approved_divergences:
        unresolved = [row["saleor_surface"] for row in divergence_rows if row["approval_status"] != "APPROVED"]
        if unresolved:
            fail("divergences awaiting approval/ADR: " + ", ".join(unresolved))


if __name__ == "__main__":
    main()
