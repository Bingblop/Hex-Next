package com.hexnext.themer.safety

import com.hexnext.themer.IThemeService
import com.hexnext.themer.shizuku.ThemeService
import com.hexnext.themer.theme.FontInstaller

/**
 * Root-only boot-loop recovery that runs before the user unlocks the phone.
 *
 * [ApplyGuard] and [BootReceiver] only get a chance once Android has booted far enough to
 * deliver a broadcast. If a theme breaks System UI badly enough that the phone never gets
 * there, this hook still runs: it is a small root module (`/data/adb/modules/hexnext_early_recovery`,
 * listed as "Hex Next Early Recovery" in KernelSU / Magisk / APatch) whose `post-fs-data.sh`
 * starts the recovery script detached early in each boot. It needs persistent root; a
 * temporary root that is gone after reboot runs nothing. The script reads the guard's
 * device-protected
 * preferences and applies the same rule as [ApplyGuard.shouldEnterSafeMode]: an unconfirmed
 * apply less than 10 minutes old and this is the second restart since it. Then it flags the
 * Hex Next font module ([FontInstaller.MODULE_DIR]) for removal by the root manager, disables
 * every `HexNext*` overlay and leaves a marker in the module directory that the app reads through
 * the service ([wasTriggeredThisBoot]) so the safe-mode banner, the font removal and the SemWT
 * restore still happen.
 *
 * Every file lives in the root-only module directory: nothing is staged in, run from or written
 * to the shell-owned state directory, where any uid 2000 process could swap a file or plant a
 * symlink before root uses it.
 *
 * The module is installed with each root apply and removed on "It works" and on Remove theme.
 * If the app is uninstalled the module removes itself on the next boot.
 */
internal object EarlyRecoveryHook {

    private const val SCRIPT_NAME = "early-recovery.sh"
    private const val LAUNCHER_NAME = "post-fs-data.sh"
    private const val MODULE_PROP_NAME = "module.prop"
    private const val MODULE_ID = "hexnext_early_recovery"
    private const val MARKER_NAME = "early_safe_mode"
    private const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"

    /** `£` stands for `$` so the shell text needs no Kotlin escaping. */
    private val script = """
        #!/system/bin/sh
        set -f
        USER_ID=£(am get-current-user 2>/dev/null)
        case "£USER_ID" in ''|*[!0-9]*) USER_ID=0 ;; esac
        if [ -d /data/user_de/0/com.android.settings ] && [ ! -d /data/user_de/0/com.hexnext.themer ] &&
          [ ! -d "/data/user_de/£USER_ID/com.hexnext.themer" ]; then
          # The app was uninstalled: remove this module.
          rm -rf /data/adb/modules/hexnext_early_recovery
          exit 0
        fi
        PREFS="/data/user_de/£USER_ID/com.hexnext.themer/shared_prefs/hexnext_apply.xml"
        [ -r "£PREFS" ] || exit 0
        CONFIRMED=£(sed -n 's/.*<boolean name="confirmed" value="\([^"]*\)" \/>.*/\1/p' "£PREFS")
        [ "£CONFIRMED" = false ] || exit 0
        APPLIED=£(sed -n 's/.*<long name="appliedAt" value="\([^"]*\)" \/>.*/\1/p' "£PREFS")
        APPLY_BOOT=£(sed -n 's/.*<string name="applyBootId">\([^<]*\)<\/string>.*/\1/p' "£PREFS")
        BOOT_IDS=£(sed -n 's/.*<string name="bootIds">\([^<]*\)<\/string>.*/\1/p' "£PREFS")
        CURRENT_BOOT=£(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
        case "£APPLIED" in ''|*[!0-9]*) exit 0 ;; esac
        [ -n "£APPLY_BOOT" ] && [ -n "£CURRENT_BOOT" ] && [ "£CURRENT_BOOT" != "£APPLY_BOOT" ] || exit 0
        NOW=£(date +%s)
        AGE=£((NOW - APPLIED / 1000))
        [ "£AGE" -ge 0 ] && [ "£AGE" -le 600 ] || exit 0

        # Same rule as ApplyGuard.shouldEnterSafeMode: at least two boots since the apply, this
        # one included. bootIds holds the boots the app saw; this boot is not in it yet.
        LATER=0
        SEEN_CURRENT=0
        OLD_IFS=£IFS
        IFS=,
        for id in £BOOT_IDS; do
          [ -n "£id" ] && [ "£id" != "£APPLY_BOOT" ] || continue
          LATER=£((LATER + 1))
          [ "£id" = "£CURRENT_BOOT" ] && SEEN_CURRENT=1
        done
        IFS=£OLD_IFS
        [ "£SEEN_CURRENT" = 1 ] || LATER=£((LATER + 1))
        [ "£LATER" -ge 2 ] || exit 0

        # The font may be what keeps the phone restarting: the root manager drops the module at
        # the next boot. The boot receiver unmounts and deletes it as soon as this boot completes.
        FONT_DIR=${FontInstaller.MODULE_DIR}
        if [ -f "£FONT_DIR/module.prop" ] && grep -qx 'id=${FontInstaller.MODULE_ID}' "£FONT_DIR/module.prop"; then
          touch "£FONT_DIR/remove"
        fi

        TRIES=0
        while [ "£TRIES" -lt 12 ]; do
          LIST=£(cmd overlay list --user current 2>/dev/null)
          case "£LIST" in
            *HexNext*)
              printf '%s\n' "£LIST" | while IFS= read -r line; do
                case "£line" in *'['*']'*) ;; *) continue ;; esac
                id=
                for word in £line; do id=£word; done
                name=£{id#*:}
                case "£name" in
                  HexNext*)
                    case "£id" in *[!A-Za-z0-9._:-]*) continue ;; esac
                    cmd overlay disable --user current "£id" >/dev/null 2>&1
                    ;;
                esac
              done
              # Root-only module directory: no other uid can plant a link under this name.
              umask 022
              printf '%s\n' "£CURRENT_BOOT" > $MODULE_DIR/$MARKER_NAME
              exit 0
              ;;
          esac
          TRIES=£((TRIES + 1))
          sleep 5
        done
    """.trimIndent().replace('£', '$') + "\n"

    /** Module entry point: starts the recovery script detached so boot is never blocked. */
    private val launcher = """
        #!/system/bin/sh
        MODDIR=£{0%/*}
        if command -v setsid >/dev/null 2>&1; then
          nohup setsid sh "£MODDIR/$SCRIPT_NAME" >/dev/null 2>&1 &
        else
          nohup sh "£MODDIR/$SCRIPT_NAME" >/dev/null 2>&1 &
        fi
    """.trimIndent().replace('£', '$') + "\n"

    private const val MODULE_PROP =
        "id=$MODULE_ID\n" +
            "name=Hex Next Early Recovery\n" +
            "version=1.0.0\n" +
            "versionCode=1\n" +
            "author=Hex Next\n" +
            "description=Turns Hex Next overlays off if the phone keeps restarting right after an unconfirmed apply. Removed when you tap It works.\n"

    /**
     * Installs the module. Returns null when the service is not running as root (Shizuku shell or
     * system uid cannot write `/data/adb`), true when installed, false when root failed to.
     * Works for both a Shizuku root service and the in-process `su -c` service, so it checks
     * the effective uid of `exec` rather than the binder uid.
     *
     * Root writes every file straight into [MODULE_DIR] from the text held here, in one command:
     * a new `.tmp` file (umask 022) that gets owner, mode and then its name with `mv -f`. No copy
     * passes through the shell-owned state directory, so no uid 2000 process can swap in a script
     * of its own for root to run at every boot.
     */
    fun install(service: IThemeService): Boolean? = runCatching<Boolean?> {
        val id = service.exec("id -u")
        if (id.getString(ThemeService.KEY_OUTPUT).orEmpty().trim() != "0") return@runCatching null
        val writes = listOf(
            Triple(SCRIPT_NAME, script, "755"),
            Triple(LAUNCHER_NAME, launcher, "755"),
            // Last: the root manager lists the module once module.prop is in place.
            Triple(MODULE_PROP_NAME, MODULE_PROP, "644"),
        ).joinToString(" && ") { (name, text, mode) ->
            val tmp = "'$MODULE_DIR/$name.tmp'"
            "rm -f $tmp && printf '%s' ${singleQuoted(text)} > $tmp && chown 0:0 $tmp && chmod $mode $tmp && " +
                "mv -f $tmp '$MODULE_DIR/$name'"
        }
        val installed = service.exec(
            "umask 022; " +
                "if [ -e '$MODULE_DIR/disable' ] || [ -e '$MODULE_DIR/remove' ]; then " +
                "echo 'recovery module disabled or pending removal' >&2; exit 1; fi && " +
                "if [ -f '$MODULE_DIR/$MODULE_PROP_NAME' ] && " +
                "! grep -qx 'id=$MODULE_ID' '$MODULE_DIR/$MODULE_PROP_NAME'; then " +
                "echo 'module id conflict' >&2; exit 1; fi && " +
                "mkdir -p '$MODULE_DIR' && chown 0:0 '$MODULE_DIR' && chmod 755 '$MODULE_DIR' && " +
                writes,
        )
        installed.getInt(ThemeService.KEY_CODE, -1) == 0
    }.getOrDefault(false)

    /** Removes the module and its marker. Harmless when nothing is installed or without root. */
    fun uninstall(service: IThemeService) {
        runCatching {
            service.exec(
                "[ \"$(id -u)\" = 0 ] || exit 0; " +
                    "if [ -f '$MODULE_DIR/$MODULE_PROP_NAME' ] && grep -qx 'id=$MODULE_ID' '$MODULE_DIR/$MODULE_PROP_NAME'; then " +
                    "rm -rf '$MODULE_DIR'; fi; " +
                    "rm -f '/data/adb/service.d/hexnext-recovery.sh'",
            )
        }
    }

    /**
     * True when the hook disabled the overlays during this boot. The marker lives in the root-only
     * module directory, so it is read as root through [service]; without root it reads false.
     */
    fun wasTriggeredThisBoot(service: IThemeService): Boolean = runCatching {
        val currentBoot = ApplyGuard.currentBootId() ?: return@runCatching false
        val read = service.exec("cat '$MODULE_DIR/$MARKER_NAME'")
        read.getInt(ThemeService.KEY_CODE, -1) == 0 &&
            read.getString(ThemeService.KEY_OUTPUT).orEmpty().trim() == currentBoot
    }.getOrDefault(false)

    /** [text] as one single-quoted shell word. */
    private fun singleQuoted(text: String): String = "'" + text.replace("'", "'\\''") + "'"
}
