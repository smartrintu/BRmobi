const express = require('express'), multer = require('multer'), fs = require('fs'),
      path = require('path'), crypto = require('crypto');
const app = express();
const ADMIN_KEY = process.env.ADMIN_KEY || 'change-me';
const UP = path.join(__dirname, 'uploads'); fs.mkdirSync(UP, { recursive: true });
const DB = path.join(__dirname, 'movies.json');
const read = () => fs.existsSync(DB) ? JSON.parse(fs.readFileSync(DB)) : [];
const write = (a) => fs.writeFileSync(DB, JSON.stringify(a, null, 2));
const upload = multer({ storage: multer.diskStorage({
  destination: UP,
  filename: (r, f, cb) => cb(null, crypto.randomUUID() + path.extname(f.originalname)) }) });
const auth = (q, s, n) => q.get('x-admin-key') === ADMIN_KEY ? n() : s.status(401).json({ error: 'unauthorized' });

app.use('/uploads', express.static(UP));                      // Range support => seeking works
app.get('/api/movies', (q, s) => s.json(read().reverse()));
app.post('/api/movies', auth, upload.fields([{ name: 'video', maxCount: 1 }, { name: 'thumb', maxCount: 1 }]),
  (q, s) => {
    if (!q.files || !q.files.video) return s.status(400).json({ error: 'video required' });
    const m = { id: crypto.randomUUID(), title: q.body.title || 'Untitled', description: q.body.description || '',
      category: String(q.body.category || '').trim() || 'General', language: String(q.body.language || '').trim(),
      videoPath: '/uploads/' + q.files.video[0].filename,
      thumbPath: q.files.thumb ? '/uploads/' + q.files.thumb[0].filename : '' };
    const all = read(); all.push(m); write(all);
    s.json(m);
  });
app.delete('/api/movies/:id', auth, (q, s) => {
  const all = read(), m = all.find(x => x.id === q.params.id);
  if (m) for (const p of [m.videoPath, m.thumbPath]) if (p) fs.unlink(path.join(__dirname, p), () => {});
  write(all.filter(x => x.id !== q.params.id)); s.json({ ok: true });
});
// ---- Chunked + parallel upload (badi files ke liye; proxy ki size limit bypass) ----
const tmpFile = id => path.join(UP, 'tmp-' + id + '.part');
const metaFile = id => tmpFile(id) + '.json';
const okId = id => /^[a-f0-9-]{36}$/.test(id || '');
const readMeta = id => fs.existsSync(metaFile(id)) ? JSON.parse(fs.readFileSync(metaFile(id))) : { received: [] };
app.post('/api/chunk', auth, express.raw({ type: 'application/octet-stream', limit: '32mb' }), (q, s) => {
  const id = q.get('x-upload-id'), idx = Number(q.get('x-index')), off = Number(q.get('x-offset'));
  if (!okId(id) || !Number.isInteger(idx) || !Number.isInteger(off) || idx < 0 || off < 0 ||
      !Buffer.isBuffer(q.body) || !q.body.length) return s.status(400).json({ error: 'bad chunk' });
  const f = tmpFile(id);
  if (!fs.existsSync(f)) fs.closeSync(fs.openSync(f, 'a'));
  const fd = fs.openSync(f, 'r+');
  fs.writeSync(fd, q.body, 0, q.body.length, off); fs.closeSync(fd);
  const meta = readMeta(id);
  if (!meta.received.includes(idx)) meta.received.push(idx);
  fs.writeFileSync(metaFile(id), JSON.stringify(meta));
  s.json({ ok: true });
});
app.get('/api/upload-status/:id', auth, (q, s) => {
  if (!okId(q.params.id)) return s.status(400).json({ error: 'bad id' });
  s.json({ received: readMeta(q.params.id).received });
});
app.post('/api/finish', auth, upload.single('thumb'), (q, s) => {
  const id = q.body.uploadId, ext = String(q.body.ext || '.mp4').toLowerCase();
  if (!okId(id) || !/^\.[a-z0-9]{1,5}$/.test(ext) || !fs.existsSync(tmpFile(id)))
    return s.status(400).json({ error: 'bad finish' });
  const meta = readMeta(id);
  if (meta.received.length !== Number(q.body.chunks) || fs.statSync(tmpFile(id)).size !== Number(q.body.size))
    return s.status(400).json({ error: 'incomplete upload' });
  const name = crypto.randomUUID() + ext;
  fs.renameSync(tmpFile(id), path.join(UP, name));
  fs.unlink(metaFile(id), () => {});
  const m = { id: crypto.randomUUID(), title: q.body.title || 'Untitled', description: q.body.description || '',
    category: String(q.body.category || '').trim() || 'General', language: String(q.body.language || '').trim(),
    videoPath: '/uploads/' + name, thumbPath: q.file ? '/uploads/' + q.file.filename : '' };
  const all = read(); all.push(m); write(all);
  s.json(m);
});

app.put('/api/movies/:id', auth, upload.single('thumb'), (q, s) => {
  const all = read(), m = all.find(x => x.id === q.params.id);
  if (!m) { if (q.file) fs.unlink(q.file.path, () => {}); return s.status(404).json({ error: 'not found' }); }
  if (typeof q.body.title === 'string' && q.body.title.trim()) m.title = q.body.title.trim();
  if (typeof q.body.description === 'string') m.description = q.body.description;
  if (typeof q.body.category === 'string') m.category = q.body.category.trim() || 'General';
  if (typeof q.body.language === 'string') m.language = q.body.language.trim();
  if (q.file) {
    if (m.thumbPath) fs.unlink(path.join(__dirname, m.thumbPath), () => {});
    m.thumbPath = '/uploads/' + q.file.filename;
  }
  write(all); s.json(m);
});
// ---- Subscription codes ----
const CODES = path.join(__dirname, 'codes.json');
const readCodes = () => fs.existsSync(CODES) ? JSON.parse(fs.readFileSync(CODES)) : [];
const writeCodes = (a) => fs.writeFileSync(CODES, JSON.stringify(a, null, 2));
app.get('/api/codes', auth, (q, s) => s.json(readCodes().reverse()));
app.post('/api/codes', auth, express.json(), (q, s) => {
  const days = Math.max(1, Math.min(3650, parseInt(q.body.days) || 30));
  const count = Math.max(1, Math.min(50, parseInt(q.body.count) || 1));
  const all = readCodes(), made = [];
  for (let i = 0; i < count; i++) {
    const c = { code: crypto.randomBytes(5).toString('hex').toUpperCase(), days, createdAt: Date.now(), usedBy: null, usedAt: null };
    all.push(c); made.push(c);
  }
  writeCodes(all); s.json(made);
});
app.post('/api/redeem', express.json(), (q, s) => {
  const code = String(q.body.code || '').trim().toUpperCase(), device = String(q.body.device || '').slice(0, 64);
  const all = readCodes(), c = all.find(x => x.code === code);
  if (!c || !device) return s.status(404).json({ error: 'Code galat hai' });
  if (c.usedBy && c.usedBy !== device) return s.status(409).json({ error: 'Ye code pehle use ho chuka hai' });
  if (!c.usedBy) { c.usedBy = device; c.usedAt = Date.now(); writeCodes(all); }
  const expiresAt = c.usedAt + c.days * 86400000;
  if (expiresAt < Date.now()) return s.status(410).json({ error: 'Code expire ho gaya' });
  s.json({ expiresAt, days: c.days });
});
// Admin page ab seedha root URL par: https://<your-url>/   (/admin bhi chalega)
app.use(express.static(path.join(__dirname, 'admin')));
app.use('/admin', express.static(path.join(__dirname, 'admin')));
app.listen(process.env.PORT || 3000, () => console.log('Server running on port 3000'));
