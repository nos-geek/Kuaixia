package com.kuaixia.app.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kuaixia.app.core.log.AppLogRepository
import com.kuaixia.app.core.log.LogTags
import com.kuaixia.app.core.model.AppLanguage
import com.kuaixia.app.ui.debug.AppLogScreen
import com.kuaixia.app.ui.debug.AppLogViewModel
import com.kuaixia.app.ui.debug.YtDlpDebugScreen
import com.kuaixia.app.ui.debug.YtDlpDebugViewModel
import com.kuaixia.app.ui.douyin.DouyinLoginScreen
import com.kuaixia.app.ui.douyin.DouyinLoginViewModel
import com.kuaixia.app.ui.download.DownloadListScreen
import com.kuaixia.app.ui.download.DownloadViewModel
import com.kuaixia.app.ui.home.HomeScreen
import com.kuaixia.app.ui.home.HomeViewModel
import com.kuaixia.app.ui.settings.AddServerScreen
import com.kuaixia.app.ui.settings.DeveloperOptionsScreen
import com.kuaixia.app.ui.settings.ServerListScreen
import com.kuaixia.app.ui.settings.ServerSettingsViewModel
import com.kuaixia.app.ui.settings.SettingsScreen
import com.kuaixia.app.ui.theme.KuaixiaTheme

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val SERVER_LIST = "server_list"
    const val ADD_SERVER = "add_server?serverId={serverId}"
    const val YTDLP_DEBUG = "ytdlp_debug"
    const val DOWNLOADS = "downloads"
    const val LOGS = "logs"
    const val DOUYIN_LOGIN = "douyin_login?url={url}"
    const val DEVELOPER_OPTIONS = "developer_options"
}

/** 构建抖音登录路由：url 为待重试的原始抖音链接（可空=仅登录/从设置进入）。 */
private fun douyinLoginRoute(url: String?): String {
    val encoded = url?.let { android.net.Uri.encode(it) }.orEmpty()
    return "douyin_login?url=$encoded"
}

/**
 * 防止快速连点重复导航：
 * - 目标已是当前目的地时直接跳过（避免重复 navigate）；
 * - singleTop 避免返回栈重复压入同一 destination。
 */
private fun NavController.navigateSingleTop(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) { launchSingleTop = true }
}

/**
 * 返回守卫（v1.0.0-RC 黑屏根因修复）。
 *
 * 根因：各页面返回按钮此前无条件调用 `popBackStack()`。当返回栈只剩
 * 「导航图根 + 起始目的地」时再 pop，会把 **起始目的地也弹掉**，NavController 返回栈变为空
 * （`currentDestination == null`、`currentBackStack.size == 0`）。此时 NavHost 没有任何可渲染的
 * 目的地，Compose 根节点测量为 0×0，宿主 ComposeView 随之塌陷为 0×0 → 整屏黑屏；
 * 又因宿主视图尺寸为 0，触摸无法命中，界面永久无响应，只能杀进程重启。
 *
 * 真机取证（应用自身持久化日志）：
 * ```
 * route=downloads depth=3   ← 进入子页
 * route=home      depth=2   ← 第 1 次返回：正常
 * route=null      depth=0   ← 第 2 次返回：起始目的地被弹掉，返回栈清空
 * compose=0x0 parent=0x0    ← 宿主塌陷 → 黑屏
 * ```
 *
 * 修复：返回栈只剩起始目的地时不再 pop（此时用户实际已在首页，重复的返回点击应被忽略）。
 * 系统返回键不受影响：仍由 NavHost 的返回回调处理，栈空时正常退出应用。
 */
private fun NavController.popBackGuarded() {
    // 返回栈结构固定为 [导航图根, ...目的地]，size <= 2 表示已只剩起始目的地
    if (currentBackStack.value.size <= 2) return
    popBackStack()
}

@Composable
fun KuaixiaApp(initialUrl: String?) {
    // Activity 级共享的设置 ViewModel（服务器列表 + 外观模式）
    val settingsViewModel: ServerSettingsViewModel = viewModel()
    val settingsState by settingsViewModel.uiState.collectAsState()

    KuaixiaTheme(themeMode = settingsState.themeMode) {
        KuaixiaNavHost(
            settingsViewModel = settingsViewModel,
            initialUrl = initialUrl,
        )
    }
}

@Composable
private fun KuaixiaNavHost(
    settingsViewModel: ServerSettingsViewModel,
    initialUrl: String?,
) {
    val navController = rememberNavController()

    // 兜底自愈：返回栈一旦被清空（任何路径造成），NavHost 会渲染 0×0 → 整屏黑屏且不可恢复。
    // 这里立即把起始目的地补回来，确保最坏情况下也能自动恢复到首页，而不是永久黑屏。
    LaunchedEffect(navController) {
        navController.currentBackStack.collect { stack ->
            if (stack.isEmpty()) {
                AppLogRepository.i(LogTags.KUAIXIA, "返回栈为空，兜底恢复起始目的地 ${Routes.HOME}")
                runCatching { navController.navigate(Routes.HOME) { launchSingleTop = true } }
            }
        }
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel()
            val homeContext = LocalContext.current
            val currentLanguage = settingsViewModel.uiState.collectAsState().value.language
            LaunchedEffect(Unit) { vm.setInitialUrl(initialUrl) }
            HomeScreen(
                uiState = vm.uiState.collectAsState().value,
                currentLanguage = currentLanguage,
                onToggleLanguage = {
                    settingsViewModel.setLanguage(
                        if (currentLanguage == AppLanguage.ZH) AppLanguage.EN else AppLanguage.ZH,
                    )
                    (homeContext as? Activity)?.recreate()
                },
                onUrlChange = vm::onUrlChange,
                onParse = vm::parse,
                onCancel = vm::cancel,
                onOpenSettings = { navController.navigateSingleTop(Routes.SETTINGS) },
                onOpenDownloads = { navController.navigateSingleTop(Routes.DOWNLOADS) },
                onDownload = { stream ->
                    vm.downloadSelected(stream)
                    navController.navigateSingleTop(Routes.DOWNLOADS)
                },
                onDownloadImageAt = { video, index ->
                    vm.downloadImageAt(video, index)
                    navController.navigateSingleTop(Routes.DOWNLOADS)
                },
                onDownloadImages = { video ->
                    vm.downloadImages(video)
                    navController.navigateSingleTop(Routes.DOWNLOADS)
                },
                onDownloadImagesSelected = { video, indices ->
                    vm.downloadImagesSelected(video, indices)
                    navController.navigateSingleTop(Routes.DOWNLOADS)
                },
                onPaste = vm::pasteFromClipboard,
                onHomeReady = vm::onHomeReady,
                onClipboardSource = vm::checkClipboardAndAutoParse,
                onParseClipboard = vm::parseClipboard,
                onIgnoreClipboard = vm::ignoreClipboard,
                onOpenDouyinLogin = {
                    navController.navigateSingleTop(douyinLoginRoute(vm.currentUrl()))
                },
                onDismissDouyinPrompt = vm::dismissDouyinPrompt,
            )
        }

        composable(Routes.DOWNLOADS) {
            val vm: DownloadViewModel = viewModel()
            DownloadListScreen(
                viewModel = vm,
                onBack = { navController.popBackGuarded() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                uiState = settingsViewModel.uiState.collectAsState().value,
                onBack = { navController.popBackGuarded() },
                onOpenServerList = { navController.navigateSingleTop(Routes.SERVER_LIST) },
                onThemeModeChange = settingsViewModel::setThemeMode,
                onParseModeChange = settingsViewModel::setParseMode,
                onClipboardAutoParseChange = settingsViewModel::setClipboardAutoParse,
                onOpenDouyinLogin = { navController.navigateSingleTop(douyinLoginRoute(null)) },
                onClearDouyinSession = settingsViewModel::clearDouyinSession,
                onOpenDeveloperOptions = { navController.navigateSingleTop(Routes.DEVELOPER_OPTIONS) },
            )
        }

        // 开发者选项：v0.2.0-parser-lifecycle 原「设置 → 高级」的调试/测试入口统一收口于此
        composable(Routes.DEVELOPER_OPTIONS) {
            DeveloperOptionsScreen(
                uiState = settingsViewModel.uiState.collectAsState().value,
                onBack = { navController.popBackGuarded() },
                onDebugLoggingChange = settingsViewModel::setDebugLogging,
                onOpenYtDlpDebug = { navController.navigateSingleTop(Routes.YTDLP_DEBUG) },
                onOpenLogs = { navController.navigateSingleTop(Routes.LOGS) },
            )
        }

        composable(Routes.YTDLP_DEBUG) {
            val vm: YtDlpDebugViewModel = viewModel()
            YtDlpDebugScreen(
                viewModel = vm,
                onBack = { navController.popBackGuarded() },
            )
        }

        composable(Routes.LOGS) {
            val vm: AppLogViewModel = viewModel()
            AppLogScreen(
                viewModel = vm,
                onBack = { navController.popBackGuarded() },
            )
        }

        composable(
            route = Routes.DOUYIN_LOGIN,
            arguments = listOf(navArgument("url") {
                type = NavType.StringType
                defaultValue = ""
            }),
        ) { backStackEntry ->
            val vm: DouyinLoginViewModel = viewModel()
            val raw = backStackEntry.arguments?.getString("url").orEmpty()
            val target = if (raw.isBlank()) null else android.net.Uri.decode(raw)
            DouyinLoginScreen(
                pendingUrl = target,
                onBack = { navController.popBackGuarded() },
                viewModel = vm,
            )
        }

        composable(Routes.SERVER_LIST) {
            ServerListScreen(
                uiState = settingsViewModel.uiState.collectAsState().value,
                onBack = { navController.popBackGuarded() },
                onAdd = { navController.navigateSingleTop("add_server") },
                onEdit = { id -> navController.navigateSingleTop("add_server?serverId=$id") },
                onDelete = settingsViewModel::deleteServer,
                onSetDefault = settingsViewModel::setDefaultServer,
                onToggleEnabled = settingsViewModel::setServerEnabled,
            )
        }

        composable(
            route = Routes.ADD_SERVER,
            arguments = listOf(navArgument("serverId") {
                type = NavType.StringType
                defaultValue = ""
            }),
        ) { backStackEntry ->
            val serverId = backStackEntry.arguments?.getString("serverId").orEmpty()
            val state = settingsViewModel.uiState.collectAsState().value
            val existing = state.servers.firstOrNull { it.id == serverId }

            AddServerScreen(
                serverId = serverId,
                existingServer = existing,
                onSave = { config ->
                    settingsViewModel.saveServer(config)
                    navController.popBackGuarded()
                },
                onTestConnection = settingsViewModel::testConnection,
                onBack = { navController.popBackGuarded() },
            )
        }
    }
}
