(function () {
  if (window.__TK_NATIVE_REFERENCE_HOOK__) return;
  window.__TK_NATIVE_REFERENCE_HOOK__ = true;

  // V45.3.6: TikTok's current web-app promotion gate is keyed by this session value.
  // Set only the known promotion preference; do not scan text or click generic dialogs.
  try { sessionStorage.setItem('webapp_first_open_cta', '1'); } catch (_) {}

  function closest(el, selector) {
    try { return el && el.closest ? el.closest(selector) : null; }
    catch (_) { return null; }
  }

  function visibleCommentSheetNode() {
    var nodes;
    try { nodes = document.querySelectorAll('[data-e2e="search-comment-container"],[data-e2e="comment-list"]'); }
    catch (_) { return null; }
    for (var i = 0; i < nodes.length; i++) {
      try {
        var r = nodes[i].getBoundingClientRect(), s = getComputedStyle(nodes[i]);
        var o = parseFloat(s.opacity || '1');
        if (r.width > 40 && r.height > 40 && r.bottom > 0 && r.top < innerHeight &&
            s.display !== 'none' && s.visibility !== 'hidden' && s.pointerEvents !== 'none' &&
            (!isFinite(o) || o > 0.05)) return nodes[i];
      } catch (_) {}
    }
    return null;
  }

  function hasVisibleCommentSheet() { return !!visibleCommentSheetNode(); }

  function closeBelongsToVisibleCommentSheet(el) {
    var sheet = visibleCommentSheetNode();
    if (!sheet || !el) return false;
    try {
      if (sheet.contains(el)) return true;
      var p = sheet.parentElement;
      if (p && p.contains(el)) return true;
      var pp = p && p.parentElement;
      return !!(pp && pp !== document.body && pp.contains(el));
    } catch (_) { return false; }
  }

  // V45.2: no feed scanner, no seen-video auto-skip, no continuous playback controller.
  // TikTok owns the confirmed-good V44.0.3 home feed.

  document.addEventListener('click', function (event) {
    var target = event.target;
    var button = closest(target, 'button,[role="button"]');

    var searchOpen = closest(target, 'button[data-e2e="search-button"],[data-e2e="search-icon"]');
    if (searchOpen) {
      try { NativeShell.searchOpened(); } catch (_) {}
      return;
    }

    var searchClose = closest(target, '[data-e2e="search-close"],button[aria-label="Close search"],button[aria-label="关闭搜索"]');
    if (searchClose) {
      try { NativeShell.searchClosed(); } catch (_) {}
      return;
    }

    var commentOpen = closest(target,
      'button[data-e2e="comment-icon"],button[data-e2e="comment-button"],[data-e2e="comment-icon"],[data-e2e="comment-button"],button[aria-label="Comment"],button[aria-label="评论"]');
    if (!commentOpen && button && button.querySelector) {
      try { commentOpen = button.querySelector('[data-e2e="comment-icon"],[data-e2e="comment-button"]'); } catch (_) {}
    }
    if (commentOpen) {
      try { NativeShell.commentOpened(); } catch (_) {}
      return;
    }

    var close = closest(target,
      '[data-e2e="browse-close"],button[aria-label="exit"],button[aria-label="Close"],button[aria-label="关闭"]');
    if (close && closeBelongsToVisibleCommentSheet(close)) {
      try { NativeShell.commentClosed(); } catch (_) {}
      return;
    }

    var profileLink = closest(target, 'a[href]');
    if (profileLink && !closest(target, 'a[data-e2e="nav-profile"]')) {
      try {
        var pu = new URL(String(profileLink.getAttribute('href') || profileLink.href || ''), location.href);
        var ph = String(pu.hostname || '').toLowerCase();
        var pp = String(pu.pathname || '').toLowerCase();
        var trustedProfileHost = ph === 'tiktok.com' || /\.tiktok\.com$/.test(ph);
        /* Only a bare /@user profile route changes profile ownership. Post/photo/live
           anchors also start with /@ but must not clear ownProfileRoute. */
        var currentProfilePath = String(location.pathname || '').toLowerCase();
        if (trustedProfileHost && /^\/@[^/]+\/?$/.test(pp) && pp.replace(/\/$/, '') !== currentProfilePath.replace(/\/$/, '')) {
          NativeShell.externalProfileOpened();
        }
      } catch (_) {}
    }
  }, true);

  // V45.2: LIVE stays on TikTok's real mobile page. We never consume the gesture.
  // If TikTok's own mobile page already switched rooms, the fingerprint changes and
  // the fallback does nothing. Otherwise we only scroll the real live scroller by one viewport.
  function isLivePath() {
    try {
      var p = String(location.pathname || '').toLowerCase();
      return p === '/live' || p === '/live/' || /^\/@[^/]+\/live(?:\/[0-9]+)?\/?$/.test(p) || /^\/live\/[0-9]+\/?$/.test(p);
    } catch (_) { return false; }
  }

  function largestVisibleVideo() {
    var list;
    try { list = document.querySelectorAll('video'); } catch (_) { return null; }
    var best = null, bestArea = 0;
    var vw = Math.max(1, innerWidth || 1), vh = Math.max(1, innerHeight || 1);
    for (var i = 0; i < list.length; i++) {
      var v = list[i], r, st, op;
      try {
        r = v.getBoundingClientRect();
        st = getComputedStyle(v);
        op = parseFloat(st.opacity || '1');
        if (st.display === 'none' || st.visibility === 'hidden' || (isFinite(op) && op <= 0.05)) continue;
      } catch (_) { continue; }
      var w = Math.max(0, Math.min(r.right, vw) - Math.max(r.left, 0));
      var h = Math.max(0, Math.min(r.bottom, vh) - Math.max(r.top, 0));
      var a = w * h;
      if (a > bestArea) { bestArea = a; best = v; }
    }
    return best;
  }

  function liveFingerprint() {
    try {
      var v = largestVisibleVideo();
      var src = v ? String(v.currentSrc || v.src || '') : '';
      var room = '';
      var a = v && closest(v, 'a[href*="/live/"],a[href$="/live"]');
      if (!a) a = document.querySelector('a[href*="/live/"][aria-current="page"],a[href$="/live"][aria-current="page"]');
      if (a) room = String(a.href || a.getAttribute('href') || '');
      return String(location.pathname || '') + '|' + room + '|' + src.split('?')[0].slice(-180);
    } catch (_) { return String(location.pathname || ''); }
  }

  function findLiveScroller(seed) {
    var p = seed && seed.parentElement;
    while (p && p !== document.body && p !== document.documentElement) {
      try {
        var st = getComputedStyle(p);
        var oy = String(st.overflowY || '');
        if ((oy === 'auto' || oy === 'scroll') && p.scrollHeight > p.clientHeight + 80) return p;
      } catch (_) {}
      p = p.parentElement;
    }
    // No document-wide LIVE class scan: if the video's ancestor chain does not expose
    // a real vertical scroller, fall back to TikTok's page scroller only.
    return document.scrollingElement || document.documentElement;
  }

  // V45.3.5: do not scan/click generic "next" buttons. Product carousels,
  // dialogs and comment controls can expose the same labels. TikTok gets first chance;
  // the only fallback is scrolling the real LIVE scroller after the room stayed unchanged.

  window.__TK_NATIVE_LIVE_SWIPE__ = function (dir, before) {
    if (!isLivePath()) return 'not-live';
    var now = liveFingerprint();
    if (before && now && before !== now) return 'already-switched';
    var v = largestVisibleVideo();
    var scroller = findLiveScroller(v);
    var delta = Math.max(innerHeight || 0, 480) * (dir === 'next' ? 0.94 : -0.94);
    try {
      if (scroller && scroller.scrollBy) scroller.scrollBy({ top: delta, behavior: 'smooth' });
      else window.scrollBy({ top: delta, behavior: 'smooth' });
      return 'scrolled';
    } catch (_) { return 'miss'; }
  };

  function liveInteractiveTarget(el) {
    try {
      return !!(el && el.closest && el.closest('a,button,input,textarea,select,[role="button"],[contenteditable="true"]'));
    } catch (_) { return false; }
  }

  var liveTouchY = 0, liveTouchX = 0, liveTouchAt = 0, liveTouchFp = '', liveTouchArmed = false;
  try {
    document.addEventListener('touchstart', function (e) {
      liveTouchArmed = false;
      liveTouchAt = 0;
      liveTouchFp = '';
      if (!isLivePath() || !e.touches || e.touches.length !== 1 || liveInteractiveTarget(e.target)) return;
      var t = e.touches[0];
      liveTouchX = t.clientX; liveTouchY = t.clientY; liveTouchAt = Date.now(); liveTouchFp = liveFingerprint();
      liveTouchArmed = true;
    }, { passive: true, capture: true });
    document.addEventListener('touchend', function (e) {
      if (!liveTouchArmed) return;
      liveTouchArmed = false;
      if (!isLivePath() || !e.changedTouches || e.changedTouches.length !== 1 || liveInteractiveTarget(e.target)) return;
      var t = e.changedTouches[0];
      var dx = t.clientX - liveTouchX, dy = t.clientY - liveTouchY;
      var age = Date.now() - liveTouchAt;
      if (age <= 0 || age > 1400 || Math.abs(dy) < 80 || Math.abs(dy) < Math.abs(dx) * 1.25) return;
      var dir = dy < 0 ? 'next' : 'prev';
      var before = liveTouchFp;
      setTimeout(function () {
        if (!isLivePath()) return;
        if (before && liveFingerprint() !== before) return;
        try { window.__TK_NATIVE_LIVE_SWIPE__(dir, before); } catch (_) {}
      }, 360);
    }, { passive: true, capture: true });
    document.addEventListener('touchcancel', function () {
      liveTouchArmed = false; liveTouchAt = 0; liveTouchFp = '';
    }, { passive: true, capture: true });
  } catch (_) {}


  // V45.3: isolated feed gestures only. This layer never scans/rewrites the Feed,
  // never changes UA/navigation, and never removes TikTok DOM. Ordinary vertical
  // swipes remain owned by TikTok. We only expose a thin white seek line and a
  // press-and-hold 2x speed gesture on /foryou.
  if (!window.__TK_NATIVE_FEED_GESTURES_453__) {
    window.__TK_NATIVE_FEED_GESTURES_453__ = true;

    var holdTimer453 = 0;
    var holdVideo453 = null;
    var holdRate453 = 1;
    var holdX453 = 0;
    var holdY453 = 0;
    var seek453 = null;

    function isForYou453() {
      try {
        var p = String(location.pathname || '').toLowerCase();
        return p === '' || p === '/' || p === '/foryou' || p.indexOf('/foryou/') === 0;
      } catch (_) { return false; }
    }

    function interactive453(el) {
      try {
        return !!(el && el.closest && el.closest('a,button,input,textarea,select,[role="button"],[contenteditable="true"]'));
      } catch (_) { return false; }
    }

    function visibleCommentSheet453() {
      var nodes;
      try { nodes = document.querySelectorAll('[data-e2e="search-comment-container"],[data-e2e="comment-list"]'); }
      catch (_) { return false; }
      for (var i = 0; i < nodes.length; i++) {
        try {
          var r = nodes[i].getBoundingClientRect();
          var st = getComputedStyle(nodes[i]);
          var op = parseFloat(st.opacity || '1');
          if (r.width > 40 && r.height > 40 && r.bottom > 0 && r.top < innerHeight &&
              st.display !== 'none' && st.visibility !== 'hidden' && st.pointerEvents !== 'none' &&
              (!isFinite(op) || op > 0.05)) return true;
        } catch (_) {}
      }
      return false;
    }

    function ensureGestureUi453() {
      var host = document.body || document.documentElement;
      if (!host) return;
      if (!document.getElementById('tk-feed-progress-453')) {
        var progress = document.createElement('div');
        progress.id = 'tk-feed-progress-453';
        var track = document.createElement('div');
        track.id = 'tk-feed-progress-track-453';
        var fill = document.createElement('div');
        fill.id = 'tk-feed-progress-fill-453';
        track.appendChild(fill);
        progress.appendChild(track);
        host.appendChild(progress);
      }
      if (!document.getElementById('tk-speed-badge-453')) {
        var badge = document.createElement('div');
        badge.id = 'tk-speed-badge-453';
        badge.textContent = '2×';
        host.appendChild(badge);
      }
    }

    function currentFeedVideo453() {
      if (!isForYou453()) return null;
      var v = largestVisibleVideo();
      return visibleEnough453(v) ? v : null;
    }

    function hideProgress453() {
      var host = document.getElementById('tk-feed-progress-453');
      var fill = document.getElementById('tk-feed-progress-fill-453');
      if (host) host.classList.remove('tk-ready', 'tk-seeking');
      if (fill) fill.style.width = '0%';
    }

    function syncProgress453() {
      if (!isForYou453()) { hideProgress453(); return; }
      var v = currentFeedVideo453();
      if (v) updateProgress453(v);
      else hideProgress453();
    }

    function updateProgress453(video) {
      if (!isForYou453() || !video) return;
      var duration = Number(video.duration);
      var current = Number(video.currentTime);
      if (!isFinite(duration) || duration <= 0 || !isFinite(current)) { hideProgress453(); return; }
      ensureGestureUi453();
      var host = document.getElementById('tk-feed-progress-453');
      var fill = document.getElementById('tk-feed-progress-fill-453');
      if (!host || !fill) return;
      var ratio = Math.max(0, Math.min(1, current / duration));
      fill.style.width = (ratio * 100) + '%';
      host.classList.add('tk-ready');
    }

    function visibleEnough453(video) {
      if (!video) return false;
      try {
        var r = video.getBoundingClientRect();
        var st = getComputedStyle(video), op = parseFloat(st.opacity || '1');
        if (st.display === 'none' || st.visibility === 'hidden' || (isFinite(op) && op <= 0.05)) return false;
        var cx = innerWidth * 0.5, cy = innerHeight * 0.5;
        return r.width > 80 && r.height > 80 && cx >= r.left && cx <= r.right && cy >= r.top && cy <= r.bottom;
      } catch (_) { return false; }
    }

    function mediaProgress453(e) {
      var v = e && e.target;
      if (!v || String(v.tagName || '').toUpperCase() !== 'VIDEO' || !visibleEnough453(v)) return;
      updateProgress453(v);
    }

    function restoreHold453() {
      if (holdTimer453) {
        clearTimeout(holdTimer453);
        holdTimer453 = 0;
      }
      if (holdVideo453) {
        try { holdVideo453.playbackRate = holdRate453 || 1; } catch (_) {}
        holdVideo453 = null;
      }
      var badge = document.getElementById('tk-speed-badge-453');
      if (badge) badge.classList.remove('tk-show');
    }

    function seekTo453(video, clientX) {
      if (!video) return;
      var duration = Number(video.duration);
      if (!isFinite(duration) || duration <= 0) return;
      var left = 12, width = Math.max(1, innerWidth - 24);
      var ratio = Math.max(0, Math.min(1, (clientX - left) / width));
      try { video.currentTime = duration * ratio; } catch (_) {}
      updateProgress453(video);
    }

    document.addEventListener('timeupdate', mediaProgress453, true);
    document.addEventListener('durationchange', mediaProgress453, true);
    document.addEventListener('loadedmetadata', mediaProgress453, true);

    document.addEventListener('touchstart', function (e) {
      if (!isForYou453() || !e.touches || e.touches.length !== 1) return;
      restoreHold453();
      seek453 = null;
      if (visibleCommentSheet453()) return;
      var t = e.touches[0];
      var video = currentFeedVideo453();
      if (!video) return;
      ensureGestureUi453();
      updateProgress453(video);

      holdX453 = t.clientX;
      holdY453 = t.clientY;
      if (interactive453(e.target)) return;

      // Native bottom bar is ~76dp high. Seeking lives immediately above it,
      // so normal feed swipes and bottom-navigation taps are not stolen.
      var seekZone = t.clientY >= innerHeight - 132 && t.clientY <= innerHeight - 78;
      if (seekZone && isFinite(Number(video.duration)) && Number(video.duration) > 0) {
        seek453 = { video: video, startX: t.clientX, startY: t.clientY, active: false };
        return;
      }

      holdVideo453 = video;
      holdRate453 = Number(video.playbackRate) || 1;
      holdTimer453 = setTimeout(function () {
        holdTimer453 = 0;
        if (!holdVideo453) return;
        try { holdVideo453.playbackRate = 2; } catch (_) {}
        var badge = document.getElementById('tk-speed-badge-453');
        if (badge) badge.classList.add('tk-show');
      }, 430);
    }, { passive: true, capture: true });

    document.addEventListener('touchmove', function (e) {
      if (!isForYou453() || !e.touches || e.touches.length !== 1) return;
      var t = e.touches[0];
      var dx = t.clientX - holdX453;
      var dy = t.clientY - holdY453;
      if (Math.abs(dx) > 14 || Math.abs(dy) > 14) restoreHold453();

      if (!seek453) return;
      var sdx = t.clientX - seek453.startX;
      var sdy = t.clientY - seek453.startY;
      if (!seek453.active) {
        if (Math.abs(sdx) < 10 || Math.abs(sdx) <= Math.abs(sdy) * 1.25) return;
        seek453.active = true;
        var progress = document.getElementById('tk-feed-progress-453');
        if (progress) progress.classList.add('tk-seeking');
      }
      try { e.preventDefault(); e.stopPropagation(); } catch (_) {}
      seekTo453(seek453.video, t.clientX);
    }, { passive: false, capture: true });

    function finishGesture453(e) {
      restoreHold453();
      if (seek453 && seek453.active && e && e.changedTouches && e.changedTouches.length === 1) {
        seekTo453(seek453.video, e.changedTouches[0].clientX);
      }
      seek453 = null;
      var progress = document.getElementById('tk-feed-progress-453');
      if (progress) progress.classList.remove('tk-seeking');
    }

    document.addEventListener('touchend', function (e) {
      finishGesture453(e);
      setTimeout(syncProgress453, 90);
      setTimeout(syncProgress453, 460);
    }, { passive: true, capture: true });
    document.addEventListener('touchcancel', function (e) {
      finishGesture453(e);
      setTimeout(syncProgress453, 90);
      setTimeout(syncProgress453, 460);
    }, { passive: true, capture: true });
    window.addEventListener('pagehide', restoreHold453, true);
    document.addEventListener('visibilitychange', function () {
      if (document.hidden) restoreHold453();
    }, true);
  }


  // V45.3.12: preserve the approved portrait fullscreen crop, but do not crop
  // source video wider than TikTok's native 9:16 feed canvas into a portrait viewport. TikTok's own video element
  // remains the player; this only tags its intrinsic aspect after metadata exists.
  // No observer, feed scan, playback command or scroll ownership is introduced.
  function isFeedPath45312() {
    try {
      var p = String(location.pathname || '').toLowerCase().replace(/\/+$/, '');
      return p === '' || p === '/foryou' || p === '/following' || p === '/friends';
    } catch (_) { return false; }
  }

  function syncVideoAspect45312(video) {
    if (!isFeedPath45312() || !video || String(video.tagName || '').toUpperCase() !== 'VIDEO') return;
    try {
      if (!closest(video, 'article[data-e2e="recommend-list-item-container"]')) return;
      var w = Number(video.videoWidth), h = Number(video.videoHeight);
      if (!(w > 0 && h > 0)) return;
      var ratio = w / h;
      var contain = ratio > (9 / 16 + 0.01);
      video.classList.toggle('tk-native-media-contain-45312', contain);
      video.classList.toggle('tk-native-media-cover-45312', !contain);
    } catch (_) {}
  }

  function syncVisibleVideoAspect45312() {
    if (!isFeedPath45312()) return;
    try { syncVideoAspect45312(largestVisibleVideo()); } catch (_) {}
  }

  document.addEventListener('loadedmetadata', function (e) { syncVideoAspect45312(e && e.target); }, true);
  document.addEventListener('play', function (e) { syncVideoAspect45312(e && e.target); }, true);
  window.addEventListener('resize', syncVisibleVideoAspect45312, true);
  // Two bounded startup passes cover the already-playing first card without adding
  // per-swipe scanning. Later cards are tagged by their normal media lifecycle events.
  setTimeout(syncVisibleVideoAspect45312, 60);
  setTimeout(syncVisibleVideoAspect45312, 520);


  // V45.3.14: reduce exact For You repeats across launches without replacing
  // TikTok recommendation or installing a persistent feed scanner. The native bridge
  // remembers only numeric post IDs for 72 hours. A recent exact repeat gets at most
  // two bounded one-card advances in a row; similar-but-different posts are untouched.
  var repeatLastChecked45314 = '', repeatLastCheckAt45314 = 0;
  var repeatSkipChain45314 = 0, repeatSkipCooldownUntil45314 = 0;

  function isForYouFresh45314() {
    try {
      var p = String(location.pathname || '').toLowerCase().replace(/\/+$/, '');
      return p === '' || p === '/foryou';
    } catch (_) { return false; }
  }

  function centeredFeedVideo45314() {
    if (!isForYouFresh45314()) return null;
    var v = largestVisibleVideo();
    if (!v) return null;
    try {
      var r = v.getBoundingClientRect(), st = getComputedStyle(v), op = parseFloat(st.opacity || '1');
      var cx = Math.max(1, innerWidth || 1) * 0.5, cy = Math.max(1, innerHeight || 1) * 0.5;
      if (st.display === 'none' || st.visibility === 'hidden' || (isFinite(op) && op <= 0.05)) return null;
      if (r.width <= 80 || r.height <= 80 || cx < r.left || cx > r.right || cy < r.top || cy > r.bottom) return null;
      return v;
    } catch (_) { return null; }
  }

  function feedPost45314(video) {
    try {
      var card = closest(video, 'article[data-e2e="recommend-list-item-container"]');
      if (!card) return null;
      var a = card.querySelector('a[href*="/video/"],a[href*="/photo/"]');
      if (!a) return null;
      var u = new URL(String(a.getAttribute('href') || a.href || ''), location.href);
      var h = String(u.hostname || '').toLowerCase();
      if (!(h === 'tiktok.com' || /\.tiktok\.com$/.test(h))) return null;
      var m = String(u.pathname || '').match(/\/(?:video|photo)\/([0-9]{8,24})(?:\/|$)/i);
      return m ? { id: m[1], card: card, video: video } : null;
    } catch (_) { return null; }
  }

  function feedScroller45314(seed) {
    var p = seed && seed.parentElement;
    while (p && p !== document.body && p !== document.documentElement) {
      try {
        var st = getComputedStyle(p), oy = String(st.overflowY || '');
        if ((oy === 'auto' || oy === 'scroll') && p.scrollHeight > p.clientHeight + 80) return p;
      } catch (_) {}
      p = p.parentElement;
    }
    return document.scrollingElement || document.documentElement;
  }

  function maybeAdvanceRecentRepeat45314() {
    if (!isForYouFresh45314() || Date.now() < repeatSkipCooldownUntil45314) return;
    if (document.documentElement && document.documentElement.classList.contains('tk-native-comment-open')) return;
    var video = centeredFeedVideo45314(), post = feedPost45314(video);
    if (!post || !window.NativeShell || typeof NativeShell.recentFeedPostRepeated !== 'function') return;
    var now = Date.now();
    if (post.id === repeatLastChecked45314 && now - repeatLastCheckAt45314 < 1100) return;
    repeatLastChecked45314 = post.id;
    repeatLastCheckAt45314 = now;
    var repeated = false;
    try { repeated = !!NativeShell.recentFeedPostRepeated(post.id); } catch (_) { return; }
    if (!repeated) { repeatSkipChain45314 = 0; return; }
    if (repeatSkipChain45314 >= 2) return;
    repeatSkipChain45314++;
    repeatSkipCooldownUntil45314 = now + 900;
    try {
      var scroller = feedScroller45314(post.card);
      var delta = Math.max(Number(innerHeight) || 0, post.card.getBoundingClientRect().height || 0, 480) * 0.98;
      if (scroller && scroller.scrollBy) scroller.scrollBy({ top: delta, behavior: 'smooth' });
      else window.scrollBy({ top: delta, behavior: 'smooth' });
      setTimeout(maybeAdvanceRecentRepeat45314, 760);
    } catch (_) {}
  }

  document.addEventListener('play', function (e) {
    var v = e && e.target;
    if (!v || String(v.tagName || '').toUpperCase() !== 'VIDEO') return;
    setTimeout(maybeAdvanceRecentRepeat45314, 240);
  }, true);
  document.addEventListener('touchend', function () {
    setTimeout(maybeAdvanceRecentRepeat45314, 520);
  }, { passive: true, capture: true });

})();
