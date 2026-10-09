// Model display names on the agents' nameplates.
import { describe, expect, it } from 'vitest';
import { modelLabel } from '../src/agents/models.js';

describe('modelLabel', () => {
  it.each([
    ['claude-opus-5-5', 'Opus 5.5'],
    ['claude-sonnet-5', 'Sonnet 5'],
    ['claude-haiku-4-5-20251001', 'Haiku 4.5'],
    ['claude-fable-5-1', 'Fable 5.1'],
    ['claude-opus-5-5[1m]', 'Opus 5.5'],
    ['opus', 'Opus'],
    ['sonnet', 'Sonnet'],
    ['gpt-6-astra', 'GPT-6 Astra'],
    ['gpt-5.1-codex-mini', 'GPT-5.1 Codex Mini'],
    ['o4-mini', 'o4 Mini'],
    ['my-local-model', 'my-local-model'],
  ])('%s -> %s', (model, label) => {
    expect(modelLabel(model)).toBe(label);
  });

  it('has no label for an unknown or default model', () => {
    expect(modelLabel(undefined)).toBeUndefined();
    expect(modelLabel('')).toBeUndefined();
    expect(modelLabel('default')).toBeUndefined();
  });
});
