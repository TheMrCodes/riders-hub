package at.themrcodes.ridershub.api

import android.net.Uri
import at.themrcodes.ridershub.BuildConfig
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

internal fun oidcAuthorizationRequest(
    serviceConfiguration: AuthorizationServiceConfiguration,
    config: OidcAuthConfig,
): AuthorizationRequest = AuthorizationRequest.Builder(
    serviceConfiguration,
    config.clientId,
    ResponseTypeValues.CODE,
    Uri.parse(BuildConfig.OIDC_REDIRECT_URI),
).setScopes(config.scopes).build()
