package leek

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.render.RenderingUtil
import com.intellij.ui.tree.ui.Control
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.function.Supplier
import javax.swing.BoxLayout
import javax.swing.DropMode
import javax.swing.JButton
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.JToolBar
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

class LeekToolWindow : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = WatchPanel(LeekService.get())
        val manager = toolWindow.contentManager
        val content = manager.factory.createContent(panel, "", false)
        content.setDisposer(panel)
        content.setPreferredFocusableComponent(panel.input)
        manager.addContent(content)
    }
}

private class WatchPanel(private val service: LeekService) : JPanel(BorderLayout()), Disposable {
    private val picker = SymbolPicker(::addSymbol)
    val input get() = picker.input
    private val feedback = JBLabel().apply { name = "leek.feedback"; isVisible = false }
    private val detailLine = JBLabel().apply { name = "leek.sourceTime"; isVisible = false; border = JBUI.Borders.empty(3, 6) }
    private val chart = ChartPanel(service)
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root, true)
    private val groups = WatchGroup.entries.associateWith { DefaultMutableTreeNode(it) }
    private val tree = object : Tree(model) {
        private var pressedGroup: Pair<TreePath, Boolean>? = null

        private fun groupAt(event: MouseEvent): TreePath? =
            TreeUtil.getPathForLocation(this, event.x, event.y)?.takeIf {
                (it.lastPathComponent as DefaultMutableTreeNode).userObject is WatchGroup
            }

        override fun processMouseEvent(event: MouseEvent) {
            if (event.id == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(event)) {
                // The native arrow can toggle on press. Resolve the completed click from its original state.
                pressedGroup = groupAt(event)?.let { it to isExpanded(it) }
            }
            super.processMouseEvent(event)
            if (event.id == MouseEvent.MOUSE_CLICKED && SwingUtilities.isLeftMouseButton(event)) {
                val (path, wasExpanded) = pressedGroup ?: return
                pressedGroup = null
                if (groupAt(event) == path && isExpanded(path) == wasExpanded) {
                    if (wasExpanded) collapsePath(path) else expandPath(path)
                }
            }
        }

        override fun getToolTipText(event: MouseEvent): String? {
            val symbol = symbolAt(getPathForLocation(event.x, event.y)) ?: return null
            return rows[symbol.code]?.let(QuoteText::tooltip)
        }
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            (transferHandler as? WatchTreeReorder)?.paintDropLine(g)
        }
    }
    private val pin = button("固定到状态栏", "pin", AllIcons.General.Pin) {
        val code = selected()?.code ?: return@button
        service.pin(if (service.view().pin == code) null else code)
    }
    private val remove = button("删除", "remove", AllIcons.General.Remove) {
        selected()?.let { service.remove(it.code) }
    }
    private val refresh = button("刷新", "refresh", AllIcons.Actions.Refresh) { service.refresh(); chart.refresh() }
    private val hours = button("设置自动刷新时段", "hours", AllIcons.General.History) { editRefreshHours() }
    private var rows = emptyMap<String, WatchRow>()
    private var pinnedCode: String? = null
    private var selectAfterAdd: String? = null
    private var maPeriods = MovingAverages.defaults
    private var rebuildingTree = false

    init {
        name = "leek.panel"
        val add = button("添加标的", "add", AllIcons.General.Add) { picker.submit() }
        val tools = JToolBar().apply {
            isFloatable = false
            isRollover = true
            isOpaque = false
            border = JBUI.Borders.empty()
            add(add)
            add(pin)
            add(remove)
            add(refresh)
            add(hours)
        }
        val entry = JPanel(BorderLayout(JBUI.scale(3), 0)).apply {
            add(input, BorderLayout.CENTER)
            add(tools, BorderLayout.EAST)
        }
        val top = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(3, 4)
            add(entry)
            add(picker.suggestions)
            add(feedback)
        }
        add(top, BorderLayout.NORTH)
        tree.name = "leek.watchlist"
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.rowHeight = JBUI.scale(22)
        // Handle group-row clicks after release so starting a drag leaves the group as it was.
        tree.toggleClickCount = 0
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.putClientProperty(Control.Painter.KEY, Control.Painter.LEAF_WITHOUT_INDENT)
        tree.transferHandler = WatchTreeReorder(tree, service)
        tree.dropMode = DropMode.ON
        tree.dragEnabled = true
        tree.addPropertyChangeListener("dropLocation") { tree.repaint() }
        tree.putClientProperty(RenderingUtil.CUSTOM_SELECTION_BACKGROUND, Supplier { tree.background })
        RenderingUtil.setHoverPaintingDisabled(tree, true)
        tree.cellRenderer = RowRenderer()
        tree.addTreeSelectionListener { if (!rebuildingTree) updateButtons() }
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 2 || !SwingUtilities.isLeftMouseButton(event)) return
                val path = tree.getPathForLocation(event.x, event.y) ?: return
                val symbol = symbolAt(path) ?: return
                tree.selectionPath = path
                chart.select(rows[symbol.code], maPeriods)
            }
        })
        ToolTipManager.sharedInstance().registerComponent(tree)
        add(OnePixelSplitter(true, "leek.chart.split", 0.46f).apply {
            firstComponent = JBScrollPane(tree)
            secondComponent = chart
        }, BorderLayout.CENTER)
        add(detailLine, BorderLayout.SOUTH)
        render()
        service.subscribe(this, ::render)
    }

    private fun button(title: String, id: String, image: Icon, action: () -> Unit) = JButton(image).apply {
        name = "leek.$id"
        toolTipText = title
        accessibleContext.accessibleName = title
        isFocusable = true
        isBorderPainted = false
        isContentAreaFilled = false
        preferredSize = JBUI.size(25, 25)
        maximumSize = preferredSize
        margin = JBUI.emptyInsets()
        addActionListener { action() }
    }

    private fun addSymbol(symbol: Symbol, displayName: String?) {
        selectAfterAdd = symbol.code
        if (service.add(symbol, displayName)) {
            showFeedback("")
        } else {
            showFeedback("${symbol.code} 已在自选中")
        }
        input.text = ""
        picker.hide()
        render()
    }

    private fun showFeedback(text: String) {
        feedback.text = text
        feedback.isVisible = text.isNotBlank()
        revalidate()
    }

    private fun selected() = symbolAt(tree.selectionPath)
    private fun symbolAt(path: TreePath?) =
        (path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Symbol

    private fun editRefreshHours() {
        val row = rows[selected()?.code] ?: return
        val validator = object : InputValidatorEx {
            override fun getErrorText(inputString: String): String? =
                if (inputString.isBlank() || DomesticRefreshHours.parse(inputString) != null) null
                else "请输入有效且互不重叠的时段，例如 09:00-10:15, 21:00-01:00"
        }
        val text = Messages.showInputDialog(this,
            "<html>按北京时间填写，多个时段用逗号分隔。跨午夜可填 21:00-01:00。<br>" +
                "按周一至周五运行，跳过已收录的节假日。留空恢复默认。</html>",
            "自动刷新时段 · ${row.symbol.code}", null, row.refreshHours?.text.orEmpty(), validator,
            null, row.marketHours?.let { "已查询到的时段　${it.text}" } ?: "09:00-10:15, 10:30-11:30, 13:30-15:00, 21:00-23:00") ?: return
        service.setRefreshHours(row.symbol.code, text)
    }

    private fun updateButtons() {
        val code = selected()?.code
        pin.isEnabled = code != null
        remove.isEnabled = code != null
        refresh.isEnabled = rows.isNotEmpty()
        hours.isEnabled = selected()?.group == Group.DOMESTIC
        val pinned = code != null && code == pinnedCode
        pin.icon = if (pinned) AllIcons.General.PinSelected else AllIcons.General.Pin
        pin.toolTipText = if (pinned) "取消固定" else "固定到状态栏"
        pin.accessibleContext.accessibleName = pin.toolTipText
        val row = rows[code]
        if (chart.isVisible) chart.select(row, maPeriods)
        detailLine.isVisible = row != null
        if (row != null) {
            detailLine.text = row.symbol.code + "  ·  " + QuoteText.timestamp(row)
            detailLine.toolTipText = QuoteText.tooltip(row)
        }
    }

    private fun render() {
        rebuildingTree = true
        try { syncTree() } finally { rebuildingTree = false }
        updateButtons()
    }

    private fun syncTree() {
        val view = service.view()
        maPeriods = view.maPeriods
        val next = view.rows.associateBy { it.symbol.code }
        val previous = rows
        val previousPin = pinnedCode
        rows = next
        pinnedCode = view.pin
        val selection = selectAfterAdd ?: selected()?.code
        val selectedGroup = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? WatchGroup
        if (root.children().asSequence().toList() != view.groupOrder.map(groups::getValue)) {
            val expanded = groups.values.filter { it.parent != null && tree.isExpanded(TreePath(it.path)) }
            root.removeAllChildren()
            view.groupOrder.forEach { root.add(groups.getValue(it)) }
            model.nodeStructureChanged(root)
            expanded.forEach { tree.expandPath(TreePath(it.path)) }
            if (selectedGroup != null) tree.selectionPath = TreePath(groups.getValue(selectedGroup).path)
        }
        if (previous.keys.toList() != next.keys.toList()) {
            for ((group, parent) in groups) {
                val children = view.rows.filter { it.symbol.watchGroup == group }
                val path = TreePath(parent.path)
                val existing = (0 until parent.childCount).map { (parent.getChildAt(it) as DefaultMutableTreeNode).userObject }
                if (existing == children.map { it.symbol }) continue
                val expand = existing.isEmpty() || tree.isExpanded(path)
                parent.removeAllChildren()
                children.forEach { parent.add(DefaultMutableTreeNode(it.symbol, false)) }
                model.nodeStructureChanged(parent)
                if (expand) tree.expandPath(path)
            }
        }
        for (parent in groups.values) {
            for (index in 0 until parent.childCount) {
                val node = parent.getChildAt(index) as DefaultMutableTreeNode
                val code = (node.userObject as Symbol).code
                if (previous[code] != next[code] || (code == previousPin) != (code == pinnedCode)) model.nodeChanged(node)
                if (selection == code && (selectAfterAdd != null || selected()?.code != code)) {
                    val path = TreePath(node.path)
                    tree.selectionPath = path
                    tree.scrollPathToVisible(path)
                }
            }
        }
        selectAfterAdd = null
    }

    override fun dispose() {
        chart.dispose()
        picker.hide()
        ToolTipManager.sharedInstance().unregisterComponent(tree)
    }

    private inner class RowRenderer : JBLabel(), TreeCellRenderer {
        private val move = JBLabel()
        private val percent = JBLabel()
        private val price = JBLabel()
        private val title = JBLabel()
        private val tail = JBLabel()
        private val line = JPanel(HorizontalLayout(4)).apply {
            isOpaque = false
            listOf(move, percent, price, title, tail).forEach(::add)
        }
        private fun fit(label: JLabel, minWidth: Int) {
            label.preferredSize = null
            val size = label.preferredSize
            label.preferredSize = Dimension(maxOf(JBUI.scale(minWidth), size.width), size.height)
        }
        override fun getTreeCellRendererComponent(tree: JTree, value: Any, selected: Boolean, expanded: Boolean,
                                                 leaf: Boolean, row: Int, hasFocus: Boolean): Component {
            foreground = tree.foreground
            font = if (selected) tree.font.deriveFont(tree.font.style or Font.BOLD) else tree.font
            val item = (value as DefaultMutableTreeNode).userObject
            if (item is WatchGroup) {
                text = "${item.title} (${value.childCount})"
            } else if (item is Symbol) {
                val entry = rows[item.code] ?: return apply { text = item.code }
                val sign = entry.quote?.percent?.signum() ?: 0
                move.text = when { sign > 0 -> "⌃"; sign < 0 -> "⌄"; else -> "–" }
                move.foreground = when { sign > 0 -> JBColor(0xB33731, 0xF07870); sign < 0 -> JBColor(0x25764C, 0x72BC91); else -> foreground }
                fit(move, 8)
                percent.text = QuoteText.percentage(entry.quote?.percent)
                percent.foreground = foreground
                percent.font = font
                fit(percent, 44)
                price.text = QuoteText.price(entry)
                price.foreground = foreground
                price.font = font
                fit(price, 44)
                title.text = QuoteText.name(entry).orEmpty()
                title.foreground = foreground
                title.font = font
                title.icon = if (item.code == pinnedCode) AllIcons.General.Pin else null
                tail.foreground = if (selected) foreground else JBColor.GRAY
                tail.text = listOfNotNull(item.code, QuoteText.rowTime(entry),
                    QuoteText.availability(entry), QuoteText.refreshNote(entry, compact = true))
                    .joinToString("  ·  ")
                line.accessibleContext.accessibleName = listOf(percent.text, price.text, title.text, tail.text).joinToString("  ")
                return line
            } else {
                text = ""
            }
            return this
        }
    }
}
