package at.themrcodes.ridershub.api

import android.app.Application
import android.net.Uri
import at.themrcodes.ridershub.BuildConfig
import net.openid.appauth.AuthorizationServiceConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class OidcAuthorizationTest {
    @Test fun nativeAuthorizationUsesFixedRedirectAndPkce() {
        val request = oidcAuthorizationRequest(
            AuthorizationServiceConfiguration(
                Uri.parse("https://identity.example.test/authorize"),
                Uri.parse("https://identity.example.test/token"),
            ),
            OidcAuthConfig(
                "https://identity.example.test/realms/ridershub",
                "ridershub-android",
                OIDC_AUDIENCE,
                listOf("openid", OIDC_REGISTRATION_SCOPE),
            ),
        )
        assertEquals("at.themrcodes.ridershub:/oauth2redirect", BuildConfig.OIDC_REDIRECT_URI)
        assertEquals(BuildConfig.OIDC_REDIRECT_URI, request.redirectUri.toString())
        assertEquals("S256", request.codeVerifierChallengeMethod)
        assertTrue(request.codeVerifier?.isNotBlank() == true)
        assertTrue(request.codeVerifierChallenge?.isNotBlank() == true)
        assertTrue(request.state?.isNotBlank() == true)
        assertEquals("openid ridershub.device.register", request.scope)
    }
}
