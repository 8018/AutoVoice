import { useEffect, useState } from 'react';
import * as api from './api';
import type { Skill, SkillDraft, ToolInfo } from './types';

interface FormState extends SkillDraft {
  tools: ToolInfo[];
  checked: Record<string, boolean>;
}

const emptyForm = (): FormState => ({
  id: '', name: '', description: '', mcpUrl: '', authHeader: '', authValue: '', toolsJson: '[]',
  enabled: true, tools: [], checked: {},
});

export default function App() {
  const [authed, setAuthed] = useState<boolean>(() => localStorage.getItem('skill-authed') === '1');
  const [password, setPassword] = useState('');
  const [skills, setSkills] = useState<Skill[]>([]);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm());
  const [msg, setMsg] = useState('');
  const [err, setErr] = useState('');

  async function load() {
    try {
      setSkills(await api.listSkills());
    } catch (e: any) {
      if (e.message === 'unauthorized') {
        setAuthed(false);
        localStorage.removeItem('skill-authed');
      } else {
        setErr(String(e.message || e));
      }
    }
  }

  useEffect(() => {
    if (authed) load();
  }, [authed]);

  async function doLogin() {
    try {
      await api.login(password);
      localStorage.setItem('skill-authed', '1');
      setAuthed(true);
      setErr('');
    } catch (e) {
      setErr('口令错误');
    }
  }

  async function doDiscover() {
    if (!editingId) return;
    setMsg(''); setErr('');
    try {
      const tools = await api.discoverTools(editingId, form);
      const checked: Record<string, boolean> = {};
      tools.forEach((t) => (checked[t.name] = true));
      setForm({ ...form, tools, checked });
    } catch (e) {
      setErr('发现工具失败（MCP server 不可达？）');
    }
  }

  function buildDraft(): SkillDraft {
    const enabledTools = Object.entries(form.checked)
      .filter(([, v]) => v)
      .map(([name]) => ({ name, enabled: true }));
    return {
      id: form.id, name: form.name, description: form.description,
      mcpUrl: form.mcpUrl, authHeader: form.authHeader, authValue: form.authValue,
      toolsJson: JSON.stringify(enabledTools), enabled: form.enabled,
    };
  }

  async function doSave() {
    setMsg(''); setErr('');
    try {
      if (editingId) {
        await api.updateSkill(editingId, buildDraft());
        setMsg('已保存');
      } else {
        await api.createSkill(buildDraft());
        setMsg('已创建');
      }
      setEditingId(null);
      setForm(emptyForm());
      load();
    } catch (e: any) {
      setErr(e.message === 'http 409' ? 'skill id 已存在' : String(e.message || e));
    }
  }

  async function doToggle(s: Skill) {
    await api.setEnabled(s.id, !s.enabled);
    load();
  }

  async function doDelete(s: Skill) {
    if (!confirm(`删除 skill ${s.id}？`)) return;
    await api.deleteSkill(s.id);
    if (editingId === s.id) { setEditingId(null); setForm(emptyForm()); }
    load();
  }

  function edit(s: Skill) {
    const checked: Record<string, boolean> = {};
    try {
      const arr = JSON.parse(s.toolsJson) as { name: string; enabled: boolean }[];
      arr.forEach((t) => (checked[t.name] = true));
    } catch { /* 忽略非法清单 */ }
    setEditingId(s.id);
    setForm({
      id: s.id, name: s.name, description: s.description, mcpUrl: s.mcpUrl,
      authHeader: s.authHeader, authValue: '', toolsJson: s.toolsJson,
      enabled: s.enabled, tools: [], checked,
    });
  }

  if (!authed) {
    return (
      <div className="login">
        <h1>Skill 管理平台</h1>
        <input type="password" placeholder="管理口令" value={password}
               onChange={(e) => setPassword(e.target.value)} />
        <button onClick={doLogin}>登录</button>
        {err && <p className="err">{err}</p>}
      </div>
    );
  }

  return (
    <div className="app">
      <div className="topbar">
        <h1>Skill 管理平台</h1>
        <button onClick={() => { setAuthed(false); localStorage.removeItem('skill-authed'); }}>退出</button>
      </div>
      <div className="main">
        <div className="list-pane">
          {skills.map((s) => (
            <div key={s.id} className={`row ${editingId === s.id ? 'sel' : ''}`} onClick={() => edit(s)}>
              <span className="name">{s.name}</span>
              <span className="desc">{s.description || s.id}</span>
              <span className={`badge ${s.enabled ? 'on' : 'off'}`}>{s.enabled ? '启用' : '禁用'}</span>
              <button onClick={(e) => { e.stopPropagation(); doToggle(s); }}>{s.enabled ? '禁用' : '启用'}</button>
              <button onClick={(e) => { e.stopPropagation(); doDelete(s); }}>删除</button>
            </div>
          ))}
          <button className="new" onClick={() => { setEditingId(null); setForm(emptyForm()); }}>+ 新建 skill</button>
        </div>
        <div className="form-pane">
          <h2>{editingId ? `编辑 ${editingId}` : '新建 skill'}</h2>
          <label>id<input value={form.id} disabled={!!editingId}
                 onChange={(e) => setForm({ ...form, id: e.target.value })} /></label>
          <label>名称<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
          <label>描述（面向 LLM）<textarea value={form.description}
                 onChange={(e) => setForm({ ...form, description: e.target.value })} /></label>
          <label>MCP 地址<input value={form.mcpUrl} placeholder="https://mcp.example.com/mcp"
                 onChange={(e) => setForm({ ...form, mcpUrl: e.target.value })} /></label>
          <label>认证头名<input value={form.authHeader} placeholder="x-api-key（可留空）"
                 onChange={(e) => setForm({ ...form, authHeader: e.target.value })} /></label>
          <label>认证头值<input type="password" value={form.authValue}
                 placeholder={editingId ? '留空保持不变' : ''}
                 onChange={(e) => setForm({ ...form, authValue: e.target.value })} /></label>
          <label>启用<input type="checkbox" checked={form.enabled}
                 onChange={(e) => setForm({ ...form, enabled: e.target.checked })} /></label>
          <button onClick={doDiscover} disabled={!editingId || !form.mcpUrl}>发现工具</button>
          {form.tools.length > 0 && (
            <div className="tools">
              {form.tools.map((t) => (
                <label key={t.name}>
                  <input type="checkbox" checked={!!form.checked[t.name]}
                         onChange={(e) => setForm({ ...form, checked: { ...form.checked, [t.name]: e.target.checked } })} />
                  {t.name} <span className="tool-desc">{t.description}</span>
                </label>
              ))}
            </div>
          )}
          <div className="actions">
            <button className="save" onClick={doSave}>{editingId ? '保存' : '创建'}</button>
            {msg && <span className="msg">{msg}</span>}
            {err && <span className="err">{err}</span>}
          </div>
        </div>
      </div>
    </div>
  );
}
