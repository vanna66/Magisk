package com.topjohnwu.magisk.ui.install

import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.widget.Toast
import androidx.databinding.Bindable
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.BuildConfig
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.core.Const
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.magisk.core.base.ContentResultCallback
import com.topjohnwu.magisk.data.repository.NetworkService
import com.topjohnwu.magisk.databinding.set
import com.topjohnwu.magisk.di.AppContext
import com.topjohnwu.magisk.di.ServiceLocator
import com.topjohnwu.magisk.events.GetContentEvent
import com.topjohnwu.magisk.events.dialog.SecondSlotWarningDialog
import com.topjohnwu.magisk.ui.flash.FlashFragment
import com.topjohnwu.magisk.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import timber.log.Timber
import java.io.File
import java.io.IOException

class InstallViewModel(
    svc: NetworkService
) : BaseViewModel() {

    val isRooted get() = Info.isRooted
    val hideVbmeta = Info.vbmeta || Info.isSamsung || Info.isAB
    val skipOptions = Info.isEmulator || (Info.isSAR && !Info.isFDE && hideVbmeta && Info.ramdisk)
    val noSecondSlot = !isRooted || !Info.isAB || Info.isEmulator

    @get:Bindable
    var step = if (skipOptions) 1 else 0
        set(value) = set(value, field, { field = it }, BR.step)

    private var methodId = -1

    @get:Bindable
    var method
        get() = methodId
        set(value) = set(value, methodId, { methodId = it }, BR.method) {
            when (it) {
                R.id.method_patch -> {
                    GetContentEvent("*/*", UriCallback()).publish()
                }
                R.id.method_inactive_slot -> {
                    SecondSlotWarningDialog().publish()
                }
            }
        }

    val data: LiveData<Uri?> get() = uri

    @get:Bindable
    var notes: Spanned = SpannableStringBuilder()
        set(value) = set(value, field, { field = it }, BR.notes)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(AppContext.cacheDir, "${BuildConfig.VERSION_CODE}.md")
                val text = when {
                    file.exists() -> file.readText()
                    Const.Url.CHANGELOG_URL.isEmpty() -> ""
                    else -> {
                        val str = svc.fetchString(Const.Url.CHANGELOG_URL)
                        file.writeText(str)
                        str
                    }
                }
                notes = ServiceLocator.markwon.toMarkdown(text)
            } catch (e: IOException) {
                Timber.e(e)
            }
        }
    }

    fun install() {
        when (method) {
            R.id.method_patch -> FlashFragment.patch(data.value!!).navigate(true)
            R.id.method_direct -> FlashFragment.flash(false).navigate(true)
            R.id.method_inactive_slot -> FlashFragment.flash(true).navigate(true)
            else -> error("Unknown value")
        }
        state = State.LOADING
    }

    override fun onSaveState(state: Bundle) {
        state.putParcelable(INSTALL_STATE_KEY, InstallState(
            methodId,
            step,
            Config.keepVerity,
            Config.keepEnc,
            Config.patchVbmeta,
            Config.recovery
        ))
    }

    override fun onRestoreState(state: Bundle) {
        state.getParcelable<InstallState>(INSTALL_STATE_KEY)?.let {
            methodId = it.method
            step = it.step
            Config.keepVerity = it.keepVerity
            Config.keepEnc = it.keepEnc
            Config.patchVbmeta = it.patchVbmeta
            Config.recovery = it.recovery
        }
    }

    @Parcelize
    class UriCallback : ContentResultCallback {
        override fun onActivityLaunch() {
            Utils.toast(R.string.patch_file_msg, Toast.LENGTH_LONG)
        }
        override fun onActivityResult(result: Uri) {
            uri.value = result
        }
    }

    @Parcelize
    class InstallState(
        val method: Int,
        val step: Int,
        val keepVerity: Boolean,
        val keepEnc: Boolean,
        val patchVbmeta: Boolean,
        val recovery: Boolean,
    ) : Parcelable

    companion object {
        private const val INSTALL_STATE_KEY = "install_state"
        private val uri = MutableLiveData<Uri?>()
    }
}
