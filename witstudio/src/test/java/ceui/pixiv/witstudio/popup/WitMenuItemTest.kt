package ceui.pixiv.witstudio.popup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WitMenuItemTest {

    @Test
    fun `default values are expected`() {
        val item = WitMenuItem(id = 101, title = "测试菜单项")
        assertEquals(101, item.id)
        assertEquals("测试菜单项", item.title)
        assertTrue(item.enabled)
        assertEquals(null, item.onClick)
    }

    @Test
    fun `click callback executes when invoked`() {
        var clicked = false
        val item = WitMenuItem(
            id = 202,
            title = "点击项",
            onClick = { clicked = true }
        )
        item.onClick?.invoke()
        assertTrue(clicked)
    }

    @Test
    fun `disabled state is preserved`() {
        val item = WitMenuItem(id = 303, title = "禁用项", enabled = false)
        assertFalse(item.enabled)
    }

    @Test
    fun `riseAndFall liquid physics calculations`() {
        // At t=0, dip is 0
        assertEquals(0f, WitMorphPopup.riseAndFall(0f), 0.0001f)

        // At peak ~0.098s, value is close to 1.0
        val peakVal = WitMorphPopup.riseAndFall(0.098f, 0.098f, 3.3f)
        assertEquals(1.0f, peakVal, 0.01f)

        // Beyond 0.35s, value decays near 0
        val decayed = WitMorphPopup.riseAndFall(0.35f, 0.098f, 3.3f)
        assertTrue(decayed < 0.1f)
    }

    @Test
    fun `prism physics springs boundary and settle values`() {
        // openSpring: 0 at t=0, reaches ~1.0 at 0.45s with slight overshoot around ~0.24s
        assertEquals(0f, WitMorphPopup.openSpring(0f), 0.0001f)
        val midOvershoot = WitMorphPopup.openSpring(0.24f)
        assertTrue("Expected slight spring bounce > 1.0, got $midOvershoot", midOvershoot > 1.0f)
        val settled = WitMorphPopup.openSpring(0.45f)
        assertEquals(1.0f, settled, 0.02f)

        // cornersSpring: 1 at t<=0, decays towards 0
        assertEquals(1.0f, WitMorphPopup.cornersSpring(0f), 0.0001f)
        val cornersDecayed = WitMorphPopup.cornersSpring(0.3f)
        assertEquals(0.0f, cornersDecayed, 0.05f)

        // dropletSpring: 0 at t=0, reaches ~1.0 at 0.5s
        assertEquals(0f, WitMorphPopup.dropletSpring(0f), 0.0001f)
        val dropletSettled = WitMorphPopup.dropletSpring(0.5f)
        assertEquals(1.0f, dropletSettled, 0.02f)
    }
}
