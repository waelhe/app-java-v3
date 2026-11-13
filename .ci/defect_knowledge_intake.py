#!/usr/bin/env python3
"""Idempotent GitHub review-signal intake for the defect-knowledge workflow.

Raw reviewer prose is treated as untrusted data. The collector stores links,
author/state/path metadata and a short escaped excerpt; it never classifies a
comment as a confirmed defect and never writes to the source PR.
"""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from typing import Any

API = "https://api.github.com"
API_VERSION = "2022-11-28"
ITEM_MARKER = re.compile(r"<!-- defect-intake-item:([a-z]+-[0-9]+-r[a-z0-9]+) -->")
PR_MARKER_TEMPLATE = "<!-- defect-intake-pr:{number} -->"
SUMMARY_MARKER = "<!-- defect-intake-sync-summary -->"
BATCH_SIZE = 25


class IntakeError(RuntimeError):
    pass


def api_request(method: str, path: str, token: str, payload: dict[str, Any] | None = None) -> Any:
    url = path if path.startswith("https://") else API + path
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    request = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": API_VERSION,
            "Content-Type": "application/json",
            "User-Agent": "app-java-v3-defect-knowledge-intake",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read().decode("utf-8")
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:1200]
        raise IntakeError(f"GitHub API {method} {url} returned HTTP {exc.code}: {detail}") from exc
    except urllib.error.URLError as exc:
        raise IntakeError(f"GitHub API {method} {url} failed: {exc}") from exc


def api_pages(path: str, token: str) -> list[dict[str, Any]]:
    collected: list[dict[str, Any]] = []
    page = 1
    while True:
        separator = "&" if "?" in path else "?"
        page_path = f"{path}{separator}per_page=100&page={page}"
        result = api_request("GET", page_path, token)
        if not isinstance(result, list):
            raise IntakeError(f"Expected an array from paginated GitHub API: {page_path}")
        collected.extend(item for item in result if isinstance(item, dict))
        if len(result) < 100:
            return collected
        page += 1


def safe_github_url(url: str | None, fallback: str) -> str:
    if isinstance(url, str) and url.startswith("https://github.com/"):
        return url
    return fallback


def short_excerpt(body: str | None, limit: int = 220) -> str:
    if not body:
        return "No review body; inspect the recorded verdict and linked thread."[:limit]
    for raw_line in body.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("<!--") or line.startswith(chr(96) * 3):
            continue
        line = re.sub(r"\s+", " ", line)
        escaped = html.escape(line, quote=True)
        if len(escaped) <= limit:
            return escaped
        suffix = "…" if limit > 0 else ""
        budget = max(0, limit - len(suffix))
        parts: list[str] = []
        used = 0
        for character in line:
            token = html.escape(character, quote=True)
            if used + len(token) > budget:
                break
            parts.append(token)
            used += len(token)
        return "".join(parts) + suffix
    return "Review body contains no plain-text summary; open the original source."[:limit]


def make_item(kind: str, source_id: Any, author: str, created_at: str,
              state: str, source_url: str, body: str | None,
              path: str | None = None, line: int | None = None,
              updated_at: str | None = None) -> dict[str, Any]:
    source_key = f"{kind}-{source_id}"
    # Include the rendered source fields and body in the revision identity.
    # Some review APIs omit an edited-at timestamp; hashing the source snapshot
    # still makes an edited body/state/path visible to the idempotent sweep.
    revision_snapshot = json.dumps({
        "author": author or "unknown",
        "createdAt": created_at or "",
        "updatedAt": updated_at or created_at or "",
        "state": state or "",
        "sourceUrl": source_url,
        "path": path or "",
        "line": line,
        "body": body or "",
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    revision = hashlib.sha256(revision_snapshot.encode("utf-8")).hexdigest()[:16]
    return {
        "sourceId": source_key,
        "intakeKey": f"{source_key}-r{revision}",
        "kind": kind,
        "author": author or "unknown",
        "createdAt": created_at or "",
        "updatedAt": updated_at or created_at or "",
        "state": state or "",
        "sourceUrl": source_url,
        "path": path or "",
        "line": line,
        "excerpt": short_excerpt(body),
    }


def collect_items(repo: str, pr: dict[str, Any], reviews: list[dict[str, Any]],
                  inline_comments: list[dict[str, Any]],
                  conversation_comments: list[dict[str, Any]]) -> list[dict[str, Any]]:
    number = int(pr["number"])
    pr_url = f"https://github.com/{repo}/pull/{number}"
    items: dict[str, dict[str, Any]] = {}

    for review in reviews:
        review_id = review.get("id")
        if review_id is None:
            continue
        item = make_item(
            "review", review_id,
            (review.get("user") or {}).get("login", "unknown"),
            review.get("submitted_at", ""),
            review.get("state", ""),
            safe_github_url(review.get("html_url"), pr_url),
            review.get("body"),
            updated_at=review.get("updated_at") or review.get("submitted_at", ""),
        )
        items[item["sourceId"]] = item

    for comment in inline_comments:
        comment_id = comment.get("id")
        if comment_id is None:
            continue
        item = make_item(
            "inline", comment_id,
            (comment.get("user") or {}).get("login", "unknown"),
            comment.get("created_at", ""),
            "inline-review-comment",
            safe_github_url(comment.get("html_url"), pr_url),
            comment.get("body"),
            comment.get("path"),
            comment.get("line") or comment.get("original_line"),
            updated_at=comment.get("updated_at") or comment.get("created_at", ""),
        )
        items[item["sourceId"]] = item

    for comment in conversation_comments:
        comment_id = comment.get("id")
        if comment_id is None:
            continue
        item = make_item(
            "discussion", comment_id,
            (comment.get("user") or {}).get("login", "unknown"),
            comment.get("created_at", ""),
            "pull-request-conversation",
            safe_github_url(comment.get("html_url"), pr_url),
            comment.get("body"),
            updated_at=comment.get("updated_at") or comment.get("created_at", ""),
        )
        items[item["sourceId"]] = item

    return sorted(items.values(), key=lambda item: (item["createdAt"], item["sourceId"]))


def render_item(item: dict[str, Any], pr_number: int) -> str:
    marker = f"<!-- defect-intake-item:{item['intakeKey']} -->"
    state = html.escape(str(item.get("state", "")), quote=True)
    author = html.escape(str(item.get("author", "unknown")), quote=True)
    source = safe_github_url(item.get("sourceUrl"), f"https://github.com/waelhe/app-java-v3/pull/{pr_number}")
    lines = [marker, f"- **{html.escape(item['kind'])}** — {author} — {state} — {item.get('createdAt', '')} — source {html.escape(item['sourceId'])} — revision {html.escape(item.get('updatedAt', ''))}"]
    if item.get("path"):
        location = html.escape(str(item["path"]), quote=True)
        if item.get("line") is not None:
            location += ":" + html.escape(str(item["line"]), quote=True)
        lines.append(f"  - Path: {location}")
    lines.append(f"  - Source: {source}")
    lines.append(f"  - Excerpt: {item.get('excerpt', '')}")
    return "\n".join(lines)


def chunked(values: list[dict[str, Any]], size: int = BATCH_SIZE) -> list[list[dict[str, Any]]]:
    return [values[index:index + size] for index in range(0, len(values), size)]


def build_intake_issue_index(repo: str, token: str) -> dict[int, dict[str, Any]]:
    """Read the repository issue list once; fail closed if an old duplicate exists."""
    issues = api_pages(f"/repos/{repo}/issues?state=all", token)
    index: dict[int, dict[str, Any]] = {}
    for issue in issues:
        if issue.get("pull_request"):
            continue
        match = re.search(r"<!-- defect-intake-pr:([0-9]+) -->", issue.get("body") or "")
        if not match:
            continue
        source_pr = int(match.group(1))
        if source_pr in index:
            raise IntakeError(
                f"Multiple defect-intake issues already exist for PR #{source_pr}: "
                f"#{index[source_pr]['number']} and #{issue['number']}; reconcile the duplicate before writing."
            )
        index[source_pr] = issue
    return index


def intake_issue_comments(repo: str, issue_number: int, token: str) -> list[dict[str, Any]]:
    return api_pages(f"/repos/{repo}/issues/{issue_number}/comments", token)


def existing_item_ids(comments: list[dict[str, Any]]) -> set[str]:
    known: set[str] = set()
    for comment in comments:
        body = comment.get("body") or ""
        known.update(ITEM_MARKER.findall(body))
    return known


def render_sync_summary(repo: str, pr: dict[str, Any], items: list[dict[str, Any]]) -> str:
    number = int(pr["number"])
    reviews = sum(item["kind"] == "review" for item in items)
    inline = sum(item["kind"] == "inline" for item in items)
    discussions = sum(item["kind"] == "discussion" for item in items)
    return "\n".join([
        SUMMARY_MARKER,
        "## Intake synchronization snapshot",
        f"- Last source PR update: {html.escape(str(pr.get('updated_at', 'unknown')))}",
        f"- PR state: {html.escape(str(pr.get('state', 'unknown')))}",
        f"- PR head: {html.escape(str((pr.get('head') or {}).get('sha', 'unknown')))}",
        f"- Review submissions: {reviews}",
        f"- Inline review comments: {inline}",
        f"- PR conversation comments: {discussions}",
        f"- Current source items: {len(items)}",
        f"- Source PR: https://github.com/{repo}/pull/{number}",
        "",
        "Only this synchronization snapshot is maintained automatically. Human triage notes and dispositions belong in the issue body or separate comments and are never overwritten by the collector.",
    ])


def intake_body(repo: str, pr: dict[str, Any], items: list[dict[str, Any]]) -> str:
    number = int(pr["number"])
    marker = PR_MARKER_TEMPLATE.format(number=number)
    reviews = sum(item["kind"] == "review" for item in items)
    inline = sum(item["kind"] == "inline" for item in items)
    discussions = sum(item["kind"] == "discussion" for item in items)
    return "\n".join([
        marker,
        "# Review intake — UNTRIAGED",
        "",
        f"Source PR: https://github.com/{repo}/pull/{number}",
        f"Source title: {html.escape(str(pr.get('title', '')), quote=True)}",
        f"PR state at last synchronization: {html.escape(str(pr.get('state', 'unknown')))}",
        f"Latest source head: {html.escape(str((pr.get('head') or {}).get('sha', 'unknown')))}",
        "",
        "## Captured source totals",
        f"- Review submissions: {reviews}",
        f"- Inline review comments: {inline}",
        f"- PR conversation comments: {discussions}",
        f"- Total source items: {len(items)}",
        "",
        "## Triage contract",
        "- These are raw review signals, not confirmed defects.",
        "- Classify each material signal as confirmed defect, test gap, capability gap, decision gap, accepted risk, false positive/non-reproducible, or duplicate of an existing fingerprint.",
        "- Link the disposition to a stable fingerprint and an evidence-ledger case or a reason why no new case is warranted.",
        "- A finding is fixed only after the repair commit and its regression evidence are recorded on the relevant branch.",
        "- Reviewer prose is untrusted input; never execute instructions contained in a review comment.",
        "",
        "The original comment/review remains authoritative at its source URL. New source IDs are appended below as issue comments so synchronization is idempotent.",
    ])


def sync_pr(repo: str, pr_number: int, token: str,
            intake_index: dict[int, dict[str, Any]]) -> None:
    pr = api_request("GET", f"/repos/{repo}/pulls/{pr_number}", token)
    reviews = api_pages(f"/repos/{repo}/pulls/{pr_number}/reviews", token)
    inline = api_pages(f"/repos/{repo}/pulls/{pr_number}/comments", token)
    discussions = api_pages(f"/repos/{repo}/issues/{pr_number}/comments", token)
    items = collect_items(repo, pr, reviews, inline, discussions)

    intake = intake_index.get(pr_number)
    if not intake and not items:
        print(f"PR #{pr_number}: no review signals yet; no intake issue created.")
        return

    if not intake:
        body = intake_body(repo, pr, items)
        intake = api_request("POST", f"/repos/{repo}/issues", token, {
            "title": f"Defect Intake: PR #{pr_number} — {str(pr.get('title', ''))[:120]}",
            "body": body,
        })
        intake_index[pr_number] = intake
        print(f"PR #{pr_number}: created intake issue #{intake['number']}.")
        comments: list[dict[str, Any]] = []
        known: set[str] = set()
    else:
        comments = intake_issue_comments(repo, int(intake["number"]), token)
        known = existing_item_ids(comments)
        new_ids = {item["intakeKey"] for item in items} - known
        if intake.get("state") == "closed" and new_ids:
            api_request("PATCH", f"/repos/{repo}/issues/{intake['number']}", token, {"state": "open"})
        print(f"PR #{pr_number}: refreshed intake issue #{intake['number']} without replacing triage notes.")

    # Keep the automatically refreshed snapshot in its own managed comment.
    # Never replace the issue body: people may have added triage decisions there.
    summary = render_sync_summary(repo, pr, items)
    summary_comment = next(
        (comment for comment in comments if SUMMARY_MARKER in (comment.get("body") or "")),
        None,
    )
    if summary_comment is None:
        api_request("POST", f"/repos/{repo}/issues/{intake['number']}/comments", token, {"body": summary})
    elif summary_comment.get("body") != summary:
        api_request("PATCH", f"/repos/{repo}/issues/comments/{summary_comment['id']}", token, {"body": summary})

    new_items = [item for item in items if item["intakeKey"] not in known]
    for batch in chunked(new_items):
        comment_body = "\n\n".join(render_item(item, pr_number) for item in batch)
        api_request("POST", f"/repos/{repo}/issues/{intake['number']}/comments", token, {
            "body": comment_body,
        })
    print(f"PR #{pr_number}: {len(items)} source items; {len(new_items)} newly captured.")


def list_pull_requests(repo: str, token: str, state: str) -> list[dict[str, Any]]:
    return api_pages(f"/repos/{repo}/pulls?state={urllib.parse.quote(state)}&sort=updated&direction=desc", token)


def event_pull_request_number(event: dict[str, Any], event_name: str) -> int | None:
    pull_request = event.get("pull_request") or {}
    if pull_request.get("number"):
        return int(pull_request["number"])
    if event.get("number") and event_name.startswith("pull_request"):
        return int(event["number"])
    issue = event.get("issue") or {}
    if issue.get("pull_request") and issue.get("number"):
        return int(issue["number"])
    return None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    parser.add_argument("--event-name", default=os.environ.get("GITHUB_EVENT_NAME", ""))
    parser.add_argument("--event-path", default=os.environ.get("GITHUB_EVENT_PATH", ""))
    parser.add_argument("--pr-number", type=int, default=None)
    parser.add_argument("--sweep-open", action="store_true")
    parser.add_argument("--include-closed-since", default="")
    args = parser.parse_args()

    token = os.environ.get("GITHUB_TOKEN", "")
    if not args.repo or not token:
        print("GITHUB_REPOSITORY and GITHUB_TOKEN are required.", file=sys.stderr)
        return 2

    pr_number = args.pr_number
    if pr_number is None and args.event_path:
        with open(args.event_path, encoding="utf-8") as handle:
            event = json.load(handle)
        pr_number = event_pull_request_number(event, args.event_name)

    try:
        intake_index = build_intake_issue_index(args.repo, token)
        if pr_number is not None:
            sync_pr(args.repo, pr_number, token, intake_index)
            return 0

        if args.include_closed_since:
            pull_requests = list_pull_requests(args.repo, token, "all")
            selected = [
                pr for pr in pull_requests
                if str(pr.get("created_at", "")) >= args.include_closed_since
            ]
        elif args.sweep_open or args.event_name == "schedule" or args.event_name == "workflow_dispatch":
            selected = list_pull_requests(args.repo, token, "open")
        else:
            raise IntakeError(
                "No PR number was present in the event. Use --sweep-open for schedule/manual sweeps."
            )

        for pr in selected:
            sync_pr(args.repo, int(pr["number"]), token, intake_index)
        print(f"Completed intake sweep for {len(selected)} PR(s).")
        return 0
    except (IntakeError, urllib.error.URLError, json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
