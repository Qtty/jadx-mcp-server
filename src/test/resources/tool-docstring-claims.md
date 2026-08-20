# Tool descriptions: the figures the model is handed

`mcp_server/jadx_mcp.py`'s `@mcp.tool` docstrings are not comments. They are the
text a model reads when it decides whether to call a tool, with what arguments,
and what to do with the answer. A number in one that nothing re-runs is a number
that drifts, so this file ties each figure a docstring states to a measurement.

`ToolDocstringTest` checks the docstrings against the two fenced blocks below.
Both files are in this repository, so the suite runs anywhere:

- **`claims`** -- `id | apk | call | unit | expected | cite`. The id names a
  measurement in `DocClaims`, `apk`/`call`/`unit` have to match what that
  measurement says it measured, the cite has to occur in the docstrings exactly
  once and carry the value, and the call is re-run and compared.
- **`unverified`** -- `value | why`. A figure the docstrings state that nothing
  re-runs, and the reason. Every measurement-shaped figure in a docstring has to
  appear in one block or the other, so a new number cannot be written into a
  tool description and be checked by nothing.

A docstring quotes the few figures that change a caller's decision -- the
default caps -- rather than a table of measurements about any one application.

```claims
cap.entries.default | code | JadxAnalyzerCore.DEFAULT_RESULT_LIMIT | entries | 1000 | Capped at 1000 names by default. The whole list on a large APK is many
cap.bytes.default | code | JadxAnalyzerCore.DEFAULT_MAX_BYTES | bytes | 1,048,576 | An UNCAPPED answer over 1,048,576 bytes leads with
cap.bytes.source.default | code | JadxAnalyzerCore.DEFAULT_SOFT_BYTES | bytes | 8,192 | Budgeted at 8,192 bytes of answer by default
```

```unverified
1 MB | the 1,048,576-byte cap (cap.bytes.default) in round numbers, in the sentences that tell a caller what an @oversized marker means. The exact figure is claimed; this is its name.
8 KB | the 8,192-byte budget (cap.bytes.source.default) in round numbers, in the sentence explaining what the first part of a decompiled class actually contains. The exact figure is claimed; this is its name.
```
