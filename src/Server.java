import java.io.ByteArrayOutputStream; // Imports ByteArrayOutputStream to build outgoing WebSocket frames in memory.
import java.io.IOException; // Imports IOException for network and stream error handling.
import java.io.InputStream; // Imports InputStream to read raw bytes from sockets.
import java.io.OutputStream; // Imports OutputStream to write raw bytes from sockets.
import java.net.ServerSocket; // Imports ServerSocket to listen for incoming TCP connections.
import java.net.Socket; // Imports Socket to represent each client TCP connection.
import java.nio.charset.StandardCharsets; // Imports UTF-8 charset helpers for stable text encoding.
import java.security.MessageDigest; // Imports MessageDigest to calculate SHA-1 for WebSocket handshake.
import java.text.SimpleDateFormat; // Imports SimpleDateFormat to create message timestamps.
import java.util.ArrayList; // Imports ArrayList for client storage.
import java.util.Base64; // Imports Base64 for handshake accept key generation.
import java.util.Collections; // Imports Collections for synchronizedList wrapper.
import java.util.Date; // Imports Date for current time on each message.
import java.util.List; // Imports List interface for client collection.

public class Server { // Declares the main chat server class.
    private static final int PORT = 8080; // Defines the fixed server port required by the task.
    private static final int MAX_CLIENTS = 2; // Defines that only two users can be connected at a time.
    private static final String WEB_SOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"; // Defines the official WebSocket GUID used in accept key generation.
    private final List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<ClientHandler>()); // Stores active clients in a thread-safe list.

    public static void main(String[] args) { // Entry point for the Java process.
        new Server().start(); // Creates and starts the chat server instance.
    } // Ends main method.

    private void start() { // Starts listening and accepting incoming client connections.
        try (ServerSocket serverSocket = new ServerSocket(PORT)) { // Opens the server socket and auto-closes it on shutdown.
            System.out.println("WebSocket chat server started on ws://localhost:" + PORT); // Logs startup information.
            while (true) { // Keeps accepting new connections forever.
                Socket socket = serverSocket.accept(); // Waits for and accepts the next incoming connection.
                if (isServerFull()) { // Checks whether two clients are already connected.
                    sendBusyResponse(socket); // Sends HTTP 503 to reject extra clients cleanly.
                    continue; // Skips to the next accept iteration.
                } // Ends full-capacity check block.
                boolean handshakeOk = performHandshake(socket); // Performs manual WebSocket handshake with this client.
                if (!handshakeOk) { // Checks whether handshake failed.
                    closeQuietly(socket); // Closes bad or incomplete handshake socket.
                    continue; // Skips creating a client handler for invalid connection.
                } // Ends handshake failure block.
                ClientHandler client = new ClientHandler(socket); // Creates a handler object for the accepted client.
                clients.add(client); // Adds the new client to the active list.
                client.start(); // Starts background thread to read client frames.
            } // Ends accept loop.
        } catch (Exception exception) { // Catches fatal server-side exceptions.
            System.err.println("Server stopped: " + exception.getMessage()); // Prints server stop reason.
        } // Ends try-catch for server lifecycle.
    } // Ends start method.

    private boolean isServerFull() { // Checks if the active client list already reached the 2-user limit.
        synchronized (clients) { // Locks client list while reading size.
            return clients.size() >= MAX_CLIENTS; // Returns true when at least two active clients exist.
        } // Ends synchronized block.
    } // Ends isServerFull method.

    private boolean performHandshake(Socket socket) { // Performs RFC6455 handshake manually using raw streams.
        try { // Starts handshake logic with exception handling.
            InputStream inputStream = socket.getInputStream(); // Gets raw input stream from socket.
            OutputStream outputStream = socket.getOutputStream(); // Gets raw output stream from socket.
            String request = readHttpRequest(inputStream); // Reads HTTP upgrade request headers until CRLFCRLF.
            if (request == null || request.isEmpty()) { // Validates that request data exists.
                return false; // Fails handshake when request is missing.
            } // Ends request validation.
            String webSocketKey = extractHeader(request, "Sec-WebSocket-Key"); // Extracts client WebSocket key from headers.
            if (webSocketKey == null || webSocketKey.trim().isEmpty()) { // Validates required key is present.
                return false; // Fails handshake when key is absent.
            } // Ends key validation.
            String acceptKey = createAcceptKey(webSocketKey.trim()); // Computes Sec-WebSocket-Accept from key + GUID.
            String response = "HTTP/1.1 101 Switching Protocols\r\n" + // Starts status line for successful protocol switch.
                    "Upgrade: websocket\r\n" + // Adds required Upgrade header.
                    "Connection: Upgrade\r\n" + // Adds required Connection header.
                    "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n"; // Adds handshake accept header and ends header block.
            outputStream.write(response.getBytes(StandardCharsets.UTF_8)); // Writes HTTP handshake response bytes.
            outputStream.flush(); // Flushes response immediately to complete handshake.
            return true; // Reports handshake success.
        } catch (Exception exception) { // Catches handshake parsing, digest, or socket errors.
            return false; // Reports handshake failure on any exception.
        } // Ends handshake try-catch.
    } // Ends performHandshake method.

    private String readHttpRequest(InputStream inputStream) throws IOException { // Reads HTTP headers byte-by-byte until CRLFCRLF.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); // Creates memory buffer for incoming header bytes.
        int previousThree = -1; // Tracks byte 3 positions back for end-of-header detection.
        int previousTwo = -1; // Tracks byte 2 positions back for end-of-header detection.
        int previousOne = -1; // Tracks previous byte for end-of-header detection.
        int current; // Declares holder for each newly read byte.
        while ((current = inputStream.read()) != -1) { // Reads bytes until stream ends or header termination pattern appears.
            buffer.write(current); // Appends current byte into header buffer.
            if (previousThree == '\r' && previousTwo == '\n' && previousOne == '\r' && current == '\n') { // Checks for CRLFCRLF sequence.
                break; // Stops reading once full header block is received.
            } // Ends CRLFCRLF detection block.
            previousThree = previousTwo; // Shifts byte history window forward.
            previousTwo = previousOne; // Shifts byte history window forward.
            previousOne = current; // Saves current byte as newest history value.
            if (buffer.size() > 16384) { // Applies a hard cap to prevent oversized header abuse.
                break; // Stops reading if request headers become unreasonably large.
            } // Ends size cap block.
        } // Ends header read loop.
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8); // Converts buffered header bytes to UTF-8 text.
    } // Ends readHttpRequest method.

    private String extractHeader(String request, String headerName) { // Extracts a specific HTTP header value by name.
        String[] lines = request.split("\\r?\\n"); // Splits header text into individual lines.
        String expectedPrefix = headerName.toLowerCase() + ":"; // Prepares lowercase match prefix.
        for (String line : lines) { // Iterates through each request header line.
            String lower = line.toLowerCase(); // Converts line to lowercase for case-insensitive check.
            if (lower.startsWith(expectedPrefix)) { // Checks whether this line contains target header name.
                return line.substring(line.indexOf(':') + 1).trim(); // Returns trimmed header value after colon.
            } // Ends header match condition.
        } // Ends line iteration loop.
        return null; // Returns null when header is not found.
    } // Ends extractHeader method.

    private String createAcceptKey(String clientKey) throws Exception { // Creates Sec-WebSocket-Accept value from client key.
        String combined = clientKey + WEB_SOCKET_GUID; // Concatenates client key with fixed WebSocket GUID.
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-1"); // Initializes SHA-1 digest engine.
        byte[] sha1 = messageDigest.digest(combined.getBytes(StandardCharsets.UTF_8)); // Computes SHA-1 bytes over combined string.
        return Base64.getEncoder().encodeToString(sha1); // Returns Base64-encoded digest as accept key.
    } // Ends createAcceptKey method.

    private void sendBusyResponse(Socket socket) { // Rejects connections beyond two users with HTTP 503 response.
        try { // Starts response write with exception protection.
            OutputStream outputStream = socket.getOutputStream(); // Gets socket output stream.
            String body = "Chat room full. Only 2 users are allowed."; // Defines rejection message body.
            String response = "HTTP/1.1 503 Service Unavailable\r\n" + // Uses 503 status for full capacity.
                    "Content-Type: text/plain; charset=UTF-8\r\n" + // Declares text payload content type.
                    "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n" + // Adds exact payload size.
                    "Connection: close\r\n\r\n" + // Requests connection close after response.
                    body; // Appends body text.
            outputStream.write(response.getBytes(StandardCharsets.UTF_8)); // Writes complete HTTP response.
            outputStream.flush(); // Flushes bytes to client immediately.
        } catch (Exception ignored) { // Ignores write errors because connection is being rejected anyway.
        } // Ends rejection response try-catch.
        closeQuietly(socket); // Closes rejected socket.
    } // Ends sendBusyResponse method.

    private void broadcastToOther(ClientHandler sender, String message) { // Sends a message to every connected client except sender.
        synchronized (clients) { // Locks list during iteration.
            for (ClientHandler client : clients) { // Iterates through active clients.
                if (client != sender) { // Keeps sender excluded.
                    client.sendText(message); // Sends message to the other participant.
                } // Ends sender exclusion condition.
            } // Ends client iteration.
        } // Ends synchronized block.
    } // Ends broadcastToOther method.

    private void broadcastToAll(String message) { // Sends a message to all currently connected clients.
        synchronized (clients) { // Locks list during iteration.
            for (ClientHandler client : clients) { // Iterates through active clients.
                client.sendText(message); // Sends message to current client.
            } // Ends client iteration.
        } // Ends synchronized block.
    } // Ends broadcastToAll method.

    private String nowTime() { // Builds current time in HH:mm format for message timestamps.
        return new SimpleDateFormat("HH:mm").format(new Date()); // Returns formatted current local time.
    } // Ends nowTime method.

    private void handleClientMessage(ClientHandler sender, String text) { // Routes and processes incoming protocol messages from one client.
        if (text == null) { // Checks for null input safety.
            return; // Exits early on null.
        } // Ends null check.
        if (text.startsWith("READ:")) { // Handles read receipt notifications.
            String messageId = text.substring("READ:".length()).trim(); // Extracts the received message identifier.
            if (!messageId.isEmpty()) { // Validates the receipt identifier.
                broadcastReadToSender(sender, messageId); // Forwards the receipt to the original sender only.
            } // Ends message-id validation.
            return; // Finishes processing READ message.
        } // Ends READ handling block.
        if (text.startsWith("NAME:")) { // Handles initial or updated display name registration.
            String requestedName = text.substring("NAME:".length()).trim(); // Extracts provided username.
            String safeName = requestedName.isEmpty() ? "User" : requestedName; // Uses fallback when empty name is sent.
            sender.userName = safeName; // Saves name on sender handler.
            sender.sendText("SELF|" + sender.userName); // Sends self-name confirmation to this client.
            broadcastToAll("STATUS:" + sender.userName + ":online"); // Announces this user as online to everyone.
            synchronized (clients) { // Locks list to push current peer statuses to newcomer.
                for (ClientHandler client : clients) { // Iterates all clients for status snapshot.
                    if (client != sender && client.userName != null && !client.userName.trim().isEmpty()) { // Selects named peers only.
                        sender.sendText("STATUS:" + client.userName + ":online"); // Sends peer online status to sender.
                    } // Ends peer snapshot condition.
                } // Ends snapshot loop.
            } // Ends synchronized snapshot block.
            broadcastToOther(sender, "SYSTEM|" + sender.userName + " joined the chat."); // Notifies peer about join event.
            return; // Finishes processing NAME message.
        } // Ends NAME handling block.
        if (text.startsWith("TYPING:")) { // Handles typing state updates.
            String typingValue = text.substring("TYPING:".length()).trim(); // Reads typing boolean as text.
            String typingState = "true".equalsIgnoreCase(typingValue) ? "true" : "false"; // Normalizes typing value to true/false.
            String senderName = sender.userName == null || sender.userName.trim().isEmpty() ? "User" : sender.userName; // Resolves sender display name.
            broadcastToOther(sender, "TYPING:" + senderName + ":" + typingState); // Sends typing indicator to the other client.
            return; // Finishes processing TYPING message.
        } // Ends TYPING handling block.
        if (text.startsWith("STATUS:")) { // Handles explicit status signals from client.
            String statusValue = text.substring("STATUS:".length()).trim(); // Reads status value text.
            String normalizedStatus = "offline".equalsIgnoreCase(statusValue) ? "offline" : "online"; // Normalizes to online/offline.
            String senderName = sender.userName == null || sender.userName.trim().isEmpty() ? "User" : sender.userName; // Resolves sender display name.
            broadcastToAll("STATUS:" + senderName + ":" + normalizedStatus); // Broadcasts status update to everyone.
            return; // Finishes processing STATUS message.
        } // Ends STATUS handling block.
        String senderName = sender.userName == null || sender.userName.trim().isEmpty() ? "User" : sender.userName; // Resolves sender display name for chat message.
        String plainText = text.startsWith("MSG:") ? text.substring("MSG:".length()) : text; // Supports both MSG-prefixed and plain text chat payloads.
        String[] messageParts = plainText.split(":", 2); // Splits the client payload into message ID and message text.
        String messageId = messageParts.length > 1 ? messageParts[0].trim() : "msg_" + System.currentTimeMillis(); // Uses the provided ID or creates a fallback ID.
        String rawText = messageParts.length > 1 ? messageParts[1] : plainText; // Extracts the actual message body.
        String cleaned = rawText.trim(); // Trims message text to avoid empty-space messages.
        if (cleaned.isEmpty()) { // Checks whether message became empty after trim.
            return; // Ignores empty messages.
        } // Ends empty chat check.
        long timestamp = System.currentTimeMillis(); // Captures the message timestamp in milliseconds.
        String payload = "MSG:" + messageId + ":" + senderName + ":" + cleaned + ":" + timestamp; // Builds the outgoing message payload.
        sender.sendText("DELIVERED:" + messageId); // Confirms server delivery back to the sender.
        broadcastToOther(sender, payload); // Delivers chat payload to the other connected user.
    } // Ends handleClientMessage method.

    private void broadcastReadToSender(ClientHandler sender, String messageId) { // Forwards a read receipt to the original sender only.
        synchronized (clients) { // Locks list during lookup.
            for (ClientHandler client : clients) { // Iterates through active clients.
                if (client != sender && client.userName != null && !client.userName.trim().isEmpty()) { // Targets the peer participant.
                    client.sendText("READ:" + messageId); // Sends read receipt to the sender side only.
                    return; // Stops after forwarding to one sender.
                } // Ends target selection.
            } // Ends client iteration.
        } // Ends synchronized lookup.
    } // Ends broadcastReadToSender method.

    private void removeClient(ClientHandler clientHandler) { // Removes disconnected client and notifies remaining peer.
        clients.remove(clientHandler); // Removes this handler from active list.
        if (clientHandler.userName != null && !clientHandler.userName.trim().isEmpty()) { // Checks if disconnected client had a known name.
            broadcastToAll("STATUS:" + clientHandler.userName + ":offline"); // Announces offline status for disconnected user.
            broadcastToAll("TYPING:" + clientHandler.userName + ":false"); // Clears any stale typing indicator for disconnected user.
            broadcastToAll("SYSTEM|" + clientHandler.userName + " left the chat."); // Announces leave event to remaining users.
        } // Ends named-user disconnect notifications.
    } // Ends removeClient method.

    private void closeQuietly(Socket socket) { // Closes a socket while suppressing close exceptions.
        try { // Starts safe close block.
            socket.close(); // Closes socket resource.
        } catch (Exception ignored) { // Ignores close failures.
        } // Ends safe close block.
    } // Ends closeQuietly method.

    private class ClientHandler extends Thread { // Defines per-client worker that reads and writes WebSocket frames.
        private final Socket socket; // Holds socket for this connected client.
        private final InputStream inputStream; // Holds raw input stream for frame decoding.
        private final OutputStream outputStream; // Holds raw output stream for frame encoding.
        private volatile String userName = ""; // Stores current user's display name.

        ClientHandler(Socket socket) throws IOException { // Constructs handler and opens socket streams.
            this.socket = socket; // Saves socket reference.
            this.inputStream = socket.getInputStream(); // Gets input stream once for frame reads.
            this.outputStream = socket.getOutputStream(); // Gets output stream once for frame writes.
        } // Ends constructor.

        @Override // Overrides Thread.run to execute this client's read loop.
        public void run() { // Runs frame read loop until disconnect or error.
            try { // Starts main read loop with error handling.
                while (!socket.isClosed()) { // Continues while socket remains open.
                    String incoming = readTextFrame(inputStream); // Reads next text frame payload from client.
                    if (incoming == null) { // Treats null as disconnect or close frame.
                        break; // Exits loop on client disconnect.
                    } // Ends null frame check.
                    handleClientMessage(this, incoming); // Processes incoming protocol message.
                } // Ends read loop.
            } catch (Exception ignored) { // Ignores runtime frame exceptions because disconnect flow handles cleanup.
            } finally { // Ensures cleanup always runs once loop ends.
                removeClient(this); // Removes this client and notifies others.
                closeQuietly(socket); // Closes underlying socket safely.
            } // Ends try-finally cleanup block.
        } // Ends run method.

        private synchronized void sendText(String message) { // Sends one server-to-client text frame.
            try { // Starts frame write with exception handling.
                byte[] payload = message.getBytes(StandardCharsets.UTF_8); // Encodes text payload bytes in UTF-8.
                ByteArrayOutputStream frame = new ByteArrayOutputStream(); // Creates memory buffer for full frame.
                frame.write(0x81); // Writes FIN=1 and opcode=1 (text frame).
                int length = payload.length; // Stores payload length for header encoding.
                if (length <= 125) { // Uses short payload encoding when length <= 125.
                    frame.write(length); // Writes payload length directly.
                } else if (length <= 65535) { // Uses 16-bit extended payload length when needed.
                    frame.write(126); // Writes 126 marker for 16-bit length mode.
                    frame.write((length >> 8) & 0xFF); // Writes high byte of 16-bit length.
                    frame.write(length & 0xFF); // Writes low byte of 16-bit length.
                } else { // Uses 64-bit extended payload length for very large payloads.
                    frame.write(127); // Writes 127 marker for 64-bit length mode.
                    for (int shift = 56; shift >= 0; shift -= 8) { // Iterates through 8 bytes from high to low.
                        frame.write((length >> shift) & 0xFF); // Writes next length byte.
                    } // Ends 64-bit length loop.
                } // Ends length encoding branch.
                frame.write(payload); // Writes actual payload bytes after header.
                outputStream.write(frame.toByteArray()); // Sends full frame to socket output.
                outputStream.flush(); // Flushes frame immediately for real-time delivery.
            } catch (Exception ignored) { // Ignores send failures since disconnect cleanup will follow.
            } // Ends send try-catch.
        } // Ends sendText method.
    } // Ends ClientHandler class.

    private String readTextFrame(InputStream inputStream) throws IOException { // Reads and decodes one WebSocket frame from a client stream.
        int firstByte = inputStream.read(); // Reads first frame byte containing FIN and opcode.
        if (firstByte == -1) { // Detects end-of-stream.
            return null; // Returns null to signal disconnect.
        } // Ends first byte EOF check.
        int secondByte = inputStream.read(); // Reads second frame byte containing mask flag and payload length marker.
        if (secondByte == -1) { // Detects end-of-stream on second byte.
            return null; // Returns null to signal disconnect.
        } // Ends second byte EOF check.
        int opcode = firstByte & 0x0F; // Extracts lower 4 bits for opcode.
        boolean masked = (secondByte & 0x80) != 0; // Checks whether payload is masked (client frames should be masked).
        long payloadLength = secondByte & 0x7F; // Extracts lower 7 bits for payload length marker.
        if (payloadLength == 126) { // Handles 16-bit extended payload length mode.
            byte[] extended = readExactly(inputStream, 2); // Reads two bytes for extended length.
            payloadLength = ((extended[0] & 0xFF) << 8) | (extended[1] & 0xFF); // Decodes unsigned 16-bit length.
        } else if (payloadLength == 127) { // Handles 64-bit extended payload length mode.
            byte[] extended = readExactly(inputStream, 8); // Reads eight bytes for extended length.
            payloadLength = 0; // Initializes long length accumulator.
            for (int i = 0; i < 8; i++) { // Iterates each length byte.
                payloadLength = (payloadLength << 8) | (extended[i] & 0xFF); // Builds unsigned 64-bit length value.
            } // Ends 64-bit decode loop.
        } // Ends payload length decode branch.
        byte[] maskKey = masked ? readExactly(inputStream, 4) : null; // Reads 4-byte mask key when mask flag is present.
        if (payloadLength > Integer.MAX_VALUE) { // Protects against impractically large payload lengths.
            throw new IOException("Payload too large"); // Throws exception for oversized frame.
        } // Ends payload size guard.
        byte[] payload = readExactly(inputStream, (int) payloadLength); // Reads exact payload byte count.
        if (masked && maskKey != null) { // Checks whether payload unmasking is required.
            for (int i = 0; i < payload.length; i++) { // Iterates each payload byte.
                payload[i] = (byte) (payload[i] ^ maskKey[i % 4]); // Unmasks byte using rotating 4-byte mask key.
            } // Ends payload unmask loop.
        } // Ends unmask block.
        if (opcode == 0x8) { // Handles close frame opcode.
            return null; // Signals disconnect when close frame is received.
        } // Ends close opcode handling.
        if (opcode == 0x9) { // Handles ping frame opcode.
            return ""; // Returns empty message for ping without terminating connection.
        } // Ends ping opcode handling.
        if (opcode != 0x1) { // Checks for non-text opcodes that this simple server ignores.
            return ""; // Returns empty string for unsupported frame types.
        } // Ends non-text opcode check.
        return new String(payload, StandardCharsets.UTF_8); // Decodes and returns UTF-8 text payload.
    } // Ends readTextFrame method.

    private byte[] readExactly(InputStream inputStream, int length) throws IOException { // Reads exactly N bytes or throws on premature EOF.
        byte[] data = new byte[length]; // Allocates target buffer of requested length.
        int offset = 0; // Tracks how many bytes have been filled.
        while (offset < length) { // Continues until full buffer is populated.
            int count = inputStream.read(data, offset, length - offset); // Reads next chunk into remaining region.
            if (count == -1) { // Detects premature end-of-stream.
                throw new IOException("Unexpected end of stream"); // Throws when fewer bytes than expected are available.
            } // Ends EOF check.
            offset += count; // Advances filled byte counter.
        } // Ends exact read loop.
        return data; // Returns fully populated buffer.
    } // Ends readExactly method.
} // Ends Server class.
