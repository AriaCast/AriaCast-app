package com.aria.ariacast.quirks

import android.content.SharedPreferences

/**
 * The question asked once about each speaker, after a minute of casting to it: does it work?
 * What to ask and when lives here so it can be tested; showing it is [com.aria.ariacast.ui.SpeakerFeedbackActivity].
 */
object SpeakerFeedback {
    /** How long a speaker must have been playing before it is asked about. */
    const val ASK_AFTER_MS = 60_000L

    /**
     * A speaker is told apart by its protocol and the name it goes by. An address changes with the
     * router; a name seldom does.
     */
    fun key(platform: String?, name: String) = "${platform ?: "-"}|${name.trim().lowercase()}"

    /** A Sendspin server pulls the stream itself, so there is no speaker of ours to judge. */
    fun isAskable(platform: String?) = platform != "Sendspin"

    /** Ask when the minute is up and the speaker is still playing in a cast, and was never asked. */
    fun shouldAsk(casting: Boolean, stillInCast: Boolean, alreadyAsked: Boolean, platform: String?) =
        casting && stillInCast && !alreadyAsked && isAskable(platform)

    /**
     * After "it works": offer to add the speaker to the public list unless the list already says it
     * is supported. One more "it works" for a speaker everybody knows adds little, and sending it
     * takes a GitHub account.
     */
    fun offerSharingAfterWorks(known: List<KnownSpeaker>) = known.none { it.status == Status.SUPPORTED }

    /** The word of the report form's dropdown for a status. */
    fun formLabel(status: Status) = when (status) {
        Status.SUPPORTED -> SpeakerIssue.STATUS_SUPPORTED
        Status.QUIRKS -> SpeakerIssue.STATUS_QUIRKS
        Status.UNSUPPORTED -> SpeakerIssue.STATUS_UNSUPPORTED
    }
}

/**
 * What has been asked and answered, kept on the phone. Nothing here is sent anywhere: an answer
 * only leaves the phone if the user then opens a report.
 */
class FeedbackLedger(private val read: (String) -> String?, private val write: (String, String) -> Unit) {
    fun asked(key: String) = read("asked.$key") != null

    fun markAsked(key: String, atMs: Long) = write("asked.$key", atMs.toString())

    fun record(key: String, status: Status) = write("answer.$key", status.id)

    fun answer(key: String): Status? = Status.of(read("answer.$key"))

    companion object {
        fun of(prefs: SharedPreferences) = FeedbackLedger(
            read = { prefs.getString("fb.$it", null) },
            write = { k, v -> prefs.edit().putString("fb.$k", v).apply() },
        )
    }
}
