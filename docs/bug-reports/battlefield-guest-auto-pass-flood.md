**Bug report from Tiny: guest skips whole turns (auto-pass floods OK), build 2026-10-25**

Hey Steve, second Battlefield report. This one makes online play unplayable for me as the guest, so I've patched my local copy to get around it (details at the bottom).

**What happens**

I join your host over Tailscale. As soon as my first turn starts, the game races through my turns by itself. I can't play a land or do anything. Turns 1 to 3 went by in about 3 seconds each.

**What the log shows** (full excerpt: https://gist.github.com/ronstkbkiiicsaz-del/1be14eedaca1fa2051966049e9141e7f)

1. 10:18:21, start of Turn 1 (my turn): `[DeltaSync] CHECKSUM MISMATCH! Server=934944495, Client=-473564570 at seq=22`. The delta seq jumped from 15 to 22, and the client sent `requestResync` three times.
2. From that same second, my client sent `selectButtonOk` about 100 times per second: more than 950 OKs between 10:18:21 and 10:18:57. It never sent `selectCard`.
3. Delta seq numbers kept skipping on every phase after that (23 to 25, 25 to 27, 28 to 31, and so on), all the way to Turn 3 END_OF_TURN.

**Cause (from reading the 2026-10-25 jar)**

- `BattlefieldLive.picture()` calls `considerStackPass()` and `considerEmptyPass()` on **every frame**.
- For the joiner seat, `considerEmptyPass()` ends in `submitSeatOk()` → `selectButtonOk()` whenever `humanInput() == null`, `thisSeatMayPass()` is true and `heCanRespondNow()` / `livePlayableNow()` are both false.
- Nothing latches it to one OK per prompt. `okEnabled` stays true locally until the host's next `updateButtons` arrives, so every frame sends another OK. The host consumes them as real passes and blows through every stop.
- After the desync, `playableIds` and `isWeaklySelectable` never matched my hand, so "nothing playable" was true all turn even with lands in hand.
- Your host seat has the same per-frame path (`InputPassPriority.passPriority()` inside `considerEmptyPass`), so it can probably skip your turns too.

**Suggested fix**

- Only auto-pass once per prompt: remember the prompt / priority id you passed for and don't pass again until a new `updateButtons` / `showPromptMessage` arrives.
- Take `considerEmptyPass()` out of the render path (`picture()`). Calling it from `showPromptMessage` / `updateButtons` is enough.
- Don't auto-pass for the guest while a resync is pending, or when the local view just had a checksum mismatch.
- Separately: why does the guest desync at seq=22 right at Turn 1 upkeep?

**My local workaround**

So we can keep testing, I patched only my jar so `considerEmptyPass()` returns immediately. Nothing on the wire changed; I just click OK myself to pass.
