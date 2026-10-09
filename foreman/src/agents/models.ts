// Display names for models, shown on the agents' nameplates ("Opus 5.5", "GPT-6 Astra").

const cap = (w: string) => (w ? w[0]!.toUpperCase() + w.slice(1) : w);

/**
 * "claude-opus-5-5" -> "Opus 5.5", "claude-haiku-4-5-20251001" -> "Haiku 4.5", "opus" -> "Opus",
 * "gpt-6-astra" -> "GPT-6 Astra", "gpt-5.1-codex-mini" -> "GPT-5.1 Codex Mini", "o4-mini" -> "o4 Mini".
 * Anything unrecognised is shown as it is (trimmed).
 */
export function modelLabel(model: string | undefined): string | undefined {
  const m = model?.trim();
  if (!m || m === 'default') return undefined;
  const claude = /^(?:claude-)?(opus|sonnet|haiku|fable)(?:-(\d+)(?:-(\d{1,2}))?)?(?:-\d{8})?(?:\[.*\])?$/i.exec(m);
  if (claude) return [cap(claude[1]!.toLowerCase()), claude[2] ? `${claude[2]}${claude[3] ? `.${claude[3]}` : ''}` : ''].filter(Boolean).join(' ');
  const gpt = /^gpt-([\d.]+)(?:-(.+))?$/i.exec(m);
  if (gpt) return [`GPT-${gpt[1]}`, ...(gpt[2] ?? '').split('-').filter(Boolean).map(cap)].join(' ');
  const o = /^(o\d+)(?:-(.+))?$/i.exec(m);
  if (o) return [o[1]!.toLowerCase(), ...(o[2] ?? '').split('-').filter(Boolean).map(cap)].join(' ');
  return m.slice(0, 24);
}
