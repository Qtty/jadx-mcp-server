# JADX MCP Server

A Model Context Protocol (MCP) server that provides Android APK reverse engineering capabilities using JADX (Java Android Decompiler). This server enables AI assistants like Claude to analyze APK files, decompile code, extract components, and perform security assessments on Android applications.

## Two processes

```
Claude ──stdio──►  mcp_server/jadx_mcp.py  ──HTTP──►  JVM daemon (JADX)
                   the MCP protocol          :8765     decompilation only
```

The MCP layer is Python; the JVM is a long-lived local HTTP service that owns
decompilation and nothing else. `mcp_server/daemon.py` starts and supervises it,
so a client only ever launches the Python script.

**Why not a Java MCP server.** It was one, until concurrent tool calls proved
unsurvivable. The official MCP Java SDK emits each response onto a Reactor sink
built with `Sinks.many().unicast().onBackpressureBuffer()`, which is not safe for
concurrent emission: when several tool calls finish at the same instant
`tryEmitNext` returns `FAIL_NON_SERIALIZED`, the SDK raises
`Failed to enqueue message`, nothing handles it, and the transport dies. The JVM
then stays alive with no reader thread, so the client never sees EOF and every
request hangs forever. That is [java-sdk#686](https://github.com/modelcontextprotocol/java-sdk/issues/686),
still open, and still present in the SDK's 2.0.0 source. Over HTTP, concurrency
is just concurrent requests — there is no shared sink to race on.

Two things fall out of the split:

- **The APK stays loaded between sessions.** The JVM is a daemon, not a
  per-session subprocess, so re-analysing the same APK is free rather than a
  fresh parse every run.
- **Failure is loud.** If the JVM dies, calls fail immediately and the next
  start respawns it, instead of blocking indefinitely.

## Overview

This project implements an MCP server that wraps the powerful JADX decompiler, making Android APK analysis accessible through standardized MCP tools. It's designed for security researchers, developers, and analysts who need to reverse engineer Android applications programmatically.

### Key Features

- **APK Loading & Analysis**: Load and analyze APK files for detailed inspection
- **Code Decompilation**: Get decompiled Java source code from DEX bytecode
- **Class & Method Inspection**: Browse classes, methods, and fields
- **Component Extraction**: Extract exported components from AndroidManifest.xml
- **Method Search**: Search for specific methods across all classes
- **Cross-References & Hierarchy**: Callers, callees, subtypes and overrides read
  from the dex, so dispatch is resolvable to the concrete handler
- **Sink Sweep**: Find every framework sink call site across the whole APK with no
  decompilation, keyed by impact category
- **Manifest Analysis**: Parse and analyze AndroidManifest.xml content
- **Main Activity Detection**: Identify the application's entry point
- **Resource File Analysis**: Access and analyze APK resource files (layouts, strings, etc.)
- **Bytecode Analysis**: Extract smali (bytecode) representations for low-level analysis
- **MCP Integration**: Seamless integration with AI assistants through MCP protocol

## Architecture

The project follows a layered architecture:

- **MCP Layer** (`mcp_server/jadx_mcp.py`): Exposes the analysis API as MCP tools
- **Supervisor** (`mcp_server/daemon.py`): Starts and health-checks the JVM
- **HTTP Layer** (`JadxRestController`): The daemon's API, localhost-only
- **API Layer** (`JadxApkAnalyzerAPI`): Clean API interface for APK analysis
- **Registry** (`AnalyzerRegistry`): Keeps loaded APKs warm across sessions
- **Core Layer** (`JadxAnalyzerCore`): Core JADX integration and analysis logic
- **CLI Layer** (`JadxApkAnalyzerCLI`): Interactive command-line interface
- **Model Layer**: Data structures for components

## Prerequisites

- **Java 17** or higher (built and run on 21)
- **Maven 3.6+** for building
- **Python 3.10+** for the MCP layer — see `mcp_server/requirements.txt`
- **JADX dependencies** (automatically handled by Maven)

## Installation & Setup

### 1. Clone the Repository

```bash
git clone <repository-url>
cd jadx-mcp-server
```

### 2. Build the JVM daemon

Use the provided build script — it builds the jar, runs both test suites, and
prints the MCP client config below filled in with your actual paths:

```bash
./build.sh                # build + test
./build.sh --skip-tests   # build only
```

Or build manually with Maven:

```bash
mvn clean package
```

Either way the build produces `target/jadx-mcp-server-1.0.0.jar`. That filename
is what `mcp_server/daemon.py` looks for, so it is not free to change.

### 3. Install the Python MCP layer

```bash
python -m venv .venv
.venv/bin/pip install -r mcp_server/requirements.txt
```

Any interpreter with those packages works — the launch command below just has to
point at it.

### 4. Configure your MCP client

```json
{
  "mcpServers": {
    "jadx-analyzer": {
      "command": "/path/to/jadx-mcp-server/.venv/bin/python",
      "args": ["/path/to/jadx-mcp-server/mcp_server/jadx_mcp.py"]
    }
  }
}
```

Replace `/path/to/jadx-mcp-server` with the actual path to your project
directory. Nothing launches the JVM directly — `jadx_mcp.py` starts it on first
use and reuses it afterwards.

Environment variables, all optional:

| Variable | Default | Purpose |
|---|---|---|
| `JADX_URL` | `http://127.0.0.1:8765` | Where the daemon listens. The supervisor spawns the JVM on **this** port |
| `JADX_PORT` | `8765` | Port the JVM binds (127.0.0.1 only) |
| `JADX_HEAP` | `24g` | JVM max heap |
| `JADX_TIMEOUT` | `600` | Per-request timeout, seconds |
| `JADX_ALLOW_STALE` | unset | Set to `1` to keep a running daemon even when the jar on disk is newer |
| `JADX_LOG_FILE` | per port, see below | The daemon's application log |
| `JADX_DAEMON_LOG` | per port, see below | Capture of the spawned JVM's stdout/stderr |
| `jadx.maxLoadedApks` | `1` | APKs kept warm; a large one costs ~26 GB |

#### One daemon per analysis means one log per daemon

Run one daemon per concurrent analysis — the registry holds one APK warm by default,
and two analyses sharing a daemon will evict each other's APK on every call. Give
each its own port, and its files follow:

| Port | Application log | stdout capture | Start lock |
|---|---|---|---|
| `8765` (default) | `/tmp/jadx-mcp-server.log` | `/tmp/jadx-daemon.log` | `/tmp/jadx-daemon.lock` |
| `8791` | `/tmp/jadx-mcp-server-8791.log` | `/tmp/jadx-daemon-8791.log` | `/tmp/jadx-daemon-8791.lock` |

The default port keeps the paths it always had, so anything already grepping
`/tmp/jadx-mcp-server.log` is unaffected. The cache-thrash and oversize warnings
are only discoverable in the application log, and before this they interleaved
from every daemon on the machine into one file.

```bash
JADX_PORT=8791 JADX_URL=http://127.0.0.1:8791 ./run-api.sh   # prints its own log path
```

### Running the tests

```bash
./test.sh            # both suites at once
./test.sh java       # Maven / JUnit 5 only
./test.sh python     # pytest only
```

Or by hand:

```bash
mvn -B test                                           # Java
.venv/bin/pip install -r mcp_server/requirements-dev.txt
.venv/bin/pytest mcp_server/tests/ -q                 # Python
```

Neither suite needs a running daemon. The Java tests drive `JadxAnalyzerCore`
directly; the Python tests mock the HTTP transport, so they do not even need the
jar to be built.

**Skips are expected — a green run is not a run with zero skips.**

- The committed fixture `misc/DivaApplication.apk` (1.5 MB) is what the Java
  tests analyse. A few tests additionally want the 141k-class a large production APK APK for
  perf and scale claims; that one is not in the repo, and those tests skip
  themselves with a JUnit `Assumption` when it is missing. With it hidden:
  92 run, 4 skipped, `BUILD SUCCESS`.
- `mcp_server/tests/test_concurrency.py` replays the failure that motivated the
  two-process split — many simultaneous searches and decompilations. It skips
  itself unless a daemon is already running with an APK loaded.

## Available MCP Tools

Once running, the server provides these MCP tools:

### Core Analysis Tools

- **`load_apk`** - Load and analyze an APK file
- **`get_all_classes`** - Class names, inner classes included. **Capped at 1000
  by default** — the whole APK is 141,461 names and 7.7 MB on a large one — so
  prefer `search_classes_by_keyword` when you know part of the name. Optional
  `packagePrefix` (case-insensitive prefix of the full name — 254 names / 14 KB
  for `com.example.webview`) and `limit` narrow it further; `limit: 0` is the
  escape hatch back to the whole list.
- **`get_class_source`** - Decompiled source of a class, or its outline if the
  class is large. **Budgeted at 8,192 bytes by default**, and past that budget
  this tool does not cut — it returns what the class *declares* (its fields and
  method signatures, led by an `@outline: ` line) so the caller can pick a member
  and fetch just that body. About a third of real calls land there. Cutting was
  the old behaviour and it is worse: the first 8 KB of a class is its imports and
  two of its forty methods, with nothing saying whether the one you wanted is
  among them. The budget matters because this tool dominates what a long-running
  caller pays — on a large analysis run it was a majority of every byte pulled. `maxBytes: 0`
  (or any negative) is the escape hatch back to the whole file, for when you need
  to see how several methods relate through fields and static state; an uncapped
  answer over 1 MB leads with `@oversized: ` instead.
- **`get_methods_of_class`** - A class's methods, as fully-qualified
  deobfuscated names — the same vocabulary every other tool here speaks. Capped
  at 1000 with a `limit`: a class is usually a narrow scope, but a generated one
  is not (`kotlin.collections.ArraysKt___ArraysKt` declares 1,668).
- **`get_fields_of_class`** - Get list of fields in a specific class. Capped at
  1000 with a `limit` — the widest per-class list there is, because generated
  resource tables are classes (`com.example.R.id` declares 15,260 fields and
  411 KB of them on one production APK).

### Method Analysis Tools

Every tool that takes a `methodName` takes the same names: fully-qualified
(`com.x.Y.m30110h`) or bare (`m30110h`), deobfuscated or the raw dex name (`h`)
that `get_smali_of_class` prints. So a name any tool returns can be fed back into
any tool that accepts one, which is what discovering a method and then asking
about it requires.

- **`get_method_by_name`** - Source of a method. `<init>` and `<clinit>` are
  accepted — they are the names the sink sweep and the xref tools hand out, 161
  of the 4,125 call sites in a default sweep of one production APK. `<init>`
  returns every constructor the class declares, and an overloaded name every
  overload, separated by a blank line; annotations and javadoc above a
  declaration come with it. A method jadx prints no body for (an implicit default
  constructor, a `<clinit>` folded into field initialisers) comes back as a
  one-line `//` note saying so rather than an error.
- **`search_method_by_name`** - Search for methods across all classes
- **`search_classes_by_keyword`** - Find classes whose name contains a keyword (case-insensitive)

### Cross-Reference and Hierarchy Tools

These are what make interface dispatch resolvable — following a router to the
concrete class that actually handles a URI.

- **`get_xrefs_to_class`** - Every class and method referencing a class
- **`get_xrefs_to_method`** - Every caller of a method, with the enclosing method named
- **`get_xrefs_to_field`** - Every method reading or writing a field

  All three are **capped at 1000 entries by default**, with a `limit` to raise or
  disable it. Reference counts are heavy-tailed and the heavy end is what a trace
  walks into: `kotlin.jvm.internal.Intrinsics` has 53,181 references (10.0 MB) on
  one production APK, `Intrinsics.checkNotNullParameter` 38,291 callers (7.5 MB).
  Truncation arrives as a trailing one-key `{"@truncated": "…"}` entry naming the
  real total, since a list of objects cannot carry a bare string.

  All three read a reverse index built from the dex, not jadx's usage graph,
  which four decompiler passes rewrite as classes are decompiled — so an empty
  caller list is a fact about the APK rather than about what some earlier request
  happened to touch. The index is built once per loaded APK by whichever call
  needs it first (3.1 s and 88 MB on a 141,461-class APK) and reported under
  `reverseIndex` on `/health`. See
  [§3b of the 1.5.6 assessment](docs/research/2026-jadx-1.5.6-assessment.md#3b-the-reverse-direction-closed).
- **`get_type_hierarchy`** - Supertypes, interfaces, direct subclasses and nesting.
  Resolves framework types not present in the APK, and reports `inApk` so a caller
  can tell "not declared here" from "no supertypes". All four lists are capped at
  1000 and this tool takes **no** `limit`; only `subClasses` has ever reached it
  (`java.lang.Object` is 100,019 direct subclasses and 5.6 MB).
- **`get_overrides`** - Methods overriding a method, plus the base methods it
  overrides. Accepts a declaring class that is **not** in the APK, which is the
  useful direction: `android.webkit.WebViewClient` + `shouldOverrideUrlLoading`
  returns every concrete implementation in the app. `overriddenBy` and
  `baseMethods` are capped at 1000 with a `limit`, and the cap bites on this
  tool's own use: `java.lang.Runnable` + `run` is 7,188 implementations and
  470 KB.
- **`get_callees`** - The forward direction: what a method calls. `callees` are
  hops into classes this APK declares; `external` are calls that leave it, which
  is the method's **sink list already filtered**; `callsSelf` reports direct
  recursion. `AppWebViewClient.openThirdPartyAppWithIntentScheme` returns
  `Intent.parseUri` and `Context.startActivity` in `external` without decompiling
  a line. `limit` caps each list at 1000, though it has never bitten: this
  dedupes call targets, so the widest of one APK's 872,119 methods is 541. Read from the method's dex instructions, so the answer does not depend
  on what the daemon has already decompiled — see
  [§3a of the 1.5.6 assessment](docs/research/2026-jadx-1.5.6-assessment.md#3a-getused-is-not-safe-to-read-from-a-shared-daemon)
  for why that is not automatic.

### Sink Analysis Tools

- **`find_sink_call_sites`** - Every place the APK calls a framework sink, across
  the whole APK, **without decompiling anything** (~150 ms on a 141k-class APK).
  Called with no arguments it sweeps the caller's sink catalogue and keys the
  result by `impact_category` tag (`webview_loadurl`, `js_bridge_call`,
  `intent_launch`, `file_read`, `exec`, `class_load`, … 20 tags). You can also
  name your own sinks: `"package.Class.method"` matches that method on the class
  **or any subtype** (so `android.webkit.WebView.loadUrl` also finds an in-APK
  WebView subclass that inherits it), a bare `"method"` matches any receiver, and
  a catalogue tag expands to its calls. Each call site gives the *calling* class,
  method and signature — feed it to `get_class_source` or `get_callees` to see the
  arguments. `@resolved_in_apk` names the app's own types a sink was reached
  through, which is a lead in itself. `limit` caps each sink's list (default 100,
  per sink across the catalogue; `0` uncapped). Blind to reflection and to
  wrappers that rename the operation — follow those with `get_callees`.

### Component Analysis Tools

- **`get_exported_components`** - Every component whose `android:exported`
  resolves to true. Same record shape as `get_deeplink_components`; the two
  differ only in which components they keep. Capped at 1000 with no `limit`; a
  real APK declares tens.
- **`get_deeplink_components`** - Components a link or a co-located app can reach,
  with `scheme`/`host`/`path` resolved from resource references to literals.
  Every entry has the same keys, and every `intent_filters[].data[]` entry
  carries all nine attributes, empty where the element declares none
- **`get_android_manifest`** - Get the AndroidManifest.xml content
- **`get_main_activity_class`** - Get the main launcher activity class

### Resource Analysis Tools

- **`get_all_resource_file_names`** - Resource file names, including assets and
  the manifest. Optional `pathPrefix` (case-insensitive prefix of the whole
  archive path) is the one to reach for: `res/xml` is 24 names and 814 bytes
  against 16,559 and 897 KB for the lot, and it holds `network_security_config`,
  the `FileProvider` `<paths>` and the App Links declaration. Capped at 1000 with
  a `limit`.
- **`get_resource_file`** - Get content of a specific resource file (XML layouts, strings, etc.)

### Bytecode Analysis Tools

- **`get_smali_of_class`** - Get smali (bytecode) representation of a specific class
- **`get_smali_of_method`** - Get smali (bytecode) representation of a specific method

## Demo

https://github.com/user-attachments/assets/9c93c16a-5f42-4d57-a4a2-87975735bd91

*JADX MCP Server in action analyzing Android APKs with Claude*

The video streams from GitHub's asset CDN at the link above; it is not a file in
this repository. It used to be `misc/poc.mp4`, 83 MB of an 79 MB pack, which
every clone paid for. Removed from the working tree — GitHub already hosts the
copy this section renders.

## Usage Examples

### Basic APK Analysis with Claude

1. **Load an APK**:
   ```
   Please analyze this APK file: /path/to/app.apk
   ```
   
   *Note: A test APK (DIVA) is available in the `misc/` directory for testing purposes.*

2. **Examine Classes**:
   ```
   Show me all the classes in the loaded APK
   ```

3. **Get Source Code**:
   ```
   Get the source code for the MainActivity class
   ```

4. **Security Analysis**:
   ```
   Check for exported components and potential security issues
   ```

5. **Resource Analysis**:
   ```
   Show me all resource files in the APK and get the content of strings.xml
   ```

6. **Bytecode Analysis**:
   ```
   Get the smali bytecode for the MainActivity class to analyze low-level implementation
   ```

### Driving the daemon by hand

You do not normally start the JVM yourself — `jadx_mcp.py` does it. To watch its
log on your terminal, or to poke the HTTP API with curl without an MCP client in
the way:

```bash
./run-api.sh
```

It binds `127.0.0.1:8765` (`JADX_PORT` to change it) and prints example requests.
Loading an APK is a POST with a JSON body; everything else is a GET taking
**query parameters**, never path segments, because class names contain dots and
resource paths contain slashes:

```bash
curl -XPOST http://127.0.0.1:8765/api/jadx/load-apk -H 'Content-Type: application/json' \
     -d '{"apkPath":"misc/DivaApplication.apk"}'
curl "http://127.0.0.1:8765/api/jadx/class-source?className=jakhar.aseem.diva.MainActivity"
```

## Project Structure

```
jadx-mcp-server/
├── mcp_server/                         # the MCP server (Python)
│   ├── jadx_mcp.py                     # the 23 MCP tools, over HTTP
│   ├── daemon.py                       # spawns and health-checks the JVM
│   ├── requirements.txt                # runtime deps
│   ├── requirements-dev.txt            # + pytest
│   └── tests/
│       ├── test_tools.py               # tool/parameter contract, envelopes
│       ├── test_daemon.py              # supervision
│       └── test_concurrency.py         # regression for the transport failure
├── src/main/java/com/example/jadxmcpserver/
│   ├── JadxMcpServerApplication.java    # Spring Boot entry point (HTTP daemon)
│   ├── JadxRestController.java         # the daemon's API, localhost-only
│   ├── JadxApkAnalyzerAPI.java         # Clean API interface
│   ├── JadxApkAnalyzer.java            # Legacy compatibility wrapper
│   ├── cli/
│   │   └── JadxApkAnalyzerCLI.java     # Interactive CLI interface
│   ├── core/
│   │   ├── JadxAnalyzerCore.java       # Core JADX integration
│   │   └── AnalyzerRegistry.java       # Keeps loaded APKs warm (LRU)
│   └── model/
│       └── ExportedComponent.java      # Component data structure
├── src/main/resources/
│   └── application.properties          # Spring configuration
├── src/test/                          # Java test suite
├── misc/
│   └── DivaApplication.apk            # committed test fixture (1.5 MB)
├── build.sh                           # build the jar, print the client config
├── run-api.sh                         # start the daemon in the foreground
├── test.sh                            # run both suites
└── pom.xml                            # Maven configuration
```

## Configuration

### Spring Boot Configuration

The JVM is a plain local HTTP service. Key settings in `application.properties`:

- Runs as a servlet application on `${JADX_PORT:8765}`
- Binds `127.0.0.1` only — these endpoints read arbitrary classes and resources
  out of a loaded APK and must not be reachable from the network
- Logs to `${jadx.log.file}` — `/tmp/jadx-mcp-server.log` on the default port,
  `/tmp/jadx-mcp-server-<port>.log` on any other. `DaemonLogFile` resolves it
  from `--server.port` / `-Dserver.port` / `JADX_PORT` in `main()`, before Spring
  opens the first appender
- Console logging is off (`logging.pattern.console=` is blank) — the daemon is
  detached and nothing reads its stdout

### JADX Configuration

JADX is configured with:
- Java 11 compatibility
- Full decompilation including resources
- Error handling for corrupted APKs

## Development

### Adding New Tools

A tool spans both processes, so add it bottom-up:

1. Add core logic to `JadxAnalyzerCore`
2. Expose it through `JadxApkAnalyzerAPI`
3. Add an endpoint to `JadxRestController` — query parameters, never path
   variables: class names and resource paths contain dots and slashes
4. Add a method to `JadxClient` and a `@mcp.tool()` in `mcp_server/jadx_mcp.py`
5. Update both test suites

Tool results are returned as a single JSON string (`_json(...)`) with
`structured_output=False`. That is the wire format clients were built against;
letting FastMCP infer it from the return annotation splits a list into one
content block per element and duplicates the payload in `structuredContent`.

### Testing

```bash
./test.sh
```

Both suites. See [Running the tests](#running-the-tests) for what
skips and why. `./test.sh java` and `./test.sh python` narrow it to one side.

## Security Considerations

This tool is designed for **defensive security analysis only**:

- ✅ Vulnerability assessment
- ✅ Security research
- ✅ Code review and analysis
- ✅ Malware analysis (defensive)
- ✅ Bytecode analysis for security analyzing
- ✅ Resource file inspection for hardcoded secrets
- ❌ Creating malicious modifications
- ❌ Bypassing security controls
- ❌ Unauthorized application modification

## Troubleshooting

### Common Issues

1. **Java Version**: Ensure Java 17+ is installed and `JAVA_HOME` is set (built and tested on 21)
2. **Memory Issues**: Large APKs may require additional JVM memory: `-Xmx4g`
3. **Path Issues**: Use absolute paths in Claude Desktop configuration
4. **Permission Issues**: Ensure the APK file is readable

### Debug Logging

Enable debug logging by modifying `application.properties`:

```properties
logging.level.root=DEBUG
logging.level.com.example.jadxmcpserver=TRACE
```

The daemon's own stdout/stderr go to `/tmp/jadx-daemon.log` when the Python
supervisor starts it; its application log is `/tmp/jadx-mcp-server.log`. On any
port other than 8765 both names gain a `-<port>` — see the table above. The two
catch different failures: a JVM that dies on `-Xmx` or a bad jar only ever
reaches the stdout capture, while "Port 8791 was already in use" is logged by
Spring and, with the console pattern blank, appears nowhere else. `ensure_daemon`
quotes both when a daemon fails to come up.

## License

This project uses JADX library which is licensed under Apache License 2.0. Please refer to JADX documentation for usage terms and conditions.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Add tests for new functionality
4. Submit a pull request

## Support

For issues and questions:
1. Check the troubleshooting section
2. Review JADX documentation
3. Submit an issue with detailed information including Java version, APK details, and error messages
