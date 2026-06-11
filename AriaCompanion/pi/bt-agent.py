#!/usr/bin/env python3
"""
Headless Bluetooth pairing agent.
Accepts all incoming pairing requests automatically (NoInputNoOutput capability).
Makes the adapter discoverable and pairable indefinitely.
"""
import sys
import dbus
import dbus.service
import dbus.mainloop.glib
from gi.repository import GLib

AGENT_PATH  = "/aria/agent"
CAPABILITY  = "NoInputNoOutput"


class Agent(dbus.service.Object):
    @dbus.service.method("org.bluez.Agent1", in_signature="os", out_signature="")
    def AuthorizeService(self, device, uuid):
        pass

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
        pass  # auto-confirm

    @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="")
    def RequestAuthorization(self, device):
        pass

    @dbus.service.method("org.bluez.Agent1", in_signature="", out_signature="")
    def Cancel(self):
        pass


def _set_adapter(props, key, value):
    try:
        props.Set("org.bluez.Adapter1", key, value)
    except dbus.DBusException as e:
        print(f"Warning: could not set {key}: {e}", file=sys.stderr)


def main():
    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus  = dbus.SystemBus()
    agent = Agent(bus, AGENT_PATH)

    mgr = dbus.Interface(
        bus.get_object("org.bluez", "/org/bluez"),
        "org.bluez.AgentManager1"
    )
    mgr.RegisterAgent(AGENT_PATH, CAPABILITY)
    mgr.RequestDefaultAgent(AGENT_PATH)

    props = dbus.Interface(
        bus.get_object("org.bluez", "/org/bluez/hci0"),
        "org.freedesktop.DBus.Properties"
    )
    _set_adapter(props, "Discoverable",        dbus.Boolean(True))
    _set_adapter(props, "Pairable",            dbus.Boolean(True))
    _set_adapter(props, "DiscoverableTimeout", dbus.UInt32(0))
    _set_adapter(props, "PairableTimeout",     dbus.UInt32(0))

    print("BT agent running — discoverable, auto-pairing enabled")
    GLib.MainLoop().run()


if __name__ == "__main__":
    main()
