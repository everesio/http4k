package org.http4k.traffic

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import kotlinx.coroutines.runBlocking
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.SERVICE_UNAVAILABLE
import org.http4k.hamkrest.hasStatus
import org.junit.jupiter.api.Test

class ResponderTest {

    private val request = Request(Method.GET, "/bob")
    private val request2 = Request(Method.GET, "/bob2")
    private val response = Response(Status.OK)

    @Test
    fun `Responder from Source replays stored responses or falls back to Service Unavailable`() = runBlocking {
        val cache = ReadWriteCache.Memory()
        cache[request] = response

        val responder = Responder.from(cache)
        assertThat(responder(request), equalTo(response))
        assertThat(responder(Request(Method.GET, "/rita")), hasStatus(SERVICE_UNAVAILABLE))
    }

    @Test
    fun `Responder from Replay replays stored responses or falls back to Service Unavailable`() = runBlocking {
        val stream = ReadWriteStream.Memory()
        stream[request] = response
        stream[request2] = response

        val responder = Responder.from(stream)

        assertThat(responder(request), equalTo(response))
        assertThat(responder(request2), equalTo(response))
        assertThat(responder(Request(Method.GET, "/rita")), hasStatus(SERVICE_UNAVAILABLE))
    }

}