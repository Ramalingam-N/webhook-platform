const API_BASE = `http://${window.location.hostname}:8083`;

/* ---- DOM ---- */
const $ = (s) => document.querySelector(s);
const dom = {
    apiKey:    $('#apiKey'),
    syncForm:  $('#syncForm'),
    endpointForm: $('#endpointForm'),
    reloadBtn: $('#reloadBtn'),
    tbody:     $('#dlqTableBody'),
    empty:     $('#emptyState'),
    toast:     $('#toast'),
    toastIcon: $('#toastIcon'),
    toastMsg:  $('#toastMsg'),
};

const ICON = {
    ok:  '<svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7"/></svg>',
    err: '<svg fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"/></svg>',
};

/* ---- Utils ---- */
const esc = (v = '') => String(v).replace(/[&<>"']/g, (c) =>
    ({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c]));

const authHeaders = () => ({ 'X-Admin-Api-Key': dom.apiKey.value });

/* ---- Toast (animated, self-dismiss) ---- */
let toastTimer;
const showToast = (message, isError = false) => {
    dom.toastIcon.innerHTML = isError ? ICON.err : ICON.ok;
    dom.toastMsg.textContent = message;
    dom.toast.className = `glass ${isError ? 'toast--err' : 'toast--ok'} show`;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => dom.toast.classList.remove('show'), 4000);
};

/* ---- Skeleton loader ---- */
const renderSkeleton = (rows = 4) => {
    dom.empty.style.display = 'none';
    const frag = document.createDocumentFragment();
    for (let i = 0; i < rows; i += 1) {
        const tr = document.createElement('tr');
        tr.style.animation = 'none';
        tr.style.opacity = '1';
        tr.style.transform = 'none';
        tr.innerHTML = Array.from({ length: 6 }, (_, c) =>
            `<td><span class="skeleton" style="display:block;width:${[60,85,85,75,100,55][c]}%"></span></td>`).join('');
        frag.appendChild(tr);
    }
    dom.tbody.replaceChildren(frag);
};

/* ---- Fetch with /dead-letters -> /dlq fallback ---- */
const fetchWithFallback = async (primary, fallback, options) => {
    let res = await fetch(`${API_BASE}${primary}`, options);
    if (res.status === 404) res = await fetch(`${API_BASE}${fallback}`, options);
    if (!res.ok) {
        const errorText = await res.text().catch(() => '');
        console.error(`Server error on ${res.url} [Status ${res.status}]:`, errorText);
    }
    return res;
};

/* ---- Register endpoint ---- */
const registerEndpoint = async (e) => {
    e.preventDefault();
    const tenantId = $('#tenantId').value.trim();
    const url = $('#targetUrl').value.trim();
    try {
        const res = await fetch(`${API_BASE}/v1/admin/endpoints`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', ...authHeaders() },
            body: JSON.stringify({ tenantId, url }),
        });
        if (res.status === 403) throw new Error('Unauthorized: Invalid Admin API Key');
        if (!res.ok) throw new Error('Failed to register endpoint');

        const { secret } = await res.json();
        window.prompt("Save this signing secret now — it won't be shown again:", secret);
        dom.endpointForm.reset();
    } catch (err) {
        showToast(err.message, true);
    }
};

/* ---- Row template ---- */
const rowHtml = (record) => {
    const replayed = record.replayed || record.status === 'REPLAYED';
    const status = replayed
        ? '<span class="status status--replayed"><span class="dot"></span>Replayed</span>'
        : '<span class="status status--failed"><span class="dot"></span>Failed</span>';
    const action = replayed
        ? '<span class="archived">Archived</span>'
        : `<button class="btn btn--glass" data-action="replay" data-id="${esc(record.id)}">Replay Event</button>`;
    const payload = typeof record.payload === 'object'
        ? JSON.stringify(record.payload) : record.payload;

    return `
        <td>${status}</td>
        <td class="mono mono-hi">${esc(record.tenantId)}</td>
        <td class="mono">${esc(record.eventId || record.id)}</td>
        <td>${esc(record.originalTopic || 'webhook-events')}</td>
        <td class="payload"><code>${esc(payload)}</code></td>
        <td class="right">${action}</td>`;
};

/* ---- Fetch + render DLQ ---- */
const fetchDlq = async () => {
    renderSkeleton();
    try {
        const res = await fetchWithFallback('/v1/admin/dlq', '/v1/admin/dead-letters', { headers: authHeaders() });
        if (res.status === 403) throw new Error('Unauthorized: Invalid Admin API Key');
        if (!res.ok) throw new Error('Failed to load Dead Letters');

        const records = await res.json();
        if (!records || records.length === 0) {
            dom.tbody.replaceChildren();
            dom.empty.style.display = 'block';
            return;
        }
        dom.empty.style.display = 'none';

        const frag = document.createDocumentFragment();
        records.forEach((record, i) => {
            const tr = document.createElement('tr');
            tr.style.animationDelay = `${i * 45}ms`;
            tr.innerHTML = rowHtml(record);
            frag.appendChild(tr);
        });
        dom.tbody.replaceChildren(frag);
    } catch (err) {
        dom.tbody.replaceChildren();
        dom.empty.style.display = 'none';
        showToast(err.message, true);
    }
};

/* ---- Replay ---- */
const replayDlq = async (id) => {
    try {
        const res = await fetchWithFallback(`/v1/admin/dlq/${id}/replay`, `/v1/admin/dlq/${id}/replay`,
            { method: 'POST', headers: authHeaders() });
        if (res.status === 403) throw new Error('Unauthorized: Invalid Admin API Key');
        if (res.status === 409) throw new Error('Record already replayed');
        if (!res.ok) throw new Error('Replay failed');

        showToast('Event re-injected into fast-lane delivery topic');
        fetchDlq();
    } catch (err) {
        showToast(err.message, true);
    }
};

dom.syncForm.addEventListener('submit', (e) => {
    e.preventDefault();
    fetchDlq();
    showToast('Data refreshed');
});
dom.endpointForm.addEventListener('submit', registerEndpoint);
dom.reloadBtn.addEventListener('click', fetchDlq);
dom.tbody.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-action="replay"]');
    if (btn) replayDlq(btn.dataset.id);
});

document.addEventListener('DOMContentLoaded', fetchDlq);