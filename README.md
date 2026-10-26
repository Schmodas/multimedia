# MultiMedia

A client-side Fabric mod for Minecraft 26.3 that plays YouTube on jukeboxes and on screens built from blocks, in sync for everyone who has the mod. You can also stream your own monitor (with sound) onto in-game screens for your friends.

Download: [Modrinth](https://modrinth.com/mod/multimedia-cinema)

## Features

- **Jukebox + disc + sign with a YouTube link** = music for everyone with the mod, at the same position in the song.
- **Sound systems:** a main sign with link + channel (e.g. `#1.1`), speaker signs with just the channel.
- **Several channels per speaker:** `#cinema #1.2` plays the cinema while a film runs, otherwise the house music. The main sign decides whether a pause holds or releases the channel.
- **Screens:** a wall of one block type with a sign saying `screen` + link (or channel) below it shows the video.
- **Playlists** with skip buttons, `mix` (shuffle), `loop`, volume (`80%`) and `left`/`right` stereo.
- **Screen streaming:** stream a monitor and the sound of a chosen program to friends. Direct peer-to-peer connection (UDP hole punching), no server plugin and no port forwarding needed.
- Realistic sound: behind walls mostly the bass gets through, far away it sounds softer, underwater it's muffled; rooms echo depending on their size and material. Everything is adjustable in the settings (Mod Menu).
- English and German.

Client-side only: the server doesn't need the mod, and players without it simply hear nothing.

## Requirements

- Minecraft 26.3, Fabric Loader, Fabric API, Java 25
- Windows for screen streaming. On first use the mod downloads [yt-dlp](https://github.com/yt-dlp/yt-dlp), [FFmpeg](https://github.com/yt-dlp/FFmpeg-Builds) and [Deno](https://github.com/denoland/deno) from their official GitHub releases.

## Commands

`/disc` (also `/mm`) shows the help. Examples: `/disc check`, `/disc walls <0-10>`, `/disc titles on|off`, `/disc update`.

## Building

```
./scripts/build-audio-helper.sh   # Windows audio helper from native/mmaudio.cpp (needs MinGW-w64)
./gradlew build
```

The jar ends up in `build/libs/`. Without the first step the mod still builds, but screen streaming has no sound on Windows.

The repository contains **no prebuilt binaries**. The small audio helper (`native/mmaudio.cpp`, captures the sound of one program for screen streaming) is compiled from source by the script above; the build is reproducible (no timestamp in the exe).

## Releases

Every release on Modrinth is built and published by GitHub Actions ([`release.yml`](.github/workflows/release.yml)) from the tagged source in this repository – nothing is uploaded by hand. The build log shows the SHA-256 of the jar and of the audio helper, so you can compare them with the file on Modrinth.

## License

[MIT](LICENSE)
