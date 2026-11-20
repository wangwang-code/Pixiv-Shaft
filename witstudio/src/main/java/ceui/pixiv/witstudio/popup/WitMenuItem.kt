package ceui.pixiv.witstudio.popup

import android.view.View

/** Text-only menu item used by morphing toolbar overflow menus. */
public data class WitMenuItem(
    public val id: Int = View.NO_ID,
    public val title: CharSequence,
    public val enabled: Boolean = true,
    public val onClick: (() -> Unit)? = null,
)
