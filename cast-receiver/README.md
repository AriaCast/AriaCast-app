# AriaCast Cast receiver

When AriaCast casts audio to a Chromecast with a screen (TV, Nest Hub), Google's Default
Media Receiver shows a plain grey page. This custom receiver shows the artwork, title and
artist instead, with a blurred-artwork background.

## Setting it up (once)

1. Host `index.html` over **HTTPS**, e.g. with GitHub Pages:
   create a repository (or a `gh-pages` branch), put `index.html` at its root, enable Pages.
2. In the [Google Cast SDK Developer Console](https://cast.google.com/publish) (one-time
   US$5 registration), add a **Custom Receiver** with that URL. You get an 8-character
   application ID, e.g. `A1B2C3D4`.
3. In AriaCast: menu → **TV screen for Google Cast** → enter the application ID.

Until the app is published in the console, only Chromecasts registered there as test
devices can load it. Leave the field empty to go back to Google's Default Media Receiver.

The receiver handles the same media messages as the default one, so nothing else changes
on the sender side.

## Known limitation

The artwork URL points at the phone over plain HTTP, while the receiver page is HTTPS.
Cast firmware that blocks mixed content shows only the text; the music is unaffected.
