package com.yiyang.b6xctrl.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yiyang.b6xctrl.control.ControlConfig
import com.yiyang.b6xctrl.service.AppRepository
import com.yiyang.b6xctrl.service.ControlService
import com.yiyang.b6xctrl.service.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AppRepository(app)
    private var service: ControlService? = null

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as ControlService.LocalBinder).service
            viewModelScope.launch {
                service?.uiState?.collect { _uiState.value = it }
            }
            // 应用已保存的配置
            viewModelScope.launch {
                val cfg = repo.configFlow.first()
                service?.setConfig(cfg)
                _uiState.value = _uiState.value.copy(config = cfg)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    init {
        ControlService.start(getApplication())
        getApplication<Application>().bindService(
            Intent(getApplication(), ControlService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onCleared() {
        getApplication<Application>().unbindService(connection)
        super.onCleared()
    }

    fun connect() {
        service?.connect()
    }

    fun disconnect() {
        service?.disconnect()
    }

    fun setAutoMode(auto: Boolean) {
        service?.setAutoMode(auto)
    }

    fun setManualWindLevel(level: Int) {
        service?.setManualWindLevel(level)
    }

    fun setManualColdLevel(level: Int) {
        service?.setManualColdLevel(level)
    }

    fun setManualPower(on: Boolean) {
        service?.setManualPower(on)
    }

    /** 实验功能：发送原始 hex 命令，返回是否解析成功 */
    fun sendExperimentHex(hex: String): Boolean =
        service?.sendExperimentHex(hex) ?: false

    fun saveConfig(cfg: ControlConfig) {
        viewModelScope.launch {
            repo.saveConfig(cfg)
            service?.setConfig(cfg)
            _uiState.value = _uiState.value.copy(config = cfg)
        }
    }

    /** 导出当前配置为 JSON（从 DataStore 读最新值） */
    fun exportConfig(callback: (String) -> Unit) {
        viewModelScope.launch {
            val cfg = repo.configFlow.first()
            callback(AppRepository.encodeConfig(cfg))
        }
    }

    /** 导入配置 JSON；解析成功则保存并走 setConfig（触发当前挡位立即重发）。返回是否解析成功。 */
    fun importConfig(json: String): Boolean {
        val cfg = AppRepository.decodeConfig(json) ?: return false
        viewModelScope.launch {
            repo.saveConfig(cfg)
            service?.setConfig(cfg)
            _uiState.value = _uiState.value.copy(config = cfg)
        }
        return true
    }

    fun stopService() {
        service?.let { ControlService.stop(it) }
        _uiState.value = UiState()
    }
}
