package com.v2ray.ang.handler

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FILTERNET: who owns the tunnel.
 *
 * Android gives an app exactly one VPN service, so the private tab and the
 * home tab cannot each hold their own. What they can have is ownership: the
 * one that started the tunnel owns it, and the other refuses to touch it
 * until the owner lets go.
 *
 * Without this the two tabs were the same button wearing two hats - starting
 * the hunt from the private tab left the home tab looking connected, and
 * hitting disconnect there silently stole the tunnel out from under it.
 *
 * The owner is kept in MMKV as well as memory because the service outlives
 * the activity: the user can swipe the app away mid-hunt, come back, and the
 * tunnel is still up. Memory alone would have forgotten who started it.
 */
object FilternetMode {

    enum class Owner {
        /** Nothing is running, or the tunnel was started outside the app. */
        NONE,

        /** The home tab's connect button started it. */
        MAIN,

        /** The private tab's deep hunt started it. */
        INTERNAL,
    }

    private const val KEY_OWNER = "filternet_tunnel_owner"

    /** The guid the home tab had selected before the hunt borrowed it. */
    private const val KEY_RESTORE = "filternet_restore_guid"

    private val _owner = MutableStateFlow(readOwner())
    val owner: StateFlow<Owner> = _owner.asStateFlow()

    private fun readOwner(): Owner = runCatching {
        Owner.valueOf(MmkvManager.decodeSettingsString(KEY_OWNER, Owner.NONE.name) ?: Owner.NONE.name)
    }.getOrDefault(Owner.NONE)

    fun current(): Owner = _owner.value

    fun isInternal(): Boolean = _owner.value == Owner.INTERNAL

    /**
     * Records who is starting the tunnel. Call this *before* the service goes
     * up, so the first state broadcast already finds the right owner.
     */
    fun claim(owner: Owner) {
        if (_owner.value == owner) return
        _owner.value = owner
        runCatching { MmkvManager.encodeSettings(KEY_OWNER, owner.name) }
    }

    /** The tunnel is down: nobody owns anything. */
    fun release() = claim(Owner.NONE)

    /**
     * Remembers the profile the home tab was pointing at, because the hunt is
     * about to point it somewhere else. Null clears it.
     */
    fun rememberSelection(guid: String?) = runCatching {
        MmkvManager.encodeSettings(KEY_RESTORE, guid.orEmpty())
    }

    /** Takes back the remembered profile, once. */
    fun consumeSelection(): String? = runCatching {
        MmkvManager.decodeSettingsString(KEY_RESTORE, "")
            ?.takeIf { it.isNotBlank() }
            ?.also { MmkvManager.encodeSettings(KEY_RESTORE, "") }
    }.getOrNull()

    /** Test seam - the object outlives individual tests otherwise. */
    fun resetForTest() {
        _owner.value = Owner.NONE
    }
}
