package top.kagg886.milky.console.util.protocol

import okio.Buffer
import kotlin.uuid.Uuid

/**
 * ================================================
 * Author:     iveou
 * Created on: 2026/7/14 11:28
 * ================================================
 */

data class Packet(
    val index: Int? = null,
    val size: Int? = null,
    val uuid: Uuid = Uuid.random(),
    val data: Buffer = Buffer()
)
