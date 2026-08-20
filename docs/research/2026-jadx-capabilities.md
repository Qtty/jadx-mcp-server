# What modern JADX gives us

> Task 7 of the [JEB-Plus Parity plan](../superpowers/plans/2026-08-07-jeb-plus-parity.md).
> Recommendations only — nothing here is implemented by this document.

**Method.** Every claim below was checked against the jar we actually build
against (`javap` on `jadx-core-1.5.5.jar`), against Maven Central's published
metadata, or by running a measurement probe on two real APKs:

| APK | Size | Classes | Methods |
|---|---|---|---|
| `misc/DivaApplication.apk` | 1 MB | 1,791 | 14,809 |
| a large production APK `app.apk` | 269 MB | 141,461 | 872,119 |

The probes are kept in the tree as
`src/test/java/com/example/jadxmcpserver/core/UsageIndexCostProbe.java` and
`OverrideAttrProbe.java` so the numbers can be reproduced when the jadx version
moves.

---

## Headline: the plan's "one trap" is not a trap

This is the most consequential finding, and it **cancels a design decision**
rather than informing one.

The plan's *"The one trap to measure first"* section warns that `getUseIn()` and
`AType.METHOD_OVERRIDE` may be recomputed per query, because jadx ships
`EmptyUsageInfoCache` by default, and tells Task 2 to choose between eager and
lazy indexing. **Neither is necessary.** Measured:

| Measurement | DIVA (1.8k classes) | a large production APK (141k classes) |
|---|---|---|
| `jadx.load()` | 528 ms | 11,104 ms |
| **First `getUseIn()` on a cold decompiler** | **0 ms** | **0 ms** |
| Second `getUseIn()`, same class | 0 ms | 0 ms |
| `getUseIn()` on a *different* class | 0 ms | 0 ms |
| **Walking `getUseIn()` for every class** | **0 ms** (5,509 refs) | **22 ms** (901,332 refs) |
| `MethodNode.getUseIn()` | 0 ms | 0 ms |

`UsageInfoVisitor` runs as part of `load()`, and the results live on the dex
nodes. `EmptyUsageInfoCache` means the index is not persisted *across
`JadxDecompiler` instances* — not that it is rebuilt per query. Since the daemon
holds one live decompiler per APK, we are always on the warm path.

The same holds for the override attribute:

| | DIVA | a large production APK |
|---|---|---|
| Methods carrying `METHOD_OVERRIDE` **before** `runPreDecompileStage()` | 6,260 / 14,809 | 18,516 / 60,001 (sampled) |
| Methods carrying it **after** | 6,260 | 18,516 — *identical* |
| Cost of `runPreDecompileStage()` | 72 ms | 3,309 ms |
| Cost of calling it a second time | 47 ms | 3,306 ms — *not cached* |

**Verdict — actionable, and it changes the plan:**

- Task 2 needs **no indexing strategy at all**. Read `getUseIn()` directly. Do
  not add `setUsageInfoCache(...)`, and do not call `runPreDecompileStage()`.
- Task 4 likewise: `AType.METHOD_OVERRIDE` is already attached after `load()`.
- Calling `runPreDecompileStage()` would be an active **3.3-second regression per
  call** on a large APK, buying nothing. The plan's suggestion to call it in
  `AnalyzerRegistry` on load should be dropped.
- If a class ever *is* found without the attribute,
  `RootNode.runPreDecompileStageForClass(ClassNode)` exists and measured 0 ms —
  a per-class escape hatch far cheaper than the whole-APK stage.

---

## 1. What landed after 1.5.5 — **there is a newer release**

`1.5.6`, published **2026-07-10**, is on Maven Central
(`repo1.maven.org/maven2/io/github/skylot/jadx-core/1.5.6/`). We are one release
behind; the plan and `pom.xml` both assume 1.5.5 is current.

Relevant contents:

- **Security fixes.** "Reject absolute or path to CWD for zip entries" and XML
  parser hardening against DoS. Both touch code paths we exercise on untrusted
  input — we open attacker-supplied APKs and parse their manifests.
- "Use queue instead of recursion for type updates" — removes a stack-overflow
  class of failure on deeply nested types.
- Kotlin 1.9+ enum handling and generic-cast fixes for typed fields.
- A new `jadx-analysis` module (see §8).

**Verdict — adopt.** A patch-level bump carrying zip-traversal and XML-DoS fixes
is worth taking on a server whose whole job is parsing hostile APKs. Do it as its
own commit with the full suite green, separately from the parity tools, so a
decompiler-output regression is bisectable. Note the release also changes
decompilation output in places, so re-run the tests that assert on source text.

> **Superseded by measurement.** This section was written from release notes.
> [`2026-jadx-1.5.6-assessment.md`](2026-jadx-1.5.6-assessment.md) checks it
> against the jars and both APKs: the API diff is purely additive, the suite is
> 60/60 green on 1.5.6 with no source changes, and the output change is 7 of 60
> classes — all loop restructuring getting *better*. It also finds three things
> the notes did not mention: `getUsed()`/`getUnresolvedUsed()` (forward call
> edges, and sinks for free at 104 ms whole-APK) and a working `jadx-analysis`
> call graph at 413 ms. **Adopted** — `pom.xml` is on 1.5.6. The output-change
> figure above is the DIVA one and is too small: on a large production APK the dominant change
> is 28 methods that 1.5.5 could not decompile at all becoming readable Java.

## 2. `ICodeCache` — exists, but no persistent implementation in core

`JadxArgs.setCodeCache(ICodeCache)` is real. The interface is small:
`add/remove/get/getCode/contains`, keyed by a raw `String`.

`jadx-core` 1.5.5 ships only `InMemoryCodeCache`, `NoOpCodeCache` and
`DelegateCodeCache`. **There is no disk-backed implementation in core** — the
`DiskCodeCache` used by jadx-gui lives in the GUI module, which we do not depend
on and which drags in a desktop toolchain.

Invalidation is the caller's problem: the interface has no versioning or
staleness hook, so a disk cache would have to be keyed on APK hash *plus* jadx
version *plus* the `JadxArgs` that affect output (notably deobfuscation settings
and `DecompilationMode`) — get that wrong and the daemon serves stale source that
silently disagrees with the APK.

**Verdict — do not adopt now.** The daemon already keeps APKs warm in
`AnalyzerRegistry`, which captures most of the benefit. Writing our own
`ICodeCache` against a local disk is a real project with a correctness footgun,
justified only if daemon restarts become frequent enough to hurt. Revisit if that
changes.

## 3. `IUsageInfoCache` — exists, and we do not need it

Package is `jadx.api.usage` (the plan does not name it). 1.5.5 ships
`IUsageInfoCache`, `EmptyUsageInfoCache` (the default) and
`InMemoryUsageInfoCache`. There is **no persistent implementation**.

`IUsageInfoData` exposes `apply()`, `applyForClass(ClassNode)` and
`visitUsageData(IUsageInfoVisitor)`.

**Verdict — do not adopt.** Per the headline measurement, usage info is already
live after `load()` within a single decompiler instance, which is the only
lifetime the daemon has. `InMemoryUsageInfoCache` would only help across
instances of the same APK, which the registry already prevents. Adding it would
cost memory and buy nothing measurable.

## 4. `DecompilationMode` — real, but not worth wiring up

`JadxArgs.setDecompilationMode(...)` accepts `AUTO` (default), `RESTRUCTURE`,
`SIMPLE`, `FALLBACK`.

**Verdict — do not adopt.** The cheaper modes change output structure
substantially: `SIMPLE` emits unstructured control flow and `FALLBACK` emits
something close to annotated smali. Every worker agent in `the analysis client`
reads `get_class_source` output as ordinary Java to follow branch conditions and
validators; degrading that to save decompile time trades the exact quality the
analysis depends on. We already have `get_smali_of_class`/`get_smali_of_method` when
a caller genuinely wants the low-level view, and that is a per-call choice rather
than a global mode. If bulk scanning ever needs a cheap mode, it should be a
separate opt-in tool, never the default for `get_class_source`.

## 5. `jadx.api.metadata` / `ICodeAnnotation` — **the most promising unused API**

`ICodeInfo.getCodeMetadata()` returns `ICodeMetadata`, which maps source offsets
to code nodes:

```java
ICodeAnnotation getAt(int pos);
ICodeNodeRef    getNodeAt(int pos);          // enclosing class/method at an offset
ICodeAnnotation searchUp(int pos, ICodeAnnotation.AnnType type);
Map<Integer, Integer> getLineMapping();      // offset -> source line
Map<Integer, ICodeAnnotation> getAsMap();
```

`ICodeInfo.hasMetadata()` gates it. Annotation types include `NodeDeclareRef`,
`InsnCodeOffset`, `VarNode`/`VarRef`.

This is exactly what the plan asks about: `getNodeAt(offset)` plus
`getLineMapping()` turns an xref from "class X references this" into "class X,
method `m`, line N".

**Verdict — worth adopting, but *after* Task 2 ships, and scoped carefully.**
The caveat is cost: metadata comes from decompiled output, so resolving a line
number means decompiling the referencing class — precisely the whole-APK
decompilation the plan forbids. `getUseIn()` already yields the referencing
`MethodNode` for free, which covers most of the value at zero cost. Recommend:
ship Task 2 on `getUseIn()` alone (class + method, no line), then consider a
separate opt-in argument that resolves line numbers for a *bounded* set of
already-decompiled results. Never resolve lines for a whole result set eagerly.

## 6. Kotlin metadata — a plugin we are not using

`jadx-kotlin-metadata` is a published artifact, at 1.5.6 on Maven Central, and it
is **not** in our `pom.xml`. In core, `JadxArgs.setUseKotlinMethodsForVarNames(...)`
and `setAllowInlineKotlinLambda(boolean)` exist, plus a
`jadx.core.dex.visitors.kotlin.ProcessKotlinInternals` pass.

**Verdict — investigate with a real measurement, likely adopt.** Many targets are
Kotlin, and recovering real parameter names directly improves how well an agent
can read a router. The blocking question is one the agents care about
disproportionately: **the plugin renames things, and alias names are a contract**
(`get_class_source` rejects names it does not recognise). Before adopting, run
the same APK with and without the plugin and diff the alias name sets; if names
move, every cached trace and every recorded finding referencing an old name goes
stale. Treat as its own task with its own before/after comparison, not a
drive-by dependency addition.

## 7. Resource decoding — no new lever found for Task 5

Nothing in 1.5.5 or the 1.5.6 notes offers a materially better path for resolving
`@7F12038D`-style references than what jadx already does when it decodes
resources. 1.5.6 includes general "resource parsing" improvements and AAB/APKS
handling, but nothing that specifically targets manifest attribute resolution.

**Verdict — no upgrade dependency for Task 5.** Task 5 should resolve references
itself by reading the resource table through the existing `getResources()` /
`ResContainer` path, exactly as the plan scopes it. Do not block Task 5 on a
jadx feature; the capability is not coming from upstream.

## 8. Plugins API and the new `jadx-analysis` module

`jadx.api.plugins` is present in 1.5.5 (`JadxPlugin`, `JadxPluginContext`,
`JadxPluginInfo`, an events system, `CustomResourcesLoader`), and
`JadxDecompiler.getPluginManager()` is reachable. Input plugins for aab / apkm /
apks / xapk / smali / raung are published separately, as is `jadx-script-plugin`.

Newly published with 1.5.6: **`jadx-analysis`**, containing
`jadx.analysis.callgraph` — `CallGraph`, `CallGraphBuilder`, `CallGraphNode`,
`CallGraphEdge`, a `JadxCallGraph` plugin entry point, DOT and JSON exporters,
and an `api` sub-package (`ICallGraph`, `ICallGraphBuilder`, `ICallGraphNode`,
`ICallGraphEdge`). 22 classes, ~30 KB.

**Verdict — `jadx-analysis` is the single most interesting follow-up.** This
codebase already has a hand-rolled `generateCallGraphForMethod` in
`JadxAnalyzerCore` that finds callers by *regex-matching decompiled source* —
`methodCallsTarget()` literally searches for `methodName(` in class text, and
`findCallersRecursively()` calls `getCode()` on every class, which is the same
decompile-the-world pattern that cost 71.6 s in `searchMethodByName`. A
maintained, dex-level call graph would let that be deleted rather than fixed.
Worth a task of its own once 1.5.6 is adopted (§1). Note it is a new module at
version 1.5.6 only — no 1.5.5 fallback — so it depends on the upgrade landing
first.

> **Superseded.** The regex implementation is deleted, and `jadx-analysis` was
> *not* adopted to replace it: no REST endpoint or MCP tool ever reached the old
> code, and the caller's workers ask only one-hop questions, which
> `get_xrefs_to_method`, `get_callees` and `find_sink_call_sites` already answer.
> A transitive graph would have been a new capability with no caller. Reasoning
> and measurements in
> [`2026-jadx-1.5.6-assessment.md` §C](2026-jadx-1.5.6-assessment.md#c-replace-generatecallgraphformethod-with-jadx-analysis).

The general plugin API is **not** worth adopting for whole-APK passes: our
per-query dex scans already run in well under a second (`searchMethodByName` is
~0.08 s), so there is no cost problem for a plugin to solve.

## 9. Deobfuscation options — more knobs than we use

We set `setDeobfuscationOn(true)` with min/max length 2/64. Also available:
`setDeobfuscationWhitelist(List<String>)`, `setDeobfuscationForceSave`,
`setGeneratedRenamesMappingFile(File)` / `...MappingFileMode`,
`setUserRenamesMappingsPath(Path)` / `...Mode`, `setAliasProvider(IAliasProvider)`,
`setRenameCondition(IRenameCondition)`, and the `RenameEnum` flag set
(`setRenameValid`, `setRenamePrintable`, `setRenameCaseSensitive`).

**Verdict — one item worth adopting: mapping-file persistence.** Generated alias
names are a contract with the agents, and today they are regenerated on every
load. If jadx's naming is not deterministic across versions or runs, a finding
recorded as `p000.CafHttpClient.request` could fail to resolve after a daemon
restart or a jadx bump — silent, and expensive to debug from the agent side.
Writing the generated mapping to a file next to the APK
(`setGeneratedRenamesMappingFile`) and reloading it via `setUserRenamesMappingsPath`
would pin names across restarts. **First establish whether the problem is real:**
load the same APK twice and diff the alias sets. If names are already stable,
this is unnecessary. `IAliasProvider` / `IRenameCondition` are extension points
we have no current need for.

---

## Summary

| # | Item | In 1.5.5? | Verdict |
|---|---|---|---|
| — | Usage info / override attr already built by `load()` | yes | **Cancels Task 2's and Task 4's indexing decision** |
| 1 | jadx **1.5.6** (zip-traversal + XML-DoS fixes) | no — upgrade | **Adopt**, as its own commit |
| 2 | `ICodeCache` | yes; no disk impl in core | Do not adopt now |
| 3 | `IUsageInfoCache` (`jadx.api.usage`) | yes | Do not adopt — no benefit for a warm daemon |
| 4 | `DecompilationMode` | yes | Do not adopt — degrades what agents read |
| 5 | `jadx.api.metadata` line/method resolution | yes | Adopt later, bounded; not needed for Task 2 |
| 6 | `jadx-kotlin-metadata` plugin | separate artifact | Investigate — must diff alias names first |
| 7 | Resource decoding improvements | n/a | Nothing upstream; Task 5 resolves it itself |
| 8 | **`jadx-analysis` call graph** | no — 1.5.6 only | ~~Most interesting follow-up~~ **Not adopted** — the regex call graph was deleted outright instead |
| 9 | Deobfuscation mapping files | yes | Adopt if alias names prove unstable across loads |

### Recommended follow-up tasks

1. **Upgrade to 1.5.6** — prerequisite for everything below. Verified drop-in;
   see the assessment doc.
2. **`get_callees`** — forward call edges via `getUsed()`, completing the pair
   `get_xrefs_to_method` started. Smallest change, highest value.
3. **`find_sink_call_sites`** — whole-APK sink discovery via
   `getUnresolvedUsed()` in ~104 ms, no decompilation.
4. **Replace `generateCallGraphForMethod`** with `jadx-analysis`, deleting the
   regex-over-decompiled-source implementation.
5. **Check alias-name stability** across two loads of one APK — cheap, and it
   decides both §6 and §9.

Items 2–4 are specified in
[`2026-jadx-1.5.6-assessment.md`](2026-jadx-1.5.6-assessment.md#recommended-features).
