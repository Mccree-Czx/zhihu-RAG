<template>
  <div class="admin-page">
    <header class="page-header">
      <span class="logo">知识库管理</span>
      <div class="header-actions">
        <router-link to="/chat"><el-button>返回问答</el-button></router-link>
        <span>{{ auth.user?.username }}</span>
        <el-button @click="auth.logout()">退出</el-button>
      </div>
    </header>

    <div class="admin-body">
      <!-- Tabs -->
      <el-tabs v-model="activeTab" style="padding:0 24px">
        <el-tab-pane label="文档管理" name="docs" />
        <el-tab-pane label="分类管理" name="categories" />
        <el-tab-pane label="标签管理" name="tags" />
      </el-tabs>

      <!-- Documents -->
      <div v-if="activeTab === 'docs'" class="tab-content">
        <!-- Upload -->
        <div style="display:flex;gap:12px;align-items:center;margin-bottom:16px;flex-wrap:wrap">
          <el-upload :auto-upload="false" :show-file-list="false" :on-change="handleFileChange" accept=".pdf,.docx,.doc,.txt,.md,.csv,.json,.xml,.html">
            <el-button type="primary" :icon="Upload">选择文档</el-button>
          </el-upload>
          <el-input v-model="uploadForm.title" placeholder="标题(可选)" style="width:200px" />
          <el-select v-model="uploadForm.categoryId" placeholder="分类(可选)" clearable style="width:160px">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <el-button :disabled="!uploadFile" :loading="uploading" @click="doUpload">上传</el-button>
          <span v-if="uploadFile" style="color:#909399;font-size:13px">已选: {{ uploadFile?.name }}</span>
        </div>

        <!-- Search -->
        <div style="margin-bottom:16px">
          <el-input v-model="keyword" placeholder="搜索文档..." clearable @clear="loadDocs" @keyup.enter="loadDocs" style="width:280px">
            <template #append><el-button @click="loadDocs" :icon="Search" /></template>
          </el-input>
        </div>

        <!-- Table -->
        <el-table :data="docs" border stripe style="width:100%" v-loading="docLoading">
          <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
          <el-table-column prop="fileType" label="类型" width="70" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag v-if="row.status === 'INDEXED'" type="success">已索引</el-tag>
              <el-tag v-else-if="row.status === 'PARSING'" type="warning">解析中</el-tag>
              <el-tag v-else-if="row.status === 'FAILED'" type="danger">失败</el-tag>
              <span v-else>{{ row.status }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="chunkCount" label="分块数" width="80" />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="deleteDoc(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination v-if="docTotal > pageSize" style="margin-top:16px;justify-content:center"
                       v-model:current-page="docPage" :page-size="pageSize" :total="docTotal"
                       layout="prev, pager, next" @current-change="loadDocs" />
      </div>

      <!-- Categories -->
      <div v-if="activeTab === 'categories'" class="tab-content">
        <div style="margin-bottom:12px">
          <el-input v-model="newCatName" placeholder="新分类名称" style="width:200px;margin-right:8px" />
          <el-button type="primary" @click="addCategory">添加</el-button>
        </div>
        <el-table :data="categories" border stripe>
          <el-table-column prop="name" label="名称" />
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="deleteCategory(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- Tags -->
      <div v-if="activeTab === 'tags'" class="tab-content">
        <div style="margin-bottom:12px">
          <el-input v-model="newTagName" placeholder="新标签名称" style="width:200px;margin-right:8px" />
          <el-button type="primary" @click="addTag">添加</el-button>
        </div>
        <el-table :data="tags" border stripe>
          <el-table-column prop="name" label="名称" />
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="deleteTag(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Upload, Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import api from '../api'

const auth = useAuthStore()
const activeTab = ref('docs')

// Documents
const docs = ref([])
const keyword = ref('')
const docPage = ref(1)
const pageSize = ref(10)
const docTotal = ref(0)
const docLoading = ref(false)
const uploadFile = ref(null)
const uploading = ref(false)
const uploadForm = ref({ title: '', categoryId: null })

// Categories & Tags
const categories = ref([])
const tags = ref([])
const newCatName = ref('')
const newTagName = ref('')

onMounted(() => {
  loadDocs()
  loadCategories()
  loadTags()
})

function handleFileChange(file) { uploadFile.value = file.raw }

async function doUpload() {
  if (!uploadFile.value) return
  uploading.value = true
  try {
    const fd = new FormData()
    fd.append('file', uploadFile.value)
    if (uploadForm.value.title) fd.append('title', uploadForm.value.title)
    if (uploadForm.value.categoryId) fd.append('categoryId', uploadForm.value.categoryId)
    await api.post('/kb/document/upload', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
    ElMessage.success('上传成功，正在解析中...')
    uploadFile.value = null
    uploadForm.value = { title: '', categoryId: null }
    loadDocs()
  } catch (e) { /* handled */ }
  finally { uploading.value = false }
}

async function loadDocs() {
  docLoading.value = true
  try {
    const res = await api.get('/kb/document/list', {
      params: { page: docPage.value, size: pageSize.value, keyword: keyword.value || undefined }
    })
    docs.value = res.data.records || []
    docTotal.value = res.data.total || 0
  } finally { docLoading.value = false }
}

async function deleteDoc(id) {
  await ElMessageBox.confirm('确定删除该文档？', '提示', { type: 'warning' })
  await api.delete(`/kb/document/${id}`)
  ElMessage.success('已删除')
  loadDocs()
}

async function loadCategories() {
  const res = await api.get('/kb/category/list')
  categories.value = res.data || []
}

async function addCategory() {
  if (!newCatName.value.trim()) return
  await api.post('/kb/category', { name: newCatName.value.trim(), sort: 0 })
  ElMessage.success('已添加')
  newCatName.value = ''
  loadCategories()
}

async function deleteCategory(id) {
  await ElMessageBox.confirm('确定删除该分类？', '提示', { type: 'warning' })
  await api.delete(`/kb/category/${id}`)
  ElMessage.success('已删除')
  loadCategories()
}

async function loadTags() {
  const res = await api.get('/kb/tag/list')
  tags.value = res.data || []
}

async function addTag() {
  if (!newTagName.value.trim()) return
  await api.post('/kb/tag', { name: newTagName.value.trim() })
  ElMessage.success('已添加')
  newTagName.value = ''
  loadTags()
}

async function deleteTag(id) {
  await ElMessageBox.confirm('确定删除该标签？', '提示', { type: 'warning' })
  await api.delete(`/kb/tag/${id}`)
  ElMessage.success('已删除')
  loadTags()
}
</script>

<style scoped>
.admin-page { height: 100vh; display: flex; flex-direction: column; }
.admin-body { flex: 1; overflow-y: auto; background: #f5f7fa; }
.tab-content { padding: 16px 24px; }
</style>
