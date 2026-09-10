import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import * as api from './api';
import type { Skill } from './types';

vi.mock('./api', () => ({
  login: vi.fn(),
  listSkills: vi.fn(),
  createSkill: vi.fn(),
  updateSkill: vi.fn(),
  setEnabled: vi.fn(),
  deleteSkill: vi.fn(),
  discoverTools: vi.fn(),
  getSystemPrompt: vi.fn(),
  setSystemPrompt: vi.fn(),
  getChatSystemPrompt: vi.fn(),
  setChatSystemPrompt: vi.fn(),
}));

const mapsSkill: Skill = {
  id: 'maps',
  name: '高德导航',
  description: '搜索周边并导航',
  scope: 'llm',
  mcpUrl: 'https://mcp.example/mcp',
  authHeader: 'Authorization',
  authValue: '****',
  toolsJson: '[{"name":"search","enabled":true}]',
  enabled: true,
  updatedAt: 1,
};

describe('skill manager', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(api.listSkills).mockResolvedValue([mapsSkill]);
    vi.mocked(api.getSystemPrompt).mockResolvedValue('业务提示词');
    vi.mocked(api.getChatSystemPrompt).mockResolvedValue('闲聊提示词');
    vi.mocked(api.discoverTools).mockResolvedValue([
      { name: 'search', description: '搜索 POI' },
      { name: 'route', description: '路线规划' },
    ]);
    vi.mocked(api.updateSkill).mockResolvedValue(mapsSkill);
    vi.mocked(api.setEnabled).mockResolvedValue({ ...mapsSkill, enabled: false });
  });

  it('shows a failed login without entering the console', async () => {
    vi.mocked(api.login).mockRejectedValue(new Error('unauthorized'));
    const user = userEvent.setup();
    render(<App />);

    await user.type(screen.getByPlaceholderText('管理口令'), 'bad-password');
    await user.click(screen.getByRole('button', { name: '登录' }));

    expect(await screen.findByText('口令错误')).toBeInTheDocument();
    expect(localStorage.getItem('skill-authed')).toBeNull();
  });

  it('discovers tools, preserves selections and saves an edited skill', async () => {
    localStorage.setItem('skill-authed', '1');
    const user = userEvent.setup();
    render(<App />);

    await user.click(await screen.findByText('高德导航'));
    const formPane = screen.getByRole('heading', { name: '编辑 maps' }).closest('.form-pane') as HTMLElement;

    await user.click(screen.getByRole('button', { name: '发现工具' }));
    const route = await screen.findByRole('checkbox', { name: /路线规划/ });
    await user.click(route);
    await user.click(within(formPane).getByRole('button', { name: '保存' }));

    expect(api.updateSkill).toHaveBeenCalledWith(
      'maps',
      expect.objectContaining({
        authValue: '',
        toolsJson: JSON.stringify([
          { name: 'search', enabled: true },
          { name: 'route', enabled: false },
        ]),
      }),
    );
    expect(await screen.findByText('已保存')).toBeInTheDocument();
  });

  it('updates the business prompt and toggles a skill', async () => {
    localStorage.setItem('skill-authed', '1');
    const user = userEvent.setup();
    render(<App />);

    const skillRow = (await screen.findByText('高德导航')).closest('.row') as HTMLElement;
    await user.click(within(skillRow).getByRole('button', { name: '禁用' }));
    expect(api.setEnabled).toHaveBeenCalledWith('maps', false);

    const pane = screen.getByText(/业务 LLM 系统提示词/).closest('details') as HTMLElement;
    await user.click(within(pane).getByText(/业务 LLM 系统提示词/));
    const input = within(pane).getByRole('textbox');
    await user.clear(input);
    await user.type(input, '优先返回附近地点');
    await user.click(within(pane).getByRole('button', { name: '保存' }));

    expect(api.setSystemPrompt).toHaveBeenCalledWith('优先返回附近地点');
    expect(await screen.findByText(/系统提示词已保存/)).toBeInTheDocument();
  });
});
