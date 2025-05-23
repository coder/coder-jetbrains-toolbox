package com.coder.toolbox.sdk.v2.models

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import java.text.DecimalFormat

private val formatter = DecimalFormat("#.00")

/**
 * Coder ssh network metrics. All properties are optional
 * because Coder Connect only populates `using_coder_connect`
 * while p2p doesn't populate this property.
 */
@JsonClass(generateAdapter = true)
data class NetworkMetrics(
    @Json(name = "p2p")
    val p2p: Boolean?,

    @Json(name = "latency")
    val latency: Double?,

    @Json(name = "preferred_derp")
    val preferredDerp: String?,

    @Json(name = "derp_latency")
    val derpLatency: Map<String, Double>?,

    @Json(name = "upload_bytes_sec")
    val uploadBytesSec: Long?,

    @Json(name = "download_bytes_sec")
    val downloadBytesSec: Long?,

    @Json(name = "using_coder_connect")
    val usingCoderConnect: Boolean?
) {
    fun toPretty(): String {
        if (usingCoderConnect == true) {
            return "You're connected using Coder Connect"
        }
        return if (p2p == true) {
            "Direct (${formatter.format(latency)}ms). You're connected peer-to-peer"
        } else {
            val derpLatency = derpLatency!![preferredDerp]
            val workspaceLatency = latency!!.minus(derpLatency!!)
            "You ↔ $preferredDerp (${formatter.format(derpLatency)}ms) ↔ Workspace (${formatter.format(workspaceLatency)}ms). You are connected through a relay"
        }
    }
}
