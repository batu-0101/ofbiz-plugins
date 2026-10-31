(function () {
  'use strict';
  function wire() {
    var root = document.getElementById('books-document');
    if (!root) return;
    var dialog = root.querySelector('#books-fee-dialog');
    var pay = root.querySelector('[data-books-pay]');
    if (dialog && typeof dialog.showModal === 'function') {
      dialog.close();
      pay.hidden = false;
      pay.onclick = function () { dialog.showModal(); };
      var back = dialog.querySelector('[data-books-back]');
      back.hidden = false;
      back.onclick = function () { dialog.close(); pay.focus(); };
      dialog.addEventListener('close', function () { pay.focus(); });
    }
    var approval = root.querySelector('[data-books-approve]');
    if (approval) approval.addEventListener('submit', async function (event) {
      event.preventDefault();
      var button = approval.querySelector('button[type="submit"]');
      button.disabled = true;
      try {
        var response = await fetch(approval.action, { method: 'POST', body: new URLSearchParams(new FormData(approval)), credentials: 'same-origin' });
        var html = new DOMParser().parseFromString(await response.text(), 'text/html');
        var next = html.getElementById('books-document');
        if (!next) throw new Error('Your session expired or the server did not return the document. Refresh to check its current state.');
        root.replaceWith(next);
        next.classList.add('is-transforming');
        next.setAttribute('tabindex', '-1');
        next.focus();
        wire();
      } catch (error) {
        button.disabled = false;
        var message = document.createElement('p');
        message.setAttribute('role', 'alert');
        message.textContent = 'The result could not be confirmed. Refresh the document before retrying; approval will not create a duplicate invoice.';
        approval.appendChild(message);
      }
    });
  }
  wire();
})();
