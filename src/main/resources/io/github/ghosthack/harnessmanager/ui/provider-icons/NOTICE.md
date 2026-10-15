# AI provider marks

These files retain the published geometry of the OpenAI, Anthropic, Cursor, Grok, OpenCode, Pi and Muse
marks, sourced through the `development-iconography` project and flattened
into a small, themeable SVG profile. OpenAI, Anthropic, and Cursor are
trademarks of their respective owners. The marks are included only to identify
the corresponding provider; inclusion does not imply endorsement or
affiliation.

Geometry provenance:

- OpenAI: the CC0-licensed Simple Icons OpenAI asset, checked against OpenAI's
  published Blossom.
- Anthropic: Anthropic's first-party press kit.
- Cursor: Cursor's first-party brand assets; the 2D mark is used here.

The source SVGs intentionally carry no literal fill. The app supplies its
current foreground colour so the marks retain contrast in light, dark, and
selected sidebar rows.

Additional geometry sources:

- Grok: its two-part symbol derives from https://grok.com/images/favicon.svg.
- OpenCode: https://opencode.ai/brand and the official asset at
  https://github.com/anomalyco/opencode/blob/dev/packages/console/app/src/asset/brand/opencode-logo-light.svg.
  Clip/mask scaffolding is removed; the shaded inset uses relative opacity so it
  remains visible with the current foreground tint.
- Pi: the compact blocky mark supplied by the official https://pi.dev/press-kit,
  from https://pi.dev/favicon.svg.
- Muse: the stylized m from
  https://muse.ai/images/landing/brand/muse-logo.svg. Its published path is retained;
  the source gradient is replaced by the host foreground, consistent with other
  harness icons. This is the supplied visual identity for the Muse CLI entry.

The added source view boxes include modest optical padding. Marks identify their
corresponding entries; they do not imply affiliation or endorsement.
