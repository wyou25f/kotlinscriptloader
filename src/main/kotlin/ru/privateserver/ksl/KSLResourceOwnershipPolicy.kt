package ru.privateserver.ksl

/**
 * Addons may remove only resources they registered.
 * Calls made outside an addon's lifecycle context remain trusted host operations.
 */
internal object KSLResourceOwnershipPolicy {
    fun mayUnregister(requester: String?, resourceOwner: String?): Boolean =
        requester == null || requester == resourceOwner
}
