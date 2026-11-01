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
    def __init__(self, checks=None, workflow=None):
        self._checks = checks or []
        self._workflow = workflow or {
            "path": ".github/workflows/ci.yml",
            "event": "pull_request",
            "head_sha": "head-A"
        }
        self._base_workflow_sha = "trusted-workflow-blob"
        self._head_workflow_sha = "trusted-workflow-blob"
        self.check_calls = []

    def checks(self, sha, source_repo=None):
        self.check_calls.append((sha, source_repo))
        return self._checks

    def workflow_run(self, details_url):
        self.last_details_url = details_url
        return self._workflow

    def tree(self, sha, source_repo=None):
        blob = self._base_workflow_sha if sha == "base-A" else self._head_workflow_sha
        return {".github/workflows/ci.yml": (blob, "100644", "blob")}


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
                "reviewShards": [{"prNumber": 51, "reviewId": 9, "role": "review"}],
                "alternativeCoverage": []}

    def valid_alternative_config(self):
        return {"schemaVersion": 1, "checks": {
            "Dependency Review (PR dependency changes)": {
                "appSlugs": ["github-actions"],
                "pathPatterns": ["package-lock.json", "**/package-lock.json"],
            "workflowPath": ".github/workflows/ci.yml",
            "events": ["pull_request"]
            }
        }}

    def test_accepts_independent_review_shard_manifest(self):
        gate.validate_manifest(self.valid())

    def test_rejects_limit_above_100(self):
        manifest = self.valid()
        manifest["maxFilesPerReview"] = 101
        with self.assertRaisesRegex(gate.GateError, "must be 100"):
            gate.validate_manifest(manifest)

    def test_rejects_candidate_role_in_review_shards(self):
        manifest = self.valid()
        manifest["reviewShards"][0]["role"] = "candidate"
        with self.assertRaisesRegex(gate.GateError, "independent review PRs only"):
            gate.validate_manifest(manifest)

    def test_rejects_duplicate_shard_pr(self):
        manifest = self.valid()
        manifest["reviewShards"].append({"prNumber": 51, "reviewId": 10, "role": "review"})
        with self.assertRaisesRegex(gate.GateError, "appears more than once"):
            gate.validate_manifest(manifest)

    def test_rejects_unsupported_review_shard_role(self):
        manifest = self.valid()
        manifest["reviewShards"][0]["role"] = "alternative"
        with self.assertRaisesRegex(gate.GateError, "independent review PRs only"):
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
        self.candidate = {"base": {"sha": "base-A"},
                          "head": {"sha": "head-A", "repo": {"full_name": "example/repo"}},
                          "user": {"login": "pull-author"},
                          "html_url": "https://github.com/example/repo/pull/50"}
        self.review = {"id": 91, "user": {"login": "independent-reviewer"},
                       "commit_id": "head-A", "state": "APPROVED",
                       "submitted_at": "2026-10-31T17:00:00Z",
                       "author_association": "COLLABORATOR",
                       "html_url": "https://github.com/example/repo/pull/50#pullrequestreview-91"}
        self.config = self.valid_alternative_config()
        self.check = {"name": "Dependency Review (PR dependency changes)",
                      "head_sha": "head-A", "status": "completed", "conclusion": "success",
                      "completed_at": "2026-10-31T17:00:00Z",
                      "html_url": "https://github.com/example/repo/actions/runs/100",
                      "details_url": "https://github.com/example/repo/actions/runs/100/job/900",
                      "app": {"slug": "github-actions"}}
        self.client = FakeClient([self.check])

    def test_accepts_independent_latest_manual_review_url(self):
        item = {"kind": "manual-review", "paths": ["generated.graphqls"],
                "reason": "independent contract review", "evidenceUrl": self.review["html_url"],
                "reviewerLogin": "independent-reviewer", "reviewId": 91}
        self.assertEqual(gate.verify_alternative(item, self.candidate, [self.review],
                                                 self.client, self.config), {"generated.graphqls"})

    def test_rejects_manual_reviewer_without_repository_access(self):
        item = {"kind": "manual-review", "paths": ["generated.graphqls"],
                "reason": "untrusted reviewer", "evidenceUrl": self.review["html_url"],
                "reviewerLogin": "independent-reviewer", "reviewId": 91}
        review = dict(self.review, author_association="NONE")
        with self.assertRaisesRegex(gate.GateError, "repository owner, member, or collaborator"):
            gate.verify_alternative(item, self.candidate, [review], self.client, self.config)

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
        item = {"kind": "check", "paths": ["package-lock.json"], "reason": "dependency manifest verification",
                "evidenceUrl": self.check["html_url"], "checkName": self.check["name"]}
        self.assertEqual(gate.verify_alternative(item, self.candidate, [], self.client, self.config), {"package-lock.json"})
        self.assertEqual(self.client.check_calls, [("head-A", None)])
        self.assertEqual(self.client.last_details_url, self.check["details_url"])

    def test_rejects_check_from_untrusted_github_app(self):
        item = {"kind": "check", "paths": ["package-lock.json"], "reason": "dependency manifest verification",
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

    def test_rejects_check_from_untrusted_workflow_path(self):
        item = {"kind": "check", "paths": ["package-lock.json"], "reason": "dependency manifest verification",
                "evidenceUrl": self.check["html_url"], "checkName": self.check["name"]}
        fake = FakeClient([self.check], workflow={
            "path": ".github/workflows/attacker.yml", "event": "pull_request", "head_sha": "head-A"
        })
        with self.assertRaisesRegex(gate.GateError, "trusted workflow"):
            gate.verify_alternative(item, self.candidate, [], fake, self.config)

    def test_rejects_check_from_unexpected_event(self):
        item = {"kind": "check", "paths": ["package-lock.json"], "reason": "dependency manifest verification",
                "evidenceUrl": self.check["html_url"], "checkName": self.check["name"]}
        fake = FakeClient([self.check], workflow={
            "path": ".github/workflows/ci.yml", "event": "workflow_dispatch", "head_sha": "head-A"
        })
        with self.assertRaisesRegex(gate.GateError, "unexpected workflow event"):
            gate.verify_alternative(item, self.candidate, [], fake, self.config)

    def test_rejects_candidate_modified_trusted_workflow(self):
        item = {"kind": "check", "paths": ["package-lock.json"], "reason": "dependency manifest verification",
                "evidenceUrl": self.check["html_url"], "checkName": self.check["name"]}
        fake = FakeClient([self.check])
        fake._head_workflow_sha = "attacker-workflow-blob"
        with self.assertRaisesRegex(gate.GateError, "changed the trusted workflow"):
            gate.verify_alternative(item, self.candidate, [], fake, self.config)

    def test_rejects_failed_check(self):
        item = {"kind": "check", "paths": ["package-lock.json"], "reason": "dependency manifest verification",
                "evidenceUrl": self.check["html_url"], "checkName": self.check["name"]}
        failed = dict(self.check, conclusion="failure")
        with self.assertRaisesRegex(gate.GateError, "not successful"):
            gate.verify_alternative(item, self.candidate, [], FakeClient([failed]), self.config)

    def test_rejects_wrong_check_evidence_url(self):
        item = {"kind": "check", "paths": ["package-lock.json"], "reason": "dependency manifest verification",
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



class CoverageObligationTests(unittest.TestCase):
    def test_manifest_path_is_excluded_explicitly(self):
        changes = {"a.java", "docs/governance/campaign/release/coderabbit-review-coverage.json"}
        obligation, excluded = gate.coverage_obligation(
            changes, "docs/governance/campaign/release/coderabbit-review-coverage.json")
        self.assertEqual(obligation, {"a.java"})
        self.assertEqual(excluded, ["docs/governance/campaign/release/coderabbit-review-coverage.json"])

    def test_no_manifest_path_means_no_exclusion(self):
        obligation, excluded = gate.coverage_obligation({"a.java", "b.java"}, "other.json")
        self.assertEqual(obligation, {"a.java", "b.java"})
        self.assertEqual(excluded, [])


class MergeBaseTests(unittest.TestCase):
    def test_uses_compare_endpoint_and_returns_merge_base(self):
        client = gate.GitHub("example/repo", "token")
        calls = []

        def fake_get_from(root, path):
            calls.append((root, path))
            return {"merge_base_commit": {"sha": "a" * 40}}

        client.get_from = fake_get_from
        self.assertEqual(client.merge_base("b" * 40, "c" * 40), "a" * 40)
        self.assertEqual(calls, [("https://api.github.com/repos/example/repo",
                                  "/compare/" + "b" * 40 + "..." + "c" * 40)])

    def test_rejects_compare_without_merge_base(self):
        client = gate.GitHub("example/repo", "token")
        client.get_from = lambda root, path: {}
        with self.assertRaisesRegex(gate.GateError, "merge-base"):
            client.merge_base("b" * 40, "c" * 40)

    def test_rejects_legacy_merge_base_sha_field(self):
        # The Compare API has no top-level merge_base_sha field (verified live
        # 2026-10-31): a payload carrying only that legacy key must fail
        # closed, never silently return a missing merge base.
        client = gate.GitHub("example/repo", "token")
        client.get_from = lambda root, path: {"merge_base_sha": "a" * 40}
        with self.assertRaisesRegex(gate.GateError, "merge-base"):
            client.merge_base("b" * 40, "c" * 40)


class RunClient:
    """In-memory GitHub client for gate.run() integration tests."""

    def __init__(self, pulls=None, files=None, reviews=None, trees=None, manifests=None, merge_bases=None):
        self._pulls = pulls or {}
        self._files = files or {}
        self._reviews = reviews or {}
        self._trees = trees or {}
        self._manifests = manifests or {}
        self._merge_bases = merge_bases or {}
        self.calls = []

    def pull(self, number):
        self.calls.append(("pull", number))
        return self._pulls[number]

    def files(self, number):
        return self._files[number]

    def reviews(self, number):
        return self._reviews[number]

    def tree(self, sha, source_repo=None):
        self.calls.append(("tree", sha))
        return self._trees[sha]

    def checks(self, sha, source_repo=None):
        return []

    def file_json(self, path, ref, source_repo=None):
        self.calls.append(("file_json", path, ref))
        try:
            return self._manifests[(path, ref)]
        except KeyError:
            raise gate.GateError(f"manifest path is not a file: {path}")

    def merge_base(self, base_sha, head_sha, source_repo=None):
        self.calls.append(("merge_base", base_sha, head_sha))
        return self._merge_bases[(base_sha, head_sha)]

    def workflow_run(self, details_url):
        raise AssertionError("not used in these tests")


MANIFEST_PATH = "docs/governance/campaign/release/coderabbit-review-coverage.json"
BT2 = chr(96)


def shard_review_body(paths):
    lines = "\n".join("* " + BT2 + p + BT2 for p in paths)
    return ("<details><summary><strong>Files selected for processing: "
            + str(len(paths)) + "</strong></summary>\n<dl><dd>\n" + lines + "\n</dd></dl></details>")


class RunPathTests(ManifestTests):
    """Integration coverage for gate.run(): the oversized verdict path, the
    merge-base tree basis, and the manifest self-exclusion."""

    PRODUCT = [f"src/F{i:03d}.java" for i in range(100)]

    def setUp(self):
        self.config = self.valid_alternative_config()
        self.trusted_base = "main-tip"
        self.merge_bases = {
            (self.trusted_base, "head-A"): "mb-1",
            # each shard is cut from the current main tip, so its own merge
            # base with the recorded base is the main tip itself
            (self.trusted_base, "shard-head-A"): self.trusted_base,
        }
        self.base_tree = {path: (path + "-base", "100644", "blob") for path in self.PRODUCT}
        # main moved after the PR opened: the gate file exists on the main tip
        # but not at the merge base; it must never become a candidate change.
        self.main_tip_tree = dict(self.base_tree)
        self.main_tip_tree[".github/workflows/coderabbit-gate.yml"] = ("gate-blob", "100644", "blob")
        # 101 changed paths at the candidate head: 100 product paths + the
        # coverage manifest itself — over the 100-file per-review ceiling.
        self.head_tree = {path: (path + "-head", "100644", "blob") for path in self.PRODUCT}
        self.head_tree[MANIFEST_PATH] = ("manifest-blob", "100644", "blob")
        # the shard branch descends from the moved main tip: its tree carries
        # the main-only gate file, and its diff versus ITS OWN merge base (the
        # main tip) is exactly the 100 product files it restates.
        self.shard_tree = dict(self.main_tip_tree)
        self.shard_tree.update({path: (path + "-head", "100644", "blob") for path in self.PRODUCT})
        self.candidate_pull = {
            "state": "open",
            "base": {"sha": self.trusted_base, "ref": "main"},
            "head": {"sha": "head-A", "repo": {"full_name": "example/repo"}},
            "user": {"login": "pull-author"},
        }
        self.shard_pull = {
            "state": "open",
            "base": {"sha": self.trusted_base, "ref": "main"},
            "head": {"sha": "shard-head-A", "repo": {"full_name": "example/repo"}},
            "user": {"login": "review-branch-owner"},
        }
        self.shard_review = {
            "id": 9, "user": {"login": gate.BOT}, "commit_id": "shard-head-A",
            "state": "COMMENTED", "submitted_at": "2026-10-31T19:40:00Z",
            "body": shard_review_body(self.PRODUCT),
        }
        self.manifest = {
            "schemaVersion": 1, "maxFilesPerReview": 100,
            "reviewShards": [{"prNumber": 51, "reviewId": 9, "role": "review"}],
            "alternativeCoverage": [],
        }
        self.candidate_files = [{"filename": path} for path in self.PRODUCT] + [{"filename": MANIFEST_PATH}]
        self.shard_files = [{"filename": path} for path in self.PRODUCT]

    def client(self, **overrides):
        return RunClient(
            pulls=overrides.get("pulls", {50: self.candidate_pull, 51: self.shard_pull}),
            files=overrides.get("files", {50: self.candidate_files, 51: self.shard_files}),
            reviews=overrides.get("reviews", {50: [], 51: [self.shard_review]}),
            trees=overrides.get("trees", {
                "mb-1": self.base_tree, self.trusted_base: self.main_tip_tree,
                "head-A": self.head_tree, "shard-head-A": self.shard_tree,
            }),
            manifests=overrides.get("manifests", {(MANIFEST_PATH, "head-A"): self.manifest}),
            merge_bases=overrides.get("merge_bases", self.merge_bases),
        )

    def run_gate(self, client):
        real_github = gate.GitHub
        gate.GitHub = lambda repo, token: client
        try:
            return gate.run("example/repo", 50, MANIFEST_PATH, self.config, self.trusted_base, "token")
        finally:
            gate.GitHub = real_github

    def test_oversized_passes_without_any_candidate_head_review(self):
        client = self.client()
        self.run_gate(client)
        self.assertIn(("merge_base", self.trusted_base, "head-A"), client.calls)
        self.assertIn(("tree", "mb-1"), client.calls)
        # the candidate base is the merge-base tree (mb-1), never the moved
        # main tip: tree("main-tip") is fetched only after the shard's own
        # merge base resolves, i.e. as the shard base
        self.assertIn(("merge_base", self.trusted_base, "shard-head-A"), client.calls)
        self.assertLess(client.calls.index(("merge_base", self.trusted_base, "shard-head-A")),
                        client.calls.index(("tree", self.trusted_base)))

    def test_oversized_without_manifest_fails_closed(self):
        client = self.client(manifests={})
        with self.assertRaisesRegex(gate.GateError, "manifest path is not a file"):
            self.run_gate(client)

    def test_oversized_with_uncovered_path_fails_closed(self):
        covered = self.PRODUCT[:99]
        partial = dict(self.shard_review)
        partial["body"] = shard_review_body(covered)
        client = self.client(
            reviews={50: [], 51: [partial]},
            files={50: self.candidate_files,
                   51: [{"filename": path} for path in covered]},
            trees={
                "mb-1": self.base_tree, self.trusted_base: self.main_tip_tree,
                "head-A": self.head_tree,
                # the uncovered path stays at its base blob on the shard branch
                # (unchanged there), so the shard's tree diff versus its own
                # merge base (the main tip) equals its 99 files; the main-only
                # gate file is present on both sides and never enters the diff
                "shard-head-A": {path: (path + "-head", "100644", "blob") for path in covered} | {
                    self.PRODUCT[99]: (self.PRODUCT[99] + "-base", "100644", "blob"),
                    ".github/workflows/coderabbit-gate.yml": ("gate-blob", "100644", "blob")},
            },
        )
        with self.assertRaisesRegex(gate.GateError, "no review evidence"):
            self.run_gate(client)

    def test_oversized_with_stale_shard_review_fails_closed(self):
        stale = dict(self.shard_review)
        stale["commit_id"] = "shard-head-OLD"
        newer = dict(self.shard_review, id=10, submitted_at="2026-10-31T20:40:00Z")
        client = self.client(reviews={50: [], 51: [stale, newer]})
        with self.assertRaisesRegex(gate.GateError, "is stale"):
            self.run_gate(client)

    def test_manifest_path_itself_is_never_a_coverage_obligation(self):
        # The manifest path exists in the candidate diff (it is committed on the
        # candidate head) but carries no shard; only the gate-metadata exclusion
        # lets the verdict pass, and the exclusion must not cover anything else.
        client = self.client()
        self.run_gate(client)
        self.assertIn(("file_json", MANIFEST_PATH, "head-A"), client.calls)

    def test_rename_heavy_candidate_stays_on_the_direct_path(self):
        # 60 renames: 60 REST files but 120 tree paths (old + new name). The
        # gate workflow counts REST files and takes the direct path; the
        # verifier must use the same basis — the manifest is never consulted.
        old_names = [f"src/O{i:03d}.java" for i in range(60)]
        new_names = [f"src/N{i:03d}.java" for i in range(60)]
        rename_records = [{"filename": new, "previous_filename": old}
                          for old, new in zip(old_names, new_names)]
        review = {
            "id": 8, "user": {"login": gate.BOT}, "commit_id": "head-A",
            "state": "COMMENTED", "submitted_at": "2026-10-31T19:00:00Z",
            "body": shard_review_body(new_names),
        }
        client = self.client(
            reviews={50: [review], 51: [self.shard_review]},
            files={50: rename_records, 51: self.shard_files},
            trees={
                "mb-1": {path: (path + "-base", "100644", "blob") for path in old_names},
                self.trusted_base: dict(
                    {path: (path + "-base", "100644", "blob") for path in old_names},
                    **{".github/workflows/coderabbit-gate.yml": ("gate-blob", "100644", "blob")}),
                "head-A": {path: (path + "-head", "100644", "blob") for path in new_names},
                "shard-head-A": self.shard_tree,
            },
            manifests={},
        )
        self.run_gate(client)
        self.assertNotIn(("file_json", MANIFEST_PATH, "head-A"), client.calls)

    def test_direct_path_still_requires_candidate_head_review(self):
        small = ["src/A.java", "src/B.java"]
        candidate_review = {
            "id": 8, "user": {"login": gate.BOT}, "commit_id": "head-A",
            "state": "COMMENTED", "submitted_at": "2026-10-31T19:00:00Z",
            "body": shard_review_body(small),
        }
        base = {path: (path + "-base", "100644", "blob") for path in small}
        head = {path: (path + "-head", "100644", "blob") for path in small}
        client = self.client(
            pulls={50: self.candidate_pull, 51: self.shard_pull},
            reviews={50: [candidate_review], 51: [self.shard_review]},
            files={50: [{"filename": path} for path in small],
                   51: [{"filename": path} for path in small]},
            trees={
                "mb-1": base, self.trusted_base: dict(base, **{".github/workflows/coderabbit-gate.yml": ("gate-blob", "100644", "blob")}),
                "head-A": head, "shard-head-A": head,
            },
        )
        self.run_gate(client)


if __name__ == "__main__":
    unittest.main()
