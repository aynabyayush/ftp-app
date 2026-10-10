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

### 5. Cyber Themed UI
* Clean interface supporting Material You dynamic theming and custom Cyber/Glassmorphic accents.
* Custom Cyber Keyboard utility for terminal-style credential and port input.

---

## Complete Usage Guide

### 1. Setting Up and Hosting the Local FTP Server

Turn your Android phone into an FTP server to access mobile storage from PCs, Macs, TVs, or other phones on the same local network.

1. **Initial Server Setup**:
   * Open **FTPApp** and access the **Server Settings** (gear icon on the server card).
   * **Listening Port**: Set the port (default: `2221`).
   * **Passive Port Range**: Configure the passive range (e.g., `50000-50100`) for seamless client connections behind firewalls/routers.
   * **Shared Directory**: Tap **Browse** to select the root folder exposed over FTP (defaults to root internal storage `/storage/emulated/0`).
   * **Authentication**: Configure a **Username** and **Password**, or check **Anonymous Login** to allow unauthenticated read/write access.
2. **Starting the Daemon**:
   * Connect your phone to Wi-Fi or turn on your Personal Hotspot.
   * Tap **Initialize Server** / **Toggle Server**.
   * The status card will transition to **ONLINE** and display your access URL:
     ```text
     ftp://<Device-IP-Address>:<Port>
     Example: ftp://192.168.1.15:2121
     ```
3. **Connecting from an External Client (PC/Mac)**:
   * Open Windows File Explorer, macOS Finder (Cmd + K), FileZilla, or Cyberduck.
   * Enter the server URL, port, and credentials.
   * Manage files freely over the local network.
4. **Stopping the Server**:
   * Tap **Stop Server** in the app or tap the **Stop** action directly from the persistent Android system notification shade.

---

### 2. Managing Saved FTP Servers & Connecting as a Client

1. **Adding an FTP Server**:
   * On the main screen under **Saved Servers**, tap the **+** (Add) button.
   * Enter the connection details:
     * **Server Alias**: A friendly name (e.g., `Home NAS`, `Office Server`).
     * **Server IP / Host**: Enter the target hostname or IP address.
     * **Port**: Enter the server port (typically `21` for standard FTP or custom ports like `2121`).
     * **Credentials**: Enter username and password, or toggle **Anonymous**.
   * Tap **Save Server**.
2. **Connecting to a Target Server**:
   * Tap any saved server entry in your list.
   * The app pings the host, verifies credentials, and opens the **Remote File Explorer**.

---

### 3. Remote File Explorer & Transfers

1. **Navigating Remote Directories**:
   * Tap folders to navigate down the hierarchy.
   * Tap the **Folder Up** arrow icon or use the breadcrumb bar to return to parent directories.
   * Tap the **Grid / List View Toggle** button in the top bar to switch between structured lists and visual icon grids.
   * Use the **Sort** menu to order directory listings by **Name**, **Size**, or **Date Modified**.
2. **Downloading Files from Server**:
   * Tap on any file in the explorer to download the file.
   * Choose your local target directory (configured under settings or selected on-the-fly).
   * If a file with the same name already exists locally, an overwrite prompt will appear asking to **Overwrite**, **Skip**, or **Cancel**.
3. **Uploading Files to Server**:
   * Navigate to your desired remote folder.
   * Tap the **Upload** button (`+` or upload icon) in the bottom navigation.
   * The local storage picker will open. Select your target local file to initiate the upload.

---

### 4. Queue Management & Transfer Monitoring

1. **Live Transfer Status**:
   * Tap the **Active Transfers** icon in the toolbar.
   * Monitor real-time performance metrics:
     * Active file name and transfer direction (Upload / Download).
     * Current transfer throughput (`MB/s` or `KB/s`).
     * Transferred bytes vs. Total file size.
     * Progress bar with estimated remaining bytes.
2. **Transfer Cancellation**:
   * Tap the red **Cancel** button next to any running item to abort the socket stream immediately without corrupting local index tables.
3. **Completed Sessions**:
   * Review all files downloaded or uploaded during the current session under the **Completed Transfers** sub-tab.

---

### 5. Automated Backups & Real-Time Directory Sync

Automate background synchronization between local device folders and remote FTP paths.

1. **Creating a Backup Profile**:
   * Open the side drawer and select **Backup & Sync**.
   * Tap **Add Profile** and select your target saved FTP server.
   * **Source Directory**: Tap **Browse Local Folder** to pick the phone directory to monitor (e.g., `/DCIM/Camera` or `/Documents`).
   * **Destination Directory**: Tap **Browse Remote Folder** to choose where the files should be stored on the server.
   * **Sync Frequency**: Choose how often the engine checks for changes:
     * *Every 15 Minutes*
     * *Every 1 Hour*
     * *Daily*
     * *Custom (Minutes)* (enter your preferred interval)
   * Toggle **Enable Auto-Backup** and tap **Save Profile**.
2. **Monitoring the Sync Engine**:
   * Tap **Sync Monitor** on the top right corner of the home screen to view active sync passes, synced file tallies, remaining transfer payloads, and speed metrics.
   * Background syncing relies on the `RealtimeSyncService`. Ensure battery optimization exemptions are allowed if running sync unattended with the screen turned off.

---

### 6. Local File Manager & ZIP Archiving

1. **Managing Internal & External Storage**:
   * Open the **Local File Manager** from the side drawer or action menu.
   * Tap **Storage Volumes** to switch between **Internal Storage** and any mounted **External MicroSD Cards**.
2. **File & Folder Operations**:
   * Long-press or tap the 3-dot menu on any file/folder to:
     * **Move / Copy**: Select a destination directory and move/duplicate files.
     * **Rename / Delete**: Safely modify
     * **Directly transmit to FTP Server**: Directly transmit to any of the online saved FTP Address.
     * **Compress**: Can compress folder into 'zip'.

---

### 7. Screenshots

| Home Interface | Realtime Sync | FTP File Manager | Local File Manager |
| :---: | :---: | :---: | :---: |
| <img src="https://i.ibb.co/qLvQnwK3/HOMESCREEN.jpg?v=2" width="220" alt="Home Interface" /> | <img src="https://i.ibb.co/FbqhXHvZ/IMG-20261010-173321.jpg" width="220" alt="Realtime Sync" /> | <img src="https://i.ibb.co/BHJ4PFPq/IMG-20261010-173338.jpg" width="220" alt="FTP File Manager" /> | <img src="https://i.ibb.co/RGn2N9f8/IMG-20261010-172925.jpg" width="220" alt="Local File Manager" /> |

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
