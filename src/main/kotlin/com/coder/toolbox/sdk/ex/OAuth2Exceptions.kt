package com.coder.toolbox.sdk.ex

sealed class OAuth2ErrorException(message: String?) : Exception(message)

class ClientRegistrationException(message: String) : OAuth2ErrorException(message)
class OAuthTokenResponseException(message: String?) : OAuth2ErrorException(message)
