/* Askbase 前端逻辑
   纯原生 JS，无构建步骤。所有请求同源，不需要 CORS。 */

const TOKEN_KEY = 'askbase.token';
const HISTORY_KEY = 'askbase.history';
const MAX_HISTORY = 6;

const EXAMPLES = [
  '各专业平均薪资是多少',
  '每个学院平均起薪最高的专业是哪个',
  '2024年各专业的就业率排名',
  '投递超过20次但一个offer都没拿到的学生有多少人',
  '各投递渠道的录用率分别是多少'
];

const $ = (id) => document.getElementById(id);
let chartInstance = null;

/* ---------------- 会话 ---------------- */

function getToken() {
  return sessionStorage.getItem(TOKEN_KEY);
}

function setToken(t) {
  sessionStorage.setItem(TOKEN_KEY, t);
}

function clearToken() {
  sessionStorage.removeItem(TOKEN_KEY);
}

function showLogin(message) {
  $('app-view').hidden = true;
  $('login-view').hidden = false;
  if (message) {
    const el = $('login-error');
    el.textContent = message;
    el.hidden = false;
  }
}

function showApp() {
  $('login-view').hidden = true;
  $('app-view').hidden = false;
  renderExamples();
  renderHistory();
}

/* ---------------- 请求 ---------------- */

async function request(path, { method = 'GET', body, auth = true } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (auth) {
    const token = getToken();
    if (!token) throw new Error('UNAUTHORIZED');
    headers['Authorization'] = 'Bearer ' + token;
  }

  const resp = await fetch(path, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined
  });

  if (resp.status === 401) {
    throw new Error('UNAUTHORIZED');
  }

  let payload;
  try {
    payload = await resp.json();
  } catch {
    throw new Error('服务返回了非 JSON 内容（HTTP ' + resp.status + '）');
  }

  if (payload.code !== 0) {
    throw new Error(payload.msg || '请求失败');
  }
  return payload.data;
}

/* ---------------- 登录 ---------------- */

$('login-form').addEventListener('submit', async (e) => {
  e.preventDefault();
  const btn = e.target.querySelector('button');
  const errEl = $('login-error');
  errEl.hidden = true;
  btn.disabled = true;
  btn.textContent = '登录中…';

  try {
    const data = await request('/api/auth/login', {
      method: 'POST',
      auth: false,
      body: {
        username: $('username').value.trim(),
        password: $('password').value
      }
    });
    setToken(data.token);
    showApp();
  } catch (err) {
    errEl.textContent = err.message === 'UNAUTHORIZED' ? '用户名或密码错误' : err.message;
    errEl.hidden = false;
  } finally {
    btn.disabled = false;
    btn.textContent = '登录';
  }
});

$('logout').addEventListener('click', () => {
  clearToken();
  resetResult();
  showLogin();
});

/* ---------------- 提问 ---------------- */

$('ask-form').addEventListener('submit', (e) => {
  e.preventDefault();
  const q = $('question').value.trim();
  if (q) ask(q);
});

$('question').addEventListener('keydown', (e) => {
  // Cmd/Ctrl + Enter 提交，避免和中文输入法的回车冲突
  if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') {
    e.preventDefault();
    $('ask-form').requestSubmit();
  }
});

async function ask(question) {
  const btn = $('ask-btn');
  btn.disabled = true;
  btn.textContent = '处理中…';
  $('error').hidden = true;
  $('result').hidden = true;
  $('history-wrap').hidden = true;
  $('status').hidden = false;

  const steps = ['正在理解问题…', '正在召回相关表…', '正在生成 SQL…', '正在校验与执行…'];
  let step = 0;
  $('status-text').textContent = steps[0];
  const timer = setInterval(() => {
    step = Math.min(step + 1, steps.length - 1);
    $('status-text').textContent = steps[step];
  }, 700);

  try {
    const data = await request('/api/query', { method: 'POST', body: { question } });
    renderResult(data);
    pushHistory(question, data.totalMs);
  } catch (err) {
    if (err.message === 'UNAUTHORIZED') {
      clearToken();
      showLogin('登录已过期，请重新登录');
      return;
    }
    const el = $('error');
    el.textContent = err.message;
    el.hidden = false;
    $('history-wrap').hidden = false;
  } finally {
    clearInterval(timer);
    $('status').hidden = true;
    btn.disabled = false;
    btn.textContent = '提问';
  }
}

/* ---------------- 渲染结果 ---------------- */

function resetResult() {
  $('result').hidden = true;
  $('error').hidden = true;
  $('status').hidden = true;
  $('history-wrap').hidden = true;
  if (chartInstance) {
    chartInstance.destroy();
    chartInstance = null;
  }
}

function renderResult(data) {
  $('result').hidden = false;
  $('retriever-badge').textContent = data.retriever || '';

  $('explanation').textContent = data.explanation || '（模型未给出解释）';

  const metaParts = [];
  metaParts.push('召回 <code>' + (data.recalledTables || []).join(', ') + '</code>');
  metaParts.push('召回策略 <code>' + data.retriever + '</code>');
  metaParts.push('耗时 ' + (data.totalMs / 1000).toFixed(2) + 's');
  metaParts.push('token ' + data.promptTokens + ' + ' + data.completionTokens);
  if (data.attempts && data.attempts.length > 1) {
    metaParts.push('<span style="color:#b45309">重试 ' + (data.attempts.length - 1) + ' 次后成功</span>');
  }
  $('meta').innerHTML = metaParts.map((m) => '<span>' + m + '</span>').join('');

  renderTable(data.columns, data.rows);
  renderChart(data.chartType, data.columns, data.rows);

  $('sql').textContent = data.sql;

  const attemptsEl = $('attempts');
  attemptsEl.innerHTML = '';
  if (data.attempts && data.attempts.length > 1) {
    data.attempts.forEach((a) => {
      const div = document.createElement('div');
      const status = a.failure
        ? '<span class="fail">✗ ' + escapeHtml(a.failure) + '</span>'
        : '✓ 返回 ' + a.rowCount + ' 行';
      div.innerHTML = '第 ' + a.index + ' 轮：' + status;
      attemptsEl.appendChild(div);
    });
  }

  const noteEl = $('truncated-note');
  if (data.truncated) {
    noteEl.textContent = '结果超过 ' + data.rowCount + ' 行，已截断，只展示前 ' + data.rowCount + ' 行。';
    noteEl.hidden = false;
  } else {
    noteEl.hidden = true;
  }

  $('history-wrap').hidden = false;
}

function isNumericColumn(rows, col) {
  if (!rows.length) return false;
  return rows.every((r) => typeof r[col] === 'number' || r[col] === null);
}

function formatCell(v, numeric) {
  if (v === null || v === undefined) return '—';
  if (numeric && typeof v === 'number') {
    return Number.isInteger(v)
      ? v.toLocaleString('zh-CN')
      : v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  }
  return String(v);
}

function renderTable(columns, rows) {
  const thead = $('result-table').querySelector('thead');
  const tbody = $('result-table').querySelector('tbody');

  const numericCols = columns.map((c) => isNumericColumn(rows, c));

  thead.innerHTML = '<tr>' + columns
    .map((c, i) => '<th class="' + (numericCols[i] ? 'num' : '') + '">' + escapeHtml(c) + '</th>')
    .join('') + '</tr>';

  tbody.innerHTML = rows
    .map((row) => '<tr>' + columns
      .map((c, i) => '<td class="' + (numericCols[i] ? 'num' : '') + '">' +
        escapeHtml(formatCell(row[c], numericCols[i])) + '</td>')
      .join('') + '</tr>')
    .join('');
}

function renderChart(type, columns, rows) {
  const wrap = $('chart-wrap');
  if (chartInstance) {
    chartInstance.destroy();
    chartInstance = null;
  }

  // 图表只对「第一列是类别、第二列是数值」的结果有意义
  const canChart = rows.length >= 2 && rows.length <= 20 && columns.length >= 2 &&
    type !== 'table' && isNumericColumn(rows, columns[1]) &&
    typeof Chart !== 'undefined';

  if (!canChart) {
    wrap.hidden = true;
    return;
  }
  wrap.hidden = false;

  const labels = rows.map((r) => String(r[columns[0]]));
  const values = rows.map((r) => Number(r[columns[1]]) || 0);
  const palette = ['#2563eb', '#0ea5e9', '#14b8a6', '#f59e0b', '#8b5cf6', '#ec4899'];

  const isPie = type === 'pie';
  const config = {
    type: isPie ? 'doughnut' : type,
    data: {
      labels,
      datasets: [{
        label: columns[1],
        data: values,
        backgroundColor: isPie ? palette : 'rgba(37, 99, 235, 0.75)',
        borderColor: isPie ? '#fff' : '#2563eb',
        borderWidth: isPie ? 2 : 0,
        borderRadius: isPie ? 0 : 4,
        tension: 0.3
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { display: isPie, position: 'right', labels: { boxWidth: 10, font: { size: 12 } } },
        tooltip: { enabled: true }
      },
      scales: isPie ? {} : {
        y: { beginAtZero: true, grid: { color: '#f0efed' }, ticks: { font: { size: 11 }, color: '#78716c' } },
        x: { grid: { display: false }, ticks: { font: { size: 11 }, color: '#57534e', autoSkip: false, maxRotation: 40 } }
      }
    }
  };

  chartInstance = new Chart($('chart'), config);
}

/* ---------------- 示例与历史 ---------------- */

function renderExamples() {
  const box = $('examples');
  box.querySelectorAll('.chip').forEach((c) => c.remove());
  EXAMPLES.forEach((q) => {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = 'chip';
    chip.textContent = q;
    chip.addEventListener('click', () => {
      $('question').value = q;
      $('question').focus();
    });
    box.appendChild(chip);
  });
}

function getHistory() {
  try {
    return JSON.parse(sessionStorage.getItem(HISTORY_KEY) || '[]');
  } catch {
    return [];
  }
}

function pushHistory(question, totalMs) {
  const list = getHistory().filter((h) => h.q !== question);
  list.unshift({ q: question, ms: Math.round(totalMs) });
  sessionStorage.setItem(HISTORY_KEY, JSON.stringify(list.slice(0, MAX_HISTORY)));
  renderHistory();
}

function renderHistory() {
  const list = getHistory();
  const wrap = $('history-wrap');
  const ul = $('history');
  ul.innerHTML = '';
  if (!list.length) {
    wrap.hidden = true;
    return;
  }
  wrap.hidden = false;
  list.forEach((h) => {
    const li = document.createElement('li');
    li.innerHTML = '<span>' + escapeHtml(h.q) + '</span><span>' + h.ms + 'ms</span>';
    li.addEventListener('click', () => {
      $('question').value = h.q;
      ask(h.q);
    });
    ul.appendChild(li);
  });
}

/* ---------------- 工具 ---------------- */

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/* ---------------- 启动 ---------------- */

async function boot() {
  const token = getToken();
  if (!token) {
    showLogin();
    return;
  }
  try {
    await request('/api/auth/me');
    showApp();
  } catch (err) {
    clearToken();
    showLogin(err.message === 'UNAUTHORIZED' ? '登录已过期，请重新登录' : undefined);
  }
}

boot();
