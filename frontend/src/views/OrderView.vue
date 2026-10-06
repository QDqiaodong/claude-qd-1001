<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from '../router'
import api from '../api'
import CuttingIssuePanel from '../components/CuttingIssuePanel.vue'

// ------------------------------------------------------------------
// 页面操作范式：进度环 + 母卷匹配清单打勾。
// 每张订单是一枚环形进度（完成率）卡片，选中后右侧列出所有候选母卷，
// 每一条右侧直接给出「✓ 可上机」或「✗ 幅宽不足 / 定量偏差 / 状态不符」的打勾结论
// （结论由后端校验接口算，不是前端猜的）；勾中的母卷点一下就排产上机。
// ------------------------------------------------------------------

// 本仓 main.js 不安装 router 插件，直接读 router 实例上的当前路由
const routeQuery = computed(() => router.currentRoute.value.query || {})
const orders = ref([])
const rolls = ref([])
const error = ref('')
const tip = ref('')
const selectedId = ref(null)
const verdicts = ref({})
const checking = ref(false)

const selected = computed(() => orders.value.find((o) => o.id === selectedId.value) || null)

// 候选母卷：按订单目标定量筛选
const candidates = computed(() => {
  if (!selected.value) return []
  return rolls.value.filter(
    (r) => Math.abs(r.grammageGsm - selected.value.targetGrammage) <= 12
  )
})

const overdue = computed(() =>
  orders.value.filter((o) => o.daysToDeadline !== null && o.daysToDeadline < 0).length
)

async function load() {
  error.value = ''
  try {
    const q = routeQuery.value.q
    orders.value = await api.listOrders(q ? { status: q } : {})
    if (!orders.value.length && q) orders.value = await api.listOrders()
    rolls.value = await api.listRolls()
    if (!selectedId.value && orders.value.length) selectedId.value = orders.value[0].id
    await runCheck()
  } catch (e) {
    error.value = e.message
  }
}

// 逐个候选母卷问后端校验结论
async function runCheck() {
  if (!selected.value) { verdicts.value = {}; return }
  checking.value = true
  const out = {}
  for (const r of candidates.value) {
    try {
      const v = await api.checkRoll(selected.value.id, r.rollNo)
      out[r.rollNo] = v
    } catch (e) {
      out[r.rollNo] = { ok: false, problems: [e.message] }
    }
  }
  verdicts.value = out
  checking.value = false
}

async function assign(rollNo) {
  if (!selected.value) return
  error.value = ''; tip.value = ''
  try {
    await api.assignRoll(selected.value.id, rollNo)
    tip.value = `订单 ${selected.value.orderNo} 已排产，母卷 ${rollNo} 上机`
    await load()
  } catch (e) { error.value = e.message }
}

async function transfer(target) {
  if (!selected.value) return
  error.value = ''; tip.value = ''
  try {
    await api.transferOrder(selected.value.id, target)
    tip.value = `订单 ${selected.value.orderNo} 已转为「${target}」`
    await load()
  } catch (e) { error.value = e.message }
}

async function onIssueSuccess() {
  await load()
}

function ringStyle(rate) {
  const deg = Math.min(360, (Number(rate) / 100) * 360)
  return { background: `conic-gradient(var(--primary) ${deg}deg, #e6eeec ${deg}deg)` }
}

watch(() => routeQuery.value.q, () => load())
watch(selectedId, () => runCheck())
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">切纸工段 · 订单与排产</span>
        <span class="panel-desc">环内是完成率；选中订单后右侧母卷清单会给出可否上机的打勾结论</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">订单</div><div class="v">{{ orders.length }}</div></div>
        <div class="stat"><div class="k">切裁中</div><div class="v">{{ orders.filter(o => o.status === '切裁中').length }}</div></div>
        <div class="stat"><div class="k">待排产</div><div class="v">{{ orders.filter(o => o.status === '待排产').length }}</div></div>
        <div class="stat"><div class="k">已逾期</div><div class="v">{{ overdue }}</div></div>
      </div>

      <div class="order-grid">
        <div
          v-for="o in orders"
          :key="o.id"
          class="order-card"
          :class="{ on: o.id === selectedId }"
          @click="selectedId = o.id"
        >
          <div class="ring" :style="ringStyle(o.progressRate)">
            <div class="ring-inner">
              <div class="ring-v">{{ o.progressRate }}%</div>
              <div class="ring-k">完成</div>
            </div>
          </div>
          <div class="order-info">
            <div class="order-no">{{ o.orderNo }}</div>
            <div class="order-cust">{{ o.customerName }}</div>
            <div class="order-spec">{{ o.targetGrammage }}g · {{ o.targetWidthMm }}mm · {{ o.targetWeightKg }}kg</div>
            <div class="order-meta">
              <span class="tag">{{ o.status }}</span>
              <span v-if="o.assignedRoll" class="roll-tag">排产 {{ o.assignedRoll.rollNo }}</span>
              <span class="gap">欠 {{ o.gapWeightKg }}kg</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="panel" v-if="selected">
      <div class="panel-head">
        <span class="panel-title">母卷校验 · {{ selected.orderNo }}</span>
        <span class="panel-desc">
          目标 {{ selected.targetGrammage }}g / {{ selected.targetWidthMm }}mm
          <template v-if="selected.assignedRoll">
            ｜已排产 {{ selected.assignedRoll.rollNo }}：余 {{ selected.assignedRoll.lengthM }}m /
            {{ selected.assignedRoll.weightKg }}kg（{{ selected.assignedRoll.status }}）
          </template>
        </span>
        <span v-if="checking" class="checking">校验中…</span>
      </div>

      <div class="roll-check">
        <div
          v-for="r in candidates"
          :key="r.rollNo"
          class="check-row"
          :class="{ good: verdicts[r.rollNo] && verdicts[r.rollNo].ok, bad: verdicts[r.rollNo] && !verdicts[r.rollNo].ok }"
        >
          <span class="tick">{{ verdicts[r.rollNo] ? (verdicts[r.rollNo].ok ? '✓' : '✗') : '…' }}</span>
          <span class="rno">{{ r.rollNo }}</span>
          <span class="rspec">{{ r.grammageGsm }}g · {{ r.widthMm }}mm · 余 {{ r.lengthM }}m</span>
          <span class="rstate">{{ r.status }}</span>
          <span class="rwhy">
            {{ verdicts[r.rollNo] && verdicts[r.rollNo].problems && verdicts[r.rollNo].problems.length
               ? verdicts[r.rollNo].problems.join('；') : '可上机' }}
          </span>
          <button
            class="act sm"
            :disabled="!verdicts[r.rollNo] || !verdicts[r.rollNo].ok || selected.status !== '待排产'"
            @click="assign(r.rollNo)"
          >排产</button>
        </div>
        <div v-if="!candidates.length" class="empty">没有定量接近的候选母卷</div>
      </div>

      <div class="op-row">
        <button class="act primary" :disabled="selected.status !== '待排产'" @click="transfer('切裁中')">推进到切裁中</button>
        <button class="act" :disabled="selected.status !== '切裁中'" @click="transfer('已完成')">标记已完成</button>
        <button class="act" @click="transfer('已取消')">取消订单</button>
      </div>

      <CuttingIssuePanel
        v-if="selected.status === '切裁中' && selected.assignedRoll"
        :key="`order-issue-${selected.id}`"
        mode="order"
        :order="selected"
        @success="onIssueSuccess"
      />
    </div>
  </div>
</template>

<style scoped>
.order-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 10px; }

.order-card {
  display: flex; gap: 12px; align-items: center;
  padding: 12px; border: 1px solid var(--line); border-radius: 10px;
  cursor: pointer; background: #fff;
}
.order-card:hover { border-color: var(--primary-soft); }
.order-card.on { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-wash); }

.ring { width: 66px; height: 66px; border-radius: 50%; display: flex; align-items: center; justify-content: center; flex: none; }
.ring-inner {
  width: 50px; height: 50px; border-radius: 50%; background: #fff;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
}
.ring-v { font-size: 14px; font-weight: 800; color: var(--primary-deep); }
.ring-k { font-size: 10px; color: var(--muted); }

.order-no { font-weight: 800; color: var(--primary-deep); }
.order-cust { font-size: 12px; color: var(--ink); margin-top: 1px; }
.order-spec { font-size: 12px; color: var(--muted); margin-top: 2px; }
.order-meta { margin-top: 5px; display: flex; gap: 8px; align-items: center; }
.tag { font-size: 11px; padding: 1px 8px; border-radius: 9px; background: var(--primary-wash); color: var(--primary-deep); font-weight: 700; }
.roll-tag { font-size: 11px; padding: 1px 8px; border-radius: 9px; background: #eef5ff; color: #1565c0; font-weight: 700; }
.gap { font-size: 11px; color: #c62828; }

.checking { margin-left: auto; font-size: 12px; color: var(--muted); }

.roll-check { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
.check-row {
  display: grid; grid-template-columns: 26px 110px 1fr 70px 1fr 70px;
  align-items: center; gap: 10px;
  padding: 8px 10px; border: 1px solid var(--line); border-radius: 8px; font-size: 13px;
}
.check-row.good { border-color: #a5d6a7; background: #f1f8f2; }
.check-row.bad { border-color: #efc9c5; background: #fdf3f2; }
.tick { font-weight: 800; text-align: center; }
.check-row.good .tick { color: #2e7d32; }
.check-row.bad .tick { color: #c62828; }
.rno { font-weight: 700; color: var(--primary-deep); }
.rspec, .rstate { color: var(--muted); font-size: 12px; }
.rwhy { font-size: 12px; color: var(--ink); }

.empty { font-size: 13px; color: var(--muted); padding: 10px; }
.op-row { display: flex; gap: 8px; flex-wrap: wrap; }
.act {
  padding: 7px 14px; border-radius: 7px; cursor: pointer; font-size: 13px;
  border: 1px solid var(--line); background: #fff; color: var(--ink);
}
.act.sm { padding: 4px 10px; font-size: 12px; }
.act:hover { border-color: var(--primary-soft); color: var(--primary-deep); }
.act.primary { background: var(--primary); border-color: var(--primary); color: #fff; font-weight: 700; }
.act:disabled { opacity: 0.45; cursor: not-allowed; }
</style>
