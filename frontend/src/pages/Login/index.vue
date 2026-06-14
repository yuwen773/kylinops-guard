<script setup lang="ts">
/**
 * Admin login page.
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

  // Manually validate via the element-plus form ref. We do not gate on
  // the validation promise resolving — required fields are also enforced
  // by the input bindings.
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
    // Drop password from reactive state ASAP.
    form.password = '';
    await router.replace('/');
  } catch (err) {
    if (err instanceof ApiError) {
      // 401 → generic. 423 / 429 → use backend's message because the
      // operator must learn that the account is locked or rate-limited.
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
    // Always wipe password on failure too.
    form.password = '';
  } finally {
    isSubmitting.value = false;
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always" data-testid="login-card">
      <header class="login-title">
        <h1>麒麟安全智能运维 Agent</h1>
        <p class="login-subtitle">管理员登录</p>
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
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2d3d 0%, #2d4263 100%);
}

.login-card {
  width: 360px;
  padding: 0.5rem 0.25rem;
}

.login-title {
  text-align: center;
  margin-bottom: 1.25rem;
}

.login-title h1 {
  font-size: 1.1rem;
  margin: 0;
  color: #1f2d3d;
}

.login-subtitle {
  margin-top: 0.25rem;
  margin-bottom: 0;
  color: #6c7a89;
  font-size: 0.85rem;
}

.login-error {
  margin-bottom: 1rem;
}

.login-submit {
  width: 100%;
}
</style>
