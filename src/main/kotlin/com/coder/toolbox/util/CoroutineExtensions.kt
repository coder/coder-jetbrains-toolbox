package com.coder.toolbox.util

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job

fun Job?.name(): String = this?.get(CoroutineName)?.toString() ?: this.toString()