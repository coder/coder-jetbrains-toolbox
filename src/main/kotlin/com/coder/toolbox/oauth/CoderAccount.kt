package com.coder.toolbox.oauth

import com.jetbrains.toolbox.api.core.auth.Account
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CoderAccount(
    override val id: String,
    override val fullName: String,
    val baseUrl: String,
    val refreshUrl: String,
    val clientId: String,
    val clientSecret: String? = null
) : Account