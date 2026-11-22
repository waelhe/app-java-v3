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

    def merge_base(self, base_sha, head_sha, source_repo=None):
        """The merge-base of the live main tip and the candidate head.

        The coverage obligation is defined by the three-dot diff — the same
        basis as the REST files endpoint — so a main tip that moved after the
        PR was opened (an unrelated PR merged first, for example) can neither
        add nor remove candidate paths. The compare runs in the repository
        that holds both refs; a head unknown there fails closed.
        """
        root = self.repo_root(source_repo)
        response = self.get_from(root, f"/compare/{base_sha}...{head_sha}")
        # The Compare API returns the merge base as an object:
        # merge_base_commit.sha (verified live 2026-10-31; there is no
        # top-level merge_base_sha field).
        value = (response.get("merge_base_commit") or {}).get("sha")
        need(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{40}", value) is not None,
             "compare did not return a usable merge-base SHA")
        return value

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
    selected = []
    for line in match.group(2).splitlines():
        found = re.match(r"^\s*[*-]\s+" + re.escape(marker) + r"([^" + re.escape(marker) + r"]+)" +
                         re.escape(marker) + r"\s*$", line)
        if found:
            selected.append(found.group(1).strip())
    need(len(selected) == expected, f"CodeRabbit declared {expected} files but listed {len(selected)}")
    need(len(selected) == len(set(selected)), "CodeRabbit processed-file list contains duplicate paths")

    # A selected path that CodeRabbit explicitly skips as similar to a prior
    # change is not evidence that the current review inspected that path.
    # Subtract skipped paths before exact-coverage checks and fail closed on
    # malformed or contradictory skip declarations.
    #
    # Presence vs absence (CodeRabbit round 1 on this PR): a body that
    # carries the skipped-section HEADER with an unparseable count must
    # fail closed — treating a malformed declaration as "no skipped files"
    # would pass coverage the review never granted. Only a body with no
    # skipped-section header at all keeps the plain selected list.
    if not re.search(r"Files\s+skipped\s+from\s+review", body or "", re.I):
        return selected
    # The count token must be digits only (CodeRabbit round 2 on this PR):
    # `(\d+)` alone also accepts `0unknown` as `0` — a malformed declaration
    # that then silently passes as "zero skipped files". Anchoring the count
    # against the summary close (`:\s*(\d+)</summary>`) accepts only a bare
    # integer; anything else is a malformed declaration and fails closed.
    skipped_match = re.search(
        r"Files skipped from review[^:]*:\s*(\d+)\s*</summary>(.*?)(?:</details>|$)",
        body or "", re.I | re.S,
    )
    need(skipped_match is not None,
         "CodeRabbit skipped-file section is present but malformed (unparseable count or structure)")

    skipped_expected = int(skipped_match.group(1))
    skipped = []
    for line in skipped_match.group(2).splitlines():
        found = re.match(r"^\s*[*-]\s+(.+?)\s*$", line)
        if found:
            skipped.append(found.group(1).strip().strip(chr(96)))
    need(len(skipped) == skipped_expected,
         f"CodeRabbit declared {skipped_expected} skipped files but listed {len(skipped)}")
    need(len(skipped) == len(set(skipped)), "CodeRabbit skipped-file list contains duplicate paths")
    need(set(skipped).issubset(set(selected)),
         "CodeRabbit skipped-file list contains paths absent from the selected-file list")
    skipped_set = set(skipped)
    return [path for path in selected if path not in skipped_set]


def latest_review(reviews, sha, login):
    candidates = [item for item in reviews
                  if (item.get("user") or {}).get("login") == login
                  and item.get("commit_id") == sha
                  and item.get("state") != "PENDING"]
    # CodeRabbit round 13 (2026-11-17, assertive full review on head
    # 445dc368): a submitted_at TIE in the verdict selector resolves to the
    # LATER position in the response — the same tiebreaker declaring_review
    # already applies (round 12). Python's max() is stable and returns the
    # FIRST maximal element, so a tie between an earlier APPROVED and a
    # later CHANGES_REQUESTED/DISMISSED review on the same head would read
    # the APPROVED as "newest" and pass a head the chronologically newer
    # blocking review rejects. GitHub returns reviews in submission order,
    # so the later response position is the newer submission; the
    # (submitted_at, position) descending key selects it — fail-closed
    # either way, and consistent with the gate workflow's newest-review
    # verdict model.
    for _, item in sorted(enumerate(candidates),
                          key=lambda pair: (pair[1].get("submitted_at") or "", pair[0]),
                          reverse=True):
        return item
    return None


# The header is matched WITHOUT requiring a numeric count: a body whose
# header carries an unparseable count (e.g. "Files selected for processing:
# unknown") is a malformed declaration and must reach ``processed_files``,
# which fails closed — never be silently skipped in favour of an older
# declaration on the same head.
DECLARATION_HEADER = re.compile(r"Files selected for processing:", re.I)


def declares_processed_files(body):
    """Whether a review body opens a processed-file declaration section."""
    return DECLARATION_HEADER.search(body or "") is not None


def declaring_review(reviews, sha, login, states):
    """Newest review on this head that declares its processed-file list.

    CodeRabbit's ``request_changes_workflow`` posts its substantive review
    (state COMMENTED, body carrying ``Files selected for processing: N``) and
    then, once the findings are resolved, a SECOND empty-bodied APPROVED
    review. Taking the chronologically newest review as the coverage evidence
    therefore reads an empty body and fails with "CodeRabbit omitted its
    explicit processed-file list" even though the head is fully declared —
    measured live on PR #819 and PR #820 (2026-11-12): the only review on each
    head is ``APPROVED`` with an empty body, while the declaration sits on the
    paired substantive review.

    Coverage evidence is the declaration, not the chronology: the verdict
    state still belongs to the newest review (the gate workflow enforces it,
    and a CHANGES_REQUESTED review is never a good state here), and every
    candidate accepted by this function must already carry one of ``states``
    on this exact head. A body WITHOUT a declaration header is not evidence
    and is skipped; a body WITH the header that fails to parse is a malformed
    declaration and raises — it must never be papered over by falling back to
    an older review. Returns ``(review, selected_paths)`` or
    ``(None, None)``; the caller fails closed on ``None``.

    Decision (documented on PR #842's review): a ``CHANGES_REQUESTED``
    review is deliberately NOT accepted as coverage evidence even when a
    later empty-bodied APPROVED lands on the same head. The declaration
    describes the files as they stood when changes were requested; letting
    an approval that asserts nothing resurrect that declaration would
    weaken the fail-closed posture. No same-head CHANGES_REQUESTED-then-
    empty-APPROVED pair has been observed on this repository (measured on
    the #819/#820 review histories), and the escape if one ever appears is
    the proven one: a fresh review on the head.
    """
    candidates = [item for item in reviews
                  if (item.get("user") or {}).get("login") == login
                  and item.get("commit_id") == sha
                  and item.get("state") in states
                  and declares_processed_files(item.get("body", ""))]
    # CodeRabbit round 12 (2026-11-17T17:46Z, inline 4603504886): a
    # submitted_at TIE resolves to the LATER position in the response.
    # GitHub returns reviews chronologically, but Python's sort is stable —
    # reverse=True preserves the original order among equal keys, so a
    # tie between an older complete declaration and a newer malformed one
    # (both COMMENTED at the same submitted_at, then a later empty
    # approval) would return the OLDER declaration and PASS instead of
    # raising on the newest malformed one. The response position is the
    # tiebreaker: (submitted_at, position) descending selects the newest
    # submission the API carries, malformed or not — fail-closed either
    # way.
    for _, item in sorted(enumerate(candidates),
                          key=lambda pair: (pair[1].get("submitted_at") or "", pair[0]),
                          reverse=True):
        return item, processed_files(item.get("body", ""))
    return None, None


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


def coverage_obligation(candidate_changes, manifest_path):
    """Split the changed-path set into the coverage obligation and gate metadata.

    The manifest file is the coverage gate's own metadata: inert JSON, schema
    validated, never executed. It cannot be covered by a review of itself (its
    content lists the review IDs that would have to review it), so it is
    excluded from the coverage obligation explicitly and never silently.
    """
    excluded = sorted({manifest_path} & set(candidate_changes))
    return set(candidate_changes) - {manifest_path}, excluded


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
    base_tree = client.tree(client.merge_base(base_sha, head_sha, candidate_head_repo))
    head_tree = client.tree(head_sha, candidate_head_repo)
    candidate_changes = tree_changes(base_tree, head_tree)
    need(candidate_changes, "candidate has no changed paths")

    candidate_records = client.files(number)
    _, candidate_expanded, candidate_by_name = fileset(candidate_records)
    need(candidate_expanded == set(candidate_changes), "candidate PR file list differs from complete Git-tree diff")
    candidate_reviews = client.reviews(number)
    newest = latest_review(candidate_reviews, head_sha, BOT)
    direct = set()
    # The direct/oversized verdict uses the REST file count — the same basis as
    # the gate workflow and CodeRabbit's own per-review ceiling. Tree paths
    # double-count renames (old + new name), which would misroute a
    # rename-heavy candidate into the manifest path despite an existing review.
    if len(candidate_by_name) <= MAX_FILES:
        need(newest is not None, f"no CodeRabbit review on current candidate head {head_sha}")
        # CodeRabbit rounds 10-11 (2026-11-17, review 5939887782 and the
        # assertive full review at 16:45Z): the NEWEST review's state gates
        # the coverage evidence before any declaration is accepted, and the
        # gate is the workflow's own verdict model verbatim — GOOD_STATES
        # only. A newest CHANGES_REQUESTED **or DISMISSED** review rejects
        # coverage even when an older complete declaration exists on the
        # same head (the round-10 exclusion-only check left DISMISSED
        # through). The gate workflow's poll enforces the same model; the
        # verifier must never contradict it (a green poll that raced ahead
        # of a blocking review landing on the same head must not survive
        # here). The declaring pair measured live on #819/#820/#831 stays
        # accepted: a newest APPROVED state — even an empty-bodied one — is
        # a good state over a full declaration.
        need(newest.get("state") in GOOD_STATES,
             f"latest CodeRabbit review on head {head_sha} is {newest.get('state')}; "
             "coverage requires a good verdict")
        review, selected = declaring_review(candidate_reviews, head_sha, BOT, GOOD_STATES)
        need(review is not None,
             "no CodeRabbit review on the current candidate head declares its processed-file list "
             "(a bare empty-bodied approval is not coverage evidence)")
        need(0 < len(selected) <= MAX_FILES, "candidate CodeRabbit review exceeds the 100-file cap")
        for path in selected:
            direct.add(path)
            record = candidate_by_name.get(path)
            if record and record.get("previous_filename"):
                direct.add(record["previous_filename"])
        need(direct <= set(candidate_changes),
             f"candidate CodeRabbit review lists paths outside its diff: {sorted(direct-set(candidate_changes))}")
        if direct == set(candidate_changes):
            print(f"PASS — all {len(candidate_changes)} changed paths are listed in CodeRabbit review {review['id']}.")
            return
    else:
        print(f"Oversized candidate: {len(candidate_changes)} changed paths exceed the {MAX_FILES}-file "
              "per-review ceiling; a direct head review cannot exist by the bot's own limit. "
              "Coverage authority: the review-shard manifest with exact one-to-one verification.")

    # The candidate manifest is fetched as inert JSON from the exact head SHA.
    # No file or executable from the untrusted candidate checkout is used.
    manifest = client.file_json(manifest_path, head_sha, candidate_head_repo)
    validate_manifest(manifest)
    validate_alternative_config(alternative_config)
    obligation, gate_metadata = coverage_obligation(candidate_changes, manifest_path)
    if gate_metadata:
        print(f"Gate metadata excluded from the coverage obligation (inert schema-validated JSON, "
              f"never silently omitted): {', '.join(gate_metadata)}")

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
        # The shard's REST file list is computed against the shard's own
        # merge base (three-dot compare), not the candidate's. When main moved
        # after the candidate base, a shard cut from the current main tip
        # carries main-only files (e.g. this gate's own workflow); comparing
        # its head tree against the candidate merge-base tree would report them
        # as shard changes and fail every shard. Use the shard's merge base.
        shard_merge_base = client.merge_base(base_sha, shard_head, shard_head_repo)
        shard_base = client.tree(shard_merge_base)
        shard_head_tree = client.tree(shard_head, shard_head_repo)
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

    ensure_exact_coverage(obligation, reviewed, alternatives)
    print(f"PASS — candidate {head_sha}; paths={len(candidate_changes)} "
          f"(coverage obligation {len(obligation)} + gate metadata {len(gate_metadata)}), "
          f"CodeRabbit={len(reviewed)}, alternatives={len(alternatives)}, "
          f"review shards={len(manifest['reviewShards'])}.")


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
