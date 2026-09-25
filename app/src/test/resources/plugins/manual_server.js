console.info("Manual Server Plugin Loaded");

var showToast = function(msg) {
    var text = String(msg == null ? "" : msg);
    if (typeof ui !== "undefined" && ui && typeof ui.toast === "function") {
        ui.toast(text);
    } else if (typeof activity !== "undefined" && activity && typeof activity.runOnUiThread === "function") {
        activity.runOnUiThread(function() {
            try {
                if (typeof android !== "undefined" && android.widget && android.widget.Toast) {
                    android.widget.Toast.makeText(activity, text, 0).show();
                }
            } catch (e) {
                if (typeof console !== "undefined" && console && typeof console.error === "function") {
                    console.error("Toast error: " + e);
                }
            }
        });
    }
};

// Common validation, discovery registration, and persistent storage helper
var registerAndSaveServer = function(ip, port) {
    ip = (ip || "").trim();
    if (!ip) {
        showToast("Please enter an IP address");
        return false;
    }

    var portStr = String(port == null ? "" : port).trim();
    var p = portStr ? parseInt(portStr, 10) : 12889;
    if (isNaN(p) || p < 1 || p > 65535) {
        showToast("Port must be between 1 and 65535");
        return false;
    }

    if (typeof discovery !== "undefined" && discovery) {
        if (discovery.addManualServer(ip, p, "Manual: " + ip)) {
            storage.set("last_manual_ip", ip);
            storage.set("last_manual_port", String(p));
            showToast("Server added: " + ip + ":" + p);
            return true;
        } else {
            showToast("Invalid IP address: " + ip);
            return false;
        }
    } else {
        // Fallback for config dialog when discovery is absent (e.g. inside PluginsActivity)
        var ipv4Regex = /^(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$/;
        if (!ipv4Regex.test(ip)) {
            showToast("Invalid IP address: " + ip);
            return false;
        }
        storage.set("last_manual_ip", ip);
        storage.set("last_manual_port", String(p));
        showToast("Server saved: " + ip + ":" + p);
        return true;
    }
};

// Auto-register previously saved server into discovery on startup if available
var savedIp = storage.get("last_manual_ip");
var savedPort = parseInt(storage.get("last_manual_port"), 10) || 12889;
if (savedIp && typeof discovery !== "undefined" && discovery) {
    if (!discovery.addManualServer(savedIp, savedPort, "Manual: " + savedIp)) {
        if (typeof console !== "undefined" && console && typeof console.warn === "function") {
            console.warn("Failed to auto-register manual server: " + savedIp);
        }
    }
}

// Support Configure action from PluginsActivity
if (typeof events !== "undefined" && events && typeof events.onConfigRequested === "function") {
    events.onConfigRequested(function() {
        var curIp = storage.get("last_manual_ip") || "";
        var curPort = storage.get("last_manual_port") || "12889";
        if (typeof ui !== "undefined" && ui && typeof ui.showInputDialog === "function") {
            ui.showInputDialog(
                "Manual Server Entry",
                "Enter server IP and port for cross-VLAN casting:",
                curIp,
                curPort,
                function(ip, port) {
                    registerAndSaveServer(ip, port);
                }
            );
        } else {
            showToast("Config: " + (curIp ? curIp + ":" + curPort : "Not set"));
        }
    });
}

// State management teardown support per README.md standard
if (typeof events !== "undefined" && events && typeof events.onStateChanged === "function") {
    events.onStateChanged(function(state) {
        // Lifecycle notification hook
    });
}

var renderUI = function() {
    // Suppress UI inflation when running in config-only mode (discovery is absent) or ui is missing
    if (typeof ui === "undefined" || !ui || typeof discovery === "undefined" || !discovery) return;
    ui.run(function() {
        ui.clear();
        var header = ui.inflate("item_plugin_header");
        var ht = ui.findView(header, "headerText");
        if (ht) ht.setText("Manual Server Entry");
        ui.add(header);

        var ipInput = ui.inflate("item_plugin_input");
        var ipEdit = ui.findView(ipInput, "editText");
        if (ipEdit) {
            ipEdit.setHint("IP Address (e.g. 192.168.1.50)");
            ipEdit.setText(storage.get("last_manual_ip") || "");
        }
        ui.add(ipInput);

        var portInput = ui.inflate("item_plugin_input");
        var portEdit = ui.findView(portInput, "editText");
        if (portEdit) {
            portEdit.setHint("Port (Default: 12889)");
            portEdit.setInputType(2);
            portEdit.setText(storage.get("last_manual_port") || "12889");
        }
        ui.add(portInput);

        var btn = ui.inflate("item_plugin_button");
        var bt = ui.findView(btn, "buttonText");
        if (bt) bt.setText("Add Server");
        btn.setOnClickListener(function() {
            var rawIp = ipEdit && ipEdit.getText() ? ipEdit.getText().toString() : "";
            var rawPort = portEdit && portEdit.getText() ? portEdit.getText().toString() : "";
            registerAndSaveServer(rawIp, rawPort);
        });
        ui.add(btn);
    });
};
renderUI();
