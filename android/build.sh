#!/usr/bin/env bash
set -euo pipefail
app_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$app_dir/.." && pwd)"
sdk_dir="${HALO_ANDROID_SDK:?Set HALO_ANDROID_SDK to your Android SDK}"
tools_dir="$sdk_dir/build-tools/android-15"
android_jar="$sdk_dir/platform/android-35/android.jar"
build_dir="$project_dir/build/android"
key_dir="$project_dir/build/key"
rm -rf -- "$build_dir/classes" "$build_dir/dex"
mkdir -p "$build_dir/classes" "$build_dir/dex" "$key_dir"
chmod 700 "$key_dir"
"$tools_dir/aapt" package -f -M "$app_dir/AndroidManifest.xml" -S "$app_dir/res" -I "$android_jar" -F "$build_dir/resources.apk"
javac -source 8 -target 8 -Xlint:-options -classpath "$android_jar" -d "$build_dir/classes" "$app_dir"/src/dev/halo/visualiser/*.java
find "$build_dir/classes" -name '*.class' > "$build_dir/classes.list"
"$tools_dir/d8" --min-api 26 --lib "$android_jar" --output "$build_dir/dex" @"$build_dir/classes.list"
cp "$build_dir/resources.apk" "$build_dir/unsigned.apk"
/usr/bin/python - "$build_dir/unsigned.apk" "$build_dir/dex/classes.dex" <<'PY'
import sys,zipfile
with zipfile.ZipFile(sys.argv[1],'a') as apk:
    apk.write(sys.argv[2],'classes.dex',compress_type=zipfile.ZIP_STORED)
PY
"$tools_dir/zipalign" -f 4 "$build_dir/unsigned.apk" "$build_dir/aligned.apk"
if [[ ! -f "$key_dir/halo.jks" ]]; then
    keytool -genkeypair -keystore "$key_dir/halo.jks" -storepass android -keypass android -alias halo -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Halo Local, O=Halo, C=GB' -noprompt
    chmod 600 "$key_dir/halo.jks"
fi
"$tools_dir/apksigner" sign --ks "$key_dir/halo.jks" --ks-key-alias halo --ks-pass pass:android --key-pass pass:android --out "$app_dir/Visualiser.apk" "$build_dir/aligned.apk"
"$tools_dir/apksigner" verify --verbose "$app_dir/Visualiser.apk"
ls -lh "$app_dir/Visualiser.apk"
