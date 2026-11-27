// Run by Rider's IDE scripting host in the isolated acceptance profile only.
import com.google.gson.GsonBuilder
import com.intellij.ide.impl.ProjectUtil
import com.intellij.ide.IdeBundle
import com.intellij.ide.plugins.CustomPluginRepositoryService
import com.intellij.ide.plugins.PluginManagerConfigurable
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.RepositoryHelper
import com.intellij.ide.plugins.newui.ListPluginComponent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.updateSettings.impl.PluginDownloader
import com.intellij.openapi.updateSettings.impl.PluginUpdateSourceService
import com.intellij.openapi.updateSettings.impl.UpdateSettings
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.hover.TreeHoverListener
import com.intellij.ui.render.RenderingUtil
import com.intellij.util.net.JdkProxyCustomizer
import com.intellij.util.ui.UIUtil
import javax.swing.*
import javax.swing.tree.TreePath
import java.awt.Container
import java.awt.Point
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DragSource
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

class QuoteTraffic extends ProxySelector {
    static final hosts = ['hq.sinajs.cn', 'qt.gtimg.cn', 'api.fund.eastmoney.com', 'fundsuggest.eastmoney.com', 'futsse-static.eastmoney.com'] as Set
    final offline = new AtomicBoolean(false)
    final requests = new CopyOnWriteArrayList()
    final files = new CopyOnWriteArrayList()
    final seq = new AtomicInteger()
    final witnesses = java.util.concurrent.Executors.newFixedThreadPool(4)
    final observing = new ThreadLocal<Boolean>()
    final File folder
    final int closedPort
    QuoteTraffic(int port, File dest) { closedPort = port; folder = dest; dest.mkdirs() }
    List<Proxy> select(URI uri) {
        if (observing.get() || uri.scheme != 'https' || !hosts.contains(uri.host)) return []
        def record = [at: Instant.now().toString(), url: uri.toString(), failedByTest: offline.get()]
        requests.add(record)
        if (record.failedByTest) return [new Proxy(Proxy.Type.HTTP, new InetSocketAddress('127.0.0.1', closedPort))]
        // A separate HTTPS read at the same instant provides an independent source observation.
        // Its requests are excluded from the production request count.
        witnesses.submit {
            observing.set(true)
            def sample = [at: record.at, url: record.url, file: String.format('%03d.raw', seq.incrementAndGet())]
            def connection
            try {
                connection = uri.toURL().openConnection()
                connection.connectTimeout = 5000
                connection.readTimeout = 7000
                connection.setRequestProperty('User-Agent','Mozilla/5.0')
                def referer = ['hq.sinajs.cn': 'https://finance.sina.com.cn/', 'qt.gtimg.cn': 'https://gu.qq.com/',
                               'api.fund.eastmoney.com': 'https://fundf10.eastmoney.com/', 'fundsuggest.eastmoney.com': 'https://fund.eastmoney.com/',
                               'futsse-static.eastmoney.com': 'https://quote.eastmoney.com/']
                connection.setRequestProperty('Referer', referer[uri.host])
                if (connection.responseCode == 200) new File(folder, sample.file).bytes = connection.inputStream.withCloseable { it.readNBytes(524289) }
                sample.status = connection.responseCode
            } catch (Exception failure) { sample.error = failure.toString() }
            finally { connection?.disconnect(); files.add(sample); observing.remove() }
        }
        // Let Rider's normal proxy configuration select the route for this request.
        return []
    }
    void connectFailed(URI uri, SocketAddress address, IOException problem) {
    }
}

def edt(Closure block) {
    if (SwingUtilities.isEventDispatchThread()) return block()
    def result = new java.util.concurrent.FutureTask(block as java.util.concurrent.Callable)
    SwingUtilities.invokeAndWait(result)
    return result.get()
}
def awaitValue(String label, int seconds = 65, Closure resolve) {
    long until = System.nanoTime() + seconds * 1_000_000_000L
    while (System.nanoTime() < until) {
        def result = resolve()
        if (result) return result
        Thread.sleep(180)
    }
    throw new AssertionError('Timed out waiting for ' + label)
}
def child(component, String name) {
    if (component.name == name) return component
    if (component instanceof Container) {
        for (c in component.components) { def hit = child(c, name); if (hit != null) return hit }
    }
    return null
}
def tool(project) { ToolWindowManager.getInstance(project).getToolWindow('韭菜盒子') }
def widget(project) { WindowManager.instance.getStatusBar(project)?.getWidget('LeekBoxPinned') }
def controls(project) { tool(project).contentManagerIfCreated?.contents?.find()?.component }
def panel(project) {
    awaitValue('tool window extension') { edt { tool(project) } }
    edt { tool(project).show() }
    return awaitValue('native watchlist component') { edt { controls(project) } }
}
def selectCode(pane, String code) {
    def tree = child(pane, 'leek.watchlist')
    def node = tree.model.root.depthFirstEnumeration().find { it.userObject?.class?.name == 'leek.Symbol' && it.userObject.code == code }
    assert node != null : code
    tree.selectionPath = new TreePath(node.path)
}
def addCode(pane, String code) {
    edt { child(pane, 'leek.code').text = code }
    if (code ==~ /[0-9]{6}/) {
        awaitValue('exact fund candidate') { edt {
            def list = child(pane, 'leek.suggestions')
            def index = (0..<list.model.size).find { list.model.getElementAt(it).symbol.code == code }
            if (index == null) return false
            list.selectedIndex = index
            return true
        }}
    }
    edt { child(pane, 'leek.add').doClick(0) }
}
def lookup(pane, String query, String candidate) {
    edt { child(pane, 'leek.code').text = query }
    return awaitValue(query + ' native suggestion') { edt {
        def list = child(pane, 'leek.suggestions')
        if (!list.showing) return null
        def codes = (0..<list.model.size).collect { list.model.getElementAt(it).symbol.code }
        return codes.contains(candidate) ? codes : null
    }}
}
def chooseHit(pane, String code) {
    edt {
        def list = child(pane, 'leek.suggestions')
        def index = (0..<list.model.size).find { list.model.getElementAt(it).symbol.code == code }
        assert index != null
        list.selectedIndex = index
        child(pane, 'leek.code').postActionEvent()
    }
}
def pinCode(pane, String code) {
    edt { selectCode(pane, code); child(pane, 'leek.pin').doClick(0) }
}
def rowTexts(pane) {
    def tree = child(pane, 'leek.watchlist')
    def rows = [:]
    tree.model.root.depthFirstEnumeration().each { node ->
        def object = node.userObject
        if (object?.class?.name == 'leek.Symbol') {
            def cell = tree.cellRenderer.getTreeCellRendererComponent(tree, node, false, false, true, 0, false)
            rows[object.code] = cell instanceof JLabel ? cell.text : cell.components.findAll { it instanceof JLabel }*.text.join('  ')
        }
    }
    return rows
}

def hoursDialog(pane, String code) {
    edt {
        selectCode(pane, code)
        def button = child(pane, 'leek.hours')
        assert button?.enabled
        SwingUtilities.invokeLater { button.doClick(0) }
    }
    awaitValue('hours editor for ' + code) { edt {
        def dialog = java.awt.Window.windows.find { it instanceof JDialog && it.showing && it.title == '自动刷新时段 · ' + code }
        if (!dialog) return null
        def field = UIUtil.uiTraverser(dialog).filter(JTextField).find { it.showing && it.editable }
        def ok = dialog.rootPane.defaultButton
        field && ok ? [field: field, ok: ok, pane: dialog.rootPane] : null
    }}
}
def grab(service) {
    def view = service.view()
    return view.rows.collectEntries { row ->
        def q = row.quote
        def d = q?.day
        [(row.symbol.code): [state: row.state.toString(), received: row.received?.toString(), name: q?.name,
                            price: q?.price?.toPlainString(), percent: q?.percent?.toPlainString(),
                            time: q?.sourceTime, basis: q?.basis?.toPlainString(), basisLabel: q?.basisLabel,
                            rawTrade: q?.rawTrade?.toPlainString(), bid: q?.bidFallback,
                            day: d ? [open:d.open?.toPlainString(), high:d.high?.toPlainString(), low:d.low?.toPlainString(),
                                      volume:d.volume?.toPlainString(), turnover:d.turnover?.toPlainString()] : null]]
    }
}
def paintComponent(component, File image) {
    assert component.width > 0 && component.height > 0
    def pixels = new BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_RGB)
    def graphics = pixels.createGraphics()
    try { component.printAll(graphics) } finally { graphics.dispose() }
    ImageIO.write(pixels, 'png', image)
    return pixels
}

def sectionRows(tree) {
    tree.model.root.children().toList().collectEntries { group ->
        [(group.userObject.toString()): group.children().collect { it.userObject.code }]
    }
}

def watchPath(tree, String key) {
    def node = tree.model.root.depthFirstEnumeration().find {
        def item = it.userObject
        item instanceof Enum ? item.name() == key : item?.code == key
    }
    assert node != null : key
    new TreePath(node.path)
}

// Keep the OS pointer and clipboard out of the test. Rider handles the mouse gesture and Swing
// exports its real selection, then the installed tree's DropTarget receives that same transfer.
class LocalTreePickup extends TransferHandler {
    final TransferHandler original
    final Clipboard clipboard = new Clipboard('LeekBox acceptance')
    LocalTreePickup(TransferHandler original) { this.original = original }
    int getSourceActions(JComponent c) { original.getSourceActions(c) }
    void exportAsDrag(JComponent c, java.awt.event.InputEvent e, int action) {
        original.exportToClipboard(c, clipboard, action)
    }
}
class LocalTreeDrag extends DropTargetDragEvent {
    final Transferable data
    boolean allowed
    LocalTreeDrag(JTree tree, Point point, Transferable data) {
        super(tree.dropTarget.dropTargetContext, point, DnDConstants.ACTION_MOVE, DnDConstants.ACTION_MOVE)
        this.data = data
    }
    Transferable getTransferable() { data }
    DataFlavor[] getCurrentDataFlavors() { data.transferDataFlavors }
    boolean isDataFlavorSupported(DataFlavor flavor) { data.isDataFlavorSupported(flavor) }
    void acceptDrag(int action) { allowed = action == DnDConstants.ACTION_MOVE }
    void rejectDrag() { allowed = false }
}
class LocalTreeDrop extends DropTargetDropEvent {
    final Transferable data
    boolean moved
    LocalTreeDrop(JTree tree, Point point, Transferable data) {
        super(tree.dropTarget.dropTargetContext, point, DnDConstants.ACTION_MOVE, DnDConstants.ACTION_MOVE, true)
        this.data = data
    }
    Transferable getTransferable() { data }
    DataFlavor[] getCurrentDataFlavors() { data.transferDataFlavors }
    boolean isDataFlavorSupported(DataFlavor flavor) { data.isDataFlavorSupported(flavor) }
    void acceptDrop(int action) { assert action == DnDConstants.ACTION_MOVE }
    void rejectDrop() { moved = false }
    void dropComplete(boolean success) { moved = success }
}
def dragRow(tree, String source, String target, boolean after) {
    def path = watchPath(tree, source)
    tree.scrollPathToVisible(path)
    def start = tree.getPathBounds(path)
    int x = start.x + 5, y = start.centerY
    def original = tree.transferHandler
    def pickup = new LocalTreePickup(original)
    tree.transferHandler = pickup
    try {
        tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
            MouseEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1))
        tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(),
            MouseEvent.BUTTON1_DOWN_MASK, x + DragSource.dragThreshold + 4, y, 0, false, MouseEvent.NOBUTTON))
    } finally {
        tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
            0, x, y, 1, false, MouseEvent.BUTTON1))
        tree.transferHandler = original
    }
    def data = pickup.clipboard.getContents(tree)
    assert data && tree.selectionPath == path : "Rider did not start the drag for ${source}"
    def destination = watchPath(tree, target)
    tree.scrollPathToVisible(destination)
    def bounds = tree.getPathBounds(destination)
    def point = new Point((int)bounds.x + 5, (int)(after ? bounds.maxY - 2 : bounds.y + 2))
    def hover = new LocalTreeDrag(tree, point, data)
    tree.dropTarget.dragEnter(hover)
    def drop = new LocalTreeDrop(tree, point, data)
    tree.dropTarget.drop(drop)
    assert hover.allowed == drop.moved
    return drop.moved
}

def quoteIndent(pane, String code) {
    def tree = child(pane, 'leek.watchlist')
    selectCode(pane, code)
    def path = tree.selectionPath
    tree.scrollPathToVisible(path)
    def bounds = tree.getPathBounds(path)
    def cell = tree.cellRenderer.getTreeCellRendererComponent(tree, path.lastPathComponent, true, false, true, tree.getRowForPath(path), true)
    cell.size = cell.preferredSize
    cell.doLayout()
    [depth: path.pathCount - 1, headerX: tree.getPathBounds(path.parentPath).x, markerX: bounds.x + cell.components[0].x,
     rendererPadding: cell.components[0].x]
}

def watchlistAppearance(pane, File evidence) {
    def tree = child(pane, 'leek.watchlist')
    // Exercise the focused theme even when the acceptance window is in the background.
    tree.putClientProperty(RenderingUtil.ALWAYS_PAINT_SELECTION_AS_FOCUSED, true)
    def section = watchPath(tree, 'DOMESTIC')
    def results = [:]
    def capture = { String state ->
        def image = paintComponent(pane, new File(evidence, "appearance-${state}.png"))
        def origin = SwingUtilities.convertPoint(tree, 0, 0, pane)
        def rgb = { x, y -> String.format('#%06x', image.getRGB((int)(origin.x + x), (int)(origin.y + y)) & 0xffffff) }
        def last = tree.getRowBounds(tree.rowCount - 1)
        int right = tree.visibleRect.x + tree.visibleRect.width - 24
        def surface = rgb(right, last.y + last.height + 3)
        def mismatches = [:]
        def wrongEmphasis = []
        (0..<tree.rowCount).each { row ->
            def bounds = tree.getRowBounds(row)
            def fills = [rgb(bounds.x + 10, bounds.y + 2), rgb(right, bounds.y + 2)].unique()
            if (fills != [surface]) mismatches[row] = fills
            def node = tree.getPathForRow(row).lastPathComponent
            def selected = tree.isRowSelected(row)
            def cell = tree.cellRenderer.getTreeCellRendererComponent(tree, node, selected, tree.isExpanded(row), tree.model.isLeaf(node), row, selected)
            def labels = cell instanceof JLabel ? [cell] : cell.components.findAll { it instanceof JLabel }
            if (labels.any { it.text && it.font.bold } != selected) wrongEmphasis << row
        }
        results[state] = [rows: tree.rowCount, treeHasFocus: tree.focusOwner, surface: surface,
                          mismatches: mismatches, wrongEmphasis: wrongEmphasis]
    }
    TreeHoverListener.DEFAULT.mouseExited(tree)
    tree.collapsePath(section)
    tree.selectionPath = section
    capture('group')
    tree.expandPath(section)
    tree.selectionPath = watchPath(tree, 'MAINLAND')
    capture('venue')
    selectCode(pane, 'nf_AO2702')
    def bounds = tree.getPathBounds(tree.selectionPath)
    def event = new java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_MOVED,
        System.currentTimeMillis(), 0, (int)(bounds.x + 8), (int)(bounds.y + bounds.height / 2), 0, false)
    assert tree.getToolTipText(event) && child(pane, 'leek.pin').enabled && child(pane, 'leek.remove').enabled
    def fundPath = watchPath(tree, 'FUND')
    def fund = tree.getPathBounds(fundPath)
    TreeHoverListener.DEFAULT.mouseMoved(tree, (int)(fund.x + 8), (int)(fund.y + fund.height / 2))
    assert TreeHoverListener.getHoveredRow(tree) == tree.getRowForPath(fundPath)
    capture('quote-and-hover')
    TreeHoverListener.DEFAULT.mouseExited(tree)
    tree.clearSelection()
    tree.putClientProperty(RenderingUtil.ALWAYS_PAINT_SELECTION_AS_FOCUSED, null)
    capture('idle')
    return results
}

def app = ApplicationManager.application
def repo = Path.of(System.getProperty('leek.qa.repo')).toAbsolutePath().normalize()
def profile = Path.of(PathManager.configPath).toAbsolutePath().parent
assert profile == Path.of(System.getProperty('leek.qa.profile')).toAbsolutePath().normalize()
assert !app.headlessEnvironment
def phase = profile.resolve('phase.txt').toFile().getText('UTF-8').trim()
assert phase in ['live', 'restart', 'monthly', 'close', 'fuzzy', 'index-search', 'appearance', 'watch-order', 'order-restart', 'update-source', 'refresh-hours', 'hours-restart', 'hours-legacy', 'hours-discovery', 'hours-cache', 'fund-legacy', 'fund-quotes']
def evidence = repo.resolve('rider-plugin/build/acceptance').toFile()
def json = new GsonBuilder().setPrettyPrinting().serializeNulls().create()

Thread.startDaemon('LeekBox real Rider acceptance') {
    def note = [phase: phase, started: Instant.now().toString(), pid: ProcessHandle.current().pid(),
                build: ApplicationInfo.instance.build.asString(), checks: [:]]
    def target = new File(evidence, phase + '.json')
    def save = { target.setText(json.toJson(note), 'UTF-8') }
    def check = { String key, Object data -> note.checks[key] = data; save() }
    def trashSocket = new ServerSocket(0, 1, InetAddress.loopbackAddress)
    def meter = new QuoteTraffic(trashSocket.localPort, new File(evidence, 'wire-' + phase))
    trashSocket.close()
    def proxyToken
    try {
        def descriptor = PluginManagerCore.getPlugin(PluginId.getId('cn.leekbox.quotes'))
        assert descriptor?.enabled && descriptor.version == System.getProperty('leek.qa.version')
        assert descriptor.pluginPath.toAbsolutePath().startsWith(profile.resolve('plugins'))
        note.plugin = [id: descriptor.pluginId.idString, version: descriptor.version, path: descriptor.pluginPath.toString()]
        save()
        def project = awaitValue('original open repository') {
            ProjectManager.instance.openProjects.find { Path.of(it.basePath) == repo }
        }
        if (phase == 'update-source') {
            def feed = repo.resolve('rider-plugin/dist/updatePlugins.xml')
            def host = feed.toUri().toString()
            def sources = app.getService(PluginUpdateSourceService)
            def selectedSource = sources.createCustomRepositoryPluginUpdateSourceId(host)
            // Model the user's repository choice in this isolated Rider profile.
            edt {
                def settings = UpdateSettings.getInstance()
                if (!settings.pluginHosts.contains(host)) {
                    settings.storedPluginHosts.add(host)
                    CustomPluginRepositoryService.getInstance().clearCache()
                }
                if (sources.isMissingPluginUpdateSource(descriptor)) sources.setPluginUpdateSourceId(descriptor.pluginId, selectedSource)
            }
            def missing = sources.isMissingPluginUpdateSource(descriptor)
            def warning = IdeBundle.message('plugins.configurable.plugin.list.unknown.update.source.warning')
            app.invokeLater { PluginManagerConfigurable.showPluginConfigurable(project, descriptor) }
            def card = awaitValue('installed LeekBox settings card') { edt {
                for (window in java.awt.Window.windows.findAll { it.showing }) {
                    def row = UIUtil.uiTraverser(window).filter(ListPluginComponent).find {
                        it.showing && !it.marketplace && it.pluginModel.pluginId == descriptor.pluginId
                    }
                    if (row && row.width > 0 && row.height > 0) {
                        def visible = UIUtil.uiTraverser(row).filter(JTextArea).any { it.showing && it.text == warning }
                        if (visible == missing) {
                            paintComponent(row, new File(evidence, 'update-source-card.png'))
                            return [version: row.pluginModel.version, warningVisible: visible, missingSource: missing]
                        }
                    }
                }
                return null
            }}
            check('card', card)
            assert !card.warningVisible && !missing : card
            assert RepositoryHelper.customPluginRepositoryHosts.contains(host)
            assert sources.getPersistedPluginUpdateSourceId(descriptor.pluginId) == selectedSource
            def published = RepositoryHelper.loadPlugins(host, new EmptyProgressIndicator()).find { it.pluginId == descriptor.pluginId }
            assert published && PluginDownloader.compareVersionsSkipBrokenAndIncompatible(published.version, descriptor) >= 0
            def download = PluginDownloader.createDownloader(published, host, null).tryDownloadPlugin(new EmptyProgressIndicator())
            def archive = feed.resolveSibling("leekbox-rider-${published.version}.zip")
            assert java.nio.file.Files.mismatch(download, archive) == -1L
            check('update', [source: host, version: published.version, matchesDistribution: true])
            note.ok = true
            return
        }
        if (!(phase in ['appearance', 'watch-order', 'order-restart'])) proxyToken = JdkProxyCustomizer.getInstance().customizeProxySelector(meter)
        def service = app.getService(descriptor.pluginClassLoader.loadClass('leek.LeekService'))
        def pane = panel(project)
        awaitValue('installed native status widget') { edt { widget(project)?.presentation } }
        def codes = ['sh600000','hk00700','000001','161725','nf_IF0','nf_RB0','nf_RB2701','hf_OIL','hf_CHA50CFD']
        if (phase == 'fund-legacy') {
            if (!grab(service).containsKey('588200')) addCode(pane, '588200')
            if (service.view().pin != '588200') pinCode(pane, '588200')
            edt { child(pane, 'leek.refresh').doClick(0) }
            def oldRow = awaitValue('published NAV on the old ETF watch') { edt {
                def row = rowTexts(pane)['588200']
                def quote = grab(service)['588200']
                if (quote?.state == 'CURRENT' && quote.basisLabel == '披露日涨跌幅' && row?.contains('净值 ' + quote.price) &&
                    widget(project).presentation.text.startsWith('588200')) return row
                return null
            }}
            check('originalFundRow', oldRow)
        } else if (phase == 'fund-quotes') {
            check('existingWatch', [codes: grab(service).keySet().toList(), pinned: service.view().pin])
            def funds = ['588200','159915','000001','007300','000198']
            def labels = ['现价','现价','估值','估值','每万份收益']
            def timeLabels = ['报价时间','报价时间','估值时间','估值时间','收益日期']
            assert lookup(pane, 'sh588200', '588200') == ['588200']
            for (code in funds) {
                if (!grab(service).containsKey(code)) addCode(pane, code)
            }
            def ready = awaitValue('five live fund quotes', 90) {
                def values = grab(service)
                funds.every { values[it]?.state == 'CURRENT' && values[it].price && values[it].time } ? values : null
            }
            edt { child(pane, 'leek.refresh').doClick(0) }
            def initial = awaitValue('fund quotes observed by the wire recorder', 90) {
                def values = grab(service)
                funds.every { values[it]?.state == 'CURRENT' && values[it].received > ready[it].received } ? values : null
            }
            check('fundQuotes', initial)
            def shown = [:]
            funds.eachWithIndex { code, index ->
                if (service.view().pin != code) pinCode(pane, code)
                else edt { selectCode(pane, code) }
                shown[code] = awaitValue(code + ' row, detail and pinned quote') { edt {
                    def row = rowTexts(pane)[code]
                    def status = widget(project).presentation
                    def details = child(pane, 'leek.sourceTime').text
                    def price = labels[index] + ' ' + grab(service)[code].price
                    def group = child(pane, 'leek.watchlist').selectionPath?.parentPath?.lastPathComponent?.userObject
                    if (group?.toString() == 'FUND' && row?.contains(price) && status.text.startsWith(code) &&
                        status.text.contains(price) && status.tooltipText.contains(timeLabels[index]) && details.contains(timeLabels[index]))
                        return [row: row, status: status.text, detail: details]
                    return null
                }}
            }
            check('fundSurfaces', shown)
            assert shown['159915'].row.contains('ETF') && shown['007300'].row.contains('联接')
            if (service.view().pin != '588200') pinCode(pane, '588200')
            edt { selectCode(pane, '588200'); paintComponent(pane, new File(evidence, 'fund-quotes.png')) }
            def subsequent = awaitValue('automatic ETF tick and fund estimate poll', 110) {
                def values = grab(service)
                if (values['588200'].time > initial['588200'].time &&
                    funds.every { values[it].state == 'CURRENT' && values[it].received > initial[it].received }) return values
                return null
            }
            check('fundRefresh', subsequent)
        } else if (phase == 'hours-legacy') {
            if (!grab(service).containsKey('nf_LC2705')) addCode(pane, 'nf_LC2705')
            awaitValue('old lithium quote') { grab(service)['nf_LC2705']?.state == 'CURRENT' }
            def text = edt { rowTexts(pane)['nf_LC2705'] }
            assert text.contains('仅手动刷新')
            check('baseline', text)
        } else if (phase in ['hours-discovery', 'hours-cache']) {
            def day = '09:00-10:15, 10:30-11:30, 13:30-15:00'
            def expected = [nf_LC2705:day, nf_LC0:day, nf_AG2612:day + ', 21:00-02:30',
                nf_PR2705:day + ', 21:00-23:00', nf_T2703:'09:30-11:30, 13:00-15:15']
            def snapshot = { service.state.marketSchedules.collectEntries { [(it.product): [hours: it.hours, fetched: it.fetchedAt]] } }
            assert grab(service).containsKey('nf_LC2705') : 'A previously saved lithium contract is required'
            if (phase == 'hours-discovery') {
                awaitValue('old watch automatically resolves lithium', 90) { snapshot()['LC']?.hours == day }
                def lithiumFetched = snapshot()['LC'].fetched
                expected.keySet().findAll { it != 'nf_LC2705' }.each { code ->
                    if (!grab(service).containsKey(code)) addCode(pane, code)
                }
                awaitValue('online sessions for all added products', 120) {
                    def rows = service.view().rows.collectEntries { [(it.symbol.code):it.marketHours?.text] }
                    expected.every { code, hours -> rows[code] == hours }
                }
                assert snapshot()['LC'].fetched == lithiumFetched
                for (key in ['113_ag_info','115_pr_info','220_t_info']) {
                    assert meter.requests.count { it.url.endsWith('msgid=' + key) } == 1 : key
                }
            } else {
                def prior = json.fromJson(new File(evidence, 'hours-discovery.json').getText('UTF-8'), Map)
                assert prior.ok && snapshot() == prior.checks.cache
                assert meter.requests.every { !it.url.contains('futsse-static.eastmoney.com') }
            }
            def tips = edt {
                expected.collectEntries { code, hours ->
                    selectCode(pane, code)
                    def tip = child(pane, 'leek.sourceTime').toolTipText
                    assert tip.contains('东方财富期货') && tip.contains(hours) : tip
                    assert !rowTexts(pane)[code].contains('仅手动刷新')
                    [(code): hours]
                }
            }
            check('onlineWindows', tips)
            check('cache', snapshot())
            if (phase == 'hours-discovery') {
                def calendar = descriptor.pluginClassLoader.loadClass('leek.MarketSessions').getField('INSTANCE').get(null)
                def rows = service.view().rows.collectEntries { [(it.symbol.code):it] }
                def cadence = { code, local -> calendar.interval(rows[code], java.time.OffsetDateTime.parse(local + '+08:00').toInstant())?.seconds }
                def observed = [lithiumOpen:cadence('nf_LC2705','2026-11-26T09:00:00'),
                    lithiumBreak:cadence('nf_LC2705','2026-11-26T10:15:00'), lithiumNight:cadence('nf_LC2705','2026-11-26T21:00:00'),
                    silverAfterMidnight:cadence('nf_AG2612','2026-11-28T01:00:00'), bottleClose:cadence('nf_PR2705','2026-11-27T23:00:00'),
                    treasuryAfternoon:cadence('nf_T2703','2026-11-27T15:10:00')]
                assert observed == [lithiumOpen:25L, lithiumBreak:null, lithiumNight:null, silverAfterMidnight:25L, bottleClose:null, treasuryAfternoon:25L]
                check('clockReplaySeconds', observed)
                edt { selectCode(pane, 'nf_LC2705'); paintComponent(pane, new File(evidence, 'hours-discovery.png')) }
            }
        } else if (phase in ['refresh-hours', 'hours-restart']) {
            def glass = 'nf_FG2705', apple = 'nf_AP2705'
            def saved = '00:00-12:00, 12:00-24:00'
            if (phase == 'refresh-hours') {
                [glass, apple].each { code ->
                    if (grab(service).containsKey(code)) service.remove(code)
                    addCode(pane, code)
                }
                awaitValue('both source quotes') { [glass, apple].every { grab(service)[it]?.state == 'CURRENT' } }
                def initial = edt { rowTexts(pane) }
                assert initial[glass].contains(glass) && !initial[glass].contains('仅手动刷新')
                assert initial[apple].contains('仅手动刷新')
                check('initialRows', initial)
                def editor = hoursDialog(pane, apple)
                edt { editor.field.text = '09:00-25:00' }
                awaitValue('invalid hours disable save') { edt { !editor.ok.enabled } }
                edt { editor.field.text = '00:00-12:00，12:00-24:00' }
                awaitValue('valid hours enable save') { edt { editor.ok.enabled } }
                edt { paintComponent(editor.pane, new File(evidence, 'refresh-hours-editor.png')); editor.ok.doClick(0) }
                awaitValue('custom hours on row') { edt { rowTexts(pane)[apple]?.contains('自定义时段') } }
                def baseline = grab(service)[apple].received
                def first = awaitValue('first automatic quote') { def q = grab(service)[apple]; q.state == 'CURRENT' && q.received != baseline ? q : null }
                def second = awaitValue('second automatic quote') { def q = grab(service)[apple]; q.state == 'CURRENT' && q.received != first.received ? q : null }
                def gap = java.time.Duration.between(Instant.parse(first.received), Instant.parse(second.received)).toMillis()
                assert gap >= 25000
                check('automatic', [code: apple, first: first.received, second: second.received, gapMillis: gap])
                edt { paintComponent(pane, new File(evidence, 'refresh-hours-sidebar.png')) }
            } else {
                def rows = edt { rowTexts(pane) }
                assert rows[apple]?.contains('自定义时段') && rows[glass] && !rows[glass].contains('仅手动刷新')
                def editor = hoursDialog(pane, apple)
                def persisted = edt { editor.field.text }
                assert persisted == saved
                edt { editor.field.text = '' }
                awaitValue('blank restores default') { edt { editor.ok.enabled } }
                edt { editor.ok.doClick(0) }
                def cleared = awaitValue('row restored to default') { edt { def text = rowTexts(pane)[apple]; text?.contains('仅手动刷新') ? text : null } }
                check('restoredAndCleared', [code: apple, savedHours: persisted, clearedRow: cleared])
            }
        } else if (phase in ['watch-order', 'order-restart']) {
            def tree = child(pane, 'leek.watchlist')
            def finalOrder = [DOMESTIC:['nf_FG2705','nf_AO2702'], MAINLAND:['sh000001','sz002384'],
                              HONG_KONG:['hk00700'], OVERSEAS:[], FUND:[]]
            if (phase == 'watch-order') {
                ['sz002384','sh000001','hk00700','nf_AO2702','nf_FG2705'].each { code ->
                    if (!grab(service).containsKey(code)) addCode(pane, code)
                }
                def geometry = edt {
                    def data = ['sh000001','hk00700','nf_AO2702'].collectEntries { [(it): quoteIndent(pane, it)] }
                    paintComponent(pane, new File(evidence, 'watch-order-layout.png'))
                    [headers: tree.model.root.children().collect { it.userObject.toString() }, rows: data, dragEnabled: tree.dragEnabled]
                }
                check('layout', geometry)
                assert geometry.headers == ['MAINLAND','HONG_KONG','FUND','DOMESTIC','OVERSEAS'] : geometry
                assert geometry.rows.values().every { it.depth == 2 && it.rendererPadding == 0 &&
                    it.markerX <= it.headerX + com.intellij.util.ui.JBUI.scale(6) } : geometry
                assert geometry.dragEnabled
                if (service.view().pin != 'sh000001') pinCode(pane, 'sh000001')
                assert edt { dragRow(tree, 'sh000001', 'sz002384', false) }
                assert edt { dragRow(tree, 'nf_AO2702', 'nf_FG2705', true) }
                assert !edt { dragRow(tree, 'sz002384', 'hk00700', true) }
                edt { tree.collapsePath(watchPath(tree, 'HONG_KONG')) }
                assert edt { dragRow(tree, 'DOMESTIC', 'MAINLAND', false) }
                assert edt { dragRow(tree, 'FUND', 'OVERSEAS', true) }
                assert edt {
                    tree.isExpanded(watchPath(tree, 'DOMESTIC')) && !tree.isExpanded(watchPath(tree, 'HONG_KONG')) &&
                        tree.selectionPath == watchPath(tree, 'FUND')
                }
                def before = grab(service)['sh000001'].received
                edt { child(pane, 'leek.refresh').doClick(0) }
                awaitValue('quote refreshed after reorder') { def row = grab(service)['sh000001']; row.received && row.received != before }
            }
            def actual = edt { sectionRows(tree) }
            check('order', [groups: actual.keySet().toList(), items: actual, pinned: service.view().pin])
            assert actual.keySet().toList() == finalOrder.keySet().toList() && actual == finalOrder
            assert service.view().pin == 'sh000001'
            if (phase == 'watch-order') edt {
                selectCode(pane, 'sh000001')
                paintComponent(pane, new File(evidence, 'watch-order.png'))
            }
        } else if (phase == 'appearance') {
            ['sh600000','nf_AO2702','nf_FG2705'].each { code ->
                if (!grab(service).containsKey(code)) addCode(pane, code)
            }
            awaitValue('visible watchlist') { edt { child(pane, 'leek.watchlist').visibleRect.width > 100 } }
            edt { child(pane, 'leek.watchlist').requestFocusInWindow() }
            def appearance = edt { watchlistAppearance(pane, evidence) }
            check('rows', appearance)
            assert appearance.values().every { it.mismatches.isEmpty() && it.wrongEmphasis.isEmpty() } : appearance
        } else if (phase == 'index-search') {
            def exact = lookup(pane, 'sh000001', 'sh000001')
            assert exact == ['sh000001'] : exact
            check('sh000001', exact)
            chooseHit(pane, 'sh000001')
            def quote = awaitValue('Shanghai index quote') {
                def row = grab(service)['sh000001']
                row?.state == 'CURRENT' && row.price && row.percent ? row : null
            }
            assert quote.name == '上证指数' && new BigDecimal(quote.price) > 0
            check('quote', quote)
            def text = awaitValue('visible index row in A shares') { edt {
                def row = rowTexts(pane)['sh000001']
                if (!row?.contains('上证指数')) return null
                selectCode(pane, 'sh000001')
                def tree = child(pane, 'leek.watchlist')
                assert tree.selectionPath.parentPath.lastPathComponent.userObject.toString() == 'MAINLAND'
                tree.scrollPathToVisible(tree.selectionPath)
                return row
            }}
            check('watchlistRow', text)
            check('深证成指', lookup(pane, '深证成指', 'sz399001'))
            check('sh000300', lookup(pane, 'sh000300', 'sh000300'))
            def numeric = lookup(pane, '000001', 'sz000001')
            assert numeric.containsAll(['sh000001', 'sz000001', '000001']) : numeric
            check('000001', numeric)
            edt { paintComponent(pane, new File(evidence, 'index-search.png')) }
        } else if (phase == 'fuzzy') {
            // A previous failed UI assertion may have stopped before removing these three fixtures.
            def extras = grab(service).keySet() - codes
            assert extras.every { it in ['nf_AO0','nf_AO2702','sz000001'] }
            extras.each { code -> edt { selectCode(pane, code); child(pane, 'leek.remove').doClick(0) } }
            check('upgraded', [symbols: grab(service).keySet(), pinned: service.view().pin, previousVersion: '1.0.0'])
            assert grab(service).keySet().toList() == codes && service.view().pin == 'nf_RB2701'
            def suggestions = child(pane, 'leek.suggestions')
            def matches = lookup(pane, 'AO','nf_AO2702')
            assert matches[0] == 'nf_AO0' && matches.contains('nf_AO2701')
            edt { paintComponent(pane, new File(evidence, 'search-AO.png')); child(pane, 'leek.add').doClick(0) }
            assert grab(service).size() == 9 && edt { suggestions.selectedIndex >= 0 }
            chooseHit(pane, 'nf_AO2702')
            def chinese = lookup(pane, '氧化铝','nf_AO0')
            chooseHit(pane, 'nf_AO0')
            awaitValue('two independent oxide quotes') { def s = grab(service); s['nf_AO2702']?.price && s['nf_AO0']?.price }
            def tree = child(pane, 'leek.watchlist')
            def details = edt {
                selectCode(pane, 'nf_AO2702')
                tree.scrollPathToVisible(tree.selectionPath)
                def rect = tree.getPathBounds(tree.selectionPath)
                def hover = new java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_MOVED,
                    System.currentTimeMillis(), 0, (int)(rect.x + 6), (int)(rect.y + rect.height / 2), 0, false)
                [text: rowTexts(pane)['nf_AO2702'], height: tree.rowHeight, tooltip: tree.getToolTipText(hover),
                 source: child(pane, 'leek.sourceTime').text, stockGroups: tree.model.root.depthFirstEnumeration().count {
                     it.userObject?.toString() in ['MAINLAND','HONG_KONG']
                 }]
            }
            assert !details.text.contains('<br>') && details.stockGroups == 2
            assert ['最高','最低','今开','昨结算','成交量','成交额','nf_AO2702'].every { details.tooltip.contains(it) }
            assert details.source.contains('nf_AO2702')
            edt { paintComponent(pane, new File(evidence, 'compact.png')) }
            check('oxide', [byCode: matches, byName: chinese, details: details, rows: grab(service).subMap(['nf_AO0','nf_AO2702'])])
            def numeric = lookup(pane, '000001','sz000001')
            assert numeric.contains('000001')
            edt { child(pane, 'leek.add').doClick(0) }
            assert grab(service).size() == 11
            chooseHit(pane, 'sz000001')
            assert grab(service).containsKey('sz000001')
            check('numericChoice', numeric)
            ['sz000001','nf_AO0','nf_AO2702'].each { code ->
                edt { selectCode(pane, code); child(pane, 'leek.remove').doClick(0) }
            }
            assert grab(service).keySet().toList() == codes
        } else if (phase == 'monthly') {
            assert service.view().pin == 'nf_RB2701'
            awaitValue('monthly widget in previously saved profile') { edt { widget(project).presentation.text.startsWith('nf_RB2701 ') } }
            edt { tool(project).hide() }
            def baseline = edt { [model: grab(service)['nf_RB2701'], text: widget(project).presentation.text] }
            check('hiddenBefore', baseline)
            def updates = []
            def latest = baseline.model.received
            awaitValue('live RB2701 update with its sidebar hidden', 600) {
                edt {
                    def value = grab(service)['nf_RB2701']
                    def text = widget(project).presentation.text
                    assert !tool(project).visible
                    if (value.state == 'CURRENT' && value.received != latest && text.startsWith('nf_RB2701 ') && text.contains(new BigDecimal(value.price).stripTrailingZeros().toPlainString())) {
                        latest = value.received
                        updates << [model: value, text: text, sidebarVisible: false]
                    }
                    updates.any { it.model.time != baseline.model.time &&
                        (it.model.price != baseline.model.price || it.model.percent != baseline.model.percent) &&
                        it.text != baseline.text }
                }
            }
            check('hiddenUpdates', updates)
        } else if (phase == 'restart') {
            assert grab(service).keySet().toList() == codes
            assert service.view().pin == 'nf_RB2701'
            def status = awaitValue('restored monthly contract') { edt {
                def text = widget(project).presentation.text
                text.startsWith('nf_RB2701 ') ? text : null
            }}
            assert grab(service)['nf_RB2701'].time
            check('restored', [codes: grab(service).keySet(), pinned: service.view().pin, widget: status, rows: grab(service)])
        } else {
            if (phase == 'live') {
                codes.each { addCode(pane, it) }
                // The second launch of a failed acceptance run may already contain these codes.
                def refreshStart = Instant.now()
                edt { child(pane, 'leek.refresh').doClick(0) }
                awaitValue('fresh nine-symbol comparison') {
                    def values = grab(service)
                    values.keySet().toList() == codes && values.values().every {
                        it.state == 'CURRENT' && Instant.parse(it.received) >= refreshStart
                    }
                }
            } else if (phase == 'close') {
                def initial = Instant.now()
                edt { child(pane, 'leek.refresh').doClick(0) }
                awaitValue('fresh close-of-day reference for the stock') {
                    def value = grab(service)['sh600000']
                    value.state == 'CURRENT' && Instant.parse(value.received) >= initial
                }
            }
            awaitValue('nine production HTTP quotes', 100) {
                def values = grab(service)
                values.size() == codes.size() && values.values().every { it.price && it.time }
            }
            if (phase == 'live') {
                check('nine', [model: grab(service), rows: edt { rowTexts(pane) },
                               groups: edt { child(pane, 'leek.watchlist').model.root.children().collect { it.userObject.toString() } }])
                assert note.checks.nine.rows.keySet().toSet() == codes.toSet()
                assert note.checks.nine.groups in [['STOCK','FUND','DOMESTIC','OVERSEAS'], ['MAINLAND','HONG_KONG','FUND','DOMESTIC','OVERSEAS']]
                addCode(pane, 'nf_RB9999')
                awaitValue('explicitly empty contract') { grab(service)['nf_RB9999']?.state == 'EMPTY' }
                check('empty', edt { rowTexts(pane)['nf_RB9999'] })
                assert note.checks.empty.contains('—')
                edt { selectCode(pane, 'nf_RB9999'); child(pane, 'leek.remove').doClick(0) }
                assert !grab(service).containsKey('nf_RB9999')

                def trial = awaitValue('live overseas bid fallback', 240) {
                    grab(service).find { k, v -> k.startsWith('hf_') && v.bid }?.key
                }
                if (service.view().pin != trial) pinCode(pane, trial)
                def bid = awaitValue('both buy-one labels on the same tick') {
                    edt {
                        def row = rowTexts(pane)[trial]
                        def status = widget(project).presentation
                        def item = grab(service)[trial]
                        def price = new BigDecimal(item.price).stripTrailingZeros().toPlainString()
                        if (item.bid && status.text.startsWith(trial) && row.contains('买一') && status.text.contains('买一') &&
                                row.contains(price) && status.text.contains(price) && status.tooltipText.contains('源成交价'))
                            return [code: trial, quote: item, row: row, status: status.text, tooltip: status.tooltipText]
                        return null
                    }
                }
                check('bid', bid)
                if (service.view().pin != 'nf_RB0') pinCode(pane, 'nf_RB0')
                awaitValue('continuous contract pinned') { edt { widget(project).presentation.text.startsWith('nf_RB0 ') } }
                pinCode(pane, 'nf_RB2701')
                awaitValue('monthly contract pinned') { edt { widget(project).presentation.text.startsWith('nf_RB2701 ') } }
                check('monthly', edt { widget(project).presentation.text })
                edt { paintComponent(pane, new File(evidence, 'sidebar.png')); tool(project).hide() }

                def secondPath = repo.resolve('template-packages/leek-center')
                def second = ProjectManager.instance.openProjects.find { Path.of(it.basePath) == secondPath }
                if (second == null) second = ProjectUtil.openOrImport(secondPath, null, true)
                assert second && second != project
                def other = panel(second)
                awaitValue('second frame and widget') { edt {
                    def frame1 = WindowManager.instance.getFrame(project)
                    def frame2 = WindowManager.instance.getFrame(second)
                    frame1 && frame2 && frame1 != frame2 && widget(second)?.presentation?.text?.startsWith('nf_RB2701 ')
                }}
                assert edt { rowTexts(other).keySet().toSet() == codes.toSet() }
                // Move the pin in the second window and observe the first, whose sidebar is hidden.
                pinCode(other, 'hf_OIL')
                awaitValue('pin synchronized across frames') { edt { widget(project).presentation.text == widget(second).presentation.text &&
                    widget(project).presentation.text.startsWith('hf_OIL ') && !tool(project).visible } }
                check('windows', [projects: ProjectManager.instance.openProjects*.basePath, status: edt { widget(project).presentation.text }])
                pane = other
            }

            def tracked = ['hf_OIL', 'hf_CHA50CFD']
            if (phase == 'close') {
                def sh = Instant.now().atZone(ZoneId.of('Asia/Shanghai'))
                assert sh.toLocalTime() >= java.time.LocalTime.of(15, 30) : 'This phase requires a real mainland post-close observation'
                def first = grab(service)
                awaitValue('new open-market data in the restored IDE', 100) {
                    def current = grab(service)
                    tracked.every { current[it].state == 'CURRENT' && current[it].received != first[it].received }
                }
            }
            def start = Instant.now()
            def observations = [baseline: grab(service)]
            def rounds = tracked.collectEntries { [(it): []] }
            def latest = tracked.collectEntries { [(it): observations.baseline[it].received] }
            awaitValue('two automatic cycles for both overseas symbols', 300) {
                def values = grab(service)
                for (code in tracked) {
                    if (values[code].received != latest[code]) {
                        latest[code] = values[code].received
                        rounds[code] << [quote: values[code], widget: edt { widget(project).presentation.text }]
                    }
                }
                tracked.every { code ->
                    rounds[code].size() >= 2 && (phase != 'close' || rounds[code].last().quote.time != observations.baseline[code].time)
                }
            }
            def traffic = meter.requests.findAll { Instant.parse(it.at) >= start }
            for (code in tracked) {
                assert traffic.count { it.url.contains(code) } == rounds[code].size() : [code, traffic, rounds]
                assert rounds[code].every { it.quote.state == 'CURRENT' && it.quote.time }
            }
            observations.rounds = rounds
            observations.requests = traffic
            observations.shanghai = Instant.now().atZone(ZoneId.of('Asia/Shanghai')).toString()
            if (phase == 'close') {
                assert !traffic.any { it.url.contains('sh600000') }
                assert rounds.hf_OIL[-1].quote.time != observations.baseline.hf_OIL.time
                assert rounds.hf_CHA50CFD[-1].quote.time != observations.baseline.hf_CHA50CFD.time
            }
            check('cycles', observations)

            if (phase == 'live') {
                def before = grab(service)['hf_OIL']
                meter.offline.set(true)
                edt { child(pane, 'leek.refresh').doClick(0) }
                awaitValue('failed update visible with original time and price') { edt {
                    def value = grab(service)['hf_OIL']
                    value.state == 'FAILED' && widget(project).presentation.text.contains('更新失败') &&
                        rowTexts(pane)['hf_OIL'].contains(before.time.tokenize(' ').last()) &&
                        widget(project).presentation.tooltipText.contains(before.time)
                }}
                def offline = grab(service)['hf_OIL']
                assert [offline.price, offline.time, offline.received] == [before.price, before.time, before.received]
                check('offline', [before: before, after: offline, row: edt { rowTexts(pane)['hf_OIL'] }, status: edt { widget(project).presentation.text }])
                meter.offline.set(false)
                awaitValue('automatic recovery without manual refresh') { grab(service)['hf_OIL'].state == 'CURRENT' }
                check('recovered', grab(service)['hf_OIL'])
                pinCode(pane, 'nf_RB2701')
                awaitValue('monthly pin shared before restart') { edt { widget(project).presentation.text.startsWith('nf_RB2701 ') } }
                edt { child(pane, 'leek.refresh').doClick(0) }
                awaitValue('final cache populated for restart') { grab(service).values().every { it.state == 'CURRENT' } }
            }
        }
        note.ok = true
    } catch (Throwable failure) {
        def text = new StringWriter()
        failure.printStackTrace(new PrintWriter(text))
        note.failure = text.toString()
        note.ok = false
    } finally {
        proxyToken?.finish()
        meter.witnesses.shutdown()
        meter.witnesses.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)
        note.wire = meter.files
        note.requests = meter.requests
        note.ended = Instant.now().toString()
        save()
        app.invokeLater({ app.exit(true, true, false) }, com.intellij.openapi.application.ModalityState.any())
    }
}
