package com.coder.toolbox.oauth

import com.jetbrains.toolbox.api.core.auth.Account

data class CoderAccount(override val id: String, override val fullName: String) : Account