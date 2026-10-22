/* Askbase 前端逻辑
   纯原生 JS，无构建步骤。所有请求同源，不需要 CORS。

   多轮对话的设计：服务端不存会话，历史由前端带上。
   每次提问把之前几轮的「问题 + SQL」一起发过去，服务端据此理解追问。
   只传问题和 SQL、不传结果集——结果集可能上千行，会把 prompt 撑爆。 */

const TOKEN_KEY = 'askbase.token';
const EXAMPLES = [
  '各专业平均薪资是多少',
  '每个学院平均起薪最高的专业是哪个',
  '2024年各专业的就业率排名',
  '投递超过20次但一个offer都没拿到的学生有多少人',
  '各投递渠道的录用率分别是多少'
];
const FOLLOW_UP_HINT = '追问示例：只看2024届的 · 排除实习岗位 · 哪个最多？';

const $ = (id) => document.getElementById(id);

/* 对话状态。history 只存 {question, sql}，是发给服务端的东西 */
const state = {
  history: [],
  charts: [],
};

/* ---------------- 会话 ---------------- */

const getToken = () => sessionStorage.getItem(TOKEN_KEY);
const setToken = (t) => sessionStorage.setItem(TOKEN_KEY, t);
const clearToken = () => sessionStorage.removeItem(TOKEN_KEY);

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
  syncNewChatButton();
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

  if (resp.status === 401) throw new Error('UNAUTHORIZED');

  let payload;
  try {
    payload = await resp.json();
  } catch {
    throw new Error('服务返回了非 JSON 内容（HTTP ' + resp.status + '）');
  }
  if (payload.code !== 0) throw new Error(payload.msg || '请求失败');
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
      body: { username: $('username').value.trim(), password: $('password').value }
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
  resetConversation();
  showLogin();
});

$('new-chat').addEventListener('click', resetConversation);

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
  $('status').hidden = false;

  const steps = ['正在理解问题…', '正在召回相关表…', '正在生成 SQL…', '正在校验与执行…'];
  let step = 0;
  $('status-text').textContent = steps[0];
  const timer = setInterval(() => {
    step = Math.min(step + 1, steps.length - 1);
    $('status-text').textContent = steps[step];
  }, 700);

  // 先插入一个空的轮次，让用户立刻看到自己的问题
  const turn = createTurn(question);
  $('thread').appendChild(turn.el);
  turn.el.scrollIntoView({ behavior: 'smooth', block: 'start' });

  try {
    const data = await request('/api/query', {
      method: 'POST',
      body: { question, history: state.history }
    });

    // 只有成功的轮次才进历史——失败的 SQL 不该影响后续理解
    state.history.push({ question, sql: data.sql });
    fillTurn(turn, data);
    syncNewChatButton();
    $('retriever-badge').textContent = data.retriever || '';
  } catch (err) {
    if (err.message === 'UNAUTHORIZED') {
      clearToken();
      showLogin('登录已过期，请重新登录');
      return;
    }
    showTurnError(turn, err.message);
  } finally {
    clearInterval(timer);
    $('status').hidden = true;
    btn.disabled = false;
    btn.textContent = '提问';
    $('question').value = '';
    // preventScroll 必须加：默认的 focus() 会把页面滚回输入框，
    // 用户刚拿到答案就被弹回顶部，看不到结果
    $('question').focus({ preventScroll: true });
  }
}

function resetConversation() {
  state.history = [];
  state.charts.forEach((c) => c.destroy());
  state.charts = [];
  $('thread').innerHTML = '';
  $('retriever-badge').textContent = '';
  syncNewChatButton();
  $('question').value = '';
  $('question').focus({ preventScroll: true });
}

function syncNewChatButton() {
  $('new-chat').hidden = state.history.length === 0;
}

/* ---------------- 渲染一轮对话 ---------------- */

function createTurn(question) {
  const tpl = $('turn-tpl').content.cloneNode(true);
  const el = tpl.querySelector('.turn');
  tpl.querySelector('.turn-q-text').textContent = question;
  tpl.querySelector('.turn-body').hidden = true;
  return {
    el,
    body: tpl.querySelector('.turn-body'),
    error: tpl.querySelector('.turn-error'),
    explanation: tpl.querySelector('.explanation'),
    meta: tpl.querySelector('.meta'),
    chartWrap: tpl.querySelector('.chart-wrap'),
    canvas: tpl.querySelector('canvas'),
    thead: tpl.querySelector('thead'),
    tbody: tpl.querySelector('tbody'),
    truncated: tpl.querySelector('.truncated-note'),
    sql: tpl.querySelector('pre'),
    attempts: tpl.querySelector('.attempts'),
  };
}

function showTurnError(turn, message) {
  turn.body.hidden = true;
  turn.error.textContent = message;
  turn.error.hidden = false;
}

function fillTurn(turn, data) {
  turn.body.hidden = false;

  turn.explanation.textContent = data.explanation || '（模型未给出解释）';

  const meta = [];
  meta.push('召回 <code>' + (data.recalledTables || []).join(', ') + '</code>');
  meta.push('策略 <code>' + data.retriever + '</code>');
  meta.push((data.totalMs / 1000).toFixed(2) + 's');
  meta.push('token ' + data.promptTokens + ' + ' + data.completionTokens);
  if (data.historyTurns > 0) {
    meta.push('<span style="color:#2563eb">带 ' + data.historyTurns + ' 轮上下文</span>');
  }
  if (data.attempts && data.attempts.length > 1) {
    meta.push('<span style="color:#b45309">重试 ' + (data.attempts.length - 1) + ' 次后成功</span>');
  }
  turn.meta.innerHTML = meta.map((m) => '<span>' + m + '</span>').join('');

  renderTable(turn, data.columns, data.rows);
  renderChart(turn, data.chartType, data.columns, data.rows);

  turn.sql.textContent = data.sql;

  turn.attempts.innerHTML = '';
  if (data.attempts && data.attempts.length > 1) {
    data.attempts.forEach((a) => {
      const div = document.createElement('div');
      div.innerHTML = '第 ' + a.index + ' 轮：' + (a.failure
        ? '<span class="fail">✗ ' + escapeHtml(a.failure) + '</span>'
        : '✓ 返回 ' + a.rowCount + ' 行');
      turn.attempts.appendChild(div);
    });
  }

  if (data.truncated) {
    turn.truncated.textContent = '结果超过上限，只展示前 ' + data.rowCount + ' 行。';
    turn.truncated.hidden = false;
  } else {
    turn.truncated.hidden = true;
  }
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

function renderTable(turn, columns, rows) {
  const numericCols = columns.map((c) => isNumericColumn(rows, c));

  turn.thead.innerHTML = '<tr>' + columns
    .map((c, i) => '<th class="' + (numericCols[i] ? 'num' : '') + '">' + escapeHtml(c) + '</th>')
    .join('') + '</tr>';

  turn.tbody.innerHTML = rows
    .map((row) => '<tr>' + columns
      .map((c, i) => '<td class="' + (numericCols[i] ? 'num' : '') + '">' +
        escapeHtml(formatCell(row[c], numericCols[i])) + '</td>')
      .join('') + '</tr>')
    .join('');
}

function renderChart(turn, type, columns, rows) {
  const canChart = rows.length >= 2 && rows.length <= 20 && columns.length >= 2 &&
    type !== 'table' && isNumericColumn(rows, columns[1]) &&
    typeof Chart !== 'undefined';

  if (!canChart) {
    turn.chartWrap.hidden = true;
    return;
  }
  turn.chartWrap.hidden = false;

  const labels = rows.map((r) => String(r[columns[0]]));
  const values = rows.map((r) => Number(r[columns[1]]) || 0);
  const isPie = type === 'pie';
  const palette = ['#2563eb', '#0ea5e9', '#14b8a6', '#f59e0b', '#8b5cf6', '#ec4899'];

  const chart = new Chart(turn.canvas, {
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
        legend: { display: isPie, position: 'right', labels: { boxWidth: 10, font: { size: 12 } } }
      },
      scales: isPie ? {} : {
        y: { beginAtZero: true, grid: { color: '#f0efed' }, ticks: { font: { size: 11 }, color: '#78716c' } },
        x: { grid: { display: false }, ticks: { font: { size: 11 }, color: '#57534e', autoSkip: false, maxRotation: 40 } }
      }
    }
  });
  state.charts.push(chart);
}

/* ---------------- 示例 ---------------- */

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

  // 追问提示：让用户知道可以接着问
  const hint = document.createElement('span');
  hint.className = 'chip-hint';
  hint.textContent = FOLLOW_UP_HINT;
  box.appendChild(hint);
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
