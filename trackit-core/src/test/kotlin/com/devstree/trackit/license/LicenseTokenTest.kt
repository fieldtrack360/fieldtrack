package com.devstree.trackit.license

import com.devstree.trackit.TrackItConfig
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.util.Base64

internal class LicenseTokenTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun configSerializationDropsLicenseToken() {
        val config = TrackItConfig(license = "TRACKIT-test-token")

        val encoded = json.encodeToString(TrackItConfig.serializer(), config)
        assertThat(encoded).doesNotContain("TRACKIT-test-token")

        val decoded = json.decodeFromString(TrackItConfig.serializer(), encoded)
        assertThat(decoded.license).isNull()
    }

    @Test
    fun parseAcceptsTheExpectedWireFormat() {
        val payloadJson = """
            {"primary":"com.example.app","also":["com.example.alt"],"kid":7,"v":1,"licensee":"Acme","issued":123}
        """.trimIndent()
        val token = buildToken(payloadJson, "sig")

        when (val result = LicenseToken.parse(token)) {
            is LicenseToken.ParseResult.Success -> {
                assertThat(result.parts.payload).isNotEmpty()
                assertThat(String(result.parts.signature, Charsets.UTF_8)).isEqualTo("sig")
            }
            is LicenseToken.ParseResult.Failure -> {
                throw AssertionError("Expected success, got ${result.reason}")
            }
        }
    }

    @Test
    fun parseRejectsUnsupportedVersions() {
        val token = buildToken(
            payloadJson = """{"primary":"com.example.app","kid":1,"v":2}""",
            signature = "sig",
        )

        when (val result = LicenseToken.parse(token)) {
            is LicenseToken.ParseResult.Success -> {
                throw AssertionError("Expected failure, got ${result.parts}")
            }
            is LicenseToken.ParseResult.Failure -> {
                assertThat(result.reason).isInstanceOf(LicenseToken.ParseFailure.UnsupportedVersion::class.java)
            }
        }
    }

    private fun buildToken(payloadJson: String, signature: String): String {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payloadJson.toByteArray(Charsets.UTF_8))
        val sig = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(signature.toByteArray(Charsets.UTF_8))
        return "${LicenseToken.prefix}$payload.$sig"
    }
}
