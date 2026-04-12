# 💬 Social Chat App

![Java](https://img.shields.io/badge/Java-ED8B00?style=flat&logo=openjdk&logoColor=white)
![JavaFX](https://img.shields.io/badge/JavaFX-007396?style=flat&logo=java&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL-4479A1?style=flat&logo=mysql&logoColor=white)
![BCrypt](https://img.shields.io/badge/BCrypt-6DB33F?style=flat&logo=springsecurity&logoColor=white)
![FastAPI](https://img.shields.io/badge/FastAPI-009688?style=flat&logo=fastapi&logoColor=white)
![TCP Sockets](https://img.shields.io/badge/TCP%20Sockets-FF6B35?style=flat&logo=cloudflare&logoColor=white)
![CSS3](https://img.shields.io/badge/CSS3-1572B6?style=flat&logo=css3&logoColor=white)
![FXML](https://img.shields.io/badge/FXML-007396?style=flat&logo=java&logoColor=white)

> A JavaFX desktop social chat application built on a custom TCP socket protocol, with real-time messaging, file transfer, group chat, LAN voice/video calling, and AI-powered smart reply suggestions.

📦 **Repository:** [github.com/DangHuuLong/Social-Chat-App](https://github.com/DangHuuLong/Social-Chat-App)

---

## 🌟 Overview

**Social Chat App** is a Java desktop application implementing a full client-server chat platform. The client and server communicate over TCP sockets using a custom binary frame protocol. The application is built with JavaFX for the UI, MySQL for persistence, and supports a broad set of messaging, file sharing, group chat, and calling features.

The project is structured as a single repository containing the client, server, and a shared common layer used by both sides.

---

## ✨ Highlights

- 🖥️ **JavaFX desktop UI** with multi-panel layout (sidebar, chat area, media/info panel)
- 🔌 **Custom frame-based TCP socket protocol** using `Frame` and `FrameIO`
- 🗄️ **MySQL-backed persistence** with BCrypt password hashing
- 📬 **Offline message delivery** — messages queued and delivered on reconnect
- 📁 **Chunked file transfer** — up to 25 MB, 64 KB chunks, with image/audio/video/document classification
- 🎙️ **Voice message recording** with preview before sending (max 30 seconds)
- 👥 **Group chat** with member management and owner reassignment
- 📞 **LAN voice and video calling** with microphone/camera toggle
- 🤖 **Smart reply integration** via a local FastAPI endpoint

---

## 📸 Screenshots

<div align="center">

<table>
  <tr>
    <td align="center">
      <img src="https://github.com/user-attachments/assets/4f0998fc-d5d2-44db-b24a-ed60f47b6c53" width="420" /><br/>
      <sub><b>Welcome Screen</b></sub>
    </td>
    <td align="center">
      <img src="https://github.com/user-attachments/assets/1912c96a-1a45-49f4-bae6-ad143ab4e6ca" width="420" /><br/>
      <sub><b>Chat Interface</b></sub>
    </td>
  </tr>
</table>

</div>

---

## ✅ Features

### 🔐 Authentication & User Management
- Register and login with email/password
- Avatar selection during registration
- BCrypt password hashing
- Online/offline presence display with last seen timestamp
- User list loading in sidebar

### 💬 Messaging
- Real-time direct messaging over TCP
- Offline queued message delivery
- Conversation history loading
- Message reply, edit, and delete
- Message search

### 📁 File & Media Messaging
- Upload and download files (max **25 MB**, **64 KB** chunks)
- Image, audio, video, and document classification
- Voice message recording and sending (max **30 seconds**) with preview
- In-memory media playback via local HTTP streaming helper
- Media and document panel in the right sidebar
- Image, video, and document preview and download

### 👥 Group Chat
- Create groups, add and remove members
- Delete group
- List members
- Group conversation history loading
- Group messaging
- Owner reassignment when the owner leaves and members remain

### 📞 Calling
- Call signaling over custom socket frames (invite / accept / reject / cancel / busy / end)
- LAN audio session (PCM audio streaming over sockets)
- LAN video session (webcam JPEG frame streaming over sockets)
- Dedicated video call window with local preview and remote video view
- Toggle microphone and camera during a call

### 🤖 Smart Reply
- Server requests smart reply suggestions from a local FastAPI endpoint at `http://localhost:8000/smart-reply`
- Suggestions are forwarded to the client on success

---

## 🛠️ Tech Stack

| Layer | Technology |
|-------|------------|
| Language | Java |
| UI Framework | JavaFX + FXML |
| UI Styling | CSS (light & dark theme support) |
| Networking | TCP Sockets — custom `Frame` / `FrameIO` protocol |
| Database | MySQL (`socialchatapp` database) |
| Security | BCrypt password hashing |
| Media Playback | JavaFX Media APIs + `InMemoryMediaServer` (local HTTP) |
| Calling | `LanAudioSession` (PCM), `LanVideoSession` (JPEG over socket) |
| Smart Reply | Local FastAPI endpoint (`http://localhost:8000/smart-reply`) |

---

## 🏗️ Architecture

The repository is organized into three main layers:

```
┌─────────────────────────────────────────┐
│              JavaFX Client              │
│  (UI Controllers · ClientConnection)    │
└─────────────────┬───────────────────────┘
                  │ TCP · Custom Frame Protocol
                  │ Port 5000
                  ▼
┌─────────────────────────────────────────┐
│           Java Socket Server            │
│  (ClientHandler · DAOs · CallRouter)    │
└──────────┬──────────────────────────────┘
           │                    │
           ▼                    ▼
        MySQL              FastAPI
    (socialchatapp)   (Smart Reply · :8000)
```

- **Client** — JavaFX application managing UI, user interaction, and socket communication
- **Server** — Multi-client socket server handling message routing, persistence, and call signaling
- **Common** — Shared protocol and model classes used by both sides (`Frame`, `FrameIO`, `MessageType`, `Message`, `User`, etc.)

---

## 📁 Project Structure

```
Social-Chat-App/
├── src/
│   ├── client/
│   │   ├── ClientMain.java               # Client entry point
│   │   ├── ClientConnection.java         # Socket/network gateway
│   │   └── controller/
│   │       ├── MainController.java       # Login/register flow
│   │       ├── HomeController.java       # Main screen coordinator
│   │       ├── LeftController.java       # Sidebar, user list, presence
│   │       ├── MidController.java        # Chat area, messages, file/call state
│   │       ├── RightController.java      # Media/document panel, preview
│   │       └── handler/
│   │           ├── CallHandler.java
│   │           ├── FileHandler.java
│   │           ├── MediaHandler.java
│   │           ├── MessageHandler.java
│   │           ├── SearchHandler.java
│   │           ├── SessionHandler.java
│   │           ├── UIMessageHandler.java
│   │           └── VoiceRecordHandler.java
│   │
│   ├── server/
│   │   ├── ServerMain.java               # Server entry point (port 5000)
│   │   ├── ClientHandler.java            # Per-client frame handler
│   │   ├── CallRouter.java               # Call signaling routing
│   │   ├── MessageService.java           # Message/file deletion workflow
│   │   └── dao/
│   │       ├── UserDAO.java
│   │       ├── MessageDAO.java
│   │       ├── FileDAO.java
│   │       ├── GroupDAO.java
│   │       └── GroupMessageDAO.java
│   │
│   └── common/
│       ├── Frame.java                    # Custom binary frame
│       ├── FrameIO.java                  # Frame read/write
│       ├── MessageType.java              # Protocol message types
│       ├── Message.java
│       ├── GroupMessage.java
│       ├── Group.java
│       ├── User.java
│       └── FileResource.java
│
├── resources/
│   ├── Main.fxml
│   ├── Home.fxml
│   ├── VideoCall.fxml
│   └── *.css                            # UI stylesheets (light/dark theme)
│
├── uploads/                             # Server-side file storage
├── temp/                                # Temporary working files
├── bin/                                 # Compiled output
├── build.fxbuild
├── .classpath
├── .project
└── README.md
```

---

## ⚙️ How the System Works

### 🖥️ Client Side
The client connects to the server at `127.0.0.1:5000` via `ClientConnection`. After authentication, the `HomeController` initializes three sub-controllers: `LeftController` (sidebar and user list), `MidController` (chat area and message logic), and `RightController` (media/info panel). Specialized handlers under the controller layer manage calls, file transfers, voice recording, media playback, search, and session management.

### 🖧 Server Side
`ServerMain` opens a TCP server socket on port `5000` and spawns a `ClientHandler` thread for each connected client. The server handles message routing, offline queuing, file storage/retrieval, group management, and call signaling delegation via `CallRouter`. DAO classes manage all MySQL interactions. `MessageService` handles deletion workflows.

### 🔗 Shared Protocol / Model Layer
Both sides share the `common` package. `Frame` is the base unit of communication, and `FrameIO` handles serialization and deserialization. `MessageType` defines all protocol operations. Shared model classes (`User`, `Message`, `GroupMessage`, `Group`, `FileResource`) ensure type consistency across the client-server boundary.

---

## 💬 Messaging Flow

```
Client A types message
    └─► MidController → MessageHandler
            └─► ClientConnection → Frame(SEND_MESSAGE) → Server
                    └─► ClientHandler → MessageDAO (persist)
                            └─► Frame(RECEIVE_MESSAGE) → Client B (if online)
                            └─► Queue (if Client B offline → deliver on reconnect)
```

- Offline messages are queued on the server and delivered when the recipient reconnects.
- Smart reply: after storing the message, the server calls `http://localhost:8000/smart-reply` and forwards suggestions to the recipient if the request succeeds.

---

## 📁 File & Media Flow

```
Client A selects file
    └─► FileHandler → FILE_META frame (filename, size, type)
            └─► FILE_CHUNK frames × N  (64 KB each, max 25 MB)
                    └─► Server stores file → notifies Client B
                            └─► Client B downloads on demand
```

- Files are classified as image, audio, video, or document.
- Voice messages are recorded locally (max 30 seconds), previewed, then sent as audio chunks.
- Media playback on the client uses JavaFX Media APIs. An `InMemoryMediaServer` exposes in-memory audio/video as local HTTP URLs to support JavaFX's media player.

---

## 👥 Group Chat Flow

- A user creates a group and adds members.
- Group messages follow the same `Frame`-based flow, routed to all active members by the server.
- Members can be added or removed by authorized users.
- If the group owner leaves and other members remain, ownership is reassigned automatically.
- The group can be deleted by the owner.

---

## 📞 Call Flow

```
Caller sends CALL_INVITE frame
    └─► Server (CallRouter) → forwards to Callee
            ├─► Callee accepts  → CALL_ACCEPT → LAN media session opens
            ├─► Callee rejects  → CALL_REJECT
            ├─► Callee busy     → CALL_BUSY
            └─► Caller cancels  → CALL_CANCEL

During call:
    LanAudioSession — PCM audio streamed over direct socket
    LanVideoSession — JPEG frames from webcam streamed over direct socket
    Toggle mic / camera supported
    Dedicated VideoCall.fxml window with local + remote views

Either party sends CALL_END → session closes
```

> ⚠️ The calling implementation is LAN-oriented. It relies on direct socket connections between peers and is not designed for NAT traversal or internet-grade calling.

---

## 🗄️ Database Summary

- Database name: `socialchatapp`
- JDBC connection via MySQL driver
- **Users** — id, name, email, hashed password (BCrypt), avatar, online status, last seen
- **Messages** — direct messages with sender, receiver, content, timestamp, edit/delete state
- **Files** — file metadata linked to messages
- **Groups** — group records with owner reference and member list
- **GroupMessages** — group message records with group and sender references

---

## 🎨 UI Overview

The main application window is divided into three panels:

| Panel | Responsibility |
|-------|---------------|
| **Left Sidebar** | User/group list, search, online presence, navigation |
| **Center Chat Area** | Message history, input bar, reply flow, voice record button |
| **Right Info/Media Panel** | Contact info, media tabs (Photos · Videos · Documents), settings |

Additional UI surfaces:
- **Welcome screen** (`Main.fxml`) — Login and Register entry point
- **Video Call window** (`VideoCall.fxml`) — Local preview and remote video view
- **Search dialog** — In-conversation message search
- **Voice record dialog** — Record, preview, and send voice messages
- Light and dark theme support via CSS stylesheets

---

## 🚀 Getting Started

### ✅ Prerequisites

- **Java JDK 11+**
- **JavaFX SDK** (configured on the module path)
- **MySQL** server running locally
- **Python 3 + FastAPI** (required only for smart reply functionality)
- A Java IDE such as **Eclipse** or **IntelliJ IDEA**

### 🗄️ Database Setup

1. Create a MySQL database named `socialchatapp`
2. Run the SQL schema scripts to create the required tables (users, messages, files, groups, group_messages)
3. Update the JDBC connection settings in the server source if your MySQL credentials differ from the defaults

### 🤖 Smart Reply Service (Optional)

Start the local FastAPI service on port `8000` before running the server if smart reply functionality is needed:

```bash
uvicorn main:app --port 8000
```

If the service is not running, the server will gracefully skip smart reply suggestions.

---

## ▶️ How to Run

### 1. Run the Server

1. Import the project into your Java IDE
2. Configure the JavaFX library and MySQL JDBC driver on the build path
3. Verify the MySQL connection settings in the server source
4. Run `ServerMain.java`

The server will start listening on **port 5000**.

### 2. Run the Client

1. In the same or a separate IDE window, run `ClientMain.java`
2. The client connects to `127.0.0.1:5000` by default
3. Register a new account or log in with existing credentials

Multiple client instances can be launched simultaneously to test real-time messaging.

---

## 📜 Available Concepts / Entry Points

| Component | Entry Point |
|-----------|-------------|
| Client application | `ClientMain.java` |
| Server application | `ServerMain.java` |
| Server socket port | `5000` |
| Default client target | `127.0.0.1:5000` |
| Smart reply endpoint | `http://localhost:8000/smart-reply` |
| File chunk size | 64 KB |
| Max file size | 25 MB |
| Max voice duration | 30 seconds |

---

## ⚠️ Limitations

- **LAN-only calling** — `LanAudioSession` and `LanVideoSession` use direct socket connections. NAT traversal and internet-grade calling are not supported.
- **Local server assumption** — The client is configured to connect to `127.0.0.1:5000`. Connecting over a network requires manual configuration.
- **Manual project setup** — The project does not include a build automation file (Maven/Gradle). Setup requires manual IDE and library configuration.
- **Smart reply dependency** — The smart reply feature requires a separately running FastAPI service. It is not bundled with the application.
- **Local database** — MySQL must be installed and configured locally with the correct schema before the server can start.
- **JavaFX configuration** — JavaFX must be explicitly configured on the module path, as it is not bundled with standard JDK distributions.

---

## 🔮 Future Improvements

- 📦 Add Maven or Gradle build automation for easier dependency management and packaging
- 🌐 Support connections over a network beyond the local machine (configurable host/port)
- 🔒 Improve transport security (e.g. TLS over the socket connection)
- 📞 Extend calling support beyond LAN (e.g. STUN/TURN-based signaling)
- 🐳 Containerize the server and database for simpler deployment
- 🔄 Add refresh token / session expiry handling
- 📖 Improve inline documentation and provide a schema migration script
- 🎨 Refine the dark/light theme switcher with user preference persistence

---

## 🎓 Academic Context

This project was developed as a **university-level software engineering project** to demonstrate the design and implementation of a full client-server desktop application. It covers key topics including socket-based networking, custom binary protocols, multi-threaded server design, database integration, and desktop UI development with JavaFX.

---

<p align="center">
  Built with ❤️ by <a href="https://github.com/DangHuuLong">DangHuuLong</a> using Java & JavaFX
  <br/>
  <a href="https://github.com/DangHuuLong/Social-Chat-App">Repository</a>
</p>
