import type { Skill, SkillDraft, ToolInfo } from './types';

async function req(path: string, init?: RequestInit): Promise<any> {
  const resp = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  });
  if (resp.status === 401) {
    throw new Error('unauthorized');
  }
  if (!resp.ok) {
    throw new Error(`http ${resp.status}`);
  }
  const text = await resp.text();
  return text ? JSON.parse(text) : null;
}

export async function login(password: string): Promise<void> {
  await req('/api/admin/login', { method: 'POST', body: JSON.stringify({ password }) });
}

export async function listSkills(): Promise<Skill[]> {
  return req('/api/skills');
}

export async function createSkill(s: SkillDraft): Promise<Skill> {
  return req('/api/skills', { method: 'POST', body: JSON.stringify(s) });
}

export async function updateSkill(id: string, s: SkillDraft): Promise<Skill> {
  return req(`/api/skills/${id}`, { method: 'PUT', body: JSON.stringify(s) });
}

export async function setEnabled(id: string, enabled: boolean): Promise<Skill> {
  return req(`/api/skills/${id}/enabled`, { method: 'PATCH', body: JSON.stringify({ enabled }) });
}

export async function deleteSkill(id: string): Promise<void> {
  await req(`/api/skills/${id}`, { method: 'DELETE' });
}

export async function discoverTools(id: string, draft: SkillDraft): Promise<ToolInfo[]> {
  return req(`/api/skills/${id}/discover`, {
    method: 'POST',
    body: JSON.stringify({ mcpUrl: draft.mcpUrl, authHeader: draft.authHeader, authValue: draft.authValue }),
  });
}
