<template>
  <div class="chat-page">
    <!-- Header -->
    <header class="page-header">
      <span class="logo">政治经济学知识库问答系统</span>
      <div class="header-actions">
        <el-tag v-if="auth.isAdmin()" type="warning"><router-link to="/admin" style="color:inherit;text-decoration:none">知识库管理</router-link></el-tag>
        <span>{{ auth.user?.nickname || auth.user?.username }}</span>
        <el-button @click="auth.logout()">退出</el-button>
      </div>
    </header>

    <!-- Body -->
    <div class="chat-body">
      <!-- Session sidebar -->
      <aside class="session-sidebar">
        <div class="sidebar-top">
          <el-button type="primary" style="width:100%" @click="newSession">+ 新建会话</el-button>
        </div>
        <div class="session-list">
          <div v-for="s in sessions" :key="s.id"
               :class="['session-item', { active: s.id === currentSessionId }]"
               @click="switchSession(s)">
            <div class="session-title">
              <span>{{ s.title }}</span>
              <el-icon v-if="s.isFavorite === 1" style="color:#e6a23c"><StarFilled /></el-icon>
            </div>
            <div class="session-actions">
              <el-button link size="small" @click.stop="toggleFav(s)">
                <el-icon><Star /></el-icon>
              </el-button>
              <el-button link size="small" type="danger" @click.stop="deleteSession(s.id)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
          </div>
          <el-empty v-if="!sessions.length" description="暂无会话" :image-size="60" />
        </div>
      </aside>

      <!-- Chat area -->
      <main class="chat-main">
        <div class="message-area" ref="msgAreaRef">
          <div v-for="(msg, idx) in messages" :key="idx" :class="['msg', msg.role]">
            <div class="msg-avatar">
              <el-avatar :size="32" :icon="msg.role === 'assistant' ? Service : User" />
            </div>
            <div class="msg-content">
              <div class="msg-text" v-html="renderMarkdown(msg.content)" />
              <div v-if="msg.role === 'assistant' && msg.sources?.length" class="msg-sources">
                <el-divider />
                <p style="font-weight:600;font-size:12px;color:#909399">参考来源：</p>
                <div v-for="(src, si) in msg.sources" :key="si" class="source-item">
                  <span class="source-doc">{{ src.docTitle || ('文档#' + src.documentId) }}</span>
                  <span class="source-score" v-if="src.score">相似度: {{ (src.score * 100).toFixed(0) }}%</span>
                  <p class="source-snippet">{{ src.snippet }}</p>
                </div>
              </div>
            </div>
          </div>

          <!-- Streaming loading -->
          <div v-if="streaming" class="msg assistant">
            <div class="msg-avatar"><el-avatar :size="32" :icon="Service" /></div>
            <div class="msg-content">
              <div class="msg-text" v-html="renderMarkdown(streamText + ' ▌')" />
            </div>
          </div>

          <div style="height:20px" ref="scrollAnchor"></div>
        </div>

        <div class="input-area">
          <el-input v-model="input" type="textarea" :rows="2" placeholder="请输入您关于政治经济学的问题..."
                    :disabled="streaming" @keydown.enter.exact.prevent="sendMessage" />
          <el-button type="primary" :disabled="!input.trim() || streaming" :loading="streaming"
                     @click="sendMessage" style="margin-top:8px;float:right">
            {{ streaming ? '思考中...' : '发送' }}
          </el-button>
        </div>
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { Star, StarFilled, Delete, User, Service } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import api from '../api'
import { marked } from 'marked'

const auth = useAuthStore()
const sessions = ref([])
const currentSessionId = ref(null)
const messages = ref([])
const input = ref('')
const streaming = ref(false)
const streamText = ref('')
const msgAreaRef = ref(null)
const scrollAnchor = ref(null)

marked.setOptions({ breaks: true })
function renderMarkdown(text) {
  if (!text) return ''
  return marked(text)
}

async function scrollBottom() {
  await nextTick()
  const anchor = document.querySelector('.chat-main .message-area')
  if (anchor) anchor.scrollTop = anchor.scrollHeight
}

onMounted(async () => {
  await loadSessions()
})

async function loadSessions() {
  try {
    const res = await api.get('/chat/session/list')
    sessions.value = res.data || []
  } catch (e) { /* ignore */ }
}

async function newSession() {
  try {
    const res = await api.post('/chat/session')
    sessions.value.unshift(res.data)
    currentSessionId.value = res.data.id
    messages.value = []
    streamText.value = ''
  } catch (e) { /* ignore */ }
}

async function switchSession(session) {
  currentSessionId.value = session.id
  streaming.value = false
  streamText.value = ''
  try {
    const res = await api.get(`/chat/session/${session.id}/messages`)
    messages.value = (res.data || []).map(m => ({ ...m, sources: [] }))
    // Load sources for assistant messages
    for (const msg of messages.value) {
      if (msg.role === 'assistant') {
        try {
          const srcRes = await api.get(`/chat/message/${msg.id}/sources`)
          msg.sources = srcRes.data || []
        } catch (e) { /* ignore */ }
      }
    }
    scrollBottom()
  } catch (e) { /* ignore */ }
}

async function deleteSession(sid) {
  await ElMessageBox.confirm('确定删除该会话？', '提示', { type: 'warning' })
  await api.delete(`/chat/session/${sid}`)
  sessions.value = sessions.value.filter(s => s.id !== sid)
  if (currentSessionId.value === sid) {
    currentSessionId.value = null
    messages.value = []
  }
  ElMessage.success('已删除')
}

async function toggleFav(session) {
  await api.post(`/chat/session/${session.id}/favorite`)
  session.isFavorite = session.isFavorite === 1 ? 0 : 1
}

async function sendMessage() {
  const q = input.value.trim()
  if (!q || streaming.value) return
  input.value = ''

  if (!currentSessionId.value) await newSession()
  const sid = currentSessionId.value

  // Add user message
  messages.value.push({ role: 'user', content: q })
  scrollBottom()

  streaming.value = true
  streamText.value = ''

  try {
    // Get the fetch-based SSE stream
    const token = localStorage.getItem('accessToken')
    const resp = await fetch(`/api/chat/send`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${token}`,
      },
      body: JSON.stringify({ question: q, sessionId: sid }),
    })

    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let fullText = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      const chunk = decoder.decode(value, { stream: true })
      // SSE data lines
      const lines = chunk.split('\n').filter(l => l.startsWith('data:'))
      for (const line of lines) {
        const data = line.replace('data:', '').trim()
        if (data) {
          fullText += data
          streamText.value = fullText
          await nextTick()
          scrollBottom()
        }
      }
    }

    // Done: add assistant message
    messages.value.push({ role: 'assistant', content: fullText, sources: [] })
    streamText.value = ''

    // Reload messages to get persisted IDs
    const res = await api.get(`/chat/session/${sid}/messages`)
    messages.value = (res.data || []).map(m => ({ ...m, sources: [] }))
    for (const msg of messages.value) {
      if (msg.role === 'assistant') {
        try {
          const srcRes = await api.get(`/chat/message/${msg.id}/sources`)
          msg.sources = srcRes.data || []
        } catch (e) { /* ignore */ }
      }
    }
    scrollBottom()
  } catch (e) {
    ElMessage.error('发送失败')
  } finally {
    streaming.value = false
    streamText.value = ''
  }
}
</script>

<style scoped>
.chat-page { height: 100vh; display: flex; flex-direction: column; }
.chat-body { flex: 1; display: flex; overflow: hidden; }

.session-sidebar {
  width: 260px; min-width: 260px; background: #fff; border-right: 1px solid #e4e7ed;
  display: flex; flex-direction: column;
}
.sidebar-top { padding: 12px; }
.session-list { flex: 1; overflow-y: auto; padding: 0; }
.session-item {
  padding: 10px 12px; cursor: pointer; border-bottom: 1px solid #f0f0f0;
  display: flex; justify-content: space-between; align-items: center;
}
.session-item:hover { background: #f5f7fa; }
.session-item.active { background: #ecf5ff; }
.session-title { flex: 1; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.session-actions { display: flex; gap: 2px; visibility: hidden; }
.session-item:hover .session-actions { visibility: visible; }

.chat-main { flex: 1; display: flex; flex-direction: column; background: #f5f7fa; }
.message-area { flex: 1; overflow-y: auto; padding: 16px 24px; }

.msg { display: flex; gap: 10px; margin-bottom: 16px; }
.msg.user { flex-direction: row-reverse; }
.msg-content { max-width: 75%; }
.msg.user .msg-text { background: #409eff; color: #fff; border-radius: 12px 12px 0 12px; }
.msg.assistant .msg-text { background: #fff; color: #303133; border-radius: 12px 12px 12px 0; box-shadow: 0 1px 2px rgba(0,0,0,.06); }
.msg-text { padding: 10px 14px; font-size: 14px; line-height: 1.7; word-break: break-word; }
.msg-text :deep(p) { margin: 0 0 6px; }
.msg-text :deep(pre) { background: rgba(0,0,0,.05); padding: 8px; border-radius: 6px; overflow-x: auto; }
.msg.user .msg-text :deep(pre) { background: rgba(255,255,255,.15); }
.msg-text :deep(code) { font-size: 13px; }

.msg-sources { font-size: 12px; margin-top: 4px; }
.source-item { background: #fff; padding: 8px; border-radius: 6px; margin-top: 6px; border: 1px solid #ebeef5; }
.source-doc { font-weight: 600; color: #1a3a5c; }
.source-score { font-size: 11px; color: #909399; margin-left: 8px; }
.source-snippet { color: #606266; margin-top: 4px; max-height: 80px; overflow-y: auto; font-size: 12px; line-height: 1.5; }

.input-area { padding: 12px 24px 16px; background: #fff; border-top: 1px solid #e4e7ed; }
</style>
