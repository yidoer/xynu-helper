package com.xynu.campus

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.xynu.campus.ui.home.HomeScreen
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
import com.xynu.campus.ui.tools.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XynuCampusTheme {
                CampusApp()
            }
        }
    }
}

object Routes {
    const val HOME = "home"
    const val JW_LOGIN = "jw_login"
    const val SCHEDULE = "schedule"
    const val SCORE = "score"
    const val FREE_CLASSROOM = "free_classroom"
    const val LIB_LOGIN = "lib_login"
    const val BOOK_SEARCH = "book_search"
    const val BOOK_DETAIL = "book_detail"
    const val FAVORITES = "favorites"
    const val MY_BORROW = "my_borrow"
    const val SETTINGS = "settings"
    const val WEB = "web"
}

@Composable
fun CampusApp() {
    val navController = rememberNavController()
    // ViewModel 提升到 Activity 级作用域，登录状态/数据在页面间共享
    val jwVm: JwViewModel = viewModel()
    val libVm: LibViewModel = viewModel()
    val jwState by jwVm.state.collectAsState()
    val libState by libVm.state.collectAsState()

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
                onOpenWeb = { url, title ->
                    // 应用内打开所有外链（新闻/座位预约/超星 H5/校园资讯/地图）
                    // 用 query parameter 传递，避免 path parameter 中 %2F 被解码成 / 切碎 URL
                    val encoded = android.net.Uri.encode(url)
                    val t = title?.let { android.net.Uri.encode(it) } ?: ""
                    navController.navigate("${Routes.WEB}?url=$encoded&title=$t") {
                        launchSingleTop = true
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
                onSuccess = { navController.popBackStack(Routes.HOME, false) })
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
            // 空教室查询：原生页（走 JwApi.fetchFreeClassrooms，无需 WebView）
            com.xynu.campus.ui.jw.FreeClassroomScreen(
                jwVm,
                onBack = { navController.popBackStack() },
                onNeedLogin = { navController.navigate(Routes.JW_LOGIN) }
            )
        }

        composable(Routes.LIB_LOGIN) {
            LibLoginScreen(libVm, onBack = { navController.popBackStack() },
                onSuccess = { navController.popBackStack(Routes.HOME, false) })
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
                    navController.navigate("${Routes.BOOK_DETAIL}/$bookId")
                }
            )
        }

        composable(Routes.MY_BORROW) {
            MyBorrowScreen(libVm, onBack = { navController.popBackStack() },
                onNeedLogin = { navController.navigate(Routes.LIB_LOGIN) })
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() }, jwVm = jwVm)
        }
    }
}
