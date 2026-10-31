import importlib.util
import sys
import unittest
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1] / "check_coderabbit_review_coverage.py"
SPEC = importlib.util.spec_from_file_location("coverage_gate", MODULE)
gate = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = gate
SPEC.loader.exec_module(gate)
BT = chr(96)


class ParserTests(unittest.TestCase):
    def test_parses_explicit_processed_file_list(self):
        body = ("<details><summary><strong>Files selected for processing: 2</strong></summary>\n"
                "<dl><dd>\n* " + BT + "file-a.py" + BT + "\n* " + BT + "file-b.java" + BT +
                "\n</dd></dl></details>")
        self.assertEqual(gate.processed_files(body), ["file-a.py", "file-b.java"])

    def test_rejects_missing_processed_file_list(self):
        with self.assertRaisesRegex(gate.GateError, "omitted"):
            gate.processed_files("Review completed successfully")

    def test_rejects_mismatched_declared_count(self):
        body = "<summary>Files selected for processing: 2</summary>\n* " + BT + "a.java" + BT
        with self.assertRaisesRegex(gate.GateError, "declared 2 files but listed 1"):
            gate.processed_files(body)

    def test_rejects_duplicate_files(self):
        body = "<summary>Files selected for processing: 2</summary>\n* " + BT + "a.java" + BT
        body += "\n* " + BT + "a.java" + BT
        with self.assertRaisesRegex(gate.GateError, "duplicate paths"):
            gate.processed_files(body)


class ReviewTests(unittest.TestCase):
    def setUp(self):
        self.review = {"id": 9, "user": {"login": gate.BOT}, "commit_id": "head-A",
                       "state": "COMMENTED", "submitted_at": "2026-10-31T17:00:00Z"}

    def test_accepts_exact_head(self):
        self.assertEqual(gate.exact_review([self.review], 9, "head-A", gate.BOT, gate.GOOD_STATES)["id"], 9)

    def test_rejects_stale_head(self):
        with self.assertRaisesRegex(gate.GateError, "is stale"):
            gate.exact_review([self.review], 9, "head-B", gate.BOT, gate.GOOD_STATES)

    def test_rejects_wrong_author(self):
        with self.assertRaisesRegex(gate.GateError, "wrong author"):
            gate.exact_review([self.review], 9, "head-A", "reviewer", gate.GOOD_STATES)

    def test_rejects_changes_requested(self):
        review = dict(self.review, state="CHANGES_REQUESTED")
        with self.assertRaisesRegex(gate.GateError, "unacceptable state"):
            gate.exact_review([review], 9, "head-A", gate.BOT, gate.GOOD_STATES)

    def test_rejects_missing_review_id(self):
        with self.assertRaisesRegex(gate.GateError, "not found exactly once"):
            gate.exact_review([self.review], 10, "head-A", gate.BOT, gate.GOOD_STATES)


class ManifestTests(unittest.TestCase):
    def valid(self):
        return {"schemaVersion": 1, "maxFilesPerReview": 100,
                "reviewShards": [{"prNumber": 50, "reviewId": 9, "role": "candidate"}],
                "alternativeCoverage": []}

    def test_accepts_candidate_shard_manifest(self):
        gate.validate_manifest(self.valid())

    def test_rejects_limit_above_100(self):
        manifest = self.valid()
        manifest["maxFilesPerReview"] = 101
        with self.assertRaisesRegex(gate.GateError, "must be 100"):
            gate.validate_manifest(manifest)

    def test_rejects_missing_candidate_shard(self):
        manifest = self.valid()
        manifest["reviewShards"][0].pop("role")
        with self.assertRaisesRegex(gate.GateError, "exactly one shard"):
            gate.validate_manifest(manifest)

    def test_rejects_duplicate_shard_pr(self):
        manifest = self.valid()
        manifest["reviewShards"].append({"prNumber": 50, "reviewId": 10})
        with self.assertRaisesRegex(gate.GateError, "appears more than once"):
            gate.validate_manifest(manifest)

    def test_rejects_unsupported_evidence_kind(self):
        manifest = self.valid()
        manifest["alternativeCoverage"] = [{"paths": ["generated.graphqls"], "kind": "summary-only",
                                             "reason": "unsupported", "evidenceUrl": "https://example.invalid"}]
        with self.assertRaisesRegex(gate.GateError, "unsupported alternative"):
            gate.validate_manifest(manifest)

    def test_rejects_incomplete_alternative(self):
        manifest = self.valid()
        manifest["alternativeCoverage"] = [{"paths": ["generated.graphqls"], "kind": "check", "checkName": "Schema"}]
        with self.assertRaisesRegex(gate.GateError, "reason and evidenceUrl"):
            gate.validate_manifest(manifest)


class TreeTests(unittest.TestCase):
    def test_full_tree_diff_includes_add_modify_delete(self):
        before = {"same": ("a", "100644", "blob"), "change": ("b", "100644", "blob"),
                  "deleted": ("c", "100644", "blob")}
        after = {"same": ("a", "100644", "blob"), "change": ("d", "100644", "blob"),
                 "added": ("e", "100644", "blob")}
        self.assertEqual(set(gate.tree_changes(before, after)), {"change", "deleted", "added"})

    def test_detects_missing_changed_file(self):
        changed = {"a.java", "b.java"}
        covered = {"a.java"}
        self.assertEqual(changed - covered, {"b.java"})

    def test_detects_file_changed_after_shard_review(self):
        candidate = {"a.java": ("new", "100644", "blob")}
        reviewed = {"a.java": ("old", "100644", "blob")}
        self.assertNotEqual(candidate["a.java"], reviewed["a.java"])

    def test_rename_requires_both_path_names(self):
        _, paths, _ = gate.fileset([{"filename": "new.java", "previous_filename": "old.java"}])
        self.assertEqual(paths, {"new.java", "old.java"})

    def test_unlisted_unsupported_path_is_detected(self):
        changed = {"generated.graphqls", "Resolver.java"}
        covered = {"Resolver.java"}
        self.assertEqual(changed - covered, {"generated.graphqls"})


if __name__ == "__main__":
    unittest.main()
