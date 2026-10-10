# LLM opponent (Shandalar Ascendant AI1)

Optional language-model opponent for **Shandalar Ascendant** only. Forge’s normal AI always
remains the fallback. Stock Adventure worlds are unchanged.

Settings live in-game under **Settings → LLM opponent…** (controller, keyboard, and mouse).
They are stored locally in `llm_opponent.properties` in the Forge user folder
(Windows: `%APPDATA%\Forge\`, or the path Forge already uses for prefs). The API key is
**never** written into a world save and is **never** logged.

| Setting | Notes |
|---|---|
| Enable | Off by default. When off, every decision uses Forge AI. |
| Endpoint URL | OpenAI-compatible **base** ending in `/v1` (Forge appends `/chat/completions`). |
| Model | Exact model id the server expects. |
| API key | Masked in the UI. Required for hosted APIs; optional for most local servers. |
| Timeout | Seconds per request. On timeout, Forge AI takes over for that decision. |
| Test | One small chat request; shows success or a clear error **without** the key. |

## What the LLM decides

Only **key** decisions:

- Mulligan (keep or mull)
- Declare attackers
- Declare blockers
- Main spells (casting a spell) and counterspells

Forge AI still prepares legal options (including targets), handles routine priority and
activated abilities, and is used whenever the LLM is disabled, times out, errors, or
returns a malformed answer. A duel never hangs waiting on the network: HTTP runs on a
dedicated thread with the configured timeout.

### Co-op

In co-op duels the **host** runs the match and the enemy AI controllers. The LLM therefore
runs on the **host only**, using the host’s local settings and key. The guest’s LLM settings
are unused for enemy decisions.

## Hosted OpenAI-compatible APIs

Any provider that exposes OpenAI-style `POST …/chat/completions` works. Create an account,
copy an API key, and paste the base URL + model + key into the settings screen.

Examples (verify current model ids and prices on the provider’s site):

| Provider | Endpoint URL to enter | Key |
|---|---|---|
| [OpenRouter](https://openrouter.ai/docs) | `https://openrouter.ai/api/v1` | Dashboard API key |
| [DeepInfra](https://docs.deepinfra.com/) | `https://api.deepinfra.com/v1/openai` | Dashboard token |

**Cost:** Ascendant only calls the model for key decisions (not every priority pass), so a
match is typically dozens of small prompts rather than hundreds. Exact cost depends on the
model’s input/output price and how long the board text gets; check the provider’s pricing
page. Prefer mid-size instruct models that follow JSON instructions reliably over the
largest “reasoning” models unless you accept slower turns.

**Recommended starting point:** a current mid-size chat/instruct model from your provider’s
catalog (follow their docs for the exact slug). Use the in-game **Test** button before a duel.

## Local models (no key required)

Install a local OpenAI-compatible server, load a model, then point Shandalar Ascendant at it. Leave the
API key blank (or use a placeholder like `ollama` if a client insists — Forge omits the
Authorization header when the key is empty).

| Server | Typical endpoint URL | Notes |
|---|---|---|
| [Ollama](https://docs.ollama.com/api/openai-compatibility) | `http://localhost:11434/v1` | `ollama pull <model>` then use that name as Model |
| [LM Studio](https://lmstudio.ai/docs/developer/openai-compat) | `http://localhost:1234/v1` | Start the server in the Developer tab (`lms server start`); use the model id shown in the app |
| [llama.cpp server](https://github.com/ggml-org/llama.cpp/blob/HEAD/tools/server/README.md) | `http://localhost:8080/v1` | Default port is often `8080`; model field may be ignored when one model is loaded |

### Model size vs GPU memory (rule of thumb)

Quantized weights (e.g. Q4_K_M) need far less VRAM than full precision. Numbers below are
**approximate** for a single Q4-class chat model with a modest context; leave headroom for
the OS, Forge, and KV cache. Prefer a smaller model that answers in a few seconds over a
huge model that times out.

| GPU VRAM | Practical starting size (quantized) |
|---|---|
| 8 GB | ~7B |
| 12 GB | ~7B–13B |
| 16 GB | ~13B–20B |
| 24 GB | ~30B–34B |
| 48 GB+ | ~70B Q4-class |

CPU-only inference works but is usually too slow for combat decisions unless you raise the
timeout and accept long pauses (Forge AI still covers timeouts).

### NVIDIA

CUDA builds of Ollama, LM Studio, and llama.cpp are the usual path. Use the vendor’s current
installer and confirm the GPU appears in the server’s device list.

### AMD on Windows (RX 9070-class and similar)

On Windows these are **different stacks** — pick the one that matches your tool:

| Path | What it is | Use with |
|---|---|---|
| **Vulkan** | Portable GPU compute via the Adrenalin Vulkan driver | **llama.cpp**, **LM Studio**, and **Ollama** (their usual / recommended Windows GPU path for local chat servers) |
| **ROCm 7.2 + HIP / PyTorch** | AMD’s HIP SDK / ROCm Windows stack ([system requirements](https://rocm.docs.amd.com/projects/install-on-windows/en/docs-7.2/reference/system-requirements.html) list RX 9070 / 9070 XT as supported) | **PyTorch**-based training and inference (and other HIP apps). This is *not* the default path Shandalar Ascendant expects for llama.cpp / LM Studio / Ollama |

For Shandalar Ascendant’s local OpenAI-compatible servers on an RX 9070-class card:

1. Prefer **Vulkan** in LM Studio (Vulkan runtime), llama.cpp (Vulkan build), and Ollama
   (Vulkan is enabled by default on Windows when the backend is present; see
   [Ollama Windows](https://docs.ollama.com/windows) / [GPU](https://docs.ollama.com/gpu) docs).
2. Use **ROCm 7.2 / HIP** when you specifically need the PyTorch ecosystem on that GPU —
   follow AMD’s current Windows HIP SDK install docs, not a copied ROCm Linux recipe.

Always confirm with the server log that the Radeon is listed as a compute device before a
duel. If a llama.cpp / LM Studio / Ollama install only shows the CPU, switch that app to its
Vulkan backend rather than assuming a PyTorch ROCm install will fix it.

## Privacy

- The **API key** stays in `llm_opponent.properties` on this machine. It is not part of the
  Adventure save, is not synced through co-op, and must not appear in `llm_decisions.log`,
  stack traces, or the Test dialog.
- Each request sends a **text summary of the game state** Forge builds for that decision
  (life totals, visible board, the AI’s hand, stack text, and the numbered options). It does
  not upload your save file, collection, or unrelated Forge prefs.
- **Hosted APIs** receive that prompt text on their servers under that provider’s privacy
  policy. **Local servers** keep prompts on your machine (unless you pointed the URL at a
  remote host).
- Decision prompts/answers may be appended to `llm_decisions.log` in the same folder for
  debugging; that log is redacted for the key.

## Troubleshooting

| Symptom | What to try |
|---|---|
| Test fails immediately | Check URL ends with `/v1` (or `/v1/openai` for DeepInfra). Confirm the server is running and the model id matches. |
| Test succeeds, duel still uses Forge AI | Enable the checkbox; Ascendant only. Co-op: configure the **host**. |
| Slow turns / timeouts | Raise timeout slightly, use a smaller/faster model, or disable LLM (Forge AI continues). |
| Malformed / ignored answers | Prefer instruct models that follow “reply only with JSON”. Forge AI is used for that decision. |
| Key worries | Clear the key field and save; delete `apiKey` from `llm_opponent.properties`; never paste the key into chat logs. |

## Related code

- `forge-ai/.../llm/LlmOpponent.java`, `LlmSettings.java` — HTTP, decisions, fallback
- `forge-gui-mobile/.../scene/LlmSettingsScene.java` — settings UI
- Solo activation: `DuelScene` (Ascendant). Co-op host: `CoopDuelRuntime`
