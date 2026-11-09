// Copyright 2026 LinkedNodeDigital
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

(function () {
  try {
    var root = document.documentElement;
    root.dataset.ovPalette = "blue";
    var theme = localStorage.getItem("openvie.theme");
    if (theme !== "light" && theme !== "dark") return;
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
  } catch {}
})();
