package ceui.pixiv.witstudio.popup

import android.view.View
import androidx.appcompat.widget.ActionMenuView
import androidx.appcompat.widget.Toolbar

/**
 * Toolbar 溢出（更多）菜单接管器。
 *
 * 将 AppCompat Toolbar 原生生硬的 overflow 菜单，无缝替换为带有
 * 上浮覆盖与物理弹簧形态变换（Morphing Surface）的 [WitMorphPopup]。
 */
public object WitToolbarOverflow {

    /**
     * 接管 [toolbar] 的更多按钮点击。
     *
     * @param toolbar 目标工具栏
     * @param customItems 自定义菜单项列表；若为空则自动从 [toolbar] 的当前 menu 中提取 overflow 项
     * @param onItemSelected 菜单项点击回调；若为空则默认分发 [Toolbar.getMenu().performIdentifierAction]
     */
    @JvmStatic
    public fun attach(
        toolbar: Toolbar,
        customItems: List<WitMenuItem>? = null,
        onItemSelected: ((WitMenuItem) -> Unit)? = null,
    ): Unit {
        val binder = object : Runnable {
            override fun run() {
                val elements = findOverflowElements(toolbar)
                if (elements == null) {
                    toolbar.post(this)
                    return
                }
                val actionMenuView = elements.first
                val overflowButton = elements.second

                // 核心关键：AppCompat 的 OverflowMenuButton 默认设置了 ForwardingListener（TouchListener），
                // 它会在 TouchDown 时抢占消费事件并直接唤起原生的系统菜单，使 OnClickListener 失效。
                // 清空该 TouchListener，恢复标准 Android 点击分发。
                overflowButton.setOnTouchListener(null)
                overflowButton.setOnClickListener {
                    val items =
                        customItems ?: extractOverflowItems(toolbar, actionMenuView, overflowButton)
                    if (items.isEmpty()) return@setOnClickListener

                    WitMorphPopup.show(toolbar.context, overflowButton, items) { selected ->
                        if (onItemSelected != null) {
                            onItemSelected(selected)
                        } else if (selected.id != View.NO_ID) {
                            toolbar.menu.performIdentifierAction(selected.id, 0)
                        }
                    }
                }
            }
        }

        toolbar.post(binder)

        toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            toolbar.post(binder)
        }
    }

    /** 查找 Toolbar 内部的 [ActionMenuView] 与更多按钮 [View]。 */
    public fun findOverflowElements(toolbar: Toolbar): Pair<ActionMenuView, View>? {
        for (i in 0 until toolbar.childCount) {
            val child = toolbar.getChildAt(i)
            if (child is ActionMenuView) {
                for (j in 0 until child.childCount) {
                    val menuChild = child.getChildAt(j)
                    if (isOverflowButton(menuChild, toolbar)) {
                        return Pair(child, menuChild)
                    }
                }
            }
        }
        return null
    }

    private fun isOverflowButton(view: View, toolbar: Toolbar): Boolean {
        if (view.javaClass.simpleName.contains("OverflowMenuButton")) {
            return true
        }
        val desc = view.contentDescription?.toString()
        val defaultDesc =
            toolbar.context.getString(androidx.appcompat.R.string.abc_action_menu_overflow_description)
        return desc != null && desc == defaultDesc
    }

    private fun extractOverflowItems(
        toolbar: Toolbar,
        actionMenuView: ActionMenuView,
        overflowButton: View,
    ): List<WitMenuItem> {
        val displayedActionIds = mutableSetOf<Int>()
        for (i in 0 until actionMenuView.childCount) {
            val child = actionMenuView.getChildAt(i)
            if (child !== overflowButton) {
                if (child.id != View.NO_ID) {
                    displayedActionIds.add(child.id)
                }
            }
        }

        val menu = toolbar.menu
        val result = mutableListOf<WitMenuItem>()
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            if (!item.isVisible) continue
            if (item.itemId in displayedActionIds) continue
            if (item.actionView != null) continue

            result.add(
                WitMenuItem(
                    id = item.itemId,
                    title = item.title ?: "",
                    enabled = item.isEnabled,
                )
            )
        }
        return result
    }
}
