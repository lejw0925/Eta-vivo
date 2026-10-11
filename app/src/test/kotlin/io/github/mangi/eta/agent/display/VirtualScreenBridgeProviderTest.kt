package io.github.mangi.eta.agent.display

import android.os.Binder
import android.os.Bundle
import org.junit.Assert.assertThrows
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VirtualScreenBridgeProviderTest {
    @After fun resetUid() { ShadowBinder.reset() }
    @Test fun appUidCannotRegisterARootDisplayEvenWithAClaimedNonce() {
        ShadowBinder.setCallingUid(10001)
        assertThrows(SecurityException::class.java) { VirtualScreenBridgeProvider().call("register", null, Bundle()) }
    }
    @Test fun rootCannotReplaceAnotherSessionWithAnUnknownNonce() {
        ShadowBinder.setCallingUid(0)
        val args = Bundle().apply { putString("sessionId", "missing"); putString("token", "not-the-session-nonce"); putBinder("display", Binder()) }
        assertThrows(IllegalArgumentException::class.java) { VirtualScreenBridgeProvider().call("register", null, args) }
    }
}
