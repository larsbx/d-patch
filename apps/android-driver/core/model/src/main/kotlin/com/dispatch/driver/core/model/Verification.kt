package com.dispatch.driver.core.model

/**
 * How a participant was authenticated for an action.
 *
 * Section 7 turns on this distinction: `BiometricPrompt` reports that a strong
 * biometric or device credential succeeded, but the platform result does not
 * reliably say which modality was used. Section 25.6 therefore forbids labelling
 * it a face verification. Keeping the two as separate constants — rather than a
 * boolean or a shared "biometric" flag — makes the confusion impossible to
 * express rather than merely discouraged.
 */
enum class VerificationMethod {
    /** No verification was performed. */
    NONE,

    /** The session's device authentication only. */
    DEVICE_AUTHENTICATED,

    /**
     * Android `BiometricPrompt` with `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`
     * succeeded. This is the first-release mechanism. It MUST NOT be presented
     * as "face verified".
     */
    SYSTEM_BIOMETRIC,

    /**
     * An optional, consented, on-device one-to-one comparison against this
     * participant's own enrolled template succeeded (Section 7).
     *
     * Never produced by [SYSTEM_BIOMETRIC]. Disabled until the Section 7.7
     * provider, model, presentation-attack, accessibility, demographic
     * performance, consent, and jurisdiction gates are approved.
     */
    FACE_1_TO_1,
    ;

    /** Whether this method may be described to a user as face verification. */
    val isFaceVerification: Boolean
        get() = this == FACE_1_TO_1

    /** The label shown in the UI and recorded in the audit event. */
    val displayLabel: String
        get() = when (this) {
            NONE -> "Not verified"
            DEVICE_AUTHENTICATED -> "Device authenticated"
            SYSTEM_BIOMETRIC -> "Device biometric or passcode"
            FACE_1_TO_1 -> "Face verified"
        }
}

/**
 * The canonical face-verification result taxonomy of Section 7.4.
 *
 * Section 33.1 requires every provider result to map here exactly once, with an
 * unknown value failing as [PROVIDER_ERROR] rather than degrading to a
 * lower-confidence success.
 */
enum class FaceVerificationResult {
    VERIFIED,
    NOT_MATCHED,
    LIVENESS_FAILED,
    QUALITY_INSUFFICIENT,
    MULTIPLE_FACES,
    USER_CANCELLED,
    LOCKED_OUT,
    CONSENT_REQUIRED,
    ENROLLMENT_REQUIRED,
    DEVICE_UNTRUSTED,
    SENSOR_UNAVAILABLE,
    CHALLENGE_INVALID,
    PROVIDER_ERROR,
    REVIEW_REQUIRED,
    ;

    /** Whether this result may satisfy a verification requirement. */
    val isApproval: Boolean
        get() = this == VERIFIED

    /**
     * Whether this result is a biometric mismatch.
     *
     * Section 7.4 counts quality failures separately: at most three quality
     * retries are allowed per challenge, and a quality failure does not count
     * toward the two-mismatch limit.
     */
    val isMismatch: Boolean
        get() = this == NOT_MATCHED
}
