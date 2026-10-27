/**
 * ExamSeat AI — Frontend Single Page Application Controller
 */

// Global State
const state = {
    rooms: [],
    students: [],
    allocations: [],
    currentRoomId: null,
    strictness: 'BASIC',
    deptColors: {
        'Computer Science': { bg: 'rgba(99, 102, 241, 0.15)', border: '#6366f1', text: '#818cf8', dot: '#6366f1' },
        'Electronics': { bg: 'rgba(6, 182, 212, 0.15)', border: '#06b6d4', text: '#22d3ee', dot: '#06b6d4' },
        'Mechanical': { bg: 'rgba(245, 158, 11, 0.15)', border: '#f59e0b', text: '#fbbf24', dot: '#f59e0b' },
        'Civil': { bg: 'rgba(16, 185, 129, 0.15)', border: '#10b981', text: '#34d399', dot: '#10b981' },
        'Information Tech': { bg: 'rgba(236, 72, 153, 0.15)', border: '#ec4899', text: '#f472b6', dot: '#ec4899' }
    },
    colorPalette: [
        { bg: 'rgba(99, 102, 241, 0.15)', border: '#6366f1', text: '#818cf8', dot: '#6366f1' },
        { bg: 'rgba(6, 182, 212, 0.15)', border: '#06b6d4', text: '#22d3ee', dot: '#06b6d4' },
        { bg: 'rgba(245, 158, 11, 0.15)', border: '#f59e0b', text: '#fbbf24', dot: '#f59e0b' },
        { bg: 'rgba(16, 185, 129, 0.15)', border: '#10b981', text: '#34d399', dot: '#10b981' },
        { bg: 'rgba(236, 72, 153, 0.15)', border: '#ec4899', text: '#f472b6', dot: '#ec4899' },
        { bg: 'rgba(168, 85, 247, 0.15)', border: '#a855f7', text: '#c084fc', dot: '#a855f7' }
    ]
};

// -------------------------------------------------------------
// Auth-aware fetch (works in open mode and enforced security mode)
// -------------------------------------------------------------
let authHeader = null; // e.g. "Basic dXNlcjpwYXNz" — set after login

function apiFetch(url, options = {}) {
    options.credentials = 'same-origin';
    if (authHeader) {
        options.headers = Object.assign({}, options.headers, { 'Authorization': authHeader });
    }
    return fetch(url, options).then(response => {
        if (response.status === 401) {
            openLoginModal();
            throw new Error('Authentication required (401) — please sign in.');
        }
        return response;
    });
}

function openLoginModal() {
    const modal = document.getElementById('login-modal');
    if (modal) modal.style.display = 'flex';
}

function closeLoginModal() {
    const modal = document.getElementById('login-modal');
    if (modal) modal.style.display = 'none';
}

function submitLogin() {
    const username = (document.getElementById('login-username')?.value || '').trim();
    const password = document.getElementById('login-password')?.value || '';
    if (!username || !password) {
        showToast('Please enter username and password', 'error');
        return;
    }
    authHeader = 'Basic ' + btoa(username + ':' + password);
    apiFetch('/api/auth/me').then(async r => {
        if (r.ok) {
            closeLoginModal();
            showToast('Signed in as ' + username, 'success');
            refreshAllData();
        } else {
            authHeader = null;
            showToast('Sign-in failed — check your credentials', 'error');
        }
    }).catch(() => {});
}

// Inline onclick handlers in index.html need globals.
window.closeLoginModal = closeLoginModal;
window.submitLogin = submitLogin;

// -------------------------------------------------------------
// Initialization
// -------------------------------------------------------------
document.addEventListener('DOMContentLoaded', () => {
    initTabs();
    initStrictnessSelector();
    initForms();
    initDropzones();
    initGlobalActions();
    initExportLinks();
    refreshAllData();
});

// -------------------------------------------------------------
// Export links — scope downloads by the optional subject-code filter
// -------------------------------------------------------------
function initExportLinks() {
    const subjectInput = document.getElementById('gen-subject-code');
    if (subjectInput) {
        subjectInput.addEventListener('input', updateExportLinks);
    }
    // Sign in on Enter inside the login modal.
    const loginPassword = document.getElementById('login-password');
    if (loginPassword) {
        loginPassword.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') submitLogin();
        });
    }
    updateExportLinks();
}

function updateExportLinks() {
    const subjectInput = document.getElementById('gen-subject-code');
    const subjectCode = subjectInput ? subjectInput.value.trim() : '';
    document.querySelectorAll('a[data-export-path]').forEach(link => {
        const base = link.getAttribute('data-export-path');
        link.href = subjectCode
            ? base + '?subjectCode=' + encodeURIComponent(subjectCode)
            : base;
    });
}

// -------------------------------------------------------------
// Tab Switching Navigation
// -------------------------------------------------------------
function initTabs() {
    const tabBtns = document.querySelectorAll('.tab-btn');
    tabBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            tabBtns.forEach(b => b.classList.remove('active'));
            document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));

            btn.classList.add('active');
            const targetId = btn.getAttribute('data-tab');
            const targetTab = document.getElementById(targetId);
            if (targetTab) {
                targetTab.classList.add('active');
            }

            if (targetId === 'tab-matrix') {
                renderVisualMatrix();
            }
        });
    });
}

// -------------------------------------------------------------
// Strictness Selector
// -------------------------------------------------------------
function initStrictnessSelector() {
    const cards = [
        { card: 'card-strictness-basic', radio: 'radio-strictness-basic', level: 'BASIC' },
        { card: 'card-strictness-intermediate', radio: 'radio-strictness-intermediate', level: 'INTERMEDIATE' },
        { card: 'card-strictness-advanced', radio: 'radio-strictness-advanced', level: 'ADVANCED' }
    ];
    cards.forEach(entry => {
        const cardEl = document.getElementById(entry.card);
        const radioEl = document.getElementById(entry.radio);
        if (!cardEl || !radioEl) return;
        cardEl.addEventListener('click', () => {
            radioEl.checked = true;
            cards.forEach(other => {
                const otherEl = document.getElementById(other.card);
                if (otherEl) otherEl.classList.remove('active');
            });
            cardEl.classList.add('active');
            state.strictness = entry.level;
        });
    });
}

// -------------------------------------------------------------
// Forms & Event Handlers
// -------------------------------------------------------------
function initForms() {
    // Run Seating Arrangement Engine
    const btnRunSeating = document.getElementById('btn-run-seating');
    btnRunSeating.addEventListener('click', runSeatingArrangement);

    // Create Room Form
    const formAddRoom = document.getElementById('form-add-room');
    formAddRoom.addEventListener('submit', async (e) => {
        e.preventDefault();
        const baseName = document.getElementById('room-hall-name').value.trim();
        const rows = parseInt(document.getElementById('room-rows').value, 10);
        const cols = parseInt(document.getElementById('room-cols').value, 10);
        const benchType = document.getElementById('room-bench-type').value;
        const multiplier = parseInt(document.getElementById('room-count-multiplier').value, 10) || 1;

        try {
            for (let i = 1; i <= multiplier; i++) {
                const hallName = multiplier > 1 ? `${baseName} (Room ${i})` : baseName;
                await apiFetch('/api/rooms', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ hallName, rows, columns: cols, benchType })
                });
            }
            showToast(`Created ${multiplier} exam hall(s) successfully!`, 'success');
            formAddRoom.reset();
            document.getElementById('room-count-multiplier').value = '1';
            document.getElementById('room-rows').value = '5';
            document.getElementById('room-cols').value = '4';
            await loadRooms();
            await loadStats();
        } catch (err) {
            showToast('Failed to create hall: ' + err.message, 'error');
        }
    });

    // Create Student Form
    const formAddStudent = document.getElementById('form-add-student');
    formAddStudent.addEventListener('submit', async (e) => {
        e.preventDefault();
        const student = {
            name: document.getElementById('student-name').value.trim(),
            rollNo: document.getElementById('student-roll').value.trim(),
            department: document.getElementById('student-dept').value.trim(),
            course: document.getElementById('student-course').value.trim(),
            yearSession: document.getElementById('student-year').value.trim(),
            subjectCode: document.getElementById('student-subject').value.trim()
        };

        try {
            const res = await apiFetch('/api/students', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(student)
            });
            if (!res.ok) throw new Error(await res.text());
            showToast(`Student ${student.name} (${student.rollNo}) registered!`, 'success');
            document.getElementById('student-name').value = '';
            document.getElementById('student-roll').value = '';
            await loadStudents();
            await loadStats();
        } catch (err) {
            showToast('Failed to save student: ' + err.message, 'error');
        }
    });

    // Student Search Input
    const searchInput = document.getElementById('student-search-input');
    searchInput.addEventListener('input', () => {
        const query = searchInput.value.toLowerCase();
        renderStudentsTable(state.students.filter(s => 
            (s.name && s.name.toLowerCase().includes(query)) ||
            (s.rollNo && s.rollNo.toLowerCase().includes(query)) ||
            (s.department && s.department.toLowerCase().includes(query)) ||
            (s.yearSession && s.yearSession.toLowerCase().includes(query))
        ));
    });

    // Matrix Room Select Dropdown
    const selectMatrixRoom = document.getElementById('matrix-room-select');
    selectMatrixRoom.addEventListener('change', () => {
        state.currentRoomId = selectMatrixRoom.value;
        renderVisualMatrix();
    });

    // Refresh Rooms Button
    document.getElementById('btn-refresh-rooms').addEventListener('click', loadRooms);
}

// -------------------------------------------------------------
// Excel Dropzones
// -------------------------------------------------------------
function initDropzones() {
    setupDropzone('dropzone-rooms', 'file-upload-rooms', '/api/rooms/import', async (result) => {
        showToast(`Imported ${result.imported} rooms! (Total: ${result.totalRows})`, 'success');
        await loadRooms();
        await loadStats();
    });

    setupDropzone('dropzone-students', 'file-upload-students', '/api/students/import', async (result) => {
        showToast(`Imported ${result.imported} students! (Total: ${result.totalRows})`, 'success');
        await loadStudents();
        await loadStats();
    });
}

function setupDropzone(dropzoneId, fileInputId, uploadUrl, onSuccess) {
    const dropzone = document.getElementById(dropzoneId);
    const fileInput = document.getElementById(fileInputId);

    dropzone.addEventListener('click', () => fileInput.click());

    dropzone.addEventListener('dragover', (e) => {
        e.preventDefault();
        dropzone.classList.add('dragover');
    });

    dropzone.addEventListener('dragleave', () => {
        dropzone.classList.remove('dragover');
    });

    dropzone.addEventListener('drop', (e) => {
        e.preventDefault();
        dropzone.classList.remove('dragover');
        if (e.dataTransfer.files.length) {
            handleFileUpload(e.dataTransfer.files[0], uploadUrl, onSuccess);
        }
    });

    fileInput.addEventListener('change', () => {
        if (fileInput.files.length) {
            handleFileUpload(fileInput.files[0], uploadUrl, onSuccess);
        }
    });
}

async function handleFileUpload(file, uploadUrl, onSuccess) {
    const formData = new FormData();
    formData.append('file', file);

    try {
        showToast('Uploading and parsing ' + file.name + '...', 'info');
        const res = await apiFetch(uploadUrl, {
            method: 'POST',
            body: formData
        });
        if (!res.ok) throw new Error(await res.text());
        const result = await res.json();
        if (result.errors && result.errors.length > 0) {
            showToast(`Imported ${result.imported} of ${result.totalRows} rows. ` +
                `Errors: ${result.errors.slice(0, 3).join(' | ')}`, 'error');
        }
        if (onSuccess) onSuccess(result);
    } catch (err) {
        showToast('Upload failed: ' + err.message, 'error');
    }
}

// -------------------------------------------------------------
// Global Quick Actions
// -------------------------------------------------------------
function initGlobalActions() {
    // Quick Load Mock Data
    document.getElementById('btn-quick-mock').addEventListener('click', async () => {
        try {
            showToast('Generating mock exam halls and multi-stream students...', 'info');
            const res = await apiFetch('/api/mock/generate', { method: 'POST' });
            if (!res.ok) throw new Error(await res.text());
            const data = await res.json();
            showToast(data.message, 'success');
            await refreshAllData();
        } catch (err) {
            showToast('Failed to generate mock data: ' + err.message, 'error');
        }
    });

    // Reset All Data
    document.getElementById('btn-reset-data').addEventListener('click', async () => {
        if (!confirm('Are you sure you want to reset and clear all exam halls, students, and seating allocations?')) {
            return;
        }
        try {
            const res = await apiFetch('/api/mock/clear', { method: 'DELETE' });
            if (!res.ok) throw new Error(await res.text());
            showToast('All records cleared successfully.', 'info');
            await refreshAllData();
            document.getElementById('engine-results-active').classList.add('hidden');
            document.getElementById('engine-results-empty').classList.remove('hidden');
        } catch (err) {
            showToast('Failed to reset: ' + err.message, 'error');
        }
    });
}

// -------------------------------------------------------------
// API Data Loaders & View Refreshers
// -------------------------------------------------------------
async function refreshAllData() {
    await Promise.all([
        loadStats(),
        loadRooms(),
        loadStudents(),
        loadAllocations()
    ]);
}

async function loadStats() {
    try {
        const res = await apiFetch('/api/mock/stats');
        if (!res.ok) return;
        const stats = await res.json();
        document.getElementById('header-stat-rooms').textContent = stats.roomCount;
        document.getElementById('header-stat-capacity').textContent = stats.totalCapacity;
        document.getElementById('header-stat-students').textContent = stats.studentCount;
        document.getElementById('header-stat-allocated').textContent = stats.allocationCount;
    } catch (err) {
        console.error('Stats loading error', err);
    }
}

async function loadRooms() {
    try {
        const res = await apiFetch('/api/rooms');
        if (!res.ok) return;
        state.rooms = await res.json();
        document.getElementById('rooms-list-count').textContent = state.rooms.length;
        renderRoomsGrid();
        populateMatrixRoomSelect();
    } catch (err) {
        console.error('Rooms load error', err);
    }
}

function renderRoomsGrid() {
    const container = document.getElementById('rooms-cards-container');
    if (state.rooms.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <div class="empty-icon">🏛️</div>
                <h3>No Exam Halls Added</h3>
                <p>Create halls or import a ROOMS.xlsx sheet to begin.</p>
            </div>`;
        return;
    }

    container.innerHTML = state.rooms.map(room => `
        <div class="room-card-item">
            <div>
                <div class="room-card-title">${escapeHtml(room.hallName)}</div>
                <div class="room-card-specs">
                    <span class="room-spec-tag">📐 ${room.rows} × ${room.columns} Grid</span>
                    <span class="room-spec-tag">🪑 ${room.benchType === 'DUAL_SEATER' ? 'Dual-Seater (2 seats/bench)' : 'Single-Seater'}</span>
                    <span class="room-spec-tag text-emerald">Cap: ${room.rows * room.columns * (room.benchType === 'DUAL_SEATER' ? 2 : 1)}</span>
                </div>
            </div>
            <button class="btn btn-sm btn-outline-danger" onclick="deleteRoom(${room.id})">
                🗑️
            </button>
        </div>
    `).join('');
}

async function deleteRoom(id) {
    if (!confirm('Delete this exam hall?')) return;
    try {
        await apiFetch(`/api/rooms/${id}`, { method: 'DELETE' });
        showToast('Room deleted', 'info');
        await loadRooms();
        await loadStats();
    } catch (err) {
        showToast('Error deleting room: ' + err.message, 'error');
    }
}

async function loadStudents() {
    try {
        const res = await apiFetch('/api/students');
        if (!res.ok) return;
        state.students = await res.json();
        document.getElementById('students-list-count').textContent = state.students.length;
        renderStudentsTable(state.students);
    } catch (err) {
        console.error('Students load error', err);
    }
}

function renderStudentsTable(studentsList) {
    const tbody = document.getElementById('students-table-body');
    if (studentsList.length === 0) {
        tbody.innerHTML = `<tr><td colspan="6" class="text-muted text-center" style="padding: 2rem;">No students found</td></tr>`;
        return;
    }

    tbody.innerHTML = studentsList.map(s => {
        const deptColor = getDeptColor(s.department);
        return `
            <tr>
                <td style="font-family: var(--font-mono); font-weight: 700;">${escapeHtml(s.rollNo)}</td>
                <td><b>${escapeHtml(s.name)}</b></td>
                <td>
                    <span class="dept-pill" style="background: ${deptColor.bg}; color: ${deptColor.text}; border: 1px solid ${deptColor.border};">
                        ${escapeHtml(s.department || 'N/A')}
                    </span>
                </td>
                <td>${escapeHtml(s.yearSession || '-')}</td>
                <td><span class="tag">${escapeHtml(s.subjectCode || '-')}</span></td>
                <td>
                    <button class="btn btn-sm btn-outline-danger" onclick="deleteStudent(${s.id})" title="Delete student">
                        ✕
                    </button>
                </td>
            </tr>
        `;
    }).join('');
}

async function deleteStudent(id) {
    try {
        await apiFetch(`/api/students/${id}`, { method: 'DELETE' });
        showToast('Student removed', 'info');
        await loadStudents();
        await loadStats();
    } catch (err) {
        showToast('Error removing student: ' + err.message, 'error');
    }
}

async function loadAllocations() {
    try {
        const res = await apiFetch('/api/seating/allocations');
        if (!res.ok) return;
        const data = await res.json();
        state.allocations = data.allocations || [];
        if (state.allocations.length > 0) {
            updateExecutionMetrics(data);
        }
    } catch (err) {
        console.error('Allocations load error', err);
    }
}

// -------------------------------------------------------------
// Seating Engine Runner
// -------------------------------------------------------------
async function runSeatingArrangement() {
    const subjectCode = document.getElementById('gen-subject-code').value.trim() || null;
    const benchPairingRule = document.getElementById('gen-bench-pairing')?.value || 'DIFFERENT_DEPARTMENT_AND_YEAR';
    const horizontalDeptIsolation = document.getElementById('gen-horizontal-isolation')?.value !== 'false';
    const strictness = state.strictness;

    // Grouping criteria: which attributes make two students "classmates".
    const groupAttributes = Array.from(
        document.querySelectorAll('#gen-group-attributes input[type="checkbox"]:checked')
    ).map(cb => cb.value);
    const groupMatchMode = document.getElementById('gen-group-mode')?.value || 'ALL';

    // Optional custom gap overrides (blank = use the difficulty preset).
    const gapH = document.getElementById('gen-gap-h')?.value;
    const gapV = document.getElementById('gen-gap-v')?.value;
    const gapD = document.getElementById('gen-gap-d')?.value;

    const requestBody = {
        strictness: strictness,
        benchPairingRule: benchPairingRule,
        horizontalDeptIsolation: horizontalDeptIsolation,
        groupAttributes: groupAttributes.length ? groupAttributes : null,
        groupMatchMode: groupMatchMode,
        subjectCode: subjectCode,
        clearExisting: true
    };
    if (gapH !== '' && gapH != null) requestBody.horizontalGap = parseInt(gapH, 10);
    if (gapV !== '' && gapV != null) requestBody.verticalGap = parseInt(gapV, 10);
    if (gapD !== '' && gapD != null) requestBody.diagonalGap = parseInt(gapD, 10);

    const btn = document.getElementById('btn-run-seating');
    btn.disabled = true;
    btn.innerHTML = `<span>⏳ Solving Seating Matrix...</span>`;

    try {
        const res = await apiFetch('/api/seating/arrange', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(requestBody)
        });

        if (!res.ok) {
            const errorText = await res.text();
            throw new Error(errorText);
        }

        const plan = await res.json();
        state.allocations = plan.allocations || [];
        showToast(`Arranged ${plan.seated} students successfully! (${strictness} Mode)`, 'success');
        updateExecutionMetrics(plan);
        await loadStats();
        populateMatrixRoomSelect();
    } catch (err) {
        showToast('Seating engine failed: ' + err.message, 'error');
    } finally {
        btn.disabled = false;
        btn.innerHTML = `
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polygon points="5 3 19 12 5 21 5 3"></polygon>
            </svg>
            <span>Generate Seating Arrangement</span>`;
    }
}

function updateExecutionMetrics(plan) {
    document.getElementById('engine-results-empty').classList.add('hidden');
    document.getElementById('engine-results-active').classList.remove('hidden');

    document.getElementById('metric-seated').textContent = plan.seated;
    document.getElementById('metric-unplaced').textContent = plan.unplaced;
    document.getElementById('metric-slots').textContent = plan.totalSlots;
    document.getElementById('metric-backtracks').textContent = plan.backtracks;

    const banner = document.getElementById('result-status-banner');
    const title = document.getElementById('result-title');
    const subtitle = document.getElementById('result-subtitle');

    if (plan.unplaced === 0 && plan.solved) {
        banner.className = 'status-banner banner-success';
        title.textContent = `Optimal Seating Solved (${state.strictness} Mode)`;
        subtitle.textContent = `100% of students seated with zero anti-cheating violations.`;
    } else {
        banner.className = 'status-banner banner-extreme';
        title.textContent = `Partial Placement (${plan.unplaced} unplaced)`;
        subtitle.textContent = `Hall capacity or gap constraints limited full placement.`;
    }
}

// -------------------------------------------------------------
// Visual 2D Seat Matrix Rendering
// -------------------------------------------------------------
function populateMatrixRoomSelect() {
    const select = document.getElementById('matrix-room-select');
    const prevVal = select.value;
    select.innerHTML = '<option value="">Select an Exam Hall...</option>';

    state.rooms.forEach(r => {
        const option = document.createElement('option');
        option.value = r.hallName;
        option.textContent = `${r.hallName} (${r.rows}x${r.columns} - ${r.benchType === 'DUAL_SEATER' ? 'Dual' : 'Single'})`;
        select.appendChild(option);
    });

    if (state.rooms.length > 0) {
        select.value = prevVal && state.rooms.some(r => r.hallName === prevVal) 
            ? prevVal 
            : state.rooms[0].hallName;
        state.currentRoomId = select.value;
    }
}

function renderVisualMatrix() {
    const container = document.getElementById('room-matrix-grid');
    const emptyState = document.getElementById('matrix-empty-state');
    const legendContainer = document.getElementById('legend-items-container');

    const selectedHallName = document.getElementById('matrix-room-select').value;
    if (!selectedHallName || state.rooms.length === 0) {
        container.classList.add('hidden');
        emptyState.classList.remove('hidden');
        return;
    }

    const room = state.rooms.find(r => r.hallName === selectedHallName);
    if (!room) {
        container.classList.add('hidden');
        emptyState.classList.remove('hidden');
        return;
    }

    // Filter allocations for this room
    const roomAllocations = state.allocations.filter(a => a.hallName === room.hallName);

    // Map by bench row, col, and slot
    const allocMap = {};
    const departmentsInRoom = new Set();
    roomAllocations.forEach(a => {
        const key = `${a.benchRow}_${a.benchCol}_${a.slot}`;
        allocMap[key] = a;
        if (a.department) departmentsInRoom.add(a.department);
    });

    // Render Stream Color Legend
    legendContainer.innerHTML = Array.from(departmentsInRoom).map(dept => {
        const color = getDeptColor(dept);
        return `
            <div class="legend-item">
                <span class="legend-color-dot" style="background: ${color.dot};"></span>
                <span>${escapeHtml(dept)}</span>
            </div>`;
    }).join('') || '<span class="text-muted" style="font-size: 0.8rem;">No departments seated yet</span>';

    // Render 2D Grid
    container.classList.remove('hidden');
    emptyState.classList.add('hidden');

    let html = '<div class="room-matrix-grid">';
    const seatsPerBench = room.benchType === 'DUAL_SEATER' ? 2 : 1;

    for (let r = 1; r <= room.rows; r++) {
        html += `<div class="matrix-row">`;
        for (let c = 1; c <= room.columns; c++) {
            html += `
                <div class="matrix-bench">
                    <div class="bench-header">
                        <span>Row ${r}, Col ${c}</span>
                        <span>Desk ${((r-1)*room.columns)+c}</span>
                    </div>
                    <div class="bench-slots">`;

            for (let s = 1; s <= seatsPerBench; s++) {
                const alloc = allocMap[`${r}_${c}_${s}`];
                if (alloc) {
                    const color = getDeptColor(alloc.department);
                    html += `
                        <div class="bench-slot occupied" style="background: ${color.bg}; border-color: ${color.border};">
                            <div class="slot-roll" style="color: ${color.text};">#${escapeHtml(alloc.rollNo)}</div>
                            <div class="slot-name" title="${escapeHtml(alloc.name)}">${escapeHtml(alloc.name)}</div>
                            <div class="slot-dept">${escapeHtml(alloc.department || '')} (${escapeHtml(alloc.yearSession || '')})</div>
                        </div>`;
                } else {
                    html += `
                        <div class="bench-slot">
                            <div class="slot-empty">Slot ${s}<br>Empty</div>
                        </div>`;
                }
            }

            html += `</div></div>`;
        }
        html += `</div>`;
    }
    html += '</div>';

    container.innerHTML = html;
}

// -------------------------------------------------------------
// Color Helpers
// -------------------------------------------------------------
function getDeptColor(deptName) {
    if (!deptName) return state.colorPalette[0];
    if (state.deptColors[deptName]) return state.deptColors[deptName];

    let hash = 0;
    for (let i = 0; i < deptName.length; i++) {
        hash = deptName.charCodeAt(i) + ((hash << 5) - hash);
    }
    const idx = Math.abs(hash) % state.colorPalette.length;
    state.deptColors[deptName] = state.colorPalette[idx];
    return state.deptColors[deptName];
}

// -------------------------------------------------------------
// Toast Notification
// -------------------------------------------------------------
function showToast(message, type = 'info') {
    const container = document.getElementById('toast-container');
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    
    let icon = 'ℹ️';
    if (type === 'success') icon = '✅';
    if (type === 'error') icon = '⚠️';

    toast.innerHTML = `<span>${icon}</span><span>${escapeHtml(message)}</span>`;
    container.appendChild(toast);

    setTimeout(() => {
        toast.style.animation = 'slideUp 0.3s ease reverse forwards';
        setTimeout(() => toast.remove(), 300);
    }, 4000);
}

function escapeHtml(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
