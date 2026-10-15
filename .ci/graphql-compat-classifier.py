#!/usr/bin/env python3
"""GraphQL SDL compatibility classifier (the .ci GraphQL gate's brain).

Compares a baseline (release-tag) runtime-printed SDL against the current
runtime-printed SDL and classifies BREAKING vs NON-BREAKING schema changes
— the GraphQL twin of the OpenAPI gate's openapi-diff leg. The breaking
taxonomy follows the GraphQL target design §9 (the change classes that
block an unreviewed merge):

  BREAKING:
    - a named type disappeared
    - a field disappeared from a type
    - an enum value disappeared
    - an output field's type tightened nullability (T -> T!)
    - a field argument disappeared
    - an argument's nullability tightened / became required (a: T -> a: T!)
    - an argument's type changed
    - a field's type changed (replacement is breaking by definition)

  NON-BREAKING (additive):
    - new types, new fields, new enum values, new optional arguments
      (nullable or defaulted), nullability WIDENING (T! -> T)

Breaking changes are cross-checked against .ci/graphql-compat-allowlist.yml
(same lifecycle as the OpenAPI exceptions file: ticket + reason + expiry;
an entry goes stale at the next release re-baseline and the gate fails
main until it is removed).

SDL input format: the deterministic output of graphql-java's
GraphQLSchemaPrinter over the booted GraphQlSource — a normalized SDL
document (this is why the gate prints BOTH trees through the same
machinery instead of diffing raw schema.graphqls sources).

Usage: graphql-compat-classifier.py <baseline.graphqls> <current.graphqls> <allowlist.yml>
Exit codes: 0 = compatible or fully allowlisted; 1 = unallowlisted breaking
changes; 2 = input/allowlist errors.
"""
import re
import sys
from pathlib import Path

# ---------------------------------------------------------------- SDL parsing

TYPE_RE = re.compile(
    r"^(?P<kind>type|interface|input|enum|scalar|union)\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)", re.M)


def parse_fields(block: str):
    """Parses a type body's field lines -> {fieldName: (type, args)}.

    Handles multi-line argument lists by joining until the field's closing
    paren, then stripping descriptions/comments.
    """
    fields = {}
    lines = [ln.strip() for ln in block.splitlines()]
    joined = []
    buf = ""
    for ln in lines:
        if not ln or ln.startswith("#") or ln.startswith('"'):
            continue
        buf = (buf + " " + ln).strip() if buf else ln
        if buf.count("(") > buf.count(")"):
            continue  # argument list continues on the next line
        joined.append(buf)
        buf = ""
    if buf:
        joined.append(buf)
    for ln in joined:
        m = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)\s*(\((.*?)\))?\s*:\s*(.+)$", ln, re.S)
        if not m:
            continue
        name, _, args_src, ret = m.groups()
        args = {}
        if args_src:
            for arg in split_args(args_src):
                am = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(.+?)(\s*=\s*.+)?$", arg.strip())
                if am:
                    args[am.group(1)] = am.group(2).strip()
        fields[name] = (ret.strip(), args)
    return fields


def split_args(src: str):
    """Splits an argument list on top-level commas (nested parens aware)."""
    out, depth, cur = [], 0, ""
    for ch in src:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        out.append(cur)
    return out


def parse_schema(path: str):
    """Parses an SDL document -> {typeName: {'kind', 'fields', 'values'}}."""
    text = Path(path).read_text(encoding="utf-8")
    schema = {}
    for m in TYPE_RE.finditer(text):
        kind, name = m.group("kind"), m.group("name")
        body = extract_block(text, m.end())
        if kind == "enum":
            values = [ln.strip() for ln in body.splitlines()
                      if ln.strip() and not ln.strip().startswith(("#", '"'))]
            schema[name] = {"kind": kind, "values": values, "fields": {}}
        else:
            schema[name] = {"kind": kind, "fields": parse_fields(body), "values": []}
    return schema


def extract_block(text: str, start: int):
    """Extracts a { ... } body starting after a type header (brace-aware)."""
    depth, body_start = 0, None
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
            body_start = i + 1 if body_start is None else body_start
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[body_start:i]
    return ""


# ------------------------------------------------------------- classification
# A finding is a structured tuple (kind, object, detail):
#   kind   — 'type' | 'field' | 'enum-value' | 'argument'
#   object — dotted path WITHOUT the kind word, e.g. 'Query.services',
#            'Service.description', 'Query.services.first'
#   detail — the human sentence for the report

def nullable(t: str):
    return not t.endswith("!")


def strip_bang(t: str):
    return t.rstrip("!")


def compare(baseline: dict, current: dict):
    breaking, additive = [], []
    for tname, tdef in baseline.items():
        if tname not in current:
            breaking.append(("type", tname, f"type {tname} removed"))
            continue
        cdef = current[tname]
        if tdef["kind"] == "enum":
            for value in tdef["values"]:
                if value not in cdef["values"]:
                    breaking.append(("enum-value", f"{tname}.{value}",
                                     f"enum value {tname}.{value} removed"))
            continue
        for fname, (ftype, fargs) in tdef["fields"].items():
            if fname not in cdef["fields"]:
                breaking.append(("field", f"{tname}.{fname}",
                                 f"field {tname}.{fname} removed"))
                continue
            ctype, cargs = cdef["fields"][fname]
            if ftype != ctype:
                if strip_bang(ftype) == strip_bang(ctype) \
                        and nullable(ftype) and not nullable(ctype):
                    breaking.append(("field", f"{tname}.{fname}",
                                     f"field {tname}.{fname} nullability tightened: {ftype} -> {ctype}"))
                else:
                    breaking.append(("field", f"{tname}.{fname}",
                                     f"field {tname}.{fname} type changed: {ftype} -> {ctype}"))
            for aname, atype in fargs.items():
                if aname not in cargs:
                    breaking.append(("argument", f"{tname}.{fname}.{aname}",
                                     f"argument {tname}.{fname}({aname}) removed"))
                    continue
                catype = cargs[aname]
                if catype != atype:
                    breaking.append(("argument", f"{tname}.{fname}.{aname}",
                                     f"argument {tname}.{fname}({aname}) type changed: {atype} -> {catype}"))
                elif nullable(atype) and not nullable(catype):
                    breaking.append(("argument", f"{tname}.{fname}.{aname}",
                                     f"argument {tname}.{fname}({aname}) became required: {atype} -> {catype}"))
    for tname, tdef in current.items():
        if tname not in baseline:
            additive.append(("type", tname, f"type {tname} added"))
            continue
        if tdef["kind"] == "enum":
            for value in tdef["values"]:
                if value not in baseline[tname]["values"]:
                    additive.append(("enum-value", f"{tname}.{value}",
                                     f"enum value {tname}.{value} added"))
            continue
        for fname, (ftype, fargs) in tdef["fields"].items():
            if fname not in baseline[tname]["fields"]:
                additive.append(("field", f"{tname}.{fname}", f"field {tname}.{fname} added"))
                continue
            btype, bargs = baseline[tname]["fields"][fname]
            if not nullable(btype) and nullable(ftype):
                additive.append(("field", f"{tname}.{fname}",
                                 f"field {tname}.{fname} nullability widened: {btype} -> {ftype}"))
            for aname, atype in fargs.items():
                if aname not in bargs:
                    additive.append(("argument", f"{tname}.{fname}.{aname}",
                                     f"argument {tname}.{fname}({aname}) added"))
                else:
                    batype = bargs[aname]
                    if not nullable(batype) and nullable(atype):
                        additive.append(("argument", f"{tname}.{fname}.{aname}",
                                         f"argument {tname}.{fname}({aname}) became optional"))
    return breaking, additive


# ------------------------------------------------------- allowlist (YAML-lite)

def parse_allowlist(path: str):
    """Reads the exceptions list without a YAML dependency (structured
    entries: target + ticket + reason + expiry)."""
    entries = {}
    if not Path(path).exists():
        return entries, ["allowlist file missing"]
    errors = []
    current = None
    for raw in Path(path).read_text(encoding="utf-8").splitlines():
        line = raw.rstrip()
        if line.strip().startswith("- target:"):
            current = line.split(":", 1)[1].strip()
            entries[current] = {}
        elif current and re.match(r"^\s+(ticket|reason|expiry):\s*.+", line):
            key, _, value = line.strip().partition(":")
            entries[current][key.strip()] = value.strip()
    for target, meta in entries.items():
        for key in ("ticket", "reason", "expiry"):
            if key not in meta or not meta[key]:
                errors.append(f"allowlist entry '{target}' missing {key}")
    return entries, errors


def is_stale(expiry: str):
    import datetime
    try:
        return datetime.date.today() > datetime.date.fromisoformat(expiry)
    except ValueError:
        return True


def parse_target(target: str):
    """Parses an allowlist target 'field Query.services' -> ('field', 'Query.services')."""
    parts = target.strip().split(None, 1)
    if len(parts) != 2 or parts[0] not in ("type", "field", "enum-value", "argument"):
        return None
    return parts[0], parts[1]


def covers(parsed_target, finding):
    """A target covers a finding when kinds match and the finding's object
    IS the target object or lives UNDER it (an argument-level finding is
    covered by its field-level target — a field replacement exception
    covers the argument findings under that field)."""
    kind, obj = parsed_target
    fkind, fobj, _ = finding
    return fkind == kind and (fobj == obj or fobj.startswith(obj + "."))


def main():
    if len(sys.argv) != 4:
        print(__doc__)
        return 2
    baseline_sdl, current_sdl, allowlist_path = sys.argv[1:4]
    baseline = parse_schema(baseline_sdl)
    current = parse_schema(current_sdl)
    breaking, additive = compare(baseline, current)

    print("=== GraphQL schema compatibility report ===")
    for _, _, detail in additive:
        print(f"  [additive] {detail}")
    for _, _, detail in breaking:
        print(f"  [BREAKING] {detail}")

    entries, errors = parse_allowlist(allowlist_path)
    if errors:
        for err in errors:
            print(f"  [ALLOWLIST-ERROR] {err}", file=sys.stderr)
        return 2

    unmatched = []
    stale = []
    for finding in breaking:
        matched_meta = None
        for target, meta in entries.items():
            parsed = parse_target(target)
            if parsed and covers(parsed, finding):
                matched_meta = meta
                break
        if matched_meta is None:
            unmatched.append(finding)
        elif is_stale(matched_meta.get("expiry", "")):
            stale.append(finding)

    for _, _, detail in stale:
        print(f"  [STALE-EXCEPTION] {detail} — exception expired")
    if stale:
        unmatched.extend(stale)

    if unmatched:
        print("Unallowlisted (or stale-exception) breaking changes:")
        for _, _, detail in unmatched:
            print(f"  {detail}")
        print("Document them in .ci/graphql-compat-allowlist.yml (ticket + reason + expiry) "
              "or fix the change.")
        return 1

    if breaking:
        print(f"All {len(breaking)} breaking change(s) covered by documented exceptions.")
    print("GraphQL schema compatibility gate passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
