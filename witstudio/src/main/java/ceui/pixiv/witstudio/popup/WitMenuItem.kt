package ceui.pixiv.witstudio.popup

import android.graphics.drawable.Drawable
import android.view.View
import androidx.annotation.DrawableRes

/**
 * [WitMenuPopup] 的菜单项数据模型。
 *
 * 承载标题、图标（可选）与点击回调，支持日夜双模与主题色自适应。
 */
public data class WitMenuItem(
    public val id: Int = View.NO_ID,
    public val title: CharSequence,
    @get:DrawableRes public val iconRes: Int = 0,
    public val iconDrawable: Drawable? = null,
    public val enabled: Boolean = true,
    public val onClick: (() -> Unit)? = null,
)
