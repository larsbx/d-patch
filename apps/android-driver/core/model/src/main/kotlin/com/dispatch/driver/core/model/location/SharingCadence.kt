package com.dispatch.driver.core.model.location

/**
 * What a sharing device does, as a pure function of what happens to it:
 * Section 25.3, with ADR-0014's demand-driven cadence.
 *
 * [step] takes an [Input] at a time in milliseconds and returns the next
 * cadence with the [Effect]s to carry out, in order. The service owns the
 * clock, the location provider and the network; this owns every decision, so
 * the invariants below are properties of one function:
 *
 * - **Conservation.** Every sample acquired while sharing is uploaded exactly
 *   once, in order, or is still [held]; none acquired after revocation is.
 * - **Revocation is final.** It stops fixes, sends the sealed batch, and the
 *   cadence does nothing afterwards.
 * - **Bounded batches.** An upload carries 1 to [MAX_BATCH] samples.
 * - **Liveness.** After any step nothing held is past its [deadline], and the
 *   last [Effect.WakeAt] is no later than it. Realtime (`uploadIntervalMs == 0`)
 *   sends each sample in the step that acquired it.
 * - **Requests follow the policy.** [Effect.RequestFixes] is emitted when the
 *   sample interval changes, and only then.
 */
class SharingCadence<S> private constructor(
    val policy: DemandPolicy,
    val held: List<S>,
    private val oldestAtMs: Long?,
    val revoked: Boolean,
) {
    sealed interface Input<out S> {
        /** The demand stream sent a policy. */
        data class Policy(val policy: DemandPolicy) : Input<Nothing>

        /** The demand stream closed or cannot be reached: trickle. */
        data object StreamLost : Input<Nothing>

        data class Acquired<S>(val sample: S) : Input<S>

        /** A wake asked for by [Effect.WakeAt], or any other moment to check the clock. */
        data object Tick : Input<Nothing>

        /** The app came to the foreground: Section 25.3 uploads then. */
        data object Foreground : Input<Nothing>

        data object Revoked : Input<Nothing>
    }

    sealed interface Effect<out S> {
        data class RequestFixes(val intervalMs: Long) : Effect<Nothing>
        data object StopFixes : Effect<Nothing>
        data class Upload<S>(val batch: List<S>) : Effect<S>

        /** Deliver [Input.Tick] at [atMs]; replaces any earlier wake. */
        data class WakeAt(val atMs: Long) : Effect<Nothing>
    }

    /** When the oldest held sample must have left, or null when nothing is held. */
    val deadline: Long? get() = oldestAtMs?.let { it + policy.uploadIntervalMs }

    fun step(input: Input<S>, nowMs: Long): Pair<SharingCadence<S>, List<Effect<S>>> {
        if (revoked) return this to emptyList()
        if (input is Input.Revoked) {
            return SharingCadence<S>(policy, emptyList(), null, revoked = true) to
                listOf(Effect.StopFixes) + upload()
        }

        val (applied, effects) = apply(input, nowMs)
        val (flushed, sent) = applied.flush(nowMs, force = input is Input.Foreground)
        val wake = flushed.deadline?.takeIf { it != deadline }?.let { Effect.WakeAt(it) }
        return flushed to effects + sent + listOfNotNull(wake)
    }

    private fun apply(input: Input<S>, nowMs: Long): Pair<SharingCadence<S>, List<Effect<S>>> = when (input) {
        is Input.Policy -> follow(input.policy)
        Input.StreamLost -> follow(DemandPolicy.TRICKLE)
        is Input.Acquired -> copy(held = held + input.sample, oldestAtMs = oldestAtMs ?: nowMs) to emptyList()
        Input.Tick, Input.Foreground, Input.Revoked -> this to emptyList()
    }

    private fun follow(next: DemandPolicy): Pair<SharingCadence<S>, List<Effect<S>>> =
        copy(policy = next) to listOfNotNull(
            Effect.RequestFixes(next.sampleIntervalMs).takeIf { next.sampleIntervalMs != policy.sampleIntervalMs },
        )

    private fun flush(nowMs: Long, force: Boolean): Pair<SharingCadence<S>, List<Effect<S>>> {
        val due = deadline?.let { nowMs >= it } ?: false
        return if (held.isNotEmpty() && (force || due || held.size >= MAX_BATCH)) {
            copy(held = emptyList(), oldestAtMs = null) to upload()
        } else {
            this to emptyList()
        }
    }

    private fun upload(): List<Effect<S>> = if (held.isEmpty()) emptyList() else listOf(Effect.Upload(held))

    private fun copy(policy: DemandPolicy = this.policy, held: List<S> = this.held, oldestAtMs: Long? = this.oldestAtMs) =
        SharingCadence(policy, held, oldestAtMs, revoked)

    companion object {
        /** Section 25.3's batch size. */
        const val MAX_BATCH = 25

        /** Sharing begins at the trickle, until the demand stream says otherwise. */
        fun <S> start(): Pair<SharingCadence<S>, List<Effect<S>>> =
            SharingCadence<S>(DemandPolicy.TRICKLE, emptyList(), null, revoked = false) to
                listOf(Effect.RequestFixes(DemandPolicy.TRICKLE.sampleIntervalMs))
    }
}
