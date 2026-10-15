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
    videoPath: '/uploads/' + name, thumbPath: q.file ? '/uploads/' + q.file.filename : '' };
  const all = read(); all.push(m); write(all);
  s.json(m);
});

app.put('/api/movies/:id', auth, upload.single('thumb'), (q, s) => {
  const all = read(), m = all.find(x => x.id === q.params.id);
  if (!m) { if (q.file) fs.unlink(q.file.path, () => {}); return s.status(404).json({ error: 'not found' }); }
  if (typeof q.body.title === 'string' && q.body.title.trim()) m.title = q.body.title.trim();
  if (typeof q.body.description === 'string') m.description = q.body.description;
  if (q.file) {
    if (m.thumbPath) fs.unlink(path.join(__dirname, m.thumbPath), () => {});
    m.thumbPath = '/uploads/' + q.file.filename;
  }
  write(all); s.json(m);
});
// Admin page ab seedha root URL par: https://<your-url>/   (/admin bhi chalega)
app.use(express.static(path.join(__dirname, 'admin')));
app.use('/admin', express.static(path.join(__dirname, 'admin')));
app.listen(process.env.PORT || 3000, () => console.log('Server running on port 3000'));
