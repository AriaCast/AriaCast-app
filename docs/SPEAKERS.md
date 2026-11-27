# Known speakers

<!-- Generated from data/speakers.json (list version 1). Do not edit by hand:
     change the JSON, then run `UPDATE_SPEAKERS_DOC=1 ./gradlew :app:testGithubDebugUnitTest --tests '*SpeakersDocTest*'`. -->

How speakers behave with AriaCast, what you see when something is wrong, and what to do.
Only behavior with evidence is listed; a speaker that is not here is untested, not broken.
The app reads the same list (Settings → Known speakers) and keeps it up to date.

## Works

| Speaker | Protocol | If it goes wrong | What to do | Since | Evidence |
|---|---|---|---|---|---|
| Sonos (tested on Play:5) | AirPlay 2 | On 1.1.9 the AirPlay 2 stream did not play, while DLNA to the same speaker did. | Update. Until then, cast to it over DLNA. | 1.1.9_hotfix | [#48](https://github.com/AriaCast/AriaCast-app/issues/48), confirmed by the reporter after the hotfix |
| Sonos (tested on Play:5) | DLNA |  |  |  | [#48](https://github.com/AriaCast/AriaCast-app/issues/48), reporter: DLNA was working |
| WiiM (tested on Pro Plus) | DLNA | The track title changes but playback stays paused at 0:00 after every track change.<br>Pressing play in the WiiM app starts it again. | Update: AriaCast now sends Play after every track change. If it still stops, press play once in the WiiM app and report it. | 2.0.0 | [#50](https://github.com/AriaCast/AriaCast-app/issues/50), fixed in the 2.0 line; the reporter's confirmation is not recorded |
| shairport-sync | AirPlay 1 |  |  |  | Maintainer's own test in [#48](https://github.com/AriaCast/AriaCast-app/issues/48); the AirPlay version is not stated there, so it is listed under AirPlay 1 |

## Works with a catch

| Speaker | Protocol | If it goes wrong | What to do | Since | Evidence |
|---|---|---|---|---|---|
| shairport-sync | AirPlay 2 | The speaker accepts the connection but timing is off, or AriaCast drops to AirPlay 1. | Nothing to do: AriaCast tries NTP timing and falls back to AirPlay 1 on its own. Real PTP needs root. |  | Code: PtpMaster (shairport-sync with nqptp accepts only PTP timing) |
| Apple TV | AirPlay (video) | On Apple TVs that require pairing, casting a video fails with "This AirPlay device requires pairing for video". Audio casting is not affected. | Cast audio only. Video works with Apple TVs that allow unpaired access. |  | Roadmap status table and VideoTargets.kt |

## Does not work

Nothing recorded yet.

## Not supported

| Speaker | Protocol | If it goes wrong | What to do | Since | Evidence |
|---|---|---|---|---|---|
| Amazon Echo Show 5 (1st gen), Echo Hub | No local protocol | The speaker does not appear in AriaCast, and it has no AirPlay. | Bluetooth is the only local way in, and AriaCast does not send to Bluetooth speakers yet. |  | Maintainer's own devices; Alexa has no local streaming protocol |

## Add a speaker

In the app, long-press the speaker in the Send tab and choose **Report speaker**, or answer the
question that appears the first time you cast to a new one. It opens a pre-filled
[issue](https://github.com/AriaCast/AriaCast-app/issues/new?template=speaker-report.yml); the logs are
copied for you to paste in. A maintainer reviews it and it joins the list.
