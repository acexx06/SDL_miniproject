// Drives the web portal through every role (visitor, patient, doctor, pharmacist, administrator) in a headless
// Microsoft Edge or Chrome over the DevTools protocol: 49 checks, screenshots of every page, and a report of any
// JavaScript errors. Light, dark and phone layouts are covered.
//
// Usage (Node 22+, the application running):
//   node scripts/ui-check.mjs [baseUrl] [screenshotDir] [browserExe]
// Defaults: http://localhost:8088, ./ui-check-output, Microsoft Edge in its standard Windows location.
// Use a test database: the script books, pays, prescribes and raises emergencies.
import { spawn } from 'node:child_process';
import fs from 'node:fs';

const base = process.argv[2] || "http://localhost:8088";
const outDir = process.argv[3] || "ui-check-output";
const edgePath = process.argv[4] || 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const sleep = ms => new Promise(r => setTimeout(r, ms));
fs.mkdirSync(outDir, { recursive: true });
const edge = spawn(edgePath, ['--headless=new', '--remote-debugging-port=9334', '--user-data-dir=' + outDir + '/edge-profile',
  '--no-first-run', '--disable-gpu', '--disable-extensions', 'about:blank'], { stdio: 'ignore' });
let page;
for (let i = 0; i < 30 && !page; i++) {
  await sleep(500);
  try { page = (await (await fetch('http://127.0.0.1:9334/json')).json()).find(t => t.type === 'page'); } catch { /* starting */ }
}
if (!page) { console.log('could not connect to Edge'); edge.kill(); process.exit(1); }
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise(r => ws.addEventListener('open', r));
let id = 0;
const pending = new Map();
const errors = [];
let failures = 0;
ws.addEventListener('message', ev => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) { pending.get(m.id)(m); pending.delete(m.id); }
  else if (m.method === 'Runtime.exceptionThrown') errors.push('EXCEPTION: ' + (m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text));
  else if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') errors.push('console.error: ' + m.params.args.map(a => a.value ?? a.description).join(' '));
  else if (m.method === 'Log.entryAdded' && m.params.entry.level === 'error' && !/favicon|status of 4\d\d/.test(m.params.entry.text)) errors.push('log: ' + m.params.entry.text);
});
const send = (method, params = {}) => new Promise(res => { const i = ++id; pending.set(i, res); ws.send(JSON.stringify({ id: i, method, params })); });
const js = async expr => {
  const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
  if (r.result.exceptionDetails) { errors.push('EVAL: ' + expr.slice(0, 90) + ' -> ' + (r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text)); return undefined; }
  return r.result.result.value;
};
const waitFor = async (expr, ms = 8000) => { const end = Date.now() + ms; while (Date.now() < end) { if (await js(`!!(${expr})`)) return true; await sleep(150); } return false; };
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) failures++; };
const shot = async (name, full = false) => {
  let params = { format: 'png' };
  if (full) {
    const metrics = await send('Page.getLayoutMetrics');
    const h = Math.min(4000, Math.ceil(metrics.result.cssContentSize.height));
    params = { format: 'png', captureBeyondViewport: true, clip: { x: 0, y: 0, width: metrics.result.cssLayoutViewport.clientWidth, height: h, scale: 1 } };
  }
  const r = await send('Page.captureScreenshot', params);
  fs.writeFileSync(`${outDir}/${name}.png`, Buffer.from(r.result.data, 'base64'));
};
const viewport = (width, height, mobile = false) => send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile });
const scheme = dark => send('Emulation.setEmulatedMedia', { features: [{ name: 'prefers-color-scheme', value: dark ? 'dark' : 'light' }] });
const nav = async hash => { await js(`location.hash = '${hash}'`); await sleep(900); };
const toastText = () => js(`[...document.querySelectorAll('.toast')].map(t => t.textContent).join(' | ')`);
const type = async (sel, text) => js(`(() => { const i = document.querySelector('${sel}'); i.focus(); i.value = ${JSON.stringify(text)}; i.dispatchEvent(new Event('input')); })()`);
const key = async (sel, k) => js(`document.querySelector('${sel}').dispatchEvent(new KeyboardEvent('keydown', {key: '${k}', bubbles: true}))`);
const signOut = async () => { await js(`document.querySelector('#signout')?.click()`); await waitFor(`location.hash === '#/welcome'`); await sleep(500); };
const demo = async i => { await js(`document.querySelector('[data-demo="${i}"]').click()`); await sleep(2200); };

await send('Page.enable'); await send('Runtime.enable'); await send('Log.enable');
await viewport(1360, 960);
await scheme(false);
await send('Page.navigate', { url: base + '/#/welcome' });
await sleep(2500);
await js(`localStorage.clear(); sessionStorage.clear()`);
await send('Page.navigate', { url: base + '/#/welcome' });
await sleep(2000);
check(await js(`document.querySelector('#app').classList.contains('landing')`), 'signed out: the landing page fills the screen');
check(await js(`getComputedStyle(document.querySelector('#sidebar')).display`) === 'none', 'signed out: no navigation into the rest of the system');
check(await js(`!!document.querySelector('.emergency-cta')`), 'signed out: the ambulance request is offered');
await shot('01-welcome-light');

// ---- signed-out visitor: only the ambulance request is open ----
await nav('#/doctors');
check(await js(`location.hash`) === '#/welcome', 'signed out: "Find a doctor" leads to the sign-in page');
check(((await toastText()) || '').includes('sign in'), 'signed out: with a message asking to sign in');
await nav('#/emergency');
check(await js(`getComputedStyle(document.querySelector('#guestbar')).display !== 'none' && getComputedStyle(document.querySelector('#gb-signin')).display !== 'none'`),
  'signed out: the ambulance page has a slim bar with "Sign in"');
await js(`document.querySelector('#e-name').value = 'Visitor'; document.querySelector('#e-phone').value = '9000000006'; document.querySelector('#e-form button').click()`);
await waitFor(`!!document.querySelector('#e-refresh')`);
check(await js(`/Dispatched|Queued/.test(document.querySelector('#e-status').textContent)`), 'signed out: an ambulance can be requested without an account');
await shot('02-emergency-visitor');

// ---- after signing in, the visitor is taken to the page they asked for ----
await nav('#/welcome');
await demo(0);
check(await js(`location.hash`) === '#/doctors', 'after signing in, the patient lands on the page they first asked for');
check(await js(`getComputedStyle(document.querySelector('#guestbar')).display === 'none' && getComputedStyle(document.querySelector('#sidebar')).display !== 'none'`),
  'signed in: the sidebar replaces the visitor bar');
await type('#doc-q', 'card');
await waitFor(`!document.querySelector('.combo-list').hidden`);
await shot('02-doctor-suggest');
check((await js(`document.querySelector('.combo-list li')?.textContent`) || '').includes('Cardiology'), 'typing "card" suggests Cardiology first');
check((await js(`document.querySelector('#doc-meta').textContent`) || '').includes('Autocomplete'), 'suggestion meta credits the autocomplete component');
await key('#doc-q', 'ArrowDown');
check(await js(`document.querySelector('.combo-list li').getAttribute('aria-selected')`) === 'true', 'arrow key highlights the first suggestion');
await key('#doc-q', 'Enter');
await sleep(1200);
check(await js(`document.querySelector('[data-spec="Cardiology"]').getAttribute('aria-pressed')`) === 'true', 'Enter on a specialization applies it as a filter');
check(await js(`document.querySelectorAll('#doc-results .doc').length`) > 5, 'Cardiology doctors listed');
await type('#doc-q', 'cardoilogy');
await waitFor(`!document.querySelector('.combo-list').hidden`);
check((await js(`document.querySelector('.combo-list li')?.textContent`) || '').includes('Cardiology'), 'the typo "cardoilogy" still suggests Cardiology');
await key('#doc-q', 'Escape');

// ---- patient ----
await nav('#/home');
check(await js(`document.querySelectorAll('.stat').length`) === 4, 'patient overview shows four summary tiles');
await shot('03-patient-home', true);
await nav('#/doctors');
await sleep(800);
await js(`document.querySelector('[data-book]').click()`);
await waitFor(`document.querySelector('#modal').open && document.querySelectorAll('.day').length`);
const book = async () => {
  // pick the first working day at least two days ahead that has a free slot, then the first free slot
  const days = await js(`[...document.querySelectorAll('.day:not([disabled])')].map(b => b.dataset.day)`);
  for (const d of days.slice(2)) {
    await js(`document.querySelector('.day[data-day="${d}"]').click()`);
    await sleep(700);
    if (await js(`!!document.querySelector('.slot:not([disabled])')`)) break;
  }
  await js(`document.querySelector('.slot:not([disabled])').click()`);
  await js(`document.querySelector('#b-reason').value = 'UI check'`);
  await js(`document.querySelector('#b-go').click()`);
  return waitFor(`document.querySelector('#modal-title')?.textContent === 'Appointment booked'`);
};
await shot('04-booking-dialog');
check(await book(), 'booking succeeds and shows the confirmation dialog');
await shot('05-booked');
await js(`document.querySelector('#modal a[href="#/appointments"]').click()`);
await sleep(1200);
const upcoming = await js(`document.querySelectorAll('[data-cancel]').length`);
check(upcoming >= 1, `appointments page lists the booking (${upcoming} cancellable)`);
await js(`document.querySelector('[data-cancel]').click()`);
await waitFor(`document.querySelector('#modal').open`);
await shot('06-cancel-confirm');
await js(`document.querySelector('#modal [data-ok]').click()`);
await sleep(1200);
check((await toastText()).includes('cancelled'), 'cancelling asks for confirmation and succeeds');
await nav('#/doctors');
await sleep(600);
await js(`document.querySelector('[data-book]').click()`);
await waitFor(`document.querySelector('#modal').open && document.querySelectorAll('.day').length`);
check(await book(), 'a second booking succeeds');
await js(`document.querySelector('#modal a[href^="#/bills/"]').click()`);
await sleep(1500);
check(await js(`!!document.querySelector('.bill.focus')`), 'the confirmation links straight to the new bill, highlighted');
await js(`document.querySelector('.bill.focus [data-pay]').click()`);
await waitFor(`document.querySelector('#modal').open`);
await js(`document.querySelector('#pay-m').value = 'CARD'; document.querySelector('#modal [data-ok]').click()`);
await sleep(1600);
check((await toastText()).includes('Payment recorded'), 'paying through the dialog works');
await js(`document.querySelector('[data-share]').click()`);
await sleep(1200);
const link = await js(`document.querySelector('.linkbox a')?.textContent`);
check(/\/s\/[A-Za-z0-9]{8}$/.test(link || ''), `share creates a short link (${link})`);
await js(`document.querySelector('#ins-plan').selectedIndex = 3; document.querySelector('#ins-plan').dispatchEvent(new Event('change'))`);
check(await js(`document.querySelectorAll('#ins-detail tr').length`) === 6, 'choosing a plan shows its cover');
await js(`document.querySelector('#ins-save').click()`);
await sleep(900);
await shot('07-bills', true);
await nav('#/health');
await shot('08-health');
await nav('#/pharmacy');
await type('#m-q', 'zyv');
await waitFor(`!document.querySelector('.combo-list').hidden`);
await key('#m-q', 'ArrowDown');
await key('#m-q', 'Enter');
await waitFor(`!!document.querySelector('#m-detail .amount')`);
check(await js(`document.querySelector('#m-detail').textContent.includes('Reference information')`), 'medicine detail shows stock and crawled information');
await shot('09-medicines');
await nav('#/emergency');
await js(`document.querySelector('#e-name').value = 'UI Check'; document.querySelector('#e-phone').value = '9000000005'; document.querySelector('input[name=severity][value="2"]').click(); document.querySelector('#e-form button').click()`);
await waitFor(`!!document.querySelector('#e-refresh')`);
check(await js(`document.querySelector('#e-status').textContent.includes('Dispatched') || document.querySelector('#e-status').textContent.includes('Queued')`), 'emergency request shows dispatch status');
await shot('10-emergency');
await signOut();

// ---- doctor ----
await demo(1);
check(await js(`location.hash`) === '#/schedule', 'doctor lands on the schedule');
await shot('11-doctor-schedule');
await nav('#/patients');
await js(`document.querySelector('#pl').value = 'patient@hms.test'; document.querySelector('#lookup button').click()`);
await waitFor(`!!document.querySelector('#rec-form')`);
check(await js(`location.hash.startsWith('#/patients/PAT')`), 'lookup by email opens the patient');
const recTitle = 'Seasonal allergy ' + Date.now();
await js(`document.querySelector('#rec-title').value = '${recTitle}'; document.querySelector('#rec-details').value = 'Synthetic.'; document.querySelector('#rec-form button').click()`);
await waitFor(`document.querySelector('#rec-list')?.textContent.includes('${recTitle}')`);
check(await js(`document.querySelector('#rec-list').textContent.includes('${recTitle}')`), 'doctor adds a record and sees it in the history');
await type('#rx-med', 'zyv');
await waitFor(`!document.querySelector('.combo-list').hidden`);
await key('#rx-med', 'ArrowDown');
await key('#rx-med', 'Enter');
await sleep(300);
check(await js(`document.querySelectorAll('#rx-items tr').length`) === 2, 'medicine added to the prescription');
await js(`document.querySelector('#rx-go').click()`);
await sleep(1200);
check((await toastText()).includes('issued'), 'prescription issued');
await shot('12-doctor-patient', true);
await nav('#/emergencies');
await sleep(800);
await shot('13-emergency-board');
const open = await js(`document.querySelectorAll('[data-res]').length`);
await js(`document.querySelector('[data-res]')?.click()`);
await waitFor(`document.querySelector('#modal').open`);
await js(`document.querySelector('#modal [data-ok]').click()`);
await sleep(1500);
check(await js(`document.querySelectorAll('[data-res]').length`) === open - 1, 'resolving removes the request from the board');
await signOut();

// ---- pharmacist ----
await demo(2);
check(await js(`location.hash`) === '#/dispense', 'pharmacist lands on the pharmacy counter');
await shot('14-dispense');
await js(`document.querySelector('[data-disp]').click()`);
await sleep(1800);
check((await toastText()).includes('Dispensed'), 'dispensing raises a bill');
await signOut();

// ---- administrator ----
await demo(3);
check(await js(`location.hash`) === '#/system', 'administrator lands on the system page');
await sleep(7000);
check(await js(`document.querySelectorAll('#comp-tiles .comp').length`) === 8, 'eight component tiles (seven components and MongoDB)');
check(await js(`document.querySelectorAll('#ring path').length`) === 3, 'ring donut shows three servers');
check(await js(`document.querySelectorAll('#ch-rps path').length`) >= 2, 'requests-per-second chart has a line');
await js(`(() => { const s = document.querySelector('#ch-rps svg'); const r = s.getBoundingClientRect(); s.dispatchEvent(new PointerEvent('pointermove', {clientX: r.right - 20, clientY: r.top + 50, bubbles: true})); })()`);
await sleep(200);
check(await js(`getComputedStyle(document.querySelector('#ch-rps .tooltip')).display`) === 'block', 'hovering the chart shows a tooltip');
await shot('15-system', true);
await js(`document.querySelector('[data-kill="n2"]').click()`);
await waitFor(`document.querySelector('#modal').open`);
await js(`document.querySelector('#modal [data-ok]').click()`);
await sleep(3500);
check(await js(`document.querySelectorAll('#ring path').length`) === 2, 'after stopping n2 the ring has two servers');
check(await js(`!!document.querySelector('[data-restart="n2"]')`), 'n2 can be restarted');
await shot('16-system-node-down');
await js(`document.querySelector('[data-restart="n2"]').click()`);
await sleep(3500);
check(await js(`document.querySelectorAll('#ring path').length`) === 3, 'n2 rejoins the ring');
await js(`document.querySelector('#flood').click()`);
await sleep(2500);
check((await js(`document.querySelector('#flood-out').textContent`) || '').includes('429'), 'rate limiter test shows refused requests');
await nav('#/staff');
await js(`document.querySelector('#d-name').value = 'Ira Quillon'; document.querySelector('#d-email').value = 'ira.' + Date.now() + '@hms.test'; document.querySelector('#d-pass').value = 'Doctor@12345'; document.querySelector('#doc-form button').click()`);
await sleep(1500);
check((await js(`document.querySelector('#doc-added').textContent`) || '').includes('Searchable now'), 'administrator adds a doctor');
await shot('17-staff');

// ---- white theme by default whatever the operating system says; dark only on request ----
await scheme(true);
await nav('#/system');
await sleep(1500);
check(await js(`getComputedStyle(document.body).backgroundColor`) === 'rgb(252, 250, 248)', 'the light theme is used even when the system prefers dark');
await js(`document.querySelector('#theme-btn').click()`);
await sleep(5000);
check(await js(`document.documentElement.dataset.theme`) === 'dark', 'the theme button switches to dark');
await shot('18-system-dark', true);
await nav('#/doctors');
await sleep(1200);
await shot('19-doctors-dark');
await js(`document.querySelector('#theme-btn').click()`);
await scheme(false);
await sleep(300);
await signOut();
await viewport(390, 844, true);
await demo(0);
await sleep(800);
await shot('20-phone-home', true);
await js(`document.querySelector('#menu-btn').click()`);
await sleep(400);
check(await js(`document.querySelector('#app').classList.contains('nav-open')`), 'phone: menu button opens the navigation drawer');
await shot('21-phone-menu');
check(await js(`document.documentElement.scrollWidth <= innerWidth + 1`), 'phone: no horizontal scrolling');
await nav('#/bills');
await sleep(800);
check(await js(`document.documentElement.scrollWidth <= innerWidth + 1`), 'phone: bills page fits the width');
await shot('22-phone-bills', true);

console.log(errors.length ? 'JS ERRORS:\n' + errors.join('\n') : 'no JavaScript errors');
console.log(failures ? `${failures} CHECK(S) FAILED` : 'all UI checks passed');
ws.close();
edge.kill();
process.exit(0);
