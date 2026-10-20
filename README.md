# MultiMedia

A client-side Fabric mod for Minecraft 26.3 that plays YouTube on jukeboxes and on screens built from blocks, in sync for everyone who has the mod. You can also stream your own monitor (with sound) onto in-game screens for your friends.

Download: [Modrinth](https://modrinth.com/mod/multimedia-cinema)

## Features

- **Jukebox + disc + sign with a YouTube link** = music for everyone with the mod, at the same position in the song.
- **Sound systems:** a main sign with link + channel (e.g. `#1.1`), speaker signs with just the channel.
- **Screens:** a wall of one block type with a sign saying `screen` + link (or channel) below it shows the video.
- **Playlists** with skip buttons, `mix` (shuffle), `loop`, volume (`80%`) and `left`/`right` stereo.
- **Screen streaming:** stream a monitor and the sound of a chosen program to friends. Direct peer-to-peer connection (UDP hole punching), no server plugin and no port forwarding needed.
- Walls and blocks dampen the sound; everything is adjustable in the settings (Mod Menu).
- English and German.

Client-side only: the server doesn't need the mod, and players without it simply hear nothing.

## Requirements

- Minecraft 26.3, Fabric Loader, Fabric API, Java 25
- Windows for screen streaming. On first use the mod downloads [yt-dlp](https://github.com/yt-dlp/yt-dlp), [FFmpeg](https://github.com/yt-dlp/FFmpeg-Builds) and [Deno](https://github.com/denoland/deno) from their official GitHub releases.

## Commands

`/disc` (also `/mm`) shows the help. Examples: `/disc check`, `/disc walls <0-10>`, `/disc titles on|off`, `/disc update`.

## Building

```
./gradlew build
```

The jar ends up in `build/libs/`. The small Windows audio helper (`native/mmaudio.cpp`, captures the sound of one program) is prebuilt in `src/client/resources/multimedia/mmaudio.exe`. To rebuild it with MinGW:

```
x86_64-w64-mingw32-g++ -O2 -static -municode native/mmaudio.cpp -o mmaudio.exe -lole32 -luuid -lmmdevapi -lwinmm
```

## License

[MIT](LICENSE)
