const express = require('express'), multer = require('multer'), fs = require('fs'),
      path = require('path'), crypto = require('crypto');
const app = express();
const ADMIN_KEY = process.env.ADMIN_KEY || 'change-me';
const UP = path.join(__dirname, 'uploads'); fs.mkdirSync(UP, { recursive: true });
const DB = path.join(__dirname, 'movies.json');
const read = () => fs.existsSync(DB) ? JSON.parse(fs.readFileSync(DB)) : [];
const upload = multer({ storage: multer.diskStorage({
  destination: UP,
  filename: (r, f, cb) => cb(null, crypto.randomUUID() + path.extname(f.originalname)) }) });
const auth = (q, s, n) => q.get('x-admin-key') === ADMIN_KEY ? n() : s.status(401).json({ error: 'unauthorized' });

app.use('/uploads', express.static(UP));          // supports Range requests => seeking works
app.use('/admin', express.static(path.join(__dirname, 'admin')));
app.get('/api/movies', (q, s) => s.json(read().reverse()));
app.post('/api/movies', auth, upload.fields([{ name: 'video', maxCount: 1 }, { name: 'thumb', maxCount: 1 }]),
  (q, s) => {
    if (!q.files.video) return s.status(400).json({ error: 'video required' });
    const m = { id: crypto.randomUUID(), title: q.body.title || 'Untitled', description: q.body.description || '',
      videoPath: '/uploads/' + q.files.video[0].filename,
      thumbPath: q.files.thumb ? '/uploads/' + q.files.thumb[0].filename : '' };
    const all = read(); all.push(m); fs.writeFileSync(DB, JSON.stringify(all, null, 2));
    s.json(m);
  });
app.delete('/api/movies/:id', auth, (q, s) => {
  fs.writeFileSync(DB, JSON.stringify(read().filter(m => m.id !== q.params.id), null, 2)); s.json({ ok: true });
});
app.listen(process.env.PORT || 3000, () => console.log('Server running on port 3000'));
