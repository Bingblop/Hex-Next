import os
import shutil
import subprocess
import zipfile
from typing import Dict, Optional, List
from .shizuku_bridge import ShizukuBridge

class RROApkBuilder:
    """
    Compiles standard signed Runtime Resource Overlay (RRO) APKs
    targeted for Samsung One UI apps using aapt2, d8, and apksigner.
    """

    def __init__(self, target_package: str, overlay_package: str, work_dir: str = "/tmp/rro_build"):
        self.target = target_package
        self.overlay_package = overlay_package
        self.work_dir = work_dir
        self.colors: Dict[str, str] = {}
        self.drawables: Dict[str, str] = {}
        self.bridge = ShizukuBridge()

    def set_color(self, name: str, value: str):
        self.colors[name] = value
        return self

    def build_apk(self, output_path: str) -> bool:
        """Builds and signs the overlay APK."""
        res_dir = os.path.join(self.work_dir, "res", "values")
        os.makedirs(res_dir, exist_ok=True)

        # Generate colors.xml
        colors_xml_path = os.path.join(res_dir, "colors.xml")
        with open(colors_xml_path, "w", encoding="utf-8") as f:
            f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n')
            for k, v in self.colors.items():
                f.write(f'    <color name="{k}">{v}</color>\n')
            f.write('</resources>\n')

        # Generate AndroidManifest.xml
        manifest_path = os.path.join(self.work_dir, "AndroidManifest.xml")
        with open(manifest_path, "w", encoding="utf-8") as f:
            f.write(f'''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{self.overlay_package}"
    android:versionCode="1"
    android:versionName="1.0">
    <overlay
        android:targetPackage="{self.target}"
        android:targetName="{self.target}"
        android:priority="1"
        android:isStatic="false" />
    <application android:hasCode="false" android:label="{self.overlay_package}" />
</manifest>
''')

        # Step 1: AAPT2 compile
        compiled_zip = os.path.join(self.work_dir, "res.zip")
        res_root = os.path.join(self.work_dir, "res")
        res_code = subprocess.run(["aapt2", "compile", "--dir", res_root, "-o", compiled_zip]).returncode
        if res_code != 0:
            return False

        # Step 2: AAPT2 link
        unaligned_apk = os.path.join(self.work_dir, "unaligned.apk")
        link_cmd = [
            "aapt2", "link",
            "-I", "/system/framework/framework-res.apk",
            "--manifest", manifest_path,
            "-o", unaligned_apk,
            compiled_zip
        ]
        if subprocess.run(link_cmd).returncode != 0:
            return False

        # Step 3: Keystore generation if needed
        keystore_path = os.path.join(self.work_dir, "debug.keystore")
        if not os.path.exists(keystore_path):
            keytool_cmd = [
                "keytool", "-genkeypair", "-v",
                "-keystore", keystore_path,
                "-alias", "debugkey",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "10000",
                "-storepass", "android",
                "-keypass", "android",
                "-dname", "CN=Android Debug,O=Android,C=US"
            ]
            subprocess.run(keytool_cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

        # Step 4: Sign APK
        sign_cmd = [
            "apksigner", "sign",
            "--ks", keystore_path,
            "--ks-pass", "pass:android",
            "--key-pass", "pass:android",
            "--out", output_path,
            unaligned_apk
        ]
        return subprocess.run(sign_cmd).returncode == 0

    def install_and_enable(self, apk_path: str) -> bool:
        """Installs via Shizuku/rish and enables overlay."""
        code, out, err = self.bridge.run(f"pm install -r {apk_path}")
        if code != 0 or "Success" not in out:
            print(f"Install failed: {out} {err}")
            return False
        code_en, _, _ = self.bridge.run(f"cmd overlay enable --user current {self.overlay_package}")
        return code_en == 0
