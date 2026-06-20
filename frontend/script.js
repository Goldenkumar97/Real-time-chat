var ws = null; 
var userName = ""; 
var peerName = "User"; 
var shouldReconnect = false; 
var reconnectTimer = null; 
var reconnectDelay = 1000; 
var typingTimer = null; 
var sentTypingTrue = false; 
var lastReceivedMessage = null; 
var statusMap = {}; 
var outgoingMessages = {}; 

var loginScreen = document.getElementById("loginScreen"); 
var chatScreen = document.getElementById("chatScreen"); 
var nameInput = document.getElementById("nameInput"); 
var joinBtn = document.getElementById("joinBtn"); 
var loginHint = document.getElementById("loginHint"); 
var chatTitle = document.getElementById("chatTitle"); 
var userCount = document.getElementById("userCount"); 
var connectionBadge = document.getElementById("connectionBadge"); 
var typingIndicator = document.getElementById("typingIndicator"); 
var messages = document.getElementById("messages"); 
var messageInput = document.getElementById("messageInput"); 
var sendBtn = document.getElementById("sendBtn"); 
var menuToggle = document.getElementById("menuToggle"); 
var menuDropdown = document.getElementById("menuDropdown"); 
var leaveChatBtn = document.getElementById("leaveChatBtn"); 
var clearChatBtn = document.getElementById("clearChatBtn"); 
var copyLastBtn = document.getElementById("copyLastBtn"); 

function escapeHtml(value) { 
  return String(value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/\"/g, "&quot;").replace(/'/g, "&#39;"); 
} 

function formatTimestamp(value) { 
  var date = new Date(value); 
  return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", hour12: true }); 
} 

function scrollToBottom() { 
  messages.scrollTop = messages.scrollHeight; 
} 

function openScreen(screen) { 
  loginScreen.classList.toggle("active", screen === "login"); 
  chatScreen.classList.toggle("active", screen === "chat"); 
} 

function setConnectionState(text, isConnected) { 
  connectionBadge.textContent = text; 
  sendBtn.disabled = !isConnected; 
  messageInput.disabled = !isConnected; 
} 

function setHeaderStatus(name, state) { 
  connectionBadge.textContent = (state === "online" ? "🟢 " : "🔴 ") + name + " " + state; 
} 

function setUserCount() { 
  var count = 0; 
  Object.keys(statusMap).forEach(function (name) { 
    if (statusMap[name] === "online") { 
      count += 1; 
    } 
  }); 
  userCount.textContent = count + "/2 Users Connected"; 
} 

function sendReadReceipt(messageId) { 
  if (!messageId) { 
    return; 
  } 
  sendProtocol("READ:" + messageId); 
} 

function addSystemMessage(text) { 
  var node = document.createElement("div"); 
  node.className = "system-note"; 
  node.textContent = text; 
  messages.appendChild(node); 
  scrollToBottom(); 
} 

function renderMessageTicks(tickNode, state) { 
  if (state === "delivered") { 
    tickNode.textContent = "✓✓"; 
    tickNode.className = "message-ticks delivered"; 
    tickNode.style.color = "#8e8e93"; 
    return; 
  } 
  if (state === "read") { 
    tickNode.textContent = "✓✓"; 
    tickNode.className = "message-ticks read"; 
    tickNode.style.color =  "#25D366"; 
    return; 
  } 
  tickNode.textContent = "✓"; 
  tickNode.className = "message-ticks sent"; 
  tickNode.style.color = "#8e8e93"; 
} 

function updateOutgoingMessageState(messageId, state) { 
  var entry = outgoingMessages[messageId]; 
  if (!entry) { 
    return; 
  } 
  renderMessageTicks(entry.tickNode, state); 
} 

function addMessage(direction, sender, text, timestamp, messageId, tickState) { 
  var row = document.createElement("div"); 
  row.className = "message-row " + direction; 
  if (messageId) { 
    row.dataset.messageId = messageId; 
  } 

  var bubble = document.createElement("div"); 
  bubble.className = "bubble " + direction; 

  var messageText = document.createElement("div"); 
  messageText.className = "message-text"; 
  messageText.textContent = text; 
  bubble.appendChild(messageText); 

  var meta = document.createElement("div"); 
  meta.className = "meta"; 

  var senderNode = document.createElement("span"); 
  senderNode.textContent = sender; 
  meta.appendChild(senderNode); 

  var timeNode = document.createElement("span"); 
  timeNode.textContent = formatTimestamp(timestamp); 
  meta.appendChild(timeNode); 

  if (direction === "sent") { 
    var tickNode = document.createElement("span"); 
    tickNode.className = "message-ticks sent"; 
    renderMessageTicks(tickNode, tickState || "sent"); 
    meta.appendChild(tickNode); 
    if (messageId) { 
      outgoingMessages[messageId] = { row: row, tickNode: tickNode }; 
    } 
  } 

  bubble.appendChild(meta); 
  row.appendChild(bubble); 
  messages.appendChild(row); 
  scrollToBottom(); 
} 

function clearMenu() { 
  menuDropdown.classList.add("hidden"); 
} 

function toggleMenu() { 
  menuDropdown.classList.toggle("hidden"); 
} 

function clearReconnectTimer() { 
  if (reconnectTimer) { 
    clearTimeout(reconnectTimer); 
    reconnectTimer = null; 
  } 
} 

function sendProtocol(text) { 
  if (ws && ws.readyState === WebSocket.OPEN) { 
    ws.send(text); 
  } 
} 

function sendTypingState(isTyping) { 
  sendProtocol("TYPING:" + (isTyping ? "true" : "false")); 
} 

function updateTypingIndicator(name, isTyping) { 
  typingIndicator.textContent = isTyping ? name + " is typing..." : ""; 
} 

function updateStatus(name, state) { 
  statusMap[name] = state; 
  setUserCount(); 
} 

function handleServerMessage(message) { 
  if (!message) { 
    return; 
  } 

  if (message.indexOf("SELF|") === 0) { 
    userName = message.split("|")[1] || userName; 
    chatTitle.textContent = "Chat Room - " + userName; 
    return; 
  } 

  if (message.indexOf("MSG:") === 0) { 
    var chatParts = message.split(":"); 
    var messageId = chatParts[1] || ""; 
    var sender = chatParts[2] || "User"; 
    var time = chatParts[chatParts.length - 1] || Date.now(); 
    var text = chatParts.slice(3, chatParts.length - 1).join(":"); 
    if (sender !== userName) { 
      lastReceivedMessage = { id: messageId, sender: sender, text: text, time: time }; 
      addMessage("received", sender, text, Number(time), messageId); 
      sendReadReceipt(messageId); 
    } 
    return; 
  } 

  if (message.indexOf("STATUS:") === 0) { 
    var statusParts = message.split(":"); 
    var statusName = statusParts[1] || "User"; 
    var statusValue = statusParts[2] || "online"; 
    updateStatus(statusName, statusValue); 
    if (statusName !== userName) { 
      peerName = statusName; 
    } 
    if (statusName !== userName) {
    peerName = statusName;
    setHeaderStatus(peerName, statusValue);
    } 
    return; 
  } 

  if (message.indexOf("TYPING:") === 0) { 
    var typingParts = message.split(":"); 
    var typingName = typingParts[1] || "User"; 
    var typingState = typingParts[2] === "true"; 
    if (typingName !== userName) { 
      updateTypingIndicator(typingName, typingState); 
    } 
    return; 
  } 

  if (message.indexOf("DELIVERED:") === 0) { 
    var deliveredId = message.substring("DELIVERED:".length).trim(); 
    updateOutgoingMessageState(deliveredId, "delivered"); 
    return; 
  } 

  if (message.indexOf("READ:") === 0) { 
    var readId = message.substring("READ:".length).trim(); 
    updateOutgoingMessageState(readId, "read"); 
    return; 
  } 

  if (message.indexOf("SYSTEM|") === 0) { 
    addSystemMessage(message.substring(7)); 
    return; 
  } 

  if (message.indexOf("REJECT:") === 0) { 
    addSystemMessage(message.substring(7)); 
    return; 
  } 
} 

function connectSocket() { 
  clearReconnectTimer(); 
  try { 
    ws = new WebSocket("wss://captivating-elegance-production-f587.up.railway.app");
  } catch (error) { 
    setConnectionState("Connection Failed", false); 
    scheduleReconnect(); 
    return; 
  } 

  ws.onopen = function () { 
    reconnectDelay = 1000; 
    setConnectionState("Connected", true); 
    sendProtocol("NAME:" + userName); 
    sendProtocol("STATUS:online"); 
    updateStatus(userName, "online"); 
    connectionBadge.textContent = "Waiting for another user";
    addSystemMessage("Chat room joined"); 
  }; 

  ws.onmessage = function (event) { 
    handleServerMessage(String(event.data || "")); 
  }; 

  ws.onclose = function () { 
    setConnectionState("Disconnected", false); 
    clearTypingTimeout(true); 
    setHeaderStatus(userName || "User", "offline"); 
    scheduleReconnect(); 
  }; 

  ws.onerror = function () { 
    setConnectionState("Disconnected", false); 
  }; 
} 

function scheduleReconnect() { 
  if (!shouldReconnect) { 
    return; 
  } 
  clearReconnectTimer(); 
  reconnectTimer = setTimeout(function () { 
    connectSocket(); 
  }, reconnectDelay); 
  reconnectDelay = Math.min(reconnectDelay * 2, 8000); 
} 

function clearTypingTimeout(sendFalse) { 
  if (typingTimer) { 
    clearTimeout(typingTimer); 
    typingTimer = null; 
  } 
  if (sendFalse) { 
    sendTypingState(false); 
  } 
  sentTypingTrue = false; 
} 

function queueTypingState() { 
  if (!ws || ws.readyState !== WebSocket.OPEN) { 
    return; 
  } 
  if (!sentTypingTrue) { 
    sendTypingState(true); 
    sentTypingTrue = true; 
  } 
  if (typingTimer) { 
    clearTimeout(typingTimer); 
  } 
  typingTimer = setTimeout(function () { 
    sendTypingState(false); 
    sentTypingTrue = false; 
  }, 2000); 
} 

function sendMessage() { 
  var text = messageInput.value.trim(); 
  if (!text) { 
    return; 
  } 
  if (!ws || ws.readyState !== WebSocket.OPEN) { 
    addSystemMessage("You are offline."); 
    return; 
  } 
  var messageId = "msg_" + Date.now(); 
addMessage("sent", userName, text, Date.now(), messageId, "sent"); 
sendProtocol("MSG:" + messageId + ":" + text); 

  messageInput.value = ""; 
  clearTypingTimeout(true); 
} 

function joinChat() { 
  var value = nameInput.value.trim(); 
  if (!value) {
    loginHint.textContent = "Please enter your name.";
    return;
}

if (!/^[A-Za-z ]+$/.test(value)) {
    loginHint.textContent = "Only letters and spaces are allowed.";
    return;
}
  if (value.length < 3) {
    loginHint.textContent = "Name must be at least 3 letters long.";
    return;
  }
   
  userName = value.slice(0, 30); 
  shouldReconnect = true; 
  statusMap = {}; 
  lastReceivedMessage = null; 
  chatTitle.textContent = "Chat Room - " + userName; 
  loginHint.textContent = ""; 
  openScreen("chat"); 
  setConnectionState("Connecting...", false); 
  setUserCount(); 
  connectSocket(); 
} 

function leaveChat() { 
  var confirmed = window.confirm("Leave the chat and return to the login screen?"); 
  if (!confirmed) { 
    return; 
  } 
  shouldReconnect = false; 
  clearReconnectTimer(); 
  clearTypingTimeout(false); 
  typingIndicator.textContent = ""; 
  if (ws && ws.readyState === WebSocket.OPEN) { 
    sendProtocol("STATUS:offline"); 
    ws.close(); 
  } else if (ws) { 
    ws.close(); 
  } 
  ws = null; 
  setConnectionState("Disconnected", false); 
  setHeaderStatus(userName || "User", "offline"); 
  openScreen("login"); 
  addSystemMessage("You left the chat."); 
} 

function clearChat() { 
  messages.innerHTML = ""; 
  outgoingMessages = {}; 
} 

function copyLastMessage() { 
  if (!lastReceivedMessage) { 
    addSystemMessage("No received message to copy."); 
    return; 
  } 
  var payload = lastReceivedMessage.sender + ": " + lastReceivedMessage.text; 
  navigator.clipboard.writeText(payload).then(function () { 
    addSystemMessage("Last message copied."); 
  }).catch(function () { 
    addSystemMessage("Unable to copy message."); 
  }); 
} 

joinBtn.addEventListener("click", joinChat); 
nameInput.addEventListener("keydown", function (event) { 
  if (event.key === "Enter") { 
    joinChat(); 
  } 
}); 

sendBtn.addEventListener("click", sendMessage); 
messageInput.addEventListener("keydown", function (event) { 
  if (event.key === "Enter") { 
    event.preventDefault(); 
    sendMessage(); 
    return; 
  } 
  queueTypingState(); 
}); 
messageInput.addEventListener("input", function () { 
  if (!messageInput.value.trim()) { 
    clearTypingTimeout(true); 
    return; 
  } 
  queueTypingState(); 
}); 

menuToggle.addEventListener("click", function (event) { 
  event.stopPropagation(); 
  toggleMenu(); 
}); 

leaveChatBtn.addEventListener("click", function () { 
  clearMenu(); 
  leaveChat(); 
}); 

clearChatBtn.addEventListener("click", function () { 
  clearMenu(); 
  clearChat(); 
}); 

copyLastBtn.addEventListener("click", function () { 
  clearMenu(); 
  copyLastMessage(); 
}); 

document.addEventListener("click", function () { 
  clearMenu(); 
}); 

menuDropdown.addEventListener("click", function (event) { 
  event.stopPropagation(); 
}); 

window.addEventListener("beforeunload", function () { 
  shouldReconnect = false; 
  clearReconnectTimer(); 
  clearTypingTimeout(false); 
  if (ws && ws.readyState === WebSocket.OPEN) { 
    sendProtocol("STATUS:offline"); 
    ws.close(); 
  } 
}); 

openScreen("login"); 
setConnectionState("Disconnected", false); 
setUserCount(); 
clearMenu(); 
