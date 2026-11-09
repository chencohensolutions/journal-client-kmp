# Showing on a TV (Google Cast)

Asked for on Nov 9, 2026: photos and videos from the viewer on the owner's Google TV Streamer, and on any TV with
Google Cast, without installing anything on the TV. Swiping and the slideshow on the phone change the TV; zooming on
the phone zooms the TV, and the phone shows the whole photo with a frame of what the TV shows. The originals go to the
TV unchanged (the owner didn't want them re-encoded); the TV decodes them itself.

Android only; iOS not built yet ([ios-todo.md](ios-todo.md)).

## How it fits together

```
phone (viewer)                                   TV (Cast receiver page)
  JournalCast ── Cast session, namespace ──────►  receiver/receiver.js
  CastController (shared): show, preload,          shows a photo at the phone's frame, plays a video,
    frame, video, mute, chunk, stop                 answers hello, shown, video, error, need
  CastServer: http://<phone's Wi-Fi IP>:<port>/<random token>/<n>/<name>
             ◄────────────── GET (a photo whole, a video in ranges) ──────
```

- **The receiver** is our own Cast "Custom Receiver": a web page, `receiver/` at this repo's root, served by GitHub
  Pages at `https://chencohensolutions.github.io/journal-client-kmp/receiver/` (Settings › Pages: deploy from
  `main`, `/ (root)`; `.nojekyll` keeps Pages from running Jekyll on the rest). The Cast console
  ([cast.google.com/publish](https://cast.google.com/publish), $5 once) registers that URL and gives an app id;
  `JournalCast.APP_ID` holds it. While the receiver is unpublished, only TVs listed there as test devices (by serial
  number, then restarted) run it; publishing it opens it to any Cast TV.
- **The phone's web server** (`CastServer.kt`) runs only while casting. Each item the viewer sends gets a path with a
  random token; it answers `GET`/`HEAD` with `Range` (videos seek), and `OPTIONS` with CORS and
  `Access-Control-Allow-Private-Network`, as the page is on GitHub Pages and the phone on the home network. It serves
  the library's file, an original the app kept, or a server photo's original downloaded now (`ServerPreviews.original`);
  a video only on the server streams through the video cache (`ServerVideos.playable`). A JPEG XL the server packed
  from a JPEG goes as that JPEG, byte for byte (`JpegXl.unpack`): it is the original, and the TV decodes JPEG itself.
  A photo the TV can't read (DNG, other raw files) goes as a JPEG made on the phone at 3840 px.
- **The receiver decodes**: JPEG, PNG, WebP, GIF and AVIF by the TV's browser; HEIC/HEIF with libheif-js 1.23.5 and
  JPEG XL with jxl-oxide-wasm 0.12.6, both WebAssembly, vendored in `receiver/vendor/` with their licences and loaded
  the first time one comes. HEIC is decoded in a worker (`receiver/heif-worker.js`): libheif-js compiles its
  WebAssembly synchronously, which the TV's Cast browser (Chromium 92 on the Streamer, Nov 2026) refuses on the page's
  thread for anything over 4 KB. The kind is told from the bytes. Photos are kept at most 6144 px on their longer side, so
  zooming stays sharp on a 4K TV; the one shown, the next three and the one before are kept decoded.
- **The frame** (`TvZoom` in `sharedLogic/.../cast/CastFrame.kt`) is a rectangle of the TV's shape in fractions of the
  upright photo. At scale 1 it shows the whole photo (reaching past it where the TV has black bars); a pinch zooms it
  about the fingers (up to 12×), one finger drags it once zoomed, a double tap goes back to the whole photo. Frames go
  at most every 40 ms; the TV glides to each.

## Messages

JSON on `urn:x-cast:com.chencohensolution.journal`, listed at the top of `receiver/receiver.js` and built by
`CastController` (`CAST_NAMESPACE`). Phone to TV: `hi`, `show {item, frame?, autoplay, muted, position}`,
`preload {items}`, `frame {id, x, y, w, h}`, `video {action, position?}`, `mute`, `chunk`, `stop`. TV to phone:
`hello {width, height}` (the frame's shape), `shown {id}`, `video {id, position, duration, playing, ended}` (each
second and on each change; `ended` lets the slideshow go on), `error {id, message}`, `need {id}`.

## If the TV can't fetch from the phone

Seen on Nov 9, 2026: the phone's Wi-Fi dozes between packets and drops the TV's requests for seconds at a time, so
`JournalCast` holds Wi-Fi locks (low latency, and high performance) while casting, with `CastService` (a
foreground service with a "Showing on <TV>" notification and Stop) keeping the app counted as in front, as a
low-latency lock does nothing for an app in the background; and the receiver gives up a fetch
that is silent for 10 s and tries three times before asking for chunks. A receiver page the phone left without stopping
closes itself after a minute, as a stale one blocks the next cast (error 2252 on the phone).

The receiver page is https and the phone's server plain http on the home network. Whether the TV's browser lets the
page fetch from it (mixed content, and Chrome's Private Network Access) was not known when this was built, and can only
be tried on a real TV. If the fetch fails, the receiver sends `need`, and the phone sends the photo's bytes over the
Cast session itself in 45 KB pieces (`chunk`, base64; up to 80 MB). Videos have no such way round: if they don't
play, that is the cause, and another route is needed (the TV can't trust the journal server's own certificate either).

## Trying it

- The receiver in a browser, without a TV: serve `receiver/` and open `index.html?local`; `window.receive({...})` takes
  the phone's messages and `window.sent` collects the replies. JPEG (with EXIF rotation), HEIC, JPEG XL, a zoomed
  frame and the chunk fallback were tried that way in Chromium on Nov 9, 2026.
- On the TV: the app id in `JournalCast.APP_ID`, the Streamer added as a test device, the page published on Pages.
  Chrome's `chrome://inspect` on a computer on the same Wi-Fi shows the receiver's console.

## Code

- `receiver/`: `index.html`, `receiver.js` (Cast, messages, drawing, video), `decode.js` (formats), `vendor/`.
- `sharedLogic/.../cast/`: `CastController.kt` (messages, the outbox, frames, chunks, `castAhead`), `CastFrame.kt`
  (`TvZoom`, `CastFrame`); tests in `CastTest.kt`.
- `androidApp/.../ui/library/`: `JournalCast.kt` (app id, `JournalCastOptions`, finding TVs with MediaRouter, the
  session), `CastServer.kt`, `CastViews.kt` (Cast button and TV picker, the frame overlay and its gestures, a video's
  TV controls); the viewer's wiring in `ViewerScreen.kt`.
