// Variables that never reach a process the Foreman starts (agents, CI, setup, gh/az, git, the
// notifier, a restarted Foreman): the client token tools may take from the environment.
// scrubEnv runs on the FINAL environment of every spawn, after repoSettings env and any other merge.

export const SECRET_ENV_VARS = ['AGENTCRAFT_CLIENT_TOKEN'];

export function isSecretEnvVar(name: string): boolean {
  return SECRET_ENV_VARS.includes(name.toUpperCase());
}

/** A copy of `env` without the secret variables (any letter case: Windows names are case-insensitive). */
export function scrubEnv<T extends Record<string, string | undefined>>(env: T): T {
  const out = { ...env };
  for (const k of Object.keys(out)) if (isSecretEnvVar(k)) delete out[k];
  return out;
}
