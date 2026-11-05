"""Static wiring: RestoreService hands its wake lock to RestoreTaskOwner.

`RestoreTaskOwnerTest` proves the owner's behaviour on the JVM (release on destruction before
the coroutine is dispatched, once-only release, primary failure kept through failing cleanup).
These checks pin that the service really routes its lease, its only launch and its destruction
through that owner instead of releasing the lock inside the launched job, which leaked a
15-minute partial wake lock when the service was destroyed before its job ran.
"""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
THEME = ROOT / "android/app/src/main/java/com/hexnext/themer/theme"
SERVICE = THEME / "RestoreService.kt"
OWNER = THEME / "RestoreTaskOwner.kt"
OWNER_TEST = ROOT / "android/app/src/test/java/com/hexnext/themer/theme/RestoreTaskOwnerTest.kt"


def member_body(source: str, signature: str, following: str) -> str:
    start = source.index(signature)
    return source[start:source.index(following, start)]


class RestoreServiceLifecycleCodeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.service = SERVICE.read_text(encoding="utf-8")
        self.owner = OWNER.read_text(encoding="utf-8")

    def test_service_launches_only_through_the_owner(self) -> None:
        self.assertIn("private val taskOwner = RestoreTaskOwner(scope)", self.service)
        self.assertNotIn("scope.launch", self.service)
        self.assertNotIn("import kotlinx.coroutines.launch", self.service)
        self.assertEqual(1, self.service.count("taskOwner.start("))

    def test_wake_lock_is_leased_to_the_owner_not_released_in_the_job(self) -> None:
        start = member_body(self.service, "override fun onStartCommand(", "override fun onDestroy(")
        acquire = start.index("acquire(15 * 60_000L)")
        self.assertLess(start.index("setReferenceCounted(false)"), acquire)
        lease = start.index("Closeable { if (acquired.isHeld) acquired.release() }")
        self.assertLess(acquire, lease)
        self.assertLess(lease, start.index("taskOwner.start(lease) {"))
        # The job itself only runs the restore on IO; the owner releases the lease.
        job = start[start.index("taskOwner.start(lease) {"):]
        self.assertIn("withContext(Dispatchers.IO)", job)
        self.assertIn("ThemeRestorer.restoreIfNeeded(applicationContext)", job)
        self.assertNotIn("release()", job)
        self.assertNotIn("stopSelf", job)
        # No duplicate owner while a restore runs: the running check precedes the acquisition.
        self.assertLess(start.index("taskOwner.running"), acquire)

    def test_destruction_closes_the_owner_before_super(self) -> None:
        destroy = member_body(self.service, "override fun onDestroy(", "private fun goForeground(")
        self.assertLess(destroy.index("taskOwner.close()"), destroy.index("super.onDestroy()"))
        self.assertIn("finally {", destroy)
        self.assertNotIn("scope.cancel()", self.service)

    def test_finish_callback_stops_foreground_then_self(self) -> None:
        finish = member_body(self.service, "private val taskOwner = RestoreTaskOwner(scope)", "override fun onBind(")
        self.assertLess(finish.index("stopForeground(STOP_FOREGROUND_REMOVE)"), finish.index("stopSelf()"))
        self.assertIn("} finally {", finish)

    def test_scope_has_a_supervisor_and_a_redacted_failure_boundary(self) -> None:
        scope = member_body(self.service, "private val scope = CoroutineScope(", "private val taskOwner")
        self.assertIn("SupervisorJob()", scope)
        self.assertIn("Dispatchers.Main.immediate", scope)
        self.assertIn("CoroutineExceptionHandler", scope)
        self.assertRegex(scope, r"error\.javaClass\.simpleName")
        self.assertNotIn("error.message", scope)

    def test_owner_keeps_the_restore_outcome_primary(self) -> None:
        launch = member_body(self.owner, "scope.launch {", "private fun finish()")
        self.assertIn("Closeable { finish() }.use {", launch)
        self.assertIn("Closeable { releaseResource() }.use { restore() }", launch)
        self.assertNotIn("finally", launch)
        finish = member_body(self.owner, "private fun finish()", "override fun close()")
        self.assertLess(finish.index("running = false"), finish.index("onFinished()"))

    def test_owner_releases_rejected_and_destroyed_resources(self) -> None:
        start = member_body(self.owner, "fun start(", "private fun finish()")
        self.assertIn("if (closed || running || !scope.isActive) {", start)
        self.assertIn("acquired?.close()", start)
        close = member_body(self.owner, "override fun close()", "private fun releaseResource()")
        self.assertLess(close.index("scope.cancel()"), close.index("releaseResource()"))
        self.assertRegex(self.owner, re.compile(r"@Synchronized\s+fun start\("))

    def test_behavioural_coverage_is_present(self) -> None:
        test = OWNER_TEST.read_text(encoding="utf-8")
        for case in (
            "destructionBeforeDispatchReleasesTheWakeResourceImmediately",
            "destructionDuringSuspensionReleasesBeforeTheCancelledTaskResumes",
            "cleanupFailureDoesNotHideTheOriginalFailureOrSkipTheFinish",
            "finishFailureDoesNotHideTheRestoreFailure",
            "finishFailureDuringDestructionKeepsTheCancellation",
            "duplicateStartClosesOnlyTheRejectedResource",
            "destroyedOwnerRejectsAndReleasesNewResources",
        ):
            self.assertIn(case, test)


if __name__ == "__main__":
    unittest.main()
