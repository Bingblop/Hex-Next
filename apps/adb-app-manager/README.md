# ADB Application Manager Pro — moved

The ADB Application Manager app now lives in its own repository:

**https://github.com/Bingblop/ADB-Application-Manager**

Source, build script (`build.sh`, works in Termux), changelog and signed release APKs are there.

The copy that used to be in this folder was an older version (3.0) and is no longer maintained.
It also contained an ADB key pair (`assets/adbkey`) that was shared by every install. Current
versions generate a private key on each phone instead. If you ever used the old version, revoke the
old key on your phone: **Developer Options → Revoke USB debugging authorizations**.
