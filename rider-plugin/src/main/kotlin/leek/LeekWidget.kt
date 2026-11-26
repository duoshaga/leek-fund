package leek

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import kotlinx.coroutines.CoroutineScope

private const val WIDGET_ID = "LeekBoxPinned"
internal const val TOOL_WINDOW = "韭菜盒子"

class LeekWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = WIDGET_ID
    override fun getDisplayName() = "韭菜盒子 · 固定行情"
    override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget = LeekWidget(project)
}

private class LeekWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private val service = LeekService.get()
    @Volatile private var row = service.view().pinned

    override fun ID() = WIDGET_ID
    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
    override fun getText() = QuoteText.status(row)
    override fun getTooltipText() = row?.let(QuoteText::tooltip) ?: "打开韭菜盒子，选择标的并固定到状态栏"
    override fun getAlignment() = 0.5f
    override fun getClickConsumer() = Consumer<MouseEvent> {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW)?.activate(null)
    }

    override fun install(statusBar: StatusBar) {
        service.subscribe(this) {
            row = service.view().pinned
            statusBar.updateWidget(WIDGET_ID)
        }
    }
}
