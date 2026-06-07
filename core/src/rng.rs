/// Mulberry32 — same algorithm as the original `web/src/rng.ts` so a given
/// seed produces the same maze on both web and the Rust core. Tiny, fast,
/// not cryptographic.
pub struct Mulberry32 {
    state: u32,
}

impl Mulberry32 {
    pub fn new(seed: u32) -> Self {
        Self { state: seed }
    }

    pub fn next_u32(&mut self) -> u32 {
        self.state = self.state.wrapping_add(0x6d2b79f5);
        let mut t = self.state;
        t = (t ^ (t >> 15)).wrapping_mul(t | 1);
        t ^= t.wrapping_add((t ^ (t >> 7)).wrapping_mul(t | 61));
        t ^ (t >> 14)
    }

    /// Uniform float in [0, 1).
    pub fn next_f64(&mut self) -> f64 {
        (self.next_u32() as f64) / 4_294_967_296.0
    }

    /// Inclusive range `[min, max]`.
    pub fn pick_int(&mut self, min: u32, max: u32) -> u32 {
        debug_assert!(max >= min);
        min + ((self.next_f64() * (max - min + 1) as f64) as u32)
    }
}
