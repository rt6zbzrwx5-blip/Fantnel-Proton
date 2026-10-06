⚠️ Legal Note: This repository contains deployment scripts and adaptation configurations written by me. Fantnel itself is licensed under GPL-3.0. If you distribute packages containing the Fantnel program binary, you must provide the source code of Fantnel at the same time.# Fantnel 盒子 · Android APK

把 `Fantnel.linux.x64.zip`（.NET 10 程序）打包成的安卓应用。
**APK 里已经内置了一整套 Linux 和 .NET 运行时，不需要 root，不需要 Termux。**

## 装

1. 用文件管理器点开 `Fantnel盒子.apk`
2. 系统会问是否允许「安装未知应用」，允许即可
3. 装完打开

## 用

1. **打开 App** → 状态显示「未安装」
   - 首次会弹一次「存储权限」请求，**建议允许**：允许后日志会写到 `/sdcard/Download/fantnel-log.txt`，方便排查问题
2. 点 **「开始运行」** → 开始安装：解包 Linux 系统 + .NET 运行时 + Fantnel 程序
   （进度条会走，大约 3～8 分钟，手机慢一点正常）
3. 装完后状态变成「已安装」，按钮**还是「开始运行」** → **再点一次就直接启动**
4. 服务起来后会自动打开内置界面；也可以随时点「打开界面」
5. 有「停止」和「看日志」按钮，出问题先看日志

> 第一次启动后 Fantnel 自己还会联网下载 Fantnel 2.0 和它的前端界面，属正常现象。

## APK 里都装了什么

| 内容 | 说明 |
|---|---|
| Linux 用户态 | Ubuntu 24.04 base（aarch64），共 2500+ 文件 |
| 运行方式 | proot（aarch64，取自 Termux），免 root |
| .NET 运行时 | ASP.NET Core Runtime 10.0.12，linux-arm64 原生版 |
| 待运行程序 | 你给的 Fantnel.linux.x64.zip 原包 |
| 体积 | APK 约 78 MB，安装后占用约 250 MB |

## 做了哪些兼容处理

原包是给 x64 Linux 准备的，直接放到 arm64 手机上跑不起来。踩掉 4 个坑：

1. **架构不匹配**：`Fantnel.dll` 是纯 IL 字节码，但 PE 头被标成了 AMD64，.NET 在 arm64 上会拒绝加载。
   → 安装时原地把机器码字段改成 AnyCPU（不改任何代码逻辑）。
   实测 Fantnel 自己更新到 2.0 后主程序集本来就是 ARM64，无需再改。
2. **安卓 10+ 的 W^X**：应用数据目录里的可执行文件被禁止执行，而 proot 必须执行 rootfs 里的 `/opt/dotnet/dotnet`。
   → `targetSdkVersion` 设为 28（Termux 同款做法），并把 proot 本体放进 `lib/arm64-v8a/` 走原生库目录。
3. **首次运行会自我更新**：这个 1.7.0 一启动就会把自己更新到 2.0 然后退出，还会重新拉起自己。
   → App 会监测 `Fantnel.dll` 的修改时间，发现更新过就自动等 4 秒重新启动，不会误报失败。
4. **`/proc/net/tcp` 读不到**：安卓不允许应用读这个文件，而 Fantnel 用它找空闲端口，读不到会直接崩。
   → 启动时用一个「只有表头」的假文件 bind 覆盖 `/proc/net/tcp` 和 `/proc/net/tcp6`。
5. **本机主机名解析失败**：.NET 的 `Dns.GetHostEntry` 在 rootfs 里解析本机会抛异常，导致服务刚起来程序就退出。
   → rootfs 内预装 `libnss-myhostname`，并在 `nsswitch.conf` 的 hosts 行加上 `myhostname`。

启动时会在 4 套 proot 参数组合之间自动降级重试（含 `PROOT_NO_SECCOMP`），一种不行换下一种。
6. **解包报 `tar 返回 1`**（第一版实测踩到）：安卓应用没有 root，而压缩包里所有条目的属主都是 root，
   toybox tar 会对每个条目尝试 chown，全部失败后整体返回 1（文件其实已经解出来了）。
   → 解包改用 `tar -x -o -z -f`（`-o` = 忽略属主），并且**不再把返回码当失败依据**，
   而是检查关键文件是否就位；同时把 tar 的输出和解包日志都记录下来。


### 解包环节踩过的坑（都已修）

| # | 问题 | 现象 | 处理 |
|---|---|---|---|
| 1 | 归档里有**硬链接** | 安卓禁止应用建硬链接，`perl`/`gunzip` 丢失且 tar 返回 1 | 打包时加 `--hard-dereference`，硬链接变成实体文件，tar 返回 0 |
| 2 | 校验判据写死 | `.NET` 包解到 `opt/dotnet` 里没有 `bin/bash`，必然误判失败 | 每个包传各自的校验标记 |
| 3 | 外部命令管道没读干净 | tar 输出超 8000 字符就停止读取，可能永久卡死 | 继续读满管道，只是不再记录 |
| 4 | 只看单个文件 | 解包解一半也可能蒙混过关 | 关键文件 + **节点数≥95%** 双重校验 |
| 5 | 失败残留临时文件 | 几十 MB 垃圾留在应用目录 | `try/finally` 清理 |
| 6 | 没做空间预检 | 空间不足时报 tar 的晦涩错误 | 预检 400MB 并给明确提示 |


### 启动环节踩过的坑（都已修）

| # | 问题 | 现象 | 处理 |
|---|---|---|---|
| 7 | **rootfs 缺 ICU 国际化库** | .NET 起来后立刻 `Couldn't find a valid ICU package`，FailFast 退出 | rootfs 里补进 `libicuuc/libicui18n/libicudata/libicutu`（.so.74）。APK 因此增大约 15MB |
| 8 | 重装新 APK 后不会重新解包 | `.installed` 标记还在，改了 rootfs 也白改 | 加「载荷版本号」，版本不匹配自动重新解包 |
| 9 | 启动重试过多 | 4 套方案 × 6 轮，要跑十几分钟 | 一旦日志里出现 .NET 自身的报错（说明 proot 已通），立即停止换方案；总轮数降到 2 |
| 10 | **rootfs 缺根证书** | HTTPS 下载会全部证书校验失败（ICU 修好后才会暴露） | 补进 `/etc/ssl/certs/ca-certificates.crt` + `openssl.cnf` + `/usr/lib/ssl` |
| 11 | 缺时区数据 | 时间会显示成 UTC | 补进 `tzdata`，`/etc/localtime` 指向 `Asia/Shanghai` |

> 第 7 条是 .NET 在精简 Linux 上的经典坑：Ubuntu base 默认不带 ICU，而 .NET 从启动就初始化全球化。

另外 proot 的 `DT_NEEDED` 已从 `libtalloc.so.2` 原地改成 `libtalloc.so`，避免部分安卓版本只提取 `.so` 结尾的原生库。

## 目录

```
Fantnel盒子/
├── Fantnel盒子.apk     安装包（已签名）
├── README.md           本文件
└── 源码/
    ├── MainActivity.java   应用全部逻辑（界面 + 安装 + 启动）
    ├── AndroidManifest.xml
    ├── build.sh            构建脚本
    └── 构建说明.md
```

运行时数据都在 App 私有目录 `/data/data/com.fantnel.box/files/`：
`rootfs/`（Linux 系统）、`rootfs/opt/dotnet/`、`rootfs/opt/fantnel/`、`shm/`、`tmp/`。卸载 App 就全清了。
---

## 附：关于「脱盒」与端口（第 19 轮补充）

**脱盒**：盒子 = 网易《我的世界》官方启动器；脱盒 = 不依赖官方启动器直接拉起游戏。
Fantnel 就是这类项目，盒子提供 Linux 运行环境，服务器仍是网易的。

### 局域网 Minecraft 端口

盒子把代理只绑在 `127.0.0.1:25565`（从 `/api/server/get` 读到的 `"ip":"127.0.0.1"` 证实），
所以局域网其他设备连不上。App 现在会**额外绑定「局域网 IP:25565」**并转发到 `127.0.0.1:25565`：

| 从哪连 | 填什么 |
|---|---|
| 本机启动器 | `127.0.0.1:25565` |
| 局域网其他设备 | `<手机IP>:25565`（如 192.168.2.206:25565） |

绑的是**具体局域网 IP**而不是 `0.0.0.0`，这样不会和盒子已占用的 `127.0.0.1:25565` 抢端口。

### 已知问题：插件与账号（非本 APK 问题）

- 盒子用 4399 共享账号（`/api/gameaccount/current`），共享账号被顶号很常见，
  被顶后表现为 `原因22: 您的帐号在另一处登录` → 资源下载 401
- `/api/pluginstore/get` 会卡满 30 秒后超时，异常来自 `Nirvana.WPFLauncher\Http\X19Extensions.cs`，
  即从网易 CDN `x19.fp.ps.netease.com` 拉插件列表超时 → 插件列表为空 → 代理无法启动
- 那个 401 的 URL 在盒外直接访问同样 401，可排除打包环境问题

### 顺带适配：IPv6

本机 IPv6 不通但 DNS 返回 AAAA，.NET 会优先尝试导致超时。
已加 `/etc/gai.conf` 让 `getaddrinfo` 优先返回 IPv4。

