import { afterEach, describe, expect, it } from 'vitest';
import { mount, enableAutoUnmount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import StatusMetricCard, { type StatusMetricStatus } from './index.vue';

enableAutoUnmount(afterEach);

function mountMetric(props: {
  title?: string;
  value?: number | string | null;
  unit?: string;
  threshold?: string;
  status?: StatusMetricStatus;
}) {
  return mount(StatusMetricCard, {
    props: {
      title: props.title ?? 'CPU 使用率',
      value: props.value,
      unit: props.unit,
      threshold: props.threshold,
      status: props.status ?? 'ok',
    },
    global: { plugins: [ElementPlus] },
  });
}

describe('StatusMetricCard', () => {
  it('renders the title and the value with unit', () => {
    const wrapper = mountMetric({ value: 12, unit: '%' });
    expect(wrapper.text()).toContain('CPU 使用率');
    expect(wrapper.text()).toContain('12');
    expect(wrapper.text()).toContain('%');
  });

  it('falls back to "—" when value is null', () => {
    const wrapper = mountMetric({ title: '磁盘使用率', value: null, status: 'unavailable' });
    const valueNode = wrapper.find('[data-testid="status-metric-value-磁盘使用率"]');
    expect(valueNode.text()).toContain('—');
  });

  it('falls back to "—" when value is undefined', () => {
    const wrapper = mountMetric({ title: '内存使用率', value: undefined, status: 'unavailable' });
    const valueNode = wrapper.find('[data-testid="status-metric-value-内存使用率"]');
    expect(valueNode.text()).toContain('—');
  });

  it('hides the unit suffix when value is empty', () => {
    const wrapper = mountMetric({ title: '网络吞吐', value: null, unit: 'MB/s', status: 'unavailable' });
    // The unit should not be rendered when there's no value.
    const valueNode = wrapper.find('[data-testid="status-metric-value-网络吞吐"]');
    expect(valueNode.text()).not.toContain('MB/s');
  });

  it('shows "数据不可用" tag for status=unavailable', () => {
    const wrapper = mountMetric({ status: 'unavailable' });
    expect(wrapper.find('[data-testid="status-metric-tone-unavailable"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('数据不可用');
  });

  it('shows "部分降级" tag for status=degraded and still renders the value', () => {
    const wrapper = mountMetric({
      title: '磁盘使用率',
      value: 86,
      unit: '%',
      status: 'degraded',
    });
    expect(wrapper.find('[data-testid="status-metric-tone-degraded"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('部分降级');
    // Degraded is not the same as unavailable — the partial value still shows.
    const valueNode = wrapper.find('[data-testid="status-metric-value-磁盘使用率"]');
    expect(valueNode.text()).toContain('86');
  });

  it('uses success tone for ok, warning for warning/degraded, danger for critical', () => {
    const expectations: Array<[StatusMetricStatus, string]> = [
      ['ok', 'el-tag--success'],
      ['warning', 'el-tag--warning'],
      ['degraded', 'el-tag--warning'],
      ['critical', 'el-tag--danger'],
      ['unavailable', 'el-tag--info'],
    ];
    expectations.forEach(([status, expected]) => {
      const wrapper = mountMetric({ status });
      const tag = wrapper.findComponent({ name: 'ElTag' });
      expect(tag.props('type')).toBe(expected.replace('el-tag--', ''));
    });
  });

  it('renders the threshold helper text when provided', () => {
    const wrapper = mountMetric({ value: 86, unit: '%', threshold: '阈值 80%' });
    expect(wrapper.text()).toContain('阈值 80%');
  });
});
