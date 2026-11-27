package com.aria.ariacast.scan

import com.aria.ariacast.dlna.Dlna
import com.aria.ariacast.quirks.DeviceIdentity
import com.aria.ariacast.quirks.SpeakerReport

/** An answer to an SSDP search: where the device describes itself, and what it says it is. */
data class SsdpReply(val location: String, val server: String?)

/** What a device's UPnP description says. All of it is the device's own text, so it is only shown and compared. */
data class SsdpDevice(
    val host: String,
    val friendlyName: String,
    val manufacturer: String?,
    val modelName: String?,
    val deviceType: String?,
    val services: List<String>,
    val server: String?,
) {
    /** A DLNA renderer is something AriaCast already plays to; it is not a missing speaker. */
    val isRenderer: Boolean get() = services.any { it.contains(":service:AVTransport:") }
}

/** A service name announced over mDNS, as the network gave it. */
data class MdnsFound(val name: String, val type: String)

/** A device on the network that AriaCast has no way to play to, as far as a scan can tell. */
data class OtherDevice(
    val name: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val mdnsTypes: List<String> = emptyList(),
    val deviceType: String? = null,
    val upnpServices: List<String> = emptyList(),
    val server: String? = null,
    val host: String? = null,
)

/** Reading what a scan finds. Pure, so it can be tested without a network. */
object ScanParsing {
    const val MAX_DESCRIPTION_BYTES = 64 * 1024

    /**
     * mDNS service types worth looking for: what speakers and their ecosystems announce, other than
     * what AriaCast already plays to (AirPlay, Google Cast, AriaCast, Snapcast). A type nobody on the
     * network announces just finds nothing.
     */
    val MDNS_TYPES: Map<String, String> = linkedMapOf(
        "_spotify-connect._tcp" to "Spotify Connect",
        "_sonos._tcp" to "Sonos",
        "_amzn-wplay._tcp" to "Amazon",
        "_hap._tcp" to "HomeKit",
        "_heos-audio._tcp" to "HEOS",
        "_soundtouch._tcp" to "Bose SoundTouch",
        "_mediaremotetv._tcp" to "Apple TV",
        "_companion-link._tcp" to "Apple",
    )

    private fun clean(text: String?, max: Int) =
        text?.filter { it >= ' ' && it != '\u007f' }?.trim()?.take(max)?.takeIf { it.isNotEmpty() }

    /** The LOCATION and SERVER of an SSDP answer, or null if it does not say where the device is. */
    fun ssdpReply(text: String): SsdpReply? {
        val headers = text.split("\r\n", "\n").mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()
        val location = clean(headers["location"], 300) ?: return null
        return SsdpReply(location, clean(headers["server"], 120))
    }

    /** The device a UPnP description is about, or null if it has no name. */
    fun description(xml: String, host: String, server: String?): SsdpDevice? {
        if (xml.length > MAX_DESCRIPTION_BYTES) return null
        fun tag(t: String, src: String = xml) = Regex("<$t>([\\s\\S]*?)</$t>").find(src)?.groupValues?.get(1)?.let(Dlna::xmlUnescape)
        val name = clean(tag("friendlyName"), 80) ?: return null
        val services = Regex("<serviceType>([\\s\\S]*?)</serviceType>").findAll(xml)
            .mapNotNull { clean(Dlna.xmlUnescape(it.groupValues[1]), 80) }.distinct().take(12).toList()
        return SsdpDevice(
            host = host,
            friendlyName = name,
            manufacturer = clean(tag("manufacturer"), 60),
            modelName = clean(tag("modelName"), 60),
            deviceType = clean(tag("deviceType"), 80),
            services = services,
            server = server,
        )
    }

    /**
     * What the scan found, as devices AriaCast cannot play to: UPnP devices that are not renderers
     * and mDNS services of the other types, with the two joined when they share a name, and without
     * anything whose name is one of [usableNames] (a speaker the app already lists).
     */
    fun otherDevices(ssdp: List<SsdpDevice>, mdns: List<MdnsFound>, usableNames: Collection<String> = emptyList()): List<OtherDevice> {
        val usable = usableNames.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        fun isUsable(name: String) = usable.any { u -> name.lowercase().let { n -> n == u || n.contains(u) || u.contains(n) } }

        val byName = linkedMapOf<String, OtherDevice>()
        mdns.groupBy { clean(it.name, 60)?.lowercase() ?: "" }.forEach { (key, found) ->
            if (key.isEmpty()) return@forEach
            byName[key] = OtherDevice(name = clean(found.first().name, 60)!!, mdnsTypes = found.map { it.type }.distinct().sorted())
        }
        ssdp.filter { !it.isRenderer }.forEach { d ->
            val key = d.friendlyName.lowercase()
            val joined = byName[key] ?: byName.entries.firstOrNull { (k, _) -> key.contains(k) || k.contains(key) }?.value
            val device = OtherDevice(
                name = joined?.name ?: d.friendlyName, manufacturer = d.manufacturer, model = d.modelName,
                mdnsTypes = joined?.mdnsTypes ?: emptyList(), deviceType = d.deviceType, upnpServices = d.services,
                server = d.server, host = d.host,
            )
            byName.remove(joined?.name?.lowercase())
            byName[key] = device
        }
        return byName.values.filterNot { isUsable(it.name) }.sortedBy { it.name.lowercase() }
    }

    /** The identity the report form uses for a device: no protocol, since there is none we can play to. */
    fun identity(d: OtherDevice) = DeviceIdentity(platform = null, name = d.name, model = d.model, manufacturer = d.manufacturer)

    /** Everything the scan learned about a device, for a report. Addresses are shortened. */
    fun fingerprint(d: OtherDevice): String = buildString {
        appendLine("Found by a network scan, not as a speaker AriaCast can play to.")
        appendLine("Name: ${d.name}")
        d.manufacturer?.let { appendLine("Manufacturer: $it") }
        d.model?.let { appendLine("Model: $it") }
        if (d.mdnsTypes.isNotEmpty()) appendLine("mDNS: ${d.mdnsTypes.joinToString(", ")}")
        d.deviceType?.let { appendLine("UPnP device type: $it") }
        if (d.upnpServices.isNotEmpty()) appendLine("UPnP services: ${d.upnpServices.joinToString(", ")}")
        d.server?.let { appendLine("Server: $it") }
        d.host?.let { appendLine("Address: $it") }
    }.trimEnd().let(SpeakerReport::maskAddresses)

    /** A short line for the list: what kind of thing it announced. */
    fun summary(d: OtherDevice): String {
        val labels = d.mdnsTypes.mapNotNull { MDNS_TYPES[it] }
        return listOfNotNull(
            labels.takeIf { it.isNotEmpty() }?.joinToString(", "),
            listOfNotNull(d.manufacturer, d.model).joinToString(" ").takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifEmpty { "UPnP" }
    }
}
