<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import NavIcon from '../components/NavIcon.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const merchantCode = ref('DEMO_MERCHANT')
const username = ref('csr')
const password = ref('')
const pending = ref(false)
const error = ref(route.query.expired ? '登录已过期，请重新登录。' : '')

async function submit() {
  if (pending.value) return
  pending.value = true
  error.value = ''
  try {
    await auth.login(merchantCode.value.trim(), username.value.trim(), password.value)
    await router.push(typeof route.query.next === 'string' ? route.query.next : '/workspace')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '登录失败，请检查账号信息后重试。'
  } finally {
    pending.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-story">
      <div class="login-brand"><span class="brand__mark"><span></span><span></span><span></span></span><div><strong>织云</strong><small>售后运营系统</small></div></div>
      <div class="login-story__copy">
        <span class="eyebrow eyebrow--light">AFTERCARE OPERATIONS</span>
        <h1>把每一次售后，<br><em>都处理得更清楚。</em></h1>
        <p>订单、物流、政策依据与后续操作，在一个协作空间里完成。</p>
      </div>
      <div class="login-story__footer"><span class="story-rule"></span><span>服务有依据 · 操作可追踪</span><span>WORKSPACE / 01</span></div>
      <div class="story-orbit story-orbit--one"></div><div class="story-orbit story-orbit--two"></div>
    </section>
    <section class="login-form-side">
      <form class="login-form" @submit.prevent="submit">
        <div class="login-form__heading"><span class="eyebrow">欢迎回来</span><h2>登录工作台</h2><p>使用您的内部账号继续处理售后事务。</p></div>
        <label class="field"><span>商家编码</span><input v-model="merchantCode" name="merchantCode" autocomplete="organization" required maxlength="64" placeholder="例如 DEMO_MERCHANT" /></label>
        <label class="field"><span>用户名</span><input v-model="username" name="username" autocomplete="username" required maxlength="64" placeholder="输入内部账号" /></label>
        <label class="field"><span>密码</span><input v-model="password" name="password" type="password" autocomplete="current-password" required maxlength="128" placeholder="输入密码" /></label>
        <p v-if="error" class="form-error login-error" role="alert">{{ error }}</p>
        <button class="button button--primary button--wide login-submit" type="submit" :disabled="pending">
          <span>{{ pending ? '正在验证…' : '进入工作台' }}</span><NavIcon v-if="!pending" name="arrow" :size="18" />
        </button>
        <div class="login-help"><span>演示账号</span><code>csr / supervisor / warehouse / admin</code><small>使用启动环境配置的对应账号密码。修改密码配置后需重新启动服务。</small></div>
        <div class="login-security"><span aria-hidden="true">◈</span>访问记录将纳入操作审计</div>
      </form>
    </section>
  </main>
</template>
