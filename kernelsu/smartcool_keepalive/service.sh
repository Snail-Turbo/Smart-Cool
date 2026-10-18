#!/system/bin/sh

MODDIR=${0%/*}
PKG=com.yiyang.b6xctrl
SVC="$PKG/.service.ControlService"
LOG="$MODDIR/watchdog.log"

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG"
}

# ColorOS 会把第三方 BOOT_COMPLETED 标成 DEFER_BY_OPLUS，等系统启动完成再直接拉起。
n=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$n" -lt 120 ]; do
    sleep 5
    n=$((n + 1))
done
sleep 5

# DataStore 在 CE 存储里，用户解锁前启动服务会拿不到配置。
while ! dumpsys user 2>/dev/null | sed -n '/UserInfo{0:/,/State:/p' | grep -q 'RUNNING_UNLOCKED'; do
    sleep 5
done
sleep 2
log "user 0 unlocked"

dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1
cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1

start_service() {
    am start-foreground-service -n "$SVC" >/dev/null 2>&1
    log "start $SVC"
}

start_service

while true; do
    if ! pidof "$PKG" >/dev/null 2>&1; then
        start_service
    fi
    sleep 30
done
