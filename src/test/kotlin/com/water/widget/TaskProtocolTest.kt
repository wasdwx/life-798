package com.water.widget

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class TaskProtocolTest {
    @Test fun requestIdentityAndBodiesFollowTheTaskPlatform() {
        val app = Account("test").apply { appToken = "app-token" }
        assertEquals(TaskPlatform.APP, TaskProtocol.preferredPlatform(app))
        assertEquals("app-token", TaskProtocol.token(app, TaskPlatform.APP))
        assertEquals("1,1", TaskProtocol.appType(TaskPlatform.APP))
        assertEquals("3.1.9", TaskProtocol.version(TaskPlatform.APP))
        app.token = "main-token"
        assertEquals(TaskPlatform.MAIN, TaskProtocol.preferredPlatform(app))
        assertEquals("1,5", TaskProtocol.appType(TaskPlatform.MAIN))
        assertEquals("2.0.178", TaskProtocol.version(TaskPlatform.MAIN))

        val appBody = TaskProtocol.missionBody("mission", 7, TaskPlatform.APP)
        assertEquals(101, appBody.getInt("type"))
        assertEquals(7, appBody.getInt("addScore"))
        assertEquals(4, appBody.getInt("addScoreType"))
        assertEquals(2, TaskProtocol.missionBody("mission", 7, TaskPlatform.MAIN).length())

        val signIn = TaskProtocol.signInBody("daily", 3, 5, TaskPlatform.APP)
        assertEquals(3, signIn.getInt("weekday"))
        assertFalse(signIn.has("weekDay"))
        assertEquals(5, signIn.getInt("addScore"))
        assertEquals(1, signIn.getInt("addScoreType"))
        val mini = TaskProtocol.signInBody("daily", 3, 5, TaskPlatform.MAIN)
        assertTrue(mini.has("weekDay"))
        assertFalse(mini.has("addScore"))
    }

    @Test fun signatureUsesThirtySecondBucketsAndEightCharacterSuffixes() {
        val expected = MessageDigest.getInstance("MD5")
            .digest("mission6012345678abcdefghsalt".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, Signer.signAt("mission", "prefix12345678", "prefixabcdefgh", "salt", 89_999))
        assertEquals(expected, Signer.signAt("mission", "12345678", "abcdefgh", "salt", 60_000))
        assertNotEquals(expected, Signer.signAt("mission", "12345678", "abcdefgh", "salt", 90_000))
        assertEquals("", Signer.signAt("mission", "token", "uid", "", 90_000))
    }

    @Test fun staleCredentialsCannotInvalidateANewLoginOrOverwriteItsScore() {
        val latest = Account("test").apply { token = "new-main"; appToken = "new-app"; score = 10 }
        assertFalse(latest.invalidateToken("old-app", true))
        assertFalse(latest.updateScoreForToken("old-app", 99))
        assertEquals("new-main", latest.token)
        assertEquals("new-app", latest.appToken)
        assertEquals(10, latest.score)
        assertTrue(latest.updateScoreForToken("new-app", 0))
        assertEquals(0, latest.score)
        assertTrue(latest.invalidateToken("new-app", true))
        assertFalse(latest.hasAppToken())
        assertTrue(latest.hasToken())
    }

    @Test fun failureMessagesDistinguishExpiryAndRedactTokens() {
        assertTrue(TaskProtocol.failure(JSONObject().put("code", -99), null, "token").contains("重新"))
        val message = TaskProtocol.failure(JSONObject().put("code", -1).put("msg", "reject sensitive-token"), null, "sensitive-token")
        assertTrue(message.contains("code=-1"))
        assertFalse(message.contains("sensitive-token"))
        assertEquals("HTTP 503", TaskProtocol.failure(null, "HTTP 503", "token"))
    }
}
