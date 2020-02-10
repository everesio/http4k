package org.http4k.contract

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import kotlinx.coroutines.runBlocking
import org.http4k.contract.security.NoSecurity
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.then
import org.junit.jupiter.api.Test

class NoSecurityTest {
    @Test
    fun `no security is rather lax`() = runBlocking {
        val response = (NoSecurity.filter.then(HttpHandler { Response(Status.OK).body("hello") })(Request(Method.GET, "")))

        assertThat(response.status, equalTo(Status.OK))
        assertThat(response.bodyString(), equalTo("hello"))
    }
}