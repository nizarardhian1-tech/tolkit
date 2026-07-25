(function () {
  "use strict";

  var codeArea = document.getElementById("codeArea");
  var gutter = document.getElementById("gutter");
  var highlightCode = document.getElementById("highlightCode");
  var highlightLayer = document.getElementById("highlightLayer");
  var toolbar = document.getElementById("toolbar");
  var findbar = document.getElementById("findbar");
  var findInput = document.getElementById("findInput");
  var replaceInput = document.getElementById("replaceInput");
  var findStatus = document.getElementById("findStatus");
  var root = document.documentElement;

  var VOID_TAGS = ["br", "img", "input", "hr", "meta", "link", "source",
    "area", "base", "col", "embed", "param", "track", "wbr"];

  var fontSize = 14;

  // ============================================================
  //  Bridge helper -- aman dipanggil walau AndroidBridge belum
  //  ke-inject (misalnya saat testing langsung di browser desktop).
  // ============================================================
  function notifyKotlin(text) {
    if (window.AndroidBridge && window.AndroidBridge.onCodeChanged) {
      window.AndroidBridge.onCodeChanged(text);
    }
  }

  var notifyTimer = null;
  function scheduleNotify() {
    clearTimeout(notifyTimer);
    notifyTimer = setTimeout(function () { notifyKotlin(codeArea.value); }, 150);
  }

  // ============================================================
  //  Syntax highlighting (single-pass tokenizer, mirip logika
  //  Kotlin HtmlSyntaxHighlighter tapi dibangun sebagai string HTML).
  // ============================================================
  var TOKEN_RE = /(<!--[\s\S]*?-->|\/\*[\s\S]*?\*\/|\/\/[^\n]*)|("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')|(<\/?[a-zA-Z][a-zA-Z0-9-]*)|(\b[a-zA-Z-][a-zA-Z0-9-]*(?=\s*=\s*["']))|(\b\d+(?:\.\d+)?\b)|(\b(?:function|var|let|const|if|else|return|for|while|new|this|true|false|null|undefined|typeof|break|continue|switch|case|default|try|catch|finally|class|extends|import|export|from|async|await|document|window|console)\b)/g;

  function escapeHtml(s) {
    return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  function highlight(text) {
    var out = "";
    var lastIndex = 0;
    TOKEN_RE.lastIndex = 0;
    var m;
    while ((m = TOKEN_RE.exec(text)) !== null) {
      out += escapeHtml(text.slice(lastIndex, m.index));
      var cls = "tok-comment";
      if (m[2] !== undefined) cls = "tok-string";
      else if (m[3] !== undefined) cls = "tok-tag";
      else if (m[4] !== undefined) cls = "tok-attr";
      else if (m[5] !== undefined) cls = "tok-number";
      else if (m[6] !== undefined) cls = "tok-keyword";
      out += '<span class="' + cls + '">' + escapeHtml(m[0]) + "</span>";
      lastIndex = TOKEN_RE.lastIndex;
      if (m[0].length === 0) TOKEN_RE.lastIndex++;
    }
    out += escapeHtml(text.slice(lastIndex));
    // trailing newline supaya tinggi <pre> tetap sinkron kalau baris terakhir kosong
    if (text.endsWith("\n")) out += " ";
    return out;
  }

  var highlightTimer = null;
  function scheduleHighlight() {
    clearTimeout(highlightTimer);
    highlightTimer = setTimeout(function () {
      highlightCode.innerHTML = highlight(codeArea.value);
    }, 120);
  }

  // ============================================================
  //  Gutter nomor baris + sinkronisasi scroll
  // ============================================================
  function updateGutter() {
    var lines = codeArea.value.split("\n").length;
    var buf = [];
    for (var i = 1; i <= lines; i++) buf.push(i);
    gutter.textContent = buf.join("\n");
  }

  codeArea.addEventListener("scroll", function () {
    highlightLayer.scrollTop = codeArea.scrollTop;
    highlightLayer.scrollLeft = codeArea.scrollLeft;
    gutter.scrollTop = codeArea.scrollTop;
  });

  codeArea.addEventListener("input", function () {
    updateGutter();
    scheduleHighlight();
    scheduleNotify();
  });

  // ============================================================
  //  Auto-indent (Enter) & auto-close tag ('>') & Tab -> spasi
  // ============================================================
  function currentLineIndent(text, uptoIndex) {
    var lineStart = text.lastIndexOf("\n", uptoIndex - 1) + 1;
    var line = text.substring(lineStart, uptoIndex);
    var m = /^[ \t]*/.exec(line);
    return { indent: m ? m[0] : "", line: line };
  }

  function opensBlock(line) {
    var trimmed = line.trim();
    var tagMatch = /.*<([a-zA-Z][a-zA-Z0-9-]*)(\s[^<>]*)?>$/.exec(trimmed);
    if (!tagMatch) return false;
    if (trimmed.endsWith("/>")) return false;
    if (/.*<\/[a-zA-Z0-9-]+>$/.test(trimmed)) return false;
    return VOID_TAGS.indexOf(tagMatch[1].toLowerCase()) === -1;
  }

  codeArea.addEventListener("keydown", function (e) {
    if (e.key === "Enter") {
      var cursor = codeArea.selectionStart;
      var text = codeArea.value;
      var info = currentLineIndent(text, cursor);
      var indent = info.indent;
      if (opensBlock(info.line)) indent += "    ";
      e.preventDefault();
      document.execCommand("insertText", false, "\n" + indent);
      return;
    }
    if (e.key === "Tab") {
      e.preventDefault();
      document.execCommand("insertText", false, "    ");
      return;
    }
    // '>' auto-close tag: dibiarkan lewat 'input' handler di bawah supaya
    // logikanya konsisten dipakai juga kalau '>' datang dari paste/IME.
  });

  codeArea.addEventListener("beforeinput", function (e) {
    if (e.data !== ">") return;
    var cursor = codeArea.selectionStart;
    var text = codeArea.value;
    // Simulasikan teks setelah '>' disisipkan, tanpa menunggu event input,
    // supaya kita bisa langsung menambahkan closing tag di posisi yang tepat.
    setTimeout(function () { handleAutoCloseTag(); }, 0);
  });

  function handleAutoCloseTag() {
    var cursor = codeArea.selectionStart;
    var text = codeArea.value;
    var gtIndex = cursor - 1;
    if (gtIndex < 0 || text[gtIndex] !== ">") return;
    if (gtIndex > 0 && text[gtIndex - 1] === "/") return;

    var ltIndex = text.lastIndexOf("<", gtIndex);
    if (ltIndex === -1) return;
    var tagContent = text.substring(ltIndex + 1, gtIndex);
    if (!tagContent || tagContent[0] === "/" || tagContent[0] === "!") return;

    var tagMatch = /^[a-zA-Z][a-zA-Z0-9-]*/.exec(tagContent);
    if (!tagMatch) return;
    var tagName = tagMatch[0];
    if (VOID_TAGS.indexOf(tagName.toLowerCase()) !== -1) return;

    var after = text.substring(cursor, cursor + tagName.length + 3);
    if (after.toLowerCase() === ("</" + tagName + ">").toLowerCase()) return;

    var closeTag = "</" + tagName + ">";
    document.execCommand("insertText", false, closeTag);
    codeArea.setSelectionRange(cursor, cursor);
  }

  // ============================================================
  //  Toolbar actions
  // ============================================================
  function insertWrapped(before, after) {
    var start = codeArea.selectionStart;
    var end = codeArea.selectionEnd;
    var hasSelection = start !== end;
    codeArea.focus();
    if (hasSelection) {
      var sel = codeArea.value.substring(start, end);
      document.execCommand("insertText", false, before + sel + after);
      codeArea.setSelectionRange(start + before.length, start + before.length + sel.length);
    } else {
      document.execCommand("insertText", false, before + after);
      codeArea.setSelectionRange(start + before.length, start + before.length);
    }
  }

  toolbar.addEventListener("click", function (e) {
    var btn = e.target.closest(".tbtn");
    if (!btn) return;
    codeArea.focus();
    switch (btn.getAttribute("data-action")) {
      case "undo": document.execCommand("undo"); break;
      case "redo": document.execCommand("redo"); break;
      case "find": toggleFindBar(true); break;
      case "tag": insertWrapped("<", ">"); break;
      case "quote": insertWrapped('"', '"'); break;
      case "tab": document.execCommand("insertText", false, "    "); break;
      case "zoomout": setFontSize(fontSize - 2); break;
      case "zoomin": setFontSize(fontSize + 2); break;
    }
  });

  function setFontSize(size) {
    fontSize = Math.max(10, Math.min(24, size));
    root.style.setProperty("--font-size", fontSize + "px");
  }

  // ============================================================
  //  Find & Replace
  // ============================================================
  function toggleFindBar(show) {
    findbar.classList.toggle("hidden", !show);
    if (show) { findInput.focus(); findStatus.textContent = ""; }
  }

  findbar.addEventListener("click", function (e) {
    var btn = e.target.closest(".fbtn");
    if (!btn) return;
    var action = btn.getAttribute("data-action");
    var q = findInput.value;
    var r = replaceInput.value;
    var text = codeArea.value;

    if (action === "closefind") { toggleFindBar(false); return; }
    if (!q) return;

    if (action === "findnext") {
      var from = codeArea.selectionEnd || 0;
      var idx = text.toLowerCase().indexOf(q.toLowerCase(), from);
      if (idx === -1) idx = text.toLowerCase().indexOf(q.toLowerCase(), 0);
      if (idx === -1) { findStatus.textContent = "Tidak ditemukan."; return; }
      codeArea.focus();
      codeArea.setSelectionRange(idx, idx + q.length);
      findStatus.textContent = "Ditemukan di posisi " + idx + ".";
    } else if (action === "replace") {
      var start = codeArea.selectionStart, end = codeArea.selectionEnd;
      var sel = text.substring(start, end);
      if (sel.toLowerCase() === q.toLowerCase()) {
        codeArea.focus();
        codeArea.setSelectionRange(start, end);
        document.execCommand("insertText", false, r);
      }
      findbar.querySelector('[data-action="findnext"]').click();
    } else if (action === "replaceall") {
      var re = new RegExp(q.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"), "gi");
      var count = (text.match(re) || []).length;
      if (count === 0) { findStatus.textContent = "Tidak ditemukan."; return; }
      var newText = text.replace(re, function () { return r; });
      setCode(newText);
      findStatus.textContent = count + " kemunculan diganti.";
    }
  });

  // ============================================================
  //  API yang dipanggil dari Kotlin lewat evaluateJavascript()
  // ============================================================
  function setCode(text) {
    codeArea.value = text;
    updateGutter();
    highlightCode.innerHTML = highlight(text);
    codeArea.setSelectionRange(0, 0);
    notifyKotlin(text);
  }

  window.EditorAPI = {
    setCode: setCode,
    getCode: function () { return codeArea.value; }
  };

  // ============================================================
  //  Init
  // ============================================================
  updateGutter();
  highlightCode.innerHTML = highlight(codeArea.value);

  window.addEventListener("load", function () {
    if (window.AndroidBridge && window.AndroidBridge.onEditorReady) {
      window.AndroidBridge.onEditorReady();
    }
  });
})();
