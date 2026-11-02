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

  // Ссылка на объявление Avito оканчивается на _<ID>: /moskva/telefony/iphone_15_1234567890
  var ID_RE = /_(\d{6,})\/?$/;

  function idFromHref(href) {
    if (!href) return null;
    var path = href.split('#')[0].split('?')[0];
    var m = path.match(ID_RE);
    return m ? m[1] : null;
  }

  function absUrl(href) {
    try {
      var u = new URL(href, location.href);
      if (!/(^|\.)avito\.ru$/.test(u.hostname)) return null;
      return 'https://www.avito.ru' + u.pathname;
    } catch (e) {
      return null;
    }
  }

  function itemIdsIn(el) {
    var set = {};
    var links = el.querySelectorAll('a[href]');
    for (var i = 0; i < links.length; i++) {
      var id = idFromHref(links[i].getAttribute('href'));
      if (id) set[id] = true;
    }
    return Object.keys(set);
  }

  // Поднимаемся от ссылки вверх, пока контейнер содержит только это объявление.
  function findCard(a, id) {
    var best = a;
    var el = a;
    for (var i = 0; i < 12 && el.parentElement && el.parentElement !== document.body; i++) {
      var p = el.parentElement;
      var pid = p.getAttribute('data-item-id');
      if (pid && pid !== id) break;
      var ids = itemIdsIn(p);
      if (ids.length > 1 || (ids.length === 1 && ids[0] !== id)) break;
      best = p;
      el = p;
      if (pid === id) break;
      var dm = p.getAttribute('data-marker') || '';
      if (dm === 'item' || /^item\//.test(dm)) break;
    }
    return best;
  }

  function titleOf(card, link) {
    var t = txt(card, '[data-marker="item-title"]') || txt(card, '[itemprop="name"]');
    if (!t && link) t = clean(link.getAttribute('title'));
    if (!t) t = txt(card, 'h3') || txt(card, 'h2');
    if (!t) {
      var links = card.querySelectorAll('a[href]');
      var longest = '';
      for (var i = 0; i < links.length; i++) {
        if (!idFromHref(links[i].getAttribute('href'))) continue;
        var s = clean(links[i].textContent);
        if (s.length > longest.length && s.length < 200) longest = s;
      }
      t = longest;
    }
    return t.slice(0, 200);
  }

  function priceOf(card) {
    var meta = card.querySelector('meta[itemprop="price"]');
    var metaVal = meta ? clean(meta.getAttribute('content')) : '';
    var t = txt(card, '[data-marker="item-price"]') ||
            txt(card, '[data-marker*="price"]') ||
            txt(card, '[itemprop="price"]') ||
            txt(card, '[class*="price-price"]');
    if (!t) {
      var m = clean(card.innerText || card.textContent).match(/\d[\d ]*\s?₽/);
      if (m) t = clean(m[0]);
    }
    if (!t && metaVal) t = metaVal + ' ₽';
    return t.slice(0, 60);
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
    var geo = txt(card, '[data-marker="item-address"]') ||
              txt(card, '[data-marker*="address"]') ||
              txt(card, '[data-marker*="geo"]') ||
              txt(card, '[class*="geo-root"]');
    var params = txt(card, '[data-marker="item-specific-params"]') ||
                 txt(card, '[data-marker*="params"]');
    var desc = txt(card, 'meta[itemprop="description"]') ||
               txt(card, '[data-marker*="description"]') ||
               txt(card, '[class*="description"]');
    if (!desc) {
      // Мобильная вёрстка: берём весь текст карточки за вычетом уже известных полей.
      var full = clean(card.innerText || card.textContent);
      [title, price, geo, params].forEach(function (p) {
        if (p) full = full.split(p).join(' ');
      });
      desc = clean(full);
    }
    var url = (link && absUrl(link.getAttribute('href'))) || ('https://www.avito.ru/' + id);
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
        var ls = card.querySelectorAll('a[href]');
        for (var i = 0; i < ls.length; i++) {
          if (idFromHref(ls[i].getAttribute('href')) === id) { link = ls[i]; break; }
        }
      }
      cards[id] = { card: card, link: link };
      order.push(id);
    }

    // 1) Десктопная/планшетная вёрстка: явные контейнеры с data-item-id
    var withId = document.querySelectorAll('[data-item-id]');
    for (var i = 0; i < withId.length; i++) {
      var did = withId[i].getAttribute('data-item-id');
      if (/^\d{6,}$/.test(did || '')) add(did, withId[i], null);
    }

    // 2) Любая вёрстка (в т.ч. m.avito.ru): ссылки вида ..._<ID>
    var anchors = document.querySelectorAll('a[href]');
    for (var j = 0; j < anchors.length; j++) {
      var a = anchors[j];
      var aid = idFromHref(a.getAttribute('href'));
      if (!aid || cards[aid]) continue;
      add(aid, findCard(a, aid), a);
    }

    var items = [];
    for (var k = 0; k < order.length && items.length < 120; k++) {
      var c = cards[order[k]];
      items.push(extract(order[k], c.card, c.link));
    }

    // Детектор капчи / блокировки
    var body = document.body;
    var bodyText = clean(body ? (body.innerText || body.textContent) : '').slice(0, 6000);
    var hay = ((document.title || '') + ' ' + bodyText).toLowerCase();
    var markers = [
      'доступ ограничен',
      'проблема с ip',
      'подтвердите, что вы не робот',
      'я не робот',
      'вы не робот',
      'доступ временно ограничен',
      'access denied',
      'are you a robot'
    ];
    var hit = '';
    for (var m = 0; m < markers.length; m++) {
      if (hay.indexOf(markers[m]) >= 0) { hit = markers[m]; break; }
    }
    if (!hit && document.querySelector(
        'iframe[src*="captcha"], [data-marker*="captcha"], #captcha, form[action*="captcha"], .firewall-container')) {
      hit = 'элемент капчи на странице';
    }
    var captcha = hit !== '' && items.length === 0;

    send({
      ok: true,
      captcha: captcha,
      reason: hit,
      items: captcha ? [] : items,
      title: document.title || '',
      url: location.href
    });
  } catch (e) {
    send({ ok: false, error: String((e && e.message) || e) });
  }
})
