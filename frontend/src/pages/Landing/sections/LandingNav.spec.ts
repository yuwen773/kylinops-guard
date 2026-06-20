import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, enableAutoUnmount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import LandingNav from './LandingNav.vue'

/**
 * Captured IntersectionObserver instances. Each `new IntersectionObserver(cb)`
 * call appends to this list; tests assert against them.
 */
type Callback = (entries: Array<{ isIntersecting: boolean }>) => void
interface ObservedTarget {
  el: Element
  cb: Callback
}
const observed: ObservedTarget[] = []
let lastObserver: { callback: Callback; options: IntersectionObserverInit | undefined } | null =
  null

class FakeIntersectionObserver implements IntersectionObserver {
  readonly root: Element | Document | null = null
  readonly rootMargin = '0px'
  readonly thresholds: ReadonlyArray<number> = []
  readonly callback: Callback
  readonly options: IntersectionObserverInit | undefined
  private targets: Element[] = []

  constructor(cb: Callback, options?: IntersectionObserverInit) {
    this.callback = cb
    this.options = options
    lastObserver = { callback: cb, options }
  }

  observe(target: Element): void {
    this.targets.push(target)
    observed.push({ el: target, cb: this.callback })
  }

  unobserve(target: Element): void {
    this.targets = this.targets.filter((t) => t !== target)
  }

  disconnect(): void {
    this.targets = []
  }

  takeRecords(): IntersectionObserverEntry[] {
    return []
  }
}

describe('LandingNav — scroll state via IntersectionObserver', () => {
  enableAutoUnmount(afterEach)

  function buildRouter(): Router {
    return createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/login', component: { template: '<div />' } },
        { path: '/landing', component: { template: '<div />' } },
      ],
    })
  }

  async function mountNav() {
    const router = buildRouter()
    router.push('/landing')
    await router.isReady()
    const wrapper = mount(LandingNav, { global: { plugins: [router] } })
    await flushPromises()
    return wrapper
  }

  beforeEach(() => {
    observed.length = 0
    lastObserver = null
    // Install the fake before mounting.
    ;(globalThis as unknown as { IntersectionObserver: typeof FakeIntersectionObserver }).IntersectionObserver =
      FakeIntersectionObserver
    // Sanity: no scroll listener helpers on window.
    const win = globalThis as unknown as { addEventListener: typeof window.addEventListener }
    vi.spyOn(win, 'addEventListener').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('does not register a window scroll listener (skill §5.D)', async () => {
    const win = globalThis as unknown as { addEventListener: typeof window.addEventListener }
    const spy = vi.spyOn(win, 'addEventListener')
    const wrapper = await mountNav()
    const scrollCalls = spy.mock.calls.filter(([type]) => type === 'scroll')
    expect(scrollCalls).toEqual([])
    wrapper.unmount()
  })

  it('creates one IntersectionObserver that watches the sentinel div', async () => {
    const wrapper = await mountNav()
    expect(lastObserver).not.toBeNull()
    expect(observed).toHaveLength(1)
    expect(observed[0].el.getAttribute('data-testid')).toBe('landing-nav-sentinel')
    wrapper.unmount()
  })

  it('starts with is-scrolled=false at scroll position 0 (sentinel visible)', async () => {
    const wrapper = await mountNav()
    const nav = wrapper.find('[data-testid="landing-nav"]')
    expect(nav.classes()).not.toContain('is-scrolled')
    wrapper.unmount()
  })

  it('applies is-scrolled when the sentinel scrolls out of view', async () => {
    const wrapper = await mountNav()
    // Simulate the user scrolling down: the sentinel (at document y=0) is
    // no longer in the viewport.
    observed[0].cb([{ isIntersecting: false }])
    await flushPromises()
    const nav = wrapper.find('[data-testid="landing-nav"]')
    expect(nav.classes()).toContain('is-scrolled')
    // Returning to the top reverts the class.
    observed[0].cb([{ isIntersecting: true }])
    await flushPromises()
    expect(nav.classes()).not.toContain('is-scrolled')
    wrapper.unmount()
  })

  it('disconnects the observer on unmount to avoid leaks', async () => {
    const disconnectSpy = vi.spyOn(FakeIntersectionObserver.prototype, 'disconnect')
    const wrapper = await mountNav()
    wrapper.unmount()
    expect(disconnectSpy).toHaveBeenCalled()
  })
})
