package com.group3.blecrowd

import java.security.MessageDigest
import java.util.UUID

/**
 * Privacy: real MAC addresses are never written to disk (CSV holds hashes only).
 * They can optionally be shown on screen via the "Show real MAC addresses" toggle (memory only).
 * We keep SHA-256(sessionSalt + MAC), truncated to 12 hex chars (48 bits).
 * The salt is random per app process, so IDs can't be linked across sessions,
 * but they stay stable within a session so counting still works.
 */
object HashUtil {
    val sessionSalt: String = UUID.randomUUID().toString()

    private val digest = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

    private const val HEX = "0123456789abcdef"

    fun hash(mac: String): String {
        val bytes = digest.get()!!.digest((sessionSalt + mac).toByteArray())
        val sb = StringBuilder(12)
        for (i in 0 until 6) {
            val v = bytes[i].toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }
}
