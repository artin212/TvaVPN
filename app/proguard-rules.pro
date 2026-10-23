# Keep JNI entry points used by the native tun2socks bridge.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# R8/ProGuard is NOT enabled for release (isMinifyEnabled = false).
#
# 1.2.9-r3 audit decision, recorded here so the next person does not have to
# re-derive it: obfuscation is not a security control, and for an app whose full
# source is published it buys nothing - the class and string names an attacker
# would recover are already on GitHub. What it costs is real: R8's missing-class
# errors against the Go-generated Psiphon AAR are a plausible way to break a
# release, and this project's packaging environment has no Android SDK and no
# network, so such a change cannot be validated before it ships.
#
# The keep rule above is the only one the native bridge needs, so enabling
# minification later is a flag flip plus a smoke test - do it in a round that can
# actually build and run the APK.
# ---------------------------------------------------------------------------
