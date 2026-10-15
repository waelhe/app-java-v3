#!/usr/bin/env python3
"""GraphQL SDL compatibility classifier (the .ci GraphQL gate's brain).

Compares a baseline (release-tag) runtime-printed SDL against the current
runtime-printed SDL and classifies BREAKING vs NON-BREAKING schema changes
— the GraphQL twin of the OpenAPI gate's openapi-diff leg. The breaking
taxonomy follows the GraphQL target design §9 (the change classes that
block an unreviewed merge):

  BREAKING:
    - a named type disappeared
    - a type's kind changed (type/interface/input/enum/scalar/union)
    - a field disappeared from a type
    - an enum value disappeared
    - a union member disappeared
    - an output field's type tightened nullability (T -> T!)
    - a field argument disappeared
    - an argument's nullability tightened / became required (a: T -> a: T!)
    - an argument's type changed
    - a field's type changed (replacement is breaking by definition)
    - a REQUIRED addition: a new non-null argument without a default on
      an existing field, or a new non-null input field without a default —
      existing operations that omit them stop validating

  NON-BREAKING (additive):
    - new types, new fields, new enum values, new union members, new
      optional arguments (nullable or defaulted), new input fields that
      are nullable or defaulted, nullability WIDENING (T! -> T)

Breaking changes are cross-checked against .ci/graphql-compat-allowlist.yml
(same lifecycle as the OpenAPI exceptions file: ticket + reason + expiry;
an entry that matches no finding is STALE — the documented re-baseline
mechanism — and the gate fails main until it is removed).

SDL input format: the deterministic output of graphql-java's
SchemaPrinter (graphql.schema.idl) over the booted GraphQlSource — a
normalized SDL document (this is why the gate prints BOTH trees through
the same machinery instead of diffing raw schema.graphqls sources). The
printer's default options emit descriptions (single-line "…" lines and
multi-line triple-quoted blocks) and directive applications
(@deprecated and friends) — both are STRUCTURE-IRRELEVANT to
compatibility, so they are stripped before parsing
(strip_block_descriptions + strip_directives): the classifier compares
the schema's shape, never its prose.

Usage: graphql-compat-classifier.py <baseline.graphqls> <current.graphqls> <allowlist.yml>
Exit codes: 0 = compatible or fully allowlisted; 1 = unallowlisted breaking
changes, or stale/unused allowlist entries; 2 = input/allowlist errors.
"""
import re
import sys
from pathlib import Path

# ---------------------------------------------------------------- SDL parsing

TYPE_RE = re.compile(
    r"^(?P<kind>type|interface|input|enum|scalar|union)\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)", re.M)


def strip_block_descriptions(text: str):
    """Removes triple-quoted block descriptions line-wise.

    SchemaPrinter prints a multi-line description as a \"\"\" line, the
    content lines, then a closing \"\"\" line; a single-line description
    may also ride one \"\"\"desc\"\"\" line. Inner description lines are
    NOT SDL definitions — a line such as 'Returns: the page' must never
    reach the field parser as a phantom field (and an unbalanced '(' in
    description prose must never glue the next real field into a buffer).
    """
    out, in_block = [], False
    for line in text.splitlines():
        stripped = line.strip()
        if in_block:
            if '"""' in line:
                in_block = False
            continue
        if stripped.startswith('"""'):
            if stripped.count('"""') >= 2:
                continue  # a complete one-line block description
            in_block = True
            continue
        out.append(line)
    return "\n".join(out)


def strip_directives(src: str):
    """Removes directive applications (@name / @name(args)).

    The printer emits directives by default — e.g. @deprecated(reason:
    "…") after a field type, an enum value, or an argument. Directives
    decorate, never define, the schema shape: deprecating Service.foo
    must not read as a type change, and deprecating an enum value must
    not read as a removal. The scanner is parenthesis-balanced and
    string-aware; an '@' directly after an identifier character (an
    email address inside a default-value string, say) is not a
    directive.
    """
    out = []
    i, n = 0, len(src)
    while i < n:
        ch = src[i]
        if ch == '@' and (i == 0 or not (src[i - 1].isalnum() or src[i - 1] == '_')):
            j = i + 1
            while j < n and (src[j].isalnum() or src[j] == '_'):
                j += 1
            if j < n and src[j] == '(':
                depth, k, in_str = 0, j, False
                while k < n:
                    c = src[k]
                    if in_str:
                        if c == '\\':
                            k += 1
                        elif c == '"':
                            in_str = False
                    else:
                        if c == '"':
                            in_str = True
                        elif c == '(':
                            depth += 1
                        elif c == ')':
                            depth -= 1
                            if depth == 0:
                                k += 1
                                break
                    k += 1
                i = k
                continue
            i = j
            continue
        out.append(ch)
        i += 1
    return "".join(out).strip()


def parse_fields(block: str):
    """Parses a type body's field lines -> {fieldName: (type, args, default)}.

    `args` maps {argName: (type, default)}. Defaults are RETAINED, never
    discarded: a defaulted argument or input field remains an optional
    contract member even when its type is non-null — exactly the
    distinction the required-additions taxonomy keys on. Handles
    multi-line argument lists by joining until the field's closing paren,
    then skipping descriptions/comments (block descriptions were already
    stripped document-wide).
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
        m = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)\s*(\((.*?)\))?\s*:\s*(.+?)(?:\s*=\s*(.+))?$", ln, re.S)
        if not m:
            continue
        name, _, args_src, ftype, fdefault = m.groups()
        args = {}
        if args_src:
            for arg in split_args(args_src):
                am = re.match(r"^([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(.+?)(?:\s*=\s*(.+))?$", arg.strip(), re.S)
                if am:
                    aname, atype, adefault = am.groups()
                    args[aname] = (atype.strip(), adefault.strip() if adefault else None)
        fields[name] = (ftype.strip(), args, fdefault.strip() if fdefault else None)
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
    """Parses an SDL document -> {typeName: {'kind', 'fields', 'values', 'members'}}.

    Block descriptions and directives are stripped first (the printer's
    default output carries both). `scalar X` has no body; `union U = A | B`
    carries its members on the header line — neither must ever go through
    extract_block, which would graft the NEXT type's {…} body onto the
    scalar/union and invent phantom fields. Union members are parsed so
    that removing a member is detected (the union twin of a field
    removal).
    """
    text = Path(path).read_text(encoding="utf-8")
    text = strip_directives(strip_block_descriptions(text))
    schema = {}
    for m in TYPE_RE.finditer(text):
        kind, name = m.group("kind"), m.group("name")
        if kind == "scalar":
            schema[name] = {"kind": kind, "fields": {}, "values": [], "members": []}
            continue
        if kind == "union":
            line_end = text.find("\n", m.start())
            header = text[m.start():line_end if line_end != -1 else len(text)]
            rhs = header.split("=", 1)[1] if "=" in header else ""
            members = [part.strip() for part in rhs.split("|") if part.strip()]
            schema[name] = {"kind": kind, "fields": {}, "values": [], "members": members}
            continue
        body = extract_block(text, m.end())
        if kind == "enum":
            values = [ln.strip() for ln in body.splitlines()
                      if ln.strip() and not ln.strip().startswith(("#", '"'))]
            schema[name] = {"kind": kind, "values": values, "fields": {}, "members": []}
        else:
            schema[name] = {"kind": kind, "fields": parse_fields(body), "values": [], "members": []}
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
        if tdef["kind"] != cdef["kind"]:
            # A kind change redefines what the type IS — an input is not
            # interchangeable with an output type however identical the
            # member lists look. The finding is type-level and precedes
            # any member comparison (members of different kinds are not
            # comparable).
            breaking.append(("type", tname,
                             f"type {tname} kind changed: {tdef['kind']} -> {cdef['kind']}"))
            continue
        if tdef["kind"] == "enum":
            for value in tdef["values"]:
                if value not in cdef["values"]:
                    breaking.append(("enum-value", f"{tname}.{value}",
                                     f"enum value {tname}.{value} removed"))
            continue
        if tdef["kind"] == "union":
            for member in tdef["members"]:
                if member not in cdef["members"]:
                    breaking.append(("type", tname,
                                     f"union member removed from {tname}: {member}"))
            continue
        for fname, (ftype, fargs, _) in tdef["fields"].items():
            if fname not in cdef["fields"]:
                breaking.append(("field", f"{tname}.{fname}",
                                 f"field {tname}.{fname} removed"))
                continue
            ctype, cargs, _ = cdef["fields"][fname]
            if ftype != ctype:
                if strip_bang(ftype) == strip_bang(ctype) \
                        and nullable(ftype) and not nullable(ctype):
                    breaking.append(("field", f"{tname}.{fname}",
                                     f"field {tname}.{fname} nullability tightened: {ftype} -> {ctype}"))
                else:
                    breaking.append(("field", f"{tname}.{fname}",
                                     f"field {tname}.{fname} type changed: {ftype} -> {ctype}"))
            for aname, (atype, _) in fargs.items():
                if aname not in cargs:
                    breaking.append(("argument", f"{tname}.{fname}.{aname}",
                                     f"argument {tname}.{fname}({aname}) removed"))
                    continue
                catype, _ = cargs[aname]
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
        bdef = baseline[tname]
        if tdef["kind"] == "enum":
            for value in tdef["values"]:
                if value not in bdef["values"]:
                    additive.append(("enum-value", f"{tname}.{value}",
                                     f"enum value {tname}.{value} added"))
            continue
        if tdef["kind"] == "union":
            for member in tdef["members"]:
                if member not in bdef["members"]:
                    additive.append(("type", tname,
                                     f"union member added to {tname}: {member}"))
            continue
        for fname, (ftype, fargs, fdefault) in tdef["fields"].items():
            if fname not in bdef["fields"]:
                if tdef["kind"] == "input" and not nullable(ftype) and fdefault is None:
                    # A required input-field addition breaks every
                    # existing operation that omits it — the input-side
                    # twin of a required argument addition.
                    breaking.append(("field", f"{tname}.{fname}",
                                     f"input field {tname}.{fname} added as non-null without default — required additions break existing operations"))
                else:
                    additive.append(("field", f"{tname}.{fname}", f"field {tname}.{fname} added"))
                continue
            btype, bargs, _ = bdef["fields"][fname]
            if not nullable(btype) and nullable(ftype):
                additive.append(("field", f"{tname}.{fname}",
                                 f"field {tname}.{fname} nullability widened: {btype} -> {ftype}"))
            for aname, (atype, adefault) in fargs.items():
                if aname not in bargs:
                    if not nullable(atype) and adefault is None:
                        # A non-null argument without a default cannot be
                        # omitted by an existing operation — it stops
                        # validating. Nullable or defaulted additions
                        # stay additive.
                        breaking.append(("argument", f"{tname}.{fname}.{aname}",
                                         f"argument {tname}.{fname}({aname}) added as required (non-null, no default) — breaks existing operations"))
                    else:
                        additive.append(("argument", f"{tname}.{fname}.{aname}",
                                         f"argument {tname}.{fname}({aname}) added"))
                else:
                    batype, _ = bargs[aname]
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
    """Parses an allowlist target -> (finding kind, dotted object path).

    The accepted formats (what `compare` actually emits as finding kinds
    and object paths): 'type X', 'field X.y', 'enum-value X.v',
    'argument X.y.a' — the argument object is the DOTTED path of the
    argument finding, not the SDL signature notation. Anything else is
    malformed and rejected by the caller (a typo'd entry can never match
    a finding, so failing loudly is the only honest behavior).
    """
    parts = target.strip().split(None, 1)
    if len(parts) != 2 or parts[0] not in ("type", "field", "enum-value", "argument"):
        return None
    return parts[0], parts[1]


def covers(parsed_target, finding):
    """A target covers a finding when the finding's object IS the target
    object or lives UNDER it, and the finding's kind sits at or below the
    target's level in the schema hierarchy:

      type X          covers every finding on X and under it (its own
                      type-level findings, its fields, its enum values,
                      its fields' arguments)
      field X.y       covers the field's own findings AND the argument
                      findings under it (a field replacement exception
                      covers the argument findings under that field)
      enum-value X.v  covers exactly itself
      argument X.y.a  covers exactly itself

    (The kind hierarchy must be honored alongside the path prefix: a
    'field' entry never covers an enum-value finding even if the path
    happened to align — enum values are covered by 'enum-value' or by
    the owning type.)"""
    kind, obj = parsed_target
    fkind, fobj, _ = finding
    if not (fobj == obj or fobj.startswith(obj + ".")):
        return False
    if kind == "type":
        return True
    if kind == "field":
        return fkind in ("field", "argument")
    return fkind == kind


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
    malformed = [t for t in entries if parse_target(t) is None]
    errors.extend(f"allowlist entry '{t}' has a malformed target "
                  f"(expected one of: type X | field X.y | enum-value X.v | argument X.y.a)"
                  for t in malformed)
    if errors:
        for err in errors:
            print(f"  [ALLOWLIST-ERROR] {err}", file=sys.stderr)
        return 2

    unmatched = []
    stale = []
    used = set()
    for finding in breaking:
        matched_meta = None
        for target, meta in entries.items():
            parsed = parse_target(target)
            if parsed and covers(parsed, finding):
                matched_meta = meta
                used.add(target)
                break
        if matched_meta is None:
            unmatched.append(finding)
        elif is_stale(matched_meta.get("expiry", "")):
            stale.append(finding)

    for _, _, detail in stale:
        print(f"  [STALE-EXCEPTION] {detail} — exception expired")
    if stale:
        unmatched.extend(stale)

    # The documented lifecycle, enforced: an entry exists only while its
    # breaking change is NOT part of the baseline. After the next release
    # tag re-baselines the schema, the finding disappears, the entry
    # matches nothing, and the gate fails main until it is removed — the
    # mechanism that keeps this list temporary.
    unused = [t for t in entries if t not in used]
    for target in unused:
        print(f"  [UNUSED-ENTRY] allowlist entry '{target}' matched no breaking change — "
              f"stale after re-baseline; remove it (the gate fails until the list is honest)")

    if unmatched:
        print("Unallowlisted (or stale-exception) breaking changes:")
        for _, _, detail in unmatched:
            print(f"  {detail}")
        print("Document them in .ci/graphql-compat-allowlist.yml (ticket + reason + expiry) "
              "or fix the change.")
        return 1

    if unused:
        print("Unused allowlist entries (see [UNUSED-ENTRY] above).")
        return 1

    if breaking:
        print(f"All {len(breaking)} breaking change(s) covered by documented exceptions.")
    print("GraphQL schema compatibility gate passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
