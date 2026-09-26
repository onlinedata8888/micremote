import re, sys
src = open('patch/My_Remote_Design_v11_17.html', encoding='utf-8').read()
orig = src

def rep(old, new, count=1):
    global src
    assert src.count(old) == count, (old[:70], src.count(old))
    src = src.replace(old, new)

# ---------------------------------------------------------------- R1: native helpers at the top
rep("""  var phoneScale = 1;
""", """  var phoneScale = 1;

  // ===== Real Google TV (Android TV Remote v2) — native side is `TVNative` (built into the APK) =====
  // When this page is opened in a normal browser TVNative is missing, so TV = null and the old mock behaviour stays.
  var TV = window.TVNative || null;
  var K = { up:19, down:20, left:21, right:22, ok:23, back:4, home:3, recent:187, power:26,
            volUp:24, volDown:25, mute:164, search:84, settings:176, enter:66, del:67,
            playPause:85, next:87, prev:88, rewind:89, ffwd:90, chUp:166, chDown:167,
            tv:170, input:178, captions:175, numEntry:234, red:183, green:184, yellow:185, blue:186 };
  // buttons on the 3-dot page (data-key -> Android key code). null = not possible over the Google TV protocol
  var PAGE_KEYS = { pmode:K.settings, smode:K.settings, bluetooth:K.settings, wifi:K.settings, cast:null, mirror:null,
                    'ch-up':K.chUp, 'ch-down':K.chDown, tv:K.tv, input:K.input, subtitle:K.captions, numbers:K.numEntry,
                    red:K.red, green:K.green, yellow:K.yellow, blue:K.blue,
                    previous:K.prev, rewind:K.rewind, playpause:K.playPause, forward:K.ffwd, next:K.next };
  // launch links for the app row (Google TV opens the installed app for these)
  var APP_LINKS = { youtube:'https://www.youtube.com', netflix:'https://www.netflix.com/title',
                    prime:'https://app.primevideo.com', hotstar:'https://www.hotstar.com', zee5:'https://www.zee5.com',
                    sonyliv:'https://www.sonyliv.com', jiocinema:'https://www.jiocinema.com' };
  function tap(code){ if(TV) TV.key(code, 3); }
  // key DOWN on touch, key UP on release: instant response, and holding repeats like a real remote
  function press(el, code){
    if(!el) return;
    var isDown = false;
    el.addEventListener('pointerdown', function(e){
      if(!TV) return;
      isDown = true;
      try{ el.setPointerCapture(e.pointerId); }catch(err){}
      TV.key(code, 1);
    });
    function up(){ if(!isDown) return; isDown = false; if(TV) TV.key(code, 2); }
    ['pointerup','pointercancel','lostpointercapture'].forEach(function(ev){ el.addEventListener(ev, up); });
  }
  function launchApp(app){
    if(!TV) return;
    var link = app.link || APP_LINKS[app.id];
    if(!link){ TV.toastMsg('Type a package name (com.example.app) or a link to launch this app'); return; }
    TV.launch(link);
  }
  function customLink(name){
    var n = (name || '').trim();
    if(/^[a-z][a-z0-9+.\\-]*:\\/\\//i.test(n)) return n;                                  // full link
    if(/^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$/i.test(n)) return 'market://launch?id=' + n;   // package name
    return APP_LINKS[n.toLowerCase().replace(/[^a-z0-9]/g, '')] || null;
  }
""")

# ---------------------------------------------------------------- R2: TV dropdown (options come from native discovery)
rep("""  tvDropdown.querySelectorAll('.tv-option').forEach(function(opt){
    opt.addEventListener('click', function(e){
      e.stopPropagation();
      tvDropdown.querySelectorAll('.tv-option').forEach(function(o){ o.classList.remove('selected'); });
      opt.classList.add('selected');
      tvNameEl.textContent = opt.getAttribute('data-name');
      closeTvDropdown();
    });
  });
""", """  function bindTvOption(opt){
    opt.addEventListener('click', function(e){
      e.stopPropagation();
      tvDropdown.querySelectorAll('.tv-option').forEach(function(o){ o.classList.remove('selected'); });
      opt.classList.add('selected');
      tvNameEl.textContent = opt.getAttribute('data-name');
      closeTvDropdown();
      if(TV && opt.getAttribute('data-id')) TV.connect(opt.getAttribute('data-id'));
    });
  }
  tvDropdown.querySelectorAll('.tv-option').forEach(bindTvOption);
  var curTvId = '';
  function renderTvOptions(list){
    tvDropdown.querySelectorAll('.tv-option').forEach(function(o){ o.parentNode.removeChild(o); });
    list.forEach(function(d){
      var b = document.createElement('button');
      b.className = 'tv-option' + (d.id === curTvId ? ' selected' : '');
      b.setAttribute('data-name', d.name);
      b.setAttribute('data-id', d.id);
      b.innerHTML = '<span class="tv-option-dot online"></span> ';
      b.appendChild(document.createTextNode(d.name));
      bindTvOption(b);
      tvDropdown.appendChild(b);
    });
  }
""")

# ---------------------------------------------------------------- R2b: opening the list rescans; tapping its title = type TV IP
rep("""    var isOpen = tvDropdown.classList.toggle('open');
    tvPicker.classList.toggle('open', isOpen);
  });""", """    var isOpen = tvDropdown.classList.toggle('open');
    tvPicker.classList.toggle('open', isOpen);
    if(isOpen && TV) TV.rescan();
  });
  // tap the "Searching for TVs..." line to type the TV's IP address by hand (if it is not found automatically)
  var tvTitleEl = tvDropdown.querySelector('.tv-dropdown-title');
  if(tvTitleEl) tvTitleEl.addEventListener('click', function(e){ e.stopPropagation(); if(TV) TV.manualIp(); });""")

# ---------------------------------------------------------------- R3: status dot = reconnect
rep("""    e.stopPropagation();
    statusDot.classList.toggle('offline');
  });""", """    e.stopPropagation();
    if(TV){ TV.reconnect(); return; }   // real mode: tap the dot to reconnect / pair again
    statusDot.classList.toggle('offline');
  });""")

# ---------------------------------------------------------------- R4: power
rep("""  power.addEventListener('click', function(){
    power.classList.toggle('on');
  });""", """  power.addEventListener('click', function(){
    power.classList.toggle('on');
    tap(K.power);
  });""")

# ---------------------------------------------------------------- R5: touchpad gestures
rep("""  ['pointerup','pointerleave'].forEach(function(ev){
    touchpad.addEventListener(ev, function(){ touchpad.classList.remove('active'); });
  });
""", """  ['pointerup','pointerleave'].forEach(function(ev){
    touchpad.addEventListener(ev, function(){ touchpad.classList.remove('active'); });
  });
  // Touchpad -> D-pad: quick tap = OK, drag = arrow keys (one press per ~26px of movement)
  (function(){
    var down = false, lx = 0, ly = 0, ax = 0, ay = 0, total = 0, t0 = 0, sent = 0;
    var STEP_FIRST = 42, STEP_NEXT = 72;   // a normal swipe = ONE step; keep dragging for more (bigger = less sensitive)
    // without this Android's WebView treats a drag on the pad as a page scroll and cancels the swipe after a few px
    touchpad.style.touchAction = 'none';
    touchpad.addEventListener('pointerdown', function(e){
      down = true; lx = e.clientX; ly = e.clientY; ax = 0; ay = 0; total = 0; t0 = Date.now(); sent = 0;
      try{ touchpad.setPointerCapture(e.pointerId); }catch(err){}
    });
    touchpad.addEventListener('pointermove', function(e){
      if(!down || !TV) return;
      var dx = e.clientX - lx, dy = e.clientY - ly;
      lx = e.clientX; ly = e.clientY;
      total += Math.abs(dx) + Math.abs(dy); ax += dx; ay += dy;
      var need = sent === 0 ? STEP_FIRST : STEP_NEXT;
      if(Math.abs(ax) >= need && Math.abs(ax) >= Math.abs(ay)){ tap(ax > 0 ? K.right : K.left); sent++; ax = 0; ay = 0; }
      else if(Math.abs(ay) >= need){ tap(ay > 0 ? K.down : K.up); sent++; ax = 0; ay = 0; }
    });
    touchpad.addEventListener('pointerup', function(){
      if(!down) return;
      down = false;
      if(total < 10 && Date.now() - t0 < 400) tap(K.ok);
    });
    touchpad.addEventListener('pointercancel', function(){ down = false; });
  })();
""")

# ---------------------------------------------------------------- R6: scroll wheels -> arrow keys
rep("""  setupWheel(document.getElementById('wheelV'), document.getElementById('wheelVGroove'), 'v');
  setupWheel(document.getElementById('wheelH'), document.getElementById('wheelHGroove'), 'h');
""", """  var WHEEL_STEP = 45; // px of wheel drag per arrow-key press (bigger number = less sensitive)
  var wvAcc = 0, whAcc = 0, wvT = 0, whT = 0;
  // at most ONE key per drag movement, and leftover distance is dropped when the finger pauses
  setupWheel(document.getElementById('wheelV'), document.getElementById('wheelVGroove'), 'v', function(d){
    var t = Date.now(); if(t - wvT > 300) wvAcc = 0; wvT = t;
    wvAcc += d;
    if(wvAcc >= WHEEL_STEP){ wvAcc = 0; tap(K.down); }
    else if(wvAcc <= -WHEEL_STEP){ wvAcc = 0; tap(K.up); }
  });
  setupWheel(document.getElementById('wheelH'), document.getElementById('wheelHGroove'), 'h', function(d){
    var t = Date.now(); if(t - whT > 300) whAcc = 0; whT = t;
    whAcc += d;
    if(whAcc >= WHEEL_STEP){ whAcc = 0; tap(K.right); }
    else if(whAcc <= -WHEEL_STEP){ whAcc = 0; tap(K.left); }
  });
""")

# ---------------------------------------------------------------- R6b: volume wheel less sensitive on a real TV
rep("""  var VOL_PX_PER_STEP = 4; // pixels of drag per 1 volume step""", """  var VOL_PX_PER_STEP = TV ? 12 : 4; // pixels of drag per 1 volume step (real TV: gentler, one turn no longer floods the TV)""")

# ---------------------------------------------------------------- R6c: + / - buttons = exactly ONE TV volume step per press
rep("""    function fire(){ fired = true; setHLevel(hLevel + step); }""", """    function fire(){
      fired = true;
      if(TV){
        // real TV: one press = one volume key (holding repeats). The bar moves by the TV's own step size.
        var up = step > 0;
        paintHLevel(hLevel + (up ? 1 : -1) * (100 / (tvVolMax || 100)));
        lastVolTouch = Date.now();
        TV.keys(up ? K.volUp : K.volDown, 1);
      } else {
        setHLevel(hLevel + step);
      }
    }""")

# ---------------------------------------------------------------- R8: app launch
rep("""        // TODO (real code): launch this app → monkey -p <package> -c android.intent.category.LAUNCHER 1
""", """        launchApp(app);
""")

# ---------------------------------------------------------------- R9: custom app keeps a real link/package
rep("""      inner: '<span>' + name.slice(0,10) + '</span>'
      // TODO (real code): store the real package name here so the launch handler can use it
""", """      inner: '<span>' + name.slice(0,10) + '</span>',
      link: customLink(name)   // package name (com.x.y) or link typed in the box; known app names also work
""")

# ---------------------------------------------------------------- R10: back / home / recent
rep("""  document.getElementById('backHomeBtn').addEventListener('click', function(){
    // TODO (real code): input keyevent 4  (BACK)
  });""", """  press(document.getElementById('backHomeBtn'), K.back);""")
rep("""  document.getElementById('homeBtn').addEventListener('click', function(){
    // TODO (real code): input keyevent 3  (HOME)
  });""", """  press(document.getElementById('homeBtn'), K.home);""")
rep("""  document.getElementById('recentBtn').addEventListener('click', function(){
    // TODO (real code): input keyevent 187  (APP_SWITCH)
  });""", """  press(document.getElementById('recentBtn'), K.recent);""")

# nav bar buttons (D-pad, OK, play/pause)
rep("""  document.getElementById('navBar').addEventListener('pointerdown', function(e){
    e.stopPropagation();
  });
""", """  document.getElementById('navBar').addEventListener('pointerdown', function(e){
    e.stopPropagation();
  });
  (function(){
    var dirs = { left:K.left, right:K.right, up:K.up, down:K.down };
    document.querySelectorAll('#navBar .nav-btn[data-dir]').forEach(function(el){ press(el, dirs[el.getAttribute('data-dir')]); });
    document.querySelectorAll('#navBar .nav-btn[data-key]').forEach(function(el){
      press(el, el.getAttribute('data-key') === 'ok' ? K.ok : K.playPause);
    });
  })();
  // 3-dot page buttons (numbers, channel, colour keys, media, TV/Input...) — single press on tap
  // Same handler also covers the media row (Previous/Rewind/Play-Pause/Forward/Next) wherever it sits in
  // this layout (v11_17 moved it next to the touchpad, outside the 3-dot page).
  (function(){
    function onPress(e){
      var b = e.target.closest('[data-key]');
      if(!b || !TV) return;
      var k = b.getAttribute('data-key'), code;
      if(/^num[0-9]$/.test(k)) code = 7 + parseInt(k.charAt(3), 10);     // KEYCODE_0 = 7
      else if(PAGE_KEYS.hasOwnProperty(k)) code = PAGE_KEYS[k];
      else return;
      if(code === null || code === undefined){ TV.toastMsg('Not available over the Google TV remote protocol'); return; }
      tap(code);
    }
    var page = document.getElementById('panePage');
    page.addEventListener('click', onPress);
    var mediaRow = document.getElementById('mediaRowTop');
    if(mediaRow) mediaRow.addEventListener('click', onPress);
  })();
  (function(){
    // Mic: TAP to start talking (your PHONE's microphone is streamed to the TV, the TV needs no mic).
    // It stops by itself when you stop speaking; tap again to stop earlier.
    var mic = document.querySelector('[aria-label="Microphone"]');
    if(mic) mic.addEventListener('click', function(){ if(TV) TV.voiceToggle(); });
  })();
""")

# ---------------------------------------------------------------- R11: mute
rep("""    muteBtn.style.color = muteBtn.classList.contains('active') ? '#ef4444' : '';
  });

  // Horizontal""", """    muteBtn.style.color = muteBtn.classList.contains('active') ? '#ef4444' : '';
    tap(K.mute);
  });

  // Horizontal""")

# ---------------------------------------------------------------- R12: volume -> real key presses
rep("""  function setHLevel(pct){
    hLevel = Math.max(0, Math.min(100, Math.round(pct)));
    hFill.style.width = hLevel + '%';
    hHandle.style.left = hLevel + '%';
    hHandle.textContent = hLevel;
  }
""", """  // paint only (also used when the TV reports its real volume)
  function paintHLevel(pct){
    hLevel = Math.max(0, Math.min(100, Math.round(pct)));
    hFill.style.width = hLevel + '%';
    hHandle.style.left = hLevel + '%';
    hHandle.textContent = hLevel;
  }
  // user change -> volume up/down key presses on the TV (batched every 25 ms so dragging stays smooth)
  var tvVolMax = 100, volPending = 0, volTimer = null, lastVolTouch = 0;
  function flushVol(){
    volTimer = null;
    var n = volPending; volPending = 0;
    if(TV && n !== 0) TV.keys(n > 0 ? K.volUp : K.volDown, Math.abs(n));
  }
  function setHLevel(pct){
    var before = hLevel;
    paintHLevel(pct);
    var diff = hLevel - before;
    if(TV && diff !== 0){
      lastVolTouch = Date.now();
      var steps = Math.max(1, Math.round(Math.abs(diff) * tvVolMax / 100));
      volPending += diff > 0 ? steps : -steps;
      if(!volTimer) volTimer = setTimeout(flushVol, 25);
    }
  }
""")

# ---------------------------------------------------------------- R13: keyboard -> TV text (+ reuse it for the pairing code)
rep("""  document.getElementById('keyboardBtn').addEventListener('click', openKb);
  document.getElementById('kbClose').addEventListener('click', closeKb);
  document.getElementById('kbDone').addEventListener('click', closeKb);
""", """  var pairingMode = false, kbPrev = '', typedOnTv = false, kbHintDefault = null;
  function setKbHint(t){
    var h = document.querySelector('#kbOverlay .kb-hint span');
    if(!h) return;
    if(kbHintDefault === null) kbHintDefault = h.textContent;
    h.textContent = t;
  }
  // First-time pairing: the TV shows a 6-character code; it is typed on this same on-screen keyboard.
  function beginPairing(name){
    pairingMode = true; kbInput.value = ''; kbPrev = '';
    kbInput.placeholder = 'Code shown on TV';
    setKbHint('Enter the 6-character code shown on ' + name);
    openKb();
  }
  function endPairing(){
    pairingMode = false; kbInput.value = ''; kbPrev = '';
    kbInput.placeholder = 'Start typing…';
    if(kbHintDefault !== null) setKbHint(kbHintDefault);
  }
  function onKbDone(){
    if(pairingMode){
      var c = kbInput.value.trim();
      if(c.length !== 6){ setKbHint('The code has 6 characters (0-9, A-F)'); return; }
      if(TV) TV.pairCode(c);
      setKbHint('Checking code…');
      return;
    }
    if(TV && typedOnTv) tap(K.enter);   // submit what was typed (e.g. a search) on the TV
    typedOnTv = false;
    closeKb();
  }
  document.getElementById('keyboardBtn').addEventListener('click', openKb);
  document.getElementById('kbClose').addEventListener('click', function(){
    if(pairingMode){ if(TV) TV.cancelPairing(); endPairing(); }
    closeKb();
  });
  document.getElementById('kbDone').addEventListener('click', onKbDone);
  // typing with the phone's own keyboard works too
  kbInput.addEventListener('input', function(){
    var v = kbInput.value;
    if(pairingMode){ v = v.replace(/[^0-9a-fA-F]/g, '').slice(0, 6); kbInput.value = v; kbPrev = v; return; }
    if(TV){
      if(v.length > kbPrev.length && v.indexOf(kbPrev) === 0){ TV.text(v.slice(kbPrev.length)); typedOnTv = true; }
      else if(v.length < kbPrev.length){ for(var i = 0; i < kbPrev.length - v.length; i++) tap(K.del); }
    }
    kbPrev = v;
  });
  kbInput.addEventListener('keydown', function(e){ if(e.key === 'Enter'){ e.preventDefault(); onKbDone(); } });
""")

rep("""    if(keyBtn.id === 'kbBackspace'){
      kbInput.value = kbInput.value.slice(0, -1);
      return;
    }
    var val = keyBtn.getAttribute('data-key');
    if(val == null) return; // language button etc. — no-op in this demo
    var ch = (val.length === 1 && /[a-z]/i.test(val)) ? (shiftOn ? val.toUpperCase() : val.toLowerCase()) : val;
    kbInput.value += ch;
""", """    if(keyBtn.id === 'kbBackspace'){
      kbInput.value = kbInput.value.slice(0, -1);
      kbPrev = kbInput.value;
      if(!pairingMode && TV) tap(K.del);
      return;
    }
    var val = keyBtn.getAttribute('data-key');
    if(val == null) return; // language button etc.
    var ch = (val.length === 1 && /[a-z]/i.test(val)) ? (shiftOn ? val.toUpperCase() : val.toLowerCase()) : val;
    if(pairingMode){
      if(!/^[0-9a-f]$/i.test(ch) || kbInput.value.length >= 6) return;
      kbInput.value += ch; kbPrev = kbInput.value;
      return;
    }
    if(TV && ch.length !== 1) return;   // symbol-page key: nothing to type
    kbInput.value += ch;
    kbPrev = kbInput.value;
    if(TV){ TV.text(ch); typedOnTv = true; }
""")

# ---------------------------------------------------------------- R14: callbacks from native + start
rep("""    kbPrev = kbInput.value;
    if(TV){ TV.text(ch); typedOnTv = true; }
  });
})();
</script>""", """    kbPrev = kbInput.value;
    if(TV){ TV.text(ch); typedOnTv = true; }
  });

  // Top-left 3-line button = Google TV's Quick Settings (opened by a LONG PRESS of Home on a real remote)
  (function(){
    var menuBtn = document.querySelector('[aria-label="Menu"]');
    if(menuBtn) menuBtn.addEventListener('click', function(){
      if(!TV) return;
      TV.key(K.home, 1);                                  // press and hold Home...
      setTimeout(function(){ TV.key(K.home, 2); }, 650);   // ...then release ~650ms later
    });
  })();

  // ===== callbacks called by the native side (TvBridge) =====
  window.__tv = {
    onState: function(st, name, id){
      curTvId = id || '';
      if(name) tvNameEl.textContent = name;
      statusDot.classList.toggle('offline', st !== 'connected');
      tvDropdown.querySelectorAll('.tv-option').forEach(function(o){
        o.classList.toggle('selected', !!id && o.getAttribute('data-id') === id);
      });
    },
    onDevices: function(list){ renderTvOptions(list); },
    onPower: function(on){ power.classList.toggle('on', !!on); },
    onVolume: function(level, max, muted){
      if(max > 0){
        tvVolMax = max;
        if(!hDragging && Date.now() - lastVolTouch > 700) paintHLevel(level * 100 / max);
      }
      var m = !!muted;
      muteBtn.classList.toggle('active', m);
      muteBtn.style.color = m ? '#ef4444' : '';
    },
    onPairing: function(name){ beginPairing(name); },
    onPairError: function(msg){ kbInput.value = ''; kbPrev = ''; setKbHint(msg); },
    onPaired: function(){ endPairing(); closeKb(); },
    onPairEnd: function(){ endPairing(); closeKb(); }
  };
  if(TV){
    statusDot.classList.add('offline');   // until the TV answers
    TV.ready();
  }
})();
</script>""")

open('app/assets/remote.html', 'w', encoding='utf-8').write(src)

# sanity: everything before <script> must be byte-identical (UI untouched)
i0 = orig.find('<script'); i1 = src.find('<script')
assert orig[:i0] == src[:i1], 'markup/CSS changed!'
print('OK  markup+CSS identical:', len(orig[:i0]), 'bytes; script', len(orig)-i0, '->', len(src)-i1)
