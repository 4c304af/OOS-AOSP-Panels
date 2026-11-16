"""Run display/gesture regressions with JDK 17 or newer.

Pass a classpath containing the compiled module classes, libxposed API and android.jar.
"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

if len(sys.argv) != 2:
    raise SystemExit("Usage: python host-tests/run.py <module-classes:libxposed-api.jar:android.jar>")
classpath = sys.argv[1]
sources = sorted((Path(__file__).parent / "src").rglob("*.java"))
with tempfile.TemporaryDirectory() as temp:
    subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-source", "17", "-target", "17",
                    "-cp", classpath, "-d", temp, *map(str, sources)], check=True)
    for suite in ("TestDisplay", "TestGesture", "TestBattery", "TestSplitHeader"):
        subprocess.run(["java", "-cp", temp + os.pathsep + classpath, "dev.oos.nativepanels." + suite], check=True)
