# Device lab suites

Saved jobs for the device lab (`tools/device/autotest.sh` on the phone). Each suite is a job
template: steps, captures, lookups and the `expect` block that `check_results.py` checks. They run
unchanged on One UI 7, 8 and 9: a resource a phone does not have is reported as n/a.

| Suite | What it checks | Soft reboot |
|---|---|---|
| `smoke` | Apply the stored theme; every preview next to the phone, light and dark | no |
| `keyboard` | Samsung Keyboard colours and pack key art | no |
| `dialogs-power` | Pack dialog, popup and power menu art | no |
| `samsung-apps` | A pack theme on every themed Samsung app, power menu (incl. generated icons), keyboard, dialogs; Google app tables | no |
| `statusbar` | Pack status bar icons (selects a pack with indicator art) | no |
| `restore` | The theme comes back by itself after a soft reboot | yes |

Only `restore` soft reboots: after a soft reboot some phones lose networking until a full reboot
(the runner then reboots by itself and pushes the results on the next start).

## Run a suite

On a computer with `gh` signed in and the lab repository cloned:

```
tools/device/post-job.sh keyboard                      # HEAD's build, next lab-NNN
tools/device/post-job.sh keyboard <commit> '' '.pack="apatite"'
```

The phone's watcher (`bash autotest.sh watch` in Termux) picks the job up, runs it and pushes
`results/<id>/`. Then:

```
tools/device/check_results.py <lab-dir> <id> --write
```

prints the verdict and saves it as `results/<id>/verdict.md`.

## Beta testers on One UI 7 and 8

The same three steps on their own phone and lab repository: `install.sh` sets up Termux and the
watcher, `post-job.sh` posts a suite, and the verdict and screenshots show what differs on their
One UI version. Fixes go into `android/engine/src/main/resources/oneui/<major>.json` (names,
copies, colour groups) and ship with the next app build.
