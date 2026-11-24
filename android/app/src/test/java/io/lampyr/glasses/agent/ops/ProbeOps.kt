package io.lampyr.glasses.agent.ops

import io.lampyr.glasses.agent.*

/** Test-only providers for name-based discovery (ActionRegistryTest.providersAreFoundByName). */
object ZzProbeOps : OpProvider {
    override fun ops() = listOf(OpSpec("zzprobe.ping", "Ping", "Test op found by name.", risk = Risk.READ) { ok("pong") })
}

/** Wrong shape on purpose: a class, not an object. */
class ZzBadOps : OpProvider { override fun ops() = emptyList<OpSpec>() }
