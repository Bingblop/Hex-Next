package com.hexnext.themer.safety

import com.hexnext.themer.IThemeService
import com.hexnext.themer.shizuku.ThemeService
import java.io.File

internal object EarlyRecoveryHook {

    private const val SCRIPT_NAME = "early-recovery.sh"
    private const val LAUNCHER_NAME = "post-fs-data.sh"
    private const val MODULE_PROP_NAME = "module.prop"
    private const val MODULE_ID = "hexnext_early_recovery"
    private const val STAGING_PATH = "/data/local/tmp/hexnext/$SCRIPT_NAME"
    private const val STAGING_LAUNCHER_PATH = "/data/local/tmp/hexnext/$LAUNCHER_NAME"
    private const val STAGING_MODULE_PROP_PATH = "/data/local/tmp/hexnext/$MODULE_PROP_NAME"
    private const val MODULE_DIR = "/data/adb/modules/$MODULE_ID"
    private const val MARKER_PATH = "/data/local/tmp/hexnext/early_safe_mode"

    private val script = """
        #!/system/bin/sh
        set -f
        USER_ID=£(am get-current-user 2>/dev/null)
        case "£USER_ID" in ''|*[!0-9]*) USER_ID=0 ;; esac
        PREFS="/data/user_de/£USER_ID/com.hexnext.themer/shared_prefs/hexnext_apply.xml"
        [ -r "£PREFS" ] || exit 0
        CONFIRMED=£(sed -n 's/.*<boolean name="confirmed" value="\([^"]*\)" \/>.*/\1/p' "£PREFS")
        [ "£CONFIRMED" = false ] || exit 0
        APPLIED=£(sed -n 's/.*<long name="appliedAt" value="\([^"]*\)" \/>.*/\1/p' "£PREFS")
        APPLY_BOOT=£(sed -n 's/.*<string name="applyBootId">\([^<]*\)<\/string>.*/\1/p' "£PREFS")
        CURRENT_BOOT=£(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
        case "£APPLIED" in ''|*[!0-9]*) exit 0 ;; esac
        [ -n "£APPLY_BOOT" ] && [ -n "£CURRENT_BOOT" ] && [ "£CURRENT_BOOT" != "£APPLY_BOOT" ] || exit 0
        NOW=£(date +%s)
        AGE=£((NOW - APPLIED / 1000))
        [ "£AGE" -ge 0 ] && [ "£AGE" -le 600 ] || exit 0

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
              mkdir -p /data/local/tmp/hexnext
              printf '%s\n' "£CURRENT_BOOT" > /data/local/tmp/hexnext/early_safe_mode
              chmod 644 /data/local/tmp/hexnext/early_safe_mode
              exit 0
              ;;
          esac
          TRIES=£((TRIES + 1))
          sleep 5
        done
    """.trimIndent().replace('£', '$') + "\n"

    private val launcher = """
        #!/system/bin/sh
        MODDIR=£{0%/*}
        if command -v setsid >/dev/null 2>&1; then
          nohup setsid sh "£MODDIR/$SCRIPT_NAME" >/dev/null 2>&1 &
        else
          nohup sh "£MODDIR/$SCRIPT_NAME" >/dev/null 2>&1 &
        fi
    """.trimIndent().replace('£', '$') + "\n"

    private const val moduleProp =
        "id=$MODULE_ID\n" +
            "name=Hex Next Early Recovery\n" +
            "version=1.0.0\n" +
            "versionCode=1\n" +
            "author=Hex Next\n" +
            "description=Pre-unlock recovery for an unconfirmed Hex Next apply\n"

    fun install(service: IThemeService): Boolean {
        if (service.uid != 0) return false
        return runCatching {
            val staged = service.writeStateFile(SCRIPT_NAME, script)
            val stagedLauncher = service.writeStateFile(LAUNCHER_NAME, launcher)
            val stagedModuleProp = service.writeStateFile(MODULE_PROP_NAME, moduleProp)
            if (staged.getInt(ThemeService.KEY_CODE, -1) != 0 ||
                stagedLauncher.getInt(ThemeService.KEY_CODE, -1) != 0 ||
                stagedModuleProp.getInt(ThemeService.KEY_CODE, -1) != 0
            ) {
                false
            } else {
                val installed = service.exec(
                    "if [ -e '$MODULE_DIR/disable' ] || [ -e '$MODULE_DIR/remove' ]; then " +
                        "echo 'recovery module disabled or pending removal' >&2; exit 1; fi && " +
                        "if [ -f '$MODULE_DIR/$MODULE_PROP_NAME' ] && " +
                        "! grep -qx 'id=$MODULE_ID' '$MODULE_DIR/$MODULE_PROP_NAME'; then " +
                        "echo 'module id conflict' >&2; exit 1; fi && " +
                        "mkdir -p '$MODULE_DIR' && " +
                        "cp '$STAGING_PATH' '$MODULE_DIR/$SCRIPT_NAME' && " +
                        "cp '$STAGING_LAUNCHER_PATH' '$MODULE_DIR/$LAUNCHER_NAME' && " +
                        "cp '$STAGING_MODULE_PROP_PATH' '$MODULE_DIR/$MODULE_PROP_NAME' && " +
                        "chown 0:0 '$MODULE_DIR' '$MODULE_DIR/$SCRIPT_NAME' " +
                        "'$MODULE_DIR/$LAUNCHER_NAME' '$MODULE_DIR/$MODULE_PROP_NAME' && " +
                        "chmod 755 '$MODULE_DIR/$SCRIPT_NAME' '$MODULE_DIR/$LAUNCHER_NAME' && " +
                        "chmod 644 '$MODULE_DIR/$MODULE_PROP_NAME'",
                )
                installed.getInt(ThemeService.KEY_CODE, -1) == 0
            }
        }.getOrDefault(false)
    }

    fun wasTriggeredThisBoot(): Boolean = runCatching {
        val currentBoot = ApplyGuard.currentBootId()
        currentBoot != null && File(MARKER_PATH).readText().trim() == currentBoot
    }.getOrDefault(false)
}
