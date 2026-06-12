#!/usr/bin/env python3
"""
Headless Bluetooth pairing agent.
Accepts all incoming pair requests (NoInputNoOutput capability).
Keeps the adapter permanently discoverable and pairable.
"""
import sys
import time
import dbus
import dbus.service
import dbus.mainloop.glib
from gi.repository import GLib

AGENT_PATH   = "/aria/agent"
CAPABILITY   = "NoInputNoOutput"
A2DP_SINK_UUID = "0000110b-0000-1000-8000-00805f9b34fb"


class Agent(dbus.service.Object):
    @dbus.service.method("org.bluez.Agent1", in_signature="os", out_signature="")
    def AuthorizeService(self, device, uuid):
        if uuid.lower() != A2DP_SINK_UUID:
            raise dbus.DBusException(
                "org.bluez.Error.Rejected", f"Service {uuid} not allowed"
            )

    @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="s")
    def RequestPinCode(self, device):
        return "0000"

    @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="u")
    def RequestPasskey(self, device):
        return dbus.UInt32(0)

    @dbus.service.method("org.bluez.Agent1", in_signature="ouq", out_signature="")
    def DisplayPasskey(self, device, passkey, entered):
        pass

    @dbus.service.method("org.bluez.Agent1", in_signature="os", out_signature="")
    def DisplayPinCode(self, device, pincode):
        pass

    @dbus.service.method("org.bluez.Agent1", in_signature="ou", out_signature="")
    def RequestConfirmation(self, device, passkey):
        pass  # auto-confirm all pairings

    @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="")
    def RequestAuthorization(self, device):
        pass

    @dbus.service.method("org.bluez.Agent1", in_signature="", out_signature="")
    def Cancel(self):
        pass


def _set(props, key, val):
    try:
        props.Set("org.bluez.Adapter1", key, val)
    except dbus.DBusException as e:
        print(f"  warning: {key}: {e}", file=sys.stderr)


def main():
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus   = dbus.SystemBus()
    agent = Agent(bus, AGENT_PATH)

    # Retry until bluetoothd is up and RegisterAgent succeeds
    for attempt in range(30):
        try:
            mgr = dbus.Interface(
                bus.get_object("org.bluez", "/org/bluez"),
                "org.bluez.AgentManager1",
            )
            mgr.RegisterAgent(AGENT_PATH, CAPABILITY)
            break
        except dbus.DBusException as e:
            if attempt == 29:
                print(f"bt-agent: bluetoothd not ready after 30 s: {e}", file=sys.stderr)
                sys.exit(1)
            time.sleep(1)
    mgr.RequestDefaultAgent(AGENT_PATH)

    props = dbus.Interface(
        bus.get_object("org.bluez", "/org/bluez/hci0"),
        "org.freedesktop.DBus.Properties",
    )
    _set(props, "Powered",            dbus.Boolean(True))
    _set(props, "Discoverable",       dbus.Boolean(True))
    _set(props, "DiscoverableTimeout", dbus.UInt32(0))
    _set(props, "Pairable",           dbus.Boolean(True))
    _set(props, "PairableTimeout",    dbus.UInt32(0))

    print("bt-agent: discoverable, auto-pairing enabled", flush=True)
    GLib.MainLoop().run()


if __name__ == "__main__":
    main()
