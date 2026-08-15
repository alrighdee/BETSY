package org.betsy.update

/** Outcome of one look at the latest published GitHub release. */
sealed class UpdateStatus {
    /** [version] is newer than the installed name and should be offered. */
    data class Available(
        val version: String,
        val url: String,
    ) : UpdateStatus()

    /** Installed name is equal to or ahead of `/latest`. */
    data class Current(
        val installed: String,
    ) : UpdateStatus()

    /** The check failed; Connect stays silent and Settings shows [reason]. */
    data class Unknown(
        val reason: String,
    ) : UpdateStatus()
}
