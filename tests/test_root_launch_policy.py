"""Source-wiring regressions; behavioral timeout/cancellation tests run on the JVM."""
from pathlib import Path
import unittest

SOURCE = (Path(__file__).resolve().parents[1]
          / "android/app/src/main/java/com/hexnext/themer/shizuku")


class RootLaunchWiringTest(unittest.TestCase):
    def test_both_retry_levels_use_the_terminal_aware_policy(self):
        launcher = (SOURCE / "SystemLauncher.kt").read_text()
        self.assertIn("RootLaunchPolicy.firstSuccessful(listOf(1000, 0))", launcher)
        self.assertIn("RootLaunchPolicy.firstSuccessful(attempts)", launcher)
        self.assertIn("RootLaunchPolicy.runCommand", launcher)
        self.assertNotIn("p.waitFor(", launcher)

    def test_service_handshake_timeout_is_also_terminal(self):
        launcher = (SOURCE / "SystemLauncher.kt").read_text()
        timeout = launcher[launcher.index("if (binder == null)"):
                           launcher.index("val bound =")]
        self.assertIn("retryAllowed = false", timeout)

    def test_cancellation_is_not_swallowed_or_automatically_retried(self):
        launcher = (SOURCE / "SystemLauncher.kt").read_text()
        cancelled = launcher[launcher.index("catch (e: CancellationException)"):
                             launcher.index("service = started")]
        self.assertIn("failed = true", cancelled)
        self.assertIn("throw e", cancelled)
        manager = (SOURCE / "ShizukuManager.kt").read_text()
        background = manager[manager.index("fun startSystem()"):
                             manager.index("fun requestRootAccess()")]
        self.assertIn("catch (e: CancellationException)", background)
        self.assertIn("throw e", background)
        request = manager[manager.index("fun requestRootAccess()"):
                          manager.index("private fun permittedShizukuUid")]
        self.assertIn("catch (e: CancellationException)", request)
        self.assertIn("ServiceTasks.finish()", request)
        self.assertIn("requesting = false", request)

    def test_request_ui_state_and_slot_ownership_are_on_main(self):
        manager = (SOURCE / "ShizukuManager.kt").read_text()
        background = manager[manager.index("fun startSystem()"):
                             manager.index("fun requestRootAccess()")]
        self.assertIn("scope.launch(Dispatchers.Main.immediate)", background)
        request = manager[manager.index("fun requestRootAccess()"):
                          manager.index("private fun permittedShizukuUid")]
        dispatch = request.index("scope.launch(Dispatchers.Main.immediate)")
        self.assertLess(dispatch, request.index("_rootRequest.compareAndSet"))
        self.assertLess(dispatch, request.index("ServiceTasks.tryStart"))

    def test_only_known_platform_failures_are_caught_at_root_request_boundary(self):
        manager = (SOURCE / "ShizukuManager.kt").read_text()
        callers = manager[manager.index("fun startSystem()"):
                          manager.index("private fun permittedShizukuUid")]
        self.assertNotIn("catch (e: Exception)", callers)
        self.assertEqual(2, callers.count("catch (e: RemoteException)"))
        self.assertEqual(2, callers.count("catch (e: SecurityException)"))


if __name__ == "__main__":
    unittest.main()
