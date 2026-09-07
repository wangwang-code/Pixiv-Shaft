package ceui.pixiv.download

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.testing.TestLifecycleOwner
import ceui.pixiv.communication.StateEntry
import ceui.pixiv.communication.android.collectIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRecordStateSourceTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun `completion changes the visible button state without leaving the page`() = runTest(dispatcher) {
        val changes = MutableStateFlow(0)
        var downloaded = false
        val source = DownloadRecordStateSource({ changesAsUnits(changes) }, { downloaded })
        val seen = mutableListOf<StateEntry<Boolean>>()
        backgroundScope.launch { source.observe(42).collect { seen.add(it) } }
        runCurrent()
        downloaded = true
        changes.value++
        runCurrent()
        changes.value++ // An unrelated download must not render the same button again.
        runCurrent()
        assertEquals(listOf(StateEntry.Unknown, StateEntry.Value(false), StateEntry.Value(true)), seen)
    }

    @Test fun `fresh source restores stored records without a completion event`() = runTest(dispatcher) {
        val persistedIds = setOf(42L)
        fun freshSource() = DownloadRecordStateSource(
            { changesAsUnits(MutableStateFlow(0)) },
            { it in persistedIds },
        )
        // No process-local topic/cache is shared by the two instances.
        assertEquals(StateEntry.Value(true), freshSource().observe(42).first { it is StateEntry.Value })
        assertEquals(StateEntry.Value(true), freshSource().observe(42).first { it is StateEntry.Value })
        assertEquals(StateEntry.Value(false), freshSource().observe(99).first { it is StateEntry.Value })
    }

    @Test fun `hidden pager does no probes and resuming reads current records`() = runTest(dispatcher) {
        val owner = TestLifecycleOwner(Lifecycle.State.STARTED)
        val changes = MutableStateFlow(0)
        var downloaded = false
        var probes = 0
        val source = DownloadRecordStateSource({ changesAsUnits(changes) }, { probes++; downloaded })
        val seen = mutableListOf<StateEntry<Boolean>>()
        source.observe(42).collectIn(owner, minState = Lifecycle.State.RESUMED,
            onError = { throw AssertionError(it) }) { seen.add(it) }
        runCurrent()
        assertEquals(0, probes)
        owner.setCurrentState(Lifecycle.State.RESUMED)
        runCurrent()
        assertEquals(1, probes)
        owner.setCurrentState(Lifecycle.State.STARTED)
        runCurrent()
        downloaded = true
        changes.value++
        runCurrent()
        assertEquals(1, probes)
        owner.setCurrentState(Lifecycle.State.RESUMED)
        runCurrent()
        assertEquals(2, probes)
        assertEquals(StateEntry.Value(true), seen.last())
        owner.setCurrentState(Lifecycle.State.DESTROYED)
        runCurrent()
        changes.value++
        runCurrent()
        assertEquals(2, probes)
    }

    @Test fun `failed read remains unknown and later record changes recover`() = runTest(dispatcher) {
        val changes = MutableStateFlow(0)
        var failed = true
        val source = DownloadRecordStateSource({ changesAsUnits(changes) }, {
            if (failed) error("database temporarily unavailable")
            true
        })
        val seen = mutableListOf<StateEntry<Boolean>>()
        backgroundScope.launch { source.observe(42).collect { seen.add(it) } }
        runCurrent()
        assertEquals(listOf(StateEntry.Unknown), seen)
        failed = false
        changes.value++
        runCurrent()
        assertEquals(StateEntry.Value(true), seen.last())
    }

    @Test fun `cancelling a hidden view cancels its probe without emitting a stale result`() = runTest(dispatcher) {
        var cancelled = false
        val source = DownloadRecordStateSource({ changesAsUnits(MutableStateFlow(0)) }, {
            try { awaitCancellation() }
            catch (error: CancellationException) { cancelled = true; throw error }
        })
        val seen = mutableListOf<StateEntry<Boolean>>()
        val job = backgroundScope.launch { source.observe(42).collect { seen.add(it) } }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(listOf(StateEntry.Unknown), seen)
    }

    private fun changesAsUnits(changes: MutableStateFlow<Int>) =
        kotlinx.coroutines.flow.flow { changes.collect { emit(Unit) } }
}
