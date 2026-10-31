#!/usr/bin/env python3
"""Fail-closed verifier for CodeRabbit reviews split into <=100-file shards.

The final candidate is compared using complete Git trees. Each shard must have
a current CodeRabbit review, an explicit processed-file list, the exact same
base SHA, and identical before/after blobs for every covered path. Any uncovered
path needs a named, independently verifiable alternative check or review.
"""
from __future__ import annotations
import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"
BOT = "coderabbitai[bot]"
GOOD_STATES = {"APPROVED", "COMMENTED"}
MAX_FILES = 100


class GateError(RuntimeError):
    pass


def need(condition, message):
    if not condition:
        raise GateError(message)


class GitHub:
    def __init__(self, repo, token):
        need(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo) is not None, "invalid repository")
        need(bool(token), "GITHUB_TOKEN is required")
        self.root = f"{API}/repos/{repo}"
        self.token = token

    def get(self, path):
        request = urllib.request.Request(
            self.root + path,
            headers={"Accept": "application/vnd.github+json",
                     "Authorization": f"Bearer {self.token}",
                     "X-GitHub-Api-Version": "2022-11-28",
                     "User-Agent": "saleor-review-coverage-gate"},
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:800]
            raise GateError(f"GitHub API {path}: HTTP {exc.code}: {detail}") from exc
        except (urllib.error.URLError, TimeoutError) as exc:
            raise GateError(f"GitHub API {path}: {exc}") from exc

    def pages(self, path):
        result, page = [], 1
        while True:
            separator = "&" if "?" in path else "?"
            batch = self.get(f"{path}{separator}per_page=100&page={page}")
            need(isinstance(batch, list), f"invalid paginated response for {path}")
            result.extend(batch)
            if len(batch) < 100:
                return result
            page += 1
            need(page <= 100, f"pagination limit reached for {path}")

    def pull(self, number):
        return self.get(f"/pulls/{number}")

    def files(self, number):
        return self.pages(f"/pulls/{number}/files")

    def reviews(self, number):
        return self.pages(f"/pulls/{number}/reviews")

    def tree(self, sha):
        response = self.get(f"/git/trees/{sha}?recursive=1")
        need(not response.get("truncated"), f"Git tree truncated for {sha}")
        return {
            item["path"]: (item["sha"], item["mode"], item["type"])
            for item in response.get("tree", [])
            if item.get("type") in {"blob", "commit"}
        }

    def checks(self, sha):
        result, page = [], 1
        while True:
            response = self.get(f"/commits/{sha}/check-runs?per_page=100&page={page}")
            batch = response.get("check_runs", [])
            need(isinstance(batch, list), f"invalid check-run response for {sha}")
            result.extend(batch)
            if len(batch) < 100:
                return result
            page += 1
            need(page <= 100, f"check-run pagination limit reached for {sha}")


def tree_changes(base, head):
    return {path: (base.get(path), head.get(path))
            for path in sorted(set(base) | set(head)) if base.get(path) != head.get(path)}


def fileset(records):
    names, expanded, by_name = set(), set(), {}
    for record in records:
        name = record.get("filename", "")
        need(name and name not in names, f"empty or duplicate PR file path: {name!r}")
        names.add(name)
        expanded.add(name)
        previous = record.get("previous_filename")
        if previous:
            expanded.add(previous)
        by_name[name] = record
    return names, expanded, by_name


def processed_files(body):
    match = re.search(
        r"Files selected for processing:\s*(\d+).*?</summary>(.*?)(?:</details>|$)",
        body or "", re.I | re.S,
    )
    need(match is not None, "CodeRabbit omitted its explicit processed-file list")
    expected = int(match.group(1))
    marker = chr(96)
    result = []
    for line in match.group(2).splitlines():
        found = re.match(r"^\s*[*-]\s+" + re.escape(marker) + r"([^" + re.escape(marker) + r"]+)" +
                         re.escape(marker) + r"\s*$", line)
        if found:
            result.append(found.group(1).strip())
    need(len(result) == expected, f"CodeRabbit declared {expected} files but listed {len(result)}")
    need(len(result) == len(set(result)), "CodeRabbit processed-file list contains duplicate paths")
    return result


def latest_review(reviews, sha, login):
    candidates = [item for item in reviews
                  if (item.get("user") or {}).get("login") == login
                  and item.get("commit_id") == sha
                  and item.get("state") != "PENDING"]
    return max(candidates, key=lambda item: item.get("submitted_at") or "") if candidates else None


def exact_review(reviews, review_id, sha, login, states):
    found = [item for item in reviews if item.get("id") == review_id]
    need(len(found) == 1, f"review {review_id} not found exactly once")
    item = found[0]
    need((item.get("user") or {}).get("login") == login, f"review {review_id} has wrong author")
    need(item.get("commit_id") == sha, f"review {review_id} is stale; expected head {sha}")
    need(item.get("state") in states, f"review {review_id} has unacceptable state {item.get('state')!r}")
    need(item.get("submitted_at"), f"review {review_id} has no submission timestamp")
    return item


def validate_manifest(data):
    need(data.get("schemaVersion") == 1, "manifest schemaVersion must be 1")
    need(data.get("maxFilesPerReview") == MAX_FILES, "manifest maxFilesPerReview must be 100")
    shards = data.get("reviewShards")
    alternatives = data.get("alternativeCoverage", [])
    need(isinstance(shards, list) and shards, "reviewShards must be a non-empty array")
    need(isinstance(alternatives, list), "alternativeCoverage must be an array")
    seen, candidate_count = set(), 0
    for shard in shards:
        need(isinstance(shard, dict), "review shard must be an object")
        number, review_id = shard.get("prNumber"), shard.get("reviewId")
        need(isinstance(number, int) and number > 0, "review shard needs a positive prNumber")
        need(isinstance(review_id, int) and review_id > 0, "review shard needs a positive reviewId")
        need(number not in seen, f"PR #{number} appears more than once")
        seen.add(number)
        candidate_count += shard.get("role") == "candidate"
    need(candidate_count == 1, "exactly one shard must have role='candidate'")
    for item in alternatives:
        need(isinstance(item, dict), "alternative coverage must be an object")
        paths = item.get("paths")
        need(isinstance(paths, list) and paths and all(isinstance(path, str) and path for path in paths),
             "alternative evidence requires non-empty paths")
        need(item.get("reason") and item.get("evidenceUrl"), "alternative evidence needs reason and evidenceUrl")
        need(item.get("kind") in {"check", "manual-review"},
             f"unsupported alternative evidence kind: {item.get('kind')!r}")
        if item["kind"] == "check":
            need(item.get("checkName"), "check evidence requires checkName")
        else:
            need(item.get("reviewerLogin") and isinstance(item.get("reviewId"), int),
                 "manual review evidence requires reviewerLogin and reviewId")


def verify_alternative(item, candidate, candidate_reviews, client):
    paths = set(item["paths"])
    head = candidate["head"]["sha"]
    if item["kind"] == "manual-review":
        reviewer = item["reviewerLogin"]
        author = (candidate.get("user") or {}).get("login", "")
        need(reviewer not in {author, BOT}, "manual reviewer must be independent")
        review = exact_review(candidate_reviews, item["reviewId"], head, reviewer, {"APPROVED"})
        latest = latest_review(candidate_reviews, head, reviewer)
        need(latest and latest.get("id") == review["id"], "manual review is not latest on candidate head")
        need(item["evidenceUrl"] in {review.get("html_url"), candidate.get("html_url")},
             "manual review evidence URL does not identify the recorded review")
    else:
        checks = [run for run in client.checks(head)
                  if run.get("name") == item["checkName"] and run.get("head_sha") == head]
        need(checks, f"no candidate-head check named {item['checkName']!r}")
        latest = max(checks, key=lambda run: (run.get("completed_at") or "", run.get("started_at") or ""))
        need(latest.get("status") == "completed" and latest.get("conclusion") == "success",
             f"latest {item['checkName']} run is not successful")
        need(latest.get("html_url") == item["evidenceUrl"], "check evidence URL is not the latest run")
    return paths


def run(repo, number, manifest_path, token):
    client = GitHub(repo, token)
    candidate = client.pull(number)
    need(candidate.get("state") == "open", "candidate PR must remain open during coverage verification")
    need(candidate.get("base", {}).get("ref") == "main", "coverage gate only applies to main-targeting PRs")
    base_sha, head_sha = candidate["base"]["sha"], candidate["head"]["sha"]
    base_tree, head_tree = client.tree(base_sha), client.tree(head_sha)
    candidate_changes = tree_changes(base_tree, head_tree)
    need(candidate_changes, "candidate has no changed paths")

    candidate_records = client.files(number)
    _, candidate_expanded, candidate_by_name = fileset(candidate_records)
    need(candidate_expanded == set(candidate_changes), "candidate PR file list differs from complete Git-tree diff")
    candidate_reviews = client.reviews(number)
    newest = latest_review(candidate_reviews, head_sha, BOT)
    need(newest is not None, f"no CodeRabbit review on current candidate head {head_sha}")
    review = exact_review(candidate_reviews, newest["id"], head_sha, BOT, GOOD_STATES)
    selected = processed_files(review.get("body", ""))
    need(0 < len(selected) <= MAX_FILES, "candidate CodeRabbit review exceeds the 100-file cap")

    direct = set()
    for path in selected:
        direct.add(path)
        record = candidate_by_name.get(path)
        if record and record.get("previous_filename"):
            direct.add(record["previous_filename"])
    if len(candidate_changes) <= MAX_FILES and direct == set(candidate_changes):
        print(f"PASS — all {len(candidate_changes)} changed paths are listed in CodeRabbit review {review['id']}.")
        return

    try:
        with open(manifest_path, encoding="utf-8") as handle:
            manifest = json.load(handle)
    except FileNotFoundError as exc:
        uncovered = sorted(set(candidate_changes) - direct)
        raise GateError(f"coverage manifest is required; uncovered paths={uncovered}") from exc
    validate_manifest(manifest)

    reviewed, alternatives, candidate_shards = {}, set(), 0
    for shard in manifest["reviewShards"]:
        number_for_shard = shard["prNumber"]
        pull = client.pull(number_for_shard)
        shard_head = pull["head"]["sha"]
        need(pull["base"]["sha"] == base_sha and pull["base"]["ref"] == "main",
             f"shard PR #{number_for_shard} must use exact candidate base {base_sha}")
        reviews = candidate_reviews if number_for_shard == number else client.reviews(number_for_shard)
        r = exact_review(reviews, shard["reviewId"], shard_head, BOT, GOOD_STATES)
        newest_shard = latest_review(reviews, shard_head, BOT)
        need(newest_shard and newest_shard["id"] == r["id"], f"shard PR #{number_for_shard} review is stale")
        selected_files = processed_files(r.get("body", ""))
        need(0 < len(selected_files) <= MAX_FILES, "a CodeRabbit shard exceeded 100 files")

        if number_for_shard == number:
            candidate_shards += 1
            need(shard.get("role") == "candidate", "final PR shard must declare candidate role")
            records, shard_base, shard_head_tree = candidate_records, base_tree, head_tree
        else:
            need(shard.get("role") != "candidate", "only final PR may be the candidate shard")
            records = client.files(number_for_shard)
            names, expanded, by_name = fileset(records)
            need(0 < len(records) <= MAX_FILES, f"shard PR #{number_for_shard} exceeds 100 files")
            need(set(selected_files) == names, f"CodeRabbit did not process every file in shard PR #{number_for_shard}")
            shard_base, shard_head_tree = base_tree, client.tree(shard_head)
            need(set(tree_changes(shard_base, shard_head_tree)) == expanded,
                 f"shard PR #{number_for_shard} REST file list differs from full Git-tree diff")

        _, _, by_name = fileset(records)
        paths = set()
        for path in selected_files:
            paths.add(path)
            if path in by_name and by_name[path].get("previous_filename"):
                paths.add(by_name[path]["previous_filename"])
        for path in paths:
            need(path in candidate_changes, f"shard covers path outside final candidate: {path}")
            need(shard_base.get(path) == base_tree.get(path), f"base content changed after review: {path}")
            need(shard_head_tree.get(path) == head_tree.get(path), f"head content changed after review: {path}")
            need(path not in reviewed, f"path covered by more than one CodeRabbit shard: {path}")
            reviewed[path] = f"PR #{number_for_shard} review {r['id']}"

    need(candidate_shards == 1, "candidate PR must occur exactly once in reviewShards")
    for item in manifest.get("alternativeCoverage", []):
        paths = verify_alternative(item, candidate, candidate_reviews, client)
        need(not (paths & (set(reviewed) | alternatives)), "alternative evidence overlaps existing coverage")
        need(paths <= set(candidate_changes), f"alternative evidence outside candidate: {sorted(paths-set(candidate_changes))}")
        alternatives.update(paths)

    missing = set(candidate_changes) - set(reviewed) - alternatives
    extra = (set(reviewed) | alternatives) - set(candidate_changes)
    need(not extra, f"evidence includes paths outside candidate: {sorted(extra)}")
    need(not missing, f"changed files have no review evidence: {sorted(missing)}")
    need(not (set(reviewed) & alternatives), "path has both CodeRabbit and alternative evidence")
    print(f"PASS — candidate {head_sha}; paths={len(candidate_changes)}, CodeRabbit={len(reviewed)}, "
          f"alternatives={len(alternatives)}, review shards={len(manifest['reviewShards'])}.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default=os.getenv("GITHUB_REPOSITORY"))
    parser.add_argument("--pr-number", type=int, required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--max-files-per-review", type=int, default=MAX_FILES)
    args = parser.parse_args()
    if not args.repository:
        parser.error("--repository or GITHUB_REPOSITORY is required")
    if args.max_files_per_review != MAX_FILES:
        parser.error("this repository enforces exactly 100 files per CodeRabbit review")
    token = os.getenv("GITHUB_TOKEN") or os.getenv("GH_TOKEN") or ""
    try:
        run(args.repository, args.pr_number, args.manifest, token)
    except (GateError, OSError, json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
