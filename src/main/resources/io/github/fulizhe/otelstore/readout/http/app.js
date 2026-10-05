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
    if (has('token')) {
      sessionStorage.setItem(TOKEN_KEY, $('token').value.trim());
    }
    Otl.refresh();
  }
  function clearToken() {
    sessionStorage.removeItem(TOKEN_KEY);
    if (has('token')) { $('token').value = ''; }
    Otl.refresh();
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

  // ---------------- 趋势图

  var CHART_COLORS = ['#4c9aff', '#3fb950', '#d29922', '#f85149', '#a371f7',
                      '#39c5cf', '#e3b341', '#8b949e'];

  /**
   * 这个形态该画哪个量。
   *
   * <p>直方图与摘要**没有单一的值**，所以画的是「计数」这种代理量，
   * 并把这个名字交给图例标出来。把 sum 标成「值」就是在撒谎（ADR-6 第十四节）。
   *
   * @return 可画的量名，或 null 表示这个点没有可画的数字（**不补 0**）
   */
  function quantityOf(row) {
    if (row.dataType === 'GAUGE') {
      return numOrNull(row.metricValue) === null ? null : { key: '值', get: function (r) { return r.metricValue; } };
    }
    if (row.dataType === 'SUM') {
      return numOrNull(row.metricSum) === null ? null : { key: '求和', get: function (r) { return r.metricSum; } };
    }
    if (row.dataType === 'HISTOGRAM' || row.dataType === 'SUMMARY') {
      return numOrNull(row.metricCount) === null
          ? null : { key: '计数', get: function (r) { return r.metricCount; } };
    }
    return numOrNull(row.metricValue) === null ? null : { key: '值', get: function (r) { return r.metricValue; } };
  }

  /** 取数字；不是数字时返回 null（**不返回 0** —— 补 0 会造出不存在的下跌）。
   *  名字别叫 num：格式化那一节已经有一个 num 了，重名会把那个悄悄顶掉。 */
  function numOrNull(v) {
    if (v === null || v === undefined) { return null; }
    var n = Number(v);
    return isNaN(n) ? null : n;
  }

  /**
   * 指标趋势图：内联 SVG，与表格共用同一次 /api/metrics 的结果。
   *
   * <p>几条自我约束（理由见 ADR-6 第十四节）：
   * 只画能画对的量并写在图例上；没有数字的点跳过而不是补 0；
   * 只有一个时间点就不画线；只画前 8 条并说清还剩几条没画。
   */
  function renderChart(rows) {
    var host = $('chart');
    if (!host) { return; }
    var list = rows || [];
    if (!list.length) {
      host.innerHTML = '<div class="foot">没有指标点，画不出趋势。</div>';
      return;
    }

    // 序列 = 指标名 + 属性组合哈希 + 画的量。attrKey 只是哈希，
    // 所以图例只能给指标名缀一段哈希，不能拿它冒充属性名。
    var byKey = {};
    var order = [];
    list.forEach(function (r) {
      var q = quantityOf(r);
      var t = numOrNull(r.ts);
      if (!q || t === null) { return; }
      var key = r.metricName + '|' + (r.attrKey || '-') + '|' + q.key;
      if (!byKey[key]) {
        byKey[key] = { label: r.metricName, attr: r.attrKey || '', unit: r.unit || '',
                       what: q.key, get: q.get, points: [] };
        order.push(key);
      }
      byKey[key].points.push({ t: t, v: q.get(r) });
    });

    if (!order.length) {
      host.innerHTML = '<div class="foot">这些点没有可画的数字（值/求和/计数全为空）—— '
        + '不补 0，补出来的下跌是假的。</div>';
      return;
    }

    var MAX_SERIES = 8;
    var shown = order.slice(0, MAX_SERIES);
    var hidden = order.length - shown.length;
    shown.forEach(function (k) { byKey[k].points.sort(function (a, b) { return a.t - b.t; }); });

    var tMin = Infinity, tMax = -Infinity, vMin = Infinity, vMax = -Infinity, tCount = {};
    shown.forEach(function (k) {
      byKey[k].points.forEach(function (p) {
        tMin = Math.min(tMin, p.t); tMax = Math.max(tMax, p.t);
        vMin = Math.min(vMin, p.v); vMax = Math.max(vMax, p.v);
        tCount[p.t] = true;
      });
    });
    if (Object.keys(tCount).length < 2) {
      host.innerHTML = '<div class="foot">只有一个时间点，'
        + '画不出趋势 —— 画一个点再连成横线，会被读成"这段时间没变化"。</div>';
      return;
    }
    // 全平的序列：纵轴上下限重合会让所有点都贴在一条边上，读起来像"顶到上限了"
    if (vMin === vMax) {
      var pad = Math.abs(vMin) > 0 ? Math.abs(vMin) * 0.05 : 1;
      vMin -= pad; vMax += pad;
    }

    var W = 720, H = 190, L = 52, R = 12, T = 12, B = 24;
    /** 一条序列最多画多少列。超出就按列降采样，并把比例写在图例上。 */
    var COLS = 512;
    var px = function (t) { return L + (t - tMin) / (tMax - tMin) * (W - L - R); };
    var py = function (v) { return T + (vMax - v) / (vMax - vMin) * (H - T - B); };
    var totalPoints = 0;
    shown.forEach(function (k) { totalPoints += byKey[k].points.length; });

    var svg = ['<svg class="chart" viewBox="0 0 ' + W + ' ' + H + '" role="img"'
      + ' aria-label="指标趋势图">'];
    // 三条横向参考线：上界、中间、下界。标出上下限，
    // 否则两条平线会被读成"两条一样高"而不是"两条各自平的"
    [vMax, (vMax + vMin) / 2, vMin].forEach(function (v) {
      svg.push('<line class="grid-line" x1="' + L + '" x2="' + (W - R) + '" y1="' + py(v)
        + '" y2="' + py(v) + '"/>');
    });
    [vMax, vMin].forEach(function (v, i) {
      svg.push('<text class="axis-text" x="4" y="' + (py(v) + 3) + '">'
        + esc(clip(fmtNum(round(v)), 8)) + '</text>');
    });
    svg.push('<text class="axis-text" x="4" y="' + (T + 8) + '">' + esc(whatUnit(shown, byKey))
      + '</text>');
    svg.push('<text class="axis-text" x="' + L + '" y="' + (H - 6) + '">'
      + esc(clockOf(tMin)) + '</text>');
    svg.push('<text class="axis-text" x="' + (W - R) + '" y="' + (H - 6) + '" text-anchor="end">'
      + esc(clockOf(tMax)) + '</text>');

    var downsampled = 0;
    shown.forEach(function (k, i) {
      var s = byKey[k];
      var color = CHART_COLORS[i % CHART_COLORS.length];
      var cols = downsample(s.points, COLS, px);
      downsampled += Math.max(0, s.points.length - cols.length);
      // 每列画 min..max 的竖线，再连首尾。取 min/max 而不是均值 ——
      // 均值会把尖峰抹平，而"刚才抖了一下"正是要看这张图的原因
      var d = cols.map(function (c, j) {
        var top = py(c.vmax).toFixed(1);
        if (c.vmax === c.vmin) {
          return (j ? 'L' : 'M') + c.x.toFixed(1) + ' ' + top;
        }
        return (j ? 'L' : 'M') + c.x.toFixed(1) + ' ' + top
          + 'V' + py(c.vmin).toFixed(1)
          + 'L' + c.x.toFixed(1) + ' ' + top;
      }).join(' ');
      svg.push('<path d="' + d + '" fill="none" stroke="' + color + '" stroke-width="1.6"/>');
      // 悬浮读数给这一列**原始点**的 min/max 与首尾时间，不是降采样后的值
      cols.forEach(function (c) {
        svg.push('<circle class="dot" cx="' + c.x.toFixed(1) + '" cy="' + py(c.vmax).toFixed(1)
          + '" r="2.2" fill="' + color + '"><title>' + esc(colTitle(c, s)) + '</title></circle>');
      });
    });
    svg.push('</svg>');

    var legend = shown.map(function (k, i) {
      var s = byKey[k];
      return '<span class="item" title="' + esc(s.label + (s.attr ? ' · ' + s.attr : '')
        + '（' + s.what + (s.unit ? '，单位 ' + s.unit : '') + '）') + '">'
        + '<span class="swatch" style="background:' + CHART_COLORS[i % CHART_COLORS.length]
        + '"></span>' + esc(clip(s.label, 30))
        + ' <span style="opacity:.7">' + esc(s.what) + (s.attr ? ' · ' + esc(s.attr.slice(0, 8)) : '')
        + '</span></span>';
    });
    if (hidden > 0) {
      legend.push('<span class="item">还有 ' + hidden + ' 条序列没画</span>');
    }
    if (downsampled > 0) {
      // 降级必须可见（ADR-6 第十四节）：不写这一句，读者会以为那就是全部点
      legend.push('<span class="item">已降采样：' + fmtNum(totalPoints) + ' 点 → '
        + fmtNum(totalPoints - downsampled) + ' 列（每列取 min..max，悬浮看读数）</span>');
    }

    host.innerHTML = svg.join('') + '<div class="legend">' + legend.join('') + '</div>'
      + '<div class="foot">图与上面的表是<b>同一份数据</b>（同一次 /api/metrics）。'
      + '悬浮任一点看读数。</div>';
  }

  /**
 * 按像素列降采样：一列一个 min/max/首尾时间。
 *
 * <p>为什么取 min/max 而不是均值：均值会把尖峰抹平，
 * 而"刚才抖了一下"正是要看这张图的原因。
 *
 * <p>点是按<b>时间</b>分的列，不是按序号 —— 时间间隔不均匀时按序号分列会把安静段
 * 和密集段画成同样的宽度。
 *
 * @param px 时间 → x 的换算函数。<b>必须传进来</b>：它是调用方的局部变量，
 *        放在这里闭包引用会在运行时报 "px is not defined"。
 */
  function downsample(points, cols, px) {
    if (points.length <= cols) {
      return points.map(function (p) {
        return { x: px(p.t), vmin: p.v, vmax: p.v, n: 1, t0: p.t, t1: p.t };
      });
    }
    var out = [];
    var bucket = Math.ceil(points.length / cols);
    for (var i = 0; i < points.length; i += bucket) {
      var slice = points.slice(i, i + bucket);
      var lo = slice[0].v, hi = slice[0].v;
      for (var j = 1; j < slice.length; j++) {
        lo = Math.min(lo, slice[j].v);
        hi = Math.max(hi, slice[j].v);
      }
      out.push({ x: px(slice[Math.floor(slice.length / 2)].t),
                 vmin: lo, vmax: hi, n: slice.length,
                 t0: slice[0].t, t1: slice[slice.length - 1].t });
    }
    return out;
  }

  function colTitle(c, s) {
    var u = s.unit ? ' ' + s.unit : '';
    if (c.n === 1) {
      return clockOf(c.t0) + '　' + s.what + ' ' + fmtNum(round(c.vmax)) + u;
    }
    return clockOf(c.t0) + '–' + clockOf(c.t1) + '　' + c.n + ' 个点　'
      + s.what + ' min ' + fmtNum(round(c.vmin)) + ' / max ' + fmtNum(round(c.vmax)) + u;
  }

  function whatUnit(keys, byKey) {
    var whats = {};
    keys.forEach(function (k) { whats[byKey[k].what] = true; });
    var list = Object.keys(whats);
    return list.length === 1 ? list[0] : list.join(' / ');
  }

  function round(v) {
    // 坐标轴上不必精确到小数点后十五位
    return Math.abs(v) < 1e15 ? Math.round(v * 1000) / 1000 : v;
  }

  /** epoch 纳秒 → 时:分:秒（趋势图的横轴要的是"几点几分"，不是完整日期）。 */
  function clockOf(nanos) {
    var d = new Date(Math.round(nanos / 1000000));
    return isNaN(d.getTime()) ? '–' : d.toLocaleTimeString();
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
        + (isSpan ? '' : logBody(p.decoded))
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

  /**
   * 日志正文。
   *
   * <p>正文在 {@code decoded.body} 里，而列表那一列只是它的<b>预览</b>。
   * 详情弹框原本只渲染 attributes —— 于是日志详情里最该有的东西反倒是空的，
   * 而"弹框打开了、里面只有属性表"看上去完全像正常。
   *
   * <p>正文可能是一整个请求体或一整段栈，所以截断到 4 KB 且<b>标明截了多少</b>
   * （半截内容看起来像完整的，比明确写着被截断危险得多，ADR-6 第十节）。
   */
  function logBody(decoded) {
    var b = (decoded || {}).body;
    if (b === null || b === undefined || b === '') {
      return '<div class="foot">这条日志没有正文（或正文是空的）</div>';
    }
    var text = typeof b === 'object' ? JSON.stringify(b) : String(b);
    var LIMIT = 4096;
    var shown = text.length > LIMIT ? text.slice(0, LIMIT) : text;
    var more = text.length > LIMIT
      ? '<div class="foot">正文共 ' + text.length.toLocaleString('en-US') + ' 字符，'
        + '这里只显示前 ' + LIMIT.toLocaleString('en-US') + ' 个'
        + '（载荷本身不提供下载口，这是刻意不给的，见 ADR-6 第七节）</div>'
      : '';
    return '<h3>正文</h3><pre class="body">' + esc(shown) + '</pre>' + more;
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
    renderChart(res.d);
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

  /** 段名 → 表格 tbody 的 id。**这两者不是同一个词**，别靠"恰好同名"活着。 */
  var TABLE_OF = { traces: 'spans', logs: 'logs', metrics: 'metrics' };

  /** 事件委托：tbody 上一个监听，管 data-trace 链接与 data-detail 行（新增的行也自动生效）。 */
  function wireTable(id) {
    var body = $(id);
    if (!body) {
      // 拼错段名要立刻炸。之前这里是静默 return，span 页因此整页点不动，
      // 而页面上看不出任何异常。
      throw new Error('找不到表格元素 #' + id + '（段名与 tbody 的 id 对不上）');
    }
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

  /**
   * 本页要跑的东西，由**页面显式声明**（{@link start} 的参数）。
   *
   * <p>曾经试图靠"猜"：`has('spans')` 有就挂加载器、`has('s-spans')` 有就填格子。
   * 那是错的 —— 猜意味着键名和 id 必须永远对上，而对不上时的表现是
   * `<b>f is not a function</b>` 或者一格数字永远不动：
   * 两种都只出现在浏览器控制台，HTML 看上去完全正常。
   *
   * <p>声明式的代价是每页多写一个字符串数组，收益是**接线错了会立刻炸**，
   * 而且能被测试钉住。
   */
  var reload = [];

  /** 从 URL 取参数，让别的页面能带着筛选条件跳过来。 */
  function param(name) {
    var m = new RegExp('[?&]' + name + '=([^&]*)').exec(location.search);
    return m ? decodeURIComponent(m[1].replace(/\+/g, ' ')) : '';
  }

  var LOADERS = {
    traces: loadTraces,
    logs: loadLogs,
    metrics: loadMetrics,
    tiles: function () { return loadSummary(tiles); },
    library: function () { return loadSummary(libraryTables); }
  };

  /**
   * @param page 段名，只用于报错时说清是哪个页面。
   * @param parts 要跑的部分，见 {@link LOADERS} 的键。
   */
  function start(page, parts) {
    injectShell();
    if (has('token') && token()) { $('token').value = token(); }

    reload = [];
    (parts || []).forEach(function (name) {
      var loader = LOADERS[name];
      // 拼错名字要立刻炸，不能等 30 秒后表现为"这一块是空的"
      if (!loader) {
        throw new Error(page + ' 声明了没知的部分 ' + name +
            '（可用：' + Object.keys(LOADERS).join(' / ') + '）');
      }
      reload.push(loader);
    });

    ['traces', 'logs', 'metrics'].forEach(function (k) {
      if ((parts || []).indexOf(k) >= 0) { wireTable(TABLE_OF[k]); }
    });

    // 表单回车即查询，否则"填了不按按钮"是很常见的误操作
    ['trace-id', 'metric-name'].forEach(function (id) {
      if (has(id)) {
        $(id).addEventListener('keydown', function (ev) {
          if (ev.key === 'Enter') { ev.preventDefault(); runReload(); }
        });
      }
    });
    if (has('trace-id') && param('traceId')) { $('trace-id').value = param('traceId'); }
    if (has('metric-name') && param('name')) { $('metric-name').value = param('name'); }

    runReload();
    setInterval(runReload, REFRESH_MS);
  }

  function runReload() {
    reload.forEach(function (f) {
      // 已经变成 undefined 的（比如有人 push 了立即调用的结果）要有说明，
      // 而不是让调用方看到 "f is not a function"
      if (typeof f !== 'function') {
        throw new Error('要跑的东西不是函数，是 ' + String(f) +
            ' —— 那是 push 了一个立即调用的结果');
      }
      f();
    });
  }

  /** 索引页的概览：只放四个行数，不重复自监控页那套健康表。 */
  function tiles(ctx) {
    var c = rowCounts(ctx.store);
    if (has('s-spans')) { $('s-spans').textContent = c.spans; }
    if (has('s-logs')) { $('s-logs').textContent = c.logs; }
    if (has('s-metrics')) { $('s-metrics').textContent = c.metrics; }
    if (has('s-resources')) { $('s-resources').textContent = c.resources; }
    if (c.off && has('tiles-note')) {
      // 原因由服务端给（/api/summary 的 storeDegradedReason），**不在这里猜**
      $('tiles-note').innerHTML = '<span class="err">存储层未就绪</span> —— '
        + '本次只计数不落盘。'
        + (ctx.d.storeDegradedReason
            ? '原因：' + esc(ctx.d.storeDegradedReason)
            : '原因见 <a href="self.html">自监控</a>。');
    }
  }

  /** 库状态：扩展自身的采集与落盘状况（ADR-6 第八节）。 */
  function libraryTables(ctx) {
    // 降级原因由服务端给。**不要在页面里猜** ——
    // 原来这里硬编码了一句"原因是数据目录不可写"，而实际可能是路径不是目录、
    // 权限被拒、文件被占用、建表失败，每一种的下一步都不一样。
    if (has('runtime')) {
      $('runtime').innerHTML = kv([
        ['实际端口', fmtNum(ctx.d.actualPort)],
        ['配置端口', fmtNum((ctx.d.config || {}).port)
          + (ctx.d.actualPort !== (ctx.d.config || {}).port ? '（冲突已退让）' : '')],
        ['启动于', new Date(ctx.d.startedAt || 0).toLocaleString()],
        ['已运行', Math.round((ctx.d.uptimeMs || 0) / 1000) + ' 秒'],
        ['agent', ctx.d.agentVersion || '不在 agent 里运行']
      ]) + (ctx.d.storeDegradedReason
        ? '<div class="note" style="margin-top:10px"><b>存储层已降级</b> —— '
          + esc(ctx.d.storeDegradedReason)
          + '<br>本次只计数不落盘：队列还在收，数据不会攒下来。'
          + '「生效配置」那一段仍然可用。</div>'
        : '');
    }
    if (has('queues')) {
      $('queues').innerHTML = ['traces', 'logs', 'metrics'].map(function (sig) {
        var q = ctx.queues[sig] || {};
        return '<tr><td>' + sig + '</td><td class="num">' + num(q.offered) + '</td>'
          + '<td class="num">' + num(q.drained) + '</td>'
          + '<td class="num">' + num(q.dropped) + '</td>'
          + '<td class="num">' + num(q.sinkErrors) + '</td>'
          + '<td class="num">' + num(q.backlog) + '</td>'
          + '<td class="num">' + num(q.batches) + '</td>'
          + '<td class="num">' + num(q.capacity) + '</td></tr>';
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
    refresh: runReload,
    esc: esc, clip: clip
  };
})();
