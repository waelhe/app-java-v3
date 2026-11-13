import importlib.util
from pathlib import Path
import unittest
import unittest.mock

SCRIPT = Path(__file__).resolve().parents[1] / "check_defect_knowledge.py"
SPEC = importlib.util.spec_from_file_location("check_defect_knowledge", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)


class DefectKnowledgeReceiptTests(unittest.TestCase):
    def test_fingerprint_is_stable_under_unicode_compatibility_and_whitespace(self):
        first = MODULE.make_fingerprint(
            "money.delivery",
            "one committed refund delta",
            "two workers read the same cumulative amount",
        )
        second = MODULE.make_fingerprint(
            " MONEY.DELIVERY ",
            "ONE COMMITTED REFUND DELTA",
            "two   workers\nread the same cumulative amount",
        )
        self.assertEqual(first, second)
        self.assertRegex(first, r"^sha256:[0-9a-f]{64}$")

    def test_code_and_runtime_paths_are_mandatory_but_docs_are_not(self):
        self.assertTrue(MODULE.is_code_or_runtime_path(
            "marketplace-ledger/src/main/java/com/marketplace/ledger/LedgerService.java"
        ))
        self.assertTrue(MODULE.is_code_or_runtime_path(".github/workflows/ci.yml"))
        self.assertTrue(MODULE.is_code_or_runtime_path(".ci/check_defect_knowledge.py"))
        self.assertTrue(MODULE.is_code_or_runtime_path("mvnw"))
        self.assertTrue(MODULE.is_code_or_runtime_path("mvnw.cmd"))
        self.assertTrue(MODULE.is_code_or_runtime_path("pom.xml"))
        self.assertFalse(MODULE.is_code_or_runtime_path(
            "docs/governance/defect-knowledge/evidence-ledger.md"
        ))

    def test_git_diff_name_only_preserves_non_ascii_paths(self):
        from types import SimpleNamespace
        output = "marketplace-app/src/main/java/مثال.java\0mvnw\0".encode("utf-8")
        with unittest.mock.patch.object(
            MODULE.subprocess, "run",
            return_value=SimpleNamespace(returncode=0, stdout=output, stderr=b""),
        ) as run:
            changed = MODULE.git_diff_name_only("a" * 40, "b" * 40)
        self.assertEqual(changed, [
            "marketplace-app/src/main/java/مثال.java",
            "mvnw",
        ])
        self.assertIn("--name-only", run.call_args.args[0])
        self.assertIn("-z", run.call_args.args[0])

    def test_docs_only_receipt_mutation_is_still_validated(self):
        receipt_path = "docs/governance/defect-knowledge/change-records/PR-901.json"
        with (
            unittest.mock.patch.object(MODULE, "git_diff_name_only", return_value=[receipt_path]),
            unittest.mock.patch.object(MODULE, "is_code_or_runtime_path", return_value=False),
            unittest.mock.patch.object(MODULE, "load_json_at_head", return_value={}),
            unittest.mock.patch.object(MODULE, "git_show", return_value=""),
            unittest.mock.patch.object(MODULE, "validate_receipt", return_value=["invalid receipt"]),
            unittest.mock.patch.object(MODULE, "validate_global_fingerprints", return_value=[]),
        ):
            with self.assertRaisesRegex(MODULE.GateError, "invalid receipt"):
                MODULE.validate_pr(901, "2026-11-13T00:00:00Z", "a" * 40, "b" * 40)

    def test_rule_pattern_and_case_ids_are_derived_from_the_documents(self):
        rules = MODULE.extract_rule_ids(
            "### A1 — authority\n### B2 — retry\n- **D11** receipt\n"
        )
        patterns = MODULE.extract_pattern_ids("## P1 · first\n## P14 · last\n")
        cases = MODULE.extract_case_ids("## E-01 · first\n## E-30 · last\n")
        self.assertEqual(rules, {"A1", "B2", "D11"})
        self.assertEqual(patterns, {"P1", "P14"})
        self.assertEqual(cases, {"E-01", "E-30"})

    def test_receipt_rejects_unknown_rule_and_pattern_references(self):
        record = {
            "schemaVersion": 1,
            "pullRequest": 900,
            "governingFiles": ["docs/CODING_STANDARDS.md"],
            "rulesTriggered": ["A1", "B999"],
            "patternsReviewed": ["P14", "P99"],
            "historicalCasesReviewed": [],
            "noApplicablePatternRationale": "",
            "invariants": ["a stable invariant"],
            "doors": ["REST"],
            "siblingSearch": [{"family": "same mechanism", "pathsSearched": ["module/src"], "result": "none"}],
            "tests": [{"name": "unit", "command": "./mvnw test", "verification": "CI run"}],
            "findings": [],
            "noFindingsRationale": "No new defect was found.",
        }
        errors = MODULE.validate_receipt(
            record, 900, "a" * 40, "waelhe/app-java-v3", "test-token",
            {"A1", "B2", "D11"}, {"P1", "P14"}, {"E-01"}
        )
        joined = "\n".join(errors)
        self.assertIn("B999", joined)
        self.assertIn("P99", joined)


    def test_workflow_run_url_must_be_canonical_and_in_this_repository(self):
        self.assertEqual(
            MODULE.workflow_run_id_from_url(
                "https://github.com/waelhe/app-java-v3/actions/runs/12345",
                "waelhe/app-java-v3",
            ),
            12345,
        )
        with self.assertRaises(MODULE.GateError):
            MODULE.workflow_run_id_from_url(
                "https://github.com/waelhe/app-java-v3/actions/runs/12345/attempts/2",
                "waelhe/app-java-v3",
            )
        with self.assertRaises(MODULE.GateError):
            MODULE.workflow_run_id_from_url(
                "https://github.com/attacker/other/actions/runs/12345",
                "waelhe/app-java-v3",
            )
        with self.assertRaises(MODULE.GateError):
            MODULE.workflow_run_id_from_url(
                "https://github.com/waelhe/app-java-v3/actions/runs/12345?status=success",
                "waelhe/app-java-v3",
            )

    def test_fixed_claim_requires_completed_successful_run_on_exact_head(self):
        run = {
            "id": 12345,
            "status": "completed",
            "conclusion": "success",
            "head_sha": "a" * 40,
            "event": "pull_request",
            "name": "CI",
            "path": ".github/workflows/ci.yml",
            "repository": {"full_name": "waelhe/app-java-v3"},
            "pull_requests": [{"number": 901, "head": {"sha": "a" * 40}}],
        }
        self.assertEqual(
            MODULE.validate_workflow_run_payload(run, "waelhe/app-java-v3", "a" * 40, 901),
            [],
        )
        self.assertIn(
            "workflow run must be completed",
            MODULE.validate_workflow_run_payload(
                {**run, "status": "in_progress"},
                "waelhe/app-java-v3",
                "a" * 40,
                901,
            ),
        )
        self.assertIn(
            "workflow run conclusion must be success",
            MODULE.validate_workflow_run_payload(
                {**run, "conclusion": "failure"},
                "waelhe/app-java-v3",
                "a" * 40,
                901,
            ),
        )
        self.assertIn(
            "workflow run must target the exact tested code head SHA",
            MODULE.validate_workflow_run_payload(
                {**run, "head_sha": "b" * 40, "pull_requests": [{"number": 901, "head": {"sha": "b" * 40}}]},
                "waelhe/app-java-v3",
                "a" * 40,
                901,
            ),
        )
        self.assertIn(
            "workflow run repository does not match the target repository",
            MODULE.validate_workflow_run_payload(
                {**run, "repository": {"full_name": "attacker/other"}},
                "waelhe/app-java-v3",
                "a" * 40,
                901,
            ),
        )
        self.assertIn(
            "workflow run must be triggered by pull_request",
            MODULE.validate_workflow_run_payload(
                {**run, "event": "push"},
                "waelhe/app-java-v3",
                "a" * 40,
                901,
            ),
        )
        self.assertIn(
            "workflow run must be the repository CI or Integration Test workflow",
            MODULE.validate_workflow_run_payload(
                {**run, "name": "Request Copilot Code Review", "path": ".github/workflows/copilot.yml"},
                "waelhe/app-java-v3",
                "a" * 40,
                901,
            ),
        )

        self.assertIn(
            "workflow run must be the repository CI or Integration Test workflow",
            MODULE.validate_workflow_run_payload(
                {**run, "path": ".github/workflows/unrelated.yml"},
                "waelhe/app-java-v3",
                "a" * 40,
                901,
            ),
        )
        self.assertIn(
            "workflow run is not associated with this PR number and exact tested code head SHA",
            MODULE.validate_workflow_run_payload(
                {**run, "pull_requests": [{"number": 902, "head": {"sha": "a" * 40}}]},
                "waelhe/app-java-v3",
                "a" * 40,
                901,
            ),
        )

    def test_fixed_finding_requires_commit_and_passed_verification(self):
        scenario = "two concurrent refund workers read the same previous amount"
        invariant = "one cumulative refund is booked at most once"
        domain = "ledger.delivery-refund"
        fingerprint = MODULE.make_fingerprint(domain, invariant, scenario)
        record = {
            "schemaVersion": 1,
            "pullRequest": 901,
            "governingFiles": ["docs/governance/defect-knowledge/evidence-ledger.md"],
            "rulesTriggered": ["A1"],
            "patternsReviewed": ["P11"],
            "historicalCasesReviewed": [],
            "invariants": [invariant],
            "doors": ["delivery refund listener"],
            "siblingSearch": [{"family": "refund delta", "pathsSearched": ["ledger/src"], "result": "same family checked"}],
            "tests": [{"name": "two-thread race", "command": "./mvnw verify", "verification": "CI"}],
            "findings": [{
                "key": "A3",
                "domain": domain,
                "scenario": scenario,
                "invariant": invariant,
                "fingerprint": fingerprint,
                "sourceUrls": ["https://github.com/waelhe/app-java-v3/pull/816"],
                "classification": "confirmed_defect",
                "severity": "high",
                "status": "fixed",
                "fix": {"commitSha": "b" * 40, "regressionTest": "two-thread race", "verification": {"status": "pending", "testedHeadSha": "a" * 40, "workflowRunUrl": ""}},
            }],
        }
        errors = MODULE.validate_receipt(
            record, 901, "a" * 40, "waelhe/app-java-v3", "test-token",
            {"A1"}, {"P11"}, set()
        )
        joined = "\n".join(errors)
        self.assertIn("commitSha must be an ancestor of testedHeadSha", joined)
        self.assertIn("status must be passed", joined)
        self.assertIn("workflowRunUrl", joined)

    def test_fixed_claim_accepts_tested_code_head_when_only_receipt_changes_after_it(self):
        from unittest.mock import patch

        pr_number = 901
        tested_head = "a" * 40
        final_head = "c" * 40
        receipt_path = f"{MODULE.RECEIPT_DIR}/PR-{pr_number}.json"
        scenario = "one review finding is fixed before receipt evidence is finalized"
        invariant = "fixed requires a reproducible regression test on code-bearing commit"
        domain = "governance.defect-knowledge"
        run = {
            "id": 12345, "status": "completed", "conclusion": "success",
            "head_sha": tested_head, "event": "pull_request", "name": "CI",
            "path": ".github/workflows/ci.yml",
            "repository": {"full_name": "waelhe/app-java-v3"},
            "pull_requests": [{"number": pr_number, "head": {"sha": tested_head}}],
        }
        record = {
            "schemaVersion": 1, "pullRequest": pr_number,
            "governingFiles": ["docs/governance/defect-knowledge/change-protocol.md"],
            "rulesTriggered": ["A1"], "patternsReviewed": ["P1"],
            "historicalCasesReviewed": ["E-01"], "invariants": [invariant],
            "doors": ["trusted-base receipt validation"],
            "siblingSearch": [{"family": "receipt verification", "pathsSearched": [".ci/"], "result": "validator family checked"}],
            "tests": [{"name": "D11 regression", "command": "python3 -m unittest", "verification": "CI"}],
            "findings": [{
                "key": "D11-TEST", "domain": domain, "scenario": scenario,
                "invariant": invariant,
                "fingerprint": MODULE.make_fingerprint(domain, invariant, scenario),
                "sourceUrls": ["https://github.com/waelhe/app-java-v3/pull/834"],
                "classification": "test_gap", "severity": "high", "status": "fixed",
                "fix": {
                    "commitSha": tested_head, "regressionTest": "D11 regression",
                    "verification": {
                        "status": "passed", "testedHeadSha": tested_head,
                        "workflowRunUrl": "https://github.com/waelhe/app-java-v3/actions/runs/12345",
                    },
                },
            }],
        }
        with (
            patch.object(MODULE, "is_ancestor", return_value=True),
            patch.object(MODULE, "git_diff_name_only", return_value=[receipt_path]),
            patch.object(MODULE, "fetch_workflow_run", return_value=run),
        ):
            errors = MODULE.validate_receipt(
                record, pr_number, final_head, "waelhe/app-java-v3", "token",
                {"A1"}, {"P1"}, {"E-01"},
            )
        self.assertEqual(errors, [])

        with (
            patch.object(MODULE, "is_ancestor", return_value=True),
            patch.object(MODULE, "git_diff_name_only", return_value=["marketplace-app/src/main/java/changed.java"]),
            patch.object(MODULE, "fetch_workflow_run", return_value=run),
        ):
            errors = MODULE.validate_receipt(
                record, pr_number, final_head, "waelhe/app-java-v3", "token",
                {"A1"}, {"P1"}, {"E-01"},
            )
        self.assertTrue(any("only the same PR receipt may change" in item for item in errors))

    def test_superseded_finding_requires_a_resolvable_successor(self):
        scenario = "a recorded cause has been replaced by a successor"
        invariant = "superseded findings retain a traceable successor"
        domain = "governance.defect-knowledge"
        finding = {
            "key": "OLD", "domain": domain, "scenario": scenario, "invariant": invariant,
            "fingerprint": MODULE.make_fingerprint(domain, invariant, scenario),
            "sourceUrls": ["https://github.com/waelhe/app-java-v3/pull/834"],
            "classification": "decision_gap", "severity": "medium", "status": "superseded",
        }
        record = {
            "schemaVersion": 1, "pullRequest": 902,
            "governingFiles": ["docs/governance/defect-knowledge/change-records/README.md"],
            "rulesTriggered": ["A1"], "patternsReviewed": ["P1"],
            "historicalCasesReviewed": [], "noApplicablePatternRationale": "",
            "invariants": ["successor reference must resolve"], "doors": ["receipt"],
            "siblingSearch": [{"family": "receipt status", "pathsSearched": [".ci/"], "result": "checked"}],
            "tests": [{"name": "status test", "command": "python3 -m unittest", "verification": "CI"}],
            "findings": [finding],
        }
        errors = MODULE.validate_receipt(
            record, 902, "a" * 40, "waelhe/app-java-v3", "token",
            {"A1"}, {"P1"}, set(),
        )
        self.assertTrue(any("supersededBy" in item for item in errors))
        finding["supersededBy"] = "NEXT"
        record["findings"].append({
            "key": "NEXT", "domain": domain, "scenario": "successor",
            "invariant": "different causal scope",
            "fingerprint": MODULE.make_fingerprint(domain, "different causal scope", "successor"),
            "sourceUrls": ["https://github.com/waelhe/app-java-v3/pull/834"],
            "classification": "decision_gap", "severity": "medium", "status": "open",
        })
        errors = MODULE.validate_receipt(
            record, 902, "a" * 40, "waelhe/app-java-v3", "token",
            {"A1"}, {"P1"}, set(),
        )
        self.assertFalse(any("supersededBy" in item for item in errors))


if __name__ == "__main__":
    unittest.main()
