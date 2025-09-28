# 📦 SnapShare — Fast & Simple Peer-to-Peer File Sharing

**SnapShare** is a lightweight, peer-to-peer file sharing app built with **Java sockets + multithreading** on the backend and a **Next.js + React frontend**.  
It lets you upload one or more files, generate an invite code, and share instantly — without relying on third-party cloud storage.

---

## 🚀 Features
- 🔗 **Peer-to-Peer Transfer** — direct file streaming over sockets, no external storage required  
- 🧵 **Multithreaded Backend** — concurrent downloads via `ExecutorService`  
- 📂 **Flexible Uploads** — single file served directly; multiple files auto-bundled into a zip  
- 🎯 **Invite Code Sharing** — unique code for every session, easy to share  
- 📥 **Modern UI** — drag-and-drop multi-file upload with progress bars and file type icons  
- 🌍 **Cross-Origin Ready** — JSON APIs with CORS enabled  
- 📧 **Share Options** — email or one-click copy for WhatsApp/clipboard  
- 🔒 **Safe Storage** — UUID-prefixed filenames stored in `uploads/` directory to prevent conflicts  

---

## 🛠 Tech Stack
**Backend**
- Java (Core) — `com.sun.net.httpserver.HttpServer`
- Socket programming + multithreading
- Streaming I/O for large files
- JSON (Jackson)

**Frontend**
- Next.js (React + TypeScript)
- TailwindCSS
- React Dropzone
- React Icons

**Deployment**
- Docker & Docker Compose
- Vercel (frontend)
- Railway / Heroku (backend)
- Optional persistent storage (local volume or S3)

---

## 📂 How It Works
1. User uploads file(s) via the UI.  
2. Backend saves them in `uploads/` with UUID-safe names.  
3. If multiple files → they’re bundled into a zip.  
4. Backend generates an **invite code**.  
5. Recipient enters the code to download the file (or zip) directly from the peer.  

---

## ⚡ Getting Started

### Prerequisites
- [Java 17+](https://adoptium.net/)  
- [Node.js 18+](https://nodejs.org/)  
- [Maven](https://maven.apache.org/)  
- [Docker](https://www.docker.com/) (optional, for containerized run)

