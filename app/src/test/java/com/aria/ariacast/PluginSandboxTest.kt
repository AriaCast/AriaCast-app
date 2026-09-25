package com.aria.ariacast

import org.junit.Assert.*
import org.junit.Test
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.ScriptableObject

class PluginSandboxTest {

    @Test
    fun `PluginClassShutter allows legitimate app and Android UI classes`() {
        // App classes
        assertTrue(PluginClassShutter.visibleToScripts("com.aria.ariacast.MainActivity"))
        assertTrue(PluginClassShutter.visibleToScripts("com.aria.ariacast.DiscoveryManager"))
        assertTrue(PluginClassShutter.visibleToScripts("com.aria.ariacast.AudioCastService"))
        assertTrue(PluginClassShutter.visibleToScripts("com.aria.ariacast.VisualizerView"))
        assertTrue(PluginClassShutter.visibleToScripts("com.aria.ariacast.PluginManager\$runPlugin\$uiHelper\$1"))

        // Android UI classes
        assertTrue(PluginClassShutter.visibleToScripts("android.widget.Toast"))
        assertTrue(PluginClassShutter.visibleToScripts("android.view.View"))
        assertTrue(PluginClassShutter.visibleToScripts("android.widget.TextView"))
        assertTrue(PluginClassShutter.visibleToScripts("android.widget.EditText"))
        assertTrue(PluginClassShutter.visibleToScripts("android.widget.LinearLayout"))
        assertTrue(PluginClassShutter.visibleToScripts("android.app.Activity"))
        assertTrue(PluginClassShutter.visibleToScripts("android.content.Context"))

        // Safe standard utilities
        assertTrue(PluginClassShutter.visibleToScripts("java.lang.String"))
        assertTrue(PluginClassShutter.visibleToScripts("java.lang.Integer"))
        assertTrue(PluginClassShutter.visibleToScripts("java.lang.Thread"))
        assertTrue(PluginClassShutter.visibleToScripts("java.util.ArrayList"))
        assertTrue(PluginClassShutter.visibleToScripts("org.json.JSONObject"))
    }

    @Test
    fun `PluginClassShutter denies dangerous system, execution, and reflection classes`() {
        assertFalse(PluginClassShutter.visibleToScripts("java.lang.Runtime"))
        assertFalse(PluginClassShutter.visibleToScripts("java.lang.Process"))
        assertFalse(PluginClassShutter.visibleToScripts("java.lang.ProcessBuilder"))
        assertFalse(PluginClassShutter.visibleToScripts("java.lang.System"))
        assertFalse(PluginClassShutter.visibleToScripts("java.lang.ClassLoader"))
        assertFalse(PluginClassShutter.visibleToScripts("java.lang.reflect.Method"))
        assertFalse(PluginClassShutter.visibleToScripts("java.lang.reflect.Field"))
        assertFalse(PluginClassShutter.visibleToScripts("dalvik.system.DexClassLoader"))
        assertFalse(PluginClassShutter.visibleToScripts("dalvik.system.PathClassLoader"))
        assertFalse(PluginClassShutter.visibleToScripts("java.io.File"))
        assertFalse(PluginClassShutter.visibleToScripts("com.example.ArbitraryClass"))
    }

    class DummyAppObject {
        fun greet(name: String): String = "Hello, $name"
    }

    @Test
    fun `Sandboxed Rhino context allows member calls on whitelisted classes but blocks reflection`() {
        val cx = RhinoContext.enter()
        try {
            cx.optimizationLevel = -1
            cx.setClassShutter(PluginClassShutter)
            cx.setWrapFactory(PluginWrapFactory)

            val scope = cx.initSafeStandardObjects()
            val dummy = DummyAppObject()
            ScriptableObject.putProperty(scope, "dummy", RhinoContext.javaToJS(dummy, scope))

            // Calling legitimate method succeeds
            val result = cx.evaluateString(scope, "dummy.greet('World')", "test.js", 1, null)
            val unwrapped = if (result is org.mozilla.javascript.Wrapper) result.unwrap() else result
            assertEquals("Hello, World", unwrapped)

            // getClass() reflection chain is blocked by SandboxedNativeJavaObject
            val reflectionCheck = cx.evaluateString(
                scope,
                "typeof dummy.getClass === 'undefined' && typeof dummy.class === 'undefined'",
                "test.js",
                1,
                null
            )
            assertEquals(true, reflectionCheck)

            // Top-level Packages is not defined by initSafeStandardObjects()
            val packagesType = cx.evaluateString(scope, "typeof Packages", "test.js", 1, null)
            assertEquals("undefined", packagesType)

            // Injecting a blocked class via javaToJS throws SecurityException
            var threwSecurityException = false
            try {
                RhinoContext.javaToJS(Runtime.getRuntime(), scope)
            } catch (e: Exception) {
                threwSecurityException = e is SecurityException || e.message?.contains("prohibited") == true
            }
            assertTrue("Expected security exception when wrapping Runtime", threwSecurityException)
        } finally {
            RhinoContext.exit()
        }
    }
}
