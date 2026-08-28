export interface Skill {
  id: string;
  name: string;
  description: string;
  scope: 'llm' | 'chat';
  mcpUrl: string;
  authHeader: string;
  authValue: string; // 管理端视图为 "****"
  toolsJson: string;
  enabled: boolean;
  updatedAt: number;
}

export interface ToolInfo {
  name: string;
  description: string;
}

export interface SkillDraft {
  id: string;
  name: string;
  description: string;
  scope: 'llm' | 'chat';
  mcpUrl: string;
  authHeader: string;
  authValue: string;
  toolsJson: string;
  enabled: boolean;
}
