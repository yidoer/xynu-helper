package com.xynu.campus.library.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.xynu.campus.library.net.MobileLibRepository
import com.xynu.campus.library.store.IdentityStore

/**
 * 图书馆助手模块的 ViewModel 工厂。
 *
 * 为什么需要它：[BorrowViewModel] 的构造参数是 `MobileLibRepository` + `IdentityStore`，
 * 没有无参构造，而 `viewModel()` 默认走 `JvmViewModelProviders.createViewModel`
 * （反射找无参构造）——真机上点进去直接
 * `NoSuchMethodException: BorrowViewModel.<init> []` 崩溃。这个错误**编译期发现不了**，
 * JVM 单测也发现不了（单测直接 new 对象），只有真机跑一次才会暴露。
 *
 * [SeatViewModel] 是 `AndroidViewModel(Application)`，系统能自己建，
 * 但为了两条路径一致、也为了以后给它加依赖时不用改调用方，这里一并提供。
 */
class LibraryViewModelFactory(
    private val appContext: Context,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(BorrowViewModel::class.java) -> {
            BorrowViewModel(
                repo = MobileLibRepository(),
                store = IdentityStore.get(appContext),
            ) as T
        }

        modelClass.isAssignableFrom(SeatViewModel::class.java) -> {
            SeatViewModel(appContext as android.app.Application) as T
        }

        modelClass.isAssignableFrom(CatalogViewModel::class.java) -> {
            CatalogViewModel() as T
        }

        else -> throw IllegalArgumentException("未知的 ViewModel：${modelClass.name}")
    }
}
