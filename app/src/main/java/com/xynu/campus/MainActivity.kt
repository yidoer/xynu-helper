package com.xynu.campus

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xynu.campus.library.vm.LibraryViewModelFactory
import com.xynu.campus.ui.home.HomeScreen
import com.xynu.campus.ui.home.SEAT_RESERVATION_HALL_URL
import com.xynu.campus.ui.home.WebScreen
import com.xynu.campus.ui.jw.JwLoginScreen
import com.xynu.campus.ui.jw.JwViewModel
import com.xynu.campus.ui.jw.ScoreScreen
import com.xynu.campus.ui.jw.ScheduleScreen
import com.xynu.campus.ui.lib.BookDetailScreen
import com.xynu.campus.ui.lib.BookSearchScreen
import com.xynu.campus.ui.lib.FavoritesScreen
import com.xynu.campus.ui.lib.LibLoginScreen
import com.xynu.campus.ui.lib.LibViewModel
import com.xynu.campus.ui.lib.MyBorrowScreen
import com.xynu.campus.ui.theme.XynuCampusTheme
import com.xynu.campus.library.util.LibraryLog
import com.xynu.campus.ui.tools.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 让 DEBUG 级日志只在 debuggable 包里产出。
        //
        // 不用 BuildConfig.DEBUG：本模块没开 buildConfig 特性（buildFeatures
        // 里只有 compose），读它要额外加开关。`FLAG_DEBUGGABLE` 直接来自
        // 已安装包的实际标志位，正是我们要问的那个问题。
        //
        // 不初始化也不会出事：debugEnabled 默认 false，是安全的那一侧。
        LibraryLog.installDefaultSink(
            releaseBuild = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0,
        )
        setContent {
            XynuCampusTheme {
                CampusApp()
            }
        }
    }

    private var secureFlagOn = false

    /**
     * 处理敏感页面（身份迁移 / 选座 / 引导向导）的前台截屏与录屏防护。
     *
     * 这三类页面会显示凭据本体或等价物：
     * - 迁移码 = Base64(openid + Cookie)，复制到就能接管账号
     * - 选座页展示会话状态、Cookie 有效性
     * - 引导向导让用户粘贴含 openid 的链接
     *
     * 只在这些页面停留在前台时置 [WindowManager.LayoutParams.FLAG_SECURE]，
     * 离开后立刻清掉 —— 不用全局开启，避免影响用户对普通页面的正常截屏。
     */
    fun setSecureScreen(enabled: Boolean) {
        if (enabled == secureFlagOn) return
        secureFlagOn = enabled
        if (enabled) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

object Routes {
    const val HOME = "home"
    const val JW_LOGIN = "jw_login"
    const val SCHEDULE = "schedule"
    const val SCORE = "score"
    const val FREE_CLASSROOM = "free_classroom"
    const val SEAT_RESERVATION = "seat_reservation"
    const val LIB_LOGIN = "lib_login"
    const val BOOK_SEARCH = "book_search"
    const val BOOK_DETAIL = "book_detail"
    const val FAVORITES = "favorites"
    const val MY_BORROW = "my_borrow"
    const val SETTINGS = "settings"
    const val WEB = "web"

    // ---------- 图书馆助手模块 ----------

    /** F3 借阅查询（三态：缺 openid / 未绑卡 / 已绑定）。 */
    const val LIBRARY_ASSISTANT = "library_assistant"

    /** F1 openid 引导向导（三步图文）。 */
    const val LIBRARY_WIZARD = "library_wizard"

    /** F4 选座（基础档 / 高级档）。 */
    const val LIBRARY_SEAT = "library_seat"

    /** F5 身份迁移（导出/导入）。 */
    const val LIBRARY_MIGRATION = "library_migration"

    /** 应用内馆藏检索（免登录，校内外网络都能用）。 */
    const val LIBRARY_CATALOG = "library_catalog"

    /** 书目详情 + 馆藏复本。 */
    const val LIBRARY_CATALOG_DETAIL = "library_catalog_detail"
}

/**
 * 在敏感页面停留在前台期间开启 `FLAG_SECURE`，离开时清除。
 *
 * 用 `OnDispose`/`DisposableEffect` 而不是在 Activity 全局开启：
 * 全局开启会让用户没法截课表、成绩这些正常页面，代价太大。
 */
@Composable
private fun SecureScreen() {
    val activity = LocalContext.current as? MainActivity ?: return
    DisposableEffect(Unit) {
        activity.setSecureScreen(true)
        onDispose { activity.setSecureScreen(false) }
    }
}

@Composable
fun CampusApp() {
    val navController = rememberNavController()
    // ViewModel 提升到 Activity 级作用域，登录状态/数据在页面间共享
    val jwVm: JwViewModel = viewModel()
    val libVm: LibViewModel = viewModel()
    val jwState by jwVm.state.collectAsState()
    val libState by libVm.state.collectAsState()

    // 图书馆助手模块的两个 VM 同样提升到 Activity 作用域。
    // 关键原因：借阅页与引导向导页共用 BorrowViewModel 的状态机——
    // 若两处各自 viewModel() 就会实例化两份，向导里保存了 openid、
    // 返回借阅页却还是旧状态（表现为"明明保存成功了还是显示请去引导"）。
    // BorrowViewModel 有构造参数，必须显式给 factory，否则真机反射建不出来会崩。
    val libraryVmFactory = LibraryViewModelFactory(LocalContext.current.applicationContext)
    val libraryBorrowVm: com.xynu.campus.library.vm.BorrowViewModel =
        viewModel(factory = libraryVmFactory)
    val librarySeatVm: com.xynu.campus.library.vm.SeatViewModel =
        viewModel(factory = libraryVmFactory)
    val libraryCatalogVm: com.xynu.campus.library.vm.CatalogViewModel =
        viewModel(factory = libraryVmFactory)

    LaunchedEffect(jwState.sessionExpired) {
        if (jwState.sessionExpired) {
            jwVm.consumeSessionExpired()
            navController.navigate(Routes.JW_LOGIN) { launchSingleTop = true }
        }
    }
    LaunchedEffect(libState.sessionExpired) {
        if (libState.sessionExpired) {
            libVm.consumeSessionExpired()
            navController.navigate(Routes.LIB_LOGIN) { launchSingleTop = true }
        }
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {

        composable(Routes.HOME) {
            HomeScreen(
                onOpenSchedule = { navController.navigate(Routes.SCHEDULE) },
                onOpenScore = { navController.navigate(Routes.SCORE) },
                onOpenBookSearch = { navController.navigate(Routes.BOOK_SEARCH) },
                onOpenMyBorrow = { navController.navigate(Routes.MY_BORROW) },
                onOpenFreeClassroom = { navController.navigate(Routes.FREE_CLASSROOM) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenJwLogin = { navController.navigate(Routes.JW_LOGIN) },
                onOpenLibLogin = { navController.navigate(Routes.LIB_LOGIN) },
                onOpenFavorites = { navController.navigate(Routes.FAVORITES) },
                onOpenLibraryAssistant = {
                    navController.navigate(Routes.LIBRARY_ASSISTANT) { launchSingleTop = true }
                },
                onOpenLibrarySeat = {
                    navController.navigate(Routes.LIBRARY_SEAT) { launchSingleTop = true }
                },
                onOpenLibraryCatalog = {
                    navController.navigate(Routes.LIBRARY_CATALOG) { launchSingleTop = true }
                },
                onOpenWeb = { url, title ->
                    if (url == SEAT_RESERVATION_HALL_URL) {
                        navController.navigate(Routes.SEAT_RESERVATION) {
                            launchSingleTop = true
                        }
                    } else {
                        // 用 query parameter 传递，避免 path parameter 中 %2F 被解码成 / 切碎 URL
                        val encoded = android.net.Uri.encode(url)
                        val t = title?.let { android.net.Uri.encode(it) } ?: ""
                        navController.navigate("${Routes.WEB}?url=$encoded&title=$t") {
                            launchSingleTop = true
                        }
                    }
                },
            )
        }

        composable(
            route = "${Routes.WEB}?url={url}&title={title}",
            arguments = listOf(
                androidx.navigation.navArgument("url") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("title") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val url = backStackEntry.arguments?.getString("url") ?: ""
            val title = backStackEntry.arguments?.getString("title")?.takeIf { it.isNotEmpty() }
            WebScreen(
                encodedUrl = url,
                title = title,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.JW_LOGIN) {
            JwLoginScreen(jwVm, onBack = { navController.popBackStack() },
                onSuccess = { navController.popBackStack() })
        }

        composable(Routes.SCHEDULE) {
            ScheduleScreen(jwVm, onBack = { navController.popBackStack() },
                onNeedLogin = { navController.navigate(Routes.JW_LOGIN) })
        }

        composable(Routes.SCORE) {
            ScoreScreen(jwVm, onBack = { navController.popBackStack() },
                onNeedLogin = { navController.navigate(Routes.JW_LOGIN) })
        }

        composable(Routes.FREE_CLASSROOM) {
            // 空教室实际属于教务系统子页面，必须使用已登录的教务会话查询。
            com.xynu.campus.ui.jw.FreeClassroomScreen(
                jwVm,
                onBack = { navController.popBackStack() },
                onNeedLogin = { navController.navigate(Routes.JW_LOGIN) }
            )
        }

        composable(Routes.SEAT_RESERVATION) {
            WebScreen(
                encodedUrl = android.net.Uri.encode(SEAT_RESERVATION_HALL_URL),
                title = "座位预约",
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.LIB_LOGIN) {
            LibLoginScreen(
                vm = libVm,
                onBack = { navController.popBackStack() },
                onSuccess = { navController.popBackStack(Routes.HOME, false) }
            )
        }

        composable(Routes.BOOK_SEARCH) {
            BookSearchScreen(
                libVm,
                onBack = { navController.popBackStack() },
                onOpenDetail = { bookId ->
                    navController.navigate("${Routes.BOOK_DETAIL}/$bookId")
                },
                onOpenFavorites = { navController.navigate(Routes.FAVORITES) }
            )
        }

        composable(
            route = "${Routes.BOOK_DETAIL}/{bookId}",
            arguments = listOf(
                androidx.navigation.navArgument("bookId") {
                    type = androidx.navigation.NavType.StringType
                }
            )
        ) { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId") ?: ""
            BookDetailScreen(
                bookId = bookId,
                vm = libVm,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.FAVORITES) {
            FavoritesScreen(
                libVm,
                onBack = { navController.popBackStack() },
                onOpenBook = { bookId ->
                    // 收藏里存的是书目 id，与老 OPAC 详情页共用同一套 id；
                    // 直接打开应用内的书目详情，这样在**校外网络**下点收藏也能看到
                    // 馆藏复本（老 BOOK_DETAIL 走 222.21.62.201:8011，只能校园网）。
                    if (bookId.isNotBlank()) {
                        libraryCatalogVm.loadDetail(
                            bookId = bookId,
                            titleHint = libState.favorites.firstOrNull { it.id == bookId }?.title,
                        )
                        navController.navigate(Routes.LIBRARY_CATALOG_DETAIL) {
                            launchSingleTop = true
                        }
                    }
                }
            )
        }

        composable(Routes.MY_BORROW) {
            MyBorrowScreen(libVm, onBack = { navController.popBackStack() },
                onNeedLogin = { navController.navigate(Routes.LIB_LOGIN) })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                jwVm = jwVm,
                onOpenLibraryMigration = {
                    navController.navigate(Routes.LIBRARY_MIGRATION) { launchSingleTop = true }
                },
            )
        }

        // ---------- 图书馆助手模块 ----------

        composable(Routes.LIBRARY_ASSISTANT) {
            com.xynu.campus.library.ui.BorrowScreen(
                vm = libraryBorrowVm,
                onBack = { navController.popBackStack() },
                onOpenWizard = {
                    navController.navigate(Routes.LIBRARY_WIZARD) { launchSingleTop = true }
                },
            )
        }

        composable(Routes.LIBRARY_WIZARD) {
            SecureScreen()
            com.xynu.campus.library.ui.BorrowWizardScreen(
                vm = libraryBorrowVm,
                onBack = { navController.popBackStack() },
                onFinished = {
                    // 向导完成后回借阅页，让它用新的 openid 重新判定落地态
                    navController.popBackStack(Routes.LIBRARY_ASSISTANT, false)
                },
            )
        }

        composable(Routes.LIBRARY_SEAT) {
            SecureScreen()
            com.xynu.campus.library.ui.SeatScreen(
                vm = librarySeatVm,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.LIBRARY_MIGRATION) {
            SecureScreen()
            com.xynu.campus.library.ui.MigrationScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.LIBRARY_CATALOG) {
            com.xynu.campus.library.ui.CatalogScreen(
                vm = libraryCatalogVm,
                onBack = { navController.popBackStack() },
                onOpenDetail = { detailUrl, titleHint ->
                    // detailUrl 里只有书目 id 有用，但 URL 带 / 不能当路由段，
                    // 所以用 ViewModel 直接发起加载，路由只承载「去详情页」这件事。
                    libraryCatalogVm.openDetail(detailUrl, titleHint)
                    navController.navigate(Routes.LIBRARY_CATALOG_DETAIL) {
                        launchSingleTop = true
                    }
                },
                // 收藏复用老模块的 DataStore 存储（favorite_books），
                // 这样「收藏书架」里能同时看到老检索页和这个新检索页收藏的书。
                favoriteIds = libState.favorites.map { it.id }.toSet(),
                onToggleFavorite = { book ->
                    val id = com.xynu.campus.library.net.CatalogRepository
                        .bookIdOf(book.detailUrl) ?: book.title
                    libVm.toggleFavorite(
                        com.xynu.campus.data.model.BookItem(
                            id = id,
                            title = book.title,
                            author = book.author.orEmpty(),
                            callNumber = book.callNo.orEmpty(),
                            isbn = "",
                            publisher = book.publisher.orEmpty(),
                            detailUrl = book.detailUrl.orEmpty(),
                            holdingCount = "",
                            canBorrowCount = "",
                        ),
                    )
                },
            )
        }

        composable(Routes.LIBRARY_CATALOG_DETAIL) {
            // 用 collectAsState 而不是 .value：收藏心形要跟着状态变，
            // 直接读 .value 不会触发重组，点了没反应。
            val catalogState by libraryCatalogVm.state.collectAsState()
            val detailFavId = catalogState.detailBookId
            com.xynu.campus.library.ui.CatalogDetailScreen(
                vm = libraryCatalogVm,
                onBack = {
                    libraryCatalogVm.clearDetail()
                    navController.popBackStack()
                },
                isFavorite = detailFavId != null &&
                    libState.favorites.any { it.id == detailFavId },
                onToggleFavorite = { detail, bookId ->
                    val id = bookId ?: detail.title.orEmpty()
                    // 收藏表只有一份（favorite_books），字段与 BookItem 对齐；
                    // 这里的 detail 是免登录检索的结果，拿不到 ISBN/出版信息，
                    // 但索书号能从第一条复本记录里补上——收藏书架列表要显示它。
                    libVm.toggleFavorite(
                        com.xynu.campus.data.model.BookItem(
                            id = id,
                            title = detail.title.orEmpty(),
                            author = detail.author.orEmpty(),
                            callNumber = detail.holdings.firstOrNull { !it.callNo.isNullOrBlank() }
                                ?.callNo.orEmpty(),
                            isbn = detail.isbn.orEmpty(),
                            publisher = detail.publisher.orEmpty(),
                            detailUrl = "",
                            holdingCount = detail.holdings.size.toString(),
                            canBorrowCount = detail.availableCount.toString(),
                        ),
                    )
                },
            )
        }
    }
}
