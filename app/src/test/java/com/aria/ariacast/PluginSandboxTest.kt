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

        // Android UI & Jetpack classes
        assertTrue(PluginClassShutter.visibleToScripts("android.widget.Toast"))
        assertTrue(PluginClassShutter.visibleToScripts("android.view.View"))
        assertTrue(PluginClassShutter.visibleToScripts("android.widget.TextView"))
        assertTrue(PluginClassShutter.visibleToScripts("android.widget.EditText"))
        assertTrue(PluginClassShutter.visibleToScripts("android.widget.LinearLayout"))
        assertTrue(PluginClassShutter.visibleToScripts("android.app.Activity"))
        assertTrue(PluginClassShutter.visibleToScripts("android.content.Context"))
        assertTrue(PluginClassShutter.visibleToScripts("androidx.constraintlayout.widget.ConstraintLayout"))
        assertTrue(PluginClassShutter.visibleToScripts("androidx.core.view.ViewCompat"))

        // Dynamic proxies generated for SAM/interface adapters (e.g. OnClickListener, Runnable)
        assertTrue(PluginClassShutter.visibleToScripts("\$Proxy0"))
        assertTrue(PluginClassShutter.visibleToScripts("\$Proxy2"))
        assertTrue(PluginClassShutter.visibleToScripts("jdk.proxy1.\$Proxy4"))
        assertTrue(PluginClassShutter.visibleToScripts("com.sun.proxy.\$Proxy1"))

        // Safe standard utilities
        assertTrue(PluginClassShutter.visibleToScripts("java.lang.String"))
        assertTrue(PluginClassShutter.visibleToScripts("java.lang.Integer"))
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
        assertFalse(PluginClassShutter.visibleToScripts("java.lang.Thread"))
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
            cx.languageVersion = RhinoContext.VERSION_ES6
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

    @Test
    fun `manual_server js parses without syntax errors`() {
        val scriptFile = listOf(
            java.io.File("plugins/manual_server/manual_server.js"),
            java.io.File("../plugins/manual_server/manual_server.js")
        ).firstOrNull { it.exists() }
        assertNotNull("Script file must exist", scriptFile)
        val scriptContent = scriptFile!!.readText()

        val cx = RhinoContext.enter()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = RhinoContext.VERSION_ES6
            cx.setClassShutter(PluginClassShutter)
            cx.setWrapFactory(PluginWrapFactory)
            val scope = cx.initSafeStandardObjects()

            // Test capability helpers so evaluation tests all code paths cleanly
            cx.evaluateString(scope, "var storage = { get: function() { return null; }, set: function() {} };", "setup.js", 1, null)
            cx.evaluateString(scope, """
                var mockView = {
                    setOnClickListener: function(f) { this.listener = f; },
                    setText: function(t) {},
                    setHint: function(h) {},
                    setInputType: function(t) {},
                    getText: function() { return { toString: function() { return "192.168.1.100"; } }; }
                };
                var ui = {
                    run: function(f) { f(); },
                    clear: function() {},
                    inflate: function() { return mockView; },
                    findView: function() { return mockView; },
                    add: function() {},
                    toast: function() {},
                    showInputDialog: function() {}
                };
            """.trimIndent(), "setup.js", 1, null)
            cx.evaluateString(scope, "var discovery = { addManualServer: function() { return true; } };", "setup.js", 1, null)
            cx.evaluateString(scope, "var events = { onConfigRequested: function() {} };", "setup.js", 1, null)
            cx.evaluateString(scope, "var console = { info: function() {}, error: function() {} };", "setup.js", 1, null)

            cx.evaluateString(scope, scriptContent, "Manual Server Entry", 1, null)
        } finally {
            RhinoContext.exit()
        }
    }
}
