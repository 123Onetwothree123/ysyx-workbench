# BusyBox vendor 说明

本目录是 BusyBox 1.36.1 官方源码，作为 ysyx-workbench 的普通文件跟踪，
不是 git submodule，也不在构建时联网下载。

## 来源

```text
上游地址:
  https://busybox.net/downloads/busybox-1.36.1.tar.bz2

镜像地址（官方源不可用时）:
  https://mirrors.tuna.tsinghua.edu.cn/busybox/busybox-1.36.1.tar.bz2

SHA-256:
  b8cc24c9574d809e7279c3be349795c5d5ceb6fdf19ca709f80cde50e47de314
```

当前树内文件直接从上述官方 tarball 解压得到；没有修改 BusyBox 源码。

## 许可

BusyBox 使用 GPL-2.0，具体条款见本目录的 `LICENSE`。

## Kconfig

NPC 的 rootfs 选择在：

```text
npc/kconfig/Kconfig.rootfs
```

当前只提供：

```text
ROOTFS_BUSYBOX
```

默认源码路径：

```text
vendor/busybox
```

## 构建

在 `npc/` 下打开 `CONFIG_ROOTFS_ENABLE=y` 后运行：

```bash
make rootfs
```

构建脚本 `npc/scripts/build_rootfs_busybox.sh` 使用
`O=npc/build/rootfs/busybox` 做 out-of-tree 构建，目标为静态
RV64GC/lp64d，不会在源码目录生成 `.config` 或对象文件。产物、
`.info` 摘要和清理方式见 `npc/README.md` 的“BusyBox rootfs /
initramfs”一节。构建过程不下载任何文件，也不编译内核。

## 更新方式

1. 下载新版本 tarball，并校验 SHA-256。
2. 解压后替换本目录内容。
3. 同步更新 `npc/kconfig/Kconfig.rootfs` 中的
   `ROOTFS_BUSYBOX_VERSION` 和本文件的来源/校验信息。
4. 不要提交 BusyBox 的构建产物（`.config`、`busybox`、`_install` 等）。
