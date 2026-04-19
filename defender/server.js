require('dotenv').config();
const express = require('express');
const path    = require('path');
const mongoose = require('mongoose');

const app  = express();
const PORT = 3000;

app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

// ── MongoDB Atlas ─────────────────────────────────────────
mongoose.connect(process.env.MONGODB_URI)
  .then(() => console.log('✅ MongoDB Atlas connected'))
  .catch(err => console.error('❌ MongoDB error:', err.message));

// ── Schemas / Models ──────────────────────────────────────
const AlertSchema = new mongoose.Schema({
  id:          { type: Number, default: () => Date.now() },
  score:       Number,
  danger_type: { type: String, default: 'unknown' },
  explanation: { type: String, default: '' },
  original:    { type: String, default: '' },
  timestamp:   { type: String, default: () => new Date().toISOString() },
  createdAt:   { type: Date,   default: Date.now },
});

// TTL index — auto-delete alerts older than 30 days
AlertSchema.index({ createdAt: 1 }, { expireAfterSeconds: 60 * 60 * 24 * 30 });

const ConfigSchema = new mongoose.Schema({
  _id:         { type: String, default: 'singleton' },
  familyEmail: { type: String, default: '' },
  grandmaName: { type: String, default: '' },
  active:      { type: Boolean, default: true },
});

const Alert  = mongoose.model('Alert',  AlertSchema);
const Config = mongoose.model('Config', ConfigSchema);

async function getConfig() {
  let cfg = await Config.findById('singleton');
  if (!cfg) cfg = await Config.create({ _id: 'singleton' });
  return cfg;
}

// ── GET /api/family-email ────────────────────────────────
app.get('/api/family-email', async (req, res) => {
  const cfg = await getConfig();
  res.json({ email: cfg.familyEmail || '' });
});

// ── GET /api/settings ────────────────────────────────────
app.get('/api/settings', async (req, res) => {
  const cfg = await getConfig();
  res.json({ familyEmail: cfg.familyEmail, grandmaName: cfg.grandmaName, active: cfg.active });
});

// ── POST /api/settings ───────────────────────────────────
app.post('/api/settings', async (req, res) => {
  const { familyEmail, grandmaName, active } = req.body;
  const update = {};
  if (typeof familyEmail === 'string') update.familyEmail = familyEmail.trim();
  if (typeof grandmaName === 'string') update.grandmaName = grandmaName.trim();
  if (typeof active      === 'boolean') update.active     = active;
  const cfg = await Config.findByIdAndUpdate('singleton', update, { upsert: true, new: true });
  res.json({ ok: true, config: cfg });
});

// ── POST /api/alert ──────────────────────────────────────
app.post('/api/alert', async (req, res) => {
  const { score, danger_type, explanation, original } = req.body;
  if (typeof score !== 'number') return res.status(400).json({ error: 'score is required (number)' });

  const alert = await Alert.create({
    id: Date.now(),
    score: Math.round(score),
    danger_type: danger_type || 'unknown',
    explanation: explanation || '',
    original:    original    || '',
    timestamp:   new Date().toISOString(),
  });
  res.json({ ok: true, alert });
});

// ── GET /api/alerts ──────────────────────────────────────
app.get('/api/alerts', async (req, res) => {
  const alerts = await Alert.find().sort({ id: -1 }).limit(10).lean();
  res.json(alerts);
});

// ── GET /api/analytics ───────────────────────────────────
// Aggregation pipeline: threat stats by type + daily counts
app.get('/api/analytics', async (req, res) => {
  const [byType, daily, topScore] = await Promise.all([
    // Count + avg score per danger_type
    Alert.aggregate([
      { $group: {
          _id: '$danger_type',
          count: { $sum: 1 },
          avgScore: { $avg: '$score' },
          maxScore: { $max: '$score' },
      }},
      { $sort: { count: -1 } },
    ]),
    // Alerts per day (last 7 days)
    Alert.aggregate([
      { $match: { createdAt: { $gte: new Date(Date.now() - 7 * 24 * 60 * 60 * 1000) } } },
      { $group: {
          _id: { $dateToString: { format: '%Y-%m-%d', date: '$createdAt' } },
          count: { $sum: 1 },
          avgScore: { $avg: '$score' },
      }},
      { $sort: { _id: 1 } },
    ]),
    // Highest-risk alert ever
    Alert.findOne().sort({ score: -1 }).lean(),
  ]);
  res.json({ byType, daily, topScore });
});

// ── GET /api/search?q=text ───────────────────────────────
// Full-text search over alert messages using Atlas Search ($text fallback)
app.get('/api/search', async (req, res) => {
  const q = (req.query.q || '').trim();
  if (!q) return res.json([]);
  const regex = new RegExp(q, 'i');
  const results = await Alert.find({
    $or: [
      { original:    regex },
      { explanation: regex },
      { danger_type: regex },
    ],
  }).sort({ score: -1 }).limit(20).lean();
  res.json(results);
});

// ── GET /api/ping ────────────────────────────────────────
let lastPing = 0;
app.get('/api/ping', (req, res) => {
  lastPing = Date.now();
  res.json({ ok: true });
});

// ── GET /api/status ──────────────────────────────────────
app.get('/api/status', (req, res) => {
  const connected = (Date.now() - lastPing) < 30000;
  res.json({ connected, lastPing: lastPing || null });
});

// ── Start server ─────────────────────────────────────────
app.listen(PORT, '0.0.0.0', () => {
  console.log(`\n🛡️  MeemawDefender dashboard running → http://localhost:${PORT}\n`);
});
