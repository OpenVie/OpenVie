(function () {
  try {
    var theme = localStorage.getItem("openvie.theme");
    if (theme !== "light" && theme !== "dark") return;
    var root = document.documentElement;
    root.dataset.ovTheme = theme;
    root.dataset.theme = theme;
    var shell = document.querySelector(".ov-shell");
    if (shell) {
      shell.classList.toggle("ov-light", theme === "light");
    } else {
      var observer = new MutationObserver(function () {
        var node = document.querySelector(".ov-shell");
        if (!node) return;
        node.classList.toggle("ov-light", theme === "light");
        observer.disconnect();
      });
      observer.observe(root, { childList: true, subtree: true });
    }
  } catch (error) {}
})();
