import typing

import httpx
import pytest

from jadx_mcp import JadxClient, build_server

# The contract with the agent files. Changing any of these names, or the
# parameter names below, breaks every worker in the analysis client.
EXPECTED_TOOLS = {
    "load_apk",
    "get_all_classes",
    "get_class_source",
    "get_methods_of_class",
    "get_fields_of_class",
    "get_method_by_name",
    "search_method_by_name",
    "get_exported_components",
    "get_android_manifest",
    "get_main_activity_class",
    "get_all_resource_file_names",
    "get_resource_file",
    "get_smali_of_class",
    "get_smali_of_method",
    # Parity with jeb-plus; see docs/superpowers/plans/2026-08-07-jeb-plus-parity.md
    "search_classes_by_keyword",
    "get_xrefs_to_class",
    "get_xrefs_to_method",
    "get_xrefs_to_field",
    "get_type_hierarchy",
    "get_overrides",
    "get_deeplink_components",
    # The forward direction; docs/research/2026-jadx-1.5.6-assessment.md section A
    "get_callees",
    # The whole-APK sweep; same document, section B
    "find_sink_call_sites",
}


def client_for(handler):
    return JadxClient("http://127.0.0.1:8765", transport=httpx.MockTransport(handler))


def test_get_class_source_unwraps_envelope():
    def handler(request):
        assert request.url.path == "/api/jadx/class-source"
        assert request.url.params["className"] == "com.example.Foo"
        return httpx.Response(200, json={"source": "class Foo {}"})

    assert client_for(handler).get_class_source("com.example.Foo") == "class Foo {}"


def test_resource_path_with_slashes_is_encoded():
    seen = {}

    def handler(request):
        seen["fileName"] = request.url.params["fileName"]
        return httpx.Response(200, json={"content": "<xml/>"})

    client_for(handler).get_resource_file("res/xml/network_security_config.xml")
    assert seen["fileName"] == "res/xml/network_security_config.xml"


def test_search_returns_the_results_map_not_the_envelope():
    payload = {"com.example.Foo": ["com.example.Foo.doFilter"]}

    def handler(request):
        return httpx.Response(200, json={"results": payload})

    assert client_for(handler).search_method_by_name("doFilter") == payload


def test_load_apk_returns_the_raw_body():
    body = {"packageName": "com.example.app", "totalClasses": 72345}

    def handler(request):
        assert request.method == "POST"
        return httpx.Response(200, json=body)

    assert client_for(handler).load_apk("/tmp/app.apk") == body


def test_error_response_raises_with_the_daemon_message():
    def handler(request):
        return httpx.Response(400, json={"error": "APK not loaded. Call loadApk() first."})

    with pytest.raises(RuntimeError, match="APK not loaded"):
        client_for(handler).get_class_source("nope")


def test_connection_failure_is_not_silently_swallowed():
    def handler(request):
        raise httpx.ConnectError("connection refused")

    with pytest.raises(httpx.ConnectError):
        client_for(handler).get_all_classes()


def test_search_classes_unwraps_the_classes_envelope():
    def handler(request):
        assert request.url.path == "/api/jadx/search-classes"
        assert request.url.params["keyword"] == "SchemeDispatcher"
        return httpx.Response(200, json={"classes": ["com.example.app.SchemeDispatcher"]})

    assert client_for(handler).search_classes_by_keyword("SchemeDispatcher") == [
        "com.example.app.SchemeDispatcher"
    ]


def test_search_classes_returns_empty_list_not_an_error():
    """A search matching nothing is an answer. Agents branch on the empty list,
    so turning it into an exception would change how they behave."""

    def handler(request):
        return httpx.Response(200, json={"classes": []})

    assert client_for(handler).search_classes_by_keyword("nope") == []


def test_xrefs_unwrap_to_a_bare_list():
    """The plan requires the {"xrefs": [...]} envelope to unwrap, like every
    other endpoint. An agent receiving the envelope instead of the list would
    silently iterate the single key "xrefs"."""
    payload = [
        {"className": "com.example.app.SchemeActivity",
         "methodName": "onCreate",
         "signature": "onCreate(Landroid/os/Bundle;)V"}
    ]

    def handler(request):
        assert request.url.path == "/api/jadx/xrefs-to-method"
        assert request.url.params["className"] == "com.example.pay.BankPayMethod"
        assert request.url.params["methodName"] == "getActivity"
        return httpx.Response(200, json={"xrefs": payload})

    got = client_for(handler).get_xrefs_to_method(
        "com.example.pay.BankPayMethod", "getActivity"
    )
    assert got == payload
    assert got[0]["methodName"] == "onCreate"


def test_every_xref_entry_has_the_same_three_keys():
    """One access pattern for every entry, which is what the docstring promises
    and what a tracer writes.

    A class-level reference -- a superclass or interface bound -- has no
    enclosing method. Until 2026-08-08 the daemon expressed that by OMITTING
    "signature", so the documented entry["signature"] raised a KeyError on 8.0%
    of entries on a real APK and on a fifth of its classes. It now sends both
    method keys as empty strings, and the empty methodName is the fact a tracer
    reads: the reference is the class's own declaration, so continue with
    get_type_hierarchy rather than searching for a call site."""
    payload = [
        {"className": "com.example.app.SchemeActivity",
         "methodName": "onCreate",
         "signature": "onCreate(Landroid/os/Bundle;)V"},
        {"className": "com.example.app.BaseSchemeActivity",
         "methodName": "",
         "signature": ""},
    ]

    def handler(request):
        return httpx.Response(200, json={"xrefs": payload})

    got = client_for(handler).get_xrefs_to_class("com.example.app.SchemeActivity")

    # The access pattern the doc tells a worker to write, on every entry.
    assert [x["signature"] for x in got] == ["onCreate(Landroid/os/Bundle;)V", ""]
    assert all(set(x) == {"className", "methodName", "signature"} for x in got)
    # And the empty one is still a real entry, not bookkeeping.
    assert [x for x in got if "className" in x] == got


def test_xrefs_to_class_and_field_hit_their_own_endpoints():
    seen = {}

    def handler(request):
        seen["path"] = request.url.path
        return httpx.Response(200, json={"xrefs": []})

    c = client_for(handler)
    c.get_xrefs_to_class("com.example.Foo")
    assert seen["path"] == "/api/jadx/xrefs-to-class"
    c.get_xrefs_to_field("com.example.Foo", "bar")
    assert seen["path"] == "/api/jadx/xrefs-to-field"


def test_unreferenced_target_is_an_empty_list_not_an_error():
    def handler(request):
        return httpx.Response(200, json={"xrefs": []})

    assert client_for(handler).get_xrefs_to_class("com.example.Unused") == []


def test_unknown_class_for_xrefs_raises_the_same_not_found_shape():
    def handler(request):
        return httpx.Response(400, json={"error": "Class not found: com.nope.Nope"})

    with pytest.raises(RuntimeError, match="Class not found"):
        client_for(handler).get_xrefs_to_class("com.nope.Nope")


def test_callees_unwrap_to_the_result_object():
    """The outer envelope key and one of the inner keys are both "callees".
    Unwrapping must stop after one level: an agent that got the inner list back
    would lose "external", which is the half that names the sinks."""
    payload = {
        "callees": [
            {"className": "com.example.webview.AppWebViewClient",
             "methodName": "openThirdPartyAppWithIntentScheme",
             "signature": "openThirdPartyAppWithIntentScheme(Ljava/lang/String;)Z"}
        ],
        "external": [
            {"className": "android.content.Intent",
             "methodName": "parseUri",
             "signature": "parseUri(Ljava/lang/String;I)Landroid/content/Intent;"}
        ],
        "callsSelf": False,
    }

    def handler(request):
        assert request.url.path == "/api/jadx/callees"
        assert request.url.params["className"] == (
            "com.example.webview.AppWebViewClient"
        )
        assert request.url.params["methodName"] == "shouldOverrideUrlLoading"
        return httpx.Response(200, json={"callees": payload})

    got = client_for(handler).get_callees(
        "com.example.webview.AppWebViewClient", "shouldOverrideUrlLoading"
    )
    assert set(got) == {"callees", "external", "callsSelf"}
    assert got["external"][0]["className"] == "android.content.Intent"
    assert got["callsSelf"] is False


def test_callees_of_a_method_that_calls_nothing_are_empty_lists_not_an_error():
    """A native or empty method reaches nothing. Agents branch on the empty
    list, same as they do for an unreferenced xref target."""

    def handler(request):
        return httpx.Response(
            200, json={"callees": {"callees": [], "external": [], "callsSelf": False}}
        )

    got = client_for(handler).get_callees("com.example.Foo", "nativeThing")
    assert got["callees"] == []
    assert got["external"] == []


def test_unknown_class_for_callees_raises_the_same_not_found_shape():
    def handler(request):
        return httpx.Response(400, json={"error": "Class not found: com.nope.Nope"})

    with pytest.raises(RuntimeError, match="Class not found"):
        client_for(handler).get_callees("com.nope.Nope", "anything")


def test_unknown_method_for_callees_raises_not_found():
    def handler(request):
        return httpx.Response(
            400, json={"error": "Method not found: zzz in class com.example.Foo"}
        )

    with pytest.raises(RuntimeError, match="Method not found"):
        client_for(handler).get_callees("com.example.Foo", "zzz")


def test_sink_call_sites_unwrap_the_envelope_and_keep_the_sink_keys():
    """The envelope is "sinkCallSites"; inside it, every key is a sink the
    caller asked for. Unwrapping one level hands the agent the map it can
    iterate by impact_category."""
    payload = {
        "intent_parse": [
            {"className": "com.example.webview.AppWebViewClient",
             "methodName": "openThirdPartyAppWithIntentScheme",
             "signature": "openThirdPartyAppWithIntentScheme(Ljava/lang/String;)Z"}
        ],
        "exec": [],
    }

    def handler(request):
        assert request.url.path == "/api/jadx/sink-call-sites"
        return httpx.Response(200, json={"sinkCallSites": payload})

    got = client_for(handler).find_sink_call_sites()
    assert got["intent_parse"][0]["methodName"] == "openThirdPartyAppWithIntentScheme"
    assert got["exec"] == []


def test_no_sinks_argument_sends_no_sinks_param_so_the_daemon_uses_its_catalogue():
    """The cold call. An agent that does not yet know what to look for must be
    able to ask for nothing and get the caller's own sink catalogue back."""
    seen = {}

    def handler(request):
        seen["params"] = dict(request.url.params)
        return httpx.Response(200, json={"sinkCallSites": {}})

    client_for(handler).find_sink_call_sites()
    assert "sinks" not in seen["params"]
    assert "limit" not in seen["params"]


def test_explicit_sinks_are_sent_comma_separated():
    """REST takes one query parameter, not a repeated one -- class names carry
    dots and the daemon splits on commas."""
    seen = {}

    def handler(request):
        seen["params"] = dict(request.url.params)
        return httpx.Response(200, json={"sinkCallSites": {}})

    client_for(handler).find_sink_call_sites(
        ["android.content.Intent.parseUri", "webview_loadurl"], limit=25
    )
    assert seen["params"]["sinks"] == "android.content.Intent.parseUri,webview_loadurl"
    assert seen["params"]["limit"] == "25"


def test_get_all_classes_with_no_arguments_sends_no_params():
    """The no-argument call is a contract: every agent file and the CLI ask for
    the whole APK this way, so it must stay a bare GET /classes."""
    seen = {}

    def handler(request):
        assert request.url.path == "/api/jadx/classes"
        seen["params"] = dict(request.url.params)
        return httpx.Response(200, json={"classes": ["com.example.Foo"]})

    assert client_for(handler).get_all_classes() == ["com.example.Foo"]
    assert seen["params"] == {}


def test_get_all_classes_narrowing_params_are_sent_when_given():
    """7.7 MB is the widest answer; these are how a caller asks for less."""
    seen = {}

    def handler(request):
        seen["params"] = dict(request.url.params)
        return httpx.Response(200, json={"classes": []})

    client_for(handler).get_all_classes(packagePrefix="com.example.app", limit=50)
    assert seen["params"] == {"packagePrefix": "com.example.app", "limit": "50"}


def test_get_all_classes_limit_zero_is_sent_because_it_means_uncapped():
    """0 is a real value, not "unset" -- the check is `is not None`."""
    seen = {}

    def handler(request):
        seen["params"] = dict(request.url.params)
        return httpx.Response(200, json={"classes": []})

    client_for(handler).get_all_classes(limit=0)
    assert seen["params"]["limit"] == "0"


def test_a_truncated_class_list_keeps_its_marker():
    """The marker is one more element of the list, and the client must not
    filter or reshape it: it is the only thing that tells an agent the list it
    is looking at is not the whole APK."""

    def handler(request):
        return httpx.Response(200, json={"classes": [
            "com.example.A", "com.example.B",
            "@truncated: showing 2 of 141461 classes. Raise limit, narrow"
            " packagePrefix, or use search_classes_by_keyword for a name you"
            " already know part of.",
        ]})

    got = client_for(handler).get_all_classes(limit=2)
    assert len(got) == 3
    assert got[-1].startswith("@truncated")
    assert "141461" in got[-1]


def test_the_last_uncapped_families_send_a_limit_only_when_given():
    """The four families the size guard missed, plus the resource list.

    Measured on a large production APK before they had one: get_xrefs_to_class on
    kotlin.jvm.internal.Intrinsics was 10,030,776 bytes, get_xrefs_to_method on
    Intrinsics.checkNotNullParameter 7,542,206, get_overrides on
    java.lang.Runnable.run 470,517, get_fields_of_class on a generated R table
    411,485, and get_all_resource_file_names 897,281 -- every one HTTP 200 with
    no marker anywhere in the body.

    Absent stays absent on the wire, as for the searches: the default cap is the
    daemon's decision, and pinning an explicit 0 here would send every call back
    to the unbounded behaviour."""
    seen = []

    def handler(request):
        seen.append(dict(request.url.params))
        return httpx.Response(200, json={
            "xrefs": [], "callees": {}, "overrides": {},
            "methods": [], "fields": [], "resources": [],
        })

    c = client_for(handler)
    c.get_xrefs_to_class("kotlin.Unit")
    c.get_xrefs_to_class("kotlin.Unit", limit=10)
    c.get_xrefs_to_method("kotlin.Unit", "toString")
    c.get_xrefs_to_method("kotlin.Unit", "toString", limit=0)
    c.get_xrefs_to_field("kotlin.Unit", "INSTANCE", limit=10)
    c.get_callees("a.B", "c")
    c.get_callees("a.B", "c", limit=10)
    c.get_overrides("java.lang.Runnable", "run", limit=10)
    c.get_methods_of_class("a.B")
    c.get_fields_of_class("a.B", limit=10)

    assert seen == [
        {"className": "kotlin.Unit"},
        {"className": "kotlin.Unit", "limit": "10"},
        {"className": "kotlin.Unit", "methodName": "toString"},
        # 0 is a real value meaning uncapped, so it goes on the wire.
        {"className": "kotlin.Unit", "methodName": "toString", "limit": "0"},
        {"className": "kotlin.Unit", "fieldName": "INSTANCE", "limit": "10"},
        {"className": "a.B", "methodName": "c"},
        {"className": "a.B", "methodName": "c", "limit": "10"},
        {"className": "java.lang.Runnable", "methodName": "run", "limit": "10"},
        {"className": "a.B"},
        {"className": "a.B", "limit": "10"},
    ]


def test_the_resource_list_can_finally_be_asked_a_narrow_question():
    """It took no arguments at all: 16,559 names and 897 KB, the same answer for
    everybody. res/xml is 24 of those names and is where a caller looks first --
    network_security_config, the FileProvider paths, assetlinks."""
    seen = []

    def handler(request):
        seen.append(dict(request.url.params))
        return httpx.Response(200, json={"resources": []})

    c = client_for(handler)
    c.get_all_resource_file_names()
    c.get_all_resource_file_names("res/xml")
    c.get_all_resource_file_names("assets/", 0)

    assert seen == [
        {},
        {"pathPrefix": "res/xml"},
        {"pathPrefix": "assets/", "limit": "0"},
    ]


def test_a_truncated_xref_list_keeps_its_marker_entry():
    """A list of objects cannot carry a bare string, so the notice arrives as a
    trailing one-key entry -- the shape "@ambiguous" already uses here. The
    client must not filter it: it is the only thing distinguishing 1000 callers
    from all of them."""

    def handler(request):
        return httpx.Response(200, json={"xrefs": [
            {"className": "a.B", "methodName": "c", "signature": "c()V"},
            {"@truncated": "@truncated: showing 1000 of 53181 references."
                           " Raise limit, or pass limit 0 for all of them."},
        ]})

    got = client_for(handler).get_xrefs_to_class("kotlin.jvm.internal.Intrinsics")
    assert len(got) == 2
    assert got[-1]["@truncated"].startswith("@truncated: ")
    assert "53181" in got[-1]["@truncated"]
    # The one check: a real entry has a className, bookkeeping has one '@' key.
    assert [x for x in got if "className" in x] == [got[0]]


def test_both_searches_send_a_limit_only_when_given():
    """The limit is what keeps a wide search off an agent's context -- 58.9 MB
    for search-method and 7.5 MB for search-classes before it existed.

    Absent stays absent on the wire. It used to mean "uncapped" at the far end
    and now means the daemon's default cap, but that is the daemon's decision to
    make: sending an explicit 0 here would pin the old unbounded behaviour into
    every call this client makes, which is the opposite of the fix."""
    seen = []

    def handler(request):
        seen.append(dict(request.url.params))
        return httpx.Response(200, json={"results": {}, "classes": []})

    c = client_for(handler)
    c.search_method_by_name("onCreate")
    c.search_method_by_name("onCreate", limit=10)
    c.search_classes_by_keyword("Activity")
    c.search_classes_by_keyword("Activity", limit=10)

    assert seen == [
        {"methodName": "onCreate"},
        {"methodName": "onCreate", "limit": "10"},
        {"keyword": "Activity"},
        {"keyword": "Activity", "limit": "10"},
    ]


def test_a_refused_search_keeps_its_marker():
    """A query too short to be a lookup comes back as a stated refusal, not as
    an empty answer. The client must not reshape it into one: "you asked for
    nothing" and "this APK has no such class" are different facts."""

    def handler(request):
        if request.url.path.endswith("search-classes"):
            return httpx.Response(200, json={"classes": [
                '@rejected: keyword "a" is shorter than 2 characters, and this'
                " search matches on substring."]})
        return httpx.Response(200, json={"results": {
            "@rejected": ['@rejected: methodName "" is shorter than 2 characters,'
                          " and this search matches on substring."]}})

    c = client_for(handler)
    assert c.search_classes_by_keyword("a")[0].startswith("@rejected")
    assert "@rejected" in c.search_method_by_name("")


def test_one_check_finds_a_notice_whichever_tool_produced_it():
    """The shapes used to disagree. search-classes refused with a string that
    named itself ("@rejected: keyword ..."), search-method with a bare sentence
    filed under an @rejected key -- so "was this answer complete?" needed a
    different check per tool, and a caller that grepped the text found the
    marker in one and not the other.

    One rule now: every notice is a string beginning with its own marker name,
    trailing a list or held as a one-element list under a key of that name. So
    this walks the strings of a response and looks for a leading '@', and that
    is the whole check for all four tools and all three notices."""

    def notices(payload):
        if isinstance(payload, dict):
            found = []
            for value in payload.values():
                found += notices(value)
            return found
        if isinstance(payload, list):
            found = []
            for item in payload:
                found += notices(item)
            return found
        return [payload] if isinstance(payload, str) and payload.startswith("@") else []

    truncated = "@truncated: showing 2 of 141461 classes. Raise limit."
    oversized = "@oversized: getAllClasses returned 141461 class names (7 MB)."
    rejected = '@rejected: keyword "a" is shorter than 2 characters.'

    # get_all_classes / search_classes_by_keyword -- a trailing list element
    assert notices(["com.a.B", truncated]) == [truncated]
    assert notices(["com.a.B", oversized]) == [oversized]
    assert notices([rejected]) == [rejected]
    # search_method_by_name -- under a key, value a one-element list
    assert notices({"com.a.B": ["com.a.B.f"], "@truncated": [truncated]}) == [truncated]
    assert notices({"@rejected": [rejected]}) == [rejected]
    # find_sink_call_sites -- one string per capped sink, under the same key
    assert notices({
        "intent_launch": [{"className": "com.a.B", "methodName": "f"}],
        "@truncated": [truncated, oversized],
    }) == [truncated, oversized]
    # and a complete answer says nothing, so the notice carries information
    assert notices(["com.a.B", "com.a.C"]) == []
    assert notices({"com.a.B": ["com.a.B.f"]}) == []


def test_an_empty_sink_list_is_treated_as_no_argument():
    """[] means "I did not choose", not "sweep nothing" -- a request that swept
    nothing would return an empty object and read as a clean APK."""
    seen = {}

    def handler(request):
        seen["params"] = dict(request.url.params)
        return httpx.Response(200, json={"sinkCallSites": {}})

    client_for(handler).find_sink_call_sites([])
    assert "sinks" not in seen["params"]


def test_truncation_is_visible_to_the_caller():
    """A capped list that does not say it was capped reads as complete.

    The shape this blessed until 2026-08-08 was {"@truncated": {"intent_launch":
    910}} -- a {sink: total} map the daemon stopped emitting, and the one shape
    in which the documented check finds nothing: the values are integers, so a
    walker looking for a string beginning '@' walks straight past the only thing
    in the response that says the answer is partial. The daemon emits one
    SELF-DESCRIBING STRING per capped sink, under a key that cannot be a sink
    name, and that is what is asserted here."""
    cut = ('@truncated: showing 100 of 910 call sites for "intent_launch".'
           " Ask for this sink alone with limit 0 for the rest.")
    payload = {
        "intent_launch": [{"className": "com.a.B", "methodName": "c", "signature": "c()V"}],
        "@truncated": [cut],
    }

    def handler(request):
        return httpx.Response(200, json={"sinkCallSites": payload})

    got = client_for(handler).find_sink_call_sites(limit=1)
    assert got["@truncated"] == [cut]
    assert got["@truncated"][0].startswith("@truncated: ")
    assert "910" in got["@truncated"][0]
    assert len(got["intent_launch"]) == 1


def test_a_cut_answer_can_also_be_an_oversized_one():
    """The two markers answer different questions -- "are you missing some" and
    "is what you got a file dump" -- so they are not alternatives, and any
    count-capped tool can carry both. Until 2026-08-08 the daemon's truncation
    branch never weighed its own bytes, so an explicit limit returned multi-MB
    wearing "@truncated" alone: get_xrefs_to_class(Intrinsics, limit=50000) is
    9,518,250 bytes of 50,000 entries, and get_all_classes(limit=100000) is
    6,084,879.

    A client that treated the markers as alternatives -- stopping at the first
    one it found -- would report the response as merely truncated. So both must
    survive the unwrap, in both container shapes."""
    cut = "@truncated: showing 50000 of 53181 references. Raise limit, or pass limit 0."
    big = ("@oversized: getXrefsToClass returned 50000 references (9 MB of JSON,"
           " className=kotlin.jvm.internal.Intrinsics). That is a file dump.")

    def handler(request):
        if request.url.path.endswith("xrefs-to-class"):
            return httpx.Response(200, json={"xrefs": [
                {"className": "a.B", "methodName": "c", "signature": "c()V"},
                {"@truncated": cut},
                {"@oversized": big},
            ]})
        return httpx.Response(200, json={"classes": ["a.B", cut, big]})

    c = client_for(handler)

    refs = c.get_xrefs_to_class("kotlin.jvm.internal.Intrinsics", limit=50000)
    assert [x for x in refs if "className" in x] == [refs[0]]
    assert [k for x in refs for k in x if k.startswith("@")] == ["@truncated", "@oversized"]

    classes = c.get_all_classes(limit=100000)
    assert [n for n in classes if n.startswith("@")] == [cut, big]


def test_limit_zero_is_sent_rather_than_dropped_as_falsy():
    """0 means uncapped, and it is the one value a naive truthiness check would
    silently turn back into the default cap."""
    seen = {}

    def handler(request):
        seen["params"] = dict(request.url.params)
        return httpx.Response(200, json={"sinkCallSites": {}})

    client_for(handler).find_sink_call_sites(limit=0)
    assert seen["params"]["limit"] == "0"


def test_type_hierarchy_unwraps_to_the_hierarchy_object():
    payload = {
        "className": "com.example.WebClient",
        "inApk": True,
        "superClass": "android.webkit.WebViewClient",
        "interfaces": [],
        "superTypes": ["android.webkit.WebViewClient", "java.lang.Object"],
        "subClasses": [],
        "innerClasses": [],
        "outerClass": None,
    }

    def handler(request):
        assert request.url.path == "/api/jadx/type-hierarchy"
        return httpx.Response(200, json={"hierarchy": payload})

    got = client_for(handler).get_type_hierarchy("com.example.WebClient")
    assert got["superClass"] == "android.webkit.WebViewClient"


def test_framework_type_is_flagged_as_outside_the_apk():
    """A type jadx knows but the APK does not declare must be distinguishable
    from one with no supertypes -- the plan forbids a silent empty result."""

    def handler(request):
        return httpx.Response(200, json={"hierarchy": {
            "className": "android.app.Activity",
            "inApk": False,
            "superTypes": ["android.content.Context", "java.lang.Object"],
        }})

    got = client_for(handler).get_type_hierarchy("android.app.Activity")
    assert got["inApk"] is False
    assert got["superTypes"]


def test_overrides_unwrap_to_the_overrides_object():
    payload = {
        "className": "com.example.WebClient",
        "methodName": "shouldOverrideUrlLoading",
        "attributePresent": True,
        "baseMethods": ["android.webkit.WebViewClient.shouldOverrideUrlLoading"],
        "overriddenBy": ["com.example.PayWebClient.shouldOverrideUrlLoading"],
    }

    def handler(request):
        assert request.url.path == "/api/jadx/overrides"
        return httpx.Response(200, json={"overrides": payload})

    got = client_for(handler).get_overrides("com.example.WebClient", "shouldOverrideUrlLoading")
    assert got["overriddenBy"] == ["com.example.PayWebClient.shouldOverrideUrlLoading"]


def test_overrides_report_whether_the_attribute_was_actually_present():
    """attributePresent=False means jadx attached no override data, which is a
    different fact from "this method overrides nothing". Collapsing the two
    would make the tool answer "no overrides" for everything, silently."""

    def handler(request):
        return httpx.Response(200, json={"overrides": {
            "attributePresent": False, "baseMethods": [], "overriddenBy": []
        }})

    got = client_for(handler).get_overrides("com.example.Foo", "bar")
    assert got["attributePresent"] is False


# Every key an intent-filter <data> element can contribute, in the order the
# daemon writes them. All nine are on every entry; see DATA_ATTRS in
# JadxAnalyzerCore and the census in ComponentEntryShapeProbe.
DATA_KEYS = ["scheme", "host", "port", "path", "pathPrefix", "pathPattern",
             "pathSuffix", "mimeType", "autoVerify"]


def _data(**declared):
    """One <data> entry as the daemon emits it: every key, empty where absent."""
    entry = {k: False if k == "autoVerify" else "" for k in DATA_KEYS}
    entry.update(declared)
    return entry


def test_deeplink_components_unwrap_and_keep_the_hunter_record_shape():
    """The record shape is a hand-off contract: the analysis client feeds these
    straight into the client's component store, so the field names come from
    the client's manifest parser rather than being ours to choose."""
    payload = [{
        "name": "com.facebook.CustomTabActivity",
        "type": "activity",
        "is_main": False,
        "is_browsable": True,
        "exported": True,
        "exported_reason": "default exported via intent-filter (pre-API-31)",
        "intent_filters": [{
            "actions": ["android.intent.action.VIEW"],
            "categories": ["android.intent.category.BROWSABLE"],
            "data": [_data(scheme="fb158675888115523")],
        }],
    }]

    def handler(request):
        assert request.url.path == "/api/jadx/deeplink-components"
        return httpx.Response(200, json={"components": payload})

    got = client_for(handler).get_deeplink_components()
    assert got[0]["is_browsable"] is True
    assert got[0]["intent_filters"][0]["data"][0]["scheme"] == "fb158675888115523"


def test_every_intent_filter_data_entry_carries_every_key():
    """A data element may declare only a scheme, or only a host, or only a
    mimeType, and the entries used to carry exactly the keys that had a value --
    nine different key sets over a large production APK's 75 entries, with NO key present on
    all of them. entry["scheme"] raised a KeyError on 15 of the 75, on the tool
    phase 1 of an analysis reads first. Empty now means "not declared"."""
    payload = [{
        "name": "com.example.Router",
        "type": "activity",
        "is_main": False,
        "is_browsable": True,
        "exported": True,
        "exported_reason": "explicit android:exported",
        "intent_filters": [{
            "actions": ["android.intent.action.VIEW"],
            "categories": ["android.intent.category.BROWSABLE"],
            "data": [
                _data(scheme="myapp"),                    # scheme alone
                _data(host="example.com", autoVerify=True),  # host alone
                _data(mimeType="text/plain"),             # neither
                _data(scheme="https", host="example.com", pathPrefix="/go"),
            ],
        }],
    }]

    def handler(request):
        return httpx.Response(200, json={"components": payload})

    got = client_for(handler).get_deeplink_components()
    entries = [d for c in got for f in c["intent_filters"] for d in f["data"]]
    assert len(entries) == 4
    for entry in entries:
        # The point: subscript, not .get() -- one access pattern, every entry.
        assert list(entry) == DATA_KEYS
        assert entry["scheme"] == "" or entry["scheme"]
        assert isinstance(entry["autoVerify"], bool)
    assert entries[0]["host"] == ""       # declared no host
    assert entries[1]["scheme"] == ""     # declared no scheme
    assert entries[1]["autoVerify"] is True
    assert entries[2]["pathPattern"] == ""


def test_the_two_components_tools_answer_in_one_shape():
    """They describe overlapping populations and used to describe them
    incompatibly -- {type, name, intentFilters: ["Action: …, Data: scheme=x "],
    permission, exported} against the component record. No access pattern worked
    across the pair, and the prose form had no keys to read at all. They now
    differ only in which components they keep."""
    record = {
        "name": "com.example.Main",
        "type": "activity",
        "is_main": True,
        "is_browsable": False,
        "exported": True,
        "exported_reason": "default exported via intent-filter (pre-API-31)",
        "intent_filters": [{
            "actions": ["android.intent.action.MAIN"],
            "categories": ["android.intent.category.LAUNCHER"],
            "data": [],
        }],
    }

    def handler(request):
        return httpx.Response(200, json={"components": [record]})

    client = client_for(handler)
    exported = client.get_exported_components()
    deeplink = client.get_deeplink_components()
    assert list(exported[0]) == list(deeplink[0])
    for got in (exported, deeplink):
        assert got[0]["is_main"] is True
        assert got[0]["intent_filters"][0]["categories"] == [
            "android.intent.category.LAUNCHER"]


def test_deeplink_component_schemes_are_literals_not_resource_refs():
    """An agent cannot type "@string/fb_login_protocol_scheme" into a URL. The
    whole point of this tool over the raw manifest is that it resolves them."""

    def handler(request):
        return httpx.Response(200, json={"components": [{
            "name": "com.facebook.CustomTabActivity",
            "intent_filters": [{"actions": [], "categories": [],
                                "data": [_data(scheme="fb158675888115523")]}],
        }]})

    got = client_for(handler).get_deeplink_components()
    for c in got:
        for f in c["intent_filters"]:
            for d in f["data"]:
                assert not d["scheme"].startswith("@")


def test_all_registered_tools_match_the_contract():
    names = {t.name for t in build_server()._tool_manager.list_tools()}
    assert names == EXPECTED_TOOLS


def test_tool_parameter_names_match_the_agent_contract():
    tools = {t.name: t for t in build_server()._tool_manager.list_tools()}
    expected = {
        "load_apk": {"apkPath"},
        # The three string-returning tools gained an optional "maxBytes" -- to a
        # string result what "limit" is to a list, in the unit a string is
        # measured in. They were the last uncapped responses in the server and
        # the biggest: get_resource_file on lib/arm64-v8a/libapp.so was
        # 150,606,754 bytes, get_class_source on a generated R table
        # 6,157,819, get_smali_of_class on a Kotlin facade 3,605,169. Optional
        # everywhere, so no existing call changes shape.
        "get_class_source": {"className", "maxBytes"},
        # The five methodName-taking tools gained an optional "signature".
        # A bare name cannot tell an overload set apart -- 803 such names on
        # DivaApplication.apk, 50,387 on a large production APK -- so these tools answer for
        # every overload and say so under "@ambiguous"; signature picks one.
        # Optional, so every existing caller keeps working unchanged; the
        # assertion below pins that none of them became required.
        "get_method_by_name": {"className", "methodName", "signature"},
        "search_method_by_name": {"methodName", "limit"},
        "search_classes_by_keyword": {"keyword", "limit"},
        # The xref family, get_callees, get_overrides and the two class-listing
        # tools gained the same optional "limit" the searches have. They were the
        # last uncapped list-returning tools: 10,030,776 bytes for
        # get_xrefs_to_class on kotlin.jvm.internal.Intrinsics, 470,517 for
        # get_overrides on java.lang.Runnable.run, 411,485 for
        # get_fields_of_class on a generated R table -- all HTTP 200, none of
        # them saying so. Optional everywhere, so no existing call changes shape.
        "get_xrefs_to_class": {"className", "limit"},
        "get_xrefs_to_method": {"className", "methodName", "signature", "limit"},
        "get_xrefs_to_field": {"className", "fieldName", "limit"},
        "get_methods_of_class": {"className", "limit"},
        "get_fields_of_class": {"className", "limit"},
        "get_type_hierarchy": {"className"},
        "get_smali_of_class": {"className", "maxBytes"},
        "get_overrides": {"className", "methodName", "signature", "limit"},
        "get_callees": {"className", "methodName", "signature", "limit"},
        "find_sink_call_sites": {"sinks", "limit"},
        "get_resource_file": {"fileName", "maxBytes"},
        "get_smali_of_method": {"className", "methodName", "signature"},
        # Both stay optional, so every existing caller keeps working. What
        # changed underneath is the meaning of omitting limit: it is the default
        # cap now, not the 8.1 MB whole-APK dump. limit=0 is the way back to it.
        "get_all_classes": {"packagePrefix", "limit"},
        # The only tool that took no arguments at all while returning 897,281
        # bytes. pathPrefix is the narrowing it never had; res/xml is 24 of a
        # real APK's 16,559 resource names.
        "get_all_resource_file_names": {"pathPrefix", "limit"},
    }
    for name, params in expected.items():
        schema = tools[name].parameters
        assert set(schema.get("properties", {})) == params, name

    # get_all_classes gained parameters but must not have gained a requirement:
    # every agent file calls it bare.
    assert not tools["get_all_classes"].parameters.get("required")

    # Same rule for the signature parameter: it narrows an answer, it never
    # gates one. A worker prompt written before it existed passes className and
    # methodName only, and that has to keep meaning "every overload".
    for name in ("get_method_by_name", "get_xrefs_to_method", "get_overrides",
                 "get_callees", "get_smali_of_method"):
        assert "signature" not in tools[name].parameters.get("required", []), name

    # The two searches gained the same optional limit. Their own parameter stays
    # required and the limit must not join it -- agents call both with one
    # argument, 15 and 14 times across the caller's agent files.
    assert tools["search_method_by_name"].parameters["required"] == ["methodName"]
    assert tools["search_classes_by_keyword"].parameters["required"] == ["keyword"]

    # And the same rule for every tool that gained limit in this round: the cap
    # is a default the caller can override, never something they must supply.
    # get_all_resource_file_names in particular has to stay callable bare.
    for name in ("get_xrefs_to_class", "get_xrefs_to_method", "get_xrefs_to_field",
                 "get_callees", "get_overrides", "get_methods_of_class",
                 "get_fields_of_class", "get_all_resource_file_names"):
        assert "limit" not in tools[name].parameters.get("required", []), name
    assert not tools["get_all_resource_file_names"].parameters.get("required")


def test_results_are_a_single_json_block_like_the_old_java_server():
    """The Spring AI server JSON-serialised every result into one text block.
    FastMCP would otherwise split a list into one block per element -- 72345
    blocks for get_all_classes on a real APK."""
    import json as _json

    from jadx_mcp import _json as to_json

    # compact separators, matching the Java converter byte for byte
    assert to_json(["a", "b"]) == '["a","b"]'
    assert to_json({"k": ["v"]}) == '{"k":["v"]}'
    # plain strings were JSON-quoted too, escapes and all
    assert to_json("line1\nline2") == '"line1\\nline2"'
    assert _json.loads(to_json("<?xml?>")) == "<?xml?>"


def test_every_tool_declares_a_string_return():
    """Named for a universal, so it checks the universal.

    The previous body asserted only that no tool declares an ARRAY return --
    which a tool annotated `-> dict` or `-> int` passes while breaking the same
    contract in the same way. The claim worth pinning has two halves and both
    are checkable: the annotation is `str`, and structured_output is off so
    FastMCP publishes no output schema and emits one text block. Together those
    are what make every tool result the `_json(...)` string every worker agent
    was written against; either alone is not.
    """
    tools = build_server()._tool_manager.list_tools()
    assert len(tools) == len(EXPECTED_TOOLS)

    for tool in tools:
        # get_type_hints, not the raw annotation: jadx_mcp.py has
        # `from __future__ import annotations`, so every annotation is the
        # STRING "str" and an `is str` check on it silently never holds.
        assert typing.get_type_hints(tool.fn).get("return") is str, tool.name
        assert tool.fn_metadata.output_schema is None, (
            f"{tool.name} publishes an output schema, so structured_output is on "
            f"and the result stops being one text block"
        )


def test_the_string_tools_send_max_bytes_only_when_given():
    """The three tools that return one big string, and the only accident path
    in the whole server that needed no opt-in at all.

    Measured on a large production APK before they had a guard, as the tool result a caller
    receives: get_resource_file on lib/arm64-v8a/libapp.so is 150,606,754 bytes
    and on assets/audience_network.dex 11,879,027, get_class_source on
    com.example.app.R.id 6,157,819, get_smali_of_class on
    kotlin.collections.ArraysKt___ArraysKt 3,605,169.

    The second of those needs no argument anywhere: the name is in the DEFAULT
    capped 1000-name listing get_all_resource_file_names returns, under a
    docstring that says to feed any entry to get_resource_file.

    maxBytes rather than limit, because the unit is different -- bytes of the
    tool result, not entries in a list. Same 0-widens rule, and absent stays
    absent on the wire so the daemon owns the default. 0 widens and now also
    announces itself: an uncapped answer over 1 MB leads with "@oversized: ",
    exactly as limit=0 does on a list."""
    seen = []

    def handler(request):
        seen.append(dict(request.url.params))
        return httpx.Response(200, json={"source": "", "content": "", "smali": ""})

    c = client_for(handler)
    c.get_class_source("com.example.app.R.id")
    c.get_class_source("com.example.app.R.id", maxBytes=0)
    c.get_resource_file("lib/arm64-v8a/libapp.so")
    c.get_resource_file("lib/arm64-v8a/libapp.so", maxBytes=4096)
    c.get_smali_of_class("kotlin.collections.ArraysKt___ArraysKt")
    c.get_smali_of_class("kotlin.collections.ArraysKt___ArraysKt", maxBytes=0)

    assert seen == [
        {"className": "com.example.app.R.id"},
        # 0 means uncapped and is a real value, so it goes on the wire -- the
        # same trap limit has, in the same direction: 0 widens.
        {"className": "com.example.app.R.id", "maxBytes": "0"},
        {"fileName": "lib/arm64-v8a/libapp.so"},
        {"fileName": "lib/arm64-v8a/libapp.so", "maxBytes": "4096"},
        {"className": "kotlin.collections.ArraysKt___ArraysKt"},
        {"className": "kotlin.collections.ArraysKt___ArraysKt", "maxBytes": "0"},
    ]


def test_max_bytes_is_never_required():
    """Every worker calls these with one argument. A required maxBytes would
    break all of them, and a cap the caller must supply is not a default."""
    tools = {t.name: t for t in build_server()._tool_manager.list_tools()}

    for name in ("get_class_source", "get_resource_file", "get_smali_of_class"):
        assert "maxBytes" in tools[name].parameters["properties"], name
        assert "maxBytes" not in tools[name].parameters.get("required", []), name


def test_one_check_finds_a_notice_in_a_bare_string_result_too():
    """The fourth container, and the one the rule quietly did not cover.

    A list carries a notice as a trailing element and a map as a value under a
    key. A bare string has neither -- so get_method_by_name wrote its
    "@ambiguous" behind a "// " to keep the result valid Java, which meant the
    string did not start with '@' and the documented check found nothing, and
    get_smali_of_method emitted no notice at all while silently concatenating
    all nine overloads of a name.

    The notice now LEADS the string, bare. The response is then a string
    beginning '@', so the same walker that finds a notice in the other three
    containers finds it here, with no per-tool special case."""

    def notices(payload):
        if isinstance(payload, dict):
            return [n for v in payload.values() for n in notices(v)]
        if isinstance(payload, list):
            return [n for v in payload for n in notices(v)]
        return [payload] if isinstance(payload, str) and payload.startswith("@") else []

    ambiguous = ('@ambiguous: "contains" names 9 methods in this class and this'
                 " answer covers all of them: contains([BB)Z, contains([CC)Z."
                 "\n\n.method public static contains([BB)Z\n")
    truncated = ('@truncated: getResourceFile cut "lib/arm64-v8a/libapp.so" to fit'
                 " maxBytes=1048576; the whole answer is 48628640 bytes.\n\n\x7fELF")

    assert notices(ambiguous) == [ambiguous]
    assert notices(truncated) == [truncated]
    # and an answer that really is about one method, or really is whole, is silent
    assert notices(".method public static contains([BB)Z\n") == []
    assert notices("<?xml version=\"1.0\"?>") == []


def test_resolved_in_apk_is_a_lead_and_not_a_completeness_signal():
    """The over-fire the doc's check had. "@resolved_in_apk" is an '@'-prefixed
    KEY present on nearly every default sweep -- it names the in-APK types a
    sink was reached through, which is a lead worth reading, not a statement
    that anything is missing. A check written over keys fires on it constantly
    and teaches the reader to ignore markers.

    Written over STRINGS it never fires, because its value is class names."""

    def notices(payload):
        if isinstance(payload, dict):
            return [n for v in payload.values() for n in notices(v)]
        if isinstance(payload, list):
            return [n for v in payload for n in notices(v)]
        return [payload] if isinstance(payload, str) and payload.startswith("@") else []

    sweep = {
        "webview_loadurl": [{"className": "com.a.B", "methodName": "loadUrl"}],
        "@resolved_in_apk": {
            "webview_loadurl": ["com.example.webview.AppWebView"],
        },
    }

    assert notices(sweep) == []
    assert [k for k in sweep if k.startswith("@")] == ["@resolved_in_apk"]
