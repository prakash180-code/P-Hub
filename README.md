# P-Hub

P-Hub is an Android app for sharing files between devices over a local Wi-Fi network — no internet, no cables, no size limits. One device runs as a server, and other devices connect as clients (or via any browser) to browse, download, upload, and manage files.

## Features

- **Peer-to-peer file sharing** over local Wi-Fi (HTTP on port 8080)
- **Built-in Ktor server** that runs as a foreground service with wake lock
- **Automatic server discovery** via NSD (DNS-SD) — clients find the server without typing an IP
- **QR code connect** — server shows a QR code, client phones scan it to connect
- **Two sharing modes:**
  - **Selective Share** — pick specific files to share, nothing else is exposed
  - **Full Storage Access** — browse the entire phone storage (requires "All files access" permission)
- **Web Explorer UI** — any device on the network can browse, upload, download, preview, create folders, rename, copy, move, and delete files from a browser (list/details/preview views, drag & drop uploads, sorted columns)
- **4-digit authentication** — the server displays 4 codes (the real key is highlighted); clients must pick the correct one
- **Android companion client** — sync selected files from the server to the phone with live progress (per-file + overall), transfer speed, and estimated time remaining
- **Selective sync** — pick which files to download instead of syncing everything
- **File operations via API** — mkdir, rename, copy, move, delete endpoints secured by the auth key
- **Progress tracking** on both sides (server shows per-file upload/download progress in real time)

## Tech Stack

- 100% Kotlin + Jetpack Compose (Material 3)
- [Ktor](https://ktor.io/) Server (Netty) with Content Negotiation + kotlinx.serialization
- OkHttp client
- ZXing for QR code generation
- NSD API for local network service discovery
- Foreground services for both server and sync client

## Requirements

- Android 10 (API 29) or higher
- Both devices on the same Wi-Fi network
- Full Storage Access permission for the "Full Storage Access" mode (Android 11+)

## Setup

1. Open the project in Android Studio.
2. Sync Gradle (JDK 11).
3. Build and run on a device.

## Usage

### As Server

1. Open P-Hub and toggle **Server** mode.
2. Tap **Start Server** — your IP and a QR code are shown.
3. Choose **Selective Share** (pick files) or **Full Storage Access**.
4. Share the 4-digit code shown (the **bold** code is the real key) or let clients scan the QR code.

### As Client (Android app)

1. P-Hub auto-discovers the server over NSD. You can also type the IP manually.
2. Tap **Test** to check the connection, then **Browse** to see the server's files.
3. Enter the correct 4-digit key when prompted.
4. Select files and tap **Sync Selected Files** — files are saved to `Download/P-Hub`.

### Via Browser

Open `http://<server-ip>:8080` on any device — you get the full Web Explorer with upload, download, preview, and file management.

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Local network server + client |
| `MANAGE_EXTERNAL_STORAGE` | Full storage access mode (Android 11+) |
| `READ_MEDIA_IMAGES/VIDEO/AUDIO`, `READ/WRITE_EXTERNAL_STORAGE` | Access media files (older Android) |
| `FOREGROUND_SERVICE_DATA_SYNC` | Run server and sync as foreground services |
| `WAKE_LOCK` | Keep the server running while the screen is off |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` | NSD discovery over Wi-Fi |
| `POST_NOTIFICATIONS` | Server/sync notifications |

## API Endpoints

| Endpoint | Description |
|---|---|
| `GET /` | Web Explorer UI |
| `GET /challenge` | List of auth codes to choose from |
| `GET /roots?auth=` | List storage roots (or "Shared Files" in selective mode) |
| `GET /ls?auth=&path=` | Directory listing |
| `GET /files?auth=` | Flat file list (client app sync) |
| `GET /download?auth=&path=` | Download a file |
| `GET /preview?auth=&path=` | Preview images |
| `POST /upload?auth=&path=&name=` | Upload files |
| `POST /action?auth=` | File ops: `mkdir`, `rm`, `mv`, `cp` |

## Project Structure

```
app/src/main/java/com/prakash/phub/
├── MainActivity.kt        # UI: client/server modes, QR, auth challenge, progress
├── ServerService.kt       # Ktor server, NSD registration, file streaming
├── SyncService.kt         # Background sync client (downloads from server)
├── FileSharingRegistry.kt # Shared state (selected files, auth codes, modes)
└── ui/theme/              # Compose theme
```

## License

Private project.
