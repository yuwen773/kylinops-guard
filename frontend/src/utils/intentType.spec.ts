import { describe, it, expect } from 'vitest';
import { normalizeIntentType, rcaTitleFor } from './intentType';

describe('normalizeIntentType', () => {
  it('maps backend SYSTEM_CHECK to canonical SYSTEM_CHECK', () => {
    expect(normalizeIntentType('SYSTEM_CHECK')).toBe('SYSTEM_CHECK');
  });
  it('maps legacy frontend HEALTH_CHECK to SYSTEM_CHECK', () => {
    expect(normalizeIntentType('HEALTH_CHECK')).toBe('SYSTEM_CHECK');
  });
  it('maps PROCESS_INQUIRY to PROCESS_QUERY', () => {
    expect(normalizeIntentType('PROCESS_INQUIRY')).toBe('PROCESS_QUERY');
  });
  it('maps NETWORK_INQUIRY to NETWORK_QUERY', () => {
    expect(normalizeIntentType('NETWORK_INQUIRY')).toBe('NETWORK_QUERY');
  });
  it('maps LOG_INQUIRY to LOG_QUERY', () => {
    expect(normalizeIntentType('LOG_INQUIRY')).toBe('LOG_QUERY');
  });
  it('passes through unknown values', () => {
    expect(normalizeIntentType('UNKNOWN')).toBe('UNKNOWN');
    expect(normalizeIntentType('FOO_BAR')).toBe('FOO_BAR');
  });
});

describe('rcaTitleFor', () => {
  it('returns Chinese title for each canonical intent', () => {
    expect(rcaTitleFor('DISK_DIAGNOSIS')).toBe('根因分析链');
    expect(rcaTitleFor('SYSTEM_CHECK')).toBe('健康评估链');
    expect(rcaTitleFor('SERVICE_DIAGNOSIS')).toBe('服务诊断链');
  });
  it('handles legacy HEALTH_CHECK', () => {
    expect(rcaTitleFor('HEALTH_CHECK')).toBe('健康评估链');
  });
});