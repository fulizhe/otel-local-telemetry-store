/*
 * 读口各页面共享的脚本。零依赖：不引 CDN、不引框架（ADR-6 第十三节）。
 *
 * 共享的意义不只是省代码：token 粘贴、详情弹窗、Esc 关闭这几样必须
 * **在每个页面上都一模一样**。复制到 5 个页面里就有 5 个机会漏掉其中一份，
 * 而漏掉的那一份只会表现成"这个页面的详情打不开"，极难联想到是副本漂移。
 *
 * 每个页面调 Otl.start('index'|'traces'|'logs'|'metrics'|'self') 声明自己是什么页。
 */
var Otl = (function () {
  'use strict';

  var TOKEN_KEY = 'otelstore-token';
  var REFRESH_MS = 30000;

  function $(id) { return document.getElementById(id); }

  /**
   * 元素查找**只用** has() 判定，不做"必须有"的硬要求。
   *
   * <p>这是真机踩过的坑：注入外壳时对 #wrap 调了"必须有"，
   * 而某个页面正好没写 id="wrap" —— 于是整个 start() 抛错，
   * 概览四个数字永远显示 "–"，HTML 看上去却完全正常。
   * "缺一个 id 就整页 JS 抛错"这件事的表现太隐蔽，不能靠人眼盯。
   * （这条由测试钉住：每页必须带齐 app.js 引用到的所有 id。）
   */
  function has(id) { return !!$(id); }

  // ---------------- token

  function token() { return sessionStorage.getItem(TOKEN_KEY) || ''; }
  function headers() {
    var h = {};
    var t = token();
    if (t) { h['X-Otel-Store-Token'] = t; }
    return h;
  }
  function saveToken() {
    sessionStorage.setItem(TOKEN_KEY, token.value.trim());
    restart();
  }
  function clearToken() {
    sessionStorage.removeItem(TOKEN_KEY);
    if (has('token')) { $('token').value = ''; }
    restart();
  }

  // ---------------- 格式化

  /**
   * HTML 转义。**引号也要转** —— 表格里有大量 title="…" 与 data-detail="…"，
   * 只转 & < > 的话，一个带双引号的值就能把属性提前闭合、后面全是裸文本。
   */
  function esc(s) {
    return String(s === null || s === undefined ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  /** 截断，且**截断可见**。半截内容看起来像完整的，比明确写着被截断危险得多（ADR-6 第十节）。 */
  function clip(s, n) {
    var v = s === null || s === undefined ? '' : String(s);
    return v.length <= n ? v : v.slice(0, n) + '…';
  }

  function num(v) { return typeof v === 'number' ? v.toLocaleString('en-US') : esc(v); }

  /** null / NaN 显示成 —，不显示成 "NaN" 或 "null"。 */
  function fmtNum(v) {
    if (v === null || v === undefined) { return '–'; }
    if (typeof v === 'number' && isNaN(v)) { return '–'; }
    return typeof v === 'number' ? v.toLocaleString('en-US') : String(v);
  }

  /**
   * 「键 → 值」两列。键固定宽度、值**左对齐**。
   *
   * <p>右对齐的值列看着整齐，实际最难扫：每一行的值起点都不一样，
   * 眼睛必须逐行重新对焦，而扫两列对照时人本来就是横向读的。
   * 键列定宽之后，所有值从同一条竖线开始 ——
   * 眼睛只需竖着走一遍就能把值和键对上。
   */
  function kv(rows) {
    return rows.map(function (r) {
      return '<tr><td class="k">' + esc(r[0]) + '</td><td>' + esc(r[1]) + '</td></tr>';
    }).join('');
  }

  /** 纳秒 → 本地时间。OTLP 原生就是 epoch nanos，这里只做单位换算（ADR-2 第三条坑）。 */
  function ts(nanos) {
    if (nanos === null || nanos === undefined || nanos === 0) { return '–'; }
    var d = new Date(Math.round(nanos / 1000000));
    return isNaN(d.getTime()) ? '–' : d.toLocaleTimeString();
  }

  function dur(from, to) {
    if (!from || !to || to < from) { return '–'; }
    var ms = (to - from) / 1000000;
    return ms < 1 ? ms.toFixed(3) + ' ms' : ms.toFixed(1) + ' ms';
  }

  function statusText(code) {
    if (code === 2) { return '<span class="err">ERROR</span>'; }
    if (code === 1) { return '<span class="ok">OK</span>'; }
    return 'unset';
  }

  function severityName(n) {
    if (n === null || n === undefined) { return ''; }
    if (n === 0) { return 'UNDEFINED'; }
    if (n >= 17) { return 'FATAL'; }
    if (n >= 13) { return 'ERROR'; }
    if (n >= 9) { return 'INFO'; }
    if (n >= 5) { return 'DEBUG'; }
    return 'TRACE';
  }

  function kindName(k) {
    if (k === 'SUM') { return 'Sum'; }
    if (k === 'HISTOGRAM') { return 'Histogram'; }
    if (k === 'SUMMARY') { return 'Summary'; }
    return 'Gauge';
  }

  /**
   * 把 detail 的结构化结果渲染成人能读的形态。
   * 直方图给「桶上界 → 累计计数」的对照，摘要给各分位点 —— 而不是原样吐一串 detail 文本。
   * le 与累计都由服务端算好（MetricDetail.toMap），页面不自己数"边界比桶少一个"。
   */
  function structure(d) {
    if (!d || typeof d !== 'object') { return '–'; }
    if (d.flavor === 'unknown') {
      return '<span class="err" title="解析不出来的形态，原文在 title 里">认不出来</span>';
    }
    if (d.flavor === 'long' || d.flavor === 'double') {
      return '<span style="color:var(--dim)">' + esc(d.flavor === 'long' ? '整数' : '浮点') + '</span>';
    }
    if (d.flavor === 'explicit' && d.leBoundaries) {
      var le = d.leBoundaries, cum = d.cumulative || [];
      var pairs = le.map(function (b, i) {
        var label = (b === '+Inf') ? '+∞' : ('≤' + b);
        return label + ': ' + (cum[i] !== undefined ? cum[i] : '?');
      });
      return '<span style="color:var(--dim)" title="' + esc(pairs.join('，')) + '">'
        + esc(clip(pairs.join('，'), 80)) + '</span>';
    }
    if (d.flavor === 'exponential') {
      return '<span style="color:var(--dim)">scale=' + esc(d.scale) + ' 零桶=' + esc(d.zeroCount) + '</span>';
    }
    if (d.flavor === 'quantiles' && d.quantiles) {
      return '<span style="color:var(--dim)">'
        + esc(clip(d.quantiles.map(function (q) {
            return 'p' + q.quantile + '=' + q.value;
          }).join('，'), 80)) + '</span>';
    }
    return esc(d.flavor);
  }

  function errorText(res) {
    if (res.d && res.d.message) { return res.d.message; }
    return 'HTTP ' + res.s;
  }

  // ---------------- 全页共享的两块外壳

  /**
   * token 卡片与详情弹窗由**脚本注入**，不写在每个页面的 HTML 里。
   *
   * <p>这两样必须每个页面都有，而"每页各写一份"就是等着它们漂移：
   * 哪个页面忘了写 token 卡片，表现出来是"这个页面一直 401"，
   * 从页面上完全看不出原因是漏了一段外壳。
   */
  function injectShell() {
    if (!has('detail-overlay')) {
      var ov = document.createElement('div');
      ov.className = 'overlay';
      ov.id = 'detail-overlay';
      ov.style.display = 'none';
      ov.setAttribute('onclick', 'if(event.target===this)Otl.closeDetail();');
      ov.innerHTML =
        '<div class="modal" role="dialog" aria-modal="true" aria-labelledby="detail-title">'
        + '<div class="modal-head"><h2 id="detail-title">详情</h2>'
        + '<span class="pill" id="detail-kind"></span><span style="flex:1"></span>'
        + '<button onclick="Otl.closeDetail()">关闭 <span style="color:var(--dim)">Esc</span></button>'
        + '</div>'
        + '<div class="modal-body"><div id="detail-msg" class="foot"></div>'
        + '<div id="detail-body"></div></div></div>';
      document.body.insertBefore(ov, document.body.firstChild);
    }
    if (!has('auth-card')) {
      var ac = document.createElement('div');
      ac.className = 'card';
      ac.id = 'auth-card';
      ac.style.display = 'none';
      ac.innerHTML = '<h2>需要访问 token</h2>'
        + '<div class="note">这个读口启用了鉴权。token 在数据目录下的 <code>otelstore.token</code> 文件里，'
        + '由进程启动时随机生成。<b>粘一次即可</b> —— 值只存在本标签页的 sessionStorage，'
        + '关掉标签页就没了，不进 URL、不进 cookie。</div>'
        + '<div class="row" style="margin-top:10px">'
        + '<input id="token" class="grow" type="password" placeholder="粘贴 token" autocomplete="off">'
        + '<button onclick="Otl.saveToken()">保存</button>'
        + '<button onclick="Otl.clearToken()">清除</button></div>';
      // 挂在 .wrap 开头；页面没有 .wrap 就退到 body 末尾。
      // 这里刻意**不**要求某个固定 id —— 那是"五个页面都得记得写同一个 id"，
      // 而写漏了的表现是整页 JS 抛错、一个数据都不出。
      var wrap = document.querySelector('.wrap') || document.body;
      wrap.insertBefore(ac, wrap.firstChild);
    }
    document.addEventListener('keydown', function (ev) {
      if (ev.key === 'Escape') { closeDetail(); }
    });
  }

  function unauth() { $('auth-card').style.display = ''; }
  function authed() { $('auth-card').style.display = 'none'; }

  // ---------------- 详情弹窗

  function closeDetail() {
    if (!has('detail-overlay')) { return; }
    $('detail-overlay').style.display = 'none';
    document.body.style.overflow = '';
  }

  function openDetail(id, kind) {
    var isSpan = kind !== 'log';
    $('detail-overlay').style.display = 'flex';
    document.body.style.overflow = 'hidden';   // 背景不该跟着滚
    $('detail-kind').textContent = (isSpan ? 'span #' : '日志 #') + id;
    $('detail-msg').textContent = '加载中…';
    $('detail-body').innerHTML = '';
    fetch('/api/' + (isSpan ? 'traces/' : 'logs/') + encodeURIComponent(id),
            { headers: headers(), cache: 'no-store' })
      .then(function (r) { return r.json().then(function (d) { return { s: r.status, d: d }; }); })
      .then(function (res) {
        if (res.s === 401) { unauth(); closeDetail(); return; }
        if (res.s !== 200 || !res.d) {
          $('detail-msg').innerHTML = '<span class="err">' + esc(errorText(res)) + '</span>';
          return;
        }
        renderDetail(res.d, isSpan);
      })
      .catch(function (e) {
        $('detail-msg').innerHTML = '<span class="err">读详情失败：' + esc(e.message) + '</span>';
      });
  }

  function renderDetail(d, isSpan) {
    var head = '<table><tbody>' + kv([
      ['trace_id', d.traceId], ['span_id', d.spanId],
      isSpan ? ['parent_span_id', d.parentSpanId]
             : ['severity', d.severityText || severityName(d.severityNumber)],
      ['scope', (d.scopeName || '') + (d.scopeVersion ? ' ' + d.scopeVersion : '')],
      ['资源', d.resource]
    ]) + '</tbody></table>';

    var p = d.payload || {};
    var payloadHtml;
    if (p.available) {
      payloadHtml = '<div class="foot">载荷 ' + esc(p.bytes) + ' 字节，已解码</div>'
        + attrTable((p.decoded || {}).attributes)
        + (isSpan ? eventTable(p.decoded) + statusLine(p.decoded) : '');
    } else {
      // 三种"读不到"各有各的说法，**别混成一句"不可用"**：
      // 过期不是故障、没有载荷不影响查询、corrupt 才是真的坏了
      var kind = p.reason === 'expired' ? 'note' : 'err';
      payloadHtml = '<div class="note ' + (kind === 'note' ? '' : 'pending') + '" style="margin-top:10px">'
        + '<b>' + esc(p.reason || 'unavailable') + '</b> —— ' + esc(p.detail || '') + '</div>';
    }

    $('detail-msg').innerHTML = '';
    $('detail-body').innerHTML = head + '<h3>属性</h3>' + payloadHtml;
  }

  function attrTable(attrs) {
    if (!attrs || !attrs.length) { return '<div class="foot">没有属性</div>'; }
    return '<table><thead><tr><th>键</th><th>类型</th><th>值</th></tr></thead><tbody>'
      + attrs.map(function (a) {
          return '<tr><td>' + esc(a.key) + '</td><td>' + esc(clip(a.type, 18)) + '</td>'
            + '<td>' + esc(clip(typeof a.value === 'object' ? JSON.stringify(a.value) : a.value, 120))
            + '</td></tr>';
        }).join('') + '</tbody></table>';
  }

  function eventTable(decoded) {
    var events = (decoded || {}).events || [];
    if (!events.length) { return ''; }
    return '<h3>事件（' + events.length + '）</h3>'
      + '<table><thead><tr><th>时间</th><th>名字</th><th>属性</th></tr></thead><tbody>'
      + events.map(function (e) {
          return '<tr><td>' + esc(ts(e.timeUnixNano)) + '</td><td>' + esc(e.name) + '</td>'
            + '<td>' + esc(clip((e.attributes || []).map(function (a) {
                return a.key + '=' + a.value;
              }).join('，'), 100)) + '</td></tr>';
        }).join('') + '</tbody></table>';
  }

  function statusLine(decoded) {
    var s = (decoded || {}).status;
    if (!s || s.code === 'STATUS_CODE_UNSET') { return ''; }
    return '<div class="foot" style="margin-top:8px">状态：<b>' + esc(s.code) + '</b> '
      + esc(s.message || '') + '</div>';
  }

  // ---------------- 摘要

  /**
   * 拉一次 /api/summary 并把各块渲染交给调用方。
   *
   * @param fill 函数，收到 {d, store, queues, rings}；不认识的块自己不管。
   */
  function loadSummary(fill) {
    fetch('/api/summary', { headers: headers(), cache: 'no-store' })
      .then(function (r) {
        if (r.status === 401) {
          unauth();
          if (has('msg')) {
            $('msg').innerHTML = '<span class="err">读口要求 token（401）。</span> '
              + '把数据目录下 <code>otelstore.token</code> 的内容粘到上面。';
          }
          return null;
        }
        if (!r.ok) { throw new Error('HTTP ' + r.status); }
        if (has('msg')) { $('msg').textContent = '更新于 ' + new Date().toLocaleTimeString(); }
        return r.json();
      })
      .then(function (d) {
        if (!d) { return; }
        authed();
        fill({ d: d, store: d.store, queues: d.queues || {}, rings: ringRows(d.store) });
      })
      .catch(function (e) {
        if (has('msg')) {
          $('msg').innerHTML = '<span class="err">读不到读口：' + esc(e.message) + '</span>';
        }
      });
  }

  function ringRows(store) {
    var rings = [];
    ['traceRing', 'logRing'].forEach(function (k) {
      var r = (store || {})[k];
      if (!r) { return; }
      rings.push([r.signal + '（' + r.file + '）', r.currIndex, r.wrapCount,
                  r.oldestLiveIndex, r.rejectedTooLarge, r.expiredReads]);
    });
    return rings;
  }

  function rowCounts(store) {
    if (!store) {
      // 存储层未就绪：只计数不落盘。**不能显示 0** —— 0 会被读成"没有数据"，
      // 而真相是"什么都还没落盘"，两者的下一步完全不同。
      return { spans: 'off', logs: 'off', metrics: 'off', resources: 'off', off: true };
    }
    return {
      spans: num(store.spanRows),
      logs: num(store.logRows),
      metrics: num(store.metricRows),
      // 字典的计数是**嵌套**的一层（interned / reused / cachedHashes / collisions），
      // 字典的**行数**就是 interned —— 之前直接把 store.resources 当数字渲染，
      // 结果页面上出现 [object Object]。
      resources: num((store.resources || {}).interned),
      off: false
    };
  }

  // ---------------- 列表

  function currentTrace() {
    return has('trace-id') ? $('trace-id').value.trim() : '';
  }

  /**
   * trace 链接：只放 data 属性，**不内联 onclick**。
   *
   * <p>内联 onclick 是靠字符串拼接把引号套出来的，那是引号地狱 —— 少一个转义就整段失效，
   * 而且将来页面一旦有 CSP（script-src 不含 'unsafe-inline'）就全部点不动。
   * 事件委托（tbody 上一个监听 + data-trace）两种问题都没有。
   */
  function traceLink(id) {
    return ' <a href="#" data-trace="' + esc(id) + '" title="只看这个 trace 的 span 与日志"'
      + ' style="color:var(--dim);cursor:pointer">trace</a>';
  }

  /**
   * 点 trace 链接 → **跳到 span 页并带上 traceId**。
   *
   * <p>拆成多页之后这不是可有可无的方便：span 与日志在两个页面上，
   * 跨页筛选只能靠 URL 携带。
   */
  function pickTrace(id) { location.href = 'traces.html?traceId=' + encodeURIComponent(id); }

  function loadTraces() {
    var q = currentTrace() ? '?limit=20&traceId=' + encodeURIComponent(currentTrace()) : '?limit=20';
    fetch('/api/traces' + q, { headers: headers(), cache: 'no-store' })
      .then(function (r) { return r.json().then(function (d) { return { s: r.status, d: d }; }); })
      .then(renderSpans)
      .catch(function (e) {
        $('spans-msg').innerHTML = '<span class="err">读 span 列表失败：' + esc(e.message) + '</span>';
      });
  }

  function renderSpans(res) {
    if (res.s === 401) { unauth(); return; }
    if (res.s !== 200 || !Array.isArray(res.d)) {
      $('spans-msg').innerHTML = '<span class="err">' + esc(errorText(res)) + '</span>';
      return;
    }
    $('spans-msg').textContent = res.d.length
      ? '共 ' + res.d.length + ' 条' + (currentTrace() ? '（该 trace，按开始时间排；点「清除」回到最近）' : '（最近）')
      : '没有数据 —— 这不是故障：可能是没数据、已被行数水位淘汰、或该 trace 的采集期已过。';
    $('spans-count').textContent = res.d.length + ' 条';
    $('spans').innerHTML = res.d.map(function (r) {
      return '<tr data-detail="' + esc(r.id) + '" data-kind="span" title="点开看详情">'
        + '<td>' + esc(ts(r.startTime)) + '</td>'
        + '<td>' + esc(clip(r.name, 48)) + (r.traceId ? traceLink(r.traceId) : '') + '</td>'
        + '<td class="num">' + dur(r.startTime, r.endTime) + '</td>'
        + '<td>' + statusText(r.statusCode) + '</td>'
        + '<td>' + esc(clip(r.scopeName, 28)) + '</td>'
        + '<td>' + esc(clip(r.resource, 40)) + '</td></tr>';
    }).join('') || '<tr><td colspan="6" style="color:var(--dim)">–</td></tr>';
  }

  function loadLogs() {
    var q = currentTrace() ? '?limit=20&traceId=' + encodeURIComponent(currentTrace()) : '?limit=20';
    fetch('/api/logs' + q, { headers: headers(), cache: 'no-store' })
      .then(function (r) { return r.json().then(function (d) { return { s: r.status, d: d }; }); })
      .then(renderLogs)
      .catch(function (e) {
        $('logs-msg').innerHTML = '<span class="err">读日志列表失败：' + esc(e.message) + '</span>';
      });
  }

  function renderLogs(res) {
    if (res.s === 401) { unauth(); return; }
    if (res.s !== 200 || !Array.isArray(res.d)) {
      $('logs-msg').innerHTML = '<span class="err">' + esc(errorText(res)) + '</span>';
      return;
    }
    $('logs-msg').textContent = res.d.length ? '共 ' + res.d.length + ' 条' : '没有数据。';
    $('logs-count').textContent = res.d.length + ' 条';
    $('logs').innerHTML = res.d.map(function (r) {
      return '<tr data-detail="' + esc(r.id) + '" data-kind="log" title="点开看详情">'
        + '<td>' + esc(ts(r.timestamp)) + '</td>'
        + '<td>' + esc(clip(r.severityText || severityName(r.severityNumber), 12)) + '</td>'
        + '<td>' + esc(clip(r.bodyPreview, 90)) + '</td>'
        + '<td>' + (r.traceId
            ? '<a href="#" data-trace="' + esc(r.traceId) + '" title="去看这个 trace 的 span"'
              + ' style="color:var(--dim);cursor:pointer">' + esc(clip(r.traceId, 12)) + '…</a>'
            : '')
        + '</td></tr>';
    }).join('') || '<tr><td colspan="4" style="color:var(--dim)">–</td></tr>';
  }

  function currentMetricName() {
    return has('metric-name') ? $('metric-name').value.trim() : '';
  }

  function loadMetrics() {
    var name = currentMetricName();
    fetch('/api/metrics' + (name ? '?name=' + encodeURIComponent(name) : '?limit=20'),
          { headers: headers(), cache: 'no-store' })
      .then(function (r) { return r.json().then(function (d) { return { s: r.status, d: d }; }); })
      .then(renderMetrics)
      .catch(function (e) {
        $('metrics-msg').innerHTML = '<span class="err">读指标点失败：' + esc(e.message) + '</span>';
      });
  }

  function renderMetrics(res) {
    if (res.s === 401) { unauth(); return; }
    if (res.s !== 200 || !Array.isArray(res.d)) {
      $('metrics-msg').innerHTML = '<span class="err">' + esc(errorText(res)) + '</span>';
      return;
    }
    $('metrics-msg').textContent = res.d.length ? '共 ' + res.d.length + ' 个点' : '没有指标点。';
    $('metrics-count').textContent = res.d.length + ' 个';
    $('metrics').innerHTML = res.d.map(function (r) {
      return '<tr><td>' + esc(ts(r.ts)) + '</td>'
        + '<td>' + esc(clip(r.metricName, 36))
          + (r.unit ? ' <span class="pill">' + esc(r.unit) + '</span>' : '') + '</td>'
        + '<td>' + esc(kindName(r.dataType)) + '</td>'
        + '<td class="num">' + esc(fmtNum(r.metricValue)) + '</td>'
        + '<td class="num">' + esc(fmtNum(r.metricCount)) + '</td>'
        + '<td class="num">' + esc(fmtNum(r.metricSum)) + '</td>'
        + '<td>' + structure(r.detail) + '</td></tr>';
    }).join('') || '<tr><td colspan="7" style="color:var(--dim)">–</td></tr>';
  }

  /** 事件委托：tbody 上一个监听，管 data-trace 链接与 data-detail 行（新增的行也自动生效）。 */
  function wireTable(id) {
    var body = $(id);
    if (!body) { return; }
    body.style.cursor = 'pointer';   // 表头行也做成可点的样子 —— 否则"行可点"只能靠猜
    body.addEventListener('click', function (ev) {
      var a = ev.target.closest ? ev.target.closest('a[data-trace]') : null;
      if (a) {
        ev.preventDefault();
        pickTrace(a.getAttribute('data-trace'));
        return;
      }
      var row = ev.target.closest ? ev.target.closest('tr[data-detail]') : null;
      if (row) { openDetail(row.getAttribute('data-detail'), row.getAttribute('data-kind')); }
    });
  }

  // ---------------- 启动

  var reload = [];

  /** 从 URL 取参数，让别的页面能带着筛选条件跳过来。 */
  function param(name) {
    var m = new RegExp('[?&]' + name + '=([^&]*)').exec(location.search);
    return m ? decodeURIComponent(m[1].replace(/\+/g, ' ')) : '';
  }

  function start(page) {
    injectShell();
    if (has('token') && token()) { $('token').value = token(); }
    // 表单回车即查询，否则"填了不按按钮"是很常见的误操作
    ['trace-id', 'metric-name'].forEach(function (id) {
      if (has(id)) {
        $(id).addEventListener('keydown', function (ev) {
          if (ev.key === 'Enter') { ev.preventDefault(); reload.forEach(function (f) { f(); }); }
        });
      }
    });
    if (has('trace-id') && param('traceId')) { $('trace-id').value = param('traceId'); }
    if (has('metric-name') && param('name')) { $('metric-name').value = param('name'); }

    // 页面自己声明要刷新哪几块；重载时只跑声明过的
    ['traces', 'logs', 'metrics'].forEach(function (k) {
      if (has(k)) { reload.push(loaders[k]); wireTable(k); }
    });
    // 行数格子：按**格子本身**在不在来判定，不按外层容器某个 id ——
    // 否则"容器改名了"会表现成四个数字永远显示 –，看不出是接线断了。
    if (has('s-spans')) { reload.push(loadSummary(tiles)); }
    if (has('queues') || has('rings') || has('config')) { reload.push(loadSummary(libraryTables)); }

    reload.forEach(function (f) { f(); });
    setInterval(function () { reload.forEach(function (f) { f(); }); }, REFRESH_MS);
  }

  var loaders = { traces: loadTraces, logs: loadLogs, metrics: loadMetrics };

  /** 索引页的概览：只放四个行数，不重复自监控页那套健康表。 */
  function tiles(ctx) {
    var c = rowCounts(ctx.store);
    if (has('s-spans')) { $('s-spans').textContent = c.spans; }
    if (has('s-logs')) { $('s-logs').textContent = c.logs; }
    if (has('s-metrics')) { $('s-metrics').textContent = c.metrics; }
    if (has('s-resources')) { $('s-resources').textContent = c.resources; }
    if (c.off && has('tiles-note')) {
      $('tiles-note').innerHTML = '<span class="err">存储层未就绪</span> —— '
        + '本次只计数不落盘。原因是数据目录不可写，详见 <a href="self.html">自监控</a>。';
    }
  }

  /** 自监控页的「库状态」块。**不是扩展自身的健康**，所以与生效配置分开一段。 */
  function libraryTables(ctx) {
    if (has('queues')) {
      $('queues').innerHTML = ['traces', 'logs', 'metrics'].map(function (sig) {
        var q = ctx.queues[sig] || {};
        return '<tr><td>' + sig + '</td><td class="num">' + num(q.offered) + '</td>'
          + '<td class="num">' + num(q.drained) + '</td>'
          + '<td class="num">' + num(q.dropped) + '</td>'
          + '<td class="num">' + num(q.sinkErrors) + '</td>'
          + '<td class="num">' + num(q.backlog) + '</td></tr>';
      }).join('');
    }
    if (has('rings')) {
      $('rings').innerHTML = ctx.rings.length
        ? ctx.rings.map(function (r) {
            return '<tr><td>' + esc(r[0]) + '</td><td class="num">' + num(r[1]) + '</td>'
              + '<td class="num">' + num(r[2]) + '</td><td class="num">' + num(r[3]) + '</td>'
              + '<td class="num">' + num(r[4]) + '</td><td class="num">' + num(r[5]) + '</td></tr>';
          }).join('')
        : '<tr><td colspan="6" style="color:var(--dim)">–</td></tr>';
    }
    if (has('config')) {
      $('config').innerHTML = kv(Object.keys(ctx.d.config || {}).map(function (k) {
        return [k, ctx.d.config[k]];
      }));
    }
  }

  return {
    start: start, saveToken: saveToken, clearToken: clearToken,
    openDetail: openDetail, closeDetail: closeDetail,
    refresh: function () { reload.forEach(function (f) { f(); }); },
    esc: esc, clip: clip
  };
})();
