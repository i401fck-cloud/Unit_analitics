(function (token) {
  'use strict';

  function send(obj) {
    obj.token = token;
    try { AndroidBridge.onResult(JSON.stringify(obj)); } catch (e) { /* bridge missing */ }
  }

  function clean(s) {
    return (s || '').replace(/[\s   ]+/g, ' ').trim();
  }

  function txt(root, sel) {
    var el = root.querySelector(sel);
    if (!el) return '';
    if (el.tagName === 'META') return clean(el.getAttribute('content'));
    return clean(el.textContent);
  }

  // Ссылка на товар WB: /catalog/123456789/detail.aspx; в Ресейле возможны и другие пути
  // с числовым ID — берём последний числовой сегмент из 6–12 цифр.
  var LINK_SEL = 'a[href], [data-href], [data-url]';

  function hrefOf(el) {
    return (el && (el.getAttribute('href') || el.getAttribute('data-href') || el.getAttribute('data-url'))) || '';
  }

  function idFromHref(href) {
    if (!href) return null;
    var path = href.split('#')[0].split('?')[0];
    var m = path.match(/\/catalog\/(\d{5,12})(?:\/|$)/);
    if (m) return m[1];
    m = path.match(/\/(?:resale|product|item|items|lot)\/(?:[^/]*\/)*?(\d{6,12})(?:\/|$)/);
    if (m) return m[1];
    m = path.match(/(?:\/|_|-)(\d{7,12})(?:\/detail\.aspx)?\/?$/);
    return m ? m[1] : null;
  }

  function absUrl(href) {
    try {
      var u = new URL(href, location.href);
      if (!/(^|\.)(wildberries\.ru|wb\.ru)$/.test(u.hostname)) return null;
      return 'https://' + u.hostname + u.pathname;
    } catch (e) {
      return null;
    }
  }

  function itemIdsIn(el) {
    var set = {};
    var links = el.querySelectorAll(LINK_SEL);
    for (var i = 0; i < links.length; i++) {
      var id = idFromHref(hrefOf(links[i]));
      if (id) set[id] = true;
    }
    return Object.keys(set);
  }

  function nmIdOf(el) {
    var v = el.getAttribute('data-nm-id') || el.getAttribute('data-nm') || el.getAttribute('data-product-id') || '';
    if (/^\d{5,12}$/.test(v)) return v;
    var m = /^c(\d{5,12})$/.exec(el.id || '');
    return m ? m[1] : null;
  }

  // Поднимаемся от ссылки вверх, пока контейнер содержит только этот товар.
  function findCard(a, id) {
    var best = a;
    var el = a;
    for (var i = 0; i < 12 && el.parentElement && el.parentElement !== document.body; i++) {
      var p = el.parentElement;
      var pid = nmIdOf(p);
      if (pid && pid !== id) break;
      var ids = itemIdsIn(p);
      if (ids.length > 1 || (ids.length === 1 && ids[0] !== id)) break;
      best = p;
      el = p;
      if (pid === id) break;
      if (p.tagName === 'ARTICLE' || /(^|\s)product-card(\s|$)/.test(p.className || '')) break;
    }
    return best;
  }

  function insideOldPrice(el, card) {
    for (var n = el; n && n !== card; n = n.parentElement) {
      if (n.tagName === 'DEL' || n.tagName === 'S') return true;
      if (/old|del|before|crossed/i.test(n.className || '')) return true;
    }
    return false;
  }

  function priceOf(card) {
    // Текущая цена WB: <ins class="price__lower-price">; зачёркнутая старая — <del>
    var t = txt(card, '.price__lower-price') ||
            txt(card, '[class*="lower-price"]') ||
            txt(card, '[class*="price"] ins') ||
            txt(card, '[class*="price-current"], [class*="current-price"], [class*="final-price"]');
    if (!t) {
      var leaves = card.querySelectorAll('span, div, p, strong, b, ins');
      for (var i = 0; i < leaves.length && !t; i++) {
        if (leaves[i].children.length || insideOldPrice(leaves[i], card)) continue;
        var lt = clean(leaves[i].textContent);
        if (lt.length < 40 && /^(от\s)?\d[\d ]*\s?₽/.test(lt)) t = lt;
      }
    }
    if (!t) {
      var m = clean(card.innerText || card.textContent).match(/\d[\d ]*\s?₽/);
      if (m) t = clean(m[0]);
    }
    var m2 = t.match(/(от\s)?\d[\d ]*\s?₽/);
    return (m2 ? clean(m2[0]) : t).slice(0, 60);
  }

  function titleOf(card, link) {
    function first(sels) {
      for (var i = 0; i < sels.length; i++) { var v = txt(card, sels[i]); if (v) return v; }
      return '';
    }
    var brand = first(['.product-card__brand', '[class*="brand-name"]', '[class*="__brand"]:not([class*="wrap"])']);
    var name = first(['.product-card__name', '[class*="goods-name"]', '[class*="product-name"]',
      '[class*="card__name"]', '[class*="__title"]']).replace(/^\/\s*/, '');
    if (brand && name && name.indexOf(brand) === 0) brand = '';
    var t = '';
    if (brand && name) t = brand + ' / ' + name;
    else t = name || brand;
    if (!t && link) t = clean(link.getAttribute('aria-label') || link.getAttribute('title'));
    if (!t) {
      var img = card.querySelector('img[alt]');
      if (img && clean(img.getAttribute('alt')).length > 5) t = clean(img.getAttribute('alt'));
    }
    if (!t) {
      var leaves = card.querySelectorAll('span, div, p, h2, h3, h4, strong');
      for (var j = 0; j < leaves.length && !t; j++) {
        if (leaves[j].children.length) continue;
        var lt = clean(leaves[j].textContent);
        if (lt.length >= 6 && lt.indexOf('₽') < 0 && !/^[\d\s:.,%−-]+$/.test(lt)) t = lt;
      }
    }
    return t.slice(0, 200);
  }

  function imageOf(card) {
    var best = '';
    var maxW = -1;
    var nodes = card.querySelectorAll('img, source');
    for (var i = 0; i < nodes.length && !best; i++) {
      var ss = nodes[i].getAttribute('srcset') || nodes[i].getAttribute('data-srcset') || '';
      if (ss) {
        ss.split(',').forEach(function (part) {
          var p = part.trim().split(/\s+/);
          var w = parseInt((p[1] || '').replace(/\D/g, ''), 10) || 0;
          if (p[0] && w > maxW) { maxW = w; best = p[0]; }
        });
      }
      if (!best && nodes[i].tagName === 'IMG') {
        best = nodes[i].currentSrc || nodes[i].getAttribute('src') || nodes[i].getAttribute('data-src') || '';
      }
    }
    if (!best || best.indexOf('data:') === 0) return '';
    try { return new URL(best, location.href).href; } catch (e) { return ''; }
  }

  function extract(id, card, link) {
    var title = titleOf(card, link);
    var price = priceOf(card);
    var geo = txt(card, '[class*="city"], [class*="location"], [class*="address"], [class*="geo"]');
    var params = txt(card, '[class*="condition"], [class*="state"], [class*="size"], [class*="params"]');
    var desc = txt(card, '[class*="description"], [class*="desc"]');
    if (!desc) {
      var full = clean(card.innerText || card.textContent);
      [price, geo, params].concat(title.split(' / ')).forEach(function (p) {
        if (p) full = full.split(p).join(' ');
      });
      // убираем цены (в т.ч. зачёркнутую старую) и одиночные слэши
      desc = clean(full.replace(/(от\s)?\d[\d ]*\s?₽/g, ' ').replace(/(^|\s)[\/|·•]+(?=\s|$)/g, ' '));
    }
    var url = (link && absUrl(hrefOf(link))) || ('https://www.wildberries.ru/catalog/' + id + '/detail.aspx');
    return {
      id: id,
      title: title,
      price: price,
      url: url,
      description: desc.slice(0, 600),
      geo: geo.slice(0, 150),
      params: params.slice(0, 300),
      image: imageOf(card)
    };
  }

  try {
    var cards = {};
    var order = [];

    function add(id, card, link) {
      if (cards[id]) return;
      if (!link) {
        var ls = card.querySelectorAll(LINK_SEL);
        for (var i = 0; i < ls.length; i++) {
          if (idFromHref(hrefOf(ls[i])) === id) { link = ls[i]; break; }
        }
      }
      cards[id] = { card: card, link: link };
      order.push(id);
    }

    // 1) Карточки WB с явным ID: <article data-nm-id="…" id="c…">
    var withId = document.querySelectorAll('[data-nm-id], [data-nm], [data-product-id], article[id^="c"]');
    for (var i = 0; i < withId.length; i++) {
      var nid = nmIdOf(withId[i]);
      if (nid) add(nid, withId[i], null);
    }

    // 2) Любая вёрстка: ссылки на товар
    var anchors = document.querySelectorAll(LINK_SEL);
    for (var j = 0; j < anchors.length; j++) {
      var a = anchors[j];
      var aid = idFromHref(hrefOf(a));
      if (!aid || cards[aid]) continue;
      add(aid, findCard(a, aid), a);
    }

    // Запоминаем элементы карточек (невидимое свойство) — для скриншота конкретной карточки
    try {
      var map = {};
      for (var q = 0; q < order.length; q++) map[order[q]] = cards[order[q]].card;
      Object.defineProperty(window, '__amc', { value: map, configurable: true, writable: true, enumerable: false });
    } catch (e) { /* не критично */ }

    var items = [];
    for (var k = 0; k < order.length && items.length < 120; k++) {
      var c = cards[order[k]];
      items.push(extract(order[k], c.card, c.link));
    }

    // Детектор капчи / антибот-проверки WB
    var body = document.body;
    var bodyText = clean(body ? (body.innerText || body.textContent) : '').slice(0, 6000);
    var hay = ((document.title || '') + ' ' + bodyText).toLowerCase();
    var markers = [
      'почти готово',
      'подтвердите, что вы не робот',
      'подтвердите, что вы человек',
      'проверка браузера',
      'проверяем браузер',
      'я не робот',
      'вы не робот',
      'доступ ограничен',
      'access denied',
      'are you a robot'
    ];
    var hit = '';
    for (var mi = 0; mi < markers.length; mi++) {
      if (hay.indexOf(markers[mi]) >= 0) { hit = markers[mi]; break; }
    }
    if (!hit && document.querySelector(
        'iframe[src*="captcha"], [class*="captcha"], #captcha, form[action*="captcha"], [class*="antibot"]')) {
      hit = 'элемент капчи на странице';
    }
    var captcha = hit !== '' && items.length === 0;

    // Диагностика: если карточек нет — описываем структуру страницы, чтобы подстроить парсер
    var diag = null;
    if (items.length === 0 && !captcha) {
      try {
        var links = document.querySelectorAll(LINK_SEL);
        var hrefs = [];
        var seenH = {};
        for (var h = 0; h < links.length && hrefs.length < 40; h++) {
          var hv = hrefOf(links[h]).split('#')[0].slice(0, 160);
          if (hv && !seenH[hv]) { seenH[hv] = 1; hrefs.push(hv); }
        }
        // Частые классы — по ним видно, как названы карточки
        var cls = {};
        var allEls = document.querySelectorAll('[class]');
        for (var w = 0; w < allEls.length && w < 6000; w++) {
          var cn = (allEls[w].className && allEls[w].className.baseVal !== undefined)
            ? allEls[w].className.baseVal : allEls[w].className;
          String(cn || '').split(/\s+/).forEach(function (x) { if (x) cls[x] = (cls[x] || 0) + 1; });
        }
        var classesTop = Object.keys(cls).sort(function (x, y) { return cls[y] - cls[x]; }).slice(0, 40)
          .map(function (x) { return x + ' ×' + cls[x]; });
        var snippet = '';
        var all = document.querySelectorAll('span, div, p, strong, b, ins');
        for (var z = 0; z < all.length; z++) {
          var el = all[z];
          if (el.children.length === 0 && /\d[\d\s]*\s?₽/.test(el.textContent || '')) {
            var box = el;
            for (var up = 0; up < 7 && box.parentElement && box.parentElement !== body; up++) box = box.parentElement;
            snippet = box.outerHTML.slice(0, 6000);
            break;
          }
        }
        diag = {
          anchors: document.querySelectorAll('a').length,
          linksWithHref: links.length,
          dataMarkers: document.querySelectorAll('[data-nm-id]').length,
          itemIdAttrs: document.querySelectorAll('article').length,
          bodyTextLength: bodyText.length,
          hrefs: hrefs,
          markers: classesTop,
          snippet: snippet
        };
      } catch (e) {
        diag = { error: String(e) };
      }
    }

    send({
      ok: true,
      captcha: captcha,
      reason: hit,
      items: captcha ? [] : items,
      title: document.title || '',
      url: location.href,
      diag: diag
    });
  } catch (e) {
    send({ ok: false, error: String((e && e.message) || e) });
  }
})
