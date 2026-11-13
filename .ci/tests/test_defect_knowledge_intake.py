import importlib.util
from pathlib import Path
import unittest

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
        self.assertIn("<!-- defect-intake-item:inline-99 -->", rendered)
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

    def test_chunking_is_stable_and_bounded(self):
        values = [{"sourceId": f"review-{i}"} for i in range(61)]
        batches = MODULE.chunked(values, size=25)
        self.assertEqual([len(batch) for batch in batches], [25, 25, 11])
        self.assertEqual([item["sourceId"] for batch in batches for item in batch],
                         [item["sourceId"] for item in values])


if __name__ == "__main__":
    unittest.main()
