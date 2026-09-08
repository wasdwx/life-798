package com.water.widget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenImportCrossPlatformProbeTest {
    @Test
    fun correctsReversedTokensEvenWhenAllReadOnlyRequestsSucceed() {
        val result = resolvePair(reversed = true)

        assertTrue(result.success)
        assertEquals("score-token", result.mainToken)
        assertEquals("device-token", result.appToken)
        assertTrue(result.notices.isNotEmpty())
    }

    @Test
    fun keepsCorrectTokensWhenAllReadOnlyRequestsSucceed() {
        val result = resolvePair(reversed = false)

        assertTrue(result.success)
        assertEquals("score-token", result.mainToken)
        assertEquals("device-token", result.appToken)
        assertTrue(result.notices.isEmpty())
    }

    private fun resolvePair(reversed: Boolean): TokenImportResolution {
        val score = TokenImportResolver.inspect("score-token", successfulProbe(5), null)
        val device = TokenImportResolver.inspect("device-token", successfulProbe(1), null)
        return TokenImportResolver.resolve(
            candidates = listOf(
                TokenImportCandidate(
                    if (reversed) device else score,
                    setOf(ImportedTokenPlatform.MAIN)
                ),
                TokenImportCandidate(
                    if (reversed) score else device,
                    setOf(ImportedTokenPlatform.APP)
                )
            ),
            expectedPhone = "13800000000"
        )
    }

    private fun successfulProbe(platformType: Int) = JSONObject(
        """
        {
          "viewMain":{"code":0,"data":{"id":"u1"}},
          "viewApp":{"code":0,"data":{"id":"u1"}},
          "missionMain":{"code":0,"data":{"missions":[
            {"stype":[1,5]},
            {"stype":[$platformType]}
          ]}},
          "masterApp":{"code":0,"data":{}}
        }
        """.trimIndent()
    )
}
