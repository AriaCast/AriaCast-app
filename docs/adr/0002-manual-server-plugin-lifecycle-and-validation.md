# Manual Server Plugin Lifecycle, Validation & Config Synchronization

## Context
The `Manual Server Entry` plugin allows users to add remote streaming servers by IP address and port for cross-VLAN setups.
Two major integration issues were identified:
1. When configured via `PluginsActivity` (using `events.onConfigRequested` and `ui.showInputDialog`), `discovery` is not available in that Activity context, and `storage.set()` does not update `plugins_updated_at`. Upon returning to `MainActivity`, `MainActivity` is not recreated and `runningPluginIds` prevents `manual_server.js` from re-running, so the configured server is never registered with `DiscoveryManager` until the app process is restarted.
2. In the plugin script, storage persistence preceded `discovery.addManualServer()` validation, causing invalid IP addresses to be permanently written to storage. Furthermore, missing port range boundaries (e.g. negative ports or ports > 65535) could trigger downstream socket exceptions (`IllegalArgumentException: port out of range`).

## Decision
1. **Host-Side Config Synchronization**:
   In `PluginManager.kt`, when `storage.set()` is invoked while `isConfigOnly = true`, update `plugins_updated_at` in `plugins_prefs`. This signals `MainActivity.onResume()` that plugin configuration changed, triggering clean recreation and auto-registration of the updated manual server.
2. **Strict Validation-Before-Persistence**:
   In `manual_server.js`, consolidate validation and persistence into a single helper (`registerAndSaveServer`). `storage.set()` is executed **only after** validation succeeds. If validation fails (or if `discovery.addManualServer` returns false), the invalid input is rejected with clear user feedback and never written to storage.
3. **Strict Port Range Boundaries**:
   Reject ports that are non-numeric, `< 1`, or `> 65535` with an explicit Toast (`"Port must be between 1 and 65535"`). Empty IP inputs are rejected with `"Please enter an IP address"`.
4. **Context-Aware UI Suppression**:
   In `manual_server.js`, suppress `renderUI()` execution when `discovery` is absent (`if (typeof discovery === "undefined" || !discovery) return;`). This prevents wasteful view inflation and listener attachment when running in `PluginsActivity`'s config dialog context.

## Consequences
- Modifying manual server configuration in `PluginsActivity` seamlessly applies upon returning to `MainActivity`.
- Bad IP/Port inputs never pollute persistent storage or cause socket crashes.
- Avoids unnecessary layout inflation in background config sessions.
