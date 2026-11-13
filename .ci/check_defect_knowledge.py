#!/usr/bin/env python3
"""Fail-closed validator for per-PR defect-knowledge receipts.

The checker is run from the trusted base revision by CI and reads candidate
files with git-show. It never imports or executes code from the PR head.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request
import subprocess
import sys
import unicodedata
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROLLOUT_AT = "2026-11-13T00:00:00Z"
RECEIPT_DIR = "docs/governance/defect-knowledge/change-records"
RULEBOOK = "docs/governance/defect-knowledge/rulebook.md"
PATTERN_CATALOG = "docs/governance/defect-knowledge/pattern-catalog.md"
EVIDENCE_LEDGER = "docs/governance/defect-knowledge/evidence-ledger.md"
CODE_PREFIXES = (".ci/", ".github/workflows/", ".railway/")
ROOT_POM_NAMES = {"pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"}


class GateError(Exception):
    """A precise defect-knowledge gate failure."""


def git(*args: str, check: bool = True) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=Path.cwd(),
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if check and result.returncode != 0:
        raise GateError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def git_show(revision: str, path: str) -> str:
    return git("show", f"{revision}:{path}")


def git_diff_name_only(base_sha: str, head_sha: str) -> list[str]:
    """Return Git paths without line parsing or lossy text decoding."""
    result = subprocess.run(
        ["git", "diff", "--name-only", "-z", f"{base_sha}...{head_sha}"],
        cwd=Path.cwd(),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        raise GateError(f"git diff --name-only failed: {os.fsdecode(result.stderr).strip()}")
    return [os.fsdecode(path) for path in result.stdout.split(b"\\0") if path]


def is_ancestor(ancestor_sha: str, descendant_sha: str) -> bool:
    return subprocess.run(
        ["git", "merge-base", "--is-ancestor", ancestor_sha, descendant_sha],
        cwd=Path.cwd(),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    ).returncode == 0


def normalize_fingerprint_part(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value).casefold()
    return " ".join(normalized.split())


def make_fingerprint(domain: str, invariant: str, scenario: str) -> str:
    """Stable identity: same domain + violated invariant + triggering scenario."""
    parts = [
        normalize_fingerprint_part(domain),
        normalize_fingerprint_part(invariant),
        normalize_fingerprint_part(scenario),
    ]
    canonical = "\n".join(parts).encode("utf-8")
    return "sha256:" + hashlib.sha256(canonical).hexdigest()


def is_code_or_runtime_path(path: str) -> bool:
    if path in {"mvnw", "mvnw.cmd", "gradlew", "gradlew.bat"}:
        return True
    if path.startswith(CODE_PREFIXES):
        return True
    if path in ROOT_POM_NAMES or path.endswith("/pom.xml"):
        return True
    if path.startswith("marketplace-") and "/src/" in path:
        return True
    if "/db/migration/" in path or path.startswith("db/migration/"):
        return True
    if path.endswith((".yml", ".yaml", ".properties", ".toml")) and (
        path.startswith("marketplace-") or path.startswith("config/")
    ):
        return True
    return False


def extract_rule_ids(rulebook: str) -> set[str]:
    ids = set(re.findall(r"^### ([ABCD]\d+) — ", rulebook, re.MULTILINE))
    ids.update(re.findall(r"^- \*\*([ABCD]\d+)\*\* ", rulebook, re.MULTILINE))
    return ids


def extract_pattern_ids(catalog: str) -> set[str]:
    return {"P" + number for number in re.findall(r"^## P(\d+) ·", catalog, re.MULTILINE)}


def extract_case_ids(ledger: str) -> set[str]:
    return {"E-" + number for number in re.findall(r"^## E-(\d+) ·", ledger, re.MULTILINE)}


def load_json_at_head(head_sha: str, path: str) -> dict[str, Any]:
    try:
        parsed = json.loads(git_show(head_sha, path))
    except (json.JSONDecodeError, GateError) as exc:
        raise GateError(f"Cannot read valid JSON from {path} at candidate head {head_sha}: {exc}") from exc
    if not isinstance(parsed, dict):
        raise GateError(f"{path} must contain a JSON object")
    return parsed

def workflow_run_id_from_url(url: str, repository: str) -> int:
    """Accept only this repository's canonical GitHub Actions run URL."""
    parsed = urllib.parse.urlparse(url)
    expected_prefix = f"/{repository}/actions/runs/"
    if parsed.scheme != "https" or parsed.netloc != "github.com" or not parsed.path.startswith(expected_prefix):
        raise GateError("fixed finding workflowRunUrl must be this repository's canonical Actions run URL")
    if parsed.query or parsed.fragment:
        raise GateError("workflowRunUrl must not contain a query string or fragment")
    suffix = parsed.path[len(expected_prefix):].strip("/")
    match = re.fullmatch(r"([0-9]+)", suffix)
    if not match:
        raise GateError("workflowRunUrl must identify a GitHub Actions workflow run")
    return int(match.group(1))


def fetch_workflow_run(run_id: int, repository: str, token: str) -> dict[str, Any]:
    """Read the run from GitHub; never trust a URL/status copied into the receipt."""
    if not token:
        raise GateError("GITHUB_TOKEN is required to verify fixed-finding workflow evidence")
    url = f"https://api.github.com/repos/{repository}/actions/runs/{run_id}"
    request = urllib.request.Request(
        url,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "app-java-v3-defect-knowledge-gate",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (urllib.error.HTTPError, urllib.error.URLError, json.JSONDecodeError) as exc:
        raise GateError(f"Cannot verify GitHub Actions run {run_id}: {exc}") from exc
    if not isinstance(payload, dict):
        raise GateError(f"GitHub Actions run {run_id} returned an invalid response")
    return payload


def validate_workflow_run_payload(
    payload: dict[str, Any], repository: str, expected_head_sha: str, pr_number: int
) -> list[str]:
    errors: list[str] = []
    if payload.get("status") != "completed":
        errors.append("workflow run must be completed")
    if payload.get("conclusion") != "success":
        errors.append("workflow run conclusion must be success")
    if payload.get("head_sha") != expected_head_sha:
        errors.append("workflow run must target the exact tested code head SHA")
    if payload.get("event") != "pull_request":
        errors.append("workflow run must be triggered by pull_request")
    workflow_paths = {
        "CI": ".github/workflows/ci.yml",
        "Integration Test": ".github/workflows/integration-test.yml",
    }
    expected_path = workflow_paths.get(str(payload.get("name", "")))
    actual_path = str(payload.get("path", "")).split("@", 1)[0]
    if expected_path is None or actual_path != expected_path:
        errors.append("workflow run must be the repository CI or Integration Test workflow")
    run_repository = (payload.get("repository") or {}).get("full_name")
    if run_repository != repository:
        errors.append("workflow run repository does not match the target repository")
    pull_requests = payload.get("pull_requests")
    matching_pr = any(
        item.get("number") == pr_number
        and ((item.get("head") or {}).get("sha") == expected_head_sha)
        for item in pull_requests if isinstance(item, dict)
    ) if isinstance(pull_requests, list) else False
    if not matching_pr:
        errors.append("workflow run is not associated with this PR number and exact tested code head SHA")
    if payload.get("id") is None:
        errors.append("workflow run response has no id")
    return errors



def validate_receipt(record: dict[str, Any], pr_number: int, head_sha: str,
                     repository: str, github_token: str,
                     valid_rules: set[str], valid_patterns: set[str],
                     valid_cases: set[str]) -> list[str]:
    errors: list[str] = []

    def require(condition: bool, message: str) -> None:
        if not condition:
            errors.append(message)

    require(record.get("schemaVersion") == 1, "schemaVersion must be 1")
    require(record.get("pullRequest") == pr_number, f"pullRequest must equal #{pr_number}")

    for field in ("governingFiles", "rulesTriggered", "patternsReviewed",
                  "historicalCasesReviewed", "invariants", "doors", "siblingSearch", "tests", "findings"):
        require(isinstance(record.get(field), list), f"{field} must be an array")

    governing = record.get("governingFiles", [])
    require(bool(governing) and all(isinstance(x, str) and x.strip() for x in governing),
            "governingFiles must name at least one non-empty authority path")

    rules = record.get("rulesTriggered", [])
    require(bool(rules), "rulesTriggered must identify the universal/conditional rules used")
    unknown_rules = sorted(set(rules) - valid_rules) if isinstance(rules, list) else []
    require(not unknown_rules, f"rulesTriggered contains unknown IDs: {unknown_rules}")

    patterns = record.get("patternsReviewed", [])
    unknown_patterns = sorted(set(patterns) - valid_patterns) if isinstance(patterns, list) else []
    require(not unknown_patterns, f"patternsReviewed contains unknown IDs: {unknown_patterns}")
    if isinstance(patterns, list) and not patterns:
        require(bool(str(record.get("noApplicablePatternRationale", "")).strip()),
                "empty patternsReviewed requires noApplicablePatternRationale")

    cases = record.get("historicalCasesReviewed", [])
    unknown_cases = sorted(set(cases) - valid_cases) if isinstance(cases, list) else []
    require(not unknown_cases, f"historicalCasesReviewed contains unknown IDs: {unknown_cases}")

    invariants = record.get("invariants", [])
    require(bool(invariants) and all(isinstance(x, str) and x.strip() for x in invariants),
            "invariants must list the behavioral laws this change preserves")
    doors = record.get("doors", [])
    no_more_doors = str(record.get("noAdditionalDoorsRationale", "")).strip()
    require(bool(doors) or bool(no_more_doors),
            "doors must enumerate affected entry points, or explain why no additional doors exist")

    sibling = record.get("siblingSearch", [])
    require(bool(sibling), "siblingSearch must record a search for the same mechanism in sibling paths")
    for i, item in enumerate(sibling if isinstance(sibling, list) else []):
        require(isinstance(item, dict), f"siblingSearch[{i}] must be an object")
        if isinstance(item, dict):
            require(bool(str(item.get("family", "")).strip()), f"siblingSearch[{i}].family is required")
            require(bool(item.get("pathsSearched")) or bool(str(item.get("rationale", "")).strip()),
                    f"siblingSearch[{i}] must list pathsSearched or a rationale")
            require(bool(str(item.get("result", "")).strip()), f"siblingSearch[{i}].result is required")

    tests = record.get("tests", [])
    require(bool(tests), "tests must name executable acceptance evidence")
    for i, test in enumerate(tests if isinstance(tests, list) else []):
        require(isinstance(test, dict), f"tests[{i}] must be an object")
        if isinstance(test, dict):
            require(bool(str(test.get("name", "")).strip()), f"tests[{i}].name is required")
            require(bool(str(test.get("command", "")).strip()), f"tests[{i}].command is required")
            require(bool(str(test.get("verification", "")).strip()),
                    f"tests[{i}].verification must identify the CI/local evidence source")

    findings = record.get("findings", [])
    if isinstance(findings, list) and not findings:
        require(bool(str(record.get("noFindingsRationale", "")).strip()),
                "empty findings requires noFindingsRationale")

    allowed_classes = {
        "confirmed_defect", "test_gap", "capability_gap", "decision_gap",
        "environmental", "false_positive", "accepted_risk"
    }
    allowed_statuses = {"fixed", "open", "registered", "not_reproducible", "superseded"}
    allowed_severities = {"critical", "high", "medium", "low", "informational", "unclassified"}
    seen_fingerprints: set[str] = set()

    for i, finding in enumerate(findings if isinstance(findings, list) else []):
        prefix = f"findings[{i}]"
        if not isinstance(finding, dict):
            errors.append(f"{prefix} must be an object")
            continue
        for field in ("key", "domain", "classification", "severity", "status", "scenario",
                      "invariant", "sourceUrls", "fingerprint"):
            require(bool(str(finding.get(field, "")).strip()), f"{prefix}.{field} is required")

        require(finding.get("classification") in allowed_classes,
                f"{prefix}.classification must be one of {sorted(allowed_classes)}")
        require(finding.get("status") in allowed_statuses,
                f"{prefix}.status must be one of {sorted(allowed_statuses)}")
        require(finding.get("severity") in allowed_severities,
                f"{prefix}.severity must be one of {sorted(allowed_severities)}")

        if finding.get("status") == "superseded":
            successor = finding.get("supersededBy")
            require(isinstance(successor, str) and bool(successor.strip()),
                    f"{prefix}.supersededBy must point to a successor finding or decision")
            if isinstance(successor, str) and successor.strip():
                receipt_keys = {
                    str(item.get("key", "")).strip()
                    for item in findings if isinstance(item, dict) and item is not finding
                } if isinstance(findings, list) else set()
                key_reference = successor in receipt_keys
                url_reference = bool(re.fullmatch(
                    r"https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/(?:issues|pull)/[0-9]+(?:#[A-Za-z0-9_.-]+)?",
                    successor,
                ))
                require(key_reference or url_reference,
                        f"{prefix}.supersededBy must resolve to another receipt finding key or a GitHub issue/PR URL")
                require(successor != str(finding.get("key", "")),
                        f"{prefix}.supersededBy must not point to the finding itself")

        urls = finding.get("sourceUrls", [])
        require(isinstance(urls, list) and bool(urls) and all(
            isinstance(url, str) and url.startswith("https://github.com/") for url in urls
        ), f"{prefix}.sourceUrls must contain GitHub source links")

        case_id = finding.get("caseId")
        if case_id:
            require(case_id in valid_cases, f"{prefix}.caseId {case_id!r} is not in evidence-ledger.md")

        expected_fp = make_fingerprint(
            str(finding.get("domain", "")),
            str(finding.get("invariant", "")),
            str(finding.get("scenario", "")),
        )
        require(finding.get("fingerprint") == expected_fp,
                f"{prefix}.fingerprint does not match the canonical SHA-256 identity")
        fp = str(finding.get("fingerprint", ""))
        require(fp not in seen_fingerprints, f"{prefix} duplicates a fingerprint in this receipt")
        seen_fingerprints.add(fp)

        if finding.get("status") == "fixed":
            fix = finding.get("fix", {})
            require(isinstance(fix, dict), f"{prefix}.fix must be an object when status=fixed")
            if isinstance(fix, dict):
                commit_sha = str(fix.get("commitSha", ""))
                require(bool(re.fullmatch(r"[0-9a-f]{40}", commit_sha)),
                        f"{prefix}.fix.commitSha must be a full commit SHA")
                regression_test = str(fix.get("regressionTest", "")).strip()
                require(bool(regression_test), f"{prefix}.fix.regressionTest must name the test that proves this finding is fixed")
                test_names = {
                    str(test.get("name", "")).strip()
                    for test in tests if isinstance(test, dict)
                } if isinstance(tests, list) else set()
                require(regression_test in test_names,
                        f"{prefix}.fix.regressionTest must match a named test in the receipt tests array")
                verification = fix.get("verification", {})
                require(isinstance(verification, dict), f"{prefix}.fix.verification must be an object")
                if isinstance(verification, dict):
                    passed = verification.get("status") == "passed"
                    require(passed,
                            f"{prefix}.fix.verification.status must be passed before marking fixed")
                    tested_head_sha = str(verification.get("testedHeadSha", ""))
                    tested_sha_valid = bool(re.fullmatch(r"[0-9a-f]{40}", tested_head_sha))
                    require(tested_sha_valid,
                            f"{prefix}.fix.verification.testedHeadSha must be a full code-bearing commit SHA")
                    if re.fullmatch(r"[0-9a-f]{40}", commit_sha) and tested_sha_valid:
                        require(is_ancestor(commit_sha, tested_head_sha),
                                f"{prefix}.fix.commitSha must be an ancestor of testedHeadSha")
                        tested_is_ancestor = is_ancestor(tested_head_sha, head_sha)
                        require(tested_is_ancestor,
                                f"{prefix}.fix.verification.testedHeadSha must be an ancestor of the final PR head")
                        if tested_is_ancestor and tested_head_sha != head_sha:
                            later_paths = git_diff_name_only(tested_head_sha, head_sha)
                            receipt_path = f"{RECEIPT_DIR}/PR-{pr_number}.json"
                            require(set(later_paths) == {receipt_path},
                                    f"{prefix}.fix.verification: only the same PR receipt may change after testedHeadSha; found {later_paths}")
                    workflow_url = str(verification.get("workflowRunUrl", ""))
                    require(bool(workflow_url.strip()),
                            f"{prefix}.fix.verification.workflowRunUrl is required")
                    if passed and tested_sha_valid:
                        try:
                            run_id = workflow_run_id_from_url(workflow_url, repository)
                            payload = fetch_workflow_run(run_id, repository, github_token)
                            for run_error in validate_workflow_run_payload(
                                payload, repository, tested_head_sha, pr_number
                            ):
                                errors.append(f"{prefix}.fix.verification: {run_error}")
                        except GateError as exc:
                            errors.append(f"{prefix}.fix.verification: {exc}")

        if finding.get("status") == "registered":
            decision = finding.get("decision", {})
            require(isinstance(decision, dict), f"{prefix}.decision must be an object")
            if isinstance(decision, dict):
                require(bool(str(decision.get("owner", "")).strip()),
                        f"{prefix}.decision.owner is required for registered/accepted risk")
                require(str(decision.get("url", "")).startswith("https://github.com/"),
                        f"{prefix}.decision.url must link to an approved ADR/issue/record")

        if finding.get("status") == "not_reproducible":
            require(bool(str(finding.get("reproductionEvidence", "")).strip()),
                    f"{prefix}.reproductionEvidence is required for non-reproducible findings")

    return errors


def validate_global_fingerprints(head_sha: str) -> list[str]:
    errors: list[str] = []
    listing = git("ls-tree", "-r", "--name-only", head_sha, RECEIPT_DIR)
    previous: dict[str, tuple[str | None, str]] = {}
    for path in [line.strip() for line in listing.splitlines() if line.strip().endswith(".json")]:
        try:
            data = json.loads(git_show(head_sha, path))
        except (json.JSONDecodeError, GateError):
            errors.append(f"{path} is not valid JSON")
            continue
        for item in data.get("findings", []) if isinstance(data, dict) else []:
            if not isinstance(item, dict):
                continue
            fp = item.get("fingerprint")
            if not fp:
                continue
            case_id = item.get("caseId")
            if fp in previous and previous[fp][0] != case_id:
                errors.append(
                    f"duplicate fingerprint {fp} maps to different case IDs: "
                    f"{previous[fp][0]!r} in {previous[fp][1]} vs {case_id!r} in {path}"
                )
            else:
                previous[fp] = (case_id, path)
    return errors


def validate_pr(pr_number: int, created_at: str, base_sha: str, head_sha: str) -> int:
    try:
        created = datetime.fromisoformat(created_at.replace("Z", "+00:00"))
        cutoff = datetime.fromisoformat(ROLLOUT_AT.replace("Z", "+00:00"))
    except ValueError as exc:
        raise GateError(f"Invalid PR creation timestamp: {created_at!r}") from exc

    if created < cutoff:
        print(f"DEFECT-KNOWLEDGE: PR #{pr_number} predates rollout ({ROLLOUT_AT}); grandfathered.")
        return 0

    changed = git_diff_name_only(base_sha, head_sha)
    relevant = [path for path in changed if is_code_or_runtime_path(path)]
    receipt_path = f"{RECEIPT_DIR}/PR-{pr_number}.json"
    receipt_changed = receipt_path in changed

    if not relevant and not receipt_changed:
        print(f"DEFECT-KNOWLEDGE: PR #{pr_number} has no code/runtime or receipt changes; receipt not required.")
        return 0

    if relevant and not receipt_changed:
        raise GateError(
            f"PR #{pr_number} changes code/runtime paths but does not add/update {receipt_path}. "
            "Follow change-protocol.md and add the structured defect-knowledge receipt."
        )
    # Docs-only changes to an existing receipt still pass through full validation.

    record = load_json_at_head(head_sha, receipt_path)
    rulebook = git_show(head_sha, RULEBOOK)
    catalog = git_show(head_sha, PATTERN_CATALOG)
    ledger = git_show(head_sha, EVIDENCE_LEDGER)

    errors = validate_receipt(
        record, pr_number, head_sha,
        os.environ.get("GITHUB_REPOSITORY", ""),
        os.environ.get("GITHUB_TOKEN", ""),
        extract_rule_ids(rulebook),
        extract_pattern_ids(catalog),
        extract_case_ids(ledger),
    )
    errors.extend(validate_global_fingerprints(head_sha))
    if errors:
        raise GateError("Defect-knowledge receipt rejected:\n- " + "\n- ".join(errors))

    print(f"DEFECT-KNOWLEDGE: PR #{pr_number} receipt valid for {len(relevant)} code/runtime paths.")
    print(f"DEFECT-KNOWLEDGE: {len(record.get('findings', []))} finding(s), "
          f"{len(record.get('patternsReviewed', []))} reviewed pattern(s), "
          f"{len(record.get('historicalCasesReviewed', []))} historical case(s).")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    fp_parser = subparsers.add_parser("fingerprint", help="print the canonical finding fingerprint")
    fp_parser.add_argument("--domain", required=True)
    fp_parser.add_argument("--invariant", required=True)
    fp_parser.add_argument("--scenario", required=True)

    validate_parser = subparsers.add_parser("validate", help="validate a PR receipt")
    validate_parser.add_argument("--pr-number", type=int, required=True)
    validate_parser.add_argument("--created-at", required=True)
    validate_parser.add_argument("--base-sha", required=True)
    validate_parser.add_argument("--head-sha", required=True)

    args = parser.parse_args()
    try:
        if args.command == "fingerprint":
            print(make_fingerprint(args.domain, args.invariant, args.scenario))
            return 0
        return validate_pr(args.pr_number, args.created_at, args.base_sha, args.head_sha)
    except GateError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
