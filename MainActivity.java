package com.fantnel.box;

import android.app.Activity;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {

    static final String MARK = ".installed";
    /** 载荷版本：rootfs / .NET / 程序 有任何变化都要改这个，App 会自动重新解包 */
    static final String PAYLOAD_VER = "2024-10-04.16";
    static final String URL_RE = "https?://(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0):(\\d+)";

    File rootfs, tmpDir, installDir, nativeDir, shmDir;
    File extLog;
    final java.util.List<File> logFiles = new java.util.ArrayList<File>();
    long lastFlush = 0;
    long lastPush = 0;
    boolean installed = false;
    Process proc;
    String webUrl = null;

    TextView status, logView;
    Button mainBtn, stopBtn, openBtn, logBtn, testBtn;
    ProgressBar bar;
    WebView web;
    FrameLayout main;
    ScrollView logScroll;
    final Handler ui = new Handler(Looper.getMainLooper());
    volatile boolean appStarted = false;
    volatile boolean icuMissing = false;

    /* 局域网端口转发：盒子只绑 127.0.0.1:25565，局域网连不上。
       我们绑到「局域网 IP:25565」——和 127.0.0.1:25565 是不同的地址，不会冲突；
       收到的连接转发到盒子的 127.0.0.1:25565。这样本机和局域网都能连。 */
    static final int LAN_PORT = 25565;
    volatile boolean lanOn = false;
    volatile String lanAddr = null;
    java.net.ServerSocket lanServer = null;
    StringBuilder logBuf = new StringBuilder();
    final StringBuilder fullLog = new StringBuilder();

    int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    /** 优先写到 /sdcard/Download（方便外部读取），没权限就退到应用外部目录 */
    File pickExtLog() {
        logFiles.clear();
        try {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                File dl = new File("/sdcard/Download");
                if (!dl.exists()) dl = new File(android.os.Environment.getExternalStorageDirectory(), "Download");
                if (dl.exists()) logFiles.add(new File(dl, "fantnel-log.txt"));
                File ws = new File("/sdcard/工作区");
                if (ws.exists() || ws.mkdirs()) logFiles.add(new File(ws, "fantnel-log.txt"));
            }
        } catch (Throwable t) { }
        try { File e = getExternalFilesDir(null); if (e != null) { e.mkdirs(); logFiles.add(new File(e, "fantnel-log.txt")); } } catch (Throwable t) { }
        return logFiles.isEmpty() ? null : logFiles.get(0);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 1001) {
            extLog = pickExtLog();
            log("存储权限结果: " + (res.length > 0 && res[0] == android.content.pm.PackageManager.PERMISSION_GRANTED ? "已允许" : "被拒绝"));
            log("日志落点: " + (logFiles.isEmpty() ? "(无)" : logFiles.toString()));
            flushLog();
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        File fd = getFilesDir();
        rootfs = new File(fd, "rootfs");
        tmpDir = new File(fd, "tmp");       tmpDir.mkdirs();
        installDir = new File(fd, "install"); installDir.mkdirs();
        shmDir = new File(fd, "shm"); shmDir.mkdirs();
        nativeDir = new File(getApplicationInfo().nativeLibraryDir);
        buildUi();
        boolean marked = new File(rootfs, MARK).exists();
        String haveVer = readTextSafe(new File(rootfs, ".payload"));
        installed = marked && PAYLOAD_VER.equals(haveVer);
        killStaleBox();
        startLogHeartbeat();

        // 清理上次残留的盒子进程：重装 APK 后旧进程会变孤儿，
        // 既占着端口，又可能和新实例同时登录同一账号导致顶号
        if (marked && !installed) log("载荷已更新（" + haveVer + " -> " + PAYLOAD_VER + "），需要重新解包");
        refreshState();
        try {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{ android.Manifest.permission.WRITE_EXTERNAL_STORAGE }, 1001);
            }
        } catch (Throwable t) { }
        extLog = pickExtLog();
        log("===== Fantnel 盒子启动 =====");
        log("Android " + android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")");
        log("机型 " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        log("ABI " + java.util.Arrays.toString(android.os.Build.SUPPORTED_ABIS));
        log("安装目录: " + fd.getAbsolutePath());
        log("原生库目录: " + nativeDir.getAbsolutePath());
        log("日志落点: " + (logFiles.isEmpty() ? "(无)" : logFiles.toString()));
        for (String f : new String[]{"libproot.so", "libprootloader.so", "libtalloc.so", "libandroid-shmem.so"}) {
            File x = new File(nativeDir, f);
            log("  " + f + " 存在=" + x.exists() + " 可执行=" + x.canExecute() + " 大小=" + x.length());
        }
        log("已安装=" + installed);
    }

    /* ---------------- 界面 ---------------- */
    void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101418);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("Fantnel 盒子");
        title.setTextColor(0xFF8ED84A);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(0xFFE0D2AE);
        status.setPadding(0, dp(6), 0, dp(6));
        root.addView(status);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress(0);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, dp(10));

        mainBtn = mkBtn("开始运行", 0xFF2E7D32);
        stopBtn = mkBtn("停止", 0xFF7B2318);
        openBtn = mkBtn("打开界面", 0xFF1565C0);
        logBtn  = mkBtn("看日志", 0xFF4A4A4A);
        testBtn = mkBtn("自检", 0xFF6A4A00);
        row.addView(mainBtn); row.addView(stopBtn); row.addView(openBtn); row.addView(logBtn); row.addView(testBtn);
        hs.addView(row);
        root.addView(hs);

        main = new FrameLayout(this);
        root.addView(main, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        web.setWebViewClient(new WebViewClient());
        web.setBackgroundColor(0xFF101418);
        web.setVisibility(View.GONE);
        main.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextColor(0xFFB9C4A8);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        main.addView(logScroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        mainBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onMain(); } });
        stopBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { stopApp(); } });
        openBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showWeb(); } });
        logBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { toggleLog(); } });
        testBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { new Thread(new Runnable(){ public void run(){ selfTest(); }}).start(); } });
    }

    Button mkBtn(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14);
        b.setBackgroundColor(color);
        b.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        b.setPadding(dp(14), 0, dp(14), 0);
        return b;
    }

    void toggleLog() {
        boolean showLog = logScroll.getVisibility() != View.VISIBLE;
        logScroll.setVisibility(showLog ? View.VISIBLE : View.GONE);
        web.setVisibility(showLog ? View.GONE : View.VISIBLE);
        logBtn.setText(showLog ? "看界面" : "看日志");
    }

    void showWeb() {
        if (webUrl == null) { toast("服务还没启动"); return; }
        web.loadUrl(webUrl);
        logScroll.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        logBtn.setText("看日志");
    }

    void refreshState() {
        if (installed) {
            status.setText(proc != null ? ("运行中 · " + (webUrl == null ? "启动中…" : webUrl))
                                        : "已安装，点「开始运行」直接启动");
            openBtn.setEnabled(webUrl != null);
            stopBtn.setEnabled(proc != null);
        } else {
            status.setText("未安装：点「开始运行」开始安装（解包 Linux 与 .NET 运行时）");
            openBtn.setEnabled(false);
            stopBtn.setEnabled(false);
        }
    }

    /* ---------------- 主按钮 ---------------- */
    void onMain() {
        if (proc != null) { showWeb(); return; }
        if (!installed) startInstall(); else startApp();
    }

    void startInstall() {
        mainBtn.setEnabled(false);
        new Thread(new Runnable() { public void run() {
            try {
                doInstall();
                installed = true;
                runOnUiThreadSafe("安装完成，正在自检…");
                selfTest();
                ui.post(new Runnable() { public void run() {
                    mainBtn.setEnabled(true);
                    bar.setProgress(1000);
                    refreshState();
                    toast("安装完成，再点一次「开始运行」即可启动");
                }});
            } catch (final Throwable t) {
                ui.post(new Runnable() { public void run() {
                    mainBtn.setEnabled(true);
                    status.setText("安装失败: " + t.getMessage());
                    toast("安装失败，看日志");
                }});
                log("!! 安装失败: " + t);
            }
        }}).start();
    }

    void doInstall() throws Exception {
        // 0) 空间预检 + 清掉上次可能残留的半成品
        long free = getFilesDir().getUsableSpace();
        log("可用空间 " + (free / 1048576) + " MB");
        if (free < 400L * 1048576L)
            throw new IOException("存储空间不足：解包需要约 400MB，当前可用 " + (free / 1048576) + "MB");
        killStaleBox();
        if (rootfs.exists()) deleteTree(rootfs);
        rootfs.mkdirs();
        // 1) rootfs
        extractAssetTar("rootfs.tar.gz", rootfs, "bin/bash,usr/bin/env,lib/aarch64-linux-gnu/libc.so.6,usr/lib/aarch64-linux-gnu/libicuuc.so.74,etc/ssl/certs/ca-certificates.crt,opt/fakeproc/libfakeproc.so", 3958, 0, 550);
        // 2) .NET 运行时
        File opt = new File(rootfs, "opt/dotnet");
        extractAssetTar("dotnet.tar.gz", opt, "dotnet,shared/Microsoft.NETCore.App,shared/Microsoft.AspNetCore.App", 346, 550, 900);
        // 3) Fantnel 程序
        File app = new File(rootfs, "opt/fantnel");
        extractAssetZip("fantnel.zip", new File(rootfs, "opt"), "fantnel/Fantnel.dll", 167, 900, 970);
        log("程序解包完成");
        // 4) 修补 Fantnel.dll 的 PE 机器码（x64 -> AnyCPU），使其能在 arm64 上加载
        patchDll(new File(app, "Fantnel.dll"));
        // 5) 配置文件
        File etc = new File(rootfs, "etc");
        etc.mkdirs();
        new File(etc, "hosts").delete();
        new File(etc, "resolv.conf").delete();
        StringBuilder hosts = new StringBuilder();
        hosts.append("127.0.0.1\tlocalhost\n::1\tlocalhost ip6-localhost ip6-loopback\n");
        String hn = androidHostname();
        if (hn != null && hn.length() > 0) { hosts.append("127.0.0.1\t").append(hn).append("\n"); log("本机主机名: " + hn); }
        writeText(new File(etc, "hosts"), hosts.toString());
        // nsswitch 必须包含 myhostname，否则 .NET 解析本机名会失败
        File nssw = new File(etc, "nsswitch.conf");
        String cur = nssw.exists() ? readText(nssw) : "";
        if (cur.indexOf("myhostname") < 0) {
            if (cur.indexOf("hosts:") >= 0) cur = cur.replaceAll("(?m)^hosts:.*$", "hosts:          files myhostname dns");
            else cur = cur + "\nhosts:          files myhostname dns\n";
            writeText(nssw, cur);
            log("已写入 nsswitch.conf (myhostname)");
        }
        writeText(new File(etc, "resolv.conf"), "nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n");
        // 6) 假的 /proc/net/tcp（安卓不允许应用读取，用假文件让服务选到端口）
        String hdr = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n";
        writeText(new File(tmpDir, "fake_tcp"), hdr);
        writeText(new File(tmpDir, "fake_tcp6"), hdr);
        // 7) 各种目录
        new File(rootfs, "dev").mkdirs();
        new File(rootfs, "proc").mkdirs();
        new File(rootfs, "sys").mkdirs();
        new File(rootfs, "root").mkdirs();
        new File(rootfs, "tmp").mkdirs();
        writeText(new File(rootfs, MARK), "ok\n");
        writeText(new File(rootfs, ".payload"), PAYLOAD_VER);
        step("安装完成", 1000);
    }

    void step(final String s, final int permille) {
        ui.post(new Runnable() { public void run() { status.setText(s); bar.setProgress(permille); } });
    }

    /* ---------------- 解包 ---------------- */
    long assetSize(String name) {
        try { return getAssets().openFd(name).getLength(); } catch (Throwable t) { return 0; }
    }

    void extractAssetTar(String asset, File dest, String marker, int expectNodes, int from, int to) throws Exception {
        dest.mkdirs();
        // 先解压成临时文件（安卓自带 tar 处理符号链接最可靠）
        File tf = new File(installDir, asset);
        step("释放 " + asset + " …", from);
        long copied = copyAsset(asset, tf, from, to);
        log(asset + " 落地 " + (copied / 1048576) + " MB");
        step("解包 " + asset + " …", to);
        try {
            // -o：忽略归档里的属主信息。安卓应用没有 root，chown 到 root 会失败，
            // toybox tar 会因为每个条目的 chown 失败而返回 1（文件其实是解出来了）。
            String out = runTool(new String[]{"/system/bin/tar", "-x", "-o", "-z", "-f",
                    tf.getAbsolutePath(), "-C", dest.getAbsolutePath()});
            if (out.trim().length() > 0) log("tar 输出: " + out.trim().replace("\n", " | "));
            log("tar 返回码=" + lastToolRc);
            checkExtract(asset, dest, marker, expectNodes, out);
        } finally {
            tf.delete();
        }
    }

    /** 解包完整性校验：关键文件必须存在，且节点数达到预期 */
    void checkExtract(String asset, File dest, String marker, int expectNodes, String toolOut) throws IOException {
        String missing = null;
        for (String m : marker.split(",")) {
            if (m.trim().length() > 0 && !new File(dest, m.trim()).exists()) missing = m.trim();
        }
        int got = countNodes(dest);
        log(asset + " 校验: 节点 " + got + "/" + expectNodes + (missing == null ? "" : "  缺 " + missing));
        if (missing != null)
            throw new IOException(asset + " 解包不完整：缺少 " + missing + "（tar rc=" + lastToolRc + "）" + toolOut.trim());
        if (expectNodes > 0 && got < expectNodes * 95L / 100L)
            throw new IOException(asset + " 解包不完整：只解出 " + got + " 个节点，预期约 " + expectNodes + "（tar rc=" + lastToolRc + "）" + toolOut.trim());
        log("解包完成 " + asset + "（完整）");
    }

    void extractAssetZip(String asset, File dest, String marker, int expectNodes, int from, int to) throws Exception {
        dest.mkdirs();
        File tf = new File(installDir, asset);
        copyAsset(asset, tf, from, to);
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(tf), 1 << 16));
        ZipEntry e;
        byte[] buf = new byte[1 << 16];
        while ((e = zis.getNextEntry()) != null) {
            File out = new File(dest, e.getName());
            if (e.isDirectory()) { out.mkdirs(); continue; }
            File p = out.getParentFile(); if (p != null) p.mkdirs();
            FileOutputStream fos = new FileOutputStream(out);
            int n; while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
        }
        zis.close();
        tf.delete();
        checkExtract(asset, dest, marker, expectNodes, "");
        step("程序解包完成", to);
    }

    long copyAsset(String asset, File dst, int from, int to) throws Exception {
        AssetManager am = getAssets();
        InputStream in = am.open(asset);
        long total = assetSize(asset);
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[1 << 20];
        long done = 0; int lastPct = -1;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (total > 0) {
                int pct = (int) (done * 100 / total);
                if (pct != lastPct && pct % 2 == 0) {
                    lastPct = pct;
                    step("释放 " + asset + " " + pct + "%", from + (to - from) * pct / 100);
                }
            }
        }
        out.close(); in.close();
        return done;
    }

    int lastToolRc = 0;

    /** 跑外部命令：必须把管道读干净，否则子进程写满管道会卡死 */
    String runTool(String[] cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()), 8192);
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() < 8000) sb.append(line).append('\n');   // 继续读，只是不再记
            }
            lastToolRc = p.waitFor();
            return sb.toString();
        } catch (Throwable t) {
            lastToolRc = -1;
            return "[启动失败] " + t;
        }
    }

    /** 统计目录树节点数（不跟随符号链接，避免重复计数和死循环） */
    static int countNodes(File f) {
        int n = 1;
        try {
            if (android.system.Os.lstat(f.getAbsolutePath()).st_mode == android.system.OsConstants.S_IFLNK) return n;
        } catch (Throwable t) { }
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) { if (n > 40000) break; n += countNodes(k); }
        return n;
    }

    /* ---------------- 修补 dll ---------------- */
    void patchDll(File dll) throws Exception {
        if (!dll.exists()) { log("!! 找不到 Fantnel.dll"); return; }
        RandomAccessFile raf = new RandomAccessFile(dll, "rw");
        byte[] hdr = new byte[4];
        raf.seek(0x3c); raf.readFully(hdr);
        int pe = (hdr[0] & 255) | ((hdr[1] & 255) << 8) | ((hdr[2] & 255) << 16) | ((hdr[3] & 255) << 24);
        raf.seek(pe + 4);
        byte[] m = new byte[2]; raf.readFully(m);
        int machine = (m[0] & 255) | ((m[1] & 255) << 8);
        if (machine == 0x8664) {
            // 只有 x64 包需要补：它的 PE 头标了 AMD64，但代码是纯 IL，
            // 在 arm64 上会被 .NET 拒绝加载。官方 arm64 包(0xAA64)保持原样不动。
            m[0] = (byte) 0x4c; m[1] = 0x01;      // IMAGE_FILE_MACHINE_I386 == AnyCPU
            raf.seek(pe + 4); raf.write(m);
            log("已修补 Fantnel.dll 机器码 0x" + Integer.toHexString(machine) + " -> AnyCPU");
        } else {
            log("Fantnel.dll 机器码 0x" + Integer.toHexString(machine) + "（原生匹配，无需修补）");
        }
        raf.close();
    }

    String androidHostname() {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/sys/kernel/hostname")));
            String s = r.readLine(); r.close();
            if (s != null && s.trim().length() > 0) return s.trim();
        } catch (Throwable t) { }
        try { return java.net.InetAddress.getLocalHost().getHostName(); } catch (Throwable t) { }
        return null;
    }

    static String readTextSafe(File f) {
        try { return readText(f).trim(); } catch (Throwable t) { return ""; }
    }

    static String readText(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int n = in.read(b); in.close();
        return new String(b, 0, Math.max(0, n), "UTF-8");
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    static void writeText(File f, String s) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(s.getBytes("UTF-8"));
        o.close();
    }

    /* ---------------- 自检 ---------------- */
    void runOnUiThreadSafe(final String st) {
        ui.post(new Runnable() { public void run() { status.setText(st); } });
    }

    /** 构造 proot 命令；guestArgs 是 rootfs 里要执行的程序 */
    List<String> buildCmd(String[] flags, String[] guestArgs) {
        List<String> c = new ArrayList<String>();
        c.add(new File(nativeDir, "libproot.so").getAbsolutePath());
        c.add("-r"); c.add(rootfs.getAbsolutePath());
        for (String f : flags) c.add(f);
        c.add("-b"); c.add("/dev");
        c.add("-b"); c.add(shmDir.getAbsolutePath() + ":/dev/shm");
        c.add("-b"); c.add("/proc");
        c.add("-b"); c.add("/sys");
        c.add("-b"); c.add(new File(tmpDir, "fake_tcp").getAbsolutePath() + ":/proc/net/tcp");
        c.add("-b"); c.add(new File(tmpDir, "fake_tcp6").getAbsolutePath() + ":/proc/net/tcp6");
        c.add("-w"); c.add("/opt/fantnel");
        c.add("/usr/bin/env"); c.add("-i");
        c.add("PATH=/opt/dotnet:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin");
        c.add("HOME=/root"); c.add("TERM=xterm"); c.add("LANG=C.UTF-8");
        // 关键修复：安卓不允许应用读 /proc/net/tcp，而程序用它找空闲端口。
        // proot 的 -b 对 /proc 下的文件不生效，改用 LD_PRELOAD 在 libc 层重定向。
        c.add("LD_PRELOAD=/opt/fakeproc/libfakeproc.so");
        c.add("DOTNET_ROOT=/opt/dotnet");
        c.add("DOTNET_gcServer=0");
        c.add("DOTNET_CLI_TELEMETRY_OPTOUT=1");
        // 省内存模式。注意：不要再设 GCHeapHardLimit —— 实测它会改变 GC 布局并
        // 触发 "Reserving 256 GiB for the regions range failed" 导致 CoreCLR 起不来。
        // 内存问题改用 runtimeconfig 里的 System.GC.Server=false 解决（见 fixRuntimeConfig）。
        // 不加 DOTNET_GCConserveMemory：实测它会让 GC 更频繁、启动更卡。
        // 内存问题靠 runtimeconfig 的 System.GC.Server=false 解决。
        for (String g : guestArgs) c.add(g);
        return c;
    }

    ProcessBuilder prootBuilder(List<String> c) {
        ProcessBuilder pb = new ProcessBuilder(c);
        pb.directory(rootfs);
        pb.redirectErrorStream(true);
        java.util.Map<String, String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", nativeDir.getAbsolutePath());
        env.put("PROOT_LOADER", new File(nativeDir, "libprootloader.so").getAbsolutePath());
        env.put("PROOT_TMP_DIR", tmpDir.getAbsolutePath());
        env.put("HOME", getFilesDir().getAbsolutePath());
        env.put("PATH", "/system/bin:/system/xbin");
        return pb;
    }

    /** 跑一条命令并把输出收回来 */
    String runCapture(List<String> c, int timeoutSec) {
        try {
            Process p = prootBuilder(c).start();
            final StringBuilder sb = new StringBuilder();
            final Process pp = p;
            Thread rd = new Thread(new Runnable() { public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(pp.getInputStream()), 4096);
                    String l;
                    while ((l = r.readLine()) != null) { sb.append(l).append('\n'); if (sb.length() > 20000) break; }
                } catch (Throwable t) { }
            }});
            rd.setDaemon(true); rd.start();
            if (!p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) { p.destroy(); rd.join(1500); return sb + "\n[超时 " + timeoutSec + "s]"; }
            rd.join(2500);
            return sb.toString();
        } catch (Throwable t) { return "[异常] " + t; }
    }

    /** 逐层自检：proot 能不能跑 -> Linux 能不能进 -> .NET 能不能用 */
    boolean selfTest() {
        if (!installed) { log("自检: 还没安装"); return false; }
        boolean allOk = true;
        log("======== 自检开始 ========");

        // 第 1 层：proot 本体能否执行
        File px = new File(nativeDir, "libproot.so");
        log("[1] proot: " + px.getAbsolutePath() + " 存在=" + px.exists() + " 可执行=" + px.canExecute());
        String v = runCapture(new ArrayList<String>(java.util.Arrays.asList(
            px.getAbsolutePath(), "--version")), 15);
        boolean p1 = v.indexOf("proot") >= 0 && v.indexOf("5.") >= 0;
        log("[1] proot 输出: " + v.trim().replace('\n', ' '));
        log("[1] " + (p1 ? "通过" : "失败 —— proot 无法执行")); if (!p1) allOk = false;

        // 第 2 层：能否进入 Linux 并执行 /bin/sh
        String sh = runCapture(buildCmd(new String[]{"-0", "--ashmem-memfd"},
            new String[]{"/bin/sh", "-c", "echo ROOTFS_OK; uname -m; cat /etc/nsswitch.conf | grep hosts"}), 25);
        boolean p2 = sh.indexOf("ROOTFS_OK") >= 0;
        log("[2] rootfs 输出: " + sh.trim().replace("\n", " | "));
        log("[2] " + (p2 ? "通过" : "失败 —— 进不去 Linux")); if (!p2) allOk = false;

        // 第 2.5 层：关键文件盘点
        String[] keys = {
            "usr/lib/aarch64-linux-gnu/libicuuc.so.74",
            "usr/lib/aarch64-linux-gnu/libicudata.so.74",
            "etc/ssl/certs/ca-certificates.crt",
            "usr/share/zoneinfo/Asia/Shanghai",
            "etc/nsswitch.conf", "etc/hosts", "etc/resolv.conf",
            "opt/fakeproc/libfakeproc.so", "opt/fakeproc/fake_tcp",
            "opt/dotnet/dotnet", "opt/fantnel/Fantnel.dll"
        };
        log("[2.5] 关键文件盘点:");
        boolean allKey = true;
        for (String k : keys) {
            File f = new File(rootfs, k);
            if (!f.exists()) allKey = false;
            log("   " + (f.exists() ? "OK  " : "缺失 ") + k + (f.exists() ? (" (" + f.length() + ")") : ""));
        }
        if (!allKey) { allOk = false; log("[2.5] 有文件缺失，解包可能不完整"); }

        // 第 3 层：.NET 运行时
        String dn = runCapture(buildCmd(new String[]{"-0", "--ashmem-memfd"},
            new String[]{"/opt/dotnet/dotnet", "--list-runtimes"}), 60);
        boolean p3 = dn.indexOf("Microsoft.NETCore.App") >= 0;
        log("[3] dotnet 输出: " + dn.trim().replace("\n", " | "));
        log("[3] " + (p3 ? "通过" : "失败 —— .NET 起不来")); if (!p3) allOk = false;

        log("======== 自检" + (allOk ? "全部通过，可以启动了" : "未通过，请把以上日志发出来") + " ========");
        flushLog();
        final boolean ok = allOk;
        ui.post(new Runnable() { public void run() {
            status.setText(ok ? "自检通过 · 点「开始运行」启动" : "自检未通过，请点「看日志」");
            toast(ok ? "自检通过" : "自检未通过");
        }});
        return allOk;
    }

    /* ---------------- 启动 ---------------- */
    void startApp() {
        if (proc != null) return;
        webUrl = null;
        mainBtn.setEnabled(false);
        new Thread(new Runnable() { public void run() {
            String[][] flagSets = {
                {"-0", "--ashmem-memfd"},
                {"-0"},
                {"-0", "--ashmem-memfd", "--sysvipc"},
                {"-0", "--link2symlink"}
            };
            boolean[] noSeccomp = { false, false, true, false };
            final File dll = new File(rootfs, "opt/fantnel/Fantnel.dll");
            fixRuntimeConfig(new File(rootfs, "opt/fantnel"));
            patchWebUi(new File(rootfs, "opt/fantnel"));
            scheduleWebUiPatch(new File(rootfs, "opt/fantnel"));
            int restarts = 0;
            for (int pass = 0; pass < 2; pass++) {
                boolean retryPass = false;
                for (int i = 0; i < flagSets.length; i++) {
                    long before = dll.lastModified();
                            if (pass == 0 && i == 0) log("--- 尝试启动 ---");
                    else log("--- 启动方案 " + (i + 1) + "/" + flagSets.length + " (第 " + (pass + 1) + " 轮) ---");
                    if (launchOnce(flagSets[i], noSeccomp[i])) return;
                    if (appStarted) {
                        log(">>> proot 已正常工作，是程序自身报错，停止切换方案");
                        ui.post(new Runnable() { public void run() { status.setText("程序启动报错，请点「看日志」"); }});
                        return;
                    }
                    long after = dll.lastModified();
                    if (after != before && restarts < 3) {
                        restarts++;
                        log(">>> 检测到程序自我更新：盒子会自己重启，这里只等待它的输出，");
                        log(">>> 不重复启动（同时跑两个实例会让网易账号互相顶号）");
                        ui.post(new Runnable() { public void run() { status.setText("程序正在自我更新，等待它重启…"); }});
                        long dl = System.currentTimeMillis() + 180000;
                        while (System.currentTimeMillis() < dl) {
                            if (webUrl != null) { return; }
                            if (proc == null) break;
                            try { Thread.sleep(500); } catch (InterruptedException ie) { break; }
                        }
                        log("等待自我更新后的服务超时");
                        return;
                    }
                    ui.post(new Runnable() { public void run() { status.setText("运行中 · 换用备用方案…"); }});
                }
                if (!retryPass) {
                    log("--- 本轮方案都用过了，等 6 秒再试一轮 ---");
                    try { Thread.sleep(6000); } catch (InterruptedException ie) { }
                }
            }
            if (icuMissing) {
                log(">>> 检测到 ICU 加载失败，改用 invariant 全球化模式再试一次");
                icuMissing = false;
                if (launchOnce(new String[]{"-0", "--ashmem-memfd"}, false, true)) return;
                if (launchOnce(new String[]{"-0"}, false, true)) return;
            }
            log("!! 所有启动方案都失败了");
            flushLog();
            ui.post(new Runnable() { public void run() {
                status.setText("启动失败，请点「看日志」并把日志发出来");
                mainBtn.setEnabled(true);
            }});
        }}).start();
    }

    /** 返回 true 表示这次启动成功（要么拿到了地址，要么进程稳定存活） */
    boolean launchOnce(String[] flags, boolean noSeccomp) { return launchOnce(flags, noSeccomp, false); }

    boolean launchOnce(String[] flags, boolean noSeccomp, boolean invariant) {
        try {
            List<String> c = buildCmd(flags, new String[]{"/opt/dotnet/dotnet", "/opt/fantnel/Fantnel.dll"});
            ProcessBuilder pb = prootBuilder(c);
            if (noSeccomp) pb.environment().put("PROOT_NO_SECCOMP", "1");
            if (invariant) {
                // 万一 ICU 仍然加载不了，降级到不变文化模式，至少让程序能跑起来
                List<String> inv = new ArrayList<String>();
                for (String x : c) inv.add(x);
                int at = inv.indexOf("/opt/dotnet/dotnet");
                inv.add(at, "DOTNET_SYSTEM_GLOBALIZATION_PREDEFINED_CULTURES_ONLY=0");
                inv.add(at, "DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1");
                pb = prootBuilder(inv);
                if (noSeccomp) pb.environment().put("PROOT_NO_SECCOMP", "1");
                log("已启用 invariant 全球化降级");
            }
            log("命令: " + c.toString());
            final Process pp = pb.start();
            proc = pp;
            log("命令: " + c.toString());

            Thread reader = new Thread(new Runnable() { public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(pp.getInputStream()), 8192);
                    String line;
                    Pattern pat = Pattern.compile(URL_RE);
                    while ((line = r.readLine()) != null) {
                        line = line.replace('\r', ' ');
                        log(line);
                        // 出现这些说明 proot 已经跑通、是程序自身在报错，换 proot 方案没有意义
                        if (line.contains("ICU") || line.contains("libicu")) icuMissing = true;
                        if (line.contains("Process terminated") || line.contains("at System.")
                                || line.contains("Unhandled exception") || line.contains("Couldn't find")
                                || line.contains("应用启动成功") || line.contains("Fantnel")) appStarted = true;
                        Matcher m = pat.matcher(line);
                        if (m.find() && webUrl == null) {
                            webUrl = "http://127.0.0.1:" + m.group(1);
                            log(">>> 服务地址: " + webUrl);
                            ui.post(new Runnable() { public void run() {
                                status.setText("运行中 · " + webUrl);
                                openBtn.setEnabled(true);
                                showWeb();
                                toast("已启动");
                            }});
                            startLanForward();
                            startLanWatchdog();
                        }
                    }
                } catch (Throwable t) { }
            }});
            reader.setDaemon(true);
            reader.start();

            // 观察 30 秒：拿到地址=成功；进程还活着=当作成功；进程死了=失败，换方案
            long deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline) {
                if (webUrl != null) { ui.post(new Runnable(){ public void run(){ refreshState(); }}); return true; }
                if (!pp.isAlive()) {
                    int rc = pp.waitFor();
                    log("进程退出, 返回码 " + rc + " —— 该方案不可用");
                    proc = null;
                    return false;
                }
                try { Thread.sleep(400); } catch (InterruptedException ie) { }
            }
            ui.post(new Runnable() { public void run() { refreshState(); status.setText("运行中 · 启动服务…"); }});
            // 还在跑就算成功，挂上收尾
            reader.join();
            log("进程结束");
            proc = null;
            ui.post(new Runnable() { public void run() { refreshState(); }});
            return true;
        } catch (Throwable t) {
            proc = null;
            log("!! 启动异常: " + t);
            return false;
        }
    }

    void stopApp() {
        stopLanForward();
        if (proc == null) return;
        try { proc.destroy(); } catch (Throwable t) { }
        proc = null;
        webUrl = null;
        refreshState();
        log("已停止");
    }

    /* ---------------- 去掉插件检查 + 去掉错误红框 ----------------
       用户要求：不要检查插件，直接启动代理；界面上不要出现错误代码。
       1) Q() 原本「先 await z() 检查插件依赖，有错误就弹对话框」，
          而插件接口已在服务端下线，检查必然报错 → 弹红框 500。
          改成 Q(){T()}：跳过检查、直接启动代理。
       2) 红框渲染分支 b.value?(...) 恒为假，永不显示。
       3) 启动代理的 catch 也不显示原始错误。

       注意：官方包里【没有】resources 目录，前端资源是盒子首次运行时自己下载的，
       所以第一次启动时这里必然找不到文件 —— 必须靠 scheduleWebUiPatch() 轮询重试。 */
    boolean patchWebUi(File appDir) {
        boolean any = false;
        try {
            File dir = new File(appDir, "resources/static/assets");
            if (!dir.isDirectory()) return false;
            File[] fs = dir.listFiles();
            if (fs == null) return false;
            for (File f : fs) {
                String nm = f.getName();
                if (!nm.startsWith("ServerDetail") || !nm.endsWith(".js")) continue;
                String s = readText(f);
                if (s == null) continue;
                String orig = s;
                if (s.indexOf("async function Q(){T()}") < 0) {
                    s = s.replace(
                      "async function Q(){await z();const t=m.value.length>0||g.value.length>0,s=b.value!==null;t||s?S.value=!0:T()}",
                      "async function Q(){T()}");
                }
                if (s.indexOf("!1?(a(),n(\"div\",qe,") < 0) {
                    s = s.replace(
                      "b.value?(a(),n(\"div\",qe,[e(\"div\",Ke,d(b.value),1)])):",
                      "!1?(a(),n(\"div\",qe,[e(\"div\",Ke,d(b.value),1)])):");
                }
                s = s.replace(
                  "ve(f,h.value).then(t=>{i.value=t.msg}).catch(t=>{i.value=t.message})",
                  "ve(f,h.value).then(t=>{i.value=t.msg}).catch(t=>{i.value=\"正在启动代理中，请稍后……\"})");
                // 同一个界面上的「启动游戏」按钮也有同样的报错，一并去掉
                s = s.replace(
                  "ce(f,h.value).then(t=>{i.value=t.msg}).catch(t=>{i.value=t.message})",
                  "ce(f,h.value).then(t=>{i.value=t.msg}).catch(t=>{i.value=\"正在启动游戏中，请稍后……\"})");
                // 插件依赖检查函数整段换成空操作（不再发任何插件请求）
                s = s.replace(
                  "function z(){return new Promise(t=>{N.value=!0,b.value=null,de(f,v.value.gameVersion).then(s=>{if(s.code===1){const r=s.data.find(u=>u.mode===\"dependence\"),l=s.data.find(u=>u.mode===\"base\");m.value=r&&r.data?r.data:[],g.value=l&&l.data?l.data:[]}else m.value=[],g.value=[],b.value=s.msg;N.value=!1,t()}).catch(s=>{m.value=[],g.value=[],b.value=s.message,N.value=!1,t()})})}",
                  "function z(){return Promise.resolve()}");
                if (!s.equals(orig)) { writeText(f, s); any = true; }
            }
            if (any) log("✔ 已去掉插件检查与错误红框：" + appDir.getName() + "/resources/static/assets");
        } catch (Throwable t) {
            log("改前端失败: " + t);
        }
        return any;
    }

    /* 前端资源是盒子自己下载的，第一次启动时还没有。
       这里轮询等待资源到位->打补丁->立刻刷新 WebView，让补丁当场生效。 */
    volatile boolean webPatched = false;
    void scheduleWebUiPatch(final File appDir) {
        if (webPatched) return;
        Thread t = new Thread(new Runnable() { public void run() {
            for (int i = 0; i < 90 && !webPatched; i++) {
                try { Thread.sleep(8000); } catch (InterruptedException ie) { return; }
                if (patchWebUi(appDir)) {
                    webPatched = true;
                    log("前端补丁已生效，自动刷新界面");
                    ui.post(new Runnable() { public void run() {
                        try { if (web != null) web.reload(); } catch (Throwable t2) { }
                    }});
                    return;
                }
            }
        }});
        t.setDaemon(true); t.start();
    }

    /* ---------------- 日志心跳 ----------------
       盒子启动代理时会卡住几十秒。原来的日志只在启动流程结束后推送，
       卡死期间的现场看不到。这里每 6 秒把日志尾部推一次，卡住时就能定位到卡在哪一步。 */
    volatile boolean heartbeatOn = false;

    void startLogHeartbeat() {
        if (heartbeatOn) return;
        heartbeatOn = true;
        Thread t = new Thread(new Runnable() { public void run() {
            while (true) {
                try { Thread.sleep(6000); } catch (InterruptedException ie) { return; }
                try {
                    if (proc == null && webUrl == null) continue;
                    String s = fullLog.toString();
                    int n = s.length();
                    String tail = n > 7000 ? s.substring(n - 7000) : s;
                    String ts = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
                    pushLog("[[ 心跳 " + ts + " ]]\n" + tail);
                } catch (Throwable t2) { }
            }
        }});
        t.setDaemon(true); t.start();
    }


    /* ---------------- 修正 Fantnel.runtimeconfig.json ---------------- */
    /* 盒子自带 "System.GC.Server": true —— 服务器 GC 会按 CPU 核心数开多个堆，
       内存紧张时收不回来，整个进程卡死（表现为端口连得上但 HTTP 永不响应）。
       顺带把线程池最小线程数抬高，避免大量 30 秒超时的出网请求把线程池耗光。
       盒子自我更新会覆盖这个文件，所以每次启动前都重打一遍。 */
    void fixRuntimeConfig(File appDir) {
        try {
            File rc = new File(appDir, "Fantnel.runtimeconfig.json");
            if (!rc.exists()) { log("未找到 Fantnel.runtimeconfig.json，跳过"); return; }
            String s = readText(rc);
            if (s == null) return;
            String want = "\"System.GC.Server\": false";
            if (s.indexOf(want) >= 0) { log("runtimeconfig 已是修正状态"); return; }
            s = s.replace("\"System.GC.Server\": true", want);
            s = s.replace("\"System.GC.Server\":true", want);
            String inject = "\"System.GC.Concurrent\": true,\n"
                + "      \"System.Threading.ThreadPool.MinThreads\": 200,\n"
                + "      ";
            String cp = "\"configProperties\": {";
            int k = s.indexOf(cp);
            if (k >= 0) {
                s = s.substring(0, k) + cp + "\n      " + inject + s.substring(k + cp.length());
            } else {
                String ro = "\"runtimeOptions\": {";
                int r = s.indexOf(ro);
                if (r >= 0) {
                    int e = r + ro.length();
                    s = s.substring(0, e) + "\n    \"configProperties\": {\n      " + inject + "\n    }," + s.substring(e);
                }
            }
            writeText(rc, s);
            log("已修正 Fantnel.runtimeconfig.json：关闭 Server GC、堆限 512MB、抬高线程池");
        } catch (Throwable t) {
            log("修 runtimeconfig 失败: " + t);
        }
    }

    /* ---------------- 清理残留进程 ---------------- */
    void killStaleBox() {
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c",
                    "pkill -f 'Fantnel.dll' 2>/dev/null; pkill -f 'libproot.so' 2>/dev/null; true")
                    .redirectErrorStream(true).start();
            p.waitFor();
            log("已清理上一次残留的盒子进程");
        } catch (Throwable t) { }
    }

    /* ---------------- 局域网转发实现 ----------------
       盒子只绑 127.0.0.1:25565，局域网连不上；这里绑「局域网 IP:25565」转发过去。
       绑具体 IP 而不是 0.0.0.0，是为了不和盒子已占用的 127.0.0.1:25565 抢端口。
       注意：手机换 WiFi / DHCP 续约后 IP 会变，所以必须持续盯着并重绑，
       否则转发器会一直挂在旧 IP 上，局域网就断了。 */
    volatile String lanBoundIps = "";
    final java.util.List<java.net.ServerSocket> lanServers = new java.util.ArrayList<java.net.ServerSocket>();

    java.util.List<String> findLanIps() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis = java.net.NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (ni == null || !ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                if (n != null && (n.startsWith("rmnet") || n.startsWith("dummy") || n.startsWith("tun"))) continue;
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        if (!out.contains(ip)) out.add(ip);
                    }
                }
            }
        } catch (Throwable t) { log("枚举网卡失败: " + t); }
        return out;
    }

    void stopLanForward() {
        lanOn = false; lanAddr = null; lanBoundIps = "";
        for (java.net.ServerSocket s : lanServers) { try { s.close(); } catch (Throwable t) { } }
        lanServers.clear();
        try { if (lanServer != null) lanServer.close(); } catch (Throwable t) { }
        lanServer = null;
    }

    void startLanForward() {
        if (lanOn) return;
        java.util.List<String> ips = findLanIps();
        if (ips.isEmpty()) { log("局域网转发: 还没有局域网 IPv4，稍后重试"); return; }
        int ok = 0;
        StringBuilder sb = new StringBuilder();
        for (String ip : ips) {
            try {
                java.net.ServerSocket ss = new java.net.ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new java.net.InetSocketAddress(java.net.InetAddress.getByName(ip), LAN_PORT), 50);
                lanServers.add(ss);
                ok++;
                if (sb.length() > 0) sb.append("  ");
                sb.append(ip).append(":").append(LAN_PORT);
                final java.net.ServerSocket fss = ss;
                Thread acc = new Thread(new Runnable() { public void run() {
                    while (lanOn) {
                        try {
                            final java.net.Socket c = fss.accept();
                            Thread t2 = new Thread(new Runnable() { public void run() { relay(c); }});
                            t2.setDaemon(true); t2.start();
                        } catch (Throwable t) { break; }
                    }
                }});
                acc.setDaemon(true); acc.start();
            } catch (Throwable t) {
                log("绑 " + ip + ":" + LAN_PORT + " 失败: " + t);
            }
        }
        if (ok > 0) {
            lanOn = true;
            lanBoundIps = ips.toString();
            lanAddr = sb.toString();
            log(">>> 局域网转发已开启: " + lanAddr + " -> 127.0.0.1:" + LAN_PORT);
            final String la = lanAddr;
            ui.post(new Runnable() { public void run() {
                status.setText("运行中 · 本机 127.0.0.1:" + LAN_PORT + "   局域网 " + la);
            }});
        }
    }

    /* 盯着 IP 变化，变了就重绑 —— 换 WiFi / DHCP 续约后靠这个自动恢复 */
    volatile boolean lanWatchOn = false;
    void startLanWatchdog() {
        if (lanWatchOn) return;
        lanWatchOn = true;
        Thread t = new Thread(new Runnable() { public void run() {
            while (lanWatchOn) {
                try { Thread.sleep(15000); } catch (InterruptedException ie) { return; }
                try {
                    String cur = findLanIps().toString();
                    if (lanOn && !cur.equals(lanBoundIps)) {
                        log("检测到局域网 IP 变化: " + lanBoundIps + " -> " + cur + "，重绑转发");
                        stopLanForward();
                        Thread.sleep(500);
                        startLanForward();
                    } else if (!lanOn) {
                        startLanForward();
                    }
                } catch (Throwable t2) { }
            }
        }});
        t.setDaemon(true); t.start();
    }

    void relay(java.net.Socket client) {
        java.net.Socket up = null;
        try {
            up = new java.net.Socket();
            up.connect(new java.net.InetSocketAddress("127.0.0.1", LAN_PORT), 5000);
            Thread t1 = pump(client, up), t2 = pump(up, client);
            t1.join(); t2.join();
        } catch (Throwable t) {
            // 盒子代理没起来时会走到这里
        } finally {
            try { if (up != null) up.close(); } catch (Throwable t) { }
            try { client.close(); } catch (Throwable t) { }
        }
    }

    Thread pump(final java.net.Socket from, final java.net.Socket to) {
        Thread t = new Thread(new Runnable() { public void run() {
            try {
                java.io.InputStream in = from.getInputStream();
                java.io.OutputStream out = to.getOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); out.flush(); }
            } catch (Throwable t) { }
            finally { try { to.shutdownOutput(); } catch (Throwable t) { } }
        }});
        t.setDaemon(true); t.start();
        return t;
    }

    /* ---------------- 工具 ---------------- */
    void log(final String s) {
        ui.post(new Runnable() { public void run() {
            logBuf.append(s).append('\n');
            if (logBuf.length() > 60000) logBuf.delete(0, 20000);
            fullLog.append(s).append('\n');
            if (fullLog.length() > 1500000) fullLog.delete(0, 500000);   // 只保留最近 1.5MB
            logView.setText(logBuf);
            logScroll.post(new Runnable() { public void run() { logScroll.fullScroll(View.FOCUS_DOWN); }});
            long now = System.currentTimeMillis();
            if (extLog != null && now - lastFlush > 800) { lastFlush = now; flushLog(); }
        }});
    }

    void flushLog() {
        String body = (fullLog.length() > 0 ? fullLog.toString() : logBuf.toString());
        for (File f : logFiles) {
            try {
                FileOutputStream o = new FileOutputStream(f, false);
                o.write(body.getBytes("UTF-8"));
                o.close();
                f.setReadable(true, false);
            } catch (Throwable t) { }
        }
        pushLog(body);
    }

    /** 把日志 POST 回开发机 127.0.0.1:8899，失败无所谓 */
    void pushLog(final String body) {
        long now = System.currentTimeMillis();
        if (now - lastPush < 2500) return;
        lastPush = now;
        Thread th = new Thread(new Runnable() { public void run() {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL("http://127.0.0.1:8899/fantnel-log").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(1500);
                c.setReadTimeout(1500);
                c.getOutputStream().write(body.getBytes("UTF-8"));
                c.getResponseCode();
                c.disconnect();
            } catch (Throwable t) { }
        }});
        th.setDaemon(true);
        th.start();
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override public void onBackPressed() {
        if (web.getVisibility() == View.VISIBLE) { toggleLog(); return; }
        super.onBackPressed();
    }

    @Override protected void onPause() {
        super.onPause();
        flushLog();
    }

    @Override protected void onDestroy() {
        stopApp();
        flushLog();
        super.onDestroy();
    }
}
