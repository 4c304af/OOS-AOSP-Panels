#!/usr/bin/env python3
"""Build with JDK 17+ and Android SDK 35 configured in ANDROID_HOME or local.properties."""
from pathlib import Path
import subprocess, shutil, hashlib
root=Path(__file__).resolve().parent
wrapper=root/"gradlew"
wrapper.chmod(0o755)
subprocess.run([str(wrapper),"--no-daemon","assembleRelease"],cwd=root,check=True)
apks=list((root/"build/outputs/apk/release").glob("*.apk"))
if len(apks)!=1: raise RuntimeError("Expected one release APK")
out=root/"dist/OOS_AOSP_Panels_v0.6.44-test.apk"
out.parent.mkdir(exist_ok=True)
shutil.copy2(apks[0],out)
print(out)
print("SHA256",hashlib.sha256(out.read_bytes()).hexdigest())
