# FTPApp (FTP Client & Server)

A modern, high-performance Android utility combining a full-featured **FTP Client**, a background **Local FTP Server**, and an automated **Real-time Synchronization Engine**. Built with Kotlin, Material 3, and Apache networking libraries.

---

## Features

### 1. Dual-Engine Architecture
* **FTP Client**: Connect to any remote FTP server. Browse files in list or grid view, filter directories, inspect file details, and manage concurrent downloads and uploads.
* **Embedded Local FTP Server**: Transform your Android device into a standalone FTP server powered by Apache FtpServer and Apache MINA.
  * Supports Anonymous and credential-based authentication.
  * Configurable listening port (default: `2121` / custom) and passive port ranges (e.g., `50000-50100`).
  * Runs as an uninterrupted Android Foreground Service with background WakeLock and WifiLock handling.

### 2. Real-Time Sync & Automated Backups
* **Automated Sync Engine**: Schedule recurring sync profiles (every 15 minutes, hourly, daily, or custom interval).
* **Targeted Backups**: Map specific local folders directly to remote FTP directories.
* **Transfer Queue & Overwrite Protection**: Active transfer monitor with speed metrics (MB/s), remaining bytes indicator, and interactive overwrite confirmation dialogues.

### 3. Integrated Media Suite
* **In-App Video Player**: Stream or view local and remote video files directly inside the app. Features include timeline scrubbing, forward/rewind controls, and subtitle parsing (`.srt` and `.vtt` formats).
* **Audio Player**: Dedicated playback modal with track details, disc animation, and 10-second skip shortcuts.
* **Image Viewer & Editor**: High-resolution image preview with on-device rotation, canvas cropping, and export options.

### 4. Advanced Local File Management
* **Multi-Volume Support**: Browse internal storage and external SD cards.
* **File Operations**: Move, copy, delete, rename, and view detailed metadata (permissions, MIME type, size, modified date).
* **Archive Utility**: Built-in ZIP compression engine to package directories directly on device before transfer.
* **External Sharing**: Native Android share sheet integration to push files to other applications or directly to saved FTP endpoints.

### 5. Cyber & Glassmorphic UI
* Clean interface supporting Material You dynamic theming and custom Cyber/Glassmorphic accents.
* Custom Cyber Keyboard utility for terminal-style credential and port input.

---

## Tech Stack & Libraries

* **Language**: Kotlin
* **Architecture**: Android Jetpack (ViewBinding, Jetpack Compose foundation, ViewModel, Lifecycle Scope)
* **Networking & Protocols**:
  * [Apache Commons Net](https://commons.apache.org/proper/commons-net/) – FTP client implementation
  * [Apache FtpServer & Apache MINA](https://mina.apache.org/ftpserver-project/) – Embedded FTP server daemon
* **Concurrency**: Kotlin Coroutines & Flow
* **Minimum SDK**: Android 10 (API level 29)
* **Target SDK**: Android 14+ (API level 34+)

---

## Permissions Overview

To operate as a server and file synchronizer, FTPApp utilizes the following permissions:

| Permission | Purpose |
| :--- | :--- |
| `INTERNET` & `ACCESS_WIFI_STATE` | Network connection management and binding the FTP server socket |
| `MANAGE_EXTERNAL_STORAGE` | Direct read/write access to internal storage and external SD volumes |
| `POST_NOTIFICATIONS` | Foreground service notifications to monitor server and sync states |
| `WAKE_LOCK` | Keeping the CPU active during massive background transfers and scheduled sync loops |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Preventing OEM power managers from killing the background sync engine |

---

## Installation & Setup

### Building from Source

1. **Clone the repository:**
   ```bash
   git clone [https://github.com/ayna-gaming/ftp-app.git](https://github.com/ayna-gaming/ftp-app.git)
   cd ftp-app
