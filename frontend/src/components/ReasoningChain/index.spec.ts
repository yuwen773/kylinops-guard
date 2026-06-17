import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import ReasoningChain from './index.vue';
import type { RootCauseChain } from '@/types/rca';

const sample: RootCauseChain = {
  symptom: '磁盘根分区使用率 86%',
  evidence: [{
    evidenceId: 'ev-1', source: 'disk_usage_tool',
    sourceToolCallId: 'tc-1', observation: '/ 86% used',
    numericValue: 86, unit: '%',
  }],
  hypotheses: [{
    cause: '/var/log/app.log 占用 12GB', probability: 0.86,
    confirmed: true, reasoning: 'large_file_scan_tool 定位',
  }],
  excludedCauses: [{
    cause: '/var/lib/mysql（敏感数据库）',
    reason: '数据库目录不建议清理',
    evidenceIds: ['ev-1'],
  }],
  conclusion: '主因是 /var/log/app.log 持续增长',
  confidence: 0.86,
  suggestions: ['先归档或截断日志'],
  riskTips: ['清理前需先归档'],
};

function mountRc() {
  return mount(ReasoningChain, {
    props: { chain: sample, title: '根因分析链' },
    global: { plugins: [ElementPlus] },
  });
}

describe('ReasoningChain', () => {
  it('renders symptom as title', () => {
    const w = mountRc();
    expect(w.text()).toContain('根因分析链');
    expect(w.text()).toContain('磁盘根分区使用率 86%');
  });

  it('renders evidence count', () => {
    const w = mountRc();
    expect(w.text()).toContain('disk_usage_tool');
  });

  it('renders conclusion and riskTips', () => {
    const w = mountRc();
    expect(w.text()).toContain('主因是 /var/log/app.log 持续增长');
    expect(w.text()).toContain('清理前需先归档');
  });
});