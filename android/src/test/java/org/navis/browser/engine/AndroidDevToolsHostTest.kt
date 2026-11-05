/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package org.navis.browser.engine

import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.navis.browser.engine.runtime.EngineRuntimePort
import org.navis.browser.engine.runtime.EngineSessionObserver
import org.navis.browser.engine.runtime.EngineSessionPort

class AndroidDevToolsHostTest {
    private class Fixture {
        lateinit var observer: EngineSessionObserver
        val opening = CompletableFuture<Boolean>()
        private val session = Proxy.newProxyInstance(EngineSessionPort::class.java.classLoader,
            arrayOf(EngineSessionPort::class.java)) { _, method, _ ->
            when (method.name) {
                "openDevTools" -> opening
                else -> error("Unexpected engine call: ${method.name}")
            }
        } as EngineSessionPort
        private val engine = Proxy.newProxyInstance(EngineRuntimePort::class.java.classLoader,
            arrayOf(EngineRuntimePort::class.java)) { _, method, args ->
            check(method.name == "createSession")
            observer = args[1] as EngineSessionObserver
            session
        } as EngineRuntimePort
        val host = AndroidDevToolsHost(engine, 2, 1, false, onClosed = {})
    }

    @Test fun engineFailureRetainsTheReasonInsteadOfBecomingFalse() {
        val f = Fixture()
        f.observer.onSessionError("Toolbox document timed out")
        try { f.host.ready.get(); fail("The engine error was discarded") }
        catch (error: ExecutionException) { assertEquals("Toolbox document timed out", error.cause?.message) }
    }

    @Test fun successfulOpeningIsNotOverwrittenByLaterTeardown() {
        val f = Fixture()
        f.opening.complete(true)
        f.observer.onSessionClosed()
        assertEquals(true, f.host.ready.get())
    }
}
