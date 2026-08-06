# B6X 温控（FlydigiB6xTempCtrl）

飞智 B6X 磁吸散热器智能温控 Android App。

**核心问题**：飞智游戏厅的"智能模式"只根据散热器**自身背面温度**调速——系统温度降得再低它也只会降到最低速而不会停。本 App 改为读取**系统温度**（`/sys/class/thermal`，root 增强），按系统温度智能控制散热器的**启停与风速**，真正实现"温度够低就停机"。

## 功能

- ✅ **系统温度读取**：读取所有 thermal zone 的最高温度；检测到 root（`su`）时增强读取
- ✅ **BLE 直连散热器**：扫描并连接 `Flydigi` 系列设备（B6X 等），复用逆向得到的协议
- ✅ **自动温控（滞回）**：温度 ≥ 启动阈值 → 开机并按档位映射调速；温度 ≤ 停止阈值 → 真正停机（关风扇 + 关制冷），滞回带防频繁启停
- ✅ **手动模式**：风速滑杆（120~255）+ 开启/关闭
- ✅ **后台持续温控**：前台服务（`connectedDevice` 类型）+ 常驻通知，退后台/锁屏继续工作
- ✅ **断开保留档位**：BLE 断开后散热器固件保持最后档位；App 重启可重连
- ✅ **参数持久化**：DataStore 保存启动/停止阈值、采样间隔

## 安装

```bash
# 构建（见下方"构建"）后安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次启动需授权：
- **定位**（Android 11 及以下扫描 BLE 需要；12+ 不需要但保留兼容）
- **附近设备**（`BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`，Android 12+）
- **通知**（Android 13+，前台服务通知）

App 会尝试用 root 增强温度读取；无 root 时直接读 `/sys/class/thermal`（world-readable）也能工作。

## 使用

1. 打开 App，点 **连接**（自动扫描并连接 `Flydigi` 设备）
2. 保持 **自动温控** 开启，App 会按系统温度调度
3. 需要手动控制时，关闭自动温控，拖动风速滑杆或点开启/关闭
4. **温控设置** 可调整启动/停止阈值与采样间隔
5. **停止服务** 后前台服务退出（散热器保持最后档位）

## 权限清单

| 权限 | 用途 |
|---|---|
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` | BLE 扫描与连接（Android 12+ 运行时） |
| `ACCESS_FINE_LOCATION` | 旧版 Android 扫描 BLE（maxSdk 30） |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` | 前台温控服务 |
| `POST_NOTIFICATIONS` | 常驻通知 |

## 已知限制

- **MVP 未启用设备"手动/超频模式"命令（0x22/0x23）**：该命令布局在反编译中存在分支歧义，MVP 用已验证的 `0x11`（风速）/`0x12`（制冷）/`0x25`（风扇）/`0x26`（制冷开关）实现全部控制。待实机抓包（Android Bluetooth HCI snoop）后可扩展。
- 自动温控只调风速/制冷档位与开关，不切换设备的运行模式标识。
- 扫描策略：连接到第一个发现的 `Flydigi` 设备（多设备场景待扩展）。

## 协议参考

详见 `docs/B6X-BLE协议逆向分析.md`（飞智游戏厅 7.2.10.7 逆向）。

- GATT：服务 `0000ffe0`，写 `0000ffe1`，读/通知 `0000ffe4`，CCCD `00002902`
- 命令：`0x13` 请求状态、`0x11 xx` 风速、`0x12 xx` 制冷、`0x25 01/00` 风扇开关、`0x26 01/00` 制冷开关
- 写间隔 ≥35ms，`WRITE_TYPE_NO_RESPONSE`

## 构建

本机环境（aarch64 + qemu aapt2 wrapper 已配置）：

```bash
export JAVA_HOME=/home/yiyang-dev-pad/miniconda3/envs/eta-android-build
export ANDROID_HOME=/home/yiyang-dev-pad/Android/Sdk
cd FlydigiB6xTempCtrl
./gradlew assembleDebug
# 单元测试
./gradlew testDebugUnitTest
```

- AGP 9.3.1 / Gradle 9.6.1 / Kotlin 2.2.20 / compileSdk 35 / minSdk 26
- 产物：`app/build/outputs/apk/debug/app-debug.apk`

## 架构

```
UI (Compose) ⇄ ViewModel ⇄ ControlService(前台服务) ⇄ BleManager ⇄ B6X(BLE)
                        │
                        ├─ TempReader（/sys/class/thermal，root 增强）
                        └─ ThermalController（滞回状态机，可单测）
```

协议层（`WaspWingProtocol`）与温控状态机（`ThermalController`）为纯 Kotlin，可 JVM 单测。
