// Small single-page UI for the REST API. No build step, no external libraries.
// Login uses the session cookie set by POST /api/auth/login.

const RESOURCES = {
  admins:   { label: 'Admins',   singular: 'Admin',   extra: null },
  teachers: { label: 'Teachers', singular: 'Teacher', extra: { key: 'department',    label: 'Department' } },
  students: { label: 'Students', singular: 'Student', extra: { key: 'studentNumber', label: 'Student number' } },
};

// Mirrors the server-side RBAC rules (the server enforces them either way).
const ACCESS = {
  ADMIN:   ['admins', 'teachers', 'students'],
  TEACHER: ['teachers'],
  STUDENT: ['students'],
};

const $ = (id) => document.getElementById(id);

const state = { user: null, resource: null, rows: [], editingId: null };

const isAdmin = () => state.user && state.user.role === 'ADMIN';

/* ---------- API helper ---------- */

async function api(method, path, body) {
  const res = await fetch(path, {
    method,
    credentials: 'same-origin',
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });

  let data = null;
  const text = await res.text();
  if (text) {
    try { data = JSON.parse(text); } catch { /* non-JSON body */ }
  }

  if (!res.ok) {
    const err = new Error(describeError(data, res.status));
    err.status = res.status;
    throw err;
  }
  return data;
}

function describeError(data, status) {
  if (!data) return `Request failed (${status})`;
  let msg = data.message || data.error || `Request failed (${status})`;
  if (data.details && typeof data.details === 'object') {
    const parts = Object.entries(data.details).map(([field, m]) => `${field}: ${m}`);
    if (parts.length) msg += ' - ' + parts.join('; ');
  }
  return msg;
}

/* ---------- small UI helpers ---------- */

let toastTimer;
function toast(message) {
  const el = $('toast');
  el.textContent = message;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 3000);
}

function showError(el, message) {
  el.textContent = message;
  el.hidden = !message;
}

function cell(text, tag = 'td') {
  const c = document.createElement(tag);
  c.textContent = text ?? '';   // textContent: user data is never parsed as HTML
  return c;
}

/* ---------- views ---------- */

function showLogin() {
  state.user = null;
  $('appView').hidden = true;
  $('loginView').hidden = false;
  $('loginForm').reset();
  showError($('loginError'), '');
  $('loginForm').username.focus();
}

function showApp(user) {
  state.user = user;
  $('loginView').hidden = true;
  $('appView').hidden = false;
  $('whoami').textContent = `${user.fullName} (${user.role})`;

  const allowed = ACCESS[user.role] || [];
  const tabs = $('tabs');
  tabs.replaceChildren();
  allowed.forEach((key) => {
    const b = document.createElement('button');
    b.className = 'tab';
    b.type = 'button';
    b.textContent = RESOURCES[key].label;
    b.dataset.key = key;
    b.addEventListener('click', () => selectResource(key));
    tabs.appendChild(b);
  });
  // A single tab is pointless navigation, hide the bar.
  tabs.hidden = allowed.length < 2;

  selectResource(allowed[0]);
}

async function selectResource(key) {
  state.resource = key;
  document.querySelectorAll('.tab').forEach((t) =>
    t.setAttribute('aria-selected', String(t.dataset.key === key)));
  // Admins manage whole lists; teachers/students only see and edit their own profile.
  $('listTitle').textContent = isAdmin() ? RESOURCES[key].label : 'My profile';
  $('addBtn').textContent = `+ Add ${RESOURCES[key].singular.toLowerCase()}`;
  $('addBtn').hidden = !isAdmin();
  renderHead();
  await loadRows();
}

async function loadRows() {
  try {
    state.rows = isAdmin()
      ? await api('GET', `/api/${state.resource}`)
      : [await api('GET', `/api/${state.resource}/${state.user.id}`)];
    renderRows();
  } catch (e) {
    handleFailure(e);
  }
}

function renderHead() {
  const extra = RESOURCES[state.resource].extra;
  const tr = document.createElement('tr');
  ['ID', 'Username', 'Full name', 'Email'].forEach((h) => tr.appendChild(cell(h, 'th')));
  if (extra) tr.appendChild(cell(extra.label, 'th'));
  tr.appendChild(cell('', 'th'));
  $('thead').replaceChildren(tr);
}

function renderRows() {
  const extra = RESOURCES[state.resource].extra;
  const body = $('tbody');
  body.replaceChildren();

  state.rows.forEach((u) => {
    const tr = document.createElement('tr');
    tr.appendChild(cell(u.id));
    tr.appendChild(cell(u.username));
    tr.appendChild(cell(u.fullName));
    tr.appendChild(cell(u.email));
    if (extra) tr.appendChild(cell(u[extra.key]));

    const actions = cell('');
    actions.className = 'actions-cell';
    const edit = button('Edit', '', () => openForm(u));
    actions.appendChild(edit);
    if (isAdmin()) {
      actions.append(' ', button('Delete', 'danger', () => remove(u)));
    }
    tr.appendChild(actions);
    body.appendChild(tr);
  });

  $('empty').hidden = state.rows.length > 0;
}

function button(label, extraClass, onClick) {
  const b = document.createElement('button');
  b.type = 'button';
  b.className = `btn small ${extraClass}`.trim();
  b.textContent = label;
  b.addEventListener('click', onClick);
  return b;
}

/* ---------- create / edit ---------- */

function openForm(user) {
  const res = RESOURCES[state.resource];
  const form = $('userForm');
  form.reset();
  showError($('formError'), '');

  state.editingId = user ? user.id : null;
  $('dlgTitle').textContent = user ? `Edit ${res.singular.toLowerCase()}` : `Add ${res.singular.toLowerCase()}`;

  form.password.required = !user;
  $('pwLabel').textContent = user ? 'New password (leave blank to keep current)' : 'Password';

  $('extraWrap').hidden = !res.extra;
  if (res.extra) $('extraLabel').textContent = res.extra.label;

  // The username is the login name, so nobody can rename their own account.
  form.username.readOnly = !!user && user.id === state.user.id;

  if (user) {
    form.username.value = user.username;
    form.fullName.value = user.fullName;
    form.email.value = user.email;
    if (res.extra) form.extra.value = user[res.extra.key] ?? '';
  }
  $('dlg').showModal();
}

async function save(event) {
  event.preventDefault();
  const form = $('userForm');
  const res = RESOURCES[state.resource];

  const payload = {
    username: form.username.value.trim(),
    fullName: form.fullName.value.trim(),
    email: form.email.value.trim(),
  };
  if (form.password.value) payload.password = form.password.value;
  if (res.extra) payload[res.extra.key] = form.extra.value.trim() || null;

  const submit = form.querySelector('button[type="submit"]');
  submit.disabled = true;
  try {
    if (state.editingId == null) {
      await api('POST', `/api/${state.resource}`, payload);
      toast(`${res.singular} created`);
    } else {
      await api('PUT', `/api/${state.resource}/${state.editingId}`, payload);
      toast(`${res.singular} updated`);
    }
    $('dlg').close();
    await loadRows();
    if (state.editingId === state.user.id) {   // editing yourself: refresh the name in the header
      state.user = await api('GET', '/api/auth/me');
      $('whoami').textContent = `${state.user.fullName} (${state.user.role})`;
    }
  } catch (e) {
    if (e.status === 401) { $('dlg').close(); showLogin(); return; }
    showError($('formError'), e.message);
  } finally {
    submit.disabled = false;
  }
}

async function remove(user) {
  if (!confirm(`Delete "${user.username}"? This cannot be undone.`)) return;
  try {
    await api('DELETE', `/api/${state.resource}/${user.id}`);
    toast('Deleted');
    await loadRows();
  } catch (e) {
    handleFailure(e);
  }
}

function handleFailure(e) {
  if (e.status === 401) { showLogin(); return; }
  toast(e.message);
}

/* ---------- login / logout ---------- */

async function login(event) {
  event.preventDefault();
  const form = $('loginForm');
  const btn = form.querySelector('button[type="submit"]');
  btn.disabled = true;
  showError($('loginError'), '');
  try {
    const user = await api('POST', '/api/auth/login', {
      username: form.username.value.trim(),
      password: form.password.value,
    });
    showApp(user);
  } catch (e) {
    showError($('loginError'), e.status === 401 ? 'Invalid username or password' : e.message);
  } finally {
    btn.disabled = false;
  }
}

async function logout() {
  try { await api('POST', '/api/auth/logout'); } catch { /* session may already be gone */ }
  showLogin();
}

/* ---------- start ---------- */

$('loginForm').addEventListener('submit', login);
$('logoutBtn').addEventListener('click', logout);
$('addBtn').addEventListener('click', () => openForm(null));
$('userForm').addEventListener('submit', save);
$('cancelBtn').addEventListener('click', () => $('dlg').close());

// Restore an existing session (page refresh) or fall back to the login form.
api('GET', '/api/auth/me').then(showApp).catch(showLogin);
