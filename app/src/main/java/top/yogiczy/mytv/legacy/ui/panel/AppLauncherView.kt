package top.yogiczy.mytv.legacy.ui.panel

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import top.yogiczy.mytv.legacy.ui.panel.SourcePanelView.AppItem

/**
 * 系统应用面板（左导航「系统应用」时的中栏内容）。
 *
 * M10 从 `MainActivity` 搬来（M6 遗留的拆分项），**行为一字不改**，只搬家：
 * 后台查询可启动应用，排序后交给宿主渲染，点击时由宿主 startActivity。
 */
class AppLauncherView(private val ctx: Context) {

    /** 查询所有可启动应用（排除自己），按名称排序。 */
    fun queryApps(selfPackage: String): List<AppItem> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(intent, 0)
        return list.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == selfPackage) return@mapNotNull null
            AppItem(
                ri.loadLabel(pm).toString(),
                pkg,
                loadIcon(pm, ri),
            )
        }.distinctBy { it.pkg }.sortedBy { it.label }
    }

    private fun loadIcon(pm: PackageManager, ri: android.content.pm.ResolveInfo): Drawable? =
        runCatching { ri.loadIcon(pm) }.getOrNull()

    /** 应用列表 → 行模型。 */
    fun rows(apps: List<AppItem>, onLaunch: (AppItem) -> Unit): List<ChannelColumnView.Row> {
        val rows = ArrayList<ChannelColumnView.Row>(apps.size)
        apps.forEach { app ->
            rows.add(
                ChannelColumnView.Row.TextRow(
                    title = app.label,
                    subtitle = app.pkg,
                    icon = app.icon,
                    onClick = { onLaunch(app) },
                )
            )
        }
        return rows
    }

    /** 占位行（读取中）。 */
    fun loadingRows(): List<ChannelColumnView.Row> = listOf(
        ChannelColumnView.Row.TextRow("正在读取已装应用…", null, null, onClick = {})
    )
}
