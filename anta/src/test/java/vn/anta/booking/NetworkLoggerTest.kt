package vn.anta.booking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkLoggerTest {
    @Test
    fun `passwords, codes and every kind of token are masked in a logged body, and nothing else is`() {
        val body = """{"identifier":"an@example.com","password":"p\"q","idToken":"eyJ.a.b","refreshToken":"r1","accessToken":"a1",""" +
            """"verificationToken":"v1","fcmToken":"f1","code":"123456","deviceId":"d1","expiresIn":900}"""

        assertEquals(
            """{"identifier":"an@example.com","password":"***","idToken":"***","refreshToken":"***","accessToken":"***",""" +
                """"verificationToken":"***","fcmToken":"***","code":"***","deviceId":"d1","expiresIn":900}""",
            NetworkLogger.masked(body)
        )
        assertNull(NetworkLogger.masked(null))
    }
}
