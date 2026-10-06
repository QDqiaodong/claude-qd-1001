<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from '../router'
import api from '../api'
import CuttingIssuePanel from '../components/CuttingIssuePanel.vue'

// ------------------------------------------------------------------
// 页面操作范式：纸机导轨 + 纸卷长度条。
// 每台纸机是一条横向导轨，它抄出来的纸卷按「剩余长度」画成粗细不一的条子排在导轨上，
// 条越长代表余量越多；点条选中该卷，下方可完成检验，并通过订单领料闭环扣减余量。
// 纸机头部带 EXTRA 懒加载取到的纸卷总数徽章（那个数字是 count 查询，不装载集合）。
// ------------------------------------------------------------------

// 本仓 main.js 不安装 router 插件，直接读 router 实例上的当前路由
const routeQuery = computed(() => router.currentRoute.value.query || {})
const machines = ref([])
const rolls = ref([])
const orders = ref([])
const error = ref('')
const tip = ref('')
const selectedRollId = ref(null)
const counts = ref({})

const rollsByMachine = computed(() => {
  const map = {}
  for (const r of rolls.value) {
    const mid = r.machineId ?? (r.machine && r.machine.id) ?? r.machine_id
    if (!map[mid]) map[mid] = []
    map[mid].push(r)
  }
  return map
})

const maxLength = computed(() => {
  let m = 1
  for (const r of rolls.value) m = Math.max(m, Number(r.lengthM || 0))
  return m
})

const selected = computed(() => rolls.value.find((r) => r.id === selectedRollId.value) || null)

async function load() {
  error.value = ''
  try {
    const q = routeQuery.value.q
    machines.value = await api.listMachines()
    rolls.value = await api.listRolls(q ? { status: q } : {})
    orders.value = await api.listOrders()
    if (!rolls.value.length && q) rolls.value = await api.listRolls()
    // 逐台取纸卷总数（后端走 count 查询）
    const c = {}
    for (const m of machines.value) {
      try {
        const s = await api.machineSummary(m.id)
        c[m.id] = s.rollCount
      } catch (e) { c[m.id] = '-' }
    }
    counts.value = c
    if (!selectedRollId.value && rolls.value.length) selectedRollId.value = rolls.value[0].id
  } catch (e) {
    error.value = e.message
  }
}

function barWidth(r) {
  return Math.max(6, (Number(r.lengthM) / maxLength.value) * 100) + '%'
}

async function inspect(target) {
  if (!selected.value) return
  error.value = ''; tip.value = ''
  try {
    await api.inspectRoll(selected.value.id, target)
    tip.value = `${selected.value.rollNo} 已检验为「${target}」`
    await load()
  } catch (e) { error.value = e.message }
}

async function transfer(target) {
  if (!selected.value) return
  error.value = ''; tip.value = ''
  try {
    await api.transferRoll(selected.value.id, target)
    tip.value = `${selected.value.rollNo} 已转为「${target}」`
    await load()
  } catch (e) { error.value = e.message }
}
async function onIssueSuccess() {
  await load()
}

watch(() => routeQuery.value.q, () => load())
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">抄纸工段 · 纸机导轨与母卷</span>
        <span class="panel-desc">条子长度 = 母卷剩余长度；点条选中，可检验、查看领料上限并通过订单闭环登记</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">纸机</div><div class="v">{{ machines.length }}</div></div>
        <div class="stat"><div class="k">纸卷</div><div class="v">{{ rolls.length }}</div></div>
        <div class="stat"><div class="k">可用</div><div class="v">{{ rolls.filter(r => r.status === '可用').length }}</div></div>
        <div class="stat"><div class="k">待检</div><div class="v">{{ rolls.filter(r => r.status === '待检').length }}</div></div>
      </div>

      <div v-for="m in machines" :key="m.id" class="rail">
        <div class="rail-head">
          <span class="rail-name">{{ m.machineName }}</span>
          <span class="rail-code">{{ m.machineCode }}</span>
          <span class="rail-chip">设计幅宽 {{ m.designWidthMm }}mm</span>
          <span class="rail-chip">{{ m.status }}</span>
          <span class="rail-count" title="EXTRA 懒加载：count 查询，不装载集合">
            纸卷 {{ counts[m.id] ?? (rollsByMachine[m.id] ? rollsByMachine[m.id].length : 0) }}
          </span>
        </div>

        <div class="rail-track">
          <div
            v-for="r in (rollsByMachine[m.id] || [])"
            :key="r.id"
            class="roll-bar"
            :class="[ 'g-' + r.grade, { sel: r.id === selectedRollId, done: r.status === '已切完' } ]"
            :style="{ width: barWidth(r) }"
            :title="`${r.rollNo} · ${r.grammageGsm}g · ${r.widthMm}mm · 余 ${r.lengthM}m / ${r.weightKg}kg · ${r.status} · ${r.grade}`"
            @click="selectedRollId = r.id"
          >
            <span class="roll-inner">{{ r.lengthM }}m</span>
          </div>
          <div v-if="!(rollsByMachine[m.id] || []).length" class="rail-empty">本台暂无纸卷</div>
        </div>
      </div>
    </div>

    <div class="panel" v-if="selected">
      <div class="panel-head">
        <span class="panel-title">选中母卷 · {{ selected.rollNo }}</span>
        <span class="panel-desc">
          {{ selected.grammageGsm }}g/m² · 幅宽 {{ selected.widthMm }}mm · 余长 {{ selected.lengthM }}m ·
          余重 {{ selected.weightKg }}kg · {{ selected.grade }} · {{ selected.status }}
          <template v-if="orders.find(o => o.assignedRoll && o.assignedRoll.id === selected.id && o.status === '切裁中')">
            ｜已排产给 {{ orders.find(o => o.assignedRoll && o.assignedRoll.id === selected.id && o.status === '切裁中').orderNo }}
          </template>
        </span>
      </div>

      <div class="op-row">
        <button class="act primary" :disabled="selected.status !== '待检'" @click="inspect('可用')">检验合格</button>
        <button class="act" :disabled="selected.status !== '待检'" @click="inspect('报废')">判废</button>
      </div>
      <div class="note">检验只针对「待检」卷；切裁领料必须通过下方订单闭环登记，扣减后余长不足 200m 会自动判为已切完。</div>

      <div class="issue-wrap">
        <CuttingIssuePanel
          :key="`roll-issue-${selected.id}`"
          mode="roll"
          :roll="selected"
          :orders="orders"
          @success="onIssueSuccess"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.rail { margin-bottom: 12px; }

.rail-head {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 10px;
  background: var(--primary-wash);
  border: 1px solid #cde6e2;
  border-radius: 8px 8px 0 0;
}
.rail-name { font-weight: 800; color: var(--primary-deep); }
.rail-code { font-size: 11px; color: var(--muted); }
.rail-chip {
  font-size: 11px; padding: 1px 8px; border-radius: 9px;
  background: #fff; border: 1px solid #cde6e2; color: var(--primary-soft);
}
.rail-count { margin-left: auto; font-size: 12px; font-weight: 800; color: var(--primary-deep); }

.rail-track {
  display: flex; flex-wrap: wrap; gap: 6px;
  padding: 10px;
  border: 1px solid var(--line);
  border-top: none;
  border-radius: 0 0 8px 8px;
  background: #fff;
  min-height: 52px;
}

.roll-bar {
  height: 32px;
  border-radius: 6px;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-size: 12px; font-weight: 700;
  border: 2px solid transparent;
  transition: transform 0.12s, box-shadow 0.12s;
  min-width: 58px;
}
.roll-bar:hover { transform: translateY(-2px); box-shadow: 0 4px 10px rgba(0,0,0,0.15); }
.roll-bar.sel { border-color: var(--primary-deep); box-shadow: 0 0 0 2px var(--primary-wash); }
.roll-bar.done { opacity: 0.5; }
.roll-bar.g-一等 { background: linear-gradient(120deg, #00695c, #00897b); }
.roll-bar.g-二等 { background: linear-gradient(120deg, #558b2f, #7cb342); }
.roll-bar.g-等外 { background: linear-gradient(120deg, #8d6e63, #a1887f); }
.roll-inner { white-space: nowrap; }

.rail-empty { font-size: 12px; color: var(--muted); padding: 8px 4px; }

.op-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.op-label { font-size: 13px; color: var(--muted); }
.num { width: 92px; padding: 6px 8px; border: 1px solid var(--line); border-radius: 6px; }
.act {
  padding: 7px 14px; border-radius: 7px; cursor: pointer; font-size: 13px;
  border: 1px solid var(--line); background: #fff; color: var(--ink);
}
.act:hover { border-color: var(--primary-soft); color: var(--primary-deep); }
.act.primary { background: var(--primary); border-color: var(--primary); color: #fff; font-weight: 700; }
.act:disabled { opacity: 0.45; cursor: not-allowed; }
.note { font-size: 12px; color: var(--muted); margin-top: 8px; }
.issue-wrap { margin-top: 12px; }
</style>
