import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '../api'

export const useAuthStore = defineStore('auth', () => {
  const user = ref(JSON.parse(localStorage.getItem('user') || 'null'))
  const accessToken = ref(localStorage.getItem('accessToken') || '')
  const refreshToken = ref(localStorage.getItem('refreshToken') || '')

  const isLoggedIn = () => !!accessToken.value
  const isAdmin = () => user.value?.roles?.includes('ADMIN')

  async function login(username, password) {
    const res = await api.post('/auth/login', { username, password })
    const data = res.data
    saveTokens(data)
    return data
  }

  async function register(form) {
    await api.post('/auth/register', form)
  }

  async function refresh() {
    const res = await api.post('/auth/refresh', { refreshToken: refreshToken.value })
    saveTokens(res.data)
  }

  function logout() {
    clearTokens()
    window.location.href = '/login'
  }

  function saveTokens(data) {
    accessToken.value = data.accessToken
    refreshToken.value = data.refreshToken
    user.value = data.userInfo
    localStorage.setItem('accessToken', data.accessToken)
    localStorage.setItem('refreshToken', data.refreshToken)
    localStorage.setItem('user', JSON.stringify(data.userInfo))
  }

  function clearTokens() {
    accessToken.value = ''
    refreshToken.value = ''
    user.value = null
    localStorage.clear()
  }

  return { user, accessToken, refreshToken, isLoggedIn, isAdmin, login, register, refresh, logout }
})
