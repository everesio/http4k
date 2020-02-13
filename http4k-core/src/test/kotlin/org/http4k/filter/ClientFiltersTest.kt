package org.http4k.filter

import com.natpryce.hamkrest.absent
import com.natpryce.hamkrest.and
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.isA
import com.natpryce.hamkrest.present
import kotlinx.coroutines.runBlocking
import org.http4k.core.Body
import org.http4k.core.HttpHandler
import org.http4k.core.MemoryRequest
import org.http4k.core.MemoryResponse
import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.Method.PUT
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Uri
import org.http4k.core.UriTemplate
import org.http4k.core.parse
import org.http4k.core.then
import org.http4k.filter.GzipCompressionMode.Memory
import org.http4k.filter.GzipCompressionMode.Streaming
import org.http4k.filter.SamplingDecision.Companion.SAMPLE
import org.http4k.hamkrest.hasBody
import org.http4k.hamkrest.hasHeader
import org.http4k.hamkrest.hasStatus
import org.http4k.routing.RoutedRequest
import org.http4k.routing.RoutedResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.util.concurrent.atomic.AtomicReference

class ClientFiltersTest {
    val server = HttpHandler { request: Request ->
        when (request.uri.path) {
            "/redirect" -> Response(Status.FOUND).header("location", "/ok")
            "/loop" -> Response(Status.FOUND).header("location", "/loop")
            "/absolute-target" -> if (request.uri.host == "example.com") Response(OK).body("absolute") else Response(Status.INTERNAL_SERVER_ERROR)
            "/absolute-redirect" -> Response(Status.MOVED_PERMANENTLY).header("location", "http://example.com/absolute-target")
            "/redirect-with-charset" -> Response(Status.MOVED_PERMANENTLY).header("location", "/destination; charset=utf8")
            "/destination" -> Response(OK).body("destination")
            "/ok" -> Response(OK).body("ok")
            else -> Response(OK).let { if (request.query("foo") != null) it.body("with query") else it }
        }
    }

    private val followRedirects = ClientFilters.FollowRedirects().then(server)

    @Test
    fun `does not follow redirect by default`() = runBlocking {
        val defaultClient = server
        assertThat(defaultClient(Request(GET, "/redirect")), equalTo(Response(Status.FOUND).header("location", "/ok")))
    }

    @Test
    fun `follows redirect for temporary redirect response`() = runBlocking {
        assertThat(followRedirects(Request(GET, "/redirect")), equalTo(Response(OK).body("ok")))
    }

    @Test
    fun `follows redirect for post`() = runBlocking {
        assertThat(followRedirects(Request(POST, "/redirect")), equalTo(Response(OK).body("ok")))
    }

    @Test
    fun `follows redirect for put`() = runBlocking {
        assertThat(followRedirects(Request(PUT, "/redirect")), equalTo(Response(OK).body("ok")))
    }

    @Test
    fun `supports absolute redirects`() = runBlocking {
        assertThat(followRedirects(Request(GET, "/absolute-redirect")), equalTo(Response(OK).body("absolute")))
    }

    @Test
    fun `discards query parameters in relative redirects`() = runBlocking {
        assertThat(followRedirects(Request(GET, "/redirect?foo=bar")), equalTo(Response(OK).body("ok")))
    }

    @Test
    fun `discards charset from location header`() = runBlocking {
        assertThat(followRedirects(Request(GET, "/redirect-with-charset")), equalTo(Response(OK).body("destination")))
    }

    @Test
    fun `prevents redirection loop after 10 redirects`() = runBlocking {
        try {
            followRedirects(Request(GET, "/loop"))
            fail("should have looped")
        } catch (e: IllegalStateException) {
            assertThat(e.message, equalTo("Too many redirection"))
        }
    }

    @BeforeEach
    fun before() {
        ZipkinTraces.THREAD_LOCAL.remove()
    }

    @Test
    fun `adds request tracing to outgoing request when already present`() = runBlocking {
        val zipkinTraces = ZipkinTraces(TraceId("originalTraceId"), TraceId("originalSpanId"), TraceId("originalParentId"), SAMPLE)
        ZipkinTraces.THREAD_LOCAL.set(zipkinTraces)

        var start: Pair<Request, ZipkinTraces>? = null
        var end: Triple<Request, Response, ZipkinTraces>? = null

        val svc = ClientFilters.RequestTracing(
            { req, trace -> start = req to trace },
            { req, resp, trace -> end = Triple(req, resp, trace) }
        ).then {
            val actual = ZipkinTraces(it)
            assertThat(actual, equalTo(ZipkinTraces(TraceId("originalTraceId"), actual.spanId, TraceId("originalSpanId"), SAMPLE)))
            assertThat(actual.spanId, !equalTo(zipkinTraces.spanId))
            Response(OK)
        }

        assertThat(svc(Request(GET, "")), equalTo(Response(OK)))
        assertThat(start, equalTo(Request(GET, "") to ZipkinTraces(TraceId("originalTraceId"), end!!.third.spanId, TraceId("originalSpanId"), SAMPLE)))
        assertThat(end, equalTo(Triple(Request(GET, ""), Response(OK), ZipkinTraces(TraceId("originalTraceId"), end!!.third.spanId, TraceId("originalSpanId"), SAMPLE))))
    }

    @Test
    fun `adds new request tracing to outgoing request when not present`() = runBlocking {
        val svc = ClientFilters.RequestTracing().then { it ->
            val actual = ZipkinTraces(it)
            assertThat(actual, present())
            assertThat(actual.parentSpanId, absent())
            Response(OK)
        }

        assertThat(svc(Request(GET, "")), equalTo(Response(OK)))
    }

    @Test
    fun `set host on client`() = runBlocking {
        val handler = ClientFilters.SetHostFrom(Uri.of("http://localhost:123")).then { Response(OK).header("Host", it.header("Host")).body(it.uri.toString()) }
        assertThat(handler(Request(GET, "/loop")), hasBody("http://localhost:123/loop").and(hasHeader("Host", "localhost:123")))
    }

    @Test
    fun `set host without port on client`() = runBlocking {
        val handler = ClientFilters.SetHostFrom(Uri.of("http://localhost")).then { Response(OK).header("Host", it.header("Host")).body(it.uri.toString()) }
        assertThat(handler(Request(GET, "/loop")), hasBody("http://localhost/loop").and(hasHeader("Host", "localhost")))
    }

    @Test
    fun `set host without port on client does not set path`() = runBlocking {
        val handler = ClientFilters.SetHostFrom(Uri.of("http://localhost/a-path")).then { Response(OK).header("Host", it.header("Host")).body(it.uri.toString()) }
        assertThat(handler(Request(GET, "/loop")), hasBody("http://localhost/loop").and(hasHeader("Host", "localhost")))
    }

    @Test
    fun `set base uri appends path`() = runBlocking {
        val handler = ClientFilters.SetBaseUriFrom(Uri.of("http://localhost/a-path")).then { Response(OK).header("Host", it.header("Host")).body(it.uri.toString()) }
        assertThat(handler(Request(GET, "/loop")), hasBody("http://localhost/a-path/loop").and(hasHeader("Host", "localhost")))
    }

    @Test
    fun `set base uri appends path and copy other uri details`() = runBlocking {
        val handler = ClientFilters.SetBaseUriFrom(Uri.of("http://localhost/a-path?a=b")).then { Response(OK).header("Host", it.header("Host")).body(it.toString()) }

        val response = handler(Request(GET, "/loop").query("foo", "bar"))

        val reconstructedRequest = Request.parse(response.bodyString())
        assertThat(reconstructedRequest, equalTo(Request(GET, "http://localhost/a-path/loop").query("a", "b").query("foo", "bar").header("Host", "localhost")))
    }

    @Nested
    inner class Gzip {
        @Test
        fun `requests have an accept-encoding encoding with gzip`() = runBlocking {
            val handler = ClientFilters.GZip().then {
                assertThat(it, hasHeader("accept-encoding", "gzip"))
                Response(OK)
            }

            assertThat(handler(Request(GET, "/")), hasStatus(OK))
        }

        @Test
        fun `gzip request and gunzip in-memory response`() = runBlocking {
            val handler = ClientFilters.GZip().then {
                assertThat(it, hasHeader("content-encoding", "gzip").and(hasBody(equalTo(Body("hello").gzipped().body))))
                Response(OK).header("content-encoding", "gzip").body(it.body)
            }

            assertThat(handler(Request(GET, "/").body("hello")), hasBody("hello"))
        }

        @Test
        fun `in-memory empty bodies are not encoded`() = runBlocking {
            val handler = ClientFilters.GZip().then {
                assertThat(it, hasBody(equalTo(Body.EMPTY)).and(!hasHeader("content-encoding", "gzip")))
                Response(OK).body(Body.EMPTY)
            }

            assertThat(handler(Request(GET, "/").body(Body.EMPTY)), hasStatus(OK))
        }

        @Test
        fun `in-memory encoded empty responses are handled`() = runBlocking {
            val handler = ClientFilters.GZip().then {
                Response(OK).header("content-encoding", "gzip").body(Body.EMPTY)
            }

            assertThat(handler(Request(GET, "/").body(Body.EMPTY)), hasStatus(OK))
        }

        @Test
        fun `gzip request and gunzip streamed response`() = runBlocking {
            val handler = ClientFilters.GZip(Streaming).then {
                assertThat(it, hasHeader("content-encoding", "gzip").and(hasBody(equalTo(Body("hello").gzippedStream().body))))
                Response(OK).header("content-encoding", "gzip").body(Body("hello").gzippedStream().body)
            }

            assertThat(handler(Request(GET, "/").body("hello")), hasStatus(OK))
        }

        @Test
        fun `streaming empty bodies are not encoded`() = runBlocking {
            val handler = ClientFilters.GZip(Streaming).then {
                assertThat(it, hasBody(equalTo(Body.EMPTY)).and(!hasHeader("content-encoding", "gzip")))
                Response(OK).body(Body.EMPTY)
            }

            assertThat(handler(Request(GET, "/").body(Body.EMPTY)), hasStatus(OK))
        }

        @Test
        fun `streaming encoded empty responses are handled`() = runBlocking {
            val handler = ClientFilters.GZip(Streaming).then {
                Response(OK).header("content-encoding", "gzip").body(Body.EMPTY)
            }

            assertThat(handler(Request(GET, "/").body(Body.EMPTY)), hasStatus(OK))
        }

        @Test
        fun `passes through non-gzipped response`() = runBlocking {
            val handler = ClientFilters.GZip().then {
                Response(OK).body("hello")
            }

            assertThat(handler(Request(GET, "/").body("hello")), hasBody("hello"))
        }
    }

    @Nested
    inner class AcceptGZip {
        @Test
        fun `request bodies are not encoded`() = runBlocking {
            val handler = ClientFilters.AcceptGZip().then {
                assertThat(it, hasBody(equalTo("a value"))
                    .and(!hasHeader("content-encoding", "gzip")))
                Response(OK)
            }

            assertThat(handler(Request(GET, "/").body("a value")), hasStatus(OK))
        }

        @Test
        fun `requests have an accept-encoding encoding with gzip`() = runBlocking {
            val handler = ClientFilters.AcceptGZip().then {
                assertThat(it, hasHeader("accept-encoding", "gzip"))
                Response(OK)
            }

            assertThat(handler(Request(GET, "/")), hasStatus(OK))
        }

        @Test
        fun `in-memory encoded empty responses are handled`() = runBlocking {
            val handler = ClientFilters.AcceptGZip().then {
                Response(OK).header("content-encoding", "gzip").body(Body.EMPTY)
            }

            assertThat(handler(Request(GET, "/").body(Body.EMPTY)), hasStatus(OK).and(hasBody("")))
        }

        @Test
        fun `streaming encoded empty responses are handled`() = runBlocking {
            val handler = ClientFilters.AcceptGZip(Streaming).then {
                Response(OK).header("content-encoding", "gzip").body(Body.EMPTY)
            }

            assertThat(handler(Request(GET, "/")), hasStatus(OK).and(hasBody("")))
        }

        @Test
        fun `in-memory responses are ungzipped`() = runBlocking {
            val handler = ClientFilters.AcceptGZip(Memory).then {
                Response(OK).header("content-encoding", "gzip")
                    .body(Body("hello").gzippedStream().body)
            }

            assertThat(handler(Request(GET, "/")), hasStatus(OK).and(hasBody("hello")))
        }

        @Test
        fun `streaming responses are ungzipped`() = runBlocking {
            val handler = ClientFilters.AcceptGZip(Streaming).then {
                Response(OK).header("content-encoding", "gzip")
                    .body(Body("hello").gzippedStream().body)
            }

            assertThat(handler(Request(GET, "/")), hasStatus(OK).and(hasBody("hello")))
        }

        @Test
        fun `passes through non-gzipped response`() = runBlocking {
            val handler = ClientFilters.AcceptGZip().then {
                Response(OK).body("hello")
            }

            assertThat(handler(Request(GET, "/")), hasBody("hello"))
        }
    }

    @Test
    fun `clean proxy cleans request and response by reconstructing it on the way in and out`() = runBlocking {

        val captured = AtomicReference<Request>()

        val req = Request(GET, "")

        val resp = Response(OK)

        val app = ClientFilters.CleanProxy().then { r: Request ->
            captured.set(r)
            RoutedResponse(resp, UriTemplate.from("foo"))
        }

        val output = app(RoutedRequest(req, UriTemplate.from("foo")))

        assertThat(captured.get(), equalTo(req).and(isA<MemoryRequest>()))
        assertThat(output, equalTo(resp).and(isA<MemoryResponse>()))
    }
}
