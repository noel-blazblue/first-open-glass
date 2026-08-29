package com.glass.dining.shared.link

object P2pJoinPolicy {
    const val DIRECT_PREFIX = "192.168.49."

    fun keepCurrentOffer(currentSsid: String?, joined: Boolean, nextSsid: String): Boolean {
        return !joined && !currentSsid.isNullOrBlank() && currentSsid == nextSsid
    }

    fun ignoreOffer(joined: Boolean, currentSsid: String?, nextSsid: String): Boolean {
        return joined && currentSsid == nextSsid
    }

    fun readyIp(groupFormed: Boolean, isGroupOwner: Boolean, ipv4: String): String? {
        if (!groupFormed || isGroupOwner) return null
        if (!ipv4.startsWith(DIRECT_PREFIX)) return null
        return ipv4
    }

    fun mayDiscover(active: Boolean, joined: Boolean): Boolean = active && !joined

    fun mayConnect(active: Boolean, joined: Boolean, connecting: Boolean): Boolean {
        return active && !joined && !connecting
    }

    fun mayCreateGroup(sameAttempt: Boolean, alreadyOffered: Boolean, creating: Boolean): Boolean {
        if (creating) return false
        if (sameAttempt && alreadyOffered) return false
        return true
    }

    fun acceptAttempt(current: String, incoming: String): Boolean {
        return incoming.isBlank() || incoming == current
    }
}
