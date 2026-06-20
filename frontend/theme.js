(function () { 
  var root = document.documentElement;
  var toggleButton = document.getElementById("themeToggle"); 
  var storageKey = "real-time-chat-theme"; 

  function setTheme(theme) { 
    root.setAttribute("data-theme", theme); 
    localStorage.setItem(storageKey, theme); 
    toggleButton.textContent = theme === "dark" ? "☀️" : "🌙"; 
    toggleButton.setAttribute("aria-label", theme === "dark" ? "Switch to light mode" : "Switch to dark mode"); 
  } 

  function loadTheme() { 
    var savedTheme = localStorage.getItem(storageKey); 
    return savedTheme === "dark" ? "dark" : "light"; 
  } 

  setTheme(loadTheme()); 

  toggleButton.addEventListener("click", function () { 
    var nextTheme = root.getAttribute("data-theme") === "dark" ? "light" : "dark";
    setTheme(nextTheme); 
  }); 
})(); 
