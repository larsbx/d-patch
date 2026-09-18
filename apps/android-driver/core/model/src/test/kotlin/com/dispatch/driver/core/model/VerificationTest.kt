package com.dispatch.driver.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section 33.3 requires that `BiometricPrompt` success is recorded as
 * `SYSTEM_BIOMETRIC` and never as `FACE_1_TO_1`, and Section 15 acceptance
 * criterion 22 states it as a product guarantee. These pin the distinction at
 * the type level so it cannot erode through a display change.
 */
class VerificationTest {

    @Test
    fun `system biometric is not face verification`() {
        assertFalse(VerificationMethod.SYSTEM_BIOMETRIC.isFaceVerification)
    }

    @Test
    fun `only the one-to-one comparison counts as face verification`() {
        val faceMethods = VerificationMethod.entries.filter { it.isFaceVerification }
        assertEquals(listOf(VerificationMethod.FACE_1_TO_1), faceMethods)
    }

    @Test
    fun `no label other than FACE_1_TO_1 mentions a face`() {
        VerificationMethod.entries
            .filterNot { it == VerificationMethod.FACE_1_TO_1 }
            .forEach { method ->
                assertFalse(
                    "${method.name} must not be labelled as a face check",
                    method.displayLabel.lowercase().contains("face"),
                )
            }
    }

    @Test
    fun `only VERIFIED approves an action`() {
        val approving = FaceVerificationResult.entries.filter { it.isApproval }
        assertEquals(listOf(FaceVerificationResult.VERIFIED), approving)
    }

    @Test
    fun `a quality failure is not counted as a mismatch`() {
        assertFalse(FaceVerificationResult.QUALITY_INSUFFICIENT.isMismatch)
        assertTrue(FaceVerificationResult.NOT_MATCHED.isMismatch)
    }

    @Test
    fun `cancellation and lockout never approve`() {
        listOf(
            FaceVerificationResult.USER_CANCELLED,
            FaceVerificationResult.LOCKED_OUT,
            FaceVerificationResult.CONSENT_REQUIRED,
            FaceVerificationResult.ENROLLMENT_REQUIRED,
            FaceVerificationResult.DEVICE_UNTRUSTED,
            FaceVerificationResult.CHALLENGE_INVALID,
            FaceVerificationResult.PROVIDER_ERROR,
            FaceVerificationResult.REVIEW_REQUIRED,
        ).forEach { assertFalse("${it.name} must not approve", it.isApproval) }
    }

    @Test
    fun `the taxonomy covers every result in Section 7_4`() {
        val expected = setOf(
            "VERIFIED", "NOT_MATCHED", "LIVENESS_FAILED", "QUALITY_INSUFFICIENT",
            "MULTIPLE_FACES", "USER_CANCELLED", "LOCKED_OUT", "CONSENT_REQUIRED",
            "ENROLLMENT_REQUIRED", "DEVICE_UNTRUSTED", "SENSOR_UNAVAILABLE",
            "CHALLENGE_INVALID", "PROVIDER_ERROR", "REVIEW_REQUIRED",
        )
        assertEquals(expected, FaceVerificationResult.entries.map { it.name }.toSet())
    }
}
