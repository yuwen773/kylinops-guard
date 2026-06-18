<script setup lang="ts">
/**
 * Admin login page — Command Center edition.
 *
 * UX rules locked by P2-T5:
 *   - Username + password fields, single "登录" button. No "记住我", no
 *     "忘记密码", no "注册" links — this product has exactly one admin
 *     and no self-service registration.
 *   - On failure the form shows a single generic message
 *     ("用户名或密码错误") regardless of whether the username exists.
 *     Locked / rate-limited responses use the backend's own message
 *     ("账户已锁定 …" / "请求过于频繁 …") so the operator can act.
 *   - On success the in-memory session is populated (by `login()`) and the
 *     page navigates to `/` which the router resolves to `/chat`.
 *
 * The password is held in a local `ref` for the duration of the submit
 * and cleared immediately after the call (success or failure) so it does
 * not linger in Vue's reactivity graph.
 */
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElForm, ElFormItem, ElInput, ElButton, ElAlert, ElCard } from 'element-plus';
import { login as loginApi } from '@/api/auth';
import { ApiError } from '@/api/client';
import type { LoginRequest } from '@/types/auth';

const router = useRouter();

const form = reactive<LoginRequest>({
  username: '',
  password: '',
});

const errorMessage = ref<string>('');
const isSubmitting = ref<boolean>(false);

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
};

const formRef = ref<InstanceType<typeof ElForm> | null>(null);

async function handleSubmit(): Promise<void> {
  if (isSubmitting.value) return;
  errorMessage.value = '';

  if (formRef.value) {
    const valid = await formRef.value.validate().catch(() => false);
    if (!valid) return;
  }
  if (!form.username.trim() || !form.password) {
    errorMessage.value = '用户名或密码错误';
    return;
  }

  isSubmitting.value = true;
  const payload: LoginRequest = {
    username: form.username.trim(),
    password: form.password,
  };
  try {
    await loginApi(payload);
    form.password = '';
    await router.replace('/');
  } catch (err) {
    if (err instanceof ApiError) {
      if (err.httpStatus === 401 || err.code === 401) {
        errorMessage.value = '用户名或密码错误';
      } else if (err.httpStatus === 423 || err.code === 423) {
        errorMessage.value = err.message || '账户已锁定，请稍后再试';
      } else if (err.httpStatus === 429 || err.code === 429) {
        errorMessage.value = err.message || '请求过于频繁，请稍后再试';
      } else {
        errorMessage.value = '登录失败，请稍后再试';
      }
    } else {
      errorMessage.value = '登录失败，请稍后再试';
    }
    form.password = '';
  } finally {
    isSubmitting.value = false;
  }
}
</script>

<template>
  <div class="login-page kg-bg-grid">
    <!-- Ambient glow orbs -->
    <div class="login-ambient login-ambient--1" aria-hidden="true" />
    <div class="login-ambient login-ambient--2" aria-hidden="true" />

    <div class="login-container">
      <!-- Brand header -->
      <div class="login-brand">
        <span class="login-brand-mark">KG</span>
        <div class="login-brand-text">
          <span class="login-brand-title">麒麟安全智能运维 Agent</span>
          <span class="login-brand-sub">KylinOps Guard</span>
        </div>
      </div>

      <el-card class="login-card kg-glass" shadow="never" data-testid="login-card">
        <header class="login-title">
          <h1>安全准入</h1>
          <p class="login-subtitle">管理员身份验证</p>
        </header>

        <el-alert
          v-if="errorMessage"
          :title="errorMessage"
          type="error"
          :closable="false"
          show-icon
          class="login-error"
          data-testid="login-error"
        />

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          @submit.prevent="handleSubmit"
        >
          <el-form-item label="用户名" prop="username">
            <el-input
              v-model="form.username"
              placeholder="请输入用户名"
              autocomplete="username"
              data-testid="login-username"
            />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              autocomplete="current-password"
              show-password
              data-testid="login-password"
              @keyup.enter="handleSubmit"
            />
          </el-form-item>

          <el-form-item>
            <el-button
              type="primary"
              class="login-submit"
              :loading="isSubmitting"
              data-testid="login-submit"
              @click="handleSubmit"
            >
              登录
            </el-button>
          </el-form-item>
        </el-form>

        <!-- Security footer -->
        <div class="login-footer">
          <span class="login-footer-line" />
          <span class="login-footer-text">安全运维 · 审计留痕 · 风险阻断</span>
          <span class="login-footer-line" />
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: var(--kg-color-bg);
  position: relative;
  overflow: hidden;
}

/* Ambient glow orbs */
.login-ambient {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  pointer-events: none;
  opacity: 0.3;
}

.login-ambient--1 {
  width: 400px;
  height: 400px;
  background: radial-gradient(circle, rgba(59, 130, 246, 0.2), transparent);
  top: -100px;
  right: -100px;
  animation: kg-pulseGlow 6s ease-in-out infinite;
}

.login-ambient--2 {
  width: 350px;
  height: 350px;
  background: radial-gradient(circle, rgba(6, 182, 212, 0.15), transparent);
  bottom: -80px;
  left: -80px;
  animation: kg-pulseGlow 8s ease-in-out infinite reverse;
}

.login-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--kg-space-6);
  z-index: 1;
}

/* Brand header */
.login-brand {
  display: flex;
  align-items: center;
  gap: var(--kg-space-3);
}

.login-brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: var(--kg-radius-md);
  background: linear-gradient(135deg, var(--kg-color-primary), var(--kg-color-accent));
  color: white;
  font-family: var(--kg-font-mono);
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.05em;
  box-shadow: 0 0 24px rgba(59, 130, 246, 0.25);
}

.login-brand-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.login-brand-title {
  font-size: var(--kg-text-xl);
  font-weight: 700;
  color: var(--kg-color-text-primary);
  line-height: 1.3;
  letter-spacing: 0.02em;
}

.login-brand-sub {
  font-size: var(--kg-text-sm);
  color: var(--kg-color-text-mute);
  font-family: var(--kg-font-mono);
  letter-spacing: 0.04em;
}

/* Login card */
.login-card {
  width: 380px;
  padding: var(--kg-space-2);
}

.login-title {
  text-align: center;
  margin-bottom: var(--kg-space-5);
}

.login-title h1 {
  font-size: var(--kg-text-lg);
  margin: 0;
  color: var(--kg-color-text-primary);
  font-weight: 600;
}

.login-subtitle {
  margin-top: var(--kg-space-1);
  margin-bottom: 0;
  color: var(--kg-color-text-mute);
  font-size: var(--kg-text-sm);
}

.login-error {
  margin-bottom: var(--kg-space-4);
}

.login-submit {
  width: 100%;
}

/* Form label color override for dark glass card */
.login-card :deep(.el-form-item__label) {
  color: var(--kg-color-text-secondary);
  font-size: var(--kg-text-sm);
}

.login-card :deep(.el-input__wrapper) {
  background: var(--kg-color-surface-mute);
  border: 1px solid var(--kg-color-border);
  box-shadow: none;
}

.login-card :deep(.el-input__wrapper:hover) {
  border-color: var(--kg-color-border-strong);
}

.login-card :deep(.el-input__wrapper.is-focus) {
  border-color: var(--kg-color-primary);
  box-shadow: 0 0 0 1px var(--kg-color-primary-soft);
}

:root[data-theme="light"] .login-card :deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.7);
  border-color: var(--kg-color-border-strong);
}

/* Security footer */
.login-footer {
  display: flex;
  align-items: center;
  gap: var(--kg-space-2);
  margin-top: var(--kg-space-4);
  padding-top: var(--kg-space-3);
  border-top: 1px solid var(--kg-color-border);
}

.login-footer-line {
  flex: 1;
  height: 1px;
  background: var(--kg-color-border);
}

.login-footer-text {
  font-size: var(--kg-text-xs);
  color: var(--kg-color-text-mute);
  white-space: nowrap;
  letter-spacing: 0.06em;
}
</style>
