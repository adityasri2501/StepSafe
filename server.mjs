import express from 'express';
import { CopilotClient } from '@github/copilot-sdk';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const app = express();
const port = Number(process.env.PORT || 4173);
const root = path.dirname(fileURLToPath(import.meta.url));
app.use(express.json({ limit: '16kb' }));
app.use(express.static(root));

let client;
let session;
async function getCopilotSession() {
  if (!client) {
    client = new CopilotClient();
    await client.start();
  }
  if (!session) session = await client.createSession({ model: process.env.COPILOT_MODEL || 'gpt-5' });
  return session;
}

app.get('/api/copilot/status', (_req, res) => res.json({ configured: true, mode: 'GitHub Copilot SDK' }));
app.post('/api/copilot/chat', async (req, res) => {
  const prompt = String(req.body?.message || '').trim();
  if (!prompt || prompt.length > 1500) return res.status(400).json({ error: 'Message must be 1–1500 characters.' });
  try {
    const active = await getCopilotSession();
    const result = await active.sendAndWait({ prompt: `You are SafeStep's optional indoor-navigation assistant. Give concise, accessibility-friendly Hindi/English guidance. Never invent a user's location, never change or claim to calculate the safety-critical route, and tell the user to stop and seek human help if uncertain. The app's deterministic map graph computes routes. User message: ${prompt}` });
    res.json({ reply: result?.data?.content || 'I could not form a response. Please try again.' });
  } catch (error) {
    console.error('Copilot SDK error:', error?.message || error);
    res.status(503).json({ error: 'Copilot is unavailable. Check GitHub Copilot sign-in and SDK setup, then try again.' });
  }
});

app.listen(port, '0.0.0.0', () => console.log(`SafeStep POC running at http://localhost:${port}`));
process.on('SIGINT', async () => { try { await session?.disconnect(); await client?.stop(); } finally { process.exit(0); } });
