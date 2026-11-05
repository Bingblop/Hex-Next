"""Manual-helper and package-visibility boundaries, without a device or root commands."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

APP = Path(__file__).resolve().parents[1] / "android/app/src/main"
JAVA = APP / "java/com/hexnext/themer"
NS = "{http://schemas.android.com/apk/res/android}"


class ManualRootSetupTest(unittest.TestCase):
    def test_exact_helper_packages_are_visible_without_broad_queries(self):
        manifest = ET.parse(APP / "AndroidManifest.xml").getroot()
        packages = {p.get(NS + "name") for p in manifest.findall("queries/package")}
        self.assertTrue({"df.root", "me.weishu.kernelsu"}.issubset(packages))
        permissions = {p.get(NS + "name") for p in manifest.findall("uses-permission")}
        self.assertNotIn("android.permission.QUERY_ALL_PACKAGES", permissions)
        self.assertNotIn("android.permission.REQUEST_INSTALL_PACKAGES", permissions)
        self.assertNotIn("android.permission.REBOOT", permissions)

    def test_manual_download_confirmation_precedes_browser_handoff(self):
        source = (JAVA / "ui/ManualRootSetup.kt").read_text()
        self.assertIn("AlertDialog(", source)
        self.assertIn('Text("Open upstream releases")', source)
        self.assertIn("uri.openUri(tool.releasesUrl)", source)
        self.assertIn("LifecycleEventEffect(Lifecycle.Event.ON_RESUME)", source)
        self.assertNotIn("ACTION_INSTALL_PACKAGE", source)
        self.assertNotIn("PackageInstaller", source)
        self.assertNotIn("ProcessBuilder", source)

    def test_no_background_apk_resolution_is_needed(self):
        tools = (JAVA / "device/RootTools.kt").read_text()
        vm = (JAVA / "ui/MainViewModel.kt").read_text()
        self.assertNotIn("HttpURLConnection", tools)
        self.assertNotIn("latestApkUrl", tools)
        self.assertNotIn("latestApkUrl", vm)
        self.assertIn("mitschud/DirtyFrag", tools)
        self.assertNotIn("diabl0w/DFRoot", tools)
        self.assertIn('equals("DFRoot", ignoreCase = true)', (JAVA / "ui/ManualRootSetup.kt").read_text())

    def test_root_request_is_explicit_and_serialized(self):
        manager = (JAVA / "shizuku/ShizukuManager.kt").read_text()
        self.assertIn("fun requestRootAccess()", manager)
        self.assertIn('ServiceTasks.tryStart("Requesting root access")', manager)
        self.assertIn("requestAccess = true", manager)
        self.assertIn("_rootRequest.compareAndSet", manager)

    def test_old_guarantees_and_mandatory_shizuku_copy_are_gone(self):
        main = (JAVA / "ui/MainScreen.kt").read_text()
        setup = (JAVA / "ui/onboarding/OnboardingScreen.kt").read_text()
        for claim in ("does not trip Knox", "void your warranty", "banking apps keep working",
                      "the only compatible option", "work on most phones"):
            self.assertNotIn(claim, main)
        self.assertNotIn("Root alone cannot apply a theme", setup)
        self.assertIn("ManualRootSetup", main)
        self.assertIn("ManualRootSetup", setup)

    def test_dirtyfrag_steps_and_user_screenshots_are_present(self):
        guide = (JAVA / "ui/MainScreen.kt").read_text()
        self.assertIn("Open DirtyFrag", guide)
        self.assertIn("Root button at the bottom", guide)
        self.assertIn("Working with Jailbreak mode enabled", guide)
        self.assertIn("Superuser tab at the bottom", guide)
        self.assertIn("lost after a full reboot", guide)
        self.assertIn("Theme overlays created as UID 1000", guide)
        self.assertIn("UID 0 is a fallback", guide)
        for image in ("hx_guide_dirtyfrag", "hx_guide_kernelsu_home"):
            self.assertIn("R.drawable." + image, guide)
            data = (APP / "res/drawable-nodpi" / (image + ".jpg")).read_bytes()
            self.assertTrue(data.startswith(b"\xff\xd8"))
            self.assertGreater(len(data), 1000)

    def test_uid2000_routes_before_the_root_preview_shell(self):
        studio = (JAVA / "ui/studio/StudioScreen.kt").read_text()
        route = studio.index("if (Capability.of(shizuku) == Capability.SHELL)")
        self.assertLess(route, studio.index("viewModel.studioPreview.collectAsStateWithLifecycle()"))
        screen = (JAVA / "ui/studio/MonetOnlyScreen.kt").read_text()
        for forbidden in ("PhonePreview(", "PreviewPeek(", "PackPicker(", "NavigationBar(",
                          "ColorEngineCard(", "FontCard("):
            self.assertNotIn(forbidden, screen)
        self.assertIn("viewModel::applyMonetPalette", screen)
        self.assertIn("viewModel::restoreMonetPalette", screen)
        self.assertIn("UID 2000", screen)

    def test_legacy_uid2000_entry_points_return_before_overlay_work(self):
        applier = (JAVA / "theme/ThemeApplier.kt").read_text()
        apply_start = applier.index("private fun applyAll(")
        apply = applier[apply_start:applier.index("private fun applyValuesPass(")]
        gate = apply.index("if (service.uid == 2000)")
        early_return = apply.index("return", gate)
        self.assertLess(early_return, apply.index("DirectoryPackResolver("))
        self.assertLess(early_return, apply.index("clearOtherOwner("))
        remove = applier[applier.index("fun remove(service:"):]
        gate = remove.index("if (service.uid == 2000)")
        self.assertLess(remove.index("return MonetSeed.restore", gate), remove.index("service.disableHexOverlays()"))
        self.assertIn("check(capability == Capability.ROOT)", applier)

    def test_shizuku_system_uid_is_preferred_before_launching_root(self):
        manager = (JAVA / "shizuku/ShizukuManager.kt").read_text()
        service = manager[manager.index("suspend fun service()"):manager.index("private suspend fun shizukuService")]
        self.assertLess(service.index("permittedShizukuUid() == 1000"), service.index("SystemLauncher.ensure("))
        self.assertIn("SystemLauncher.connectedUid", manager)
        self.assertNotIn("if (SystemLauncher.alive()) return ShizukuState.Ready(uid = 1000", manager)

    def test_setup_cannot_approve_superuser_through_accessibility(self):
        manifest = ET.parse(APP / "AndroidManifest.xml").getroot()
        permissions = {p.get(NS + "name") for p in manifest.findall("uses-permission")}
        self.assertNotIn("android.permission.BIND_ACCESSIBILITY_SERVICE", permissions)
        for service in manifest.findall("application/service"):
            self.assertNotEqual("android.permission.BIND_ACCESSIBILITY_SERVICE", service.get(NS + "permission"))


if __name__ == "__main__":
    unittest.main()
