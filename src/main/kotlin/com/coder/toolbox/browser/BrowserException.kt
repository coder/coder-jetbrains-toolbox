package com.coder.toolbox.browser

import java.io.IOException

class BrowserException(msg: String, error: Throwable? = null) : IOException(msg, error)