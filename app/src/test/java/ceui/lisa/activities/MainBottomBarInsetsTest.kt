package ceui.lisa.activities

import android.graphics.Rect
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import ceui.lisa.R
import ceui.lisa.databinding.ActivityCoverBinding
import com.blankj.utilcode.util.Utils
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29, 30])
class MainBottomBarInsetsTest {
    @Test
    @Suppress("DEPRECATION")
    fun `content bottom inset does not inflate the bottom bar on repeated layouts`() {
        Utils.init(RuntimeEnvironment.getApplication())
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        val binding = ActivityCoverBinding.inflate(LayoutInflater.from(context))
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        if (Build.VERSION.SDK_INT >= 30) {
            // Robolectric initializes this global flag with a framework context. Match the
            // platform's targetSdk check so API 30 exercises the app's native inset dispatch.
            ReflectionHelpers.setStaticField(View::class.java, "sBrokenInsetsDispatch",
                context.applicationInfo.targetSdkVersion < 30)
        }
        // Exercise the real home layout/setup without starting login, network or native storage.
        ReflectionHelpers.setField(activity, "baseBind", binding)
        ReflectionHelpers.callInstanceMethod<Void>(activity, "setUpAutoHidingBottomBar")
        binding.navigationView.menu.add("Home")

        var contentBottom = 0
        var drawerBottom = 0
        ViewCompat.setOnApplyWindowInsetsListener(binding.viewPager) { _, insets ->
            contentBottom = insets.systemWindowInsetBottom
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.navView) { _, insets ->
            drawerBottom = insets.systemWindowInsetBottom
            insets
        }
        val systemBottom = 48
        // On API 28 a Builder seeded from CONSUMED retains the consumed flags. Model a
        // real system dispatch with the legacy non-consumed WindowInsets(Rect) constructor.
        val seedInsets = ReflectionHelpers.callConstructor(WindowInsets::class.java,
            ReflectionHelpers.ClassParameter.from(Rect::class.java, Rect(0, 24, 0, systemBottom)))
        val systemInsets = WindowInsetsCompat.Builder(WindowInsetsCompat.toWindowInsetsCompat(seedInsets))
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, systemBottom))
            .setVisible(WindowInsetsCompat.Type.systemBars(), true)
            .build()
        val initialPadding = binding.navigationView.paddingBottom

        fun layout() {
            binding.root.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            )
            binding.root.layout(0, 0, 1080, 1920)
        }

        layout()
        ViewCompat.dispatchApplyWindowInsets(binding.root, systemInsets)
        layout()
        val expectedHeight = binding.navigationView.height
        repeat(5) {
            ViewCompat.dispatchApplyWindowInsets(binding.root, systemInsets)
            layout()
            assertEquals("Only the system navigation bar belongs in bottom bar padding",
                initialPadding + systemBottom, binding.navigationView.paddingBottom)
            assertEquals("Bottom bar height must stabilize", expectedHeight, binding.navigationView.height)
            assertEquals("Content reserves space for the whole bottom bar", expectedHeight, contentBottom)
            assertEquals("Drawer receives original system insets", systemBottom, drawerBottom)
        }
    }
}
