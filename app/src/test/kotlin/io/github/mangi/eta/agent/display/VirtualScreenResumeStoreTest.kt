package io.github.mangi.eta.agent.display

import android.app.Application
import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VirtualScreenResumeStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val store get() = VirtualScreenResumeStore(context)

    @Before fun reset() { store.clear() }
    @After fun cleanUp() { store.clear() }

    @Test fun freshInstancesRecoverOnlyTheirConversationTarget() {
        assertTrue(store.remember("first", "com.example.first/.Main"))
        assertTrue(store.remember("second", "com.example.second/com.example.second.Main"))
        assertEquals("com.example.first/.Main", VirtualScreenResumeStore(context).component("first"))
        assertEquals("com.example.second/com.example.second.Main", store.component("second"))
        assertNull(store.component("other"))
        store.forget("first")
        assertNull(store.component("first"))
        assertNotNull(store.component("second"))
    }

    @Test fun invalidComponentsCannotReplaceASuccessfulTarget() {
        store.remember("owner", "com.example/.Main")
        for (invalid in listOf("", "com.example", "com.example/.Main; reboot", "com.example/.Main?submit=1")) {
            assertFalse(store.remember("owner", invalid))
        }
        assertFalse(store.remember("", "com.example/.Main"))
        assertEquals("com.example/.Main", store.component("owner"))
    }

    @Test fun boundedEntriesEvictOldestWithoutLeakingTargetsAcrossOwners() {
        repeat(32) { store.remember("owner-$it", "com.example/.Main") }
        store.remember("owner-0", "com.other/.Entry")
        store.remember("new-owner", "com.newapp/.Main")
        assertNull(store.component("owner-1"))
        assertEquals("com.other/.Entry", store.component("owner-0"))
        assertEquals("com.newapp/.Main", store.component("new-owner"))
    }

    @Test fun revocationAndMalformedStorageRequireANewExplicitLaunch() {
        store.remember("owner", "com.example/.Main")
        store.clear()
        assertNull(store.component("owner"))
        context.getSharedPreferences("virtual_screen_resume", Context.MODE_PRIVATE).edit()
            .putString("apps", "invalid-json").commit()
        assertNull(store.component("owner"))
        assertTrue(store.remember("next", "com.example/.Next"))
        assertEquals("com.example/.Next", store.component("next"))
    }

    @Test fun closingAnAlreadyLostDisplayForgetsOnlyItsOwnerAndRevocationClearsAll() {
        store.remember("owner", "com.example/.Main")
        store.remember("other", "com.other/.Main")
        val closed = VirtualScreenSession.execute(context, "owner", org.json.JSONObject().put("action", "close"))
        assertTrue(org.json.JSONObject(closed.content).getBoolean("ok"))
        assertNull(store.component("owner"))
        assertNotNull(store.component("other"))
        VirtualScreenSession.revokePermission(context)
        assertNull(store.component("other"))
    }
}
