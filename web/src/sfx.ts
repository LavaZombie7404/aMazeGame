/**
 * Tiny procedural-audio module — no asset files, no network. Tones are
 * synthesised on demand with the Web Audio API so the bundle stays small
 * and skin/theme changes don't drag audio along.
 *
 * AudioContext can't auto-start without a user gesture; the first SFX
 * trigger happens inside a swipe/tap event handler so the browser permits
 * it. After that the context stays alive for the rest of the session.
 */

let ctx: AudioContext | null = null;

function getCtx(): AudioContext | null {
  if (!ctx) {
    const AC =
      window.AudioContext ||
      (window as unknown as { webkitAudioContext: typeof AudioContext })
        .webkitAudioContext;
    if (!AC) return null;
    try {
      ctx = new AC();
    } catch {
      return null;
    }
  }
  // Some browsers suspend the context until the next user gesture; the
  // call is a no-op if it's already running.
  if (ctx.state === "suspended") void ctx.resume();
  return ctx;
}

/** A short rising 3-note arpeggio. Plays on goal reach. */
export function playGoalChime() {
  const c = getCtx();
  if (!c) return;
  const notes = [523.25, 659.25, 783.99]; // C5 · E5 · G5
  let t = c.currentTime;
  for (const f of notes) {
    const osc = c.createOscillator();
    const gain = c.createGain();
    osc.type = "sine";
    osc.frequency.value = f;
    gain.gain.setValueAtTime(0, t);
    gain.gain.linearRampToValueAtTime(0.28, t + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.001, t + 0.42);
    osc.connect(gain).connect(c.destination);
    osc.start(t);
    osc.stop(t + 0.45);
    t += 0.09;
  }
}

/** A short noise-burst with falling lowpass — "new maze appears". */
export function playWhoosh() {
  const c = getCtx();
  if (!c) return;
  const duration = 0.3;
  const bufferSize = Math.floor(c.sampleRate * duration);
  const buffer = c.createBuffer(1, bufferSize, c.sampleRate);
  const data = buffer.getChannelData(0);
  for (let i = 0; i < bufferSize; i++) {
    // White noise tapered with a decay envelope baked into the sample data.
    data[i] = (Math.random() * 2 - 1) * Math.exp((-i / bufferSize) * 2.5);
  }
  const src = c.createBufferSource();
  src.buffer = buffer;
  const filter = c.createBiquadFilter();
  filter.type = "lowpass";
  filter.frequency.setValueAtTime(2400, c.currentTime);
  filter.frequency.exponentialRampToValueAtTime(180, c.currentTime + duration);
  const gain = c.createGain();
  gain.gain.setValueAtTime(0.16, c.currentTime);
  gain.gain.exponentialRampToValueAtTime(0.001, c.currentTime + duration);
  src.connect(filter).connect(gain).connect(c.destination);
  src.start();
}
