package com.yiyang.b6xctrl

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.yiyang.b6xctrl.boot.BootReceiver
import com.yiyang.b6xctrl.service.ControlService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {

    private class RecordingContext : ContextWrapper(null) {
        val started = mutableListOf<Intent>()

        override fun getPackageName(): String = "com.yiyang.b6xctrl"

        override fun getApplicationContext(): Context = this

        override fun startService(service: Intent?): ComponentName? {
            service?.let { started += it }
            return null
        }

        override fun startForegroundService(service: Intent?): ComponentName? {
            service?.let { started += it }
            return null
        }
    }

    private class FakeIntent(private val action: String?) : Intent() {
        override fun getAction(): String? = action
    }

    private fun deliver(action: String?): List<Intent> {
        val context = RecordingContext()
        BootReceiver().onReceive(context, FakeIntent(action))
        return context.started
    }

    @Test
    fun bootCompleted_startsService() {
        val started = deliver(Intent.ACTION_BOOT_COMPLETED)
        assertEquals(1, started.size)
    }

    @Test
    fun packageReplaced_startsService() {
        val started = deliver(Intent.ACTION_MY_PACKAGE_REPLACED)
        assertEquals(1, started.size)
    }

    @Test
    fun unrelatedAction_doesNotStartService() {
        assertTrue(deliver(Intent.ACTION_TIME_CHANGED).isEmpty())
        assertTrue(deliver(null).isEmpty())
    }

    @Test
    fun controlServiceStart_startsService() {
        val context = RecordingContext()
        ControlService.start(context)
        assertEquals(1, context.started.size)
    }
}
