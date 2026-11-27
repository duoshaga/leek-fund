package leek

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.APP)
@State(name = "LeekBoxWatchlist", storages = [Storage(value = "leekbox.xml", roamingType = RoamingType.DISABLED)])
class LeekService(scope: CoroutineScope) : PersistentStateComponent<SavedWatchlist> {
    private val book = WatchBook(MarketSessions::interval)
    private val source: QuoteSource = MarketQuoteSource()
    private val hoursSource = DomesticHoursSource()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val hoursWake = Channel<Unit>(Channel.CONFLATED)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val updatePending = AtomicBoolean()

    // Directory and contract requests have their own worker so a slow lookup cannot delay quotes.
    private val hoursWorker = scope.launch(start = CoroutineStart.LAZY) {
        while (isActive) {
            val now = Instant.now()
            val request = book.reserveHours(now)
            if (request == null) {
                withTimeoutOrNull(60_000L) { hoursWake.receive() }
                continue
            }
            val hours = try {
                runInterruptible(Dispatchers.IO) { hoursSource.fetch(request.product, now) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            book.completeHours(request, hours, Instant.now())
            wake.trySend(Unit)
            publish()
        }
    }

    // All project windows share this worker, including when every sidebar is collapsed.
    private val worker = scope.launch(start = CoroutineStart.LAZY) {
        while (isActive) {
            val requests = book.reserve(Instant.now())
            if (requests.isNotEmpty()) {
                val results = try {
                    runInterruptible(Dispatchers.IO) { source.fetch(requests.map { it.symbol }) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    requests.associate { it.symbol.code to Result.failure<Quote?>(failure) }
                }
                val received = Instant.now()
                for (request in requests) {
                    val result = results[request.symbol.code] ?: Result.failure(IOException("Missing quote response"))
                    book.complete(request, result, received)
                }
                publish()
            }
            withTimeoutOrNull(1000L) { wake.receive() }
        }
    }

    fun view() = book.view()
    override fun getState() = book.save()
    override fun loadState(state: SavedWatchlist) {
        book.restore(state)
        hoursWake.trySend(Unit)
        wake.trySend(Unit)
        publish()
    }

    fun add(symbol: Symbol, displayName: String? = null): Boolean {
        val added = book.add(symbol, displayName)
        if (added) {
            hoursWake.trySend(Unit)
            wake.trySend(Unit)
            publish()
        }
        return added
    }

    fun remove(code: String) {
        book.remove(code)
        publish()
    }

    internal fun moveGroup(source: WatchGroup, target: WatchGroup, after: Boolean) =
        book.moveGroup(source, target, after).also { if (it) publish() }

    internal fun moveSymbol(code: String, target: String, after: Boolean) =
        book.moveSymbol(code, target, after).also { if (it) publish() }

    fun pin(code: String?) {
        book.pin(code)
        publish()
    }

    fun refresh() {
        book.requestAll()
        hoursWake.trySend(Unit)
        wake.trySend(Unit)
    }

    fun setRefreshHours(code: String, text: String): Boolean {
        if (!book.setRefreshHours(code, text)) return false
        wake.trySend(Unit)
        publish()
        return true
    }

    internal fun subscribe(parent: Disposable, listener: () -> Unit) {
        listeners.add(listener)
        Disposer.register(parent, Disposable { listeners.remove(listener) })
        worker.start()
        hoursWorker.start()
        publish()
    }

    private fun publish() {
        if (listeners.isEmpty() || !updatePending.compareAndSet(false, true)) return
        ApplicationManager.getApplication().invokeLater({
            updatePending.set(false)
            listeners.forEach { it() }
        }, ModalityState.any())
    }

    companion object {
        fun get(): LeekService = ApplicationManager.getApplication().getService(LeekService::class.java)
    }
}
