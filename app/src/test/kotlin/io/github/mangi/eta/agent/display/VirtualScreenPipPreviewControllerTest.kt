package io.github.mangi.eta.agent.display

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Rational
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VirtualScreenPipPreviewControllerTest {
    @Test
    fun previewRequiresAwakeAndUnlockedScreen() {
        val context = RuntimeEnvironment.getApplication() as Context
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
        power.setIsInteractive(true)
        keyguard.setKeyguardLocked(false)
        assertTrue(VirtualScreenPipPreviewController.screenAvailable(context))
        keyguard.setKeyguardLocked(true)
        assertFalse(VirtualScreenPipPreviewController.screenAvailable(context))
        keyguard.setKeyguardLocked(false)
        power.setIsInteractive(false)
        assertFalse(VirtualScreenPipPreviewController.screenAvailable(context))
    }

    @Test
    fun permissionRequiresBothFeatureAndAllowedAppOp() {
        val context = RuntimeEnvironment.getApplication() as Context
        val packages = shadowOf(context.packageManager)
        val ops = shadowOf(context.getSystemService(AppOpsManager::class.java))
        packages.setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        ops.setMode(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, context.applicationInfo.uid, context.packageName, AppOpsManager.MODE_ALLOWED)
        assertTrue(VirtualScreenPipPreviewController.allowed(context))
        ops.setMode(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, context.applicationInfo.uid, context.packageName, AppOpsManager.MODE_IGNORED)
        assertFalse(VirtualScreenPipPreviewController.allowed(context))
        ops.setMode(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, context.applicationInfo.uid, context.packageName, AppOpsManager.MODE_ALLOWED)
        packages.setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, false)
        assertFalse(VirtualScreenPipPreviewController.allowed(context))
    }

    @Test
    fun landscapeFrameRotatesClockwiseForPortraitPreview() {
        assertEquals(Rational(1080, 2400), VirtualScreenPipPreviewController.aspectRatio(1080, 2400, 2400, 1080))
        assertEquals(Rational(2400, 1080), VirtualScreenPipPreviewController.aspectRatio(2400, 1080, 2400, 1080))
    }

    @Test
    fun tallAndWideDisplaysClampToLegalSystemAspectRatios() {
        assertEquals(Rational(100, 239), VirtualScreenPipPreviewController.aspectRatio(1080, 2400, 100, 1000))
        assertEquals(Rational(239, 100), VirtualScreenPipPreviewController.aspectRatio(2400, 1080, 1000, 100))
    }
}
