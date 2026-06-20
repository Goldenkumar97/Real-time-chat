# Real-Time Chat Application

A lightweight real-time chat application built using Java WebSocket, HTML, CSS, and JavaScript. The application enables instant communication between users through a persistent WebSocket connection and provides a clean, responsive user interface.

## Features

* Real-Time Messaging
* Online Status Indicator
* Read Receipts
* Dark / Light Theme
* Responsive User Interface
* User Input Validation
* Typing Indicator
* Two-User Chat Support

## Technology Stack

### Frontend

* HTML5
* CSS3
* JavaScript

### Backend

* Java
* WebSocket

## Project Structure

```text
RealTimeChat/
│
├── frontend/
│   ├── index.html
│   ├── style.css
│   ├── script.js
│   ├── theme.js
│   └── Chat-image.png
│
├── src/
│   └── Server.java
│
└── README.md
```

## Getting Started

### 1. Compile the Server

```bash
javac src/Server.java
```

### 2. Run the Server

```bash
java -cp src Server
```

### 3. Launch the Application

Open `frontend/index.html` in a web browser.

### 4. Start Chatting

Open the application in a second browser window or tab and join with another user name to test real-time communication.

## Key Highlights

* Built using Java WebSocket technology for real-time communication.
* Supports instant message delivery between connected users.
* Includes online status tracking and read receipts.
* Provides dark and light theme support.
* Designed with a modern and responsive user interface.

## Future Enhancements

* Multi-user chat rooms
* User authentication
* Message history storage
* Secure WebSocket (WSS) support
* File and image sharing
