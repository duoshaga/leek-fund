package leek

import com.intellij.util.ui.JBUI
import java.awt.Graphics
import java.awt.datatransfer.DataFlavor
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.TransferHandler
import javax.swing.UIManager
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** Swing exports the selected TreePath as a JVM-local transfer through its bean property. */
internal class WatchTreeReorder(private val tree: JTree, private val service: LeekService) : TransferHandler("selectionPath") {
    private val pathFlavor = DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=javax.swing.tree.TreePath")
    private data class Placement(val source: DefaultMutableTreeNode, val target: DefaultMutableTreeNode, val after: Boolean)

    override fun getSourceActions(component: JComponent) = MOVE

    private fun placement(sourcePath: TreePath, location: JTree.DropLocation): Placement? {
        val source = sourcePath.lastPathComponent as? DefaultMutableTreeNode ?: return null
        val parent = source.parent ?: return null
        if (source.root !== tree.model.root) return null
        val hover = location.path?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        // Dropping over an expanded section's quotes places the dragged section below the whole section.
        val target = if (source.userObject is WatchGroup && hover.userObject is Symbol) hover.parent as DefaultMutableTreeNode else hover
        if (parent !== target.parent) return null
        val bounds = tree.getPathBounds(TreePath(target.path)) ?: return null
        val after = location.dropPoint.y >= bounds.centerY
        val from = parent.getIndex(source)
        val insertion = parent.getIndex(target) + if (after) 1 else 0
        if (insertion == from || insertion == from + 1) return null
        return Placement(source, target, after)
    }

    private fun placement(support: TransferSupport): Placement? {
        if (!support.isDrop || !support.isDataFlavorSupported(pathFlavor) || support.dropAction != MOVE) return null
        val source = support.transferable.getTransferData(pathFlavor) as? TreePath ?: return null
        return placement(source, support.dropLocation as JTree.DropLocation)
    }

    override fun canImport(support: TransferSupport) = placement(support) != null

    override fun importData(support: TransferSupport): Boolean {
        val drop = placement(support) ?: return false
        val target = drop.target.userObject
        return when (val source = drop.source.userObject) {
            is WatchGroup -> target is WatchGroup && service.moveGroup(source, target, drop.after)
            is Symbol -> target is Symbol && service.moveSymbol(source.code, target.code, drop.after)
            else -> false
        }
    }

    fun paintDropLine(g: Graphics) {
        val location = tree.dropLocation ?: return
        val drop = placement(tree.selectionPath ?: return, location) ?: return
        val path = TreePath(drop.target.path)
        val last = if (drop.after && tree.isExpanded(path) && drop.target.childCount > 0)
            path.pathByAddingChild(drop.target.lastChild) else path
        val bounds = tree.getPathBounds(last) ?: return
        val height = JBUI.scale(2)
        val y = if (drop.after) bounds.y + bounds.height - height else bounds.y
        val visible = tree.visibleRect
        g.color = UIManager.getColor("Tree.dropLineColor") ?: tree.foreground
        g.fillRect(visible.x, y, visible.width, height)
    }
}
