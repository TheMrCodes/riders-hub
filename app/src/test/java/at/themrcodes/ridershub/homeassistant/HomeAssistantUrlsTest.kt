package at.themrcodes.ridershub.homeassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeAssistantUrlsTest {
    @Test
    fun registrationUrl_acceptsTrustedLocalHttp() {
        assertEquals(
            "http://homeassistant.local:8123/api/mobile_app/registrations",
            HomeAssistantUrls.registrationUrl(" http://homeassistant.local:8123/ "),
        )
    }

    @Test
    fun registrationUrl_rejectsPublicHttp() {
        assertThrows(IllegalArgumentException::class.java) {
            HomeAssistantUrls.registrationUrl("http://ha.example.com")
        }
    }

    @Test
    fun webhookUrl_prefersCloudhook() {
        assertEquals(
            "https://hooks.nabu.casa/synthetic-hook",
            HomeAssistantUrls.webhookUrl(
                instanceUrl = "http://homeassistant.local:8123",
                webhookId = "synthetic-id",
                cloudhookUrl = "https://hooks.nabu.casa/synthetic-hook",
                remoteUiUrl = "https://home.example.com",
            ),
        )
    }

    @Test
    fun webhookUrl_usesRemoteUiWhenCloudhookIsMissing() {
        assertEquals(
            "https://home.example.com/api/webhook/synthetic-id",
            HomeAssistantUrls.webhookUrl(
                instanceUrl = "http://homeassistant.local:8123",
                webhookId = "synthetic-id",
                cloudhookUrl = null,
                remoteUiUrl = "https://home.example.com/",
            ),
        )
    }

    @Test
    fun webhookUrl_rejectsQueriesThatCouldHideRouting() {
        assertThrows(IllegalArgumentException::class.java) {
            HomeAssistantUrls.validateWebhookUrl("https://ha.example.com/api/webhook/id?next=elsewhere")
        }
    }
}
