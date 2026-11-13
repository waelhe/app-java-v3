import importlib.util
from pathlib import Path
import unittest
import unittest.mock

SCRIPT = Path(__file__).resolve().parents[1] / "defect_knowledge_intake.py"
SPEC = importlib.util.spec_from_file_location("defect_knowledge_intake", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)


class DefectKnowledgeIntakeTests(unittest.TestCase):
    def test_collects_reviews_inline_comments_and_discussion_without_duplicate_ids(self):
        pr = {"number": 12}
        reviews = [
            {"id": 101, "user": {"login": "coderabbitai"}, "submitted_at": "2026-11-13T10:00:00Z",
             "state": "CHANGES_REQUESTED", "html_url": "https://github.com/waelhe/app-java-v3/pull/12#pullrequestreview-101",
             "body": "Actionable comments posted: 2"},
            {"id": 101, "user": {"login": "coderabbitai"}, "submitted_at": "2026-11-13T10:00:00Z",
             "state": "CHANGES_REQUESTED", "html_url": "https://github.com/waelhe/app-java-v3/pull/12#pullrequestreview-101",
             "body": "Actionable comments posted: 2"},
        ]
        inline = [
            {"id": 202, "user": {"login": "coderabbitai"}, "created_at": "2026-11-13T10:01:00Z",
             "html_url": "https://github.com/waelhe/app-java-v3/pull/12#discussion_r202",
             "body": "A bug exists", "path": "src/Service.java", "line": 42}
        ]
        discussion = [
            {"id": 303, "user": {"login": "waelhe"}, "created_at": "2026-11-13T10:02:00Z",
             "html_url": "https://github.com/waelhe/app-java-v3/pull/12#issuecomment-303",
             "body": "Please review this change"}
        ]
        items = MODULE.collect_items("waelhe/app-java-v3", pr, reviews, inline, discussion)
        self.assertEqual([item["sourceId"] for item in items],
                         ["review-101", "inline-202", "discussion-303"])
        self.assertEqual(items[1]["path"], "src/Service.java")
        self.assertEqual(items[1]["line"], 42)
        self.assertTrue(items[1]["intakeKey"].startswith("inline-202-r"))
        self.assertEqual(len(items[1]["intakeKey"].rsplit("-r", 1)[1]), 16)

    def test_edited_review_submission_creates_a_new_revision_key(self):
        pr = {"number": 13}
        before = MODULE.collect_items(
            "waelhe/app-java-v3", pr,
            [{"id": 501, "user": {"login": "coderabbitai"},
              "submitted_at": "2026-11-13T10:00:00Z", "updated_at": "2026-11-13T10:00:00Z",
              "state": "COMMENTED", "html_url": "https://github.com/waelhe/app-java-v3/pull/13#pullrequestreview-501",
              "body": "Initial review"}],
            [], [],
        )[0]
        after = MODULE.collect_items(
            "waelhe/app-java-v3", pr,
            [{"id": 501, "user": {"login": "coderabbitai"},
              "submitted_at": "2026-11-13T10:00:00Z", "updated_at": "2026-11-13T10:07:00Z",
              "state": "CHANGES_REQUESTED", "html_url": "https://github.com/waelhe/app-java-v3/pull/13#pullrequestreview-501",
              "body": "Edited review"}],
            [], [],
        )[0]
        self.assertEqual(before["sourceId"], after["sourceId"])
        self.assertNotEqual(before["intakeKey"], after["intakeKey"])
        self.assertEqual(after["excerpt"], "Edited review")

    def test_sync_snapshot_is_separate_from_human_triage_body(self):
        summary = MODULE.render_sync_summary(
            "waelhe/app-java-v3",
            {"number": 14, "updated_at": "2026-11-13T10:00:00Z",
             "state": "open", "head": {"sha": "a" * 40}},
            [],
        )
        self.assertIn(MODULE.SUMMARY_MARKER, summary)
        self.assertIn("Human triage notes and dispositions", summary)
        self.assertNotIn("# Review intake", summary)

    def test_excerpt_escapes_untrusted_markup_and_limits_length(self):
        excerpt = MODULE.short_excerpt('<script>alert("x")</script>' + ("x" * 300))
        self.assertTrue(excerpt.startswith("&lt;script&gt;"))
        self.assertLessEqual(len(excerpt), 220)

    def test_render_item_keeps_original_source_link_and_stable_marker(self):
        item = MODULE.make_item(
            "inline", 99, "reviewer", "2026-11-13T10:00:00Z",
            "inline-review-comment",
            "https://github.com/waelhe/app-java-v3/pull/12#discussion_r99",
            "Finding headline", "module/File.java", 15,
        )
        rendered = MODULE.render_item(item, 12)
        self.assertIn(f"<!-- defect-intake-item:{item['intakeKey']} -->", rendered)
        self.assertIn("source inline-99", rendered)
        self.assertIn("module/File.java:15", rendered)
        self.assertIn("https://github.com/waelhe/app-java-v3/pull/12#discussion_r99", rendered)

    def test_non_github_url_is_replaced_by_pr_fallback(self):
        item = MODULE.make_item(
            "review", 1, "bot", "2026-11-13T10:00:00Z", "COMMENTED",
            "https://example.invalid/untrusted", "review body",
        )
        rendered = MODULE.render_item(item, 12)
        self.assertIn("https://github.com/waelhe/app-java-v3/pull/12", rendered)
        self.assertNotIn("example.invalid", rendered)


    def test_issue_comment_event_resolves_the_pr_number_from_issue_payload(self):
        event = {
            "action": "created",
            "issue": {
                "number": 834,
                "pull_request": {"url": "https://api.github.com/repos/waelhe/app-java-v3/pulls/834"},
            },
            "comment": {"id": 123},
        }
        self.assertEqual(MODULE.event_pull_request_number(event, "issue_comment"), 834)

    def test_review_and_pull_request_events_resolve_their_pr_number(self):
        self.assertEqual(
            MODULE.event_pull_request_number({"pull_request": {"number": 835}}, "pull_request_review"),
            835,
        )
        self.assertEqual(
            MODULE.event_pull_request_number({"number": 836}, "pull_request"),
            836,
        )

    def test_intake_workflow_uses_a_single_writer_group_for_all_event_kinds(self):
        workflow = Path(__file__).resolve().parents[2] / ".github" / "workflows" / "defect-knowledge-intake.yml"
        content = workflow.read_text(encoding="utf-8")
        self.assertIn("group: defect-knowledge-intake-single-writer", content)
        self.assertIn("cancel-in-progress: false", content)


    def test_build_intake_issue_index_reads_once_and_ignores_pr_issues(self):
        with unittest.mock.patch.object(MODULE, "api_pages", return_value=[
            {"number": 99, "title": "Defect Intake: PR #834 — spoof", "body": "<!-- defect-intake-pr:834 -->\nSource PR: https://github.com/waelhe/app-java-v3/pull/834", "user": {"login": "waelhe"}},
            {"number": 100, "title": "Defect Intake: PR #834 — real", "body": "<!-- defect-intake-pr:834 -->\nSource PR: https://github.com/waelhe/app-java-v3/pull/834", "user": {"login": MODULE.AUTOMATION_ACTOR}, "state": "open"},
            {"number": 101, "body": "<!-- defect-intake-pr:835 -->", "pull_request": {"url": "x"}},
            {"number": 102, "body": "ordinary issue", "user": {"login": "waelhe"}, "state": "open"},
        ]) as api_pages:
            index = MODULE.build_intake_issue_index("waelhe/app-java-v3", "token")
        api_pages.assert_called_once_with("/repos/waelhe/app-java-v3/issues?state=all", "token")
        self.assertEqual(set(index), {834})
        self.assertEqual(index[834]["number"], 100)

    def test_duplicate_intake_issues_fail_closed_instead_of_creating_another(self):
        with unittest.mock.patch.object(MODULE, "api_pages", return_value=[
            {"number": 100, "title": "Defect Intake: PR #834 — a", "body": "<!-- defect-intake-pr:834 -->\nSource PR: https://github.com/waelhe/app-java-v3/pull/834", "user": {"login": MODULE.AUTOMATION_ACTOR}},
            {"number": 200, "title": "Defect Intake: PR #834 — b", "body": "<!-- defect-intake-pr:834 -->\nSource PR: https://github.com/waelhe/app-java-v3/pull/834", "user": {"login": MODULE.AUTOMATION_ACTOR}},
        ]):
            with self.assertRaisesRegex(MODULE.IntakeError, "Multiple defect-intake issues"):
                MODULE.build_intake_issue_index("waelhe/app-java-v3", "token")

    def test_human_comment_markers_cannot_suppress_source_items_or_take_over_summary(self):
        item_key = "inline-202-r0123456789abcdef"
        item_marker = f"<!-- defect-intake-item:{item_key} -->"
        user_comment = {
            "user": {"login": "waelhe"},
            "body": item_marker + "\\n" + MODULE.SUMMARY_MARKER + "\\nHuman triage notes",
            "id": 900,
        }
        bot_comment = {
            "user": {"login": MODULE.AUTOMATION_ACTOR},
            "body": item_marker + "\\n" + MODULE.SUMMARY_MARKER + "\\nManaged snapshot",
            "id": 901,
        }
        self.assertEqual(MODULE.existing_item_ids([user_comment]), set())
        self.assertEqual(MODULE.existing_item_ids([user_comment, bot_comment]), {item_key})
        self.assertIsNone(MODULE.find_sync_summary_comment([user_comment]))
        self.assertIs(MODULE.find_sync_summary_comment([user_comment, bot_comment]), bot_comment)

    def test_duplicate_bot_summary_comments_fail_closed(self):
        comments = [
            {"user": {"login": MODULE.AUTOMATION_ACTOR}, "body": MODULE.SUMMARY_MARKER, "id": 1},
            {"user": {"login": MODULE.AUTOMATION_ACTOR}, "body": MODULE.SUMMARY_MARKER, "id": 2},
        ]
        with self.assertRaisesRegex(MODULE.IntakeError, "Multiple bot-owned"):
            MODULE.find_sync_summary_comment(comments)

    def test_bot_intake_with_multiple_pr_markers_fails_closed(self):
        issue = {
            "number": 779,
            "title": "Defect Intake: PR #834 — duplicated",
            "body": "<!-- defect-intake-pr:834 -->\\n<!-- defect-intake-pr:835 -->\\nSource PR: https://github.com/waelhe/app-java-v3/pull/834",
            "user": {"login": MODULE.AUTOMATION_ACTOR},
        }
        with unittest.mock.patch.object(MODULE, "api_pages", return_value=[issue]):
            with self.assertRaisesRegex(MODULE.IntakeError, "ambiguous control markers"):
                MODULE.build_intake_issue_index("waelhe/app-java-v3", "token")

    def test_user_authored_issue_cannot_claim_or_hijack_an_intake_marker(self):
        spoof = {
            "number": 777,
            "title": "Defect Intake: PR #834 — fake",
            "body": "<!-- defect-intake-pr:834 -->\nSource PR: https://github.com/waelhe/app-java-v3/pull/834",
            "user": {"login": "waelhe"},
        }
        with unittest.mock.patch.object(MODULE, "api_pages", return_value=[spoof]):
            self.assertEqual(MODULE.build_intake_issue_index("waelhe/app-java-v3", "token"), {})

    def test_bot_owned_intake_with_missing_or_conflicting_markers_fails_closed(self):
        tampered = {
            "number": 778,
            "title": "Defect Intake: PR #834 — tampered",
            "body": "Human notes only",
            "user": {"login": MODULE.AUTOMATION_ACTOR},
        }
        with unittest.mock.patch.object(MODULE, "api_pages", return_value=[tampered]):
            with self.assertRaisesRegex(MODULE.IntakeError, "missing control marker/title"):
                MODULE.build_intake_issue_index("waelhe/app-java-v3", "token")

    def test_daily_recovery_sweep_includes_closed_prs_since_rollout(self):
        workflow = Path(__file__).resolve().parents[2] / ".github" / "workflows" / "defect-knowledge-intake.yml"
        content = workflow.read_text(encoding="utf-8")
        self.assertIn('GITHUB_EVENT_NAME" = "schedule"', content)
        self.assertIn('--include-closed-since "2026-11-13"', content)

    def test_chunking_is_stable_and_bounded(self):
        values = [{"sourceId": f"review-{i}"} for i in range(61)]
        batches = MODULE.chunked(values, size=25)
        self.assertEqual([len(batch) for batch in batches], [25, 25, 11])
        self.assertEqual([item["sourceId"] for batch in batches for item in batch],
                         [item["sourceId"] for item in values])


if __name__ == "__main__":
    unittest.main()
