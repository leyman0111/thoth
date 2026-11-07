/*
 * Thoth — Telegram Mini App для разделения чека.
 *
 * Логика долей живёт на сервере (BillSplitterService): фронтенд отправляет одну долю
 * через POST /assign, а сервер сам добавляет «остаточного» гостя, продолжает цепочку
 * и возвращает всю сессию, которую мы просто перерисовываем.
 */
(() => {
    'use strict';

    const API = '/api/session';
    const RECEIPT_TIMEOUT_MS = 150_000;   // распознавание чека может идти до ~1.5 мин
    const DEFAULT_TIMEOUT_MS = 20_000;
    const MAX_FILE_BYTES = 10 * 1024 * 1024;
    const MAX_GUESTS = 30;

    const tg = window.Telegram?.WebApp;
    const $ = (id) => document.getElementById(id);

    const state = {
        session: null,
        queue: Promise.resolve(),   // запросы /assign выполняются строго по очереди
    };

    // ======================================================================== Telegram

    function initTelegram() {
        if (!tg) return;
        tg.ready();
        tg.expand();
        tg.disableVerticalSwipes?.();   // чтобы прокрутка списка не сворачивала приложение
    }

    function haptic(type) {
        try {
            tg?.HapticFeedback?.notificationOccurred(type);
        } catch (_) { /* старые клиенты */ }
    }

    /**
     * Пользователь из Telegram. Вне Telegram (разработка в браузере) —
     * ?devUser=123 в адресе, запоминается в localStorage; сервер принимает его,
     * только если запущен с THOTH_DEV_MODE=true.
     */
    function currentUser() {
        const user = tg?.initDataUnsafe?.user;
        if (tg?.initData && user?.id) {
            return {id: String(user.id), username: user.username || '', telegram: true};
        }
        let devId = new URLSearchParams(location.search).get('devUser');
        try {
            if (devId) localStorage.setItem('thoth.devUser', devId);
            else devId = localStorage.getItem('thoth.devUser');
        } catch (_) { /* приватный режим */ }
        return devId ? {id: devId, username: 'dev', telegram: false} : null;
    }

    /**
     * В Telegram — подписанные initData: сервер проверяет подпись токеном бота и сам
     * достаёт пользователя. Подделать id так нельзя. Вне Telegram — dev-заголовок.
     */
    function authHeaders(user) {
        if (user.telegram) {
            return {'Authorization': `tma ${tg.initData}`};
        }
        return {'X-Telegram-User-Id': user.id, 'X-Telegram-Username': user.username};
    }

    // ============================================================================= API

    class ApiError extends Error {
        constructor(message, status) {
            super(message);
            this.status = status;
        }
    }

    async function api(path, {method = 'GET', json, formData, timeout = DEFAULT_TIMEOUT_MS} = {}) {
        const user = currentUser();
        if (!user) {
            throw new ApiError('Откройте приложение из Telegram', 0);
        }

        const headers = {...authHeaders(user), 'Accept': 'application/json'};

        let body;
        if (json !== undefined) {
            headers['Content-Type'] = 'application/json';
            body = JSON.stringify(json);
        } else if (formData) {
            body = formData;   // Content-Type с boundary браузер выставит сам
        }

        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), timeout);
        let response;
        try {
            response = await fetch(API + path, {method, headers, body, signal: controller.signal});
        } catch (e) {
            throw new ApiError(e.name === 'AbortError'
                ? 'Сервер не ответил вовремя. Попробуйте ещё раз'
                : 'Нет связи с сервером. Проверьте интернет', 0);
        } finally {
            clearTimeout(timer);
        }

        const text = await response.text();
        let data = null;
        try {
            data = text ? JSON.parse(text) : null;
        } catch (_) { /* не JSON */ }

        if (!response.ok) {
            // Сервер отвечает в формате problem+json: {status, title, detail}
            const message = data?.detail || data?.title || messageForStatus(response.status);
            throw new ApiError(message, response.status);
        }
        return data;
    }

    function messageForStatus(status) {
        switch (status) {
            case 401: return 'Не удалось подтвердить пользователя Telegram. Закройте и откройте приложение заново';
            case 404: return 'Сессия не найдена';
            case 413: return 'Файл слишком большой (максимум 10 МБ)';
            case 415: return 'Этот формат файла не поддерживается';
            case 422: return 'Не удалось распознать чек';
            case 502:
            case 504: return 'Сервис распознавания недоступен. Попробуйте позже';
            default: return `Ошибка сервера (${status})`;
        }
    }

    // =========================================================================== Toast

    function toast(message, type = 'error') {
        const el = document.createElement('div');
        el.className = `toast toast--${type}`;
        el.textContent = message;
        el.setAttribute('role', type === 'error' ? 'alert' : 'status');
        $('toasts').append(el);
        if (type === 'error') haptic('error');

        const hide = () => {
            el.classList.add('toast--hide');
            setTimeout(() => el.remove(), 250);
        };
        el.addEventListener('click', hide);
        setTimeout(hide, type === 'error' ? 4500 : 2500);
    }

    function showError(e) {
        console.error(e);
        toast(e instanceof ApiError ? e.message : 'Что-то пошло не так. Попробуйте ещё раз');
    }

    // ========================================================================= Экраны

    function showScreen(name) {
        document.querySelectorAll('[data-screen]').forEach((s) => {
            s.hidden = s.dataset.screen !== name;
        });
        window.scrollTo(0, 0);
    }

    function setBusy(button, busy) {
        button.disabled = busy;
        button.classList.toggle('is-disabled', busy);
    }

    /** Последняя сессия запоминается отдельно для каждого пользователя Telegram на устройстве. */
    const storageKey = () => `thoth.sessionId.${currentUser()?.id ?? 'anon'}`;

    function saveSessionId(id) {
        try {
            if (id) localStorage.setItem(storageKey(), id);
            else localStorage.removeItem(storageKey());
        } catch (_) { /* приватный режим */ }
    }

    function loadSessionId() {
        try {
            return localStorage.getItem(storageKey());
        } catch (_) {
            return null;
        }
    }

    /** Открывает экран, соответствующий статусу сессии. */
    function route(session) {
        state.session = session;
        saveSessionId(session.id);
        switch (session.status) {
            case 'AWAITING_GUESTS':
                showScreen('guests');
                $('guest-names').focus();
                break;
            case 'AWAITING_PHOTO':
                showScreen('receipt');
                break;
            case 'PROCESSING':
                showScreen('receipt');
                setProcessing(true);
                pollWhileProcessing(session.id);
                break;
            case 'READY':
                renderMain();
                showScreen('main');
                break;
        }
    }

    // ======================================================= Экран 1: начать расчёт

    async function startSession() {
        const button = $('btn-start');
        setBusy(button, true);
        try {
            route(await api('/start', {method: 'POST'}));
        } catch (e) {
            showError(e);
        } finally {
            setBusy(button, false);
        }
    }

    // ============================================================== Экран 2: гости

    function parseGuestNames() {
        return $('guest-names').value
            .split('\n')
            .map((s) => s.trim().replace(/\s+/g, ' '))
            .filter(Boolean);
    }

    function updateGuestCount() {
        const n = parseGuestNames().length;
        const word = n % 10 === 1 && n % 100 !== 11 ? 'гость'
            : [2, 3, 4].includes(n % 10) && ![12, 13, 14].includes(n % 100) ? 'гостя' : 'гостей';
        $('guest-count').textContent = `${n} ${word}`;
    }

    async function submitGuests() {
        const names = parseGuestNames();
        if (names.length < 2) {
            toast('Добавьте хотя бы двух гостей — каждое имя с новой строки');
            return;
        }
        if (names.length > MAX_GUESTS) {
            toast(`Не больше ${MAX_GUESTS} гостей`);
            return;
        }
        const seen = new Set();
        for (const name of names) {
            const key = name.toLowerCase();
            if (seen.has(key)) {
                toast(`Имя «${name}» повторяется`);
                return;
            }
            seen.add(key);
        }

        const button = $('btn-guests');
        setBusy(button, true);
        try {
            route(await api(`/${state.session.id}/guests`, {method: 'POST', json: names}));
        } catch (e) {
            showError(e);
            // Гости уже заданы ранее (409) — просто идём дальше по актуальному статусу
            if (e.status === 409) await reloadSession();
        } finally {
            setBusy(button, false);
        }
    }

    // ============================================================ Экран 3: фото чека

    function setProcessing(on) {
        $('processing').hidden = !on;
        $('btn-receipt').hidden = on;
        // Пока чек распознаётся, Telegram переспросит перед закрытием Mini App
        try {
            if (on) tg?.enableClosingConfirmation?.();
            else tg?.disableClosingConfirmation?.();
        } catch (_) { /* старые клиенты */ }
    }

    async function uploadReceipt(file) {
        if (!file) return;
        if (file.size > MAX_FILE_BYTES) {
            toast('Файл больше 10 МБ. Сделайте фото с меньшим разрешением');
            return;
        }
        if (file.type && !['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
            toast('Нужна фотография в формате JPEG, PNG или WebP');
            return;
        }

        const form = new FormData();
        form.append('file', file, file.name || 'receipt.jpg');

        setProcessing(true);
        try {
            const session = await api(`/${state.session.id}/receipt`, {
                method: 'POST', formData: form, timeout: RECEIPT_TIMEOUT_MS,
            });
            haptic('success');
            toast(`Распознано позиций: ${session.items.length}`, 'info');
            route(session);
        } catch (e) {
            showError(e);
            // 409 «уже распознаётся» — ждём результата; иначе даём выбрать фото заново
            if (e.status === 409) await reloadSession();
        } finally {
            setProcessing(state.session?.status === 'PROCESSING');
            $('receipt-file').value = '';   // чтобы можно было выбрать тот же файл ещё раз
        }
    }

    /** Если приложение открыли заново во время распознавания — ждём, пока сервер закончит. */
    async function pollWhileProcessing(sessionId) {
        for (let attempt = 0; attempt < 60; attempt++) {
            await new Promise((r) => setTimeout(r, 3000));
            if (state.session?.id !== sessionId) return;
            try {
                const session = await api(`/${sessionId}`);
                if (session.status !== 'PROCESSING') {
                    setProcessing(false);
                    route(session);
                    return;
                }
            } catch (e) {
                showError(e);
                return;
            }
        }
        setProcessing(false);
        toast('Распознавание идёт слишком долго. Попробуйте загрузить фото ещё раз');
    }

    // ================================================================ Экран 4: позиции

    const money = (value, grouping = true) => {
        const n = Number(value) || 0;
        const digits = Math.round(n * 100) % 100 === 0 ? 0 : 2;
        return n.toLocaleString('ru-RU', {
            minimumFractionDigits: digits, maximumFractionDigits: digits, useGrouping: grouping,
        });
    };
    const share = (value) => Number(value).toFixed(2);

    /** Мини-хелпер для DOM: данные вставляются только через textContent — без XSS. */
    function h(tag, attrs = {}, ...children) {
        const el = document.createElement(tag);
        for (const [key, value] of Object.entries(attrs)) {
            if (value === false || value == null) continue;
            if (key === 'class') el.className = value;
            else if (key === 'text') el.textContent = value;
            else if (key.startsWith('on')) el.addEventListener(key.slice(2), value);
            else if (key in el && typeof value !== 'string') el[key] = value;
            else el.setAttribute(key, value === true ? '' : value);
        }
        el.append(...children.filter((c) => c != null));
        return el;
    }

    function renderMain() {
        renderItems();
        renderReport();
    }

    function renderItems() {
        const {items, guests} = state.session;
        const container = $('items');
        container.replaceChildren();

        if (!items.length) {
            container.append(h('p', {class: 'hint', text: 'В чеке нет позиций'}));
            return;
        }
        for (const item of items) {
            container.append(renderItem(item, guests));
        }
    }

    function renderItem(item, guests) {
        const sum = item.assignments.reduce((acc, a) => acc + Math.round(a.shareRatio * 100), 0);
        const card = h('div', {class: 'card item', 'data-item': item.id},
            h('div', {class: 'item__head'},
                h('span', {class: 'item__name', text: item.name}),
                h('span', {class: 'item__price', text: `${money(item.price)} ₽`})),
        );

        if (item.status === 'UNASSIGNED') {
            card.append(h('p', {class: 'item__status item__status--todo', text: 'Никому не назначено'}));
        } else if (sum !== 100) {
            card.append(h('p', {class: 'item__status item__status--todo', text: `Сумма долей ${share(sum / 100)}`}));
        }

        // Строки существующих долей: гость зафиксирован, меняется только доля
        for (const a of item.assignments) {
            card.append(renderShareRow(item, guests, a));
        }

        // Новая строка: гость, которого ещё нет на позиции
        const taken = new Set(item.assignments.map((a) => a.guestId));
        const free = guests.filter((g) => !taken.has(g.id));
        if (free.length) {
            card.append(renderNewRow(item, free));
        }
        return card;
    }

    function renderShareRow(item, guests, assignment) {
        const select = h('select', {disabled: true, 'aria-label': 'Гость'},
            ...guests.map((g) => h('option', {value: g.id, text: g.name, selected: g.id === assignment.guestId})));
        const input = shareInput(share(assignment.shareRatio));
        const error = h('p', {class: 'share-row__error', hidden: true});

        attachShareValidation(input, error, assignment.shareRatio);
        input.addEventListener('change', () => {
            const value = readShare(input, error);
            if (value === null) {
                input.value = share(assignment.shareRatio);   // недопустимое значение не отправляем
                clearError(input, error);
                return;
            }
            if (value === Number(share(assignment.shareRatio))) return;
            assign(item.id, assignment.guestId, value);
        });

        return h('div', {class: 'share-row'},
            select, input, error,
            h('span', {class: 'share-row__amount', text: `${money(assignment.amount)} ₽`}));
    }

    function renderNewRow(item, freeGuests) {
        const isFirst = item.assignments.length === 0;
        const select = h('select', {'aria-label': 'Добавить гостя'},
            h('option', {value: '', text: isFirst ? 'Кто ел?' : '+ добавить гостя'}),
            ...freeGuests.map((g) => h('option', {value: g.id, text: g.name})));
        const input = shareInput('');
        input.placeholder = isFirst ? '1.00' : '0.00';
        const error = h('p', {class: 'share-row__error', hidden: true});
        attachShareValidation(input, error, null);

        const send = () => {
            const guestId = Number(select.value);
            if (!guestId) {
                if (input.value !== '') toast('Сначала выберите гостя');
                return;
            }
            let value = input.value === '' ? (isFirst ? 1 : 0) : readShare(input, error);
            if (value === null) return;
            assign(item.id, guestId, value);
        };
        select.addEventListener('change', send);
        input.addEventListener('change', send);

        return h('div', {class: 'share-row share-row--new'}, select, input, error);
    }

    function shareInput(value) {
        return h('input', {
            type: 'number', step: '0.01', min: '0', max: '1',
            inputmode: 'decimal', value, 'aria-label': 'Доля',
        });
    }

    /**
     * Пока пользователь печатает: подсвечиваем значение вне [0; 1] и не даём его отправить.
     * Если доля увеличилась, разницу сервер заберёт у гостей ниже по списку, так что
     * сумма по позиции всегда остаётся 1.00.
     */
    function attachShareValidation(input, error, previous) {
        input.addEventListener('input', () => {
            if (input.value === '') {
                clearError(input, error);
                return;
            }
            readShare(input, error);
        });
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') input.blur();
            if (e.key === 'Escape' && previous != null) {
                input.value = share(previous);
                clearError(input, error);
                input.blur();
            }
        });
    }

    /** Значение доли или null, если оно недопустимо (ошибка показывается под полем). */
    function readShare(input, error) {
        const raw = input.value.replace(',', '.');
        const value = Number(raw);
        let message = null;
        if (raw === '' || Number.isNaN(value)) message = 'Введите число от 0 до 1';
        else if (value < 0) message = 'Доля не может быть меньше 0';
        else if (value > 1) message = 'Доля не может быть больше 1';

        if (message) {
            input.classList.add('is-invalid');
            error.textContent = message;
            error.hidden = false;
            return null;
        }
        clearError(input, error);
        return Math.round(value * 100) / 100;   // как на сервере: 2 знака
    }

    function clearError(input, error) {
        input.classList.remove('is-invalid');
        error.hidden = true;
    }

    /**
     * Одна доля → сервер пересчитывает остальные (остаток — следующему гостю, цепочкой)
     * и возвращает всю сессию.
     */
    function assign(itemId, guestId, shareRatio) {
        const card = document.querySelector(`[data-item="${itemId}"]`);
        card?.classList.add('is-busy');

        state.queue = state.queue.then(async () => {
            try {
                state.session = await api(`/${state.session.id}/assign`, {
                    method: 'POST', json: {itemId, guestId, shareRatio},
                });
                haptic('success');
            } catch (e) {
                showError(e);
                await reloadSession();   // вернуть экран к состоянию сервера
            } finally {
                renderMain();
            }
        });
        return state.queue;
    }

    async function reloadSession() {
        if (!state.session?.id) return;
        try {
            route(await api(`/${state.session.id}`));
        } catch (e) {
            showError(e);
        }
    }

    // ================================================================= Экран 5: отчёт

    function renderReport() {
        const {summary} = state.session;
        const body = $('report-body');
        body.replaceChildren(...summary.guests.map((g) =>
            h('tr', {},
                h('td', {text: g.name}),
                h('td', {class: 'num', text: `${money(g.total)} ₽`}))));
        $('report-total').textContent = `${money(summary.assignedTotal)} ₽`;

        const warning = $('report-warning');
        const unassigned = summary.unassignedItemIds.length;
        warning.hidden = summary.complete;
        if (!summary.complete) {
            warning.textContent = unassigned
                ? `Не распределено позиций: ${unassigned} на ${money(summary.unassignedTotal)} ₽`
                : 'В чеке нет позиций';
        }
        const shareButton = $('btn-share');
        shareButton.disabled = !summary.complete;
        shareButton.classList.toggle('is-disabled', !summary.complete);
    }

    /** «Чек на 2500₽ разделен. Иван: 1200₽, Петр: 1300₽» */
    function buildShareText() {
        const {summary} = state.session;
        const parts = summary.guests
            .filter((g) => Number(g.total) > 0)
            .map((g) => `${g.name}: ${money(g.total, false)}₽`);
        return `Чек на ${money(summary.receiptTotal, false)}₽ разделен. ${parts.join(', ')}`;
    }

    /**
     * В Telegram WebApp API нет метода shareText. Если он появится — используем его;
     * иначе стандартная ссылка t.me/share открывает выбор чата с готовым текстом.
     * Вне Telegram — системное «Поделиться» или буфер обмена.
     */
    async function shareResult() {
        const text = buildShareText();
        try {
            if (typeof tg?.shareText === 'function') {
                tg.shareText(text);
            } else if (tg?.openTelegramLink && tg.initData) {
                tg.openTelegramLink(`https://t.me/share/url?url=${encodeURIComponent(text)}`);
            } else if (navigator.share) {
                await navigator.share({text});
            } else {
                await navigator.clipboard.writeText(text);
                toast('Текст скопирован', 'info');
            }
        } catch (e) {
            if (e?.name !== 'AbortError') showError(e);
        }
    }

    // ========================================================================= Вкладки

    function switchTab(name) {
        document.querySelectorAll('[data-tab]').forEach((t) => {
            const active = t.dataset.tab === name;
            t.classList.toggle('is-active', active);
            t.setAttribute('aria-selected', String(active));
        });
        document.querySelectorAll('[data-panel]').forEach((p) => {
            p.hidden = p.dataset.panel !== name;
        });
        window.scrollTo(0, 0);
    }

    // ===================================================================== Запуск

    function restart() {
        const go = () => {
            saveSessionId(null);
            state.session = null;
            $('guest-names').value = '';
            updateGuestCount();
            switchTab('items');
            showScreen('start');
        };
        if (tg?.initData && tg.isVersionAtLeast?.('6.2')) tg.showConfirm('Начать новый расчёт? Текущий останется на сервере.', (ok) => ok && go());
        else if (confirm('Начать новый расчёт?')) go();
    }

    async function resume() {
        const id = loadSessionId();
        if (!id || !currentUser()) {
            showScreen('start');
            return;
        }
        try {
            route(await api(`/${id}`));
        } catch (e) {
            saveSessionId(null);
            showScreen('start');
            if (e.status !== 404) showError(e);
        }
    }

    function bind() {
        $('btn-start').addEventListener('click', startSession);
        $('guest-names').addEventListener('input', updateGuestCount);
        $('btn-guests').addEventListener('click', submitGuests);
        $('receipt-file').addEventListener('change', (e) => uploadReceipt(e.target.files[0]));
        $('btn-share').addEventListener('click', shareResult);
        $('btn-restart').addEventListener('click', restart);
        document.querySelectorAll('[data-tab]').forEach((t) =>
            t.addEventListener('click', () => switchTab(t.dataset.tab)));
    }

    initTelegram();
    bind();
    resume();
    if (!currentUser()) {
        toast('Откройте приложение из Telegram (для разработки: ?devUser=123)');
    }
})();
