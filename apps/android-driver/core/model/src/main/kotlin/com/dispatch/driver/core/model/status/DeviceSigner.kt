package com.dispatch.driver.core.model.status

/** Signing occurs before the outbox transaction commits; retries never re-sign. */
interface DeviceSigner {
    /** Re-evaluate account/role visibility even when no Room row changed. */
    val changes: kotlinx.coroutines.flow.Flow<Unit> get() = kotlinx.coroutines.flow.flowOf(Unit)
    fun ownerSubject(): String?
    fun visibleRoleAssignmentId(): String?
    fun sign(declaration: StatusDeclaration, roleAssignmentId: String): StatusDeclaration
}
