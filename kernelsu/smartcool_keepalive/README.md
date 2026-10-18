# SmartCool Keepalive

ColorOS 17 在开机时会把第三方 `BOOT_COMPLETED` 标成
`DEFER_BY_OPLUS SUB_REASON: PRESSURE`，App 自己注册的 `BootReceiver`
可能一直不触发。这个 KernelSU 模块用 root 脚本做两件事：

1. 等 `sys.boot_completed=1` 且 user 0 解锁后，直接
   `am start-foreground-service` 拉起
   `com.yiyang.b6xctrl/.service.ControlService`；
2. 每 30 秒检查一次 App 进程，不在就重新拉起。

它不持有 BLE 连接，也不替代 App 里的温控逻辑。App 进程被拉起后会自己
重连散热器，因此中间有几秒断档；要零断档需要独立进程/原生 daemon，
不在这个模块的范围内。

## 安装

```bash
./kernelsu/build.sh
# 把 kernelsu/smartcool-keepalive.zip 放进平板，用 KernelSU 管理器安装
# 或：
adb push kernelsu/smartcool-keepalive.zip /data/local/tmp/
adb shell su -c '/data/adb/ksu/bin/ksud module install /data/local/tmp/smartcool-keepalive.zip'
```

安装后重启一次。模块目录：

```text
/data/adb/modules/smartcool_keepalive/
```

## 验证

```bash
adb shell pidof com.yiyang.b6xctrl
adb shell dumpsys activity services com.yiyang.b6xctrl | grep -E 'isForeground|crashCount'
adb shell su -c 'cat /data/adb/modules/smartcool_keepalive/watchdog.log'
```

## 卸载

在 KernelSU 管理器里删除模块，或：

```bash
adb shell su -c '/data/adb/ksu/bin/ksud module remove smartcool_keepalive'
```
