package com.coder.toolbox.util

import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS as JUnitOS

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@DisabledOnOs(JUnitOS.WINDOWS)
annotation class IgnoreOnWindows
