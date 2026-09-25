# Plugin Sandbox ClassShutter Whitelist & Capability Helpers

## Context
Commit `6521ec7` introduced `PluginClassShutter` with an unconditional `visibleToScripts = false` in an attempt to deny class lookups (`Packages.java.lang.Runtime`, etc.). However, in Mozilla Rhino, `ClassShutter.visibleToScripts(fullClassName)` is also called whenever any Java instance (such as `MainActivity`, `DiscoveryManager`, `View`, or helper objects) is wrapped into JavaScript via `NativeJavaObject`. The unconditional block caused all plugins to crash on startup with `SecurityException: Access to Java class "..." is prohibited`.

## Decision
We decided to replace the blanket denial in `PluginClassShutter` with a strict whitelist/blacklist filter:
1. Explicitly deny dangerous classes (`java.lang.Runtime`, `java.lang.ProcessBuilder`, `java.lang.System`, `java.lang.ClassLoader`, `java.lang.reflect.*`, `dalvik.system.*`).
2. Whitelist necessary app and Android UI classes (`com.aria.ariacast.*`, `android.view.*`, `android.widget.*`, `android.text.*`, `android.util.*`, `android.app.Activity`, `android.content.Context`, standard primitives/containers).
3. Retain `SandboxedNativeJavaObject` to block reflection pivots (`getClass()`, `getClassLoader()`).
4. Augment `ui` helper with `toast()` and `showInputDialog()` to allow plugins like `Manual Server Entry` to show toasts and configuration dialogs without needing raw `android.widget.Toast` packages or crashing in `PluginsActivity`.

## Consequences
- Plugins can access injected host objects, views, and standard helpers without triggering `SecurityException`.
- Arbitrary native code execution and reflection escapes remain prevented.
- Older plugin scripts that call `android.widget.Toast.makeText(activity, ...)` remain backward-compatible while newer scripts can use `ui.toast()`.
