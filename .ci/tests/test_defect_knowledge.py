import importlib.util
from pathlib import Path
import unittest

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
        self.assertTrue(MODULE.is_code_or_runtime_path("pom.xml"))
        self.assertFalse(MODULE.is_code_or_runtime_path(
            "docs/governance/defect-knowledge/evidence-ledger.md"
        ))

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
        self.assertEqual(
            MODULE.workflow_run_id_from_url(
                "https://github.com/waelhe/app-java-v3/actions/runs/12345/attempts/2",
                "waelhe/app-java-v3",
            ),
            12345,
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
            "repository": {"full_name": "waelhe/app-java-v3"},
        }
        self.assertEqual(
            MODULE.validate_workflow_run_payload(run, "waelhe/app-java-v3", "a" * 40),
            [],
        )
        self.assertIn(
            "workflow run must be completed",
            MODULE.validate_workflow_run_payload(
                {**run, "status": "in_progress"},
                "waelhe/app-java-v3",
                "a" * 40,
            ),
        )
        self.assertIn(
            "workflow run conclusion must be success",
            MODULE.validate_workflow_run_payload(
                {**run, "conclusion": "failure"},
                "waelhe/app-java-v3",
                "a" * 40,
            ),
        )
        self.assertIn(
            "workflow run must target the exact PR head SHA",
            MODULE.validate_workflow_run_payload(
                {**run, "head_sha": "b" * 40},
                "waelhe/app-java-v3",
                "a" * 40,
            ),
        )
        self.assertIn(
            "workflow run belongs to a different repository",
            MODULE.validate_workflow_run_payload(
                {**run, "repository": {"full_name": "attacker/other"}},
                "waelhe/app-java-v3",
                "a" * 40,
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
                "fix": {"commitSha": "b" * 40, "verification": {"status": "pending", "workflowRunUrl": "https://github.com/waelhe/app-java-v3/actions/runs/123"}},
            }],
        }
        errors = MODULE.validate_receipt(
            record, 901, "a" * 40, "waelhe/app-java-v3", "test-token",
            {"A1"}, {"P11"}, set()
        )
        joined = "\n".join(errors)
        self.assertIn("commitSha is not an ancestor", joined)
        self.assertIn("status must be passed", joined)
        self.assertIn("workflowRunUrl", joined)


if __name__ == "__main__":
    unittest.main()
