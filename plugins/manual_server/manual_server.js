console.info("Manual Server Plugin Loaded");

function showToast(msg) {
    if (typeof ui !== "undefined" && ui && typeof ui.toast === "function") {
        ui.toast(msg);
    } else if (typeof android !== "undefined" && android.widget && android.widget.Toast && typeof activity !== "undefined" && activity) {
        android.widget.Toast.makeText(activity, msg, 0).show();
    }
}

// Auto-register previously saved server into discovery on startup if available
var savedIp = storage.get("last_manual_ip");
var savedPort = parseInt(storage.get("last_manual_port")) || 12889;
if (savedIp && typeof discovery !== "undefined" && discovery) {
    discovery.addManualServer(savedIp, savedPort, "Manual: " + savedIp);
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
                    ip = (ip || "").trim();
                    var p = parseInt(port) || 12889;
                    if (!ip) return;
                    storage.set("last_manual_ip", ip);
                    storage.set("last_manual_port", String(p));
                    if (typeof discovery !== "undefined" && discovery) {
                        if (discovery.addManualServer(ip, p, "Manual: " + ip)) {
                            showToast("Server added: " + ip + ":" + p);
                        } else {
                            showToast("Invalid IP address: " + ip);
                        }
                    } else {
                        showToast("Saved: " + ip + ":" + p);
                    }
                }
            );
        } else {
            showToast("Config: " + (curIp ? curIp + ":" + curPort : "Not set"));
        }
    });
}

function renderUI() {
    if (typeof ui === "undefined" || !ui) return;
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
            var ip = ipEdit.getText().toString().trim();
            var port = parseInt(portEdit.getText().toString().trim()) || 12889;
            if (ip) {
                if (typeof discovery !== "undefined" && discovery) {
                    if (discovery.addManualServer(ip, port, "Manual: " + ip)) {
                        storage.set("last_manual_ip", ip);
                        storage.set("last_manual_port", String(port));
                        showToast("Server added: " + ip + ":" + port);
                    } else {
                        showToast("Invalid IP address: " + ip);
                    }
                } else {
                    storage.set("last_manual_ip", ip);
                    storage.set("last_manual_port", String(port));
                    showToast("Server saved: " + ip + ":" + port);
                }
            }
        });
        ui.add(btn);
    });
}
renderUI();
