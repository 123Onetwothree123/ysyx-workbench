#!/bin/busybox sh
# 非交互 initramfs 测试入口：只依赖 BusyBox，用于证明 Debian 内核
# 已经完成 initramfs 解包、执行 /init 并进入用户态。
export PATH=/bin:/sbin:/usr/bin:/usr/sbin
/bin/busybox mkdir -p /proc /sys /dev /tmp
[ -c /dev/console ] || /bin/busybox mknod -m 600 /dev/console c 5 1 2>/dev/null
exec >/dev/console 2>&1
echo "NEMU_USER_INIT_START"
echo "NEMU_USER_PID=$$"
/bin/busybox uname -a
/bin/busybox mount -t proc proc /proc 2>/dev/null
/bin/busybox mount -t sysfs sysfs /sys 2>/dev/null
echo "--- /proc/cpuinfo ---"
/bin/busybox cat /proc/cpuinfo
echo "--- userland command ---"
/bin/busybox id
/bin/busybox echo "NEMU_USER_CMD_OK"
/bin/busybox cat /proc/version
echo "NEMU_USER_INIT_END"
/bin/busybox poweroff -f
/bin/busybox echo "NEMU_USER_POWEROFF_RETURNED"
/bin/busybox reboot -f
/bin/busybox echo "NEMU_USER_REBOOT_RETURNED"
while true; do /bin/busybox sleep 1; done
