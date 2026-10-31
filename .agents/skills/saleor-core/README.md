# Saleor Core Skill — pinned platform reference

This repository vendors the reviewed `saleor-core` skill snapshot at
`saleor/saleor-docs@2cdf6590e838929c3f74c16e982cfdc11073c2d8`.

The skill is automatically available to compatible coding agents from
`.agents/skills/saleor-core`. Its source commit, official bundle digest,
per-file hashes, attribution, and adoption state are recorded in
`docs/governance/plans/agent-skills-pins.json` and
`docs/governance/plans/saleor-ai-resource-surface.json`.

## Installation and updates

Do not run `npx skills add` or `npx skills update` in this repository:
those commands resolve remote skill content and can replace this reviewed
snapshot outside the repository's provenance and hash-verification workflow.

For a separate project that intentionally wants the current upstream skill
rather than this repository's reviewed pin, follow the official instructions
at <https://docs.saleor.io/developer/ai>:

```shell
npx skills add saleor/saleor-docs --skill saleor-core
```

That command installs the current distribution; it does not reproduce this
repository's pinned commit. For this platform, use the committed snapshot and
the pin registry above.

## What's included

The adopted snapshot provides Saleor Core implementation guidance for
discount precedence, order-level discount allocation, and stock availability
modes. These rules are specialist reference material only. The pinned Saleor
3.23.45 source/tests, Dashboard 3.23.41, canonical platform plan, frozen
contracts, and approved divergence records remain authoritative.
