package com.glass.dining.shared.indoor

import kotlin.math.abs

data class PoseSendCursor(
    val lastAt: Long = 0L,
    val lastPos: Vec3 = Vec3(),
    val lastYaw: Float = 0f,
    val sentOnce: Boolean = false,
)

/**
 * 姿态上行门限：Wi‑Fi DataChannel 可在说话时发；CXR 仍避开 PCM。
 */
object PoseSendGate {
    const val WIFI_MIN_MS = 200L
    const val WIFI_HEARTBEAT_MS = 500L
    const val WIFI_MOVE_M = 0.3f
    const val WIFI_YAW_DEG = 8f
    const val CXR_MIN_MS = 500L
    const val CXR_MOVE_M = 0.8f
    const val CXR_YAW_DEG = 12f

    fun wifi(
        cursor: PoseSendCursor,
        now: Long,
        pose: Pose3,
        lost: Boolean,
    ): Pair<Boolean, PoseSendCursor> {
        return decide(
            cursor = cursor,
            now = now,
            pose = pose,
            lost = lost,
            minMs = WIFI_MIN_MS,
            moveM = WIFI_MOVE_M,
            yawDeg = WIFI_YAW_DEG,
            heartbeatMs = WIFI_HEARTBEAT_MS,
            allowed = true,
        )
    }

    fun cxr(
        cursor: PoseSendCursor,
        now: Long,
        pose: Pose3,
        lost: Boolean,
        micOn: Boolean,
        navOn: Boolean,
    ): Pair<Boolean, PoseSendCursor> {
        return decide(
            cursor = cursor,
            now = now,
            pose = pose,
            lost = lost,
            minMs = CXR_MIN_MS,
            moveM = CXR_MOVE_M,
            yawDeg = CXR_YAW_DEG,
            heartbeatMs = Long.MAX_VALUE,
            allowed = !micOn && navOn,
        )
    }

    private fun decide(
        cursor: PoseSendCursor,
        now: Long,
        pose: Pose3,
        lost: Boolean,
        minMs: Long,
        moveM: Float,
        yawDeg: Float,
        heartbeatMs: Long,
        allowed: Boolean,
    ): Pair<Boolean, PoseSendCursor> {
        if (!allowed) return false to cursor
        if (cursor.sentOnce && now - cursor.lastAt < minMs) return false to cursor
        val moved = (pose.position - cursor.lastPos).length() >= moveM
        val turned = abs(Angle.deltaDeg(cursor.lastYaw, pose.yawDeg)) >= yawDeg
        val heartbeat = cursor.sentOnce && now - cursor.lastAt >= heartbeatMs
        if (cursor.sentOnce && !moved && !turned && !lost && !heartbeat) return false to cursor
        return true to PoseSendCursor(
            lastAt = now,
            lastPos = pose.position,
            lastYaw = pose.yawDeg,
            sentOnce = true,
        )
    }
}
