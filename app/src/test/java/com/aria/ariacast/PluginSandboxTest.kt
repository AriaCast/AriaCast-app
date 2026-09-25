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

            // Direct android package root is undefined by default (verifying ADR-0001 consequence)
            val androidType = cx.evaluateString(scope, "typeof android", "test.js", 1, null)
            assertEquals("undefined", androidType)

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
    fun `isDynamicProxyClass allows genuine proxy classes but rejects fake proxy named classes`() {
        // Genuine proxy class
        val genuineProxy = java.lang.reflect.Proxy.getProxyClass(
            PluginSandboxTest::class.java.classLoader,
            Runnable::class.java
        )
        assertTrue(PluginClassShutter.visibleToScripts(genuineProxy.name))

        // Non-proxy class with 'Proxy' in name
        assertFalse(PluginClassShutter.visibleToScripts("org.example.FakeProxyClass"))
        assertFalse(PluginClassShutter.visibleToScripts("java.net.ProxySelector"))
    }

    @Test
    fun `manual_server js parses without syntax errors`() {
        val scriptStream = javaClass.classLoader?.getResourceAsStream("plugins/manual_server.js")
            ?: java.io.File("app/src/test/resources/plugins/manual_server.js").takeIf { it.exists() }?.inputStream()
        assertNotNull("Script fixture must exist", scriptStream)
        val scriptContent = scriptStream!!.bufferedReader().use { it.readText() }

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

    @Test
    fun `manual_server config dialog rejects empty IP with toast and does not save`() {
        val scriptStream = javaClass.classLoader?.getResourceAsStream("plugins/manual_server.js")
            ?: java.io.File("app/src/test/resources/plugins/manual_server.js").takeIf { it.exists() }?.inputStream()
        assertNotNull("Script fixture must exist", scriptStream)
        val scriptContent = scriptStream!!.bufferedReader().use { it.readText() }

        val cx = RhinoContext.enter()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = RhinoContext.VERSION_ES6
            cx.setClassShutter(PluginClassShutter)
            cx.setWrapFactory(PluginWrapFactory)
            val scope = cx.initSafeStandardObjects()

            val savedMap = mutableMapOf<String, String>()
            val toasts = mutableListOf<String>()
            var dialogCallback: org.mozilla.javascript.Function? = null

            val storageHelper = object {
                fun get(key: String): String? = savedMap[key]
                fun set(key: String, value: String) { savedMap[key] = value }
            }
            ScriptableObject.putProperty(scope, "storage", RhinoContext.javaToJS(storageHelper, scope))

            val mockView = object {
                fun setOnClickListener(f: Any?) {}
                fun setText(t: Any?) {}
                fun setHint(h: Any?) {}
                fun setInputType(t: Any?) {}
                fun getText(): Any = object {
                    override fun toString(): String = "192.168.1.100"
                }
            }

            val uiHelper = object {
                fun run(f: Runnable) { f.run() }
                fun clear() {}
                fun inflate(layout: String): Any? = mockView
                fun findView(parent: Any?, id: String): Any? = mockView
                fun add(view: Any?) {}
                fun toast(msg: Any?) { toasts.add(msg?.toString() ?: "") }
                fun showInputDialog(title: String, msg: String, ip: String, port: String, onSave: org.mozilla.javascript.Function) {
                    dialogCallback = onSave
                }
            }
            ScriptableObject.putProperty(scope, "ui", RhinoContext.javaToJS(uiHelper, scope))

            val eventsHelper = object {
                fun onConfigRequested(f: org.mozilla.javascript.Function) {
                    f.call(cx, scope, scope, arrayOf())
                }
            }
            ScriptableObject.putProperty(scope, "events", RhinoContext.javaToJS(eventsHelper, scope))
            ScriptableObject.putProperty(scope, "console", RhinoContext.javaToJS(object {
                fun info(m: Any?) {}
                fun warn(m: Any?) {}
                fun error(m: Any?) {}
            }, scope))

            cx.evaluateString(scope, scriptContent, "manual_server.js", 1, null)

            assertNotNull("showInputDialog should have been called", dialogCallback)
            // Call onSave with empty IP
            dialogCallback!!.call(cx, scope, scope, arrayOf("", "12889"))

            assertTrue("Expected toast for empty IP", toasts.any { it.contains("enter an IP", ignoreCase = true) })
            assertFalse("Storage must not contain last_manual_ip", savedMap.containsKey("last_manual_ip"))
        } finally {
            RhinoContext.exit()
        }
    }

    @Test
    fun `manual_server config dialog rejects invalid port with toast and does not save`() {
        val scriptStream = javaClass.classLoader?.getResourceAsStream("plugins/manual_server.js")
            ?: java.io.File("app/src/test/resources/plugins/manual_server.js").takeIf { it.exists() }?.inputStream()
        assertNotNull("Script fixture must exist", scriptStream)
        val scriptContent = scriptStream!!.bufferedReader().use { it.readText() }

        val cx = RhinoContext.enter()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = RhinoContext.VERSION_ES6
            cx.setClassShutter(PluginClassShutter)
            cx.setWrapFactory(PluginWrapFactory)
            val scope = cx.initSafeStandardObjects()

            val savedMap = mutableMapOf<String, String>()
            val toasts = mutableListOf<String>()
            var dialogCallback: org.mozilla.javascript.Function? = null

            val storageHelper = object {
                fun get(key: String): String? = savedMap[key]
                fun set(key: String, value: String) { savedMap[key] = value }
            }
            ScriptableObject.putProperty(scope, "storage", RhinoContext.javaToJS(storageHelper, scope))

            val mockView = object {
                fun setOnClickListener(f: Any?) {}
                fun setText(t: Any?) {}
                fun setHint(h: Any?) {}
                fun setInputType(t: Any?) {}
                fun getText(): Any = object {
                    override fun toString(): String = "192.168.1.100"
                }
            }

            val uiHelper = object {
                fun run(f: Runnable) { f.run() }
                fun clear() {}
                fun inflate(layout: String): Any? = mockView
                fun findView(parent: Any?, id: String): Any? = mockView
                fun add(view: Any?) {}
                fun toast(msg: Any?) { toasts.add(msg?.toString() ?: "") }
                fun showInputDialog(title: String, msg: String, ip: String, port: String, onSave: org.mozilla.javascript.Function) {
                    dialogCallback = onSave
                }
            }
            ScriptableObject.putProperty(scope, "ui", RhinoContext.javaToJS(uiHelper, scope))

            val eventsHelper = object {
                fun onConfigRequested(f: org.mozilla.javascript.Function) {
                    f.call(cx, scope, scope, arrayOf())
                }
            }
            ScriptableObject.putProperty(scope, "events", RhinoContext.javaToJS(eventsHelper, scope))
            ScriptableObject.putProperty(scope, "console", RhinoContext.javaToJS(object {
                fun info(m: Any?) {}
                fun warn(m: Any?) {}
                fun error(m: Any?) {}
            }, scope))

            cx.evaluateString(scope, scriptContent, "manual_server.js", 1, null)

            assertNotNull("showInputDialog should have been called", dialogCallback)
            // Call onSave with port > 65535
            dialogCallback!!.call(cx, scope, scope, arrayOf("192.168.1.50", "70000"))
            assertTrue("Expected toast for out of range port", toasts.any { it.contains("65535") })
            assertFalse("Storage must not contain last_manual_ip", savedMap.containsKey("last_manual_ip"))

            toasts.clear()
            // Call onSave with port < 1
            dialogCallback!!.call(cx, scope, scope, arrayOf("192.168.1.50", "-1"))
            assertTrue("Expected toast for negative port", toasts.any { it.contains("65535") })
            assertFalse("Storage must not contain last_manual_ip", savedMap.containsKey("last_manual_ip"))
        } finally {
            RhinoContext.exit()
        }
    }

    @Test
    fun `manual_server config dialog does not save if discovery rejects invalid IP`() {
        val scriptStream = javaClass.classLoader?.getResourceAsStream("plugins/manual_server.js")
            ?: java.io.File("app/src/test/resources/plugins/manual_server.js").takeIf { it.exists() }?.inputStream()
        assertNotNull("Script fixture must exist", scriptStream)
        val scriptContent = scriptStream!!.bufferedReader().use { it.readText() }

        val cx = RhinoContext.enter()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = RhinoContext.VERSION_ES6
            cx.setClassShutter(PluginClassShutter)
            cx.setWrapFactory(PluginWrapFactory)
            val scope = cx.initSafeStandardObjects()

            val savedMap = mutableMapOf<String, String>()
            val toasts = mutableListOf<String>()
            var dialogCallback: org.mozilla.javascript.Function? = null

            val storageHelper = object {
                fun get(key: String): String? = savedMap[key]
                fun set(key: String, value: String) { savedMap[key] = value }
            }
            ScriptableObject.putProperty(scope, "storage", RhinoContext.javaToJS(storageHelper, scope))

            val discoveryHelper = object {
                fun addManualServer(h: String, p: Int, n: String): Boolean = false
            }
            ScriptableObject.putProperty(scope, "discovery", RhinoContext.javaToJS(discoveryHelper, scope))

            val mockView = object {
                fun setOnClickListener(f: Any?) {}
                fun setText(t: Any?) {}
                fun setHint(h: Any?) {}
                fun setInputType(t: Any?) {}
                fun getText(): Any = object {
                    override fun toString(): String = "192.168.1.100"
                }
            }

            val uiHelper = object {
                fun run(f: Runnable) { f.run() }
                fun clear() {}
                fun inflate(layout: String): Any? = mockView
                fun findView(parent: Any?, id: String): Any? = mockView
                fun add(view: Any?) {}
                fun toast(msg: Any?) { toasts.add(msg?.toString() ?: "") }
                fun showInputDialog(title: String, msg: String, ip: String, port: String, onSave: org.mozilla.javascript.Function) {
                    dialogCallback = onSave
                }
            }
            ScriptableObject.putProperty(scope, "ui", RhinoContext.javaToJS(uiHelper, scope))

            val eventsHelper = object {
                fun onConfigRequested(f: org.mozilla.javascript.Function) {
                    f.call(cx, scope, scope, arrayOf())
                }
            }
            ScriptableObject.putProperty(scope, "events", RhinoContext.javaToJS(eventsHelper, scope))
            ScriptableObject.putProperty(scope, "console", RhinoContext.javaToJS(object {
                fun info(m: Any?) {}
                fun warn(m: Any?) {}
                fun error(m: Any?) {}
            }, scope))

            cx.evaluateString(scope, scriptContent, "manual_server.js", 1, null)

            assertNotNull("showInputDialog should have been called", dialogCallback)
            // Call onSave with invalid IP
            dialogCallback!!.call(cx, scope, scope, arrayOf("invalid-ip", "12889"))

            assertTrue("Expected toast for invalid IP", toasts.any { it.contains("Invalid IP", ignoreCase = true) })
            assertFalse("Storage must NOT contain last_manual_ip when discovery rejects it", savedMap.containsKey("last_manual_ip"))
        } finally {
            RhinoContext.exit()
        }
    }

    @Test
    fun `manual_server suppresses renderUI when discovery is absent`() {
        val scriptStream = javaClass.classLoader?.getResourceAsStream("plugins/manual_server.js")
            ?: java.io.File("app/src/test/resources/plugins/manual_server.js").takeIf { it.exists() }?.inputStream()
        assertNotNull("Script fixture must exist", scriptStream)
        val scriptContent = scriptStream!!.bufferedReader().use { it.readText() }

        val cx = RhinoContext.enter()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = RhinoContext.VERSION_ES6
            cx.setClassShutter(PluginClassShutter)
            cx.setWrapFactory(PluginWrapFactory)
            val scope = cx.initSafeStandardObjects()

            var uiRunCalled = false
            var uiInflateCalled = false

            val storageHelper = object {
                fun get(key: String): String? = null
                fun set(key: String, value: String) {}
            }
            ScriptableObject.putProperty(scope, "storage", RhinoContext.javaToJS(storageHelper, scope))

            val uiHelper = object {
                fun run(f: Runnable) { uiRunCalled = true; f.run() }
                fun clear() {}
                fun inflate(layout: String): Any? { uiInflateCalled = true; return null }
                fun findView(parent: Any?, id: String): Any? = null
                fun add(view: Any?) {}
                fun toast(msg: Any?) {}
                fun showInputDialog(title: String, msg: String, ip: String, port: String, onSave: org.mozilla.javascript.Function) {}
            }
            ScriptableObject.putProperty(scope, "ui", RhinoContext.javaToJS(uiHelper, scope))
            ScriptableObject.putProperty(scope, "console", RhinoContext.javaToJS(object {
                fun info(m: Any?) {}
                fun warn(m: Any?) {}
                fun error(m: Any?) {}
            }, scope))

            // Notice: discovery is NOT put into scope (or is null)
            cx.evaluateString(scope, scriptContent, "manual_server.js", 1, null)

            assertFalse("renderUI should NOT inflate layouts when discovery is absent", uiInflateCalled)
        } finally {
            RhinoContext.exit()
        }
    }
}
