# Plugin Sandbox ClassShutter Whitelist & Capability Helpers

## Context
Commit `6521ec7` introduced `PluginClassShutter` with an unconditional `visibleToScripts = false` in an attempt to deny class lookups (`Packages.java.lang.Runtime`, etc.). However, in Mozilla Rhino, `ClassShutter.visibleToScripts(fullClassName)` is also called whenever any Java instance (such as `MainActivity`, `DiscoveryManager`, `View`, or helper objects) is wrapped into JavaScript via `NativeJavaObject`. The unconditional block caused all plugins to crash on startup with `SecurityException: Access to Java class "..." is prohibited`.

## Decision
We decided to replace the blanket denial in `PluginClassShutter` with a strict whitelist/blacklist filter:
1. Explicitly deny dangerous classes (`java.lang.Runtime`, `java.lang.ProcessBuilder`, `java.lang.System`, `java.lang.ClassLoader`, `java.lang.Thread`, `java.lang.reflect.*`, `dalvik.system.*`).
2. Whitelist necessary app and Android UI classes (`com.aria.ariacast.*`, `android.view.*`, `android.widget.*`, `android.text.*`, `android.util.*`, `android.app.Activity`, `android.content.Context`, standard primitives/containers).
3. Safely permit dynamic proxies for SAM interfaces (e.g. `OnClickListener`) without triggering class static initialization.
4. Retain `SandboxedNativeJavaObject` to block reflection pivots (`getClass()`, `getClassLoader()`).
5. Augment `ui` helper with `toast()` and `showInputDialog()` to allow plugins like `Manual Server Entry` to show toasts and configuration dialogs. (Note: `showInputDialog` is currently tailored for manual server configuration with IP/Port fields; it will be generalized into a dynamic schema-based form dialog when additional plugins require configuration inputs).

## Consequences
- Plugins can access injected host objects, views, and standard helpers without triggering `SecurityException`.
- Arbitrary native code execution, thread manipulation, and reflection escapes remain prevented.
- Top-level `Packages` and `android` root objects are deliberately omitted from the global scope by `initSafeStandardObjects()` for least privilege. All plugins must use the capability helper `ui.toast()` rather than attempting direct Java package invocations.
- Production plugins reside in the separate `AriaCast-android-plugins` repository; test fixture scripts are kept under `app/src/test/resources/plugins/`.
