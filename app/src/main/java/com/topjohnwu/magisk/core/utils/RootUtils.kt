package com.topjohnwu.magisk.core.utils

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.getSystemService
import com.topjohnwu.magisk.core.Info
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import com.topjohnwu.superuser.ipc.RootService
import com.topjohnwu.superuser.nio.FileSystemManager
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.AbstractQueuedSynchronizer

class RootUtils(stub: Any?) : RootService() {

    private val className: String = stub?.javaClass?.name ?: javaClass.name
    private lateinit var am: ActivityManager

    constructor() : this(null) {
        Timber.plant(object : Timber.DebugTree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                super.log(priority, "Magisk", message, t)
            }
        })
    }

    override fun onCreate() {
        am = getSystemService()!!
    }

    override fun getComponentName(): ComponentName {
        return ComponentName(packageName, className)
    }

    override fun onBind(intent: Intent): IBinder {
        return object : IRootUtils.Stub() {
            override fun getAppProcess(pid: Int) = safe(null) { getAppProcessImpl(pid) }
            override fun getFileSystem(): IBinder = FileSystemManager.getService()
        }
    }

    private inline fun <T> safe(default: T, block: () -> T): T {
        return try {
            block()
        } catch (e: Throwable) {
            Timber.e(e)
            default
        }
    }

    private fun getAppProcessImpl(_pid: Int): ActivityManager.RunningAppProcessInfo? {
        val procList = am.runningAppProcesses
        var pid = _pid
        while (pid > 1) {
            val proc = procList.find { it.pid == pid }
            if (proc != null)
                return proc
            // Find PPID
            try {
                File("/proc/$pid/status").useLines {
                    val line = it.find { l -> l.startsWith("PPid:") } ?: return null
                    pid = line.substring(5).trim().toInt()
                }
            } catch (e: IOException) {
                // The process died unexpectedly
                return null
            }
        }
        return null
    }

    object Connection : AbstractQueuedSynchronizer(), ServiceConnection {
        init {
            state = 1
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            Timber.d("onServiceConnected")
            IRootUtils.Stub.asInterface(service).let {
                obj = it
                fs = FileSystemManager.getRemote(it.fileSystem)
            }
            releaseShared(1)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            state = 1
            obj = null
            bind(Intent().setComponent(name), this)
        }

        override fun tryAcquireShared(acquires: Int) = if (state == 0) 1 else -1

        override fun tryReleaseShared(releases: Int): Boolean {
            // Decrement count; signal when transition to zero
            while (true) {
                val c = state
                if (c == 0)
                    return false
                val n = c - 1
                if (compareAndSetState(c, n))
                    return n == 0
            }
        }

        fun await() {
            // We cannot await on the main thread
            if (Info.isRooted && !ShellUtils.onMainThread())
                acquireSharedInterruptibly(1)
        }
    }

    companion object {
        var bindTask: Shell.Task? = null
        var fs = FileSystemManager.getLocal()
            private set
        var obj: IRootUtils? = null
            get() {
                Connection.await()
                return field
            }
            private set
    }
}
