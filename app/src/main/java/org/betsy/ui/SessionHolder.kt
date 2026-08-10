package org.betsy.ui

import org.betsy.detect.VehicleInfo
import org.betsy.elm.ElmSession
import org.betsy.transport.ElmTransport

/**
 * Hands the connected session from ConnectActivity to BatteryActivity. Milestone 1 is a single
 * connect → monitor flow, so a plain singleton is sufficient; no Parcelable/process persistence.
 */
object SessionHolder {
    private var transport: ElmTransport? = null
    private var session: ElmSession? = null
    private var info: VehicleInfo? = null

    fun set(
        transport: ElmTransport,
        session: ElmSession,
        info: VehicleInfo,
    ) {
        this.transport = transport
        this.session = session
        this.info = info
    }

    fun session(): ElmSession = session ?: throw IllegalStateException("no active session")

    fun info(): VehicleInfo = info ?: throw IllegalStateException("no active session")

    fun close() {
        transport?.close()
        transport = null
        session = null
        info = null
    }
}
