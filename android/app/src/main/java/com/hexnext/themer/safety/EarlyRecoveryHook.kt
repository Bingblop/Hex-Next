package com.hexnext.themer.safety

import com.hexnext.themer.IThemeService
import com.hexnext.themer.shizuku.ThemeService
import java.io.File

internal object EarlyRecoveryHook {

    private const val SCRIPT_NAME = "early-recovery.sh"
    private const val STAGING_PATH = "/data/local/tmp/hexnext/$SCRIPT_NAME"
    private const val HOOK_PATH = "/data/adb/service.d/hexnext-recovery.sh"
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

    fun install(service: IThemeService): Boolean {
        if (service.uid != 0) return false
        return runCatching {
            val staged = service.writeStateFile(SCRIPT_NAME, script)
            if (staged.getInt(ThemeService.KEY_CODE, -1) != 0) {
                false
            } else {
                val installed = service.exec(
                    "mkdir -p '/data/adb/service.d' && cp '$STAGING_PATH' '$HOOK_PATH' && " +
                        "chown 0:0 '$HOOK_PATH' && chmod 755 '$HOOK_PATH'",
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
