package ceui.pixiv.witstudio.popup

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import ceui.pixiv.witstudio.R
import ceui.pixiv.witstudio.theme.V3Palette
import ceui.pixiv.witstudio.theme.WitDisplay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 独立的流体形态变换（Morphing Surface）弹出菜单。
 *
 * 结合原生 Android Toolbar 交互特征与 1:1 prism_ui 核心物理动效：
 * 1. 原按钮图标连续融入（无突兀消失）：
 *    - 动效第 0 帧严格对齐为按钮按下时的圆形区域（直径 ~40dp，全圆角半径 20dp）；
 *    - 纯净提取原按钮图标（排除背景干扰），在展开前 25% 阶段居中渐隐溶解，收起归巢后半段渐显重组；
 * 2. 纯粹应用主题色派生色（避免 Toolbar ThemeOverlay 灰暗污染）：
 *    - 从宿主 Activity 解析真实主题色 `colorPrimary`，按日夜模式精确混色：
 *      深色模式 28% 主题色 + 72% 深色底，浅色模式 14% 主题色 + 86% 白底；
 *    - 与纯色 Toolbar 呈现精致克制的微差异，视觉上血统分明；
 *    - 完全移除卡片和动画运动中的阴影（无任何阴影画笔和绘制，零光影伪影）；
 * 3. 物理系统（1:1 prism_ui 物理系统）：
 *    - 打开主弹簧：SpringSimulation(mass=1, stiffness=400, ratio=0.754)，周期 450ms；
 *    - 圆角退行弹簧：SpringSimulation(mass=1, stiffness=324, ratio=0.75)，70ms 延迟；
 *    - 垂直起伏涌动：riseAndFall(time, 0.098, 3.3)；
 *    - 收起归巢弹簧：SpringSimulation(mass=1, stiffness=165, ratio=0.8)，周期 500ms，归巢收拢并淡出。
 */
public class WitMorphPopup private constructor(private val context: Context) {

    public companion object {
        private const val WIDTH_DP = 190
        private const val MAX_HEIGHT_DP = 360
        private const val CARD_RADIUS_DP = 20
        private const val EDGE_PROTECTION_DP = 16
        private const val ANCHOR_OVERLAP_DP = 8

        private const val OPEN_DURATION_MS = 450L
        private const val DISMISS_DURATION_MS = 500L

        /**
         * 计算 prism_ui (motion.dart:61) 的液态水滴起伏函数：
         * Rises to 1 at [peak] and back, as droplets overshoot and swell.
         */
        @JvmStatic
        @JvmOverloads
        public fun riseAndFall(time: Float, peak: Float = 0.098f, sharpness: Float = 3.3f): Float {
            if (time <= 0f) return 0f
            val x = time / peak
            return (x.toDouble()
                .pow(sharpness.toDouble()) * exp((sharpness * (1f - x)).toDouble())).toFloat()
        }

        /**
         * 计算 prism_ui (popup.dart:102) 打开主弹簧位移：
         * SpringSimulation(mass=1, stiffness=400, ratio=0.754), 0 -> 1
         */
        @JvmStatic
        public fun openSpring(time: Float): Float {
            if (time <= 0f) return 0f
            val omega0 = 20.0 // sqrt(400)
            val zeta = 0.754
            val gamma = zeta * omega0 // 15.08
            val omegaD = omega0 * sqrt(1.0 - zeta * zeta) // ~13.13749
            val c = gamma / omegaD // ~1.14786

            val envelope = exp(-gamma * time)
            val oscillation = cos(omegaD * time) + c * sin(omegaD * time)
            return (1.0 - envelope * oscillation).toFloat()
        }

        /**
         * 计算 prism_ui (popup.dart:108) 圆角退行弹簧位移：
         * SpringSimulation(mass=1, stiffness=324, ratio=0.75), 1 -> 0
         */
        @JvmStatic
        public fun cornersSpring(time: Float): Float {
            if (time <= 0f) return 1f
            val omega0 = 18.0 // sqrt(324)
            val zeta = 0.75
            val gamma = zeta * omega0 // 13.5
            val omegaD = omega0 * sqrt(1.0 - zeta * zeta) // ~11.90588
            val c = gamma / omegaD // ~1.13389

            val envelope = exp(-gamma * time)
            val oscillation = cos(omegaD * time) + c * sin(omegaD * time)
            return (envelope * oscillation).toFloat()
        }

        /**
         * 计算 prism_ui (motion.dart:54, popup.dart:90) 收起水滴弹簧位移：
         * SpringSimulation(mass=1, stiffness=165, ratio=0.8), 0 -> 1
         */
        @JvmStatic
        public fun dropletSpring(time: Float): Float {
            if (time <= 0f) return 0f
            val omega0 = 12.84523 // sqrt(165)
            val zeta = 0.8
            val gamma = zeta * omega0 // 10.27618
            val omegaD = omega0 * 0.6 // 7.70714
            val c = gamma / omegaD // ~1.33333

            val envelope = exp(-gamma * time)
            val oscillation = cos(omegaD * time) + c * sin(omegaD * time)
            return (1.0 - envelope * oscillation).toFloat()
        }

        /**
         * 从指定锚点按钮形态变换展开菜单。
         */
        @JvmStatic
        @JvmOverloads
        public fun show(
            context: Context,
            anchor: View,
            items: List<WitMenuItem>,
            @ColorInt backgroundColor: Int? = null,
            @ColorInt contentColor: Int? = null,
            onItemSelected: ((WitMenuItem) -> Unit)? = null,
        ): WitMorphPopup? {
            val popup = WitMorphPopup(context)
            val success =
                popup.display(anchor, items, backgroundColor, contentColor, onItemSelected)
            return if (success) popup else null
        }
    }

    private var rootViewGroup: ViewGroup? = null
    private var overlayView: FrameLayout? = null
    private var liquidPlatterView: LiquidPlatterView? = null
    private var listView: ListView? = null
    private var activeAnimator: ValueAnimator? = null
    private var backCallback: OnBackPressedCallback? = null
    private var targetAnchor: View? = null
    private var isDismissing: Boolean = false

    private val anchorRect = RectF()
    private val targetRect = RectF()

    public val isShowing: Boolean
        get() = overlayView != null && overlayView?.isAttachedToWindow == true

    public fun dismiss(): Unit {
        if (!isShowing || isDismissing) return
        isDismissing = true

        activeAnimator?.cancel()

        val platter = liquidPlatterView
        val overlay = overlayView
        val anchor = targetAnchor

        if (platter != null && overlay != null) {
            val dismissAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = DISMISS_DURATION_MS
                addUpdateListener { anim ->
                    val fraction = anim.animatedValue as Float
                    val time = fraction * (DISMISS_DURATION_MS / 1000f)
                    platter.updateDismissProgress(fraction, time)
                    if (time >= 0.30f) anchor?.visibility = View.VISIBLE
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        platter.updateDismissProgress(1f, DISMISS_DURATION_MS / 1000f)
                        finishDismiss(overlay, anchor)
                    }
                })
            }
            activeAnimator = dismissAnim
            dismissAnim.start()
        } else {
            finishDismiss(overlay, anchor)
        }
    }

    private fun finishDismiss(overlay: View?, anchor: View?) {
        isDismissing = false
        activeAnimator = null
        backCallback?.remove()
        backCallback = null

        overlay?.let {
            rootViewGroup?.removeView(it)
        }
        overlayView = null
        liquidPlatterView = null
        listView = null

        anchor?.visibility = View.VISIBLE
        targetAnchor = null
    }

    private fun display(
        anchor: View,
        items: List<WitMenuItem>,
        @ColorInt customBgColor: Int?,
        @ColorInt customFgColor: Int?,
        onItemSelected: ((WitMenuItem) -> Unit)?,
    ): Boolean {
        if (anchor.width <= 0 || anchor.height <= 0) return false
        val activity = anchor.context.findActivity() ?: context.findActivity() ?: return false
        val decorView = activity.window.decorView as? ViewGroup ?: return false
        rootViewGroup = decorView
        targetAnchor = anchor

        // 1. 获取宿主真实主题色，避开 Toolbar 局部 ThemeOverlay 对 colorPrimary 的灰色覆盖
        val palette = V3Palette.from(activity)
        val isDark =
            (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        val primaryColor = palette.primary

        // 主题色派生色：深色模式 28% 主题色 + 72% 深色底；浅色模式 14% 主题色 + 86% 白底
        val cardDerivedColor = customBgColor ?: if (isDark) {
            ColorUtils.blendARGB(primaryColor, Color.parseColor("#18181C"), 0.72f)
        } else {
            ColorUtils.blendARGB(primaryColor, Color.WHITE, 0.86f)
        }
        val textColor = customFgColor ?: if (isDark) Color.WHITE else Color.parseColor("#1C1C1E")

        // 2. 纯净截取原按钮图标外观（临时剥离背景，确保仅捕获纯净前景图标，无多余底色/阴影）
        val anchorBitmap = try {
            val bg = anchor.background
            anchor.background = null
            val bmp = Bitmap.createBitmap(anchor.width, anchor.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            anchor.draw(canvas)
            anchor.background = bg
            bmp
        } catch (_: Throwable) {
            null
        }

        // 3. 在挂载覆盖层前抓取当前页面，作为液体玻璃的背景采样。
        val backdropBitmap = try {
            Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                decorView.draw(Canvas(bitmap))
            }
        } catch (_: Throwable) {
            null
        }?.let(::blurBackdrop)

        // 4. 计算几何位置（DecorView 根坐标系）
        val decorLoc = IntArray(2)
        decorView.getLocationInWindow(decorLoc)
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val anchorLeft = (loc[0] - decorLoc[0]).toFloat()
        val anchorTop = (loc[1] - decorLoc[1]).toFloat()
        val anchorW = anchor.width.toFloat()
        val anchorH = anchor.height.toFloat()
        val anchorRight = anchorLeft + anchorW
        val anchorBottom = anchorTop + anchorH

        anchorRect.set(anchorLeft, anchorTop, anchorRight, anchorBottom)

        val metrics = activity.resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels
        val edgeProtectionPx = WitDisplay.dp2px(activity, EDGE_PROTECTION_DP).toFloat()
        val popupW = WitDisplay.dp2px(activity, WIDTH_DP).toFloat()
        val maxHeightPx = WitDisplay.dp2px(activity, MAX_HEIGHT_DP)
        val overlapPx = WitDisplay.dp2px(activity, ANCHOR_OVERLAP_DP).toFloat()

        // 4. 构建列表内容（纯文本 / 可选图标）
        val list = ListView(activity).apply {
            divider = null
            dividerHeight = 0
            isVerticalScrollBarEnabled = false
            overScrollMode = ListView.OVER_SCROLL_NEVER
        }
        val adapter = MorphMenuAdapter(activity, items, textColor)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val item = items[position]
            if (item.enabled) {
                item.onClick?.invoke()
                onItemSelected?.invoke(item)
                dismiss()
            }
        }
        listView = list

        list.measure(
            View.MeasureSpec.makeMeasureSpec(popupW.toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)
        )
        val popupH =
            list.measuredHeight.coerceAtMost(maxHeightPx).coerceAtLeast(anchorH.toInt()).toFloat()

        // 目标几何：右边缘严格对齐原按钮右边缘，上边缘上偏 8dp（ANCHOR_OVERLAP_DP）
        val targetRightVal =
            anchorRight.coerceIn(popupW + edgeProtectionPx, screenW - edgeProtectionPx)
        val targetLeftVal = targetRightVal - popupW
        val targetTopVal =
            (anchorTop - overlapPx).coerceIn(edgeProtectionPx, screenH - popupH - edgeProtectionPx)
        val targetBottomVal = targetTopVal + popupH

        targetRect.set(targetLeftVal, targetTopVal, targetRightVal, targetBottomVal)

        // 5. 创建全屏覆盖层
        val overlay = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { dismiss() }
            isFocusableInTouchMode = true
            requestFocus()
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    dismiss()
                    true
                } else {
                    false
                }
            }
        }
        overlayView = overlay

        // 6. 创建形态变换承载 View（对齐按下时的圆形区域，融入原按钮图标，动效中保持主题色平滑过渡到派生色，完全无阴影）
        val platter = LiquidPlatterView(
            context = activity,
            anchorRect = anchorRect,
            targetRect = targetRect,
            anchorBitmap = anchorBitmap,
            backdropBitmap = backdropBitmap,
            primaryColor = primaryColor,
            cardColor = cardDerivedColor,
            contentView = list
        )
        liquidPlatterView = platter

        overlay.addView(
            platter,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 7. 挂载到 DecorView，隐藏真实原按钮
        decorView.addView(overlay)
        anchor.visibility = View.INVISIBLE

        (activity as? ComponentActivity)?.let { compAct ->
            val callback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    dismiss()
                }
            }
            compAct.onBackPressedDispatcher.addCallback(compAct, callback)
            backCallback = callback
        }

        // 8. 启动二阶物理弹簧展开动画
        startEnterAnimation(platter)
        return true
    }

    private fun startEnterAnimation(platter: LiquidPlatterView) {
        activeAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = OPEN_DURATION_MS
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                val time = fraction * (OPEN_DURATION_MS / 1000f)
                platter.updateOpenProgress(time)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    activeAnimator = null
                    platter.updateOpenProgress(OPEN_DURATION_MS / 1000f)
                }
            })
        }
        activeAnimator = animator
        animator.start()
    }

    /**
     * 形态变换核心承载容器。
     * - 起始帧严格对齐按钮按下态的圆形区域，圆心与原按钮绝对锁定；
     * - 原按钮图标在展开初期与收起末期平滑融入；
     * - 完全移除卡片和运动中的阴影（无任何阴影画笔和绘制）；
     * - 动效中按钮保持主题色 primaryColor，随着卡片萌生平滑渐变到卡片派生色 cardColor。
     */
    private class LiquidPlatterView(
        context: Context,
        private val anchorRect: RectF,
        private val targetRect: RectF,
        private val anchorBitmap: Bitmap?,
        private val backdropBitmap: Bitmap?,
        @param:ColorInt private val primaryColor: Int,
        @param:ColorInt private val cardColor: Int,
        private val contentView: View,
    ) : FrameLayout(context) {

        private val cardRadiusPx = WitDisplay.dp2px(context, CARD_RADIUS_DP).toFloat()

        // 严格对齐按钮按下态时的圆形区域尺寸（直径 ~40dp）
        private val startSize = min(anchorRect.width(), anchorRect.height()).coerceIn(
            WitDisplay.dp2px(context, 36).toFloat(),
            WitDisplay.dp2px(context, 44).toFloat()
        )

        private var snapAlpha: Int = 255
        private var glassProgress: Float = 0f
        // 当前卡片轮廓
        private val liquidPath = Path()
        private val currentPlatterRect = RectF()

        // 实体背景画笔（从按钮主题色平滑渐变到卡片派生色，无轮廓、无阴影）
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primaryColor
            style = Paint.Style.FILL
        }
        private val snapPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private val backdropView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(backdropBitmap)
            alpha = 0.82f
        }

        init {
            setWillNotDraw(false)
            setOnClickListener { /* 拦截点击 */ }

            addView(backdropView, LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))

            val listParams = LayoutParams(
                targetRect.width().toInt(),
                targetRect.height().toInt()
            ).apply {
                leftMargin = targetRect.left.toInt()
                topMargin = targetRect.top.toInt()
            }
            addView(contentView, listParams)

            // 立即同步初始化第 0 帧状态，确保首帧绝对精准锚定在原按钮中心
            updateOpenProgress(0f)
        }

        /**
         * 展开物理演进：
         * 从按下态正圆形（直径 startSize，100% 保持主题色）向卡片（targetRect，卡片派生色）平滑膨胀滑翔，图标渐隐融入。
         */
        public fun updateOpenProgress(time: Float) {
            val prog = if (time <= 0f) 0f else openSpring(time)

            // 1. 尺度缩放：从按下时的圆形尺寸平滑扩展到卡片长宽
            val curW = if (time <= 0f) startSize else max(
                startSize,
                lerp(startSize, targetRect.width(), prog)
            )
            val curH = if (time <= 0f) startSize else max(
                startSize,
                lerp(startSize, targetRect.height(), prog)
            )

            // 2. 垂直水滴涌动起伏（第 0 帧严格归零）
            val dip = if (time <= 0f) 0f else {
                val toCardY = targetRect.centerY() - anchorRect.centerY()
                val distanceY = abs(toCardY)
                val dipScale = WitDisplay.dp2px(context, 23).toFloat()
                sign(toCardY) * (min(
                    1.0f,
                    distanceY / WitDisplay.dp2px(context, 30)
                ) * dipScale + 0.124f * distanceY) *
                        riseAndFall(time, 0.098f, 3.3f)
            }

            // 3. 中心轨迹平移：从按钮中心平滑滑翔至卡片中心（第 0 帧严格居中原按钮）
            val curCenterX = if (time <= 0f) anchorRect.centerX() else lerp(
                anchorRect.centerX(),
                targetRect.centerX(),
                prog
            )
            val curCenterY = if (time <= 0f) anchorRect.centerY() else lerp(
                anchorRect.centerY(),
                targetRect.centerY(),
                prog
            ) + dip

            currentPlatterRect.set(
                curCenterX - curW / 2f,
                curCenterY - curH / 2f,
                curCenterX + curW / 2f,
                curCenterY + curH / 2f
            )

            // 4. 圆角演变：起始帧为 startSize / 2f（正圆形，无方框！），展开初期保持胶囊，70ms 后自然方化至 20dp
            val capsuleRadius = min(curW, curH) / 2f
            val cornerTime = max(0f, time - 0.07f)
            val cornerFactor = cornersSpring(cornerTime)
            val corners = cardRadiusPx + targetRect.width() * 0.48f * cornerFactor
            val platterRadius = min(capsuleRadius, corners)

            liquidPath.reset()
            liquidPath.addRoundRect(
                currentPlatterRect,
                platterRadius,
                platterRadius,
                Path.Direction.CW
            )

            // 5. 动效中按钮这块保持主题色：从 primaryColor 平滑演进至 cardColor
            val colorProg = ((prog - 0.02f) / 0.60f).coerceIn(0f, 1f)
            fillPaint.color = ColorUtils.blendARGB(primaryColor, cardColor, colorProg)

            // 玻璃只存在于过渡阶段：中段降低卡片不透明度，让背景模糊透出；稳态恢复纯色。
            val transitionGlass = (prog * 3f).coerceIn(0f, 1f) * (1f - prog).coerceIn(0f, 1f)
            fillPaint.alpha = lerp(255f, 150f, transitionGlass).toInt()
            backdropView.alpha = 0.90f * transitionGlass
            glassProgress = transitionGlass
            val iconAlpha = if (time <= 0f) 1f else (1f - (prog / 0.25f)).coerceIn(0f, 1f)
            snapAlpha = if (time >= 0.32f) 0 else (iconAlpha * 255f).toInt()
            val contentAlpha = if (time <= 0f) 0f else ((prog - 0.08f) / 0.35f).coerceIn(0f, 1f)
            contentView.alpha = contentAlpha

            invalidate()
        }

        /**
         * 收起物理归巢：
         * 从卡片缩回原按钮中心正圆形并淡出，图标渐显重组，圆心严格锁相归巢。
         */
        public fun updateDismissProgress(fraction: Float, time: Float) {
            val ringProgress = ((time - 0.40f) / 0.10f).coerceIn(0f, 1f)
            val ringing = 1.0f - (ringProgress * ringProgress)
            val rawReturnProgress = 1.0f + (dropletSpring(time) - 1.0f) * ringing

            val toCardY = targetRect.centerY() - anchorRect.centerY()
            val away = sign(toCardY)
            val dipLimit = WitDisplay.dp2px(context, 22).toFloat()
            val dip = min(0.21f * abs(toCardY), dipLimit) * riseAndFall(time, 0.072f, 2.4f)

            // 锁相归巢（Phase Lock）：在最后 80ms（time >= 0.42s），强制快速平滑收敛锁定在原按钮中心
            val snapToAnchor = if (time >= 0.42f) {
                ((time - 0.42f) / 0.08f).coerceIn(0f, 1f)
            } else {
                0f
            }
            val returnProgress = lerp(rawReturnProgress, 1.0f, snapToAnchor)

            val curW = max(startSize, lerp(targetRect.width(), startSize, returnProgress))
            val curH = max(startSize, lerp(targetRect.height(), startSize, returnProgress))

            val rawCenterX = lerp(targetRect.centerX(), anchorRect.centerX(), returnProgress)
            val rawCenterY = lerp(
                targetRect.centerY(),
                anchorRect.centerY(),
                returnProgress
            ) + away * dip * ringing

            val curCenterX = lerp(rawCenterX, anchorRect.centerX(), snapToAnchor)
            val curCenterY = lerp(rawCenterY, anchorRect.centerY(), snapToAnchor)

            currentPlatterRect.set(
                curCenterX - curW / 2f,
                curCenterY - curH / 2f,
                curCenterX + curW / 2f,
                curCenterY + curH / 2f
            )

            val platterRadius = lerp(cardRadiusPx, startSize / 2f, returnProgress)

            liquidPath.reset()
            liquidPath.addRoundRect(
                currentPlatterRect,
                platterRadius,
                platterRadius,
                Path.Direction.CW
            )

            // 内容快速淡出
            contentView.alpha = (1.0f - (time / 0.10f)).coerceIn(0f, 1f)

            // 颜色演进：随着收拢归巢从卡片派生色平滑变回主题色
            val colorProg =
                lerp(((1.0f - returnProgress) / 0.60f).coerceIn(0f, 1f), 0f, snapToAnchor)
            fillPaint.color = ColorUtils.blendARGB(primaryColor, cardColor, colorProg)

            // 收起阶段交给真实按钮接管，避免快照图标与真实按钮在最后一帧跳变。
            snapAlpha = 0

            val platterAlpha = if (time > 0.40f) {
                (1.0f - ((time - 0.40f) / 0.10f)).coerceIn(0f, 1f)
            } else {
                1.0f
            }
            val transitionGlass = (time / 0.26f).coerceIn(0f, 1f) * platterAlpha
            fillPaint.alpha = (platterAlpha * lerp(255f, 150f, transitionGlass)).toInt()
            backdropView.alpha = 0.90f * transitionGlass
            glassProgress = transitionGlass
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
        }

        override fun dispatchDraw(canvas: Canvas) {
            if (liquidPath.isEmpty) return

            canvas.save()
            canvas.clipPath(liquidPath)
            backdropView.draw(canvas)
            canvas.drawPath(liquidPath, fillPaint)

            if (snapAlpha > 0 && anchorBitmap != null) {
                snapPaint.alpha = snapAlpha
                val snapW = anchorRect.width()
                val snapH = anchorRect.height()
                val snapRect = RectF(
                    currentPlatterRect.centerX() - snapW / 2f,
                    currentPlatterRect.centerY() - snapH / 2f,
                    currentPlatterRect.centerX() + snapW / 2f,
                    currentPlatterRect.centerY() + snapH / 2f
                )
                canvas.drawBitmap(anchorBitmap, null, snapRect, snapPaint)
            }

            if (contentView.alpha > 0f) {
                val contentScale =
                    (currentPlatterRect.width() / targetRect.width()).coerceIn(0.01f, 1.05f)
                canvas.translate(currentPlatterRect.left, currentPlatterRect.top)
                canvas.scale(contentScale, contentScale)
                contentView.draw(canvas)
            }
            canvas.restore()
        }
    }

    /**
     * 菜单列表适配器。
     */
    private class MorphMenuAdapter(
        private val context: Context,
        private val items: List<WitMenuItem>,
        @param:ColorInt private val textColor: Int,
    ) : BaseAdapter() {

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): WitMenuItem = items[position]
        override fun getItemId(position: Int): Long = items[position].id.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = getItem(position)
            val view = convertView ?: LayoutInflater.from(context).inflate(
                R.layout.wit_item_menu,
                parent,
                false
            )
            val textView = view as? TextView ?: view.findViewById(android.R.id.text1)

            textView?.apply {
                text = item.title
                setTextColor(textColor)
                alpha = if (item.enabled) 1.0f else 0.45f
                isEnabled = item.enabled
            }

            return view
        }
    }
}

private fun blurBackdrop(source: Bitmap): Bitmap {
    val targetWidth = (source.width / 8).coerceAtLeast(1)
    val targetHeight = (source.height / 8).coerceAtLeast(1)
    val reduced = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    return Bitmap.createScaledBitmap(reduced, source.width, source.height, true)
}

private fun lerp(start: Float, stop: Float, amount: Float): Float {
    return start + (stop - start) * amount
}
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

