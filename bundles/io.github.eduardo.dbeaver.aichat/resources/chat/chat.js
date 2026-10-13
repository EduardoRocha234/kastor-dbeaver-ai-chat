(function () {
  'use strict';
  var list = document.getElementById('messages');

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text !== undefined && text !== null) { e.textContent = text; }
    return e;
  }

  function button(label, onClick) {
    var b = el('button', null, label);
    b.addEventListener('click', onClick);
    return b;
  }

  function byId(id) { return document.getElementById(id); }

  function scroll() { window.scrollTo(0, document.body.scrollHeight); }

  function addCodeActions(code) {
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

  window.ChatUI = {
    clear: function () { list.innerHTML = ''; },
    addUser: function (text) { list.appendChild(el('div', 'msg user', text)); scroll(); },
    startAssistant: function (id) {
      var d = el('div', 'msg assistant');
      d.id = id;
      list.appendChild(d);
      scroll();
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
      if (d && !d.textContent.trim()) { d.parentNode.removeChild(d); }
    },
    addTool: function (id, name, args) {
      var card = el('details', 'tool');
      card.id = id;
      card.appendChild(el('summary', null, 'Ferramenta: ' + name));
      card.appendChild(el('pre', 'args', args));
      card.appendChild(el('pre', 'result', 'executando…'));
      list.appendChild(card);
      scroll();
    },
    setToolResult: function (id, text, isError) {
      var card = byId(id);
      if (!card) { return; }
      card.querySelector('.result').textContent = text;
      if (isError) { card.className += ' error'; }
    },
    addApproval: function (id, connection, sql, reason) {
      var box = el('div', 'approval');
      box.id = 'approval-' + id;
      box.appendChild(el('div', 'title', 'A IA quer executar um comando de escrita na conexão ' + connection + ':'));
      if (reason) { box.appendChild(el('div', 'reason', reason)); }
      box.appendChild(el('pre', null, sql));
      var actions = el('div', 'actions');
      actions.appendChild(button('Aprovar e executar', function () { settle(box, 'Aprovado'); aichatApprove(id); }));
      actions.appendChild(button('Rejeitar', function () { settle(box, 'Rejeitado'); aichatReject(id); }));
      box.appendChild(actions);
      list.appendChild(box);
      scroll();
    },
    closeApproval: function (id, label) {
      var box = byId('approval-' + id);
      if (box) { settle(box, label); }
    },
    addNotice: function (text) { list.appendChild(el('div', 'notice', text)); scroll(); },
    addError: function (text) { list.appendChild(el('div', 'error-msg', text)); scroll(); }
  };
}());
