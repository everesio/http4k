package org.http4k.chaos

import com.natpryce.hamkrest.and
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import kotlinx.coroutines.runBlocking
import org.http4k.chaos.ChaosBehaviours.ReturnStatus
import org.http4k.chaos.ChaosStages.Repeat
import org.http4k.chaos.ChaosStages.Variable
import org.http4k.chaos.ChaosStages.Wait
import org.http4k.chaos.ChaosTriggers.Always
import org.http4k.core.Filter
import org.http4k.core.Method.DELETE
import org.http4k.core.Method.GET
import org.http4k.core.Method.OPTIONS
import org.http4k.core.Method.POST
import org.http4k.core.Method.TRACE
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.GATEWAY_TIMEOUT
import org.http4k.core.Status.Companion.INTERNAL_SERVER_ERROR
import org.http4k.core.Status.Companion.I_M_A_TEAPOT
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.OK
import org.http4k.core.then
import org.http4k.format.Jackson.asJsonObject
import org.http4k.hamkrest.hasHeader
import org.http4k.hamkrest.hasStatus
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

private val request = Request(GET, "")
private val response = Response(OK).body("body")

abstract class ChaosStageContract {
    abstract val asJson: String
    abstract val expectedDescription: String

    @Test
    fun `deserialises from JSON`() {
        val clock = Clock.fixed(Instant.EPOCH, ZoneId.of("UTC"))
        assertThat(asJson.asJsonObject().asStage(clock).toString(), equalTo(expectedDescription))
    }
}

class WaitTest : ChaosStageContract() {
    override val asJson = """{"type":"wait"}"""
    override val expectedDescription = "Wait"

    @Test
    fun `Wait does not match the response`() = runBlocking {
        val app = Wait.asFilter().then { response }
        assertThat(app(Request(GET, "")), equalTo(response))
        Unit
    }
}

class TriggeredTest : ChaosStageContract() {
    override val asJson = """{"type":"trigger","trigger":{"type":"always"},"behaviour":{"type":"body"}}"""
    override val expectedDescription = "Always SnipBody"
}

class RepeatTest : ChaosStageContract() {
    override val asJson = """{"type":"repeat","stages":[{"type":"wait"}]}"""
    override val expectedDescription = "Repeat [Wait]"

    @Test
    fun `repeat starts again at the beginning`() = runBlocking {
        val app = Repeat {
            chaosStage(I_M_A_TEAPOT).until { it.method == POST }
                .then(chaosStage(NOT_FOUND).until { it.method == OPTIONS })
                .then(chaosStage(GATEWAY_TIMEOUT).until { it.method == TRACE })
        }.until { it.method == DELETE }
            .asFilter().then { response }

        assertThat(app(Request(GET, "")), equalTo(Response(I_M_A_TEAPOT)))
        assertThat(app(Request(POST, "")), equalTo(Response(NOT_FOUND)))
        assertThat(app(Request(GET, "")), equalTo(Response(NOT_FOUND)))
        assertThat(app(Request(OPTIONS, "")), equalTo(Response(GATEWAY_TIMEOUT)))
        assertThat(app(Request(GET, "")), equalTo(Response(GATEWAY_TIMEOUT)))
        assertThat(app(Request(TRACE, "")), equalTo(Response(I_M_A_TEAPOT)))
        assertThat(app(Request(DELETE, "")), equalTo(response))
        Unit
    }
}

class VariableStageTest {
    @Test
    fun `should provide ability to modify stage at runtime`() = runBlocking {
        val variable = Variable()
        assertThat(variable.toString(), equalTo(("Wait")))
        assertThat(variable(request)!!.then { response }(request), equalTo(response))
        variable.current = Repeat { ReturnStatus(NOT_FOUND).appliedWhen(Always()) }
        assertThat(variable.toString(), equalTo(("Repeat [Always ReturnStatus (404)]")))
        assertThat(variable(request)!!.then { response }(request), hasStatus(NOT_FOUND.description("x-http4k-chaos")).and(hasHeader("x-http4k-chaos", Regex("Status 404"))))
        Unit
    }
}

class ChaosStageOperationsTest {
    @Test
    fun `until stops when the trigger is hit`() = runBlocking {
        val app = chaosStage(NOT_FOUND).until { it.method == POST }
            .asFilter().then { response }

        assertThat(app(Request(GET, "")), equalTo(Response(NOT_FOUND)))
        assertThat(app(Request(POST, "")), equalTo(response))
        assertThat(app(Request(GET, "")), equalTo(response))
        Unit
    }

    @Test
    fun `then moves onto the next stage`() = runBlocking {
        val app = chaosStage(I_M_A_TEAPOT).until { it.method == POST }
            .then(chaosStage(NOT_FOUND).until { it.method == TRACE })
            .then(chaosStage(INTERNAL_SERVER_ERROR))
            .asFilter().then { response }

        assertThat(app(Request(GET, "")), equalTo(Response(I_M_A_TEAPOT)))
        assertThat(app(Request(POST, "")), equalTo(Response(NOT_FOUND)))
        assertThat(app(Request(GET, "")), equalTo(Response(NOT_FOUND)))
        assertThat(app(Request(TRACE, "")), equalTo(Response(INTERNAL_SERVER_ERROR)))
        assertThat(app(Request(GET, "")), equalTo(Response(INTERNAL_SERVER_ERROR)))
        Unit
    }
}

private fun chaosStage(status: Status): Stage = object : Stage {
    override fun invoke(tx: Request) = Filter { { Response(status) } }
}
