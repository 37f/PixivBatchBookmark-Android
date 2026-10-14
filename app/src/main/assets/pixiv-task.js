(function () {
    'use strict';
    var active = null;

    function isActive() { return !!active; }
    function pause() { if (active) active.paused = true; }
    function resume() {
        if (!active) return;
        active.paused = false;
        active.waiters.splice(0).forEach(function (wake) { wake(); });
    }
    function cancel() {
        var task = active;
        if (!task) return;
        // A submitted POST cannot be undone by aborting the local fetch.
        task.sent.forEach(function (index) {
            if (!task.results.has(index)) task.report(index, 'pending', '操作已提交但尚未核验；请在 Pixiv 核对后再决定是否重试');
        });
        task.cancelled = true;
        task.controller.abort();
        task.waiters.splice(0).forEach(function (wake) { wake(); });
        active = null;
    }

    function metadata() {
        var sources = [];
        for (var selector of ['meta#meta-global-data', 'meta[name="global-data"]']) {
            var meta = document.querySelector(selector);
            if (meta) {
                try { sources.push(JSON.parse(meta.content)); } catch (_) {}
            }
        }
        if (typeof globalInitData !== 'undefined') sources.push(globalInitData);
        if (typeof globalData !== 'undefined') sources.push(globalData);
        if (window.pixiv) {
            if (window.pixiv.context) sources.push(window.pixiv.context);
            if (window.pixiv.user) sources.push({ userId: window.pixiv.user.id });
        }
        var result = { userId: '', token: '' };
        for (var data of sources) {
            if (!data || typeof data !== 'object') continue;
            var id = data.userData && data.userData.id || data.userId;
            if (!result.userId && /^\d+$/.test(String(id || '')) && String(id) !== '0') result.userId = String(id);
            if (!result.token && typeof data.token === 'string' && data.token) result.token = data.token;
        }
        var csrfMeta = document.querySelector('meta[name="csrf-token"]');
        if (!result.token && csrfMeta) result.token = csrfMeta.content;
        return result;
    }

    function validCsrf(value) {
        return typeof value === 'string' && /^[A-Za-z0-9_-]{20,128}$/.test(value);
    }

    function documentCsrf(doc) {
        for (var selector of ['meta#meta-global-data', 'meta[name="global-data"]']) {
            var meta = doc.querySelector(selector);
            if (meta) {
                try {
                    var data = JSON.parse(meta.content);
                    if (data && validCsrf(data.token)) return data.token;
                } catch (_) {}
            }
        }
        var direct = doc.querySelector('meta[name="csrf-token"]');
        if (direct && validCsrf(direct.content)) return direct.content;
        var input = doc.querySelector('input[name="tt"]');
        if (input && validCsrf(input.value)) return input.value;

        // Newer Pixiv pages often embed initialization JSON in escaped script strings.
        // Read script text only; never execute fetched scripts or scrape captions.
        var values = new Set();
        var scripts = doc.querySelectorAll ? doc.querySelectorAll('script') : [];
        for (var script of scripts) {
            var patterns = [
                /[\\]*["'](?:token|csrfToken|csrf_token)[\\]*["']\s*:\s*[\\]*["']([A-Za-z0-9_-]{20,128})[\\]*["']/g,
                /\b(?:pixiv\.context\.token|g_csrfToken|csrfToken|csrf_token|token)\s*=\s*["']([A-Za-z0-9_-]{20,128})["']/g
            ];
            for (var pattern of patterns) {
                var match;
                while ((match = pattern.exec(script.textContent || '')) !== null) values.add(match[1]);
            }
        }
        return values.size === 1 ? Array.from(values)[0] : '';
    }

    // Queries have two workers; one consumer submits all mutations serially.
    // Removal windows contain at most five artwork records, never artwork IDs as bookmark IDs.
    var READ_WORKERS = 2, REMOVE_BATCH_SIZE = 5, MUTATION_GAP_MS = 350, WINDOW_GAP_MS = 120;

    function makeTask(kind, token, ids, privateMode) {
        var task = { kind: kind, token: String(token), ids: ids, privateMode: !!privateMode,
            cancelled: false, paused: false, controller: new AbortController(), waiters: [],
            sent: new Set(), results: new Map(), nextReport: 0, lastMutation: 0 };
        task.live = function () { return active === task && !task.cancelled; };
        task.report = function (index, state, detail, cause) {
            if (!task.live() || task.results.has(index)) return;
            var result = { state: state, detail: detail || '' };
            if (cause) result.cause = cause;
            task.results.set(index, result);
            if (state !== 'pending') task.sent.delete(index);
            task.flush();
        };
        task.flush = function () {
            while (task.results.has(task.nextReport) && task.live()) {
                var index = task.nextReport++;
                AndroidBridge.result(task.token, task.kind, task.ids[index], JSON.stringify(task.results.get(index)));
            }
        };
        task.end = function (state, detail) {
            if (!task.live()) return;
            active = null;
            task.controller.abort();
            task.waiters.splice(0).forEach(function (wake) { wake(); });
            if (AndroidBridge.event) AndroidBridge.event(task.token, state, detail || '');
        };
        return task;
    }

    async function admitted(task) {
        while (task.paused && task.live() && !task.halted) await new Promise(function (resolve) { task.waiters.push(resolve); });
        if (!task.live()) throw { state: 'cancelled', message: '任务已停止' };
        if (task.halted) throw task.haltError;
    }
    async function request(task, url, init) {
        await admitted(task);
        return fetch(url, Object.assign({ credentials: 'include', cache: 'no-store', signal: task.controller.signal }, init));
    }
    async function read(response, sessionProbe, allowEmptyBody) {
        if (response.status === 429) throw { state: 'blocked', message: 'HTTP 429：请求过于频繁，已停止，请稍后检查' };
        if (response.status === 401 || response.status === 403)
            throw { state: sessionProbe && response.status === 403 ? 'blocked' : 'auth',
                message: 'HTTP ' + response.status + '：登录或访问受限，请在 Pixiv 网页检查' };
        if (!response.ok) throw new Error('HTTP ' + response.status);
        var value;
        try { value = JSON.parse(await response.text()); }
        catch (_) { throw new Error('服务器未返回有效 JSON，可能进入登录或验证页面'); }
        if (!value || typeof value.error !== 'boolean') throw new Error('无法识别服务器返回值');
        if (value.error) {
            if (sessionProbe && /log\s*in|sign\s*in|ログイン|登录|登入/i.test(value.message || ''))
                throw { state: 'auth', message: '登录会话已失效，请重新登录' };
            throw new Error(value.message || 'Pixiv 返回错误');
        }
        if (!allowEmptyBody && (!value.body || typeof value.body !== 'object')) throw new Error('服务器返回值缺少信息');
        return value.body;
    }
    async function artworkInfo(task, id) {
        var response = await request(task, '/ajax/illust/' + id + '?full=1');
        if (!task.live()) throw { state: 'cancelled' };
        if (response.status === 404) {
            var probe = await request(task, '/ajax/user/' + id + '/profile/all?lang=zh');
            // Limits/auth must never be hidden by the artist-ID diagnostic.
            if (probe.status === 429 || probe.status === 401 || probe.status === 403) await read(probe);
            var isUser = false;
            if (probe.ok) {
                try { var data = JSON.parse(await probe.text()); isUser = data.error === false && !!data.body; } catch (_) {}
            }
            throw { state: isUser ? 'wrongid' : 'missing', message: isUser ?
                '这个数字是 Pixiv 画师/用户 ID，不是作品 ID，已跳过' : '作品不存在、已删除，或当前账号无权访问，已跳过' };
        }
        return read(response);
    }
    function bookmarkState(body) {
        if (!Object.prototype.hasOwnProperty.call(body, 'bookmarkData')) throw new Error('服务器返回值缺少收藏状态');
        var value = body.bookmarkData;
        if (value === null) return null;
        if (!value || typeof value !== 'object') throw new Error('无法识别收藏记录');
        var mode = value.private;
        if (mode === true || mode === 1 || mode === '1') mode = true;
        else if (mode === false || mode === 0 || mode === '0') mode = false;
        else throw new Error('无法识别收藏的公开/私密状态');
        return { id: String(value.id || ''), private: mode };
    }
    function fatal(error) { return ['auth', 'blocked', 'token', 'storage'].indexOf(error.state) !== -1; }
    function halt(task, error) {
        task.halted = true;
        task.haltError = error;
        // A limit can arrive while another worker awaits resume; release it to terminate.
        task.waiters.splice(0).forEach(function (wake) { wake(); });
    }
    async function mapReads(task, indices, operation) {
        var cursor = 0;
        async function worker() {
            while (cursor < indices.length && task.live() && !task.halted) {
                var index = indices[cursor++];
                await operation(index);
            }
        }
        // Fixed worker count, independent of input size.
        await Promise.all([worker(), worker()]);
    }
    async function mutate(task, indices, url, payload, csrf) {
        var remaining = MUTATION_GAP_MS - (Date.now() - task.lastMutation);
        if (remaining > 0) await new Promise(function (resolve) { setTimeout(resolve, remaining); });
        await admitted(task);
        // Android durably journals IDs on its bridge thread before allowing the POST.
        if (AndroidBridge.submitted && AndroidBridge.submitted(task.token,
                JSON.stringify(indices.map(function (i) { return task.ids[i]; }))) === false)
            throw { state: 'storage', message: '无法保存待核验状态，操作尚未提交，已停止' };
        indices.forEach(function (i) { task.sent.add(i); });
        task.lastMutation = Date.now();
        return read(await request(task, url, { method: 'POST', headers: { accept: 'application/json',
            'content-type': 'application/json; charset=utf-8', 'x-csrf-token': csrf }, body: JSON.stringify(payload) }), false, true);
    }
    function startBatch(kind, token, ids, privateMode, csrf) {
        if (active) return false; // Starting a new batch never cancels an existing one.
        if ((kind !== 'bookmark' && kind !== 'unbookmark') || !Array.isArray(ids) || !ids.length) return false;
        ids = ids.map(String);
        var task = makeTask(kind, token, ids, privateMode);
        active = task;
        (async function () {
            try {
                if (location.protocol !== 'https:' || location.hostname !== 'www.pixiv.net')
                    throw { state: 'auth', message: '当前不是 Pixiv 官方页面，请先完成登录' };
                if (!validCsrf(csrf)) throw { state: 'token', message: '安全令牌无效，操作尚未提交' };
                var width = kind === 'unbookmark' ? REMOVE_BATCH_SIZE : READ_WORKERS;
                for (var offset = 0; offset < ids.length && task.live(); offset += width) {
                    var indices = [], records = new Map(), errors = new Map();
                    for (var i = offset; i < Math.min(ids.length, offset + width); i++) indices.push(i);
                    await mapReads(task, indices, async function (index) {
                        try {
                            if (!/^\d{5,12}$/.test(ids[index])) throw new Error('作品 ID 无效');
                            var body = await artworkInfo(task, ids[index]);
                            records.set(index, { body: body, bookmark: bookmarkState(body) });
                        } catch (error) { errors.set(index, error); if (fatal(error)) halt(task, error); }
                    });
                    if (!task.live()) return;
                    var fatalError = Array.from(errors.values()).find(fatal);
                    if (fatalError) {
                        indices.forEach(function (index) {
                            var error = errors.get(index);
                            task.report(index, error ? error.state || 'fail' : 'stopped',
                                error ? error.message : '同组请求遇到登录或访问限制，当前作品未提交');
                        });
                        task.end('stopped', fatalError.message); return;
                    }
                    indices.forEach(function (index) {
                        if (errors.has(index)) { var e = errors.get(index); task.report(index, e.state || 'fail', e.message || String(e)); }
                    });
                    var changes = [];
                    indices.forEach(function (index) {
                        if (!records.has(index)) return;
                        var record = records.get(index), bookmark = record.bookmark;
                        if (kind === 'unbookmark' ? !bookmark : bookmark && bookmark.private === task.privateMode)
                            task.report(index, 'skip', record.body.title || '');
                        else if (bookmark && !/^\d+$/.test(bookmark.id)) task.report(index, 'fail', '原收藏记录 ID 无效，当前作品未修改');
                        else changes.push(index);
                    });
                    var groups = kind === 'unbookmark' && changes.length ? [changes] : changes.map(function (i) { return [i]; });
                    for (var group of groups) {
                        if (!task.live()) return;
                        var first = records.get(group[0]);
                        var url, payload, success;
                        if (kind === 'unbookmark') {
                            url = '/ajax/illusts/bookmarks/remove'; success = 'removed';
                            payload = { bookmarkIds: group.map(function (i) { return records.get(i).bookmark.id; }) };
                        } else if (first.bookmark) {
                            // Visibility changes in place preserve existing tags/comments.
                            url = '/ajax/illusts/bookmarks/edit_restrict'; success = 'changed';
                            payload = { bookmarkIds: [first.bookmark.id], bookmarkRestrict: task.privateMode ? 'private' : 'public' };
                        } else {
                            url = '/ajax/illusts/bookmarks/add'; success = 'ok';
                            payload = { illust_id: ids[group[0]], restrict: task.privateMode ? 1 : 0, comment: '', tags: [] };
                        }
                        var uncertain = null;
                        try {
                            await mutate(task, group, url, payload, csrf);
                            if (!task.live()) return;
                            await mapReads(task, group, async function (index) {
                                try {
                                    var body = await artworkInfo(task, ids[index]);
                                    var saved = bookmarkState(body);
                                    if (kind === 'unbookmark' ? !!saved : !saved || saved.private !== task.privateMode)
                                        throw new Error('提交后的收藏状态与目标不符，请在 Pixiv 核对');
                                    task.report(index, success, records.get(index).body.title || '');
                                } catch (error) {
                                    if (!task.live()) return;
                                    uncertain = error;
                                    if (fatal(error)) halt(task, error);
                                    task.report(index, 'pending', '操作已提交但核验未完成；' + (error.message || String(error)), error.state);
                                }
                            });
                            if (uncertain) group.forEach(function (index) {
                                if (!task.results.has(index)) task.report(index, 'pending',
                                    '操作已提交，访问受限后未继续核验；' + (uncertain.message || String(uncertain)), uncertain.state);
                            });
                        } catch (error) {
                            if (!task.live()) return;
                            uncertain = error;
                            group.forEach(function (index) {
                                task.report(index, task.sent.has(index) ? 'pending' : error.state || 'fail',
                                    (task.sent.has(index) ? '已尝试提交，结果待核验；' : '') + (error.message || String(error)), error.state);
                            });
                        }
                        if (uncertain) { task.end('stopped', '已停止，不会自动重试；' + (uncertain.message || String(uncertain))); return; }
                    }
                    if (offset + width < ids.length) await new Promise(function (resolve) { setTimeout(resolve, WINDOW_GAP_MS); });
                }
                task.end('done', '所有已报告操作均已核验；跳过项未提交');
            } catch (error) {
                if (!task.live()) return;
                task.report(0, error.state || 'fail', error.message || String(error));
                task.end('stopped', error.message || String(error));
            }
        })();
        return true;
    }

    function run(kind, token, id, privateMode, carriedCsrf) {
        if (kind !== 'login' && kind !== 'check')
            return startBatch(kind, token, [id], privateMode, validCsrf(carriedCsrf) ? carriedCsrf : metadata().token);
        if (active) return false;
        var task = makeTask(kind, token, [id], privateMode);
        active = task;
        (async function () {
            var result;
            try {
                if (location.protocol !== 'https:' || location.hostname !== 'www.pixiv.net')
                    throw { state: 'auth', message: '当前不是 Pixiv 官方页面，请先登录' };
                var session = await read(await request(task, '/ajax/user/extra?lang=zh'), true);
                if (typeof session.following !== 'number' || typeof session.followers !== 'number') throw new Error('无法识别登录核验结果');
                var data = metadata(), csrf = '';
                if (kind === 'login') {
                    csrf = validCsrf(data.token) ? data.token : documentCsrf(document);
                    if (!csrf) {
                        var response = await request(task, '/', { headers: { accept: 'text/html' } });
                        if ([401,403,429].indexOf(response.status) !== -1) await read(response);
                        if (!response.ok) throw new Error('HTTP ' + response.status);
                        var finalUrl = new URL(response.url || '/', 'https://www.pixiv.net/');
                        if (finalUrl.protocol !== 'https:' || finalUrl.hostname !== 'www.pixiv.net')
                            throw { state: 'auth', message: '官方页面跳转到了登录页或其他网站' };
                        csrf = documentCsrf(new DOMParser().parseFromString(await response.text(), 'text/html'));
                    }
                    if (!validCsrf(csrf)) throw { state: 'token', message: '登录已确认，但没有取得有效安全令牌，当前批次尚未提交' };
                }
                result = { state: 'ok', detail: '登录会话有效', userId: data.userId };
                if (kind === 'login') result.csrf = csrf;
            } catch (error) { result = { state: error.state || 'fail', detail: error.message || String(error) }; }
            if (!task.live()) return;
            active = null;
            AndroidBridge.result(String(token), kind, id, JSON.stringify(result));
        })();
        return true;
    }
    window.PixivBatchTask = { run: run, startBatch: startBatch, cancel: cancel, pause: pause, resume: resume, isActive: isActive };
})();
