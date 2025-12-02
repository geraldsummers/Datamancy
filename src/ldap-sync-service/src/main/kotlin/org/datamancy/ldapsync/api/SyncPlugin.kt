package org.datamancy.ldapsync.api

/**
 * Represents an LDAP user to be synced to external services
 */
data class LdapUser(
    val uid: String,
    val email: String,
    val displayName: String,
    val cn: String,
    val sn: String,
    val groups: Set<String>,
    val uidNumber: Int,
    val gidNumber: Int,
    val attributes: Map<String, String> = emptyMap()
)

/**
 * Result of a sync operation for a single user
 */
sealed class SyncResult {
    data class Created(val userId: String, val details: String) : SyncResult()
    data class Updated(val userId: String, val details: String) : SyncResult()
    data class Skipped(val userId: String, val reason: String) : SyncResult()
    data class Failed(val userId: String, val error: String, val exception: Throwable? = null) : SyncResult()
}

/**
 * Plugin interface for syncing LDAP users to external services
 *
 * Each plugin implements sync logic for one service (Mailu, Mastodon, etc.)
 */
interface SyncPlugin {
    /**
     * Unique identifier for this plugin (e.g., "mailu", "mastodon")
     */
    val pluginId: String

    /**
     * Human-readable name
     */
    val pluginName: String

    /**
     * Initialize the plugin with configuration
     */
    suspend fun init(config: Map<String, String>)

    /**
     * Check if the plugin is healthy and can connect to target service
     */
    suspend fun healthCheck(): Boolean

    /**
     * Sync a single LDAP user to the target service
     * Returns SyncResult indicating what happened
     */
    suspend fun syncUser(user: LdapUser): SyncResult

    /**
     * Optional: Delete a user from the target service
     * Return true if deletion succeeded or user doesn't exist
     */
    suspend fun deleteUser(uid: String): Boolean = true

    /**
     * Optional: Perform cleanup/shutdown
     */
    suspend fun shutdown() {}

    /**
     * Get current sync statistics from the target service
     */
    suspend fun getStats(): Map<String, Any> = emptyMap()
}
