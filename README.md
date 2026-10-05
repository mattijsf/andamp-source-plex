# Andamp Plex source

A music source for [Andamp](https://github.com/mattijsf/andamp) that plays the music on
your own Plex Media Server. Sign in with a code at plex.tv/link, or with the server's
address and a token.

It is a separate app. Once installed, Andamp lists it under Preferences > Music sources and
in the Media Library. This app's one screen is where you sign in and pick the music
library. Audio is decoded here and played by Andamp through its own equalizer, effects and
visualizer.

Covers come from the server, which hands out a picture only with a token. The cover URLs
this source gives Andamp carry the server's token, and Andamp writes them into playlists
you save. Keep such a playlist file to yourself, or sign out and in again to get a new
token.

## Get it

The APK is at [andamp.nl/extensions/plex](https://andamp.nl/extensions/plex).
Andamp notifies you when a newer version is available.

## Build

JDK 17 and the Android SDK (compile SDK 36).

```bash
./gradlew assembleDebug      # the APK
./gradlew installDebug       # onto a connected phone, beside Andamp
./gradlew gate               # format, detekt, unit tests and goldens
```

`lefthook install` sets up the pre-push hook, which runs `gate`.

The app is built against Andamp's source SDK, `nl.mattix.andamp:source-api` and
`nl.mattix.andamp:source-common`, from Maven Central. To test an unreleased SDK change,
run `./gradlew publishToMavenLocal` in a checkout of the player; the local artifacts take
precedence.

Release signing is optional. A `keystore.properties` file in the project root, which git
ignores, may hold `storeFile` (a path relative to the project root), `keyAlias`,
`storePassword` and `keyPassword`. `SOURCE_STORE_PASSWORD` and `SOURCE_KEY_PASSWORD` in the
environment take precedence over the two passwords in the file.

- Without the file, or without `storeFile` in it, `assembleRelease` makes an unsigned APK.
- With `storeFile`, the APK is signed with that key. The build fails when a password is in
  neither the environment nor the file.

## Writing your own source

The contract is documented in the player's
[source-packs.md](https://github.com/mattijsf/andamp/blob/main/docs/source-packs.md).
[andamp-source-template](https://github.com/mattijsf/andamp-source-template) is the
smallest source that builds.

## License

Copyright (C) 2026 Mattix (Mattijs Fuijkschot).

GNU General Public License, version 3 or later; see [LICENSE](LICENSE). The SDK is
Apache-2.0, so a source you write may use any license. Third-party attribution is in
[NOTICE.md](NOTICE.md).
