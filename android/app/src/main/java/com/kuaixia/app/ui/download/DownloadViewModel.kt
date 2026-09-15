package com.kuaixia.app.ui.download

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.kuaixia.app.KuaixiaApp
import com.kuaixia.app.data.download.DownloadRepository
import com.kuaixia.app.data.download.DownloadTask
import kotlinx.coroutines.flow.StateFlow

/** 下载列表 ViewModel：观察任务列表，转发暂停/继续/取消/重试/删除/重解析。 */
class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DownloadRepository =
        (application as KuaixiaApp).container.downloadRepository

    val tasks: StateFlow<List<DownloadTask>> = repository.tasks

    fun pause(id: String) = repository.pause(id)
    fun resume(id: String) = repository.resume(id)
    fun cancel(id: String) = repository.cancel(id)
    fun retry(id: String) = repository.retry(id)
    fun delete(id: String) = repository.delete(id)
    fun deleteBatch(ids: List<String>) = repository.deleteBatch(ids)
    fun reparseAndResume(id: String) = repository.reparseAndResume(id)
}
