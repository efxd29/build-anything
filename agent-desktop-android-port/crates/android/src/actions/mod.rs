//! Actions layer: maps core action requests onto bridge calls. Ref actions
//! travel as capture proofs and are re-matched inside the bridge app in the
//! same request that performs them, so a stale node path can never be acted
//! on by mistake.
