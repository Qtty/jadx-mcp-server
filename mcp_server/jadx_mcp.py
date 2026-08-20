#!/usr/bin/env python
"""MCP server for jadx.

This process owns the MCP protocol; a long-lived JVM owns decompilation. They
talk over local HTTP.

It is deliberately not a Java MCP server. The official MCP Java SDK emits each
response onto a Reactor sink built with Sinks.many().unicast().onBackpressureBuffer(),
which is not safe for concurrent emission: when several tool calls finish at the
same instant, tryEmitNext returns FAIL_NON_SERIALIZED, the SDK raises
"Failed to enqueue message", nobody handles it, and the whole transport dies.
The JVM then stays alive with no reader thread, so the client never sees EOF and
every in-flight and future request hangs forever. That is upstream java-sdk
issue #686, still open, and still present in the SDK's 2.0.0 source.

Here, concurrency is just parallel HTTP requests against a servlet container.
There is no shared outbound sink, so the failure mode cannot occur.

Tool names, parameter names and return shapes are a contract with the client
agents that consume this server. Do not change them.
"""

from __future__ import annotations

import json
import os
from typing import Any

import httpx
from mcp.server.fastmcp import FastMCP

BASE = os.environ.get("JADX_URL", "http://127.0.0.1:8765")
# Generous: a cold decompile of a single large class can take a while. The
# daemon is local, so the only thing this guards against is a wedged JVM.
TIMEOUT = float(os.environ.get("JADX_TIMEOUT", "600"))


class JadxClient:
    """Thin typed wrapper over the daemon's REST API.

    The daemon wraps every result in a single-key envelope ("source", "classes",
    "results", ...); callers of this class get the bare value, which is what the
    MCP tools have always returned.
    """

    def __init__(self, base: str = BASE, transport: httpx.BaseTransport | None = None):
        self._http = httpx.Client(base_url=base, timeout=TIMEOUT, transport=transport)

    def _get(self, path: str, key: str, **params: Any) -> Any:
        return self._unwrap(self._http.get(f"/api/jadx/{path}", params=params), key)

    def _post(self, path: str, key: str | None, json: dict) -> Any:
        return self._unwrap(self._http.post(f"/api/jadx/{path}", json=json), key)

    @staticmethod
    def _unwrap(r: httpx.Response, key: str | None) -> Any:
        try:
            body = r.json()
        except ValueError:
            r.raise_for_status()
            raise RuntimeError(f"non-JSON response from jadx daemon: {r.text[:200]}")
        # The daemon reports analysis failures as 400 + {"error": ...}; surface the
        # message rather than an opaque HTTP status.
        if isinstance(body, dict) and "error" in body:
            raise RuntimeError(body["error"])
        r.raise_for_status()
        return body if key is None else body[key]

    def health(self) -> dict:
        return self._get("health", None)

    def load_apk(self, apkPath: str) -> dict:
        return self._post("load-apk", None, {"apkPath": apkPath})

    def get_all_classes(
        self, packagePrefix: str | None = None, limit: int | None = None
    ) -> list[str]:
        # Neither parameter is sent when unset: the daemon reads an absent
        # packagePrefix as "every class" and an absent limit as "uncapped", which
        # is what this call has always returned. Sending "" for either would be
        # a different request.
        params: dict[str, Any] = {}
        if packagePrefix:
            params["packagePrefix"] = packagePrefix
        if limit is not None:
            params["limit"] = limit
        return self._get("classes", "classes", **params)

    def get_class_source(self, className: str, maxBytes: int | None = None) -> str:
        return self._get("class-source", "source",
                         **self._bounded({"className": className}, maxBytes))

    # maxBytes is to a string result what limit is to a list one, and it is
    # omitted rather than sent empty for the same reason: the daemon reads an
    # absent maxBytes as its default budget, and 0 is a real value meaning
    # "uncapped". Hence `is not None` rather than truthiness -- 0 widens.
    @staticmethod
    def _bounded(params: dict[str, Any], maxBytes: int | None) -> dict[str, Any]:
        if maxBytes is not None:
            params["maxBytes"] = maxBytes
        return params

    def get_methods_of_class(
        self, className: str, limit: int | None = None
    ) -> list[str]:
        return self._get("class-methods", "methods",
                         **self._limited({"className": className}, limit))

    def get_fields_of_class(
        self, className: str, limit: int | None = None
    ) -> list[str]:
        return self._get("class-fields", "fields",
                         **self._limited({"className": className}, limit))

    # limit is omitted rather than sent empty when unset, so the daemon applies
    # its own default; 0 is a real value meaning "uncapped", which is why the
    # check is `is not None` rather than truthiness.
    @staticmethod
    def _limited(params: dict[str, Any], limit: int | None) -> dict[str, Any]:
        if limit is not None:
            params["limit"] = limit
        return params

    def get_method_by_name(
        self, className: str, methodName: str, signature: str | None = None
    ) -> str:
        return self._get("method-source", "source",
                         **self._named(className, methodName, signature))

    # signature is omitted rather than sent empty when unset, for the same
    # reason limit is: the daemon reads an absent signature as "every overload
    # of this name", and "" would be a signature that matches nothing.
    @staticmethod
    def _named(
        className: str, methodName: str, signature: str | None
    ) -> dict[str, Any]:
        params: dict[str, Any] = {"className": className, "methodName": methodName}
        if signature:
            params["signature"] = signature
        return params

    # Both searches omit limit when unset rather than sending "": the daemon
    # reads an absent limit as "uncapped", which is what these calls have always
    # returned. limit=0 is a real value meaning the same thing explicitly, so the
    # check is `is not None` rather than truthiness -- as in get_all_classes.

    def search_method_by_name(
        self, methodName: str, limit: int | None = None
    ) -> dict[str, list[str]]:
        params: dict[str, Any] = {"methodName": methodName}
        if limit is not None:
            params["limit"] = limit
        return self._get("search-method", "results", **params)

    def search_classes_by_keyword(
        self, keyword: str, limit: int | None = None
    ) -> list[str]:
        params: dict[str, Any] = {"keyword": keyword}
        if limit is not None:
            params["limit"] = limit
        return self._get("search-classes", "classes", **params)

    def get_xrefs_to_class(
        self, className: str, limit: int | None = None
    ) -> list[dict]:
        return self._get("xrefs-to-class", "xrefs",
                         **self._limited({"className": className}, limit))

    def get_xrefs_to_method(
        self,
        className: str,
        methodName: str,
        signature: str | None = None,
        limit: int | None = None,
    ) -> list[dict]:
        return self._get("xrefs-to-method", "xrefs",
                         **self._limited(self._named(className, methodName, signature),
                                         limit))

    def get_xrefs_to_field(
        self, className: str, fieldName: str, limit: int | None = None
    ) -> list[dict]:
        return self._get("xrefs-to-field", "xrefs",
                         **self._limited(
                             {"className": className, "fieldName": fieldName}, limit))

    def get_callees(
        self,
        className: str,
        methodName: str,
        signature: str | None = None,
        limit: int | None = None,
    ) -> dict:
        # The envelope key and one of the inner keys are both "callees"; the
        # unwrap is one level, so this returns the whole object.
        return self._get("callees", "callees",
                         **self._limited(self._named(className, methodName, signature),
                                         limit))

    def find_sink_call_sites(
        self, sinks: list[str] | None = None, limit: int | None = None
    ) -> dict:
        # Both parameters are omitted rather than sent empty when unset: the
        # daemon reads an absent "sinks" as "run the default catalogue" and an
        # absent "limit" as "use the default cap", and sending "" for either
        # would be a different request. limit=0 is a real value (uncapped), so
        # the check is `is not None` rather than truthiness.
        params: dict[str, Any] = {}
        if sinks:
            params["sinks"] = ",".join(sinks)
        if limit is not None:
            params["limit"] = limit
        return self._get("sink-call-sites", "sinkCallSites", **params)

    def get_type_hierarchy(self, className: str) -> dict:
        return self._get("type-hierarchy", "hierarchy", className=className)

    def get_overrides(
        self,
        className: str,
        methodName: str,
        signature: str | None = None,
        limit: int | None = None,
    ) -> dict:
        return self._get("overrides", "overrides",
                         **self._limited(self._named(className, methodName, signature),
                                         limit))

    def get_exported_components(self) -> list[dict]:
        return self._get("components", "components")

    def get_deeplink_components(self) -> list[dict]:
        return self._get("deeplink-components", "components")

    def get_android_manifest(self) -> str:
        return self._get("manifest", "manifest")

    def get_main_activity_class(self) -> str:
        return self._get("main-activity", "mainActivity")

    def get_all_resource_file_names(
        self, pathPrefix: str | None = None, limit: int | None = None
    ) -> list[str]:
        params: dict[str, Any] = {}
        if pathPrefix:
            params["pathPrefix"] = pathPrefix
        return self._get("resources", "resources", **self._limited(params, limit))

    def get_resource_file(self, fileName: str, maxBytes: int | None = None) -> str:
        return self._get("resource", "content",
                         **self._bounded({"fileName": fileName}, maxBytes))

    def get_smali_of_class(self, className: str, maxBytes: int | None = None) -> str:
        return self._get("class-smali", "smali",
                         **self._bounded({"className": className}, maxBytes))

    def get_smali_of_method(
        self, className: str, methodName: str, signature: str | None = None
    ) -> str:
        return self._get("method-smali", "smali",
                         **self._named(className, methodName, signature))


def _json(value: Any) -> str:
    """Serialise a tool result the way the old Java server did.

    Every tool is annotated `-> str` and returns this, which produces exactly one
    text block holding JSON. That is what the previous Spring AI server emitted
    (its DefaultToolCallResultConverter JSON-serialised everything, including
    plain strings), and every client was written against that shape.

    Without it, FastMCP infers the shape from the annotation and splits a list
    into one content block per element -- get_all_classes on a large APK became
    one content block per class plus a structuredContent copy, substantially
    more bytes on the wire for the same data.
    """
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def build_server(client: JadxClient | None = None) -> FastMCP:
    c = client or JadxClient()
    mcp = FastMCP("jadx-analyzer")

    # Parameter names are part of the contract: callers pass them by name, e.g.
    # {"className": "com.example.app.MainActivity"}. camelCase is deliberate.

    @mcp.tool(structured_output=False)
    def load_apk(apkPath: str) -> str:
        """Load and analyze an APK file"""
        return _json(c.load_apk(apkPath))

    @mcp.tool(structured_output=False)
    def get_all_classes(packagePrefix: str | None = None, limit: int | None = None) -> str:
        """List class names in the loaded APK, inner classes included.

        Capped at 1000 names by default. The whole list on a large APK is many
        thousands of names and megabytes of text, which would land in your
        context entire; when the cap bites, the last entry says how many there
        really are. Reach for search_classes_by_keyword whenever you know any
        part of the name you are after -- it is the narrow path and it matches
        case-insensitively anywhere in the name, not just at the start.

        When you do need to enumerate, narrow it:
          packagePrefix -- keep only names starting with this, case-insensitive.
              Matched against the whole name, so "com.acme.ui" and
              "com.acme.ui.Deep" both work.
          limit -- at most this many names. Unset means the default cap of 1000.
              0 and any negative number mean UNCAPPED -- that is the escape
              hatch, not an error, so limit=-5 returns all of them. If your
              limit is computed, know that 0 widens rather than narrows.

        A list cut short ends with one extra entry beginning "@truncated: " that
        names the real total. ANY answer over 1 MB then ends with "@oversized: "
        as well, cut or not -- the cap is on the COUNT, so a big explicit limit
        still hands you a file dump. Every entry that does not start with '@' is
        a class name."""
        return _json(c.get_all_classes(packagePrefix, limit))

    @mcp.tool(structured_output=False)
    def get_class_source(className: str, maxBytes: int | None = None) -> str:
        """Get the decompiled source of a class, or its outline if it is large.

        Budgeted at 8,192 bytes of answer by default, and past that budget this
        tool does NOT cut -- it returns what the class DECLARES instead: its
        fields and its method signatures, led by a line beginning "@outline: ".
        About a third of real calls land there. That is not an error and not
        something to retry: pick the member you want and call
        get_method_by_name(className, methodName) for its body.

        Cutting was the old behaviour and it is worse than the outline. The
        first 8 KB of a decompiled class is its imports and two of its forty
        methods, with nothing in the answer saying whether the method you were
        tracing was among them. An outline names all forty.

        The budget exists because this tool dominates what a long-running
        caller pays. What a tool returns stays in the caller's context and is
        charged again on each of its later turns, so a whole class fetched
        early is re-read many times over, while the one method the caller
        wanted is a fraction of the size. The outline answers the question the
        caller actually had -- which method -- without the tax.

        maxBytes -- unset means that 8 KB budget; 0 and any negative number
            mean UNCAPPED and return the whole file, so 0 WIDENS, exactly as
            limit does on the list tools. Use it when you need to see how
            several methods relate through fields and static state, which is
            the one question an outline cannot answer.

        An UNCAPPED answer over 1,048,576 bytes leads with "@oversized: "
        instead, so maxBytes=0 is loud in the same way limit=0 is: a generated
        resource table can be several MiB of field declarations. This is the
        one tool whose answer moves with what the daemon did earlier, so its
        exact size is not fixed. A class declaring more members than the budget
        holds gets an outline that is itself cut, and then carries
        "@truncated: " under the outline line, because both facts are true."""
        return _json(c.get_class_source(className, maxBytes))

    @mcp.tool(structured_output=False)
    def get_methods_of_class(className: str, limit: int | None = None) -> str:
        """List a class's methods as fully-qualified names. The names are the
        deobfuscated ones the rest of these tools use, so any entry can be fed
        straight back into get_method_by_name, get_callees, get_xrefs_to_method,
        get_overrides or get_smali_of_method -- whole or with the class prefix
        stripped off.

        A class name spells an inner class with a "." like everything else here.
        Where that would name two different classes at once, these tools report
        the "$" spelling instead -- Outer$Inner -- and both spellings resolve.

        Capped at 1000 by default. A class is usually a narrow scope, but a
        generated one is not: a Kotlin stdlib facade can declare well over a
        thousand methods. Unset means the cap; 0 or any negative number means
        UNCAPPED. A cut-short list ends with one extra entry beginning
        "@truncated: " naming the real total, and any answer over 1 MB ends
        with "@oversized: " as well, cut or not."""
        return _json(c.get_methods_of_class(className, limit))

    @mcp.tool(structured_output=False)
    def get_fields_of_class(className: str, limit: int | None = None) -> str:
        """List a class's fields as "type name", sorted by name. The names are
        read from the dex and are the ones the rest of these tools use, so an
        entry can be fed straight back into get_xrefs_to_field -- whole, or with
        the type stripped off.

        Capped at 1000 by default -- this is the widest per-class list there is,
        because generated resource tables are classes: an R.id table on a large
        APK can declare tens of thousands of fields. Unset means the cap; 0 or
        any negative number means UNCAPPED. A cut-short list ends with one extra
        entry beginning "@truncated: " naming the real total, and any answer over
        1 MB ends with "@oversized: " as well, cut or not."""
        return _json(c.get_fields_of_class(className, limit))

    @mcp.tool(structured_output=False)
    def get_method_by_name(
        className: str, methodName: str, signature: str | None = None
    ) -> str:
        """Get the source code of a specific method. methodName may be bare or
        fully-qualified, deobfuscated or the raw dex name. "<init>" gives every
        constructor the class declares and "<clinit>" the static initializer --
        the names find_sink_call_sites and get_callees report them under. An
        overloaded name likewise returns every overload, separated by a blank
        line, and the response says so on its LEADING line, under "@ambiguous",
        naming the count and every signature; pass signature -- the "(args)ret"
        descriptor, or the whole signature string an xref or callee entry
        reports -- to ask about one.

        Capped at 1,048,576 BYTES of result -- UTF-8, JSON escaping included,
        which is what goes on the wire -- with no way to raise it. The widest
        single methods and overload sets seen stay well under it, so nothing has
        reached it, but the reason is that these answers are bounded rather than
        that they are small. A cut answer would lead with a "@truncated: " line."""
        return _json(c.get_method_by_name(className, methodName, signature))

    @mcp.tool(structured_output=False)
    def search_method_by_name(methodName: str, limit: int | None = None) -> str:
        """Search for methods across all classes. Returns a map of class name ->
        the matching method names it declares.

        methodName matches ANYWHERE in the name, not just the whole of it, so a
        fragment finds the method -- but a short fragment is a wide question, not
        a narrow one. Two characters is the minimum; a shorter one comes back as
        a single "@rejected" entry saying why, because on a large APK it means
        every method in the APK, a multi-megabyte dump.

        Clearing that floor does not make a query narrow: a common fragment like
        "in" is still a multi-megabyte answer. So the result is capped at 1000
        CLASSES by default, which is the axis this map is keyed by. Unset means
        that cap; 0 and any negative number mean UNCAPPED, which is the escape
        hatch rather than an error.

        A capped result carries a "@truncated" entry giving the real class and
        method totals, and any result over 1 MB carries an "@oversized" entry as
        well, capped or not -- the cap is on CLASSES and the payload is their
        methods, so a wide match is a file dump. Both arrive as a one-element
        list holding a string that starts with the marker name. Every other key
        is a class name."""
        return _json(c.search_method_by_name(methodName, limit))

    @mcp.tool(structured_output=False)
    def search_classes_by_keyword(keyword: str, limit: int | None = None) -> str:
        """Search for classes whose name contains a keyword (case-insensitive).
        This is the narrow alternative to get_all_classes: it matches anywhere in
        the fully-qualified name, so a fragment of a name you half-know is enough.

        The keyword must be at least 2 characters. One character is not a lookup
        -- it matches most of the APK, as wide as get_all_classes itself -- and
        comes back as a single "@rejected" entry saying so. To enumerate a
        subtree instead, use get_all_classes with a packagePrefix.

        Capped at 1000 names by default. That is set so the queries worth making
        come back whole -- a specific keyword like "WebView" or "Router" -- while
        one as broad as "com" does not. Unset means the cap; 0 and any negative
        number mean UNCAPPED.

        A cut-short list ends with one extra entry beginning "@truncated: "
        naming the real total, and any answer over 1 MB ends with "@oversized: "
        as well, cut or not. Every entry that does not start with '@' is a class
        name."""
        return _json(c.search_classes_by_keyword(keyword, limit))

    @mcp.tool(structured_output=False)
    def get_xrefs_to_class(className: str, limit: int | None = None) -> str:
        """Find all references to a class. Every entry has the same three keys --
        {"className", "methodName", "signature"} -- so one access pattern works
        on all of them. A reference no method encloses (a superclass or an
        implemented interface, which is a declaration rather than an instruction)
        keeps both method keys and leaves them EMPTY STRINGS rather than dropping
        them. Read an empty methodName as "this class names yours in its own
        declaration", and go on with get_type_hierarchy rather than looking for a
        call site. Expect it: measured over every class of a large APK,
        class-level references are a sizeable minority of all entries, spread
        across thousands of classes.

        Capped at 1000 entries by default. Reference counts are heavy-tailed and
        the heavy end is reachable from an ordinary question: a hot utility like
        kotlin.jvm.internal.Intrinsics can have tens of thousands of references
        and many MB of them. Unset means the cap; 0 or any negative number means
        UNCAPPED.

        A cut-short list ends with one extra entry {"@truncated": "..."} naming
        the real total, and any answer over 1 MB then ends with
        {"@oversized": "..."} as well -- cut or not, since a big explicit limit
        is still a multi-megabyte answer. Every real entry has a "className", so an entry without one
        is bookkeeping. If the target turns out to be a utility rather than the
        thing you were tracing, ask about the caller you came from instead of
        raising the limit."""
        return _json(c.get_xrefs_to_class(className, limit))

    @mcp.tool(structured_output=False)
    def get_xrefs_to_method(
        className: str,
        methodName: str,
        signature: str | None = None,
        limit: int | None = None,
    ) -> str:
        """Find all callers of a method. Each entry gives the calling class,
        method and signature. An overloaded name covers every overload and the response says so under "@ambiguous"; pass signature -- the "(args)ret" descriptor, or the whole signature string an xref or callee entry reports -- to ask about one. The notice is a trailing one-key entry, so
        an entry without a "className" is bookkeeping rather than a caller.

        Capped at 1000 callers by default -- a hot utility method such as
        Intrinsics.checkNotNullParameter can have tens of thousands of callers
        and many MB of them. Unset means the cap; 0 or any
        negative number means UNCAPPED. Truncation arrives as a trailing
        {"@truncated": "..."} entry naming the real total, and any answer over
        1 MB carries a trailing {"@oversized": "..."} as well, cut or not."""
        return _json(c.get_xrefs_to_method(className, methodName, signature, limit))

    @mcp.tool(structured_output=False)
    def get_xrefs_to_field(
        className: str, fieldName: str, limit: int | None = None
    ) -> str:
        """Find all methods that read or write a field. fieldName may be the
        whole "type name" entry get_fields_of_class prints, the bare name, or
        the raw dex name. A name that means two fields of one class covers both
        and the response says so under "@ambiguous"; pass the whole "type name"
        entry to ask about one.

        Capped at 1000 by default -- a hot field like kotlin.Unit.INSTANCE can
        have thousands of users and over a MB of them. Unset means the cap; 0 or
        any negative number means UNCAPPED. Truncation is a trailing
        {"@truncated": "..."} entry naming the
        real total, and any answer over 1 MB carries a trailing
        {"@oversized": "..."} as well, cut or not."""
        return _json(c.get_xrefs_to_field(className, fieldName, limit))

    @mcp.tool(structured_output=False)
    def get_callees(
        className: str,
        methodName: str,
        signature: str | None = None,
        limit: int | None = None,
    ) -> str:
        """Find the methods a method calls -- the forward direction, for sink
        tracing. "callees" are calls into classes this APK declares, i.e. the
        next hop to follow; "external" are calls that leave the APK into the
        framework or an SDK, which is the method's sink list already filtered.
        "callsSelf" reports direct recursion. An overloaded name covers every overload and the response says so under "@ambiguous"; pass signature -- the "(args)ret" descriptor, or the whole signature string an xref or callee entry reports -- to ask about one.

        limit caps "callees" and "external" separately and defaults to 1000. It
        is a backstop rather than a live constraint: even the widest methods
        resolve to a few hundred entries, because this dedupes call targets
        rather than counting call instructions. 0 or any
        negative number means UNCAPPED. If a list is cut short it ends with a
        {"@truncated": "..."} entry naming that list's real total, and any list
        over 1 MB ends with {"@oversized": "..."} as well, cut or not. The whole
        response is weighed too, under a top-level "@oversized" key, since two
        lists each under the line are a response over it."""
        return _json(c.get_callees(className, methodName, signature, limit))

    @mcp.tool(structured_output=False)
    def find_sink_call_sites(
        sinks: list[str] | None = None, limit: int | None = None
    ) -> str:
        """Find every place the APK calls a framework sink, across the whole
        APK, without decompiling anything (~150 ms even on a very large APK).

        Call it with no arguments to sweep the built-in sink catalogue: the
        result is keyed by impact_category tag -- webview_loadurl,
        html_injection_loaddata, js_bridge_call, webview_settings,
        token_exfil_via_webview, file_chooser_abuse, intent_launch,
        intent_parse, parcelable_extra_injection, file_read, file_write,
        content_provider_write, zip_extract, exec, native_load, class_load,
        content_share, network_request, crypto_misuse, account_authenticator.
        intent_launch covers Fragment.startActivity/startActivityForResult as
        well as Context's and Activity's -- a Fragment is neither, so its
        launches are a separate surface.

        A sink you name yourself is one of:
          "package.Class.method" -- matches that method on that class OR ANY
              SUBTYPE, so android.webkit.WebView.loadUrl also finds an in-APK
              WebView subclass that inherits it. Constructors are ".<init>".
          "method" -- matches that name on any receiver; wide, use knowingly.
          a catalogue tag -- expands to the calls above.
        Each key comes back exactly as you asked for it, present even when it
        matched nothing, so "swept, found none" is distinguishable.

        Each call site gives the CALLING class, method and signature -- feed it
        to get_class_source or get_callees to see the arguments.

        limit caps each sink's list, and unset means the default cap of 100 --
        smaller than the other tools' 1000 because this one caps per sink across
        a 20-tag catalogue. 0 or any negative number means uncapped. A capped
        sweep adds "@truncated": one "@truncated: ..." string per capped sink,
        each naming that sink and its real total.

        A sweep of any size adds "@oversized" when what came back is a file
        dump -- this tool returns one list per sink, so even at the default limit
        the total can cross the line. A bare method name is not bounded that way
        -- "<init>" and "toString" each match enormous numbers of call sites.
        Name the sink you are reading and leave the limit alone.

        A sink reached through a type the APK itself declares -- the app's own
        WebView subclass overriding loadUrl, or a bundled androidx class -- is
        found via that type, and "@resolved_in_apk": {sink: [classes]} names the
        types it went through. Read that as a lead: for webview_loadurl it is the
        app's own WebView containers.

        Still invisible: a sink reached by reflection, and a wrapper that renames
        the operation (an SDK's own "get(url)" that calls URL.openConnection is a
        network_request call site this reports under the wrapper, not the caller
        -- follow it with get_callees)."""
        return _json(c.find_sink_call_sites(sinks, limit))

    @mcp.tool(structured_output=False)
    def get_type_hierarchy(className: str) -> str:
        """Get supertypes, interfaces, subclasses and nesting for a class.
        Resolves framework types not present in the APK; "inApk" says which.

        Asking this tool about any class name in its own answer returns that
        same name back in "className", and a name that belongs to a class in the
        APK is one get_class_source, get_smali_of_class and get_xrefs_to_class
        take as well. (A name with "inApk": false is a framework type: it has a
        hierarchy here and no source anywhere.) That was not
        true before 2026-08-08: "superClass", "interfaces" and "superTypes" came
        back as RAW dex names, which for a renamed or inner class is a spelling
        no tool here resolves, and feeding one back got "inApk": FALSE for a
        class that is in the dex -- some of the classes named as somebody's
        supertype on a large APK, and more of them when asked by the raw name
        directly. The raw spelling still resolves, so older recorded names keep
        working; it is no longer what you are handed.

        "superTypes" is also now answered for inner and anonymous classes. It was
        empty for every inner and anonymous class -- so "is this a WebViewClient"
        read as "no" for exactly the classes that usually are one.

        "subClasses" is DIRECT subclasses and is capped at 1000, ending with a
        "@truncated: " entry when it bites -- asking this about java.lang.Object
        on a large APK names a huge number of them, and the capped call is a few
        tens of KB. "superTypes", "interfaces" and "innerClasses" carry the same
        fixed cap and the same marker; none of them has ever reached it on a real
        APK. This tool has no limit
        parameter; to enumerate a large family, use get_all_classes with a
        packagePrefix or search_classes_by_keyword."""
        return _json(c.get_type_hierarchy(className))

    @mcp.tool(structured_output=False)
    def get_overrides(
        className: str,
        methodName: str,
        signature: str | None = None,
        limit: int | None = None,
    ) -> str:
        """Get the methods overriding a method, plus the base methods it
        overrides. Resolves interface dispatch to concrete implementations.
        An overloaded name covers every overload and the response says so under "@ambiguous"; pass signature -- the "(args)ret" descriptor, or the whole signature string an xref or callee entry reports -- to ask about one.

        limit caps "overriddenBy" and "baseMethods" and defaults to 1000. It
        bites on exactly the query this tool exists for: a widely-implemented
        interface method like java.lang.Runnable.run has thousands of
        implementations and hundreds of KB of them. 0 or any negative
        number means UNCAPPED. A cut-short list ends with one extra entry
        beginning "@truncated: " naming that list's real total, and any list over
        1 MB ends with "@oversized: " as well, cut or not -- so if you are
        resolving dispatch, narrow to the interface the caller actually holds
        rather than raising the limit. The whole response is weighed too, under a
        top-level "@oversized" key, since two lists each under the line are a
        response over it."""
        return _json(c.get_overrides(className, methodName, signature, limit))

    @mcp.tool(structured_output=False)
    def get_exported_components() -> str:
        """Get the components any app on the device can start directly: every
        manifest component whose android:exported resolves to true, the launcher
        activity included.

        Same record shape as get_deeplink_components, field for field -- the two
        differ only in which components they keep, and the docstring there gives
        the record. This one keeps "exported", that one keeps "link-reachable,
        plus the exported ones no link reaches". Neither contains the other: a
        BROWSABLE activity declaring android:exported="false" is in that list and
        not this one, and an exported component whose only
        filter carries MAIN without LAUNCHER is in this one and not that."""
        return _json(c.get_exported_components())

    @mcp.tool(structured_output=False)
    def get_deeplink_components() -> str:
        """Get components a link or a co-located app can reach: the launcher
        activity, everything with a BROWSABLE filter, activity-aliases pointing
        at either, and then exported components with no BROWSABLE filter. Scheme,
        host and path arrive as literals with resource references already
        resolved -- this is the tool that resolves them, not get_android_manifest.

        EVERY ENTRY HAS THE SAME KEYS: name, type, is_main, is_browsable,
        exported, exported_reason, intent_filters. permission and alias_of are
        there only when the manifest declares them, so read those with .get().
        (A bookkeeping entry has exactly one key and it starts with "@" -- the
        usual rule, and the only entry here without a name.)

        Each intent_filters[] entry is {actions, categories, data}, and each
        data[] entry carries all nine of scheme, host, port, path, pathPrefix,
        pathPattern, pathSuffix, mimeType, autoVerify -- always, empty ("" or
        false) where the <data> element does not declare that attribute. Empty
        means NOT DECLARED, which is a matching rule rather than a gap: a data
        element with a scheme and no host matches that scheme on any host. It is
        not "any" for the URI you build -- a filter declaring no scheme matches
        no link at all -- so read which attribute is empty, not the emptiness.

        Ordered: link-reachable components first, then the rest, so taking the
        first N covers the surface a browser can reach."""
        return _json(c.get_deeplink_components())

    @mcp.tool(structured_output=False)
    def get_android_manifest() -> str:
        """Get the AndroidManifest.xml content, references and all.

        Capped at 1,048,576 BYTES of result -- UTF-8, JSON escaping included,
        which is what goes on the wire -- with no way to raise it. It has never
        come near it -- even a large app's manifest is a small fraction of the
        budget -- and a manifest is the one document an analysis has no narrower
        substitute for, so there is nothing useful to tune. A cut answer would
        lead with a "@truncated: " line.

        Values here are as the APK stores them, so an android:scheme may be
        "@string/foo". get_deeplink_components resolves those against the
        resource table; read schemes from there rather than from this."""
        return _json(c.get_android_manifest())

    @mcp.tool(structured_output=False)
    def get_main_activity_class() -> str:
        """Get the main launcher activity class name"""
        return _json(c.get_main_activity_class())

    @mcp.tool(structured_output=False)
    def get_all_resource_file_names(
        pathPrefix: str | None = None, limit: int | None = None
    ) -> str:
        """List resource file names in the APK, including assets and the
        manifest.

        Any entry can be fed to get_resource_file, but read that tool's own note
        on size before you do: this listing is mostly assets and native
        libraries, and the DEFAULT 1000 names below can already include a bundled
        .dex asset, which is many MB of content.
        get_resource_file caps at 1 MB and says so, so following this is safe --
        but the entry you want is almost always under res/xml or assets/.

        Narrow it rather than reading the lot: a large APK is many thousands of
        names and hundreds of KB, and almost none of them are what you want.
          pathPrefix -- keep only names starting with this, case-insensitive,
              matched against the whole archive path. "res/xml" is the one to
              reach for -- a handful of names, and it holds
              network_security_config, the FileProvider path declarations and
              the App Links assetlinks. "assets/" is the other useful one.
          limit -- at most this many names. Unset means the default cap of 1000;
              0 and any negative number mean UNCAPPED.

        A list cut short ends with one extra entry beginning "@truncated: " that
        names the real total, and any answer over 1 MB ends with "@oversized: "
        as well, cut or not."""
        return _json(c.get_all_resource_file_names(pathPrefix, limit))

    @mcp.tool(structured_output=False)
    def get_resource_file(fileName: str, maxBytes: int | None = None) -> str:
        """Get the content of a specific resource file.

        Capped at 1,048,576 bytes of answer by default, and this is the tool
        that most needs it: an APK's biggest entries are its native libraries
        and its assets, not its code. A native library can be tens of MB,
        arriving as a hundred-MB-plus tool result once escaped, and a bundled
        .dex read as text is almost all escapes and multi-byte replacement
        characters.

        A bundled .dex asset can be in the DEFAULT 1000-name listing
        get_all_resource_file_names hands back, so keep the cap unless you know
        the entry is text.

        maxBytes -- unset means the budget; 0 and any negative number mean
            UNCAPPED, so 0 WIDENS -- to the entry's full size, which for a native
            library is the largest response this server can produce. A cut answer says so on
            its LEADING line, a string beginning "@truncated: " naming the
            entry's real size in bytes, then a blank line, then the content; an
            uncapped one over 1 MB leads with "@oversized: " the same way.

        This returns the entry's bytes decoded as UTF-8, so a binary entry comes
        back mangled -- it is a way to read res/xml, assets/*.json and the like,
        not a way to extract a library."""
        return _json(c.get_resource_file(fileName, maxBytes))

    @mcp.tool(structured_output=False)
    def get_smali_of_class(className: str, maxBytes: int | None = None) -> str:
        """Get the smali code of a specific class.

        The "###### Class NAME (RAW)" header above each class names it the way
        every other tool here does, so NAME can be fed straight back. jadx writes
        that line with its own spelling, which for the names that denote two
        classes at once is the one that resolves to the OTHER one -- the header
        named the twin for those classes until 2026-08-08. RAW, in the
        parentheses, is the original dex name and resolves too.

        Capped at 1,048,576 bytes of answer by default; smali runs several
        times the size of the source it came from, so this bites a little sooner
        than get_class_source does -- a Kotlin stdlib facade can be several MB of
        smali against about a MiB of Java.

        maxBytes -- unset means the budget; 0 and any negative number mean
            UNCAPPED, so 0 WIDENS. A cut answer leads with a "@truncated: "
            line naming the real total, and an uncapped one over 1 MB with
            "@oversized: ". Reach for get_smali_of_method instead of raising
            it."""
        return _json(c.get_smali_of_class(className, maxBytes))

    @mcp.tool(structured_output=False)
    def get_smali_of_method(
        className: str, methodName: str, signature: str | None = None
    ) -> str:
        """Get the smali code of a specific method. Takes the same names every
        other tool here does -- bare or fully-qualified, deobfuscated or raw dex
        -- and returns every overload of the name it matches.

        An overloaded name covers every overload and the response says so on its
        LEADING line, under "@ambiguous", naming the count and every signature;
        pass signature -- the "(args)ret" descriptor -- to ask about one. Nine
        bodies used to arrive concatenated with nothing saying they were nine.

        Capped at 1,048,576 BYTES of result -- UTF-8, JSON escaping included,
        which is what goes on the wire -- with no way to raise it, exactly as
        get_method_by_name and get_android_manifest are. This docstring said
        nothing about it while the code applied it, in the same commit that
        capped both siblings and documented theirs, which is the worst way for
        a limit to exist. It does bite here sooner than on
        its siblings, because smali is bulkier than source: the widest single
        methods are table-filling generated static initialisers, and even those
        are a fraction of the budget. A cut answer
        leads with a "@truncated: " line naming the real total; if you meet one,
        you are reading a generated initialiser, and get_fields_of_class or
        get_class_source will answer what you actually wanted."""
        return _json(c.get_smali_of_method(className, methodName, signature))

    return mcp


def main() -> None:
    # Imported here so the module stays importable in tests without a JVM around.
    from daemon import ensure_daemon

    ensure_daemon(BASE)
    build_server().run()


if __name__ == "__main__":
    main()
