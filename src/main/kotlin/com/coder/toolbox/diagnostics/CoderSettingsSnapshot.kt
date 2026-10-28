package com.coder.toolbox.diagnostics

import com.coder.toolbox.settings.ReadOnlyCoderSettings
import com.coder.toolbox.store.APP_NAME_AS_TITLE
import com.coder.toolbox.store.BINARY_DESTINATION
import com.coder.toolbox.store.BINARY_SOURCE
import com.coder.toolbox.store.CONNECTION_LOG_BUFFER_SIZE
import com.coder.toolbox.store.DATA_DIRECTORY
import com.coder.toolbox.store.DEFAULT_URL
import com.coder.toolbox.store.DISABLE_AUTOSTART
import com.coder.toolbox.store.DISABLE_SIGNATURE_VALIDATION
import com.coder.toolbox.store.ENABLE_DOWNLOADS
import com.coder.toolbox.store.ENABLE_SSH_WILDCARD_CONFIG
import com.coder.toolbox.store.FALLBACK_ON_CODER_FOR_SIGNATURES
import com.coder.toolbox.store.HEADER_COMMAND
import com.coder.toolbox.store.HTTP_CLIENT_LOG_LEVEL
import com.coder.toolbox.store.LAST_USED_URL
import com.coder.toolbox.store.NETWORK_INFO_DIR
import com.coder.toolbox.store.PREFER_OAUTH2_IF_AVAILABLE
import com.coder.toolbox.store.SSH_CONFIG_OPTIONS
import com.coder.toolbox.store.SSH_CONFIG_PATH
import com.coder.toolbox.store.SSH_CONNECTION_TIMEOUT_IN_SECONDS
import com.coder.toolbox.store.SSH_LOG_DIR
import com.coder.toolbox.store.TLS_ALTERNATE_HOSTNAME
import com.coder.toolbox.store.TLS_CA_PATH
import com.coder.toolbox.store.TLS_CERT_PATH
import com.coder.toolbox.store.TLS_CERT_REFRESH_COMMAND
import com.coder.toolbox.store.TLS_KEY_PATH
import com.coder.toolbox.store.USE_KEYRING
import com.coder.toolbox.store.WORKSPACE_CREATE_URL
import com.coder.toolbox.store.WORKSPACE_VIEW_URL
import com.squareup.moshi.Moshi

/**
 * Effective plugin settings, including defaults and environment fallbacks exposed by the settings store.
 * Keep this allowlist explicit: new settings must be reviewed for secrets before including them.
 * Never serialize the backing store, credentials, or the contents of referenced files.
 */
internal fun collectSettingsSnapshot(settings: ReadOnlyCoderSettings): ByteArray {
    val tls = settings.tls
    val snapshot = sortedMapOf(
        LAST_USED_URL to settings.lastDeploymentURL,
        DEFAULT_URL to settings.defaultURL,
        APP_NAME_AS_TITLE to settings.useAppNameAsTitle,
        BINARY_SOURCE to settings.binarySource,
        BINARY_DESTINATION to settings.binaryDestination,
        DISABLE_SIGNATURE_VALIDATION to settings.disableSignatureVerification,
        FALLBACK_ON_CODER_FOR_SIGNATURES to settings.fallbackOnCoderForSignatures.toString(),
        HTTP_CLIENT_LOG_LEVEL to settings.httpClientLogLevel.toString(),
        CONNECTION_LOG_BUFFER_SIZE to settings.connectionLogBufferSize,
        DATA_DIRECTORY to settings.dataDirectory,
        ENABLE_DOWNLOADS to settings.enableDownloads,
        HEADER_COMMAND to maskSetting(settings.headerCommand),
        USE_KEYRING to settings.useKeyring,
        TLS_CERT_PATH to tls.certPath,
        TLS_KEY_PATH to tls.keyPath,
        TLS_CA_PATH to tls.caPath,
        TLS_ALTERNATE_HOSTNAME to tls.altHostname,
        TLS_CERT_REFRESH_COMMAND to maskSetting(tls.certRefreshCommand),
        DISABLE_AUTOSTART to settings.disableAutostart,
        SSH_CONNECTION_TIMEOUT_IN_SECONDS to settings.sshConnectionTimeoutInSeconds,
        ENABLE_SSH_WILDCARD_CONFIG to settings.isSshWildcardConfigEnabled,
        SSH_CONFIG_PATH to settings.sshConfigPath,
        SSH_LOG_DIR to settings.sshLogDirectory,
        SSH_CONFIG_OPTIONS to maskSetting(settings.sshConfigOptions),
        NETWORK_INFO_DIR to settings.networkInfoDir,
        PREFER_OAUTH2_IF_AVAILABLE to settings.preferOAuth2IfAvailable,
        WORKSPACE_VIEW_URL to settings.workspaceViewUrl,
        WORKSPACE_CREATE_URL to settings.workspaceCreateUrl,
    )
    val json = Moshi.Builder().build().adapter(Map::class.java).serializeNulls().indent("  ").toJson(snapshot)
    return "$json\n".toByteArray(Charsets.UTF_8)
}

private fun maskSetting(value: String?): String = if (value.isNullOrEmpty()) "<empty>" else "<set>"
