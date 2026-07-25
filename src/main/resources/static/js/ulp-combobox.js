/* ═══════════════════════════════════════════════════════════════════════════
   ULP — Searchable combobox
   Progressive enhancement over a native <select data-combobox>: keeps the
   select as the form control (so POST/validation/flash-repopulate are
   unchanged) and layers a type-to-filter text input on top of it.
   Exposes window.UlpCombobox.enhanceAll() for pages loaded after this script.
   ══════════════════════════════════════════════════════════════════════════ */

(function () {
  'use strict';

  var ACTIVE = 'is-active';

  /** Case- and diacritic-insensitive haystack for matching Vietnamese names. */
  function fold(text) {
    return text
      .toLowerCase()
      .normalize('NFD')
      .replace(/[̀-ͯ]/g, '')
      .replace(/đ/g, 'd');
  }

  /**
   * Wraps one native select in a searchable combobox.
   *
   * @param {HTMLSelectElement} select the control to enhance
   */
  function enhance(select) {
    if (select.dataset.comboboxReady === 'true') return;
    select.dataset.comboboxReady = 'true';

    // Options are read once: these catalogues are server-rendered and static
    // for the life of the page.
    var options = Array.prototype.slice.call(select.options)
      .filter(function (opt) { return opt.value !== ''; })
      .map(function (opt) {
        return { value: opt.value, label: opt.textContent.trim(), needle: fold(opt.textContent) };
      });

    var placeholder = select.dataset.comboboxPlaceholder || 'Gõ để tìm…';
    var emptyText = select.dataset.comboboxEmpty || 'Không tìm thấy kết quả';

    var root = document.createElement('div');
    root.className = 'ulp-combo';

    var input = document.createElement('input');
    input.type = 'text';
    input.className = 'ulp-combo-input';
    input.placeholder = placeholder;
    input.autocomplete = 'off';
    input.setAttribute('role', 'combobox');
    input.setAttribute('aria-expanded', 'false');
    input.setAttribute('aria-autocomplete', 'list');
    // The <label for> still points at the select, so mirror it onto the input
    // that actually receives focus.
    if (select.id) {
      var label = document.querySelector('label[for="' + select.id + '"]');
      if (label) input.setAttribute('aria-label', label.textContent.replace('*', '').trim());
    }

    var list = document.createElement('ul');
    list.className = 'ulp-combo-list';
    list.setAttribute('role', 'listbox');
    list.hidden = true;

    var listId = (select.id || 'combo') + '-list';
    list.id = listId;
    input.setAttribute('aria-controls', listId);

    var clear = document.createElement('button');
    clear.type = 'button';
    clear.className = 'ulp-combo-clear';
    clear.setAttribute('aria-label', 'Xoá lựa chọn');
    clear.textContent = '×';
    clear.hidden = true;

    select.parentNode.insertBefore(root, select);
    root.appendChild(input);
    root.appendChild(clear);
    root.appendChild(list);
    root.appendChild(select);
    select.classList.add('ulp-combo-native');

    var filtered = options.slice();
    var activeIndex = -1;

    function labelFor(value) {
      for (var i = 0; i < options.length; i++) {
        if (options[i].value === value) return options[i].label;
      }
      return '';
    }

    function close() {
      list.hidden = true;
      input.setAttribute('aria-expanded', 'false');
      activeIndex = -1;
    }

    function render(items) {
      list.textContent = '';
      if (!items.length) {
        var empty = document.createElement('li');
        empty.className = 'ulp-combo-empty';
        empty.textContent = emptyText;
        list.appendChild(empty);
        return;
      }
      items.forEach(function (item, i) {
        var li = document.createElement('li');
        li.className = 'ulp-combo-option';
        li.setAttribute('role', 'option');
        li.dataset.value = item.value;
        li.textContent = item.label;
        if (item.value === select.value) li.setAttribute('aria-selected', 'true');
        if (i === activeIndex) li.classList.add(ACTIVE);
        list.appendChild(li);
      });
    }

    function open(query) {
      var needle = fold(query || '');
      filtered = needle
        ? options.filter(function (o) { return o.needle.indexOf(needle) !== -1; })
        : options.slice();
      activeIndex = filtered.length ? 0 : -1;
      render(filtered);
      list.hidden = false;
      input.setAttribute('aria-expanded', 'true');
    }

    function choose(value) {
      select.value = value;
      input.value = labelFor(value);
      clear.hidden = !value;
      // Fire change so any listener bound to the original select still runs.
      select.dispatchEvent(new Event('change', { bubbles: true }));
      close();
    }

    function moveActive(delta) {
      if (list.hidden) { open(input.value === labelFor(select.value) ? '' : input.value); return; }
      if (!filtered.length) return;
      activeIndex = (activeIndex + delta + filtered.length) % filtered.length;
      render(filtered);
      var el = list.children[activeIndex];
      if (el && el.scrollIntoView) el.scrollIntoView({ block: 'nearest' });
    }

    // Seed from whatever the server selected (form repopulate after a failed POST).
    if (select.value) {
      input.value = labelFor(select.value);
      clear.hidden = false;
    }

    input.addEventListener('focus', function () { open(''); });

    input.addEventListener('input', function () {
      // Typing invalidates the current pick until something is chosen again,
      // otherwise a half-typed query would still submit the old value.
      select.value = '';
      clear.hidden = !input.value;
      open(input.value);
    });

    input.addEventListener('keydown', function (ev) {
      if (ev.key === 'ArrowDown') { ev.preventDefault(); moveActive(1); }
      else if (ev.key === 'ArrowUp') { ev.preventDefault(); moveActive(-1); }
      else if (ev.key === 'Enter') {
        if (!list.hidden && activeIndex >= 0 && filtered[activeIndex]) {
          ev.preventDefault();
          choose(filtered[activeIndex].value);
        }
      } else if (ev.key === 'Escape') {
        close();
      }
    });

    list.addEventListener('mousedown', function (ev) {
      // mousedown, not click: blur would close the list before click fires.
      var opt = ev.target.closest('.ulp-combo-option');
      if (!opt) return;
      ev.preventDefault();
      choose(opt.dataset.value);
    });

    clear.addEventListener('click', function () {
      select.value = '';
      input.value = '';
      clear.hidden = true;
      input.focus();
      open('');
    });

    input.addEventListener('blur', function () {
      // Restore the chosen label so the box never sits on a dangling query.
      window.setTimeout(function () {
        input.value = select.value ? labelFor(select.value) : '';
        clear.hidden = !select.value;
        close();
      }, 120);
    });

    // A required select with no value blocks submit but is visually hidden, so
    // surface the message on the input the user can actually see.
    select.addEventListener('invalid', function () {
      input.setAttribute('aria-invalid', 'true');
      input.focus();
    });
  }

  /** Enhances every `select[data-combobox]` not yet wrapped. */
  function enhanceAll(scope) {
    var root = scope || document;
    Array.prototype.slice.call(root.querySelectorAll('select[data-combobox]')).forEach(enhance);
  }

  window.UlpCombobox = { enhance: enhance, enhanceAll: enhanceAll };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { enhanceAll(); });
  } else {
    enhanceAll();
  }
})();
