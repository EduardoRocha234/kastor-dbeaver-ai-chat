(function () {
  'use strict';
  var list = document.getElementById('messages');
  var scroller = document.getElementById('scroll');
  var input = document.getElementById('input');
  var sendBtn = document.getElementById('send');
  var stopBtn = document.getElementById('stop');
  var menu = document.getElementById('mention-menu');
  var running = false;
  var sent = [];
  var histIdx = -1;
  var draft = '';

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text !== undefined && text !== null) { e.textContent = text; }
    return e;
  }

  function button(label, onClick, cls) {
    var b = el('button', cls, label);
    b.type = 'button';
    b.addEventListener('click', onClick);
    return b;
  }

  function byId(id) { return document.getElementById(id); }

  // Follow new content only while the user is at the bottom; reading older messages isn't interrupted.
  var stick = true;
  scroller.addEventListener('scroll', function () {
    stick = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < 80;
  });
  function scroll(force) {
    if (force || stick) { scroller.scrollTop = scroller.scrollHeight; }
  }

  function append(node) {
    document.body.classList.add('started');
    list.appendChild(node);
    scroll();
    return node;
  }

  function assistantRow(id) {
    var row = el('div', 'row assistant');
    row.appendChild(el('div', 'avatar'));
    var bubble = el('div', 'bubble');
    bubble.id = id;
    row.appendChild(bubble);
    return row;
  }

  /** User text with @mentions highlighted; built from text nodes only. */
  function userText(text) {
    var frag = document.createDocumentFragment();
    var re = /(^|\s)(@[^\s@]+)/g;
    var last = 0;
    var m;
    while ((m = re.exec(text)) !== null) {
      var at = m.index + m[1].length;
      frag.appendChild(document.createTextNode(text.substring(last, at)));
      frag.appendChild(el('span', 'mention', m[2]));
      last = at + m[2].length;
    }
    frag.appendChild(document.createTextNode(text.substring(last)));
    return frag;
  }

  var SQL_KEYWORDS = ('select from where and or not in is null as on join left right inner outer full cross group by order ' +
    'having limit offset union all distinct insert into values update set delete create alter drop table view index ' +
    'primary key foreign references constraint default case when then else end exists between like ilike asc desc ' +
    'with recursive returning count sum avg min max coalesce cast true false begin commit rollback grant revoke ' +
    'schema database if replace truncate explain analyze over partition window fetch first next rows only top').split(' ');
  var SQL_KW = {};
  for (var k = 0; k < SQL_KEYWORDS.length; k++) { SQL_KW[SQL_KEYWORDS[k]] = true; }
  var SQL_TOKEN = /(--[^\n]*|\/\*[\s\S]*?\*\/)|('(?:[^']|'')*'?)|("(?:[^"]|"")*"?)|(\b\d+(?:\.\d+)?\b)|([A-Za-z_][A-Za-z0-9_$]*)(?=(\s*\())?/g;

  /** Colors SQL in a code block; built from text nodes only, so model output is never parsed as HTML. */
  function highlightSql(code) {
    var lang = /language-(\S+)/.exec(code.className || '');
    if ((lang && lang[1].toLowerCase() !== 'sql') || code.getAttribute('data-hl')) { return; }
    code.setAttribute('data-hl', '1');
    var src = code.textContent;
    var frag = document.createDocumentFragment();
    var last = 0;
    var m;
    SQL_TOKEN.lastIndex = 0;
    while ((m = SQL_TOKEN.exec(src)) !== null) {
      var cls = null;
      if (m[1]) { cls = 'sql-comment'; }
      else if (m[2]) { cls = 'sql-str'; }
      else if (m[4]) { cls = 'sql-num'; }
      else if (m[5]) {
        if (SQL_KW[m[5].toLowerCase()]) { cls = 'sql-kw'; } else if (m[6]) { cls = 'sql-fn'; }
      }
      if (!cls) { continue; }
      frag.appendChild(document.createTextNode(src.substring(last, m.index)));
      frag.appendChild(el('span', cls, m[0]));
      last = m.index + m[0].length;
    }
    frag.appendChild(document.createTextNode(src.substring(last)));
    code.textContent = '';
    code.appendChild(frag);
  }

  function addCodeActions(code) {
    highlightSql(code);
    var pre = code.parentNode;
    if (pre.getAttribute('data-actions')) { return; }
    pre.setAttribute('data-actions', '1');
    var bar = el('div', 'code-actions');
    bar.appendChild(button('Inserir no editor', function () { aichatInsertSql(code.textContent); }));
    bar.appendChild(button('Abrir em novo editor', function () { aichatOpenSql(code.textContent); }));
    bar.appendChild(button('Copiar', function () { aichatCopy(code.textContent); }));
    pre.parentNode.insertBefore(bar, pre.nextSibling);
  }

  function settle(box, label) {
    var actions = box.querySelector('.actions');
    if (actions) { actions.parentNode.replaceChild(el('div', 'decision', label), actions); }
  }

  // Links never navigate the chat page (that would wipe it); http(s) ones open outside.
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a') : null;
    if (!a) { return; }
    e.preventDefault();
    var href = a.getAttribute('href') || '';
    if (/^https?:\/\//i.test(href)) { aichatOpenLink(href); }
  });

  /* ---------- Composer ---------- */

  function autosize() {
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 180) + 'px';
    input.style.overflowY = input.scrollHeight > 180 ? 'auto' : 'hidden';
  }

  /** Send only makes sense with something typed. */
  function updateSend() { sendBtn.disabled = !input.value.trim(); }

  /** Programmatic fill (history recall); cursor goes to the end. */
  function setInput(value) {
    input.value = value;
    input.setSelectionRange(value.length, value.length);
    autosize();
    updateSend();
    hideMenu();
  }

  function submit() {
    if (running || !input.value.trim()) { return; }
    hideMenu();
    aichatSend(input.value);
  }

  input.addEventListener('keydown', function (e) {
    if (!menu.hidden) {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        move(e.key === 'ArrowDown' ? 1 : -1);
        return;
      }
      if (e.key === 'Enter' || e.key === 'Tab') {
        e.preventDefault();
        acceptActive();
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        hideMenu();
        return;
      }
    }
    if ((e.key === 'ArrowUp' || e.key === 'ArrowDown') && !e.isComposing
        && !e.shiftKey && !e.ctrlKey && !e.altKey && !e.metaKey
        && input.selectionStart === input.selectionEnd) {
      var pos = input.selectionStart;
      if (e.key === 'ArrowUp' && sent.length > 0 && input.value.lastIndexOf('\n', pos - 1) === -1) {
        e.preventDefault();
        if (histIdx === -1) {
          draft = input.value;
          histIdx = sent.length - 1;
        } else {
          histIdx = Math.max(0, histIdx - 1);
        }
        setInput(sent[histIdx]);
        return;
      }
      if (e.key === 'ArrowDown' && histIdx !== -1 && input.value.indexOf('\n', pos) === -1) {
        e.preventDefault();
        histIdx++;
        if (histIdx >= sent.length) {
          histIdx = -1;
          setInput(draft);
        } else {
          setInput(sent[histIdx]);
        }
        return;
      }
    }
    if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
      e.preventDefault();
      submit();
    }
  });
  input.addEventListener('input', function () { histIdx = -1; autosize(); updateSend(); updateMention(); });
  input.addEventListener('paste', function () {
    setTimeout(function () {
      autosize(); updateSend();
      if (input.value.length >= 50000) {
        append(el('div', 'notice', 'Texto limitado a 50.000 caracteres.'));
      }
    }, 0);
  });
  input.addEventListener('click', updateMention);
  input.addEventListener('keyup', function (e) {
    if (/^(ArrowLeft|ArrowRight|Home|End)$/.test(e.key)) { updateMention(); }
  });
  input.addEventListener('blur', function () { setTimeout(hideMenu, 150); });
  sendBtn.addEventListener('click', submit);
  stopBtn.addEventListener('click', function () { aichatStop(''); });

  var chips = document.querySelectorAll('#welcome .chip');
  for (var c = 0; c < chips.length; c++) {
    chips[c].addEventListener('click', function (e) {
      setInput(e.currentTarget.textContent, false);
    });
  }

  function setInput(text, selectAll) {
    input.value = text;
    autosize();
    updateSend();
    input.focus();
    if (selectAll) { input.select(); } else { input.setSelectionRange(text.length, text.length); }
    updateMention();
  }

  /* ---------- @ mentions ---------- */

  var mentionSeq = 0;
  var current = null;   // {start, text} of the "@query" under the caret
  var items = [];
  var active = -1;

  /** Same rule as Mentions.queryAt: '@' at the start or after whitespace, no whitespace up to the caret. */
  function queryAt(text, caret) {
    for (var i = caret - 1; i >= 0; i--) {
      var ch = text.charAt(i);
      if (ch === '@') {
        return i === 0 || /\s/.test(text.charAt(i - 1)) ? {start: i, text: text.substring(i + 1, caret)} : null;
      }
      if (/\s/.test(ch)) { return null; }
    }
    return null;
  }

  function updateMention() {
    var q = queryAt(input.value, input.selectionStart);
    if (!q) { hideMenu(); return; }
    if (current && current.start === q.start && current.text === q.text && !menu.hidden) { return; }
    current = q;
    mentionSeq++;
    aichatMention(JSON.stringify({seq: mentionSeq, query: q.text}));
  }

  function hideMenu() {
    menu.hidden = true;
    current = null;
    items = [];
    active = -1;
  }

  function highlight(path, query) {
    var span = el('span', 'path');
    var q = (query || '').split('.').pop();
    var i = q ? path.toLowerCase().lastIndexOf(q.toLowerCase()) : -1;
    if (i < 0) { span.textContent = path; return span; }
    span.appendChild(document.createTextNode(path.substring(0, i)));
    span.appendChild(el('b', null, path.substring(i, i + q.length)));
    span.appendChild(document.createTextNode(path.substring(i + q.length)));
    return span;
  }

  function renderMenu() {
    closePicker();
    menu.innerHTML = '';
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      if (it.info) {
        menu.appendChild(el('div', 'info', it.info));
        continue;
      }
      var row = el('div', 'item' + (i === active ? ' active' : ''));
      row.setAttribute('role', 'option');
      row.appendChild(el('span', 'kind', it.type));
      row.appendChild(highlight(it.path, current ? current.text : ''));
      (function (index) {
        // mousedown keeps the focus in the textarea, so the blur handler doesn't close the menu first.
        row.addEventListener('mousedown', function (e) { e.preventDefault(); accept(index); });
        row.addEventListener('mousemove', function () {
          if (active !== index) { active = index; renderMenu(); }
        });
      }(i));
      menu.appendChild(row);
    }
    menu.hidden = false;
    var a = menu.querySelector('.item.active');
    if (a && a.scrollIntoView) { a.scrollIntoView({block: 'nearest'}); }
  }

  function move(delta) {
    if (!items.length || items[0].info) { return; }
    active = (active + delta + items.length) % items.length;
    renderMenu();
  }

  function acceptActive() {
    if (active >= 0 && items[active] && !items[active].info) { accept(active); } else { hideMenu(); }
  }

  function accept(index) {
    var it = items[index];
    var q = current;
    if (!it || !q) { return; }
    var caret = input.selectionStart;
    var v = input.value;
    input.value = v.substring(0, q.start) + it.replacement + v.substring(caret);
    var pos = q.start + it.replacement.length;
    input.setSelectionRange(pos, pos);
    input.focus();
    autosize();
    updateSend();
    hideMenu();
    if (it.drill) { updateMention(); }   // drill into the database or schema: list its children right away
  }

  /* ---------- Header ---------- */

  var contextToggle = byId('context-toggle');
  contextToggle.addEventListener('change', function () { aichatToggleContext(contextToggle.checked ? 'true' : 'false'); });
  byId('settings-btn').addEventListener('click', function () { aichatOpenSettings(''); });
  byId('new-btn').addEventListener('click', function () { aichatNewConversation(''); });

  /* ---------- Selector pills (connection, model) with a menu that opens upwards ---------- */

  var composer = byId('composer');
  var pickMenu = byId('pick-menu');
  var openPicker = null;
  var pickActive = -1;

  function picker(id, title, onPick) {
    var p = {btn: byId(id), title: title, entries: [], selected: null, onPick: onPick};
    p.btn.addEventListener('click', function (e) {
      e.stopPropagation();
      if (openPicker === p) { closePicker(); } else { showPicker(p); }
    });
    return p;
  }

  var connectionPicker = picker('connection', 'Conexão (Ctrl+Alt+C)', function (id) { aichatSelectConnection(id); });
  var providerPicker = picker('provider', 'Modelo (Ctrl+Alt+M)', function (id) { aichatSelectProvider(id); });

  // Ctrl+Alt+C / Ctrl+Alt+M open the pickers (unused by DBeaver). e.code, not e.key: on ABNT2 keyboards
  // Ctrl+Alt is AltGr and changes the character.
  document.addEventListener('keydown', function (e) {
    if (!(e.ctrlKey || e.metaKey) || !e.altKey || e.shiftKey) { return; }
    var p = e.code === 'KeyC' ? connectionPicker : e.code === 'KeyM' ? providerPicker : null;
    if (!p) { return; }
    e.preventDefault();
    e.stopPropagation();
    if (openPicker === p) { closePicker(); } else { showPicker(p); }
  }, true);

  function setPicker(p, entries, selected) {
    p.entries = entries;
    p.selected = selected;
    var current = null;
    for (var i = 0; i < entries.length; i++) { if (entries[i].id === selected) { current = entries[i]; } }
    if (!current && entries.length) { current = entries[0]; }
    var label = current ? current.label : '';
    p.btn.querySelector('.pill-label').textContent = label;
    p.btn.title = p.title + ': ' + label;
    if (openPicker === p) { renderPicker(); }
  }

  function showPicker(p) {
    hideMenu();
    if (openPicker) { openPicker.btn.classList.remove('open'); }
    openPicker = p;
    p.btn.classList.add('open');
    pickActive = 0;
    for (var i = 0; i < p.entries.length; i++) { if (p.entries[i].id === p.selected) { pickActive = i; } }
    renderPicker();
    // Align with the pill, but keep the whole menu inside the composer.
    var box = composer.getBoundingClientRect();
    var left = p.btn.getBoundingClientRect().left - box.left;
    pickMenu.style.left = Math.max(12, Math.min(left, box.width - pickMenu.offsetWidth - 12)) + 'px';
  }

  function closePicker() {
    if (!openPicker) { return; }
    openPicker.btn.classList.remove('open');
    openPicker = null;
    pickMenu.hidden = true;
  }

  function renderPicker() {
    var p = openPicker;
    pickMenu.innerHTML = '';
    for (var i = 0; i < p.entries.length; i++) {
      var entry = p.entries[i];
      var opt = el('div', 'opt' + (i === pickActive ? ' active' : ''));
      opt.setAttribute('role', 'option');
      opt.appendChild(el('span', 'check', entry.id === p.selected ? '✓' : ''));
      opt.appendChild(el('span', 'text', entry.label));
      opt.title = entry.label;
      (function (index) {
        opt.addEventListener('mousedown', function (e) { e.preventDefault(); pick(index); });
        opt.addEventListener('mousemove', function () {
          if (pickActive !== index) { pickActive = index; renderPicker(); }
        });
      }(i));
      pickMenu.appendChild(opt);
    }
    pickMenu.hidden = false;
    var a = pickMenu.querySelector('.opt.active');
    if (a && a.scrollIntoView) { a.scrollIntoView({block: 'nearest'}); }
  }

  function pick(index) {
    var p = openPicker;
    var entry = p && p.entries[index];
    closePicker();
    if (!entry) { return; }
    if (entry.id !== p.selected) {
      setPicker(p, p.entries, entry.id);
      p.onPick(entry.id);
    }
    input.focus();
  }

  document.addEventListener('mousedown', function (e) {
    if (openPicker && !pickMenu.contains(e.target) && !openPicker.btn.contains(e.target)) { closePicker(); }
  });
  document.addEventListener('keydown', function (e) {
    if (!openPicker || !/^(ArrowDown|ArrowUp|Enter|Escape)$/.test(e.key)) { return; }
    // Captured before the textarea sees it, so Enter picks an option instead of sending.
    e.stopPropagation();
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      var n = openPicker.entries.length;
      pickActive = (pickActive + (e.key === 'ArrowDown' ? 1 : -1) + n) % n;
      renderPicker();
    } else if (e.key === 'Enter') {
      e.preventDefault();
      pick(pickActive);
    } else if (e.key === 'Escape') {
      e.preventDefault();
      closePicker();
    }
  }, true);

  /* ---------- Conversations sidebar ---------- */

  var side = byId('sidebar');
  var overlay = byId('overlay');
  var search = byId('side-search');
  var convList = byId('conversations');
  var conversations = [];
  var activeConversation = null;
  var confirming = null;

  function openSidebar() {
    document.body.classList.add('side-open');
    overlay.hidden = false;
    confirming = null;
    search.value = '';
    renderConversations();
    aichatListConversations('');
    setTimeout(function () { search.focus(); }, 50);
  }

  function closeSidebar() {
    document.body.classList.remove('side-open');
    overlay.hidden = true;
    input.focus();
  }

  byId('menu-btn').addEventListener('click', openSidebar);
  byId('close-side').addEventListener('click', closeSidebar);
  overlay.addEventListener('click', closeSidebar);
  byId('side-new').addEventListener('click', function () { closeSidebar(); aichatNewConversation(''); });
  search.addEventListener('input', renderConversations);
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && document.body.classList.contains('side-open')) { closeSidebar(); }
  });

  function groupOf(time) {
    var day = 24 * 3600 * 1000;
    var today = new Date();
    today.setHours(0, 0, 0, 0);
    var t = today.getTime();
    if (time >= t) { return 'Hoje'; }
    if (time >= t - day) { return 'Ontem'; }
    if (time >= t - 7 * day) { return 'Últimos 7 dias'; }
    if (time >= t - 30 * day) { return 'Últimos 30 dias'; }
    var month = new Date(time).toLocaleDateString('pt-BR', {month: 'long', year: 'numeric'});
    return month.charAt(0).toUpperCase() + month.substring(1);
  }

  function renderConversations() {
    convList.innerHTML = '';
    var q = search.value.trim().toLowerCase();
    var group = null;
    var shown = 0;
    for (var i = 0; i < conversations.length; i++) {
      var c = conversations[i];
      if (q && c.title.toLowerCase().indexOf(q) < 0) { continue; }
      var g = groupOf(c.updatedAt);
      if (g !== group) { convList.appendChild(el('div', 'group', g)); group = g; }
      convList.appendChild(conversationRow(c));
      shown++;
    }
    if (!shown) { convList.appendChild(el('div', 'empty', q ? 'Nenhuma conversa encontrada.' : 'Nenhuma conversa ainda.')); }
  }

  function conversationRow(c) {
    var row = el('div', 'conv' + (c.id === activeConversation ? ' active' : '') + (c.id === confirming ? ' confirm' : ''));
    row.title = c.title;
    row.appendChild(el('span', 'name', c.id === confirming ? 'Clique na lixeira de novo para apagar' : c.title));
    if (c.id === activeConversation && c.id !== confirming) { row.appendChild(el('span', 'badge', 'Atual')); }
    var del = el('button', 'icon del');
    del.type = 'button';
    del.title = 'Apagar conversa';
    del.innerHTML = '<svg viewBox="0 0 24 24" width="14" height="14"><path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13" ' +
      'fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
    del.addEventListener('click', function (e) {
      e.stopPropagation();
      if (confirming === c.id) {
        confirming = null;
        aichatDeleteConversation(c.id);
      } else {
        confirming = c.id;
        renderConversations();
      }
    });
    row.appendChild(del);
    row.addEventListener('click', function () {
      confirming = null;
      closeSidebar();
      if (c.id !== activeConversation) { aichatOpenConversation(c.id); }
    });
    return row;
  }

  /* ---------- API called from Java ---------- */

  window.ChatUI = {
    clear: function () {
      list.innerHTML = '';
      document.body.classList.remove('started');
      sent = [];
      histIdx = -1;
      draft = '';
    },
    addUser: function (text) {
      sent.push(text);
      histIdx = -1;
      var row = el('div', 'row user');
      var bubble = el('div', 'bubble');
      bubble.appendChild(userText(text));
      row.appendChild(bubble);
      append(row);
      scroll(true);
    },
    startAssistant: function (id) {
      var row = assistantRow(id);
      var typing = el('span', 'typing');
      typing.appendChild(el('i'));
      typing.appendChild(el('i'));
      typing.appendChild(el('i'));
      row.querySelector('.bubble').appendChild(typing);
      append(row);
    },
    setAssistantHtml: function (id, html) {
      var d = byId(id);
      if (!d) { return; }
      d.innerHTML = html;
      var blocks = d.querySelectorAll('pre > code');
      for (var i = 0; i < blocks.length; i++) { addCodeActions(blocks[i]); }
      scroll();
    },
    finishAssistant: function (id) {
      var d = byId(id);
      if (d && !d.textContent.trim()) { d.parentNode.parentNode.removeChild(d.parentNode); }
    },
    addTool: function (id, name, args) {
      var card = el('details', 'tool');
      card.id = id;
      var summary = el('summary');
      summary.appendChild(el('span', 'name', name));
      summary.appendChild(el('span', 'status'));
      card.appendChild(summary);
      card.appendChild(el('pre', 'args', args));
      card.appendChild(el('pre', 'result', 'executando…'));
      append(card);
    },
    setToolResult: function (id, text, isError) {
      var card = byId(id);
      if (!card) { return; }
      card.querySelector('.result').textContent = text;
      card.className += isError ? ' error' : ' done';
    },
    addApproval: function (id, connection, sql, reason) {
      var box = el('div', 'approval');
      box.id = 'approval-' + id;
      box.appendChild(el('div', 'title', 'Kastor quer executar um comando de escrita em ' + connection + ':'));
      if (reason) { box.appendChild(el('div', 'reason', reason)); }
      box.appendChild(el('pre', null, sql));
      var actions = el('div', 'actions');
      actions.appendChild(button('Aprovar e executar', function () { settle(box, 'Aprovado'); aichatApprove(id); }, 'primary'));
      actions.appendChild(button('Rejeitar', function () { settle(box, 'Rejeitado'); aichatReject(id); }));
      box.appendChild(actions);
      append(box);
    },
    closeApproval: function (id, label) {
      var box = byId('approval-' + id);
      if (box) { settle(box, label); }
    },
    addFileCard: function (path, name, format, details) {
      var card = el('div', 'file-card');
      card.appendChild(el('div', 'file-icon ' + format, format.toUpperCase()));
      var info = el('div', 'file-info');
      info.appendChild(el('div', 'file-name', name));
      info.appendChild(el('div', 'file-details', details));
      card.appendChild(info);
      var actions = el('div', 'actions');
      actions.appendChild(button('Abrir', function () { aichatOpenFile(path); }, 'primary'));
      actions.appendChild(button('Abrir pasta', function () { aichatRevealFile(path); }));
      actions.appendChild(button('Salvar como…', function () { aichatSaveFileAs(path); }));
      card.appendChild(actions);
      card.title = path;
      append(card);
    },
    addNotice: function (text) { append(el('div', 'notice', text)); },
    addLimitNotice: function (text) {
      var n = el('div', 'notice', text);
      var more = el('div', 'more',
        'Cada chamada ao modelo é um passo do agente (raciocinar, rodar uma query, analisar o resultado). ' +
        'O limite evita laços infinitos e gasto excessivo de tokens. Aumente em Preferências > Kastor > Limites ' +
        '(máximo 50) ou envie "continue" para o agente seguir de onde parou.');
      more.hidden = true;
      var actions = el('div', 'actions');
      actions.appendChild(button('Editar limite', function () { aichatOpenSettings(''); }));
      actions.appendChild(button('Saiba mais', function () { more.hidden = !more.hidden; }));
      n.appendChild(actions);
      n.appendChild(more);
      append(n);
    },
    addError: function (text) { append(el('div', 'error-msg', text)); },

    setProviders: function (entries, activeId) { setPicker(providerPicker, entries, activeId); },
    setConnections: function (entries, selectedId) { setPicker(connectionPicker, entries, selectedId); },
    setEditorContext: function (text, checked) {
      contextToggle.checked = !!checked;
      byId('context-label').title = 'Contexto do editor (schema padrão e SQL selecionado vão junto): ' + text;
    },
    setConversations: function (entries, activeId) {
      conversations = entries;
      activeConversation = activeId;
      renderConversations();
    },
    insertMentions: function (paths) {
      var v = input.value;
      var caret = document.activeElement === input ? input.selectionStart : v.length;
      var before = v.substring(0, caret);
      var text = paths.map(function (p) { return '@' + p; }).join(' ') + ' ';
      if (before && !/\s$/.test(before)) { text = ' ' + text; }
      input.value = before + text + v.substring(caret);
      var pos = before.length + text.length;
      input.focus();
      input.setSelectionRange(pos, pos);
      autosize();
      updateSend();
      hideMenu();
    },
    setVersion: function (text) { byId('version').textContent = text; },
    setUpdate: function (version, url) {
      byId('update-text').textContent = 'Kastor ' + version + ' disponível. Atualize em Ajuda > Check for Updates.';
      byId('update-open').onclick = function () { aichatOpenLink(url); };
      byId('update-dismiss').onclick = function () { aichatDismissUpdate(version); };
      byId('update-banner').hidden = false;
    },
    hideUpdate: function () { byId('update-banner').hidden = true; },
    setTitle: function (text) {
      var t = byId('title');
      t.textContent = text;
      t.title = text;
    },
    setTheme: function (dark) { document.documentElement.classList.toggle('dark', !!dark); },
    setRunning: function (value) {
      running = !!value;
      sendBtn.hidden = running;
      stopBtn.hidden = !running;
    },
    clearInput: function () { histIdx = -1; draft = ''; input.value = ''; autosize(); updateSend(); hideMenu(); },
    /** Prefills the composer unless the user already typed a draft there. */
    setInput: function (text, selectAll) {
      if (input.value.trim()) { input.focus(); return; }
      setInput(text, selectAll);
    },
    focusInput: function () { input.focus(); },
    showMentions: function (seq, entries) {
      if (seq !== mentionSeq || !current) { return; }
      items = entries;
      active = entries.length && !entries[0].info ? 0 : -1;
      renderMenu();
    }
  };
}());
