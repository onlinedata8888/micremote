#!/bin/bash
# Builds dist/remote_r_googletv.apk from original/remote_r.apk + app/ sources.
# Needs: Java 17+ (with jdk.compiler), python3, curl, apt-get download (for dx) — Linux/macOS/WSL.
set -e
cd "$(dirname "$0")"; ROOT=$PWD; T=$ROOT/.tools; B=$ROOT/.build
mkdir -p $T $B
[ -f $T/apktool.jar ]     || curl -sSL -o $T/apktool.jar https://github.com/iBotPeaches/Apktool/releases/download/v2.10.0/apktool_2.10.0.jar
[ -f $T/uber-signer.jar ] || curl -sSL -o $T/uber-signer.jar https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar
[ -f $T/android.jar ]     || curl -sSL -o $T/android.jar https://raw.githubusercontent.com/Sable/android-platforms/master/android-33/android.jar
if [ ! -f $T/dx.jar ]; then (cd $T && apt-get download dalvik-exchange && dpkg -x dalvik-exchange*.deb dx && cp dx/usr/share/java/com.android.dx.jar dx.jar); fi
rm -rf $B/classes $B/dex && mkdir -p $B/classes $B/dex
java -m jdk.compiler/com.sun.tools.javac.Main -source 8 -target 8 -Xlint:-options -bootclasspath $T/android.jar -d $B/classes app/java/com/example/tvremote/*.java
java -cp $T/dx.jar com.android.dx.command.Main --dex --min-sdk-version=21 --output=$B/dex/classes4.dex $B/classes
rm -rf $B/work && java -jar $T/apktool.jar d -f -o $B/work original/remote_r.apk
python3 patch/patch_apk.py $B/work
python3 patch/rename_app.py $B/work
cp app/assets/remote.html $B/work/assets/remote.html
java -jar $T/apktool.jar b -f -o $B/unsigned.apk $B/work
python3 - <<PY
import zipfile
zi=zipfile.ZipFile("$B/unsigned.apk"); zo=zipfile.ZipFile("$B/withdex.apk","w",zipfile.ZIP_DEFLATED)
for i in zi.infolist(): zo.writestr(i, zi.read(i.filename), compress_type=i.compress_type)
zo.write("$B/dex/classes4.dex","classes4.dex"); zo.close()
PY
rm -rf $B/signed && java -jar $T/uber-signer.jar -a $B/withdex.apk --allowResign -o $B/signed
mkdir -p dist && cp $B/signed/withdex-aligned-debugSigned.apk dist/remote_r_googletv.apk
echo "DONE -> dist/remote_r_googletv.apk"
