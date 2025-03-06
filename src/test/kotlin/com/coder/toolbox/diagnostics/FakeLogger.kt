package com.coder.toolbox.diagnostics

import com.jetbrains.toolbox.api.core.diagnostics.Logger

class FakeLogger : Logger {
    override fun error(exception: Throwable, message: () -> String) {

    }

    override fun error(exception: Throwable, message: String) {
    }

    override fun error(message: () -> String) {

    }

    override fun error(message: String) {

    }

    override fun warn(exception: Throwable, message: () -> String) {

    }

    override fun warn(exception: Throwable, message: String) {

    }

    override fun warn(message: () -> String) {

    }

    override fun warn(message: String) {

    }

    override fun debug(exception: Throwable, message: () -> String) {
    }

    override fun debug(exception: Throwable, message: String) {
    }

    override fun debug(message: () -> String) {
    }

    override fun debug(message: String) {
    }

    override fun info(exception: Throwable, message: () -> String) {
    }

    override fun info(exception: Throwable, message: String) {
    }

    override fun info(message: () -> String) {
    }

    override fun info(message: String) {
    }

    override fun trace(exception: Throwable, message: () -> String) {
    }

    override fun trace(exception: Throwable, message: String) {
    }

    override fun trace(message: () -> String) {
    }

    override fun trace(message: String) {
    }

}