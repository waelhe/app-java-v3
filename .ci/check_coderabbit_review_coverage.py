#!/usr/bin/env python3
"""Fail-closed verifier for CodeRabbit reviews split into <=100-file shards.

The final candidate is compared using complete Git trees. Each shard must have
a current CodeRabbit review, an explicit processed-file list, the exact same
base SHA, and identical before/after blobs for every covered path. Any uncovered
path needs a named, independently verifiable alternative check or review.
"""
from __future__ import annotations
import argparse
import base64
import fnmatch
import json
import os
import re
import sys
import urllib.error
import urllib.parse
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
        return self.get_from(self.root, path)

    def workflow_run(self, details_url):
        parsed = urllib.parse.urlparse(details_url or "")
        repository = self.root.removeprefix(f"{API}/repos/")
        prefix = f"/{repository}/actions/runs/"
        match = re.fullmatch(re.escape(prefix) + r"(\d+)(?:/job/\d+)?/?", parsed.path)
        need(parsed.scheme == "https" and parsed.netloc == "github.com" and match is not None,
             "check details_url does not identify a workflow run in the base repository")
        return self.get(f"/actions/runs/{match.group(1)}")

    def file_json(self, path, ref, source_repo=None, max_bytes=250_000):
        safe_path = path.strip("/")
        need(safe_path and not path.startswith("/") and "\\" not in safe_path
             and all(part not in {"", ".", ".."} for part in safe_path.split("/")),
             "manifest path must be a safe repository-relative path")
        need(re.fullmatch(r"[0-9a-f]{40}", ref) is not None, "manifest ref must be a full commit SHA")
        encoded_path = urllib.parse.quote(safe_path, safe="/")
        encoded_ref = urllib.parse.quote(ref, safe="")
        root = self.repo_root(source_repo)
        response = self.get_from(root, f"/contents/{encoded_path}?ref={encoded_ref}")
        need(response.get("type") == "file", f"manifest path is not a file: {safe_path}")
        need(response.get("encoding") == "base64", "GitHub did not return the manifest as base64")
        need(isinstance(response.get("size"), int) and response["size"] <= max_bytes,
             f"manifest exceeds {max_bytes} bytes")
        payload = "".join((response.get("content") or "").split())
        try:
            raw = base64.b64decode(payload, validate=True)
            need(len(raw) <= max_bytes, f"manifest exceeds {max_bytes} bytes")
            return json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise GateError(f"candidate manifest is not valid UTF-8 JSON: {exc}") from exc

    def get_from(self, root, path):
        request = urllib.request.Request(
            root + path,
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

    def repo_root(self, repository=None):
        if repository is None:
            return self.root
        need(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) is not None,
             "invalid GitHub repository selector")
        return f"{API}/repos/{repository}"

    def pull(self, number):
        return self.get(f"/pulls/{number}")

    def files(self, number):
        return self.pages(f"/pulls/{number}/files")

    def reviews(self, number):
        return self.pages(f"/pulls/{number}/reviews")

    def tree(self, sha, source_repo=None):
        root = self.repo_root(source_repo)
        response = self.get_from(root, f"/git/trees/{sha}?recursive=1")
        need(not response.get("truncated"), f"Git tree truncated for {sha}")
        return {
            item["path"]: (item["sha"], item["mode"], item["type"])
            for item in response.get("tree", [])
            if item.get("type") in {"blob", "commit"}
        }

    def checks(self, sha, source_repo=None):
        root = self.repo_root(source_repo)
        result, page = [], 1
        while True:
            response = self.get_from(root, f"/commits/{sha}/check-runs?per_page=100&page={page}")
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
    seen = set()
    for shard in shards:
        need(isinstance(shard, dict), "review shard must be an object")
        number, review_id = shard.get("prNumber"), shard.get("reviewId")
        need(isinstance(number, int) and number > 0, "review shard needs a positive prNumber")
        need(isinstance(review_id, int) and review_id > 0, "review shard needs a positive reviewId")
        need(number not in seen, f"PR #{number} appears more than once")
        seen.add(number)
        need(shard.get("role") == "review",
             "reviewShards must contain independent review PRs only; candidate review is discovered from the candidate head")
    for item in alternatives:
        need(isinstance(item, dict), "alternative coverage must be an object")
        paths = item.get("paths")
        need(isinstance(paths, list) and paths and all(isinstance(path, str) and path for path in paths),
             "alternative evidence requires non-empty paths")
        need(len(paths) == len(set(paths)), "alternative evidence contains duplicate paths")
        need(item.get("reason") and item.get("evidenceUrl"), "alternative evidence needs reason and evidenceUrl")
        need(item.get("kind") in {"check", "manual-review"},
             f"unsupported alternative evidence kind: {item.get('kind')!r}")
        if item["kind"] == "check":
            need(item.get("checkName"), "check evidence requires checkName")
        else:
            need(item.get("reviewerLogin") and isinstance(item.get("reviewId"), int),
                 "manual review evidence requires reviewerLogin and reviewId")


def validate_alternative_config(config):
    need(config.get("schemaVersion") == 1, "trusted alternative-check config schemaVersion must be 1")
    checks = config.get("checks")
    need(isinstance(checks, dict), "trusted alternative-check config requires checks object")
    for name, rule in checks.items():
        need(isinstance(name, str) and name, "trusted check name must be non-empty")
        need(isinstance(rule, dict), f"trusted rule for {name!r} must be an object")
        apps = rule.get("appSlugs")
        patterns = rule.get("pathPatterns")
        workflow_path = rule.get("workflowPath")
        events = rule.get("events")
        need(isinstance(apps, list) and apps and all(isinstance(x, str) and x for x in apps),
             f"trusted rule for {name!r} requires appSlugs")
        need(isinstance(patterns, list) and patterns and all(isinstance(x, str) and x for x in patterns),
             f"trusted rule for {name!r} requires pathPatterns")
        need(isinstance(workflow_path, str) and workflow_path.startswith(".github/workflows/")
             and all(part not in {"", ".", ".."} for part in workflow_path.split("/")),
             f"trusted rule for {name!r} requires a safe workflowPath")
        need(isinstance(events, list) and events and all(isinstance(x, str) and x for x in events),
             f"trusted rule for {name!r} requires events")


def verify_alternative(item, candidate, candidate_reviews, client, alternative_config):
    paths = set(item["paths"])
    head = candidate["head"]["sha"]
    if item["kind"] == "manual-review":
        reviewer = item["reviewerLogin"]
        author = (candidate.get("user") or {}).get("login", "")
        need(reviewer not in {author, BOT}, "manual reviewer must be independent")
        review = exact_review(candidate_reviews, item["reviewId"], head, reviewer, {"APPROVED"})
        need(review.get("author_association") in {"OWNER", "MEMBER", "COLLABORATOR"},
             "manual reviewer must be a repository owner, member, or collaborator")
        latest = latest_review(candidate_reviews, head, reviewer)
        need(latest and latest.get("id") == review["id"],
             "manual review is not latest on candidate head")
        need(item["evidenceUrl"] == review.get("html_url") and bool(review.get("html_url")),
             "manual review evidence URL does not identify the recorded review")
    else:
        check_name = item["checkName"]
        rule = alternative_config.get("checks", {}).get(check_name)
        need(rule is not None, f"check {check_name!r} is not allowlisted by trusted base configuration")
        allowed_apps = set(rule["appSlugs"])
        patterns = rule["pathPatterns"]
        for path in paths:
            need(any(fnmatch.fnmatchcase(path, pattern) for pattern in patterns),
                 f"path {path!r} is outside trusted path patterns for {check_name!r}")
        workflow_path = rule["workflowPath"]
        base_tree = client.tree(candidate["base"]["sha"])
        candidate_head_repo = (candidate.get("head", {}).get("repo") or {}).get("full_name")
        need(bool(candidate_head_repo), "candidate head repository is unavailable for workflow verification")
        head_tree = client.tree(head, candidate_head_repo)
        trusted_workflow = base_tree.get(workflow_path)
        need(trusted_workflow is not None and head_tree.get(workflow_path) == trusted_workflow,
             "candidate changed the trusted workflow file used for alternative evidence")
        checks = [run for run in client.checks(head)
                  if run.get("name") == check_name
                  and run.get("head_sha") == head
                  and (run.get("app") or {}).get("slug") in allowed_apps]
        need(checks, f"no candidate-head check {check_name!r} from a trusted GitHub App")
        latest = max(checks, key=lambda run: (run.get("completed_at") or "", run.get("started_at") or ""))
        need(latest.get("status") == "completed" and latest.get("conclusion") == "success",
             f"latest trusted {check_name} run is not successful")
        workflow_run = client.workflow_run(latest.get("details_url"))
        need(workflow_run.get("path") == workflow_path,
             "check run was not produced by the trusted workflow")
        need(workflow_run.get("event") in rule["events"],
             "check run was produced by an unexpected workflow event")
        need(workflow_run.get("head_sha") == head,
             "trusted workflow run does not match the candidate head")
        need(latest.get("html_url") == item["evidenceUrl"],
             "check evidence URL is not the latest trusted run")
    return paths


def verify_path_content(path, candidate_changes, shard_base, shard_head, candidate_base, candidate_head):
    need(path in candidate_changes, f"reviewed path is outside candidate diff: {path}")
    need(shard_base.get(path) == candidate_base.get(path), f"base content changed after review: {path}")
    need(shard_head.get(path) == candidate_head.get(path), f"head content changed after review: {path}")


def ensure_exact_coverage(candidate_changes, reviewed, alternatives):
    reviewed_paths = set(reviewed)
    overlap = reviewed_paths & alternatives
    need(not overlap, f"paths have both CodeRabbit and alternative evidence: {sorted(overlap)}")
    covered = reviewed_paths | alternatives
    missing = set(candidate_changes) - covered
    extra = covered - set(candidate_changes)
    need(not extra, f"evidence includes paths outside candidate: {sorted(extra)}")
    need(not missing, f"changed files have no review evidence: {sorted(missing)}")


def run(repo, number, manifest_path, alternative_config, trusted_base_sha, token):
    client = GitHub(repo, token)
    candidate = client.pull(number)
    need(candidate.get("state") == "open", "candidate PR must remain open during coverage verification")
    need(candidate.get("base", {}).get("ref") == "main", "coverage gate only applies to main-targeting PRs")
    base_sha, head_sha = candidate["base"]["sha"], candidate["head"]["sha"]
    candidate_head_repo = (candidate.get("head", {}).get("repo") or {}).get("full_name")
    need(bool(candidate_head_repo), "candidate head repository is unavailable")
    need(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", candidate_head_repo) is not None,
         "candidate head repository name is invalid")
    need(base_sha == trusted_base_sha, f"PR base {base_sha} differs from trusted workflow base {trusted_base_sha}")
    base_tree, head_tree = client.tree(base_sha), client.tree(head_sha, candidate_head_repo)
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
    need(direct <= set(candidate_changes),
         f"candidate CodeRabbit review lists paths outside its diff: {sorted(direct-set(candidate_changes))}")
    if len(candidate_changes) <= MAX_FILES and direct == set(candidate_changes):
        print(f"PASS — all {len(candidate_changes)} changed paths are listed in CodeRabbit review {review['id']}.")
        return

    # The candidate manifest is fetched as inert JSON from the exact head SHA.
    # No file or executable from the untrusted candidate checkout is used.
    manifest = client.file_json(manifest_path, head_sha, candidate_head_repo)
    validate_manifest(manifest)
    validate_alternative_config(alternative_config)

    reviewed, alternatives = {}, set()
    for shard in manifest["reviewShards"]:
        number_for_shard = shard["prNumber"]
        need(number_for_shard != number,
             "candidate PR review is discovered directly from the current head and must not be listed as a shard")
        pull = client.pull(number_for_shard)
        shard_head = pull["head"]["sha"]
        shard_head_repo = (pull.get("head", {}).get("repo") or {}).get("full_name")
        need(bool(shard_head_repo), f"shard PR #{number_for_shard} head repository is unavailable")
        need(pull.get("state") == "open", f"shard PR #{number_for_shard} must remain open during verification")
        need(pull["base"]["sha"] == base_sha and pull["base"]["ref"] == "main",
             f"shard PR #{number_for_shard} must use exact candidate base {base_sha}")
        need(shard.get("role") == "review", "reviewShards entries must declare role='review'")
        reviews = client.reviews(number_for_shard)
        r = exact_review(reviews, shard["reviewId"], shard_head, BOT, GOOD_STATES)
        newest_shard = latest_review(reviews, shard_head, BOT)
        need(newest_shard and newest_shard["id"] == r["id"], f"shard PR #{number_for_shard} review is stale")
        selected_files = processed_files(r.get("body", ""))
        need(0 < len(selected_files) <= MAX_FILES, "a CodeRabbit shard exceeded 100 files")

        records = client.files(number_for_shard)
        names, expanded, by_name = fileset(records)
        need(0 < len(records) <= MAX_FILES, f"shard PR #{number_for_shard} exceeds 100 files")
        need(set(selected_files) == names, f"CodeRabbit did not process every file in shard PR #{number_for_shard}")
        shard_base, shard_head_tree = base_tree, client.tree(shard_head, shard_head_repo)
        need(set(tree_changes(shard_base, shard_head_tree)) == expanded,
             f"shard PR #{number_for_shard} REST file list differs from full Git-tree diff")

        _, _, by_name = fileset(records)
        paths = set()
        for path in selected_files:
            paths.add(path)
            if path in by_name and by_name[path].get("previous_filename"):
                paths.add(by_name[path]["previous_filename"])
        for path in paths:
            need(path not in reviewed, f"path covered by more than one CodeRabbit shard: {path}")
            verify_path_content(path, candidate_changes, shard_base, shard_head_tree,
                                base_tree, head_tree)
            reviewed[path] = f"PR #{number_for_shard} review {r['id']}"

    for item in manifest.get("alternativeCoverage", []):
        paths = verify_alternative(item, candidate, candidate_reviews, client, alternative_config)
        need(not (paths & (set(reviewed) | alternatives)), "alternative evidence overlaps existing coverage")
        need(paths <= set(candidate_changes), f"alternative evidence outside candidate: {sorted(paths-set(candidate_changes))}")
        alternatives.update(paths)

    ensure_exact_coverage(candidate_changes, reviewed, alternatives)
    print(f"PASS — candidate {head_sha}; paths={len(candidate_changes)}, CodeRabbit={len(reviewed)}, "
          f"alternatives={len(alternatives)}, review shards={len(manifest['reviewShards'])}.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default=os.getenv("GITHUB_REPOSITORY"))
    parser.add_argument("--pr-number", type=int, required=True)
    parser.add_argument("--manifest-path", required=True)
    parser.add_argument("--alternative-config", required=True)
    parser.add_argument("--trusted-base-sha", required=True)
    parser.add_argument("--max-files-per-review", type=int, default=MAX_FILES)
    args = parser.parse_args()
    if not args.repository:
        parser.error("--repository or GITHUB_REPOSITORY is required")
    if args.max_files_per_review != MAX_FILES:
        parser.error("this repository enforces exactly 100 files per CodeRabbit review")
    token = os.getenv("GITHUB_TOKEN") or os.getenv("GH_TOKEN") or ""
    try:
        with open(args.alternative_config, encoding="utf-8") as handle:
            alternative_config = json.load(handle)
        validate_alternative_config(alternative_config)
        run(args.repository, args.pr_number, args.manifest_path,
            alternative_config, args.trusted_base_sha, token)
    except (GateError, OSError, json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
