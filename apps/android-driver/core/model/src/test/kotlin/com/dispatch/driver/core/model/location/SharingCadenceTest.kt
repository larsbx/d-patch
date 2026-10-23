package com.dispatch.driver.core.model.location

import com.dispatch.driver.core.model.location.SharingCadence.Effect
import com.dispatch.driver.core.model.location.SharingCadence.Input
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * What a sharing device does, as a pure function of what happens to it
 * (Section 25.3 as amended by ADR-0014). Examples first; then the invariants,
 * checked over random histories.
 */
class SharingCadenceTest {

    private val realtime = DemandPolicy(DemandPolicy.Mode.REALTIME, sampleIntervalMs = 3_000, uploadIntervalMs = 0)
    private val trickle = DemandPolicy.TRICKLE

    private fun SharingCadence<Int>.run(vararg steps: Pair<Long, Input<Int>>): Pair<SharingCadence<Int>, List<Effect<Int>>> =
        steps.fold(this to emptyList()) { (cadence, effects), (now, input) ->
            cadence.step(input, now).let { (next, more) -> next to effects + more }
        }

    @Test
    fun `sharing starts by asking for fixes at the trickle`() {
        val (_, effects) = SharingCadence.start<Int>()
        assertEquals(listOf(Effect.RequestFixes(120_000)), effects)
    }

    @Test
    fun `a trickle batches, and wakes to send when the oldest sample is due`() {
        val (cadence, _) = SharingCadence.start<Int>()
        val (held, effects) = cadence.run(0L to Input.Acquired(1), 1_000L to Input.Acquired(2))

        assertEquals(listOf(Effect.WakeAt(600_000)), effects)
        assertEquals(listOf(Effect.Upload(listOf(1, 2))), held.step(Input.Tick, 600_000).second)
        assertEquals(emptyList<Effect<Int>>(), held.step(Input.Tick, 599_999).second)
    }

    @Test
    fun `someone watching makes the next fix come within seconds, and go at once`() {
        val (cadence, _) = SharingCadence.start<Int>()
        val (live, effects) = cadence.run(0L to Input.Acquired(1), 5L to Input.Policy(realtime), 3_005L to Input.Acquired(2))

        assertEquals(
            listOf(Effect.WakeAt(600_000), Effect.RequestFixes(3_000), Effect.Upload(listOf(1)), Effect.Upload(listOf(2))),
            effects,
        )
        assertEquals(listOf(Effect.RequestFixes(120_000)), live.step(Input.StreamLost, 4_000).second)
    }

    @Test
    fun `revoking stops fixes and sends the sealed batch, and nothing after`() {
        val (cadence, _) = SharingCadence.start<Int>()
        val (revoked, effects) = cadence.run(0L to Input.Acquired(1), 1L to Input.Revoked, 2L to Input.Acquired(2))

        assertEquals(listOf(Effect.WakeAt(600_000), Effect.StopFixes, Effect.Upload(listOf(1))), effects)
        assertEquals(emptyList<Effect<Int>>(), revoked.step(Input.Policy(realtime), 3).second)
    }

    @Test
    fun `the app coming to the foreground sends what is held (Section 25_3)`() {
        val (cadence, _) = SharingCadence.start<Int>()
        val (_, effects) = cadence.run(0L to Input.Acquired(1), 1L to Input.Foreground)
        assertEquals(listOf(Effect.WakeAt(600_000), Effect.Upload(listOf(1))), effects)
    }

    @Test
    fun `a batch holds at most 25`() {
        val (cadence, _) = SharingCadence.start<Int>()
        val (_, effects) = cadence.run(*Array(26) { it.toLong() to Input.Acquired(it) })
        assertEquals(Effect.Upload((0 until 25).toList()), effects.filterIsInstance<Effect.Upload<Int>>().single())
    }

    // ---- Invariants, over random histories -------------------------------

    private data class Trace(
        val steps: List<Triple<Long, Input<Int>, List<Effect<Int>>>>,
        val states: List<SharingCadence<Int>>,
    )

    private fun history(random: Random): Trace {
        val policies = listOf(realtime, trickle, DemandPolicy(DemandPolicy.Mode.REALTIME, 1_000, 5_000))
        val times = List(random.nextInt(1, 120)) { random.nextLong(0, 200_000) }.runningReduce(Long::plus)
        val inputs = times.mapIndexed { n, now ->
            now to when (random.nextInt(100)) {
                in 0..54 -> Input.Acquired(n)
                in 55..69 -> Input.Tick
                in 70..79 -> Input.Policy(policies.random(random))
                in 80..86 -> Input.StreamLost
                in 87..94 -> Input.Foreground
                else -> if (random.nextInt(4) == 0) Input.Revoked else Input.Tick
            }
        }
        val (start, effects) = SharingCadence.start<Int>()
        // The start is recorded as a step at 0 that took no input of note.
        val first = Trace(listOf(Triple(0L, Input.Tick, effects)), listOf(start))
        return inputs.fold(first) { trace, (now, input) ->
            val (next, more) = trace.states.last().step(input, now)
            Trace(trace.steps + Triple(now, input, more), trace.states + next)
        }
    }

    private fun forAll(check: (Trace) -> Unit) {
        val random = Random(2026_10_23)
        repeat(3_000) { check(history(random)) }
    }

    private val Trace.uploads get() = steps.flatMap { it.third }.filterIsInstance<Effect.Upload<Int>>()
    private val Trace.revokedAt get() = steps.indexOfFirst { it.second is Input.Revoked }.takeIf { it >= 0 }

    // Every sample acquired while sharing is sent exactly once, in order;
    // none acquired after revocation ever is.
    @Test
    fun `conservation - what is sent plus what is held is what was acquired while sharing`() = forAll { trace ->
        val sharing = trace.steps.take(trace.revokedAt ?: trace.steps.size)
        val acquired = sharing.map { it.second }.filterIsInstance<Input.Acquired<Int>>().map { it.sample }
        assertEquals(acquired, trace.uploads.flatMap { it.batch } + trace.states.last().held)
    }

    @Test
    fun `revocation is final - nothing is held, requested or sent after it`() = forAll { trace ->
        val at = trace.revokedAt ?: return@forAll
        assertTrue(trace.steps[at].third.first() is Effect.StopFixes)
        trace.steps.drop(at + 1).forEach { assertEquals(emptyList<Effect<Int>>(), it.third) }
        assertEquals(emptyList<Int>(), trace.states.last().held)
    }

    @Test
    fun `every upload holds between 1 and 25 samples`() = forAll { trace ->
        trace.uploads.forEach { assertTrue(it.batch.size in 1..25) }
    }

    // Liveness: nothing held is past its deadline after any step, and the
    // device was last asked to wake no later than the oldest sample's deadline.
    @Test
    fun `nothing is held past its deadline, and a wake is due by then`() = forAll { trace ->
        var wake: Long? = null
        trace.steps.zip(trace.states).forEach { (step, state) ->
            val (now, _, effects) = step
            effects.filterIsInstance<Effect.WakeAt>().lastOrNull()?.let { wake = it.atMs }
            val deadline = state.deadline ?: return@forEach
            assertTrue("held past its deadline at $now", now < deadline)
            assertTrue("no wake by $deadline", wake != null && wake!! <= deadline)
        }
    }

    @Test
    fun `while realtime, each sample leaves in the step that acquired it`() = forAll { trace ->
        trace.steps.zip(listOf(trace.states.first()) + trace.states).forEach { (step, before) ->
            val (_, input, effects) = step
            if (input is Input.Acquired && before.policy.uploadIntervalMs == 0L && !before.revoked) {
                assertTrue(effects.filterIsInstance<Effect.Upload<Int>>().any { input.sample in it.batch })
            }
        }
    }

    // The provider is asked for the policy's interval, and only when it changes.
    @Test
    fun `fix requests follow the policy, without repeats`() = forAll { trace ->
        val requests = trace.steps.flatMap { it.third }.filterIsInstance<Effect.RequestFixes>()
        requests.zipWithNext().forEach { (a, b) -> assertTrue(a.intervalMs != b.intervalMs) }
        val last = trace.states.last()
        if (!last.revoked) assertEquals(last.policy.sampleIntervalMs, requests.last().intervalMs)
    }
}
