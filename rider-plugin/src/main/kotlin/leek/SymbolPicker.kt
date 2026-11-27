package leek

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.Future
import javax.swing.AbstractAction
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Native, keyboard-accessible suggestions. The list stays inside the tool window and keeps editor focus. */
internal class SymbolPicker(private val choose: (Symbol, String?) -> Unit) {
    val input = JBTextField().apply {
        name = "leek.code"
        emptyText.text = "搜索名称或代码，如 AO"
        toolTipText = "按代码片段或中文名称搜索，股票和基金还可试拼音缩写。完整行情代码可直接添加。"
        accessibleContext.accessibleName = "搜索自选标的"
    }
    private val search = SymbolSearch()
    private val list = JBList<SymbolHit>().apply {
        name = "leek.suggestions"
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        fixedCellHeight = JBUI.scale(23)
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>, value: Any?, index: Int,
                                                      selected: Boolean, focus: Boolean): Component {
                super.getListCellRendererComponent(list, value, index, selected, focus)
                if (value is SymbolHit) text = "${value.name}   ${value.symbol.code}   ${value.symbol.watchGroup.title}"
                return this
            }
        }
        accessibleContext.accessibleName = "匹配的标的"
    }
    private val hint = JBLabel().apply { border = JBUI.Borders.empty(2, 4) }
    private val scroll = JBScrollPane(list)
    val suggestions = JPanel(BorderLayout()).apply {
        isVisible = false
        add(scroll, BorderLayout.CENTER)
        add(hint, BorderLayout.SOUTH)
    }
    private var generation = 0L
    private var running: Future<*>? = null
    private val debounce = Timer(260) { searchInBackground() }.apply { isRepeats = false }
    private var hits = emptyList<SymbolHit>()
    private var isSearching = false

    init {
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = edited()
            override fun removeUpdate(e: DocumentEvent) = edited()
            override fun changedUpdate(e: DocumentEvent) = edited()
        })
        input.addActionListener { submit() }
        bind(input, KeyEvent.VK_DOWN) { move(1) }
        bind(input, KeyEvent.VK_UP) { move(-1) }
        bind(input, KeyEvent.VK_ESCAPE) { hide() }
        bind(list, KeyEvent.VK_ENTER) { list.selectedValue?.let(::chooseHit) }
        bind(list, KeyEvent.VK_ESCAPE) { hide(); input.requestFocusInWindow() }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(e) || e.clickCount != 1) return
                val index = list.locationToIndex(e.point)
                if (index >= 0 && list.getCellBounds(index, index).contains(e.point)) chooseHit(hits[index])
            }
        })
    }

    fun submit() {
        val query = input.text.trim()
        if (query.isEmpty()) {
            input.requestFocusInWindow()
            return
        }
        val selected = list.selectedValue.takeIf { suggestions.isVisible }
        if (selected != null) {
            chooseHit(selected)
            return
        }
        val exact = Symbol.parse(query)
        if (exact != null && (exact.group != Group.FUND || query.equals(exact.etfMarketCode, ignoreCase = true))) {
            choose(exact, null)
            return
        }
        if (!suggestions.isVisible) edited()
        if (exact != null && !isSearching && hits.singleOrNull()?.symbol == exact) {
            choose(exact, hits.single().name)
            return
        }
        if (exact != null && !isSearching && hits.isEmpty()) {
            display(listOf(SymbolHit(exact, MANUAL_FUND)), false)
        }
        if (hits.isNotEmpty()) {
            move(1)
            hint.text = "按回车或点击候选添加"
            hint.isVisible = true
        }
    }

    private fun chooseHit(hit: SymbolHit) = choose(hit.symbol, hit.name.takeUnless { it == MANUAL_FUND || it == hit.symbol.code })

    private fun edited() {
        generation++
        debounce.stop()
        running?.cancel(true)
        val query = input.text.trim()
        if (query.isEmpty()) {
            hide()
            return
        }
        list.clearSelection()
        display(search.local(query), true)
        debounce.restart()
    }

    private fun searchInBackground() {
        val query = input.text.trim()
        val requested = generation
        running = AppExecutorUtil.getAppExecutorService().submit {
            val results = search.search(query)
            SwingUtilities.invokeLater {
                if (requested == generation && input.text.trim() == query) display(results, false)
            }
        }
    }

    private fun display(found: List<SymbolHit>, loading: Boolean) {
        isSearching = loading
        val selection = list.selectedValue?.symbol
        hits = found
        list.setListData(found.toTypedArray())
        list.selectedIndex = if (selection == null) -1 else found.indexOfFirst { it.symbol == selection }
        list.visibleRowCount = found.size.coerceIn(1, 6)
        scroll.isVisible = found.isNotEmpty()
        hint.text = if (loading) "正在查找…" else "没有匹配的标的"
        hint.isVisible = found.isEmpty()
        suggestions.isVisible = true
        suggestions.revalidate()
    }

    private fun move(direction: Int) {
        if (!suggestions.isVisible) edited()
        if (hits.isEmpty()) return
        val index = (list.selectedIndex + direction).coerceIn(0, hits.lastIndex)
        list.selectedIndex = index
        list.ensureIndexIsVisible(index)
    }

    fun hide() {
        generation++
        debounce.stop()
        running?.cancel(true)
        list.clearSelection()
        hits = emptyList()
        isSearching = false
        suggestions.isVisible = false
        suggestions.revalidate()
    }

    private fun bind(component: JComponent, key: Int, action: () -> Unit) {
        val id = "leek.picker.$key"
        component.inputMap.put(KeyStroke.getKeyStroke(key, 0), id)
        component.actionMap.put(id, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = action()
        })
    }
    companion object { private const val MANUAL_FUND = "按六位基金代码添加" }
}
