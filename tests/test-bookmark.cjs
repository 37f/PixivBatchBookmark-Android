const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/pixiv-task.js'), 'utf8');
const tick = async () => { for (let i = 0; i < 16; i++) await new Promise(setImmediate); };
const csrf = 'abcdefghijklmnopqrstuvwxyz012345';

function response(status, data, url = '') {
  const text = typeof data === 'string' ? data : JSON.stringify(data);
  return { status, url, ok: status >= 200 && status < 300, text: async () => text };
}

function fixture(options = {}) {
  const reports = [], requests = [];
  let saved = !!options.already;
  let savedPrivate = !!options.initialPrivate;
  const meta = { token: csrf, userData: { id: '12345' } };

  const context = {
    console, AbortController, URL, setTimeout, clearTimeout,
    DOMParser: class { parseFromString() { return { querySelector() { return null; }, querySelectorAll() { return []; } }; } },
    location: { protocol: 'https:', hostname: 'www.pixiv.net' },
    document: {
      querySelector(selector) {
        if (selector.includes('meta-global-data')) return { content: JSON.stringify(meta) };
        return null;
      },
      querySelectorAll() { return []; }
    },
    AndroidBridge: { result(...args) { reports.push(args); } },
    fetch: async (url, init = {}) => {
      const u = new URL(url, 'https://www.pixiv.net');
      requests.push({ path: u.pathname, search: u.search, init });

      if (u.pathname === '/ajax/user/extra')
        return response(200, { error: false, message: '', body: { following: 1, followers: 2 } });

      if (u.pathname === '/ajax/user/42658805/profile/all')
        return response(200, { error: false, message: '', body: { illusts: {} } });

      if (u.pathname === '/ajax/illust/42658805') return response(404, 'not found');

      if (u.pathname === '/ajax/illust/97003966') {
        return response(200, { error: false, message: '', body: {
          id: '97003966', title: 'fixture', bookmarkData: saved ? { id: '999', private: savedPrivate } : null
        }});
      }

      if (u.pathname === '/ajax/illusts/bookmarks/edit_restrict') {
        assert.equal(init.method, 'POST');
        assert.equal(init.headers['x-csrf-token'], csrf);
        const body = JSON.parse(init.body);
        assert.deepEqual(body.bookmarkIds, ['999']);
        assert.equal(body.bookmarkRestrict, options.targetPrivate ? 'private' : 'public');
        saved = true;
        savedPrivate = !!options.targetPrivate;
        return response(200, { error: false, message: '', body: null });
      }

      if (u.pathname === '/ajax/illusts/bookmarks/remove') {
        assert.equal(init.method, 'POST');
        assert.deepEqual(JSON.parse(init.body), { bookmarkIds: ['999'] });
        saved = false;
        return response(200, { error: false, message: '', body: null });
      }

      if (u.pathname === '/ajax/illusts/bookmarks/add') {
        const body = JSON.parse(init.body);
        saved = true;
        savedPrivate = !!body.restrict;
        return response(200, { error: false, message: '', body: { last_bookmark_id: '999' } });
      }

      throw new Error('unexpected request ' + u.pathname + u.search);
    }
  };
  context.window = context;
  vm.createContext(context);
  vm.runInContext(source, context);
  return { context, reports, requests, state: () => ({ saved, savedPrivate }) };
}

async function run(f, id, kind, privateMode = false) {
  vm.runInContext(`PixivBatchTask.run('${kind}','7','${id}',${!!privateMode},'${csrf}');`, f.context);
  await tick();
  assert.equal(f.reports.length, 1, 'one callback expected');
  return JSON.parse(f.reports[0][3]);
}

(async () => {
  {
    const f = fixture();
    const r = await run(f, '42658805', 'bookmark', false);
    assert.equal(r.state, 'wrongid');
    assert.match(r.detail, /画师\/用户 ID/);
    assert.equal(f.requests.some(x => x.path === '/ajax/illusts/bookmarks/add'), false);
    console.log('PASS artist/user ID 404 is diagnosed and skipped');
  }

  for (const targetPrivate of [true, false]) {
    const f = fixture({ already: true, initialPrivate: !targetPrivate, targetPrivate });
    const r = await run(f, '97003966', 'bookmark', targetPrivate);
    assert.equal(r.state, 'changed');
    assert.deepEqual(f.state(), { saved: true, savedPrivate: targetPrivate });
    assert.equal(f.requests.some(x => x.path === '/bookmark_add.php'), false);
    assert.equal(f.requests.some(x => x.path === '/ajax/illusts/bookmarks/remove'), false);
    assert.equal(f.requests.some(x => x.path === '/ajax/illusts/bookmarks/edit_restrict'), true);
    console.log('PASS existing bookmark visibility changes in place:', targetPrivate ? 'private' : 'public');
  }

  {
    const f = fixture({ already: true, initialPrivate: true, targetPrivate: true });
    const r = await run(f, '97003966', 'bookmark', true);
    assert.equal(r.state, 'skip');
    assert.equal(f.requests.some(x => x.init.method === 'POST'), false);
    console.log('PASS same visibility is skipped');
  }

  {
    const f = fixture({ already: true, initialPrivate: false });
    const r = await run(f, '97003966', 'unbookmark', false);
    assert.equal(r.state, 'removed');
    assert.deepEqual(f.state(), { saved: false, savedPrivate: false });
    console.log('PASS bulk unbookmark still removes and verifies');
  }

  {
    const f = fixture();
    const r = await run(f, '97003966', 'bookmark', true);
    assert.equal(r.state, 'ok');
    assert.deepEqual(f.state(), { saved: true, savedPrivate: true });
    console.log('PASS new private bookmark is added and verified');
  }
})();
