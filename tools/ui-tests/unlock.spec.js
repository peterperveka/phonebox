// The fingerprint-unlock card and buttons: invisible unless the app supports it and the agent switched it on; the card
// text comes from the app's unlockPrompt, never from the agent; Use password cancels, the screen button signs "screen".
const { test, expect } = require('@playwright/test');

async function open(page, { state, supported = true, prompt = '{"title":"sudo from the app","detail":"detail from the app"}', code = 'ABC123', android = true, listen = false, sleep = false }) {
  await page.addInitScript(({ supported, prompt, code, android, listen, sleep }) => {
    window.__phoneboxKey = 'k';
    window.__signs = [];
    window.__setups = 0;
    window.__listens = [];
    window.__sleeps = 0;
    window.__a11y = 0;
    window.__canSleep = true;
    if (!android) return;
    window.Android = {
      unlockSupported: () => supported,
      unlockSetup: () => { window.__setups++; return 'XYZ789'; },
      unlockCode: () => code,
      unlockPrompt: () => prompt,
      unlockSign: (key, id, challenge, mode) => { window.__signs.push({ key, id, challenge, mode }); },
    };
    if (listen) window.Android.unlockListen = (key, on) => { window.__listens.push(on); };
    if (sleep) Object.assign(window.Android, {
      canSleep: () => window.__canSleep,
      sleepPhone: () => { window.__sleeps++; return true; },
      openAccessibilitySettings: () => { window.__a11y++; },
    });
  }, { supported, prompt, code, android, listen, sleep });
  await page.goto('/panel/');
  await expect(page.locator('#dots i')).not.toHaveCount(0);
  await page.evaluate(async (state) => {
    window.__posts = [];
    window.__state = state;
    const real = window.fetch;
    window.fetch = (input, init) => {
      const url = typeof input === 'string' ? input : input.url;
      if (url.includes('/unlock/state')) return Promise.resolve(new Response(JSON.stringify(window.__state), { headers: { 'content-type': 'application/json' } }));
      if (url.includes('/unlock/answer')) { window.__posts.push(JSON.parse(init.body)); return Promise.resolve(new Response('{}', { headers: { 'content-type': 'application/json' } })); }
      return real(input, init);
    };
    const m = await import('/panel/main.js');
    await m.loadUnlock();
  }, state);
}

const card = (page) => page.locator('#unlockCard');

test('nothing shows while the feature is off', async ({ page }) => {
  await open(page, { state: { enabled: false, enrolled: false, locked: true, screen: 'c', request: { id: 'r', challenge: 'c' } } });
  await expect(card(page)).toBeHidden();
  await expect(page.locator('#unlockBar')).toBeHidden();
});

test('nothing shows when the app cannot sign', async ({ page }) => {
  const state = { enabled: true, enrolled: true, locked: true, screen: 'c', request: { id: 'r', challenge: 'c' } };
  await open(page, { state, supported: false });
  await expect(card(page)).toBeHidden();
  await expect(page.locator('#unlockBar')).toBeHidden();
});

test('nothing shows in a plain browser', async ({ page }) => {
  await open(page, { state: { enabled: true, enrolled: false, locked: true, screen: 'c', request: null }, android: false });
  await expect(page.locator('#unlockBar')).toBeHidden();
});

test('the card text comes from the app, not the agent; Use fingerprint signs the request', async ({ page }) => {
  await open(page, { state: { enabled: true, enrolled: true, locked: false, screen: null, request: { id: 'r1', challenge: 'chal', title: 'AGENT TEXT' } } });
  await expect(card(page)).toBeVisible();
  await expect(page.locator('#ukTitle')).toHaveText('sudo from the app');
  await expect(page.locator('#ukDetail')).toHaveText('detail from the app');
  await expect(card(page)).not.toContainText('AGENT TEXT');
  await page.locator('#ukFp').click();
  expect(await page.evaluate(() => window.__signs)).toEqual([{ key: 'k', id: 'r1', challenge: 'chal', mode: 'request' }]);
});

test('an empty prompt from the app shows no card', async ({ page }) => {
  await open(page, { prompt: '', state: { enabled: true, enrolled: true, locked: false, screen: null, request: { id: 'r1', challenge: 'chal' } } });
  await expect(card(page)).toBeHidden();
});

test('Use password answers with a null signature', async ({ page }) => {
  await open(page, { state: { enabled: true, enrolled: true, locked: false, screen: null, request: { id: 'r1', challenge: 'chal' } } });
  await page.locator('#ukPw').click();
  await expect.poll(() => page.evaluate(() => window.__posts)).toEqual([{ id: 'r1', signature: null }]);
});

test('Unlock PC signs the screen challenge in screen mode', async ({ page }) => {
  await open(page, { state: { enabled: true, enrolled: true, locked: true, screen: 'scr', request: null } });
  await expect(page.locator('#ukScreen')).toBeVisible();
  await expect(page.locator('#ukSetup')).toBeHidden();
  await page.locator('#ukScreen').click();
  expect(await page.evaluate(() => window.__signs)).toEqual([{ key: 'k', id: 'screen', challenge: 'scr', mode: 'screen' }]);
});

test('setup shows the check code, and a failed signing shows "Not unlocked"', async ({ page }) => {
  await open(page, { state: { enabled: true, enrolled: false, locked: false, screen: null, request: null } });
  await page.locator('#ukSetup').click();
  await expect(page.locator('#ukMsg')).toContainText('XYZ789');
  await page.evaluate(() => window.pbUnlockDone('screen', false));
  await expect(page.locator('#ukMsg')).toHaveText('Not unlocked');
});

test('a phone that lost its key is offered setup again', async ({ page }) => {
  await open(page, { code: '', state: { enabled: true, enrolled: true, locked: false, screen: null, request: null } });
  await expect(page.locator('#ukSetup')).toBeVisible();
});

test('an approval stays answerable once the unlock card is gone', async ({ page }) => {
  await open(page, { state: { enabled: true, enrolled: true, locked: false, screen: null, request: { id: 'r1', challenge: 'chal' } } });
  await page.evaluate(async () => {
    const m = await import('/panel/main.js');
    m.approvals.splice(0, m.approvals.length, { id: 'a1', kind: 'permission', source: 'Claude', tool: 'Bash', project: 'p', cwd: '/x' });
    m.renderApproval();
  });
  await expect(card(page)).toBeVisible();
  await expect(page.locator('#approval')).toBeVisible();
  await page.evaluate(async () => {
    window.__state = { enabled: true, enrolled: true, locked: false, screen: null, request: null };
    const m = await import('/panel/main.js');
    await m.loadUnlock();
  });
  await expect(card(page)).toBeHidden();
  await expect(page.locator('#approval')).toBeVisible();
  await page.locator('#apDeny').click({ trial: true });
});

test('the card buttons come back after a failed signing', async ({ page }) => {
  await open(page, { state: { enabled: true, enrolled: true, locked: false, screen: null, request: { id: 'r1', challenge: 'chal' } } });
  await page.locator('#ukFp').click();
  await expect(page.locator('#ukPw')).toBeDisabled();
  await page.evaluate(() => window.pbUnlockDone('r1', false));
  await expect(page.locator('#ukPw')).toBeEnabled();
  await expect(page.locator('#ukFp')).toBeEnabled();
});

// ---------------------------------------------------------------- touch to unlock: the app listens on the sensor, no dialog
const locked = () => ({ enabled: true, enrolled: true, locked: true, screen: 'scr', request: null });
const reload = (page, change) => page.evaluate(async (change) => {
  Object.assign(window.__state, change);
  const m = await import('/panel/main.js');
  await m.loadUnlock();
}, change);

test('while the PC is locked the app is asked to listen and the touch hint shows', async ({ page }) => {
  await open(page, { state: locked(), listen: true });
  expect(await page.evaluate(() => window.__listens)).toEqual([false, true]);
  await page.evaluate(() => window.pbUnlockListen('listening'));
  await expect(page.locator('#ukTouch')).toHaveText('Touch the sensor to unlock the PC');
  await expect(page.locator('#ukTouch')).toBeVisible();
  await expect(page.locator('#ukScreen')).toBeHidden();
});

test('stopped brings the Unlock PC button back', async ({ page }) => {
  await open(page, { state: locked(), listen: true });
  await page.evaluate(() => { window.pbUnlockListen('listening'); window.pbUnlockListen('stopped'); });
  await expect(page.locator('#ukScreen')).toBeVisible();
  await expect(page.locator('#ukTouch')).toBeHidden();
});

test('unlocking the PC stops listening', async ({ page }) => {
  await open(page, { state: locked(), listen: true });
  await reload(page, { locked: false, screen: null });
  expect(await page.evaluate(() => window.__listens)).toEqual([false, true, false]);
});

test('a sudo card stops listening', async ({ page }) => {
  await open(page, { state: locked(), listen: true });
  await reload(page, { request: { id: 'r', challenge: 'c' } });
  expect(await page.evaluate(() => window.__listens)).toEqual([false, true, false]);
});

test('failed shows Not unlocked and keeps listening', async ({ page }) => {
  await open(page, { state: locked(), listen: true });
  await page.evaluate(() => { window.pbUnlockListen('listening'); window.pbUnlockListen('failed'); });
  await expect(page.locator('#ukMsg')).toHaveText('Not unlocked');
  expect(await page.evaluate(() => window.__listens)).toEqual([false, true]);
});

test('an app without unlockListen keeps the Unlock PC button', async ({ page }) => {
  await open(page, { state: locked() });
  await expect(page.locator('#ukScreen')).toBeVisible();
  await expect(page.locator('#ukTouch')).toBeHidden();
});

test('a fresh panel tells the app not to listen while the PC is unlocked', async ({ page }) => {
  await open(page, { state: { enabled: true, enrolled: true, locked: false, screen: null, request: null }, listen: true });
  expect(await page.evaluate(() => window.__listens)).toEqual([false]);
});

// ---------------------------------------------------------------- the phone sleeps with the PC (follow the computer lock)
const unlocked = () => ({ enabled: true, enrolled: true, locked: false, screen: null, request: null });
const follow = (page, on) => page.evaluate(async (on) => {
  const m = await import('/panel/main.js');
  m.cfg.display.followComputerLock = on;
  m.renderUnlock();
}, on);

test('the phone sleeps once when the PC locks', async ({ page }) => {
  await open(page, { state: unlocked(), sleep: true });
  await follow(page, true);
  await reload(page, { locked: true, screen: 'scr' });
  expect(await page.evaluate(() => window.__sleeps)).toBe(1);
  await reload(page, {});
  expect(await page.evaluate(() => window.__sleeps)).toBe(1);
});

test('a phone woken while the PC stays locked is not slept again', async ({ page }) => {
  await open(page, { state: unlocked(), sleep: true });
  await follow(page, true);
  await reload(page, { locked: true, screen: 'scr' });
  await reload(page, {});
  await reload(page, {});
  expect(await page.evaluate(() => window.__sleeps)).toBe(1);
});

test('a fresh panel on an already locked PC does not sleep the phone', async ({ page }) => {
  await open(page, { state: locked(), sleep: true });
  await follow(page, true);
  await reload(page, {});
  expect(await page.evaluate(() => window.__sleeps)).toBe(0);
});

test('follow-lock off never sleeps the phone', async ({ page }) => {
  await open(page, { state: unlocked(), sleep: true });
  await follow(page, false);
  await reload(page, { locked: true, screen: 'scr' });
  expect(await page.evaluate(() => window.__sleeps)).toBe(0);
});

test('without the service the bar offers to turn it on', async ({ page }) => {
  await open(page, { state: unlocked(), sleep: true });
  await page.evaluate(() => { window.__canSleep = false; });
  await follow(page, true);
  await expect(page.locator('#ukSleep')).toHaveText('Let the phone sleep with the PC');
  await page.locator('#ukSleepOn').click();
  expect(await page.evaluate(() => window.__a11y)).toBe(1);
  await page.evaluate(() => { window.__canSleep = true; });
  await follow(page, true);
  await expect(page.locator('#ukSleep')).toBeHidden();
});

test('a PC that locked while the panel was paused does not sleep the phone on wake', async ({ page }) => {
  await open(page, { state: unlocked(), sleep: true });
  await follow(page, true);
  await page.evaluate(() => window.onAppPause(true));
  await page.evaluate(() => { Object.assign(window.__state, { locked: true, screen: 'scr' }); window.onAppPause(false); });
  await reload(page, {});
  expect(await page.evaluate(() => window.__sleeps)).toBe(0);
});
