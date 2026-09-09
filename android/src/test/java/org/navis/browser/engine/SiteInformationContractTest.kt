// SPDX-License-Identifier: MPL-2.0

package org.navis.browser.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SiteInformationContractTest {
    private fun certificate(): JSONObject = JSONObject()
        .put("commonName", "www.example.test")
        .put("subjectName", "CN=www.example.test,O=Example Company")
        .put("organization", "Example Company")
        .put("organizationalUnit", "Web Services")
        .put("displayName", "Example Website")
        .put("issuerName", "CN=Example Intermediate,O=Example Authority,C=GB")
        .put("issuerCommonName", "Example Intermediate")
        .put("issuerOrganization", "Example Authority")
        .put("serialNumber", "01:02:03")
        .put("sha256Fingerprint", "AA:BB:CC")
        .put("sha256SubjectPublicKeyInfoDigest", "base64-public-key-digest")
        .put("validFrom", 1_700_000_000_000L)
        .put("validTo", 1_900_000_000_000L)

    @Test fun completeCertificateRetainsDesktopFieldsAndFullIssuer() {
        val cert = SiteCertificate.fromJson(certificate())
        assertEquals("www.example.test", cert.commonName)
        assertEquals("CN=www.example.test,O=Example Company", cert.subject)
        assertEquals("Example Company", cert.organization)
        assertEquals("Web Services", cert.organizationalUnit)
        assertEquals("Example Website", cert.displayName)
        assertEquals("CN=Example Intermediate,O=Example Authority,C=GB", cert.issuer)
        assertEquals("Example Intermediate", cert.issuerCommonName)
        assertEquals("Example Authority", cert.issuerOrganization)
        assertEquals("01:02:03", cert.serial)
        assertEquals("AA:BB:CC", cert.fingerprint)
        assertEquals("base64-public-key-digest", cert.publicKeyDigest)
        assertEquals(1_700_000_000_000L, cert.validFrom)
        assertEquals(1_900_000_000_000L, cert.validTo)
    }

    @Test fun issuerFallbackOnlyAppliesWithoutAFullDistinguishedName() {
        val input = certificate().put("issuerName", "  ")
        assertEquals("Example Authority", SiteCertificate.fromJson(input).issuer)
        input.remove("issuerOrganization")
        assertEquals("Example Intermediate", SiteCertificate.fromJson(input).issuer)
        input.remove("issuerCommonName")
        assertEquals("", SiteCertificate.fromJson(input).issuer)
    }

    @Test fun siteResponseRetainsConnectionChainAndErrorWithoutInventingIdentity() {
        val info = SiteInformation.fromJson(JSONObject()
            .put("kind", "secure").put("origin", "https://www.example.test")
            .put("host", "www.example.test").put("navigationToken", "current-document")
            .put("canClearData", true).put("protocol", "TLS 1.3").put("cipher", "AES_256_GCM")
            .put("keyExchange", "X25519").put("signature", "RSA-PSS-SHA256")
            .put("error", "SEC_ERROR_EXAMPLE").put("certificate", certificate())
            .put("certificateChain", JSONArray().put(certificate())
                .put(certificate().put("displayName", "Example Intermediate"))))
        assertEquals("secure", info.kind)
        assertEquals("https://www.example.test", info.origin)
        assertEquals("www.example.test", info.host)
        assertEquals("current-document", info.navigationToken)
        assertTrue(info.canClearData)
        assertEquals("TLS 1.3", info.protocol)
        assertEquals("AES_256_GCM", info.cipher)
        assertEquals("X25519", info.keyExchange)
        assertEquals("RSA-PSS-SHA256", info.signature)
        assertEquals("SEC_ERROR_EXAMPLE", info.error)
        assertEquals(2, info.certificateChain.size)
        assertEquals(info.certificate, info.certificateChain[0])
        assertEquals("Example Intermediate", info.certificateChain[1].displayName)
        assertFalse(info.builtInExtension)
    }

    @Test fun missingAndNullValuesDoNotCreateCertificatesOrTrust() {
        for (input in listOf(JSONObject(), JSONObject("""{
            "certificate":null,"certificateChain":null,"builtInExtension":null,
            "keyExchange":null,"signature":null,"error":null
        }"""))) {
            val info = SiteInformation.fromJson(input)
            assertEquals("unknown", info.kind)
            assertNull(info.certificate)
            assertTrue(info.certificateChain.isEmpty())
            assertFalse(info.canClearData)
            assertFalse(info.builtInExtension)
            assertEquals("", info.keyExchange)
            assertEquals("", info.signature)
            assertEquals("", info.error)
        }
        val cert = SiteCertificate.fromJson(JSONObject())
        assertEquals("", cert.issuer)
        assertEquals("", cert.publicKeyDigest)
        assertNull(cert.validFrom)
        assertNull(cert.validTo)
    }

    @Test fun malformedChainEntriesAreIgnoredAndTraversalIsBounded() {
        val chain = JSONArray().put(JSONObject.NULL).put("not a certificate")
            .put(4).put(true).put(JSONArray())
        for (index in 5..20) chain.put(certificate().put("displayName", "Certificate $index"))
        val info = SiteInformation.fromJson(JSONObject().put("certificate", "wrong type")
            .put("certificateChain", chain))
        assertNull(info.certificate)
        assertEquals((5..15).map { "Certificate $it" }, info.certificateChain.map { it.displayName })
        for (input in listOf("invalid", 8, true, JSONObject())) {
            assertTrue(SiteInformation.fromJson(JSONObject().put("certificateChain", input)).certificateChain.isEmpty())
        }
    }

    @Test fun builtInExtensionRequiresAnActualBoolean() {
        for (input in listOf(false, "true", "false", 1, JSONObject.NULL, JSONObject())) {
            assertFalse(SiteInformation.fromJson(JSONObject().put("kind", "extension")
                .put("builtInExtension", input)).builtInExtension)
        }
        assertTrue(SiteInformation.fromJson(JSONObject().put("kind", "extension")
            .put("builtInExtension", true)).builtInExtension)
    }

    @Test fun legacyConstructorsPreserveOrderAndSafeDefaults() {
        val certificate = SiteCertificate("common", "subject", "organization", "issuer", "serial", "fingerprint", 1L, 2L)
        val info = SiteInformation("secure", "origin", "host", "token", true, "protocol", "cipher", certificate)
        assertEquals("issuer", certificate.issuer)
        assertEquals("", certificate.displayName)
        assertEquals("", certificate.publicKeyDigest)
        assertEquals(certificate, info.certificate)
        assertTrue(info.certificateChain.isEmpty())
        assertEquals("", info.keyExchange)
        assertEquals("", info.signature)
        assertEquals("", info.error)
        assertFalse(info.builtInExtension)
    }

    @Test fun invalidValidityValuesRemainUnavailable() {
        val cert = SiteCertificate.fromJson(certificate().put("validFrom", -1).put("validTo", "not a date"))
        assertNull(cert.validFrom)
        assertNull(cert.validTo)
    }
}
