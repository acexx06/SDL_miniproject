// Checks the hospital's business rules through the HTTP API: booking rules, cancellation, a race for one slot,
// access control, doctor management and the emergency priority queue. 33 checks.
//
// Usage (Node 18+, the application running):   node scripts/business-rules-check.mjs [baseUrl]
// The default base URL is http://localhost:8088. Use a test database: the script books, cancels, adds a doctor
// and raises emergencies. It switches the rate limiter off while it runs (it has its own tests) and back on.
const base = process.argv[2] || 'http://localhost:8088';
let failures = 0;
const check = (ok, what) => { console.log((ok ? 'ok   ' : 'FAIL ') + what); if (!ok) failures++; };
const call = async (method, path, token, body) => {
  const res = await fetch(base + path, { method, redirect: 'manual',
    headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* not JSON */ }
  return { status: res.status, json };
};
const login = async (email, password) => (await call('POST', '/api/auth/login', null, { email, password })).json.token;

const admin = await login('admin@hms.test', 'Admin@12345');
const doctor = await login('doctor@hms.test', 'Doctor@12345');
const patient = await login('patient@hms.test', 'Patient@12345');
const me = (await call('GET', '/api/me', patient)).json;
await call('POST', '/api/admin/config', admin, { rateLimitEnabled: false });

try {
  // ---- doctor management: a new doctor is searchable immediately and can log in ----
  const tag = Date.now().toString(36);
  const added = await call('POST', '/api/admin/doctors', admin, { name: 'Zelda Quillfeather' + tag, specialization: 'Neurology',
    qualification: 'MBBS, MD', experienceYears: 9, feePaise: 70000, rating: 4.6, email: `zelda-${tag}@hms.test`, password: 'Doctor@12345' });
  check(added.status === 201, 'administrator adds a doctor -> 201');
  const sug = (await call('GET', '/api/doctors/suggest?q=quillf')).json;
  check(sug.some(s => s.id === added.json.id), 'the new doctor appears in suggestions straight away');
  check((await call('POST', '/api/admin/doctors', patient, {})).status === 403, 'a patient cannot add doctors -> 403');
  check((await call('POST', '/api/admin/doctors', admin, { name: 'X', specialization: 'Astrology', email: 'a@b.test', password: 'Password@1',
    rating: 4, feePaise: 1, experienceYears: 1 })).status === 400, 'unknown specialization -> 400');
  const newDoctor = await login(`zelda-${tag}@hms.test`, 'Doctor@12345');
  check(!!newDoctor, 'the new doctor can log in');

  // ---- booking rules and cancellation ----
  const day = new Date();
  day.setDate(day.getDate() + 3);
  while (day.getDay() === 0 || day.getDay() === 6) day.setDate(day.getDate() + 1);
  const date = day.toISOString().slice(0, 10);
  const docId = added.json.id;
  check((await call('POST', '/api/appointments', patient, { doctorId: docId, date: '2020-01-01', time: '09:00' })).status === 400, 'booking in the past -> 400');
  check((await call('POST', '/api/appointments', patient, { doctorId: docId, date, time: '09:10' })).status === 400, 'a time that is not a slot -> 400');
  check((await call('POST', '/api/appointments', patient, { doctorId: 'DOCnope', date, time: '09:00' })).status === 404, 'unknown doctor -> 404');
  check((await call('POST', '/api/appointments', doctor, { doctorId: docId, date, time: '09:00' })).status === 403, 'only patients book -> 403');
  const booked = await call('POST', '/api/appointments', patient, { doctorId: docId, date, time: '10:00', reason: 'check' });
  check(booked.status === 201, 'booking -> 201');
  let slots = (await call('GET', `/api/doctors/${docId}/slots?date=${date}`)).json;
  check(slots.find(s => s.time === '10:00').available === false, 'the booked slot shows as taken');
  check((await call('GET', `/api/doctor/appointments?date=${date}`, newDoctor)).json.length === 1, "it is on the new doctor's schedule");
  check((await call('GET', `/api/doctor/appointments?date=${date}`, doctor)).json.every(a => a.id !== booked.json.id), "and not on another doctor's");
  check((await call('POST', `/api/appointments/${booked.json.id}/cancel`, doctor)).status === 403, 'an unrelated doctor cannot cancel it -> 403');
  const cancelled = await call('POST', `/api/appointments/${booked.json.id}/cancel`, patient);
  check(cancelled.status === 200 && cancelled.json.status === 'CANCELLED', 'the patient cancels -> CANCELLED');
  slots = (await call('GET', `/api/doctors/${docId}/slots?date=${date}`)).json;
  check(slots.find(s => s.time === '10:00').available === true, 'the slot is free again');
  check((await call('GET', `/api/bills/${booked.json.billId}`, patient)).json.status === 'CANCELLED', 'its unpaid bill was voided');
  check((await call('POST', `/api/bills/${booked.json.billId}/pay`, patient, { method: 'UPI' })).status === 409, 'a voided bill cannot be paid -> 409');
  check((await call('POST', `/api/appointments/${booked.json.id}/cancel`, patient)).status === 409, 'cancelling twice -> 409');
  const rebooked = await call('POST', '/api/appointments', patient, { doctorId: docId, date, time: '10:00' });
  check(rebooked.status === 201, 'the freed slot can be booked again');
  await call('POST', `/api/appointments/${rebooked.json.id}/cancel`, patient);

  // ---- twelve simultaneous requests for one slot: exactly one may win ----
  const race = await Promise.all(Array.from({ length: 12 }, () => call('POST', '/api/appointments', patient, { doctorId: docId, date, time: '11:30' })));
  const wins = race.filter(r => r.status === 201).length;
  check(wins === 1 && race.every(r => [201, 409].includes(r.status)),
    `12 simultaneous bookings of one slot: ${wins} succeeded, ${race.filter(r => r.status === 409).length} got 409`);

  // ---- access control ----
  const pharmacist = await login('pharmacist@hms.test', 'Pharma@12345');
  check((await call('GET', `/api/patients/${me.user.id}/records`, pharmacist)).status === 403, 'the pharmacy cannot read clinical records -> 403');
  check((await call('GET', `/api/patients/${me.user.id}`, null)).status === 401, 'no token -> 401');
  check((await call('GET', '/api/admin/metrics', doctor)).status === 403, 'a doctor cannot open the admin console -> 403');
  check((await call('POST', '/api/shorten', patient, { target: 'https://evil.example/', ttlHours: 1 })).status === 400, 'a short link to another site -> 400');

  // ---- emergency queue: fill every ambulance, then requests wait and are served most severe first ----
  const ambulances = (await call('GET', '/api/emergency/ambulances', admin)).json;
  const free = ambulances.filter(a => a.status === 'FREE').length;
  const raised = [];
  for (let i = 0; i < free; i++) {
    raised.push((await call('POST', '/api/emergency', null, { callerName: 'Queue Test', phone: '9000000004', zone: 'Old Town', severity: 4, description: 'fills an ambulance' })).json);
  }
  check(raised.every(r => r.status === 'DISPATCHED'), `${free} requests take all ${free} free ambulances`);
  check(new Set(raised.map(r => r.ambulanceCallSign)).size === free, 'each got a different ambulance');
  const minor = (await call('POST', '/api/emergency', null, { callerName: 'Queue Test', phone: '9000000004', zone: 'Central', severity: 5, description: 'minor, waits' })).json;
  const critical = (await call('POST', '/api/emergency', null, { callerName: 'Queue Test', phone: '9000000004', zone: 'Central', severity: 1, description: 'critical, waits' })).json;
  check(minor.status === 'QUEUED' && critical.status === 'QUEUED', 'with none free, new requests are queued');
  check((await call('GET', `/api/emergency/${critical.id}`)).json.queuePosition === 1 && (await call('GET', `/api/emergency/${minor.id}`)).json.queuePosition === 2,
    'the critical request is ahead of the earlier minor one in the queue');
  await call('POST', `/api/emergency/${raised[0].id}/resolve`, doctor);
  check((await call('GET', `/api/emergency/${critical.id}`)).json.status === 'DISPATCHED', 'when an ambulance is released the critical request gets it');
  check((await call('GET', `/api/emergency/${minor.id}`)).json.status === 'QUEUED', 'the minor request keeps waiting');
  for (const r of [...raised.slice(1), critical, minor]) await call('POST', `/api/emergency/${r.id}/resolve`, admin);
  check((await call('GET', '/api/emergency/ambulances', admin)).json.every(a => a.status === 'FREE'), 'after resolving everything all ambulances are free');
  check((await call('GET', '/api/emergency', patient)).status === 403, 'patients cannot see the emergency board -> 403');
} finally {
  await call('POST', '/api/admin/config', admin, { rateLimitEnabled: true });
}

console.log(failures ? `${failures} CHECK(S) FAILED` : 'all business-rule checks passed');
process.exit(failures ? 1 : 0);
