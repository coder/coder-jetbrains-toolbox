package com.coder.toolbox.feed

import com.squareup.moshi.FromJson
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.ToJson

/**
 * Represents a JetBrains IDE product from the feed API.
 *
 * The API returns an array of products, each with a code and a list of releases.
 */
@JsonClass(generateAdapter = true)
data class IdeProduct(
    @Json(name = "code") val code: String,
    @Json(name = "intellijProductCode") val intellijProductCode: String?,
    @Json(name = "name") val name: String,
    @Json(name = "releases") val releases: List<IdeRelease> = emptyList()
)

/**
 * Represents an individual release of a JetBrains IDE product.
 */
@JsonClass(generateAdapter = true)
data class IdeRelease(
    @Json(name = "build") val build: String,
    @Json(name = "version") val version: String,
    @Json(name = "type") val type: IdeType,
    @Json(name = "date") val date: String
)

/**
 * Type of IDE release: release or EAP (Early Access Program)
 */
enum class IdeType {
    RELEASE,
    EAP,
    UNSUPPORTED;

    val value: String
        get() = when (this) {
            RELEASE -> "release"
            EAP -> "eap"
            UNSUPPORTED -> "unsupported"
        }
}

class IdeTypeAdapter {
    @FromJson
    fun fromJson(type: String): IdeType {
        return when (type.lowercase()) {
            "release" -> IdeType.RELEASE
            "eap" -> IdeType.EAP
            else -> IdeType.UNSUPPORTED
        }
    }

    @ToJson
    fun toJson(type: IdeType): String = type.value
}

/**
 * Simplified representation of an IDE for use in the plugin.
 *
 * Contains the essential information: product code, build number, version, and type.
 */
@JsonClass(generateAdapter = true)
data class Ide(
    val code: String,
    val build: String,
    val version: String,
    val type: IdeType
) {
    companion object {
        /**
         * Create an Ide from an IdeProduct and IdeRelease.
         */
        fun from(product: IdeProduct, release: IdeRelease): Ide {
            return Ide(
                code = product.intellijProductCode ?: product.code,
                build = release.build,
                version = release.version,
                type = release.type
            )
        }
    }
}
