#!/bin/bash
set -e
B=/root/build
APK=$B/apk
cd $APK
echo "[1/6] 编译资源"
LD_LIBRARY_PATH=$B/tools/lib $B/tools/bin/aapt2 compile --dir res -o compiled.zip 2>/dev/null
echo "[2/6] 链接 (含 assets)"
rm -f base.apk
LD_LIBRARY_PATH=$B/tools/lib $B/tools/bin/aapt2 link -o base.apk \
  -I $B/android.jar --manifest AndroidManifest.xml -A assets \
  --min-sdk-version 24 --target-sdk-version 28 --version-code 1 --version-name 1.0 compiled.zip 2>/dev/null
echo "[3/6] javac"
rm -rf classes dexout; mkdir -p classes dexout
javac -nowarn -source 8 -target 8 -bootclasspath $B/android.jar -encoding UTF-8 -d classes src/com/fantnel/box/MainActivity.java
echo "[4/6] d8"
java -Xmx2g -cp $B/btlib/d8.jar com.android.tools.r8.D8 --min-api 24 --lib $B/android.jar --release \
  --output dexout classes/com/fantnel/box/*.class
echo "[5/6] 打包"
rm -f unsigned.apk
python3 - <<'PY'
import zipfile, os
zin=zipfile.ZipFile('/root/build/apk/base.apk')
zout=zipfile.ZipFile('/root/build/apk/unsigned.apk','w',zipfile.ZIP_DEFLATED)
for it in zin.infolist():
    zi=zipfile.ZipInfo(it.filename, date_time=it.date_time)
    zi.compress_type=it.compress_type; zi.external_attr=it.external_attr
    zout.writestr(zi, zin.read(it.filename))
zout.write('/root/build/apk/dexout/classes.dex','classes.dex',zipfile.ZIP_DEFLATED)
d='/root/build/apk/lib/arm64-v8a'
for f in sorted(os.listdir(d)):
    zout.write(os.path.join(d,f),'lib/arm64-v8a/'+f,zipfile.ZIP_DEFLATED)
zout.close(); zin.close()
PY
echo "[6/6] 签名"
rm -f $B/FantnelBox.apk
java -jar $B/btlib/apksigner.jar sign --ks $B/fantnel.jks --ks-pass pass:fantnel123 \
  --key-pass pass:fantnel123 --ks-key-alias fantnel \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out $B/FantnelBox.apk unsigned.apk
ls -la $B/FantnelBox.apk
