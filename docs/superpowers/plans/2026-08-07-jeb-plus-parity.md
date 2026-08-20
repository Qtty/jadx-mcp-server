# JEB-Plus Parity Implementation Plan

> ## Status: complete
>
> All seven tasks shipped. `jadx-analyzer` exposes 21 tools; the five gap rows in
> `the client's tool reference` read "yes"; the c7 interface-dispatch case
> resolves by query. `mvn test` 60/60, `pytest` 30/30, caller suites 3 / 180 / 253.
> Slowest new tool on the warm 141461-class daemon: 0.095 s.
>
> **Three things this plan asserted turned out to be wrong.** They are corrected
> in place below rather than quietly worked around, because each would have cost
> the next reader real time:
>
> 1. **The "one trap" is not a trap.** Usage info and override attributes are
>    already built by `load()`; no indexing, no `setUsageInfoCache`, no
>    `runPreDecompileStage()`. See the amended section and Task 7's findings.
> 2. **Two of Task 5's tests were impossible as written.** Both named DIVA's
>    `InputValidation2URISchemeActivity`, which is declared with no intent-filter
>    at all; DIVA's manifest has zero BROWSABLE categories and zero
>    `android:scheme` attributes. Resolution is verified against the big APK
>    instead, which carries a real `@string/`-referenced scheme.
> 3. **A prerequisite the plan did not know about.** Every `className` lookup
>    resolved only top-level classes — 651 of DIVA's 1791, 72345 of a large production APK's
>    141461 — so inner and anonymous classes were unreachable through source,
>    methods, fields and smali. Task 1's own contract test caught it. Fixed there,
>    and it matters: 46 `shouldOverrideUrlLoading` implementations found in Task 4
>    are largely inner classes.
>
> Deliberately not done, by the operator's decision: the full JADX-backed
> a large production APK analysis in Task 6. The c7 case it exists to prove is verified by direct
> query instead.
>
> Follow-ups worth their own tasks, from Task 7's research:
> **jadx 1.5.6** exists and carries zip-traversal and XML-DoS fixes;
> **`jadx-analysis`** (new at 1.5.6) is a real call graph that would replace this
> repo's regex-over-decompiled-source `generateCallGraphForMethod`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> This document is a **backlog**, not a step-by-step script. Each task states the
> exact contract, the verified JADX API to build it on, and how to prove it
> works — but the implementation body is yours to write and test. Expand a task
> into TDD steps when you pick it up.

**Goal:** Close the seven-tool capability gap between `jadx-analyzer` and `jeb-plus` so a JADX-backed analysis can resolve interface dispatch, and stop degrading every worker that falls back to JADX.

**Architecture:** Every tool spans four layers — `JadxAnalyzerCore` (JADX API) → `JadxApkAnalyzerAPI` (throws checked exceptions) → `JadxRestController` (query params, single-key envelope) → `mcp_server/jadx_mcp.py` (`JadxClient` method + `@mcp.tool`). Add each new capability through all four; there is no shortcut layer.

**Tech Stack:** Java 21, Spring Boot 3.4.5, jadx-core 1.5.5, Python 3.13 with `mcp` 1.27.2 + `httpx`.

## Context

`the analysis client` probes for `jeb-plus` first and falls back to
`jadx-analyzer`. Its own reference doc, `the client's tool reference`, states why:

> JEB exposes cross-reference and type-hierarchy queries that JADX does not.
> Those are what make it possible to resolve interface dispatch — which is
> exactly how most real deeplink routers pick a destination. On JADX, some
> routes are simply not resolvable to a concrete class.

That is not theoretical. **Ten agent files** carry an explicit degradation clause,
e.g. a sink-tracing worker doc:

> **Backend is `jadx`** → no xref or override tools; reaching sinks through
> interface dispatch may be impossible. Record what you can reach and note the
> limitation rather than asserting a path you could not follow.

And a real run hit it. From the a large production APK component trace (`c7`):

> jadx backend: no `get_xrefs_to_method` available, so callers of
> `BankPayMethod.getActivity` were confirmed by reading `BankPayMethod` source
> directly.

Closing these gaps deletes those clauses and makes JADX a first-class backend
rather than a lossy fallback. It also matters more now than it used to: JEB needs
a human to have the APK open in the GUI, while the JADX daemon runs headless and
keeps APKs warm, so it is the only backend that works unattended.

## The gap

Verified by enumerating both servers' tools across the agent definitions.
`jeb-plus` exposes 17, `jadx-analyzer` 14, and the sets are not nested.

| Capability | jeb-plus | jadx | This plan |
|---|---|---|---|
| `search_classes_by_keyword` | yes | **no** | Task 1 |
| `get_xrefs_to_class` / `_method` / `_field` | yes | **no** | Task 2 |
| `get_type_hierarchy` | yes | **no** | Task 3 |
| `get_overrides` | yes | **no** | Task 4 |
| `get_deeplink_components` | yes | **no** | Task 5 |
| `get_all_classes` | **no** | yes | — JADX-only, keep |
| `load_apk` | **no** | yes | — JADX-only, keep |

Seven new tools, taking `jadx-analyzer` from 14 to 21.

## Global Constraints

- **Tool and parameter names are a contract.** Agents call them by name. New
  tools must match `jeb-plus` naming exactly, and parameters follow the existing
  camelCase convention: `className`, `methodName`, `fieldName`, `keyword`.
- **One JSON text block per result.** Every `@mcp.tool` returns `_json(...)` and
  is declared `structured_output=False`. Letting FastMCP infer the shape from a
  `list`/`dict` annotation splits the result into one content block per element
  and duplicates the payload in `structuredContent`.
- **Query parameters, never path variables**, in `JadxRestController`. Class
  names contain dots and resource paths contain slashes; path variables 404.
- **Alias-aware names everywhere.** `loadApk()` sets `setDeobfuscationOn(true)`.
  Return `ClassInfo.getAliasFullName()` / `MethodInfo.getAliasFullName()`, never
  the raw name — `get_class_source` rejects raw names with "Class not found".
  Resolve incoming names with `JadxDecompiler.searchJavaClassByAliasFullName(String)`.
- **Never call `JavaClass.getMethods()` / `getFields()` / `getCode()` in a
  whole-APK loop.** They call `load()`, which decompiles. That pattern cost 71.6s
  per query before it was replaced with a dex-metadata scan; see
  `JadxAnalyzerCore.searchMethodByName` for the shape to copy.
- **Localhost only.** The daemon binds `127.0.0.1`; it reads arbitrary classes
  out of a loaded APK.
- Every task ends with `mvn test` and `pytest mcp_server/tests/ -q` green.

## The one trap to measure first — ~~measure~~ **measured: it is not a trap**

> **Resolved by Task 7.** The section below stated the concern; the measurement
> disproved it. Full numbers and method in
> [`docs/research/2026-jadx-capabilities.md`](../../research/2026-jadx-capabilities.md).
> Reproduce with `src/test/java/.../UsageIndexCostProbe.java` and
> `OverrideAttrProbe.java`.

The concern was that `getUseIn()` and `AType.METHOD_OVERRIDE` are **not free like
the dex-metadata scan is** — that because they are produced by passes
(`OverrideMethodVisitor` runs in `RootNode.runPreDecompileStage()`) and jadx
ships `EmptyUsageInfoCache` by default, the work would be redone per query.

**It is not.** `UsageInfoVisitor` runs as part of `load()` and the results live on
the dex nodes. `EmptyUsageInfoCache` means the index is not persisted *across
`JadxDecompiler` instances* — not that it is recomputed per query. The daemon
holds one live decompiler per APK, so we are always on the warm path.

Measured on the 282 MB / 141,461-class a large production APK APK:

| | a large production APK | DIVA |
|---|---|---|
| First `getUseIn()` on a cold decompiler | **0 ms** | **0 ms** |
| Walking `getUseIn()` for *every* class | **22 ms** (901,332 refs) | 0 ms |
| Methods with `METHOD_OVERRIDE` before `runPreDecompileStage()` | 18,516 | 6,260 |
| …after it (identical — the stage adds nothing) | 18,516 | 6,260 |
| Cost of `runPreDecompileStage()` | 3,309 ms | 72 ms |
| Cost of calling it a second time (not cached) | 3,306 ms | 47 ms |

**Consequences, binding on Tasks 2 and 4:**

- There is **no indexing decision to make**. Read `getUseIn()` directly.
- **Do not** call `setUsageInfoCache(...)`; it buys nothing for a warm daemon.
- **Do not** call `runPreDecompileStage()` — the attribute is already attached,
  and calling it would be a 3.3-second-per-call regression for no gain. The
  earlier suggestion to invoke it in `AnalyzerRegistry` on load is withdrawn.
- If a class ever *is* missing the attribute,
  `RootNode.runPreDecompileStageForClass(ClassNode)` measured 0 ms and is the
  per-class escape hatch.

## File structure

Every task touches the same four files plus tests. Stated once here rather than
repeated per task:

- `src/main/java/com/example/jadxmcpserver/core/JadxAnalyzerCore.java` — JADX API
- `src/main/java/com/example/jadxmcpserver/JadxApkAnalyzerAPI.java` — wrapper
- `src/main/java/com/example/jadxmcpserver/JadxRestController.java` — endpoint
- `mcp_server/jadx_mcp.py` — `JadxClient` method + `@mcp.tool`
- `src/test/java/com/example/jadxmcpserver/core/…Test.java` — Java test
- `mcp_server/tests/test_tools.py` — envelope + contract test

---

## Task 1: `search_classes_by_keyword`

Start here. It is the smallest tool that exercises all four layers, so it
establishes the pattern before anything hard.

**Contract**
- Tool: `search_classes_by_keyword(keyword: str)` → `list[str]` of class names
- REST: `GET /api/jadx/search-classes?keyword=…` → `{"classes": [...]}`

**JADX API (verified in 1.5.5)**

```java
for (ClassNode cls : jadx.getRoot().getClasses()) {
    String name = cls.getClassInfo().getAliasFullName();
    if (name.toLowerCase().contains(keyword.toLowerCase())) { ... }
}
```

Metadata only — no decompilation. Copy the loop shape from
`JadxAnalyzerCore.searchMethodByName`.

- [ ] Test: a known DIVA class is found by a partial, case-insensitive keyword;
      an absent keyword returns `[]` rather than throwing
- [ ] Test: results are alias names that `get_class_source` accepts — feed one
      straight back in and assert it resolves
- [ ] Implement across all four layers
- [ ] Verify on a large production APK: `search_classes_by_keyword("SchemeDispatcher")`
      returns `com.example.app.SchemeDispatcher` in well under a second

## Task 2: `get_xrefs_to_class` / `get_xrefs_to_method` / `get_xrefs_to_field`

The highest-value gap — this is the one that unblocks interface dispatch, and the
one the a large production APK trace explicitly worked around.

**The measurement is done — see the trap section. No indexing work is required:
call `getUseIn()` directly, add no caches, and do not run the pre-decompile stage.**

**Contract**
- `get_xrefs_to_class(className: str)` → list of referencing locations
- `get_xrefs_to_method(className: str, methodName: str)` → same
- `get_xrefs_to_field(className: str, fieldName: str)` → same
- REST: `GET /api/jadx/xrefs-to-class|-method|-field` → `{"xrefs": [...]}`

Each entry should identify the referencing **class and method**, because a bare
class name is not actionable for a tracer following a call chain. Match what
`jeb-plus` returns closely enough that the agent prose describing xrefs stays
true for both backends.

**JADX API (verified in 1.5.5)**

```java
JavaClass  cls = jadx.searchJavaClassByAliasFullName(className);
List<JavaNode> users = cls.getUseIn();          // also on JavaMethod, JavaField
// dex-level equivalents, usually cheaper:
ClassNode.getUseIn();      // List<ClassNode>
ClassNode.getUseInMth();   // List<MethodNode>
MethodNode.getUseIn();     // List<MethodNode>
```

- [x] ~~Measure and record cold `getUseIn()` cost on a large production APK; choose eager vs
      lazy indexing per the trap section~~ — done in Task 7: 0 ms cold, 22 ms for
      all 141k classes. Neither eager nor lazy indexing is needed.
- [ ] Test: a DIVA class with a known caller returns that caller
- [ ] Test: a class with no callers returns `[]`, not an error
- [ ] Test: an unknown class name raises the same "not found" error shape as
      `get_class_source`
- [ ] Test (Python): the `{"xrefs": [...]}` envelope unwraps to a bare list
- [ ] Verify on a large production APK against the known case: xrefs to
      `BankPayMethod.getActivity` must include `SchemeActivity`, which the c7
      trace had to establish by hand

## Task 3: `get_type_hierarchy`

**Contract**
- `get_type_hierarchy(className: str)` → supertypes, interfaces, and known subclasses
- REST: `GET /api/jadx/type-hierarchy?className=…` → `{"hierarchy": {...}}`

**JADX API (verified in 1.5.5)**

```java
ClassNode cls = ...;
ArgType         superCls  = cls.getSuperClass();
List<ArgType>   ifaces    = cls.getInterfaces();
List<ClassNode> inner     = cls.getInnerClasses();
ClassNode       outer     = cls.getParentClass();
```

For subclasses there is no reverse index — scan `getRoot().getClasses()` once and
match on `getSuperClass()` / `getInterfaces()`. For types outside the APK
(framework/SDK), `RootNode.getClsp()` (`ClspGraph`) answers
`isClsKnown(String)` and `getClsDetails(ArgType)`.

- [ ] Test: a DIVA activity reports `AppCompatActivity` (or its real supertype)
      and its declared interfaces
- [ ] Test: subclass lookup finds a known subclass in the APK
- [ ] Test: a framework type that is not in the APK still resolves supertypes via
      `ClspGraph`, or reports cleanly that it cannot — do not return a silent `{}`
- [ ] Verify on a large production APK: hierarchy for a `WebViewClient` subclass lists
      `android.webkit.WebViewClient`

## Task 4: `get_overrides`

**Contract**
- `get_overrides(className: str, methodName: str)` → methods overriding, plus the
  base methods overridden
- REST: `GET /api/jadx/overrides?className=…&methodName=…` → `{"overrides": {...}}`

**JADX API (verified in 1.5.5)**

```java
MethodOverrideAttr attr = mth.get(AType.METHOD_OVERRIDE);
attr.getOverrideList();     // List<IMethodDetails>
attr.getRelatedMthNodes();  // SortedSet<MethodNode> — the override set
attr.getBaseMethods();      // Set<IMethodDetails>
```

`get(...)` comes from `AttrNode`; `AType.METHOD_OVERRIDE` is its key. An
`IMethodDetails` yields a name via `getMethodInfo()`, so the same
`getAliasFullName()` treatment as everywhere else applies.

The attribute is attached by `OverrideMethodVisitor`, and **`load()` has already
run it** — measured 18,516 methods carrying it on a large production APK before any explicit
call, unchanged after. Do not call `runPreDecompileStage()`; see the trap section.
Keep the "attribute is present" test below anyway: it is what would catch this
assumption breaking on a future jadx version.

- [ ] Test: a DIVA `onCreate` reports the `Activity.onCreate` base method
- [ ] Test: a method that overrides nothing returns empty structures, not null
- [ ] Test: the attribute is present — i.e. the pre-decompile stage actually ran.
      A silent `null` here would make the tool always answer "no overrides",
      which is worse than erroring
- [ ] Verify on a large production APK: overrides of `shouldOverrideUrlLoading` list the app's
      concrete `WebViewClient` subclasses

## Task 5: `get_deeplink_components`

The largest task, and the only one whose JEB counterpart does real work beyond a
query: it returns browsable components **with resource references resolved**.

`the client's tool reference` documents the consequence of not having it:

> Manifest attributes are frequently declared as resource references rather than
> literals — `android:scheme="@string/deeplink_scheme"`. Depending on how the
> manifest was decoded, those arrive unresolved, as `@7F12038D`. Verified against
> a real production manifest: of six browsable components, two carried
> unresolved scheme references.

Two unresolved schemes out of six is a third of the attack surface arriving
unusable, so resolution is the point of this task, not a nicety.

**Contract**
- `get_deeplink_components()` → components with BROWSABLE intent-filters, each
  with scheme/host/path data **fully resolved to literals**
- REST: `GET /api/jadx/deeplink-components` → `{"components": [...]}`

Match the record shape the caller already consumes — check
`apk-manifest-extractor` and `pipeline/manifest.py` in `the analysis client`
before designing it, since that pipeline is the existing JADX-path substitute and
its schema is the de facto target.

- [ ] Read `pipeline/manifest.py` and the `write-component` CLI schema first;
      write down the exact target record shape before coding
- [ ] Test: DIVA's `InputValidation2URISchemeActivity` is returned with its
      scheme resolved to a literal
- [ ] Test: a component whose scheme is a `@string/…` reference resolves to the
      string value, not `@7F12038D`
- [ ] Verify on a large production APK: the 45 exported components include the browsable ones,
      and `appscheme://` and `paymentsdk://` appear as literals
- [ ] Compare against `cli.py import-manifest` output on the same APK — this tool
      should be a superset, never a regression

## Task 6: Propagate parity into `the analysis client`

Shipping the tools changes nothing until the workers are told they exist. This
task lives in `a sibling working directory` (no git remote).

- [ ] Add the seven tool names to the `mcp__jadx-analyzer__*` frontmatter lists in
      every agent that already lists the `jeb-plus` equivalent
- [ ] Delete the ten `Backend is `jadx`` degradation clauses in:
      a component-resolver worker, a component-tracing worker, a route-enumeration worker,
      a sink-tracing worker, `apk-webview-assessor`, `apk-webview-bridge-auditor`,
      `apk-webview-chrome-auditor`, `apk-webview-discoverer`,
      `apk-webview-dissector`, `apk-webview-navigator` — but only for capabilities
      actually shipped; leave any clause whose gap remains open
- [ ] Rewrite the availability matrix and the "Why JEB is preferred" section in
      `the client's tool reference` to reflect real state
- [ ] Run `python3 scripts/check-workflow.py workflows/*.js`,
      `node scripts/workflow-harness.mjs` (180 assertions),
      `bash scripts/smoke-test.sh` (253 assertions)
- [ ] End-to-end: run a full analysis on a large production APK with the JADX backend and confirm
      the c7 case — callers of `BankPayMethod.getActivity` — now resolves via
      `get_xrefs_to_method` instead of a hand-read

## Task 7 (research): what else does modern JADX give us?

**Deliverable:** a short findings document at
`docs/research/2026-jadx-capabilities.md` — each item with the API name, whether
it exists in 1.5.5 or needs an upgrade, and a one-line verdict on whether it is
worth adopting. Recommendations only; no implementation in this task.

The project moved from 1.5.2 to 1.5.5 during the Python rewrite without anyone
reading what changed. Some of the biggest wins in this codebase came from
noticing an API we were not using — the dex-metadata scan that turned a 71.6s
query into 0.08s was exactly that.

Sources: the [jadx releases page](https://github.com/skylot/jadx/releases),
`CHANGELOG.md`, the [Use jadx as a library](https://github.com/skylot/jadx/wiki/Use-jadx-as-a-library)
wiki page, and `javap` against the jar for anything the docs are vague about.

Questions to answer:

- [ ] **What landed between 1.5.2 and current?** Any newer stable release, and
      does it change the APIs this server depends on?
- [ ] **`ICodeCache`.** `JadxArgs.setCodeCache(...)` — can decompiled output
      persist to disk across daemon restarts, not just in memory? The daemon is
      long-lived now, so a disk cache would survive reboots and machine restarts.
      What is the invalidation story?
- [ ] **`IUsageInfoCache`.** Directly relevant to Task 2. Does
      `InMemoryUsageInfoCache` cover the whole xref index, and is there a
      persistent implementation?
- [ ] **`DecompilationMode`.** `JadxArgs.setDecompilationMode(...)` exposes
      SIMPLE / RESTRUCTURE / FALLBACK. Is a cheaper mode good enough for
      `get_class_source` in bulk-scan contexts, and does it change output enough
      to break agent parsing?
- [ ] **`jadx.api.metadata` / `ICodeAnnotation`.** Maps source offsets to code
      nodes. Could this give xrefs a **line number and enclosing method** rather
      than just a class — which is what would make Task 2's output genuinely as
      useful as JEB's?
- [ ] **Kotlin metadata support.** Many targets are Kotlin. Are there options
      recovering real parameter names, coroutine structure, or `data class`
      shapes that we are leaving switched off?
- [ ] **Resource decoding.** Anything that would resolve `@7F12038D`-style
      references more reliably, feeding Task 5.
- [ ] **The plugins API (`jadx.api.plugins`).** Input plugins and script support —
      could a plugin do whole-APK passes (e.g. find every `addJavascriptInterface`
      call site) more cheaply than our per-query scans?
- [ ] **Deobfuscation options.** We set `setDeobfuscationOn(true)` with min/max
      length 2/64. Are there newer options — mapping files, better heuristics —
      worth tuning, given alias names are a contract with the agents?

---

## Suggested order

1, then 2, is the highest-value path: Task 1 establishes the four-layer pattern
cheaply, Task 2 is what the workers actually miss. Tasks 3 and 4 get much cheaper
once Task 2 has settled the indexing question, so do not start them first. Task 5
is independent and can run in parallel. Task 6 needs at least one of 1–5 done.
Task 7 is independent throughout — and if it turns up a persistent usage cache,
it may change Task 2's design, so running it early is not wasted.

## Definition of done

- `jadx-analyzer` exposes 21 tools; the five gap rows in
  `the client's tool reference` read "yes"
- A JADX-backed a large production APK analysis resolves the c7 interface-dispatch case without a
  hand-read
- `mvn test` and `pytest mcp_server/tests/ -q` green; caller suites still
  180 / 253 / 3-scripts passing
- No new whole-APK decompilation: `search_method_by_name` stays sub-second and
  no new tool is slower than a second on a warm daemon
