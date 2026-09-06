package ceui.pixiv.snapshot

import ceui.lisa.activities.Shaft
import ceui.pixiv.api.model.Illust
import ceui.pixiv.cache.ObjectPool
import ceui.pixiv.services.appServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * 自动快照引擎：
 * - 收藏时生成离线快照（已有）；
 * - 插画/漫画自动生成快照：根据“反复浏览”和“长时间驻留”两个本地行为信号触发。
 *
 * 静默生成：不弹窗、不 toast；失败只记日志，不重试轰炸。
 * 选择性启用：收藏走 [Shaft.sSettings.isAutoSnapshotOnBookmark]，行为走
 * [Shaft.sSettings.isAutoSnapshotOnIllustManga]，开关关闭时不采集、不生成。
 */
object AutoSnapshotEngine {

    private const val TAG = "AutoSnapshot"

    /** 单次停留超过该时长视为“长时间驻留”。 */
    private const val DWELL_THRESHOLD_MS = 60_000L

    /** 7 天窗口内打开次数达到该值视为“反复浏览”。 */
    private const val REVISIT_THRESHOLD = 3

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Timber.tag(TAG).e(e, "auto snapshot scope crashed") }
    )

    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

    /** 当前详情页可见的停留起点，用于 onPause 时计算单次停留时长。 */
    private val dwellStart = ConcurrentHashMap<Long, Long>()

    /** 由 PixivActionQueue 在收藏请求被服务端确认成功后调用（事件驱动）。 */
    fun onBookmarkConfirmed(illust: Illust) {
        if (!Shaft.sSettings.isAutoSnapshotOnBookmark) return
        launchAutoSnapshot(illust, signal = null)
    }

    /** 详情页真正可见（onResume）时调用：记录一次打开，并检查是否达到反复浏览阈值。 */
    fun onArtworkPageVisible(illustId: Long, type: String?) {
        if (!Shaft.sSettings.isAutoSnapshotOnIllustManga) return
        if (illustId <= 0L) return
        dwellStart[illustId] = System.currentTimeMillis()
        val resolvedType = type ?: ObjectPool.get<Illust>(illustId).value?.type
        AutoSnapshotBehaviorStore.recordVisit(illustId, resolvedType)
        maybeTriggerRevisit(illustId)
    }

    /** 详情页离开（onPause）时调用：记录停留时长，并检查是否达到长时间驻留阈值。 */
    fun onArtworkPageHidden(illustId: Long) {
        if (!Shaft.sSettings.isAutoSnapshotOnIllustManga) return
        if (illustId <= 0L) return
        val start = dwellStart.remove(illustId) ?: return
        val dwellMs = System.currentTimeMillis() - start
        if (dwellMs <= 0L) return
        AutoSnapshotBehaviorStore.recordDwell(illustId, dwellMs)
        if (dwellMs >= DWELL_THRESHOLD_MS) {
            maybeTriggerBehaviorAuto(illustId, AutoSnapshotBehaviorStore.SIGNAL_DWELL)
        }
    }

    private fun maybeTriggerRevisit(illustId: Long) {
        val record = AutoSnapshotBehaviorStore.read(illustId) ?: return
        if (record.recentVisits.size >= REVISIT_THRESHOLD) {
            maybeTriggerBehaviorAuto(illustId, AutoSnapshotBehaviorStore.SIGNAL_REVISIT)
        }
    }

    private fun maybeTriggerBehaviorAuto(illustId: Long, signal: String) {
        if (!Shaft.sSettings.isAutoSnapshotOnIllustManga) return
        val illust = ObjectPool.get<Illust>(illustId).value ?: return
        if (illust.isGif()) return
        launchAutoSnapshot(illust, signal)
    }

    /** 统一异步生成入口：去重、网络检查、已有快照检查、静默生成、配额与记录。 */
    private fun launchAutoSnapshot(illust: Illust, signal: String?) {
        val id = illust.id
        if (id <= 0L || !inFlight.add(id)) return

        scope.launch {
            try {
                val appContext = Shaft.getContext()
                // 无网/弱网不硬拉，静默跳过。
                if (appContext.appServices().networkStateManager.networkState.value?.isOnline != true) return@launch
                // 已有正式快照时不生成；已有同作品自动快照时不重复生成。
                val formalExists = SnapshotRepository.list(appContext).any { it.manifest.illustId == id }
                if (formalExists) return@launch
                val autoExists = AutoSnapshotRepository.listAuto(appContext).any { it.manifest.illustId == id }
                if (autoExists) return@launch

                SnapshotGenerator.generateAuto(appContext, illust)
                AutoSnapshotRepository.enforceAutoQuota(appContext)
                AutoSnapshotBehaviorStore.prune()
                if (signal != null) {
                    AutoSnapshotBehaviorStore.markAutoSnapshotGenerated(id, signal)
                }
                Timber.tag(TAG).i("auto snapshot generated, illustId=%d, signal=%s", id, signal ?: "bookmark")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "auto snapshot failed, illustId=%d", id)
            } finally {
                inFlight.remove(id)
            }
        }
    }
}