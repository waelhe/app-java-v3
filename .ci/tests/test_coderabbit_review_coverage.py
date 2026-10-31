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


class FakeClient:
    def __init__(self, checks=None):
        self._checks = checks or []

    def checks(self, sha, source_repo=None):
        return self._checks


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

    def valid_alternative_config(self):
        return {"schemaVersion": 1, "checks": {
            "Dependency Review (PR dependency changes)": {
                "appSlugs": ["github-actions"],
                "pathPatterns": ["pom.xml", "**/pom.xml", "package-lock.json", "**/package-lock.json"]
            }
        }}

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

    def test_rejects_unsupported_review_shard_role(self):
        manifest = self.valid()
        manifest["reviewShards"].append({"prNumber": 51, "reviewId": 10, "role": "alternative"})
        with self.assertRaisesRegex(gate.GateError, "role must be candidate or review"):
            gate.validate_manifest(manifest)

    def test_rejects_duplicate_alternative_paths(self):
        manifest = self.valid()
        manifest["alternativeCoverage"] = [{
            "paths": ["pom.xml", "pom.xml"], "kind": "check",
            "reason": "dependency review", "evidenceUrl": "https://example.invalid",
            "checkName": "Dependency Review (PR dependency changes)"
        }]
        with self.assertRaisesRegex(gate.GateError, "duplicate paths"):
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

    def test_accepts_trusted_alternative_config(self):
        gate.validate_alternative_config(self.valid_alternative_config())

    def test_rejects_empty_trusted_path_allowlist(self):
        config = self.valid_alternative_config()
        config["checks"]["Dependency Review (PR dependency changes)"]["pathPatterns"] = []
        with self.assertRaisesRegex(gate.GateError, "requires pathPatterns"):
            gate.validate_alternative_config(config)


class AlternativeEvidenceTests(ManifestTests):
    def setUp(self):
        self.candidate = {"head": {"sha": "head-A",
                                   "repo": {"full_name": "example/repo"}},
                          "user": {"login": "pull-author"},
                          "html_url": "https://github.com/example/repo/pull/50"}
        self.review = {"id": 91, "user": {"login": "independent-reviewer"},
                       "commit_id": "head-A", "state": "APPROVED",
                       "submitted_at": "2026-10-31T17:00:00Z",
                       "html_url": "https://github.com/example/repo/pull/50#pullrequestreview-91"}
        self.config = self.valid_alternative_config()
        self.check = {"name": "Dependency Review (PR dependency changes)",
                      "head_sha": "head-A", "status": "completed", "conclusion": "success",
                      "completed_at": "2026-10-31T17:00:00Z",
                      "html_url": "https://github.com/example/repo/actions/runs/100",
                      "app": {"slug": "github-actions"}}
        self.client = FakeClient([self.check])

    def test_accepts_independent_latest_manual_review_url(self):
        item = {"kind": "manual-review", "paths": ["generated.graphqls"],
                "reason": "independent contract review", "evidenceUrl": self.review["html_url"],
                "reviewerLogin": "independent-reviewer", "reviewId": 91}
        self.assertEqual(gate.verify_alternative(item, self.candidate, [self.review],
                                                 self.client, self.config), {"generated.graphqls"})

    def test_rejects_pr_author_as_manual_reviewer(self):
        item = {"kind": "manual-review", "paths": ["generated.graphqls"],
                "reason": "self review", "evidenceUrl": self.review["html_url"],
                "reviewerLogin": "pull-author", "reviewId": 91}
        review = dict(self.review, user={"login": "pull-author"})
        with self.assertRaisesRegex(gate.GateError, "must be independent"):
            gate.verify_alternative(item, self.candidate, [review], self.client, self.config)

    def test_rejects_non_latest_manual_review(self):
        later = dict(self.review, id=92, submitted_at="2026-10-31T18:00:00Z")
        item = {"kind": "manual-review", "paths": ["generated.graphqls"],
                "reason": "old review", "evidenceUrl": self.review["html_url"],
                "reviewerLogin": "independent-reviewer", "reviewId": 91}
        with self.assertRaisesRegex(gate.GateError, "not latest"):
            gate.verify_alternative(item, self.candidate, [self.review, later], self.client, self.config)

    def test_rejects_candidate_url_as_manual_evidence(self):
        item = {"kind": "manual-review", "paths": ["generated.graphqls"],
                "reason": "candidate URL is not a review URL", "evidenceUrl": self.candidate["html_url"],
                "reviewerLogin": "independent-reviewer", "reviewId": 91}
        with self.assertRaisesRegex(gate.GateError, "does not identify the recorded review"):
            gate.verify_alternative(item, self.candidate, [self.review], self.client, self.config)

    def test_accepts_trusted_dependency_check_for_allowed_path(self):
        item = {"kind": "check", "paths": ["pom.xml"], "reason": "dependency manifest verification",
                "evidenceUrl": self.check["html_url"], "checkName": self.check["name"]}
        self.assertEqual(gate.verify_alternative(item, self.candidate, [], self.client, self.config), {"pom.xml"})

    def test_rejects_check_from_untrusted_github_app(self):
        item = {"kind": "check", "paths": ["pom.xml"], "reason": "dependency manifest verification",
                "evidenceUrl": self.check["html_url"], "checkName": self.check["name"]}
        untrusted = dict(self.check, app={"slug": "candidate-controlled-app"})
        with self.assertRaisesRegex(gate.GateError, "trusted GitHub App"):
            gate.verify_alternative(item, self.candidate, [], FakeClient([untrusted]), self.config)

    def test_rejects_check_for_path_outside_trusted_patterns(self):
        item = {"kind": "check", "paths": ["src/main/java/Resolver.java"],
                "reason": "not a dependency manifest", "evidenceUrl": self.check["html_url"],
                "checkName": self.check["name"]}
        with self.assertRaisesRegex(gate.GateError, "outside trusted path patterns"):
            gate.verify_alternative(item, self.candidate, [], self.client, self.config)

    def test_rejects_failed_check(self):
        item = {"kind": "check", "paths": ["pom.xml"], "reason": "dependency manifest verification",
                "evidenceUrl": self.check["html_url"], "checkName": self.check["name"]}
        failed = dict(self.check, conclusion="failure")
        with self.assertRaisesRegex(gate.GateError, "not successful"):
            gate.verify_alternative(item, self.candidate, [], FakeClient([failed]), self.config)

    def test_rejects_wrong_check_evidence_url(self):
        item = {"kind": "check", "paths": ["pom.xml"], "reason": "dependency manifest verification",
                "evidenceUrl": "https://github.com/example/repo/actions/runs/999",
                "checkName": self.check["name"]}
        with self.assertRaisesRegex(gate.GateError, "not the latest trusted run"):
            gate.verify_alternative(item, self.candidate, [], self.client, self.config)


class TreeTests(unittest.TestCase):
    def test_full_tree_diff_includes_add_modify_delete(self):
        before = {"same": ("a", "100644", "blob"), "change": ("b", "100644", "blob"),
                  "deleted": ("c", "100644", "blob")}
        after = {"same": ("a", "100644", "blob"), "change": ("d", "100644", "blob"),
                 "added": ("e", "100644", "blob")}
        self.assertEqual(set(gate.tree_changes(before, after)), {"change", "deleted", "added"})

    def test_rejects_missing_changed_file_evidence(self):
        changed = {"a.java": (None, ("a", "100644", "blob")),
                   "b.java": (None, ("b", "100644", "blob"))}
        with self.assertRaisesRegex(gate.GateError, "no review evidence"):
            gate.ensure_exact_coverage(changed, {"a.java": "review-1"}, set())

    def test_rejects_file_changed_after_shard_review(self):
        changed = {"a.java": (("base", "100644", "blob"), ("candidate", "100644", "blob"))}
        with self.assertRaisesRegex(gate.GateError, "head content changed after review"):
            gate.verify_path_content("a.java", changed,
                                     {"a.java": ("base", "100644", "blob")},
                                     {"a.java": ("old-reviewed", "100644", "blob")},
                                     {"a.java": ("base", "100644", "blob")},
                                     {"a.java": ("candidate", "100644", "blob")})

    def test_rejects_base_changed_after_shard_review(self):
        changed = {"a.java": (("new-base", "100644", "blob"), ("head", "100644", "blob"))}
        with self.assertRaisesRegex(gate.GateError, "base content changed after review"):
            gate.verify_path_content("a.java", changed,
                                     {"a.java": ("old-base", "100644", "blob")},
                                     {"a.java": ("head", "100644", "blob")},
                                     {"a.java": ("new-base", "100644", "blob")},
                                     {"a.java": ("head", "100644", "blob")})

    def test_rejects_duplicate_review_and_alternative_coverage(self):
        changed = {"a.java": (None, ("a", "100644", "blob"))}
        with self.assertRaisesRegex(gate.GateError, "both CodeRabbit and alternative"):
            gate.ensure_exact_coverage(changed, {"a.java": "review-1"}, {"a.java"})

    def test_rejects_evidence_for_path_outside_candidate(self):
        changed = {"a.java": (None, ("a", "100644", "blob"))}
        with self.assertRaisesRegex(gate.GateError, "outside candidate"):
            gate.ensure_exact_coverage(changed, {"other.java": "review-1"}, set())

    def test_rename_requires_both_path_names(self):
        _, paths, _ = gate.fileset([{"filename": "new.java", "previous_filename": "old.java"}])
        self.assertEqual(paths, {"new.java", "old.java"})

    def test_unlisted_unsupported_path_is_detected(self):
        changed = {"generated.graphqls": (None, ("generated", "100644", "blob")),
                   "Resolver.java": (None, ("resolver", "100644", "blob"))}
        with self.assertRaisesRegex(gate.GateError, "no review evidence"):
            gate.ensure_exact_coverage(changed, {"Resolver.java": "review-1"}, set())


if __name__ == "__main__":
    unittest.main()
